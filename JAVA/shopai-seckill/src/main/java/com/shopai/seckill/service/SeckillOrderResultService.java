package com.shopai.seckill.service;

import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.seckill.constant.SeckillRecordStatus;
import com.shopai.seckill.dto.SeckillResultVO;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.entity.SeckillOrder;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillOrderMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀成单结果处理服务
 * <p>
 * 订单服务创建完订单后，会回调 {@code POST /api/seckill/internal/order-created}
 * （见 {@code SeckillInternalController}），由本服务完成落账：
 * <ol>
 *   <li>抢购记录 pending → success，回填订单ID/订单号；</li>
 *   <li>写 {@code seckill_order} 流水；</li>
 *   <li>库存 locked → sold；</li>
 *   <li>活动与 SKU 的已售数量累加；</li>
 *   <li>本地消息置 done；</li>
 *   <li>刷新给前端轮询的 Redis 结果。</li>
 * </ol>
 * <p>
 * <b>幂等</b>：入口用 {@code markSuccess} 的返回值判断是否首次处理。
 * {@code UPDATE ... WHERE status='pending'} 返回 0 就说明这笔已经处理过了，
 * 直接返回、不再重复累加销量与写流水 —— 消息可能被投递多次，
 * 少了这道判断，销量会被重复计算。
 */
@Service
public class SeckillOrderResultService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderResultService.class);

    @Autowired
    private SeckillRecordMapper recordMapper;

    @Autowired
    private SeckillOrderMapper orderMapper;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillRedisService redisService;

    @Autowired
    private SeckillMessageService messageService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 处理「订单已创建」回调（幂等）。
     *
     * @param result 订单服务返回的下单结果
     * @return 处理结果描述
     */
    @Transactional
    public String onOrderCreated(SeckillOrderResult result) {
        if (result == null || result.getOrderId() == null) {
            return "订单信息不完整";
        }
        Long recordId = parseRecordId(result.getOrderNo());
        if (recordId == null) {
            log.error("无法从订单号解析抢购记录ID（订单号规则应为 SK+recordId）, orderNo={}",
                    result.getOrderNo());
            return "订单号格式不符合秒杀规则";
        }

        SeckillRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            log.error("秒杀抢购记录不存在, recordId={}, orderNo={}", recordId, result.getOrderNo());
            return "抢购记录不存在";
        }

        // 幂等闸门：只有还在 pending 才继续后面的落账动作
        int rows = recordMapper.markSuccess(recordId, result.getOrderId(), result.getOrderNo());
        if (rows == 0) {
            log.info("秒杀记录已处理过，跳过重复落账, recordId={}, orderNo={}",
                    recordId, result.getOrderNo());
            return "已处理（幂等）";
        }

        // 写秒杀订单流水（真实订单在订单服务，这里只是秒杀维度视图）
        writeOrderFlow(record, result);

        // 库存：锁定 → 已售
        stockMapper.confirmSold(record.getStockId(), record.getQuantity());

        // 活动与SKU销量累加（数据库侧自增，并发安全）
        activityMapper.increaseSoldCount(record.getActivityId(), record.getQuantity());

        // 本地消息置完成，重投任务不再捞它
        SeckillMessage message = messageService.getByMsgId(String.valueOf(recordId));
        if (message != null) {
            messageService.markDone(message.getId());
        }

        // 刷新前端轮询结果
        cacheSuccess(record, result);

        log.info("秒杀成单落账完成, recordId={}, orderId={}, orderNo={}, userId={}, qty={}",
                recordId, result.getOrderId(), result.getOrderNo(), record.getUserId(), record.getQuantity());
        return "落账成功";
    }

    /**
     * 写秒杀订单流水。
     * <p>
     * 用「先查后插」而不是直接 insert：{@code uk_record} 唯一索引会挡住重复，
     * 但直接 insert 撞索引会抛异常打断整个落账流程。查一下更平滑 ——
     * 反正重复落账已在上面被幂等闸门挡住了，这里只是防御性处理。
     */
    private void writeOrderFlow(SeckillRecord record, SeckillOrderResult result) {
        SeckillOrder exist = orderMapper.selectByRecordId(record.getId());
        if (exist != null) {
            return;
        }
        SeckillOrder flow = new SeckillOrder();
        flow.setActivityId(record.getActivityId());
        flow.setStockId(record.getStockId());
        flow.setRecordId(record.getId());
        flow.setUserId(record.getUserId());
        flow.setCustomerId(record.getCustomerId());
        flow.setSkuId(record.getSkuId());
        flow.setQuantity(record.getQuantity());
        flow.setSeckillPrice(record.getSeckillPrice());
        flow.setTotalAmount(record.getSeckillPrice()
                .multiply(BigDecimal.valueOf(record.getQuantity())));
        flow.setOrderId(result.getOrderId());
        flow.setOrderNo(result.getOrderNo());
        flow.setStatus("created");
        flow.setDeleted(0);
        // productId / merchantId 从库存额度上取（那里冗余了，避免再调商品服务）
        var stock = stockMapper.selectById(record.getStockId());
        if (stock != null) {
            flow.setProductId(stock.getProductId());
            flow.setMerchantId(stock.getMerchantId());
        } else if (result.getMerchantId() != null) {
            flow.setMerchantId(result.getMerchantId());
        }
        orderMapper.insert(flow);
    }

    /**
     * 更新 Redis 里的抢购结果，供前端轮询拿到订单号。
     */
    private void cacheSuccess(SeckillRecord record, SeckillOrderResult result) {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus(SeckillRecordStatus.SUCCESS);
        vo.setActivityId(record.getActivityId());
        vo.setRecordId(record.getId());
        vo.setOrderId(result.getOrderId());
        vo.setOrderNo(result.getOrderNo());
        vo.setSeckillPrice(record.getSeckillPrice());
        vo.setQuantity(record.getQuantity());
        vo.setMessage("下单成功，请尽快完成支付");
        vo.setServerTime(LocalDateTime.now());
        try {
            redisService.saveResult(record.getActivityId(), record.getUserId(),
                    objectMapper.writeValueAsString(vo));
        } catch (Exception e) {
            // 写缓存失败不影响落账 —— 用户仍可从 DB 查到记录
            log.warn("成单结果写缓存失败, recordId={}", record.getId(), e);
        }
    }

    /**
     * 从订单号解析抢购记录ID（订单号规则 {@code SK + recordId}）。
     *
     * @return 记录ID；格式不符返回 null
     */
    private Long parseRecordId(String orderNo) {
        if (orderNo == null || !orderNo.startsWith("SK")) {
            return null;
        }
        try {
            return Long.valueOf(orderNo.substring(2));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

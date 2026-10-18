package com.shopai.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.seckill.config.SeckillProperties;
import com.shopai.seckill.constant.SeckillRecordStatus;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.mapper.SeckillMessageMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.common.dto.internal.seckill.SeckillOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 抢购记录 + 本地消息的写入器
 * <p>
 * <b>为什么单独抽一个 Bean，而不是写在 SeckillBuyService 里</b>：
 * 这两条写操作<b>必须在同一个数据库事务里</b>（见下），而 Spring 的
 * {@code @Transactional} 是基于代理的 —— 同一个类内部的方法自调用不经过代理，
 * 注解会<b>静默失效</b>，根本不会开启事务。这是最容易埋雷的一类错误：
 * 代码看起来有事务，实际没有，直到线上出现「记录写了、消息没写」才暴露。
 * 抽成独立 Bean 后由 SeckillBuyService 注入调用，调用会经过代理，事务真正生效。
 * <p>
 * <b>为什么两条写操作必须同事务</b>：
 * <ul>
 *   <li>只写记录不写消息 → 库存扣了，但没有任何人去创建订单，用户永远等不到；</li>
 *   <li>只写消息不写记录 → 丢失了幂等依据（消息的 msgId 就是记录ID），
 *       重复投递会创建出多笔订单。</li>
 * </ul>
 * 注意这里直接使用 {@link SeckillMessageMapper} 而不是
 * {@code SeckillMessageService.createPending}：后者也带 {@code @Transactional}，
 * 在本事务内调用会形成嵌套事务语义，一旦它回滚而记录已提交，
 * 又会出现上面的「只写记录不写消息」。两条插入必须在<b>同一个</b>事务边界内，
 * 因此这里统一由本类的 {@code @Transactional} 方法管控。
 */
@Component
public class SeckillRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(SeckillRecordWriter.class);

    @Autowired
    private SeckillRecordMapper recordMapper;

    @Autowired
    private SeckillMessageMapper messageMapper;

    @Autowired
    private SeckillProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 在同一事务内写入抢购记录与待投递的本地消息。
     *
     * @param activity   活动
     * @param stock      秒杀库存额度
     * @param userId     用户ID
     * @param customerId 客户ID（可为 null，稍后由重试任务补解析）
     * @param quantity   数量
     * @param addressId  收货地址ID（可为 null）
     * @return 落库后的抢购记录（含 ID，用作 msgId 与订单号来源）
     * @throws org.springframework.dao.DuplicateKeyException 命中「一人一单」唯一索引
     */
    @Transactional
    public SeckillRecord write(SeckillActivity activity, SeckillStock stock,
                               Long userId, Long customerId, int quantity, Long addressId) {
        SeckillRecord record = new SeckillRecord();
        record.setActivityId(activity.getId());
        record.setStockId(stock.getId());
        record.setSkuId(stock.getSkuId());
        record.setUserId(userId);
        record.setCustomerId(customerId);
        record.setQuantity(quantity);
        // 成交价在抢购这一刻就固定下来：活动期间即使商家改了价，
        // 已抢到的用户仍按下单时的价格成交
        record.setSeckillPrice(stock.getSeckillPrice());
        record.setStatus(SeckillRecordStatus.PENDING);
        // 唯一索引 (activity_id, user_id) 会挡住「一人两单」，
        // 冲突时抛 DuplicateKeyException，由调用方转换为友好提示
        recordMapper.insert(record);

        // 组装要投递的消息。msgId 用 record.id，从而订单号（SK+msgId）
        // 在重复投递时保持不变，订单服务据此幂等。
        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setMsgId(String.valueOf(record.getId()));
        msg.setActivityId(activity.getId());
        msg.setStockId(stock.getId());
        msg.setRecordId(record.getId());
        msg.setUserId(userId);
        msg.setCustomerId(customerId);
        msg.setSkuId(stock.getSkuId());
        msg.setProductId(stock.getProductId());
        msg.setMerchantId(stock.getMerchantId());
        msg.setQuantity(quantity);
        msg.setSeckillPrice(stock.getSeckillPrice());
        msg.setAddressId(addressId);

        SeckillMessage entity = new SeckillMessage();
        entity.setMsgId(msg.getMsgId());
        entity.setActivityId(activity.getId());
        entity.setStockId(stock.getId());
        entity.setRecordId(record.getId());
        entity.setUserId(userId);
        entity.setCustomerId(customerId);
        entity.setSkuId(stock.getSkuId());
        entity.setQuantity(quantity);
        // payload 存首发内容，重投时原样再发，保证排查时前后一致
        entity.setPayload(writeJson(msg));
        entity.setStatus(SeckillRecordStatus.MSG_PENDING);
        entity.setRetryCount(0);
        entity.setMaxRetry(properties.getMessageMaxRetry());
        entity.setNextRetryAt(LocalDateTime.now());
        // uk_msg_id 唯一索引：同一笔秒杀只会有一条待投递消息，天然防重
        messageMapper.insert(entity);

        log.debug("抢购记录与本地消息已落库, recordId={}, msgId={}", record.getId(), entity.getMsgId());
        return record;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            // 序列化失败必须抛出：payload 是重投的唯一依据，
            // 存一个 null 进去会让这条消息永远无法投递（只能靠超时回补库存）
            throw new RuntimeException("秒杀下单消息序列化失败", e);
        }
    }
}

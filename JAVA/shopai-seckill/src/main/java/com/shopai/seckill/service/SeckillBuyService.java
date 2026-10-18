package com.shopai.seckill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.seckill.config.SeckillProperties;
import com.shopai.seckill.constant.SeckillRecordStatus;
import com.shopai.seckill.constant.SeckillStatus;
import com.shopai.seckill.dto.SeckillResultVO;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import com.shopai.common.dto.internal.seckill.SeckillOrderMessage;
import com.shopai.seckill.mq.SeckillOrderMessagePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 秒杀抢购核心服务 —— <b>本服务最关键的一段逻辑</b>
 * <p>
 * <b>整体流程</b>（每步失败都有明确去向，不留中间态）：
 * <pre>
 *   0. 校验活动状态与时间窗          → 失败：直接拒绝，不碰任何存储
 *   1. Redis Lua 原子扣减 + 一人一单  → 失败：拒绝（库存不足/已抢过/超限购）
 *                                      ↓ 成功
 *   2. 本地事务：写抢购记录 + 本地消息 → 失败：回补 Redis 库存（很关键，见下）
 *                                      ↓ 成功
 *   3. 投递 MQ（尽力而为）            → 失败：不回滚！留给重投任务
 *                                      ↓
 *   4. 返回「排队中」，前端轮询结果
 * </pre>
 * <p>
 * <b>为什么第 3 步投递失败不回滚第 2 步</b>：本地消息表已经落库了，
 * 重投任务一定会把它投出去。如果这里回滚，反而会出现「消息表里没有、
 * Redis 库存已扣」的最坏情况。所以投递失败只记日志，交给重投。
 * <p>
 * <b>为什么第 2 步失败必须回补 Redis</b>：Redis 扣减已经生效了，
 * 如果本地事务失败而不回补，这部分库存就凭空消失了（Redis 里扣了、
 * 数据库里没记录，永远不会有人来还）。
 */
@Service
public class SeckillBuyService {

    private static final Logger log = LoggerFactory.getLogger(SeckillBuyService.class);

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private SeckillRecordMapper recordMapper;

    @Autowired
    private SeckillRedisService redisService;

    @Autowired
    private SeckillMessageService messageService;

    /**
     * 抢购记录 + 本地消息的写入器。
     * <p>
     * 刻意抽成独立 Bean 而不是本类的方法：Spring 的 {@code @Transactional}
     * 基于代理，同类内部自调用不生效，事务会静默丢失。
     */
    @Autowired
    private SeckillRecordWriter recordWriter;

    @Autowired
    private SeckillOrderMessagePublisher messagePublisher;

    @Autowired
    private SeckillProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 执行抢购。
     *
     * @param activityId 活动ID
     * @param stockId    seckill_stock.id
     * @param userId     用户ID（网关 X-User-Id）
     * @param customerId 客户ID（调用方已解析；为 null 时依然放行，由重试任务补解析）
     * @param quantity   购买数量
     * @param addressId  收货地址ID
     * @return 抢购结果（成功为 pending，失败带原因）
     */
    public SeckillResultVO buy(Long activityId, Long stockId, Long userId,
                               Long customerId, int quantity, Long addressId) {
        // ---------- 第 0 步：校验活动 ----------
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return fail(activityId, "活动不存在");
        }
        if (!SeckillStatus.canBuy(activity.getStatus())) {
            // 注意：不能只判断时间窗。活动可能被商家下线（offline），
            // 此时即使还在时间窗内也不能抢，否则「下线」形同虚设。
            return fail(activityId, "活动未开始或已结束");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!activity.inTimeWindow(now)) {
            return fail(activityId, "不在抢购时间内");
        }

        SeckillStock stock = stockMapper.selectById(stockId);
        if (stock == null || !activityId.equals(stock.getActivityId())) {
            return fail(activityId, "秒杀商品不存在");
        }

        // 每人限购：活动级与 SKU 级取更严格的
        int perUserLimit = resolvePerUserLimit(activity, stock);
        if (perUserLimit > 0 && quantity > perUserLimit) {
            return fail(activityId, "每人限购 " + perUserLimit + " 件");
        }

        // ---------- 第 1 步：Redis 原子扣减 + 限购判定（按 SKU 维度）----------
        // perUserLimit 是「每个商品每人限购几件」，不是整个活动的总量 ——
        // 同一活动买了 A 仍然可以买 B，各 SKU 独立计数。
        //
        // 同时把「已购集合」的 TTL 传下去：该 Hash 是首次抢购时懒创建的，
        // 预热阶段设 EXPIRE 会打空，因此只能在扣减脚本里补过期时间，
        // 否则它会永不过期（TTL = -1）一直常驻 Redis。
        long boughtTtl = SeckillRedisService.boughtSetTtlSeconds(activity.getEndTime());
        long deductResult = redisService.deduct(activityId, stockId, stock.getSkuId(),
                userId, quantity, perUserLimit, boughtTtl);
        if (deductResult < 0) {
            return handleDeductFailure(activityId, stock, userId, quantity, deductResult);
        }
        log.info("秒杀扣减成功, activityId={}, stockId={}, skuId={}, userId={}, qty={}, 剩余={}",
                activityId, stockId, stock.getSkuId(), userId, quantity, deductResult);

        // ---------- 第 2 步：本地事务写记录 + 本地消息 ----------
        SeckillRecord record;
        try {
            // 经由独立 Bean（SeckillRecordWriter）调用，事务才会真正生效。
            // 说明：不能把这段写在 SeckillBuyService 自己的方法里再加 @Transactional ——
            // 同类内部自调用不经过 Spring 代理，注解会静默失效。
            record = recordWriter.write(activity, stock, userId, customerId, quantity, addressId);
        } catch (DuplicateKeyException e) {
            // Redis 的限购判定漏了（Hash 过期被重建、主从切换丢数据等），
            // 由数据库唯一索引 (activity_id, user_id, sku_id) 兜住。
            // 这不是异常流程，是设计内的最后一道防线 ——
            // 给用户明确的「这个商品已买过」而不是「系统繁忙」。
            log.warn("同活动同商品限购唯一索引拦截抢购, activityId={}, skuId={}, userId={}",
                    activityId, stock.getSkuId(), userId);
            redisService.restore(activityId, stockId, stock.getSkuId(), userId, quantity);
            return fail(activityId, "该商品您已抢过，每个商品限购一次");
        } catch (Exception e) {
            // 关键：本地事务失败必须回补 Redis，否则这部分库存凭空消失
            log.error("秒杀本地事务失败，回补Redis库存, activityId={}, stockId={}, userId={}, qty={}",
                    activityId, stockId, userId, quantity, e);
            redisService.restore(activityId, stockId, stock.getSkuId(), userId, quantity);
            return fail(activityId, "系统繁忙，请重试");
        }

        // ---------- 第 3 步：投递 MQ（失败不回滚，交给重投任务） ----------
        trySendMessage(record);

        // ---------- 第 4 步：返回「排队中」 ----------
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus(SeckillRecordStatus.PENDING);
        vo.setActivityId(activityId);
        vo.setRecordId(record.getId());
        vo.setSeckillPrice(record.getSeckillPrice());
        vo.setQuantity(record.getQuantity());
        vo.setMessage("抢购成功，正在生成订单");
        vo.setServerTime(LocalDateTime.now());
        // 写进 Redis 供前端轮询（MQ 消费完成后再覆盖为 success/failed）
        cacheResult(vo, userId);
        return vo;
    }

    /**
     * 尽力投递消息。失败只记日志，由 {@code SeckillMessageRetryTask} 重投。
     */
    private void trySendMessage(SeckillRecord record) {
        SeckillMessage message = messageService.getByMsgId(String.valueOf(record.getId()));
        if (message == null) {
            // 理论上不可能（刚落库），但真出现说明数据有问题，必须吵醒
            log.error("本地消息落库后查不到，将由重投任务兜底, recordId={}", record.getId());
            return;
        }
        try {
            SeckillOrderMessage msg = readJson(message.getPayload(), SeckillOrderMessage.class);
            messagePublisher.send(msg);
            messageService.markSent(message.getId());
        } catch (Exception e) {
            log.error("秒杀下单消息首次投递失败，将由重投任务重投, msgId={}, reason={}",
                    message.getMsgId(), e.getMessage());
        }
    }

    /**
     * 处理 Redis 扣减失败的各种原因，返回对应的用户提示。
     */
    private SeckillResultVO handleDeductFailure(Long activityId, SeckillStock stock,
                                                Long userId, int quantity, long result) {
        if (result == SeckillRedisService.RESULT_KEY_MISSING) {
            // 未预热或已过期：降级走数据库扣减，保证活动不会因为「忘了预热」而完全不可用
            log.warn("Redis库存Key不存在，降级走数据库扣减, stockId={}", stock.getId());
            return fallbackToDatabase(activityId, stock, userId, quantity);
        }
        if (result == SeckillRedisService.RESULT_ALREADY_BOUGHT) {
            return fail(activityId, "您已参与过本活动，每人限购一次");
        }
        if (result == SeckillRedisService.RESULT_EXCEED_LIMIT) {
            return fail(activityId, "超过每人限购件数");
        }
        if (result == SeckillRedisService.RESULT_BAD_QTY) {
            return fail(activityId, "购买数量不合法");
        }
        // RESULT_NO_STOCK
        return fail(activityId, "商品已抢完");
    }

    /**
     * 降级路径：直接扣数据库库存。
     * <p>
     * 触发条件是「Redis 里没有这个库存 Key」。典型场景：活动开始前忘了预热、
     * Redis 重启导致数据丢失、Key 过期时间设置短了。
     * <p>
     * 这条路径并发能力差得多（走行锁串行），但<b>可用性比性能重要</b> ——
     * 没预热的活动也有人来抢，能成交比全部报错强。同时这里会<b>补一次预热</b>，
     * 让后续请求回到正常路径。
     *
     * @return 扣减结果（成功时返回 pending，并已写入本地消息）
     */
    private SeckillResultVO fallbackToDatabase(Long activityId, SeckillStock stock,
                                               Long userId, int quantity) {
        int rows = stockMapper.deductAvailable(stock.getId(), quantity);
        if (rows == 0) {
            return fail(activityId, "商品已抢完");
        }
        // 补预热：把当前数据库余量写入 Redis，让后续请求走正常路径。
        // 注意回读的是扣减后的余量，不能用扣减前的快照，否则会把刚扣掉的量"还"回去。
        SeckillStock latest = stockMapper.selectById(stock.getId());
        if (latest != null && latest.getAvailableStock() != null
                && latest.getAvailableStock() > 0) {
            redisService.preheatStock(activityId, stock.getId(), latest.getAvailableStock());
        }
        log.warn("秒杀降级扣减成功（走数据库）, activityId={}, stockId={}, userId={}, qty={}",
                activityId, stock.getId(), userId, quantity);
        // 降级路径也要写记录 + 本地消息，否则库存扣了没人建订单。
        // 同样经独立 Bean 调用以获得真实事务；且 Redis 已回补，DB 侧也要回补。
        SeckillRecord record;
        try {
            SeckillActivity activity = activityMapper.selectById(activityId);
            record = recordWriter.write(activity, stock, userId, null, quantity, null);
        } catch (Exception e) {
            log.error("降级路径本地事务失败，回补数据库库存, stockId={}, qty={}",
                    stock.getId(), quantity, e);
            stockMapper.restoreAvailable(stock.getId(), quantity);
            return fail(activityId, "系统繁忙，请重试");
        }
        trySendMessage(record);

        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus(SeckillRecordStatus.PENDING);
        vo.setActivityId(activityId);
        vo.setRecordId(record.getId());
        vo.setSeckillPrice(record.getSeckillPrice());
        vo.setQuantity(record.getQuantity());
        vo.setMessage("抢购成功，正在生成订单");
        vo.setServerTime(LocalDateTime.now());
        cacheResult(vo, userId);
        return vo;
    }

    /**
     * 构造失败结果。
     */
    private SeckillResultVO fail(Long activityId, String message) {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus(SeckillRecordStatus.FAILED);
        vo.setActivityId(activityId);
        vo.setMessage(message);
        vo.setServerTime(LocalDateTime.now());
        return vo;
    }

    /**
     * 活动级与 SKU 级限购取更严格的那个。
     * <p>
     * 为什么不直接取 SKU 级：SKU 级的 per_user_limit 是从活动带过来的冗余值，
     * 商家单独调过某个 SKU 之后两者就可能不一致。取更严格的保证
     * 「无论是哪个限制都不会被突破」。
     *
     * @return 生效的限购件数；0 表示不限
     */
    private int resolvePerUserLimit(SeckillActivity activity, SeckillStock stock) {
        int atActivity = activity.getPerUserLimit() == null ? 0 : activity.getPerUserLimit();
        int atStock = stock.getPerUserLimit() == null ? 0 : stock.getPerUserLimit();
        if (atActivity <= 0) {
            return Math.max(atStock, 0);
        }
        if (atStock <= 0) {
            return atActivity;
        }
        return Math.min(atActivity, atStock);
    }

    /**
     * 把「排队中」结果写进 Redis 供前端轮询。
     */
    private void cacheResult(SeckillResultVO vo, Long userId) {
        try {
            redisService.saveResult(vo.getActivityId(), userId, writeJson(vo));
        } catch (Exception e) {
            // 写缓存失败不影响抢购本身 —— 用户仍可查询 DB 记录拿到结果
            log.warn("抢购结果写缓存失败, activityId={}, userId={}", vo.getActivityId(), userId, e);
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.error("序列化失败", e);
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("反序列化失败, json={}", json, e);
            return null;
        }
    }
}

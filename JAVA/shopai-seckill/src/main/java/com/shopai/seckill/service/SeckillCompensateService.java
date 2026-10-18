package com.shopai.seckill.service;

import com.shopai.seckill.config.SeckillProperties;
import com.shopai.seckill.constant.SeckillRecordStatus;
import com.shopai.seckill.constant.SeckillStatus;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import com.shopai.common.dto.internal.seckill.SeckillOrderMessage;
import com.shopai.seckill.mq.SeckillOrderMessagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀补偿与调度服务
 * <p>
 * 承担四件事，全部由 {@code com.shopai.seckill.task} 下的定时任务驱动：
 * <ol>
 *   <li><b>预热</b>：活动开始前把库存刷进 Redis；</li>
 *   <li><b>状态流转</b>：pending → running → ended；</li>
 *   <li><b>消息重投</b>：把本地消息表里没投成功的消息再投一遍；</li>
 *   <li><b>超时回补</b>：扣了库存但一直没成单的，把库存还回去。</li>
 * </ol>
 * <p>
 * <b>第 3、4 点的配合是本服务一致性的核心</b>，两者有个必须遵守的时序约束：
 * 超时回补的判定时间（{@code seckill.order-timeout-seconds}）必须明显大于
 * 「消息最大重试次数 × 退避间隔之和」。否则会出现最坏情况 ——
 * 消息还在正常重试中，超时任务先把库存回补了；之后消息终于投递成功、
 * 订单建出来了，而库存已经被还回去，等于超卖。
 * 调整 {@code message-max-retry} 时务必同步复核 {@code order-timeout-seconds}。
 */
@Service
public class SeckillCompensateService {

    private static final Logger log = LoggerFactory.getLogger(SeckillCompensateService.class);

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

    @Autowired
    private SeckillOrderMessagePublisher messagePublisher;

    @Autowired
    private SeckillProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 1. 预热 ====================

    /**
     * 扫描并预热「即将开始」的活动。
     * <p>
     * 预热窗口由 {@code seckill.preheat-ahead-seconds} 控制（默认提前 10 分钟）。
     * 幂等：只处理 {@code preheated = 0} 的库存记录，重复执行不会覆盖已扣减的库存
     * （这点很重要 —— 覆盖就等于把已经卖出去的库存"还"回去，直接超卖）。
     *
     * @return 本次预热的 SKU 数
     */
    @Transactional
    public int preheatDueActivities() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusSeconds(properties.getPreheatAheadSeconds());
        List<SeckillActivity> activities = activityMapper.selectNeedPreheat(now, until);
        int count = 0;
        for (SeckillActivity activity : activities) {
            count += preheatActivity(activity);
        }
        if (count > 0) {
            log.info("秒杀预热完成, 活动数={}, SKU数={}", activities.size(), count);
        }
        return count;
    }

    /**
     * 预热单个活动下所有未预热的 SKU。
     *
     * @param activity 活动
     * @return 预热的 SKU 数
     */
    @Transactional
    public int preheatActivity(SeckillActivity activity) {
        List<SeckillStock> stocks = stockMapper.selectNotPreheated(activity.getId());
        int count = 0;
        for (SeckillStock stock : stocks) {
            int available = stock.getAvailableStock() == null ? 0 : stock.getAvailableStock();
            redisService.preheatStock(activity.getId(), stock.getId(), available);
            stockMapper.markPreheated(stock.getId(), 1);
            count++;
        }
        return count;
    }

    // ==================== 2. 活动状态流转 ====================

    /**
     * 把到点的 pending 活动置为 running。
     *
     * @return 流转的活动数
     */
    @Transactional
    public int startDueActivities() {
        List<SeckillActivity> activities = activityMapper.selectShouldStart(LocalDateTime.now());
        for (SeckillActivity activity : activities) {
            activity.setStatus(SeckillStatus.RUNNING);
            activityMapper.updateById(activity);
            log.info("秒杀活动已开始, activityId={}, title={}", activity.getId(), activity.getTitle());
        }
        return activities.size();
    }

    /**
     * 把到点的 running 活动置为 ended，并清理 Redis 库存。
     * <p>
     * 结束后清 Redis：不清的话这些 Key 要等 24 小时 TTL 才回收，
     * 大量活动累积会白占内存；而且清掉后即使有人拿着旧链接来抢，
     * 扣减脚本也会因为 Key 不存在而拒绝。
     *
     * @return 流转的活动数
     */
    @Transactional
    public int endDueActivities() {
        List<SeckillActivity> activities = activityMapper.selectShouldEnd(LocalDateTime.now());
        for (SeckillActivity activity : activities) {
            activity.setStatus(SeckillStatus.ENDED);
            activityMapper.updateById(activity);
            List<SeckillStock> stocks = stockMapper.selectByActivityId(activity.getId());
            for (SeckillStock s : stocks) {
                redisService.evictStock(activity.getId(), s.getId());
            }
            log.info("秒杀活动已结束, activityId={}, title={}, 清理{}个库存Key",
                    activity.getId(), activity.getTitle(), stocks.size());
        }
        return activities.size();
    }

    // ==================== 3. 消息重投 ====================

    /**
     * 把本地消息表里待投递/待重试的消息重新投出去。
     * <p>
     * 分两种情况处理：
     * <ul>
     *   <li><b>还有重试次数</b>：乐观重投。注意这里对 status=sent 的消息
     *       也会重投 —— 因为「投到 MQ 成功」不等于「订单创建成功」，
     *       消费者可能挂了。消费端靠 msgId 幂等，重投不会产生第二笔订单。</li>
     *   <li><b>重试次数耗尽</b>：判定这笔秒杀失败，<b>回补库存</b>并标记记录失败。
     *       这是「不会卡在中间态」的保证：要么成单，要么把库存还回去。</li>
     * </ul>
     *
     * @return 处理的消息数
     */
    public int retryPendingMessages() {
        List<SeckillMessage> messages = messageService.listNeedRetry();
        int handled = 0;
        for (SeckillMessage message : messages) {
            if (messageService.exhausted(message)) {
                giveUp(message);
            } else {
                republish(message);
            }
            handled++;
        }
        if (handled > 0) {
            log.info("秒杀消息补偿处理完成, 条数={}", handled);
        }
        return handled;
    }

    /**
     * 重新投递一条消息。
     * <p>
     * 反序列化失败（payload 损坏）也必须走 {@code markRetry} 计数：
     * 如果不计数就 return，这条消息会永远停在 pending、{@code next_retry_at}
     * 又已过期，于是每轮都被捞出来重试一次，既刷不出失败也到不了终态，
     * 变成永久空转。计数后才能正常耗尽次数并进入 {@link #giveUp} 回补库存。
     */
    private void republish(SeckillMessage message) {
        try {
            if (message.getPayload() == null || message.getPayload().isBlank()) {
                throw new IllegalStateException("消息体为空，无法重投");
            }
            SeckillOrderMessage msg = objectMapper.readValue(message.getPayload(), SeckillOrderMessage.class);
            messagePublisher.send(msg);
            messageService.markSent(message.getId());
        } catch (Exception e) {
            messageService.markRetry(message, e.getMessage());
        }
    }

    /**
     * 放弃一条消息：回补库存 + 标记记录失败 + 标记消息失败。
     * <p>
     * 顺序很关键：<b>先标记记录失败，只有标记成功（返回行数 &gt; 0）才回补库存</b>。
     * 因为 {@code markFailed} 带 {@code WHERE status='pending'} 条件，
     * 返回 0 说明这笔记录已经被别的流程改成 success 了 ——
     * 此时若还去回补库存，就会「订单已存在 + 库存被还回去」双重错误。
     */
    private void giveUp(SeckillMessage message) {
        int rows = recordMapper.markFailed(message.getRecordId(),
                "订单创建重试耗尽: " + message.getErrorMsg());
        if (rows == 0) {
            // 记录已不是 pending（多半已成功），说明消息只是状态没更新上来，不该回补
            log.info("记录已非待成单状态，跳过回补, recordId={}, messageId={}",
                    message.getRecordId(), message.getId());
            messageService.markDone(message.getId());
            return;
        }
        // 回补两处：Redis（用户可再次抢）与 DB（库存数字还原）
        // restore 需要 skuId —— 已购记录按「活动 + 用户 + SKU」计数，必须精确回退该 SKU
        redisService.restore(message.getActivityId(), message.getStockId(), message.getSkuId(),
                message.getUserId(), message.getQuantity());
        stockMapper.rollbackLocked(message.getStockId(), message.getQuantity());
        messageService.markFailed(message.getId(), "重试耗尽");
        log.error("秒杀成单失败，已回补库存, recordId={}, activityId={}, stockId={}, userId={}, qty={}",
                message.getRecordId(), message.getActivityId(), message.getStockId(),
                message.getUserId(), message.getQuantity());
    }

    // ==================== 4. 超时未成单回补 ====================

    /**
     * 扫描「扣了库存但迟迟没成单」的记录并回补。
     * <p>
     * 与消息重投的关系：正常情况下消息重投会先一步处理掉（它按 nextRetryAt 驱动）；
     * 本方法是兜底 —— 万一消息记录本身出了问题（payload 损坏、状态卡在
     * sent 但 retry_count 已达上限而未进 failed），仍有这里保证不会永远挂着。
     * <p>
     * <b>约定</b>：本方法的判定时间必须大于消息重试的总耗时（见类注释的时序约束）。
     *
     * @return 回补的记录数
     */
    @Transactional
    public int restoreTimeoutRecords() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(properties.getOrderTimeoutSeconds());
        List<SeckillRecord> records = recordMapper.selectTimeoutPending(deadline, properties.getTaskBatchSize());
        int restored = 0;
        for (SeckillRecord record : records) {
            // 同样先改状态再回补：只有仍在 pending 才允许回补
            int rows = recordMapper.markFailed(record.getId(), "超时未成单");
            if (rows == 0) {
                continue;
            }
            redisService.restore(record.getActivityId(), record.getStockId(), record.getSkuId(),
                    record.getUserId(), record.getQuantity());
            stockMapper.rollbackLocked(record.getStockId(), record.getQuantity());
            restored++;
            log.error("秒杀超时未成单，已回补库存, recordId={}, activityId={}, stockId={}, userId={}",
                    record.getId(), record.getActivityId(), record.getStockId(), record.getUserId());
        }
        return restored;
    }
}

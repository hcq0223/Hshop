package com.shopai.seckill.service;

import com.shopai.seckill.config.SeckillProperties;
import com.shopai.seckill.constant.SeckillRecordStatus;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.mapper.SeckillMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀本地消息服务
 * <p>
 * <b>解决的问题</b>：秒杀扣减在 Redis（无事务），订单创建在订单服务（远程），
 * 两者之间没有任何事务能同时覆盖。如果抢购接口里直接同步调订单服务，
 * 一旦调用失败就是「库存扣了、订单没有」，高峰期会成片出现。
 * <p>
 * <b>做法</b>：把「Redis 扣减」与「写一条待投递消息」串起来 ——
 * Redis 扣减成功后，在<b>同一个本地事务</b>里写 seckill_record + seckill_message。
 * 之后由 {@code SeckillMessageRetryTask} 不断重投这条消息，直到订单建出来。
 * <b>关键点</b>：只要消息落库了，这笔秒杀就一定会走向「成单」或
 * 「重试耗尽后回补库存」两个终态之一，不会卡在中间。
 * <p>
 * <b>幂等</b>：{@code msg_id} 上有唯一索引（= seckill_record.id），
 * 同一笔秒杀重复写消息会直接插入失败，天然防重。
 */
@Service
public class SeckillMessageService {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageService.class);

    /** 指数退避基数（秒）：第 n 次重试等待 = BASE * 2^(n-1)，上限见 MAX_BACKOFF_SECONDS */
    private static final long BACKOFF_BASE_SECONDS = 10L;

    /**
     * 退避上限（秒）。
     * <p>
     * 不设上限的话，重试 5 次后间隔会到几分钟，可能超过
     * {@code seckill.order-timeout-seconds} 而被超时任务先回补库存 ——
     * 之后消息再投递成功就会造成超卖。压住上限让重试节奏可控。
     */
    private static final long MAX_BACKOFF_SECONDS = 60L;

    @Autowired
    private SeckillMessageMapper messageMapper;

    @Autowired
    private SeckillProperties properties;

    /**
     * 写入待投递消息（必须与「写抢购记录」在同一个事务里）。
     *
     * @param message 消息（status 会被置为 pending）
     * @return 落库后的消息（含ID）
     */
    @Transactional
    public SeckillMessage createPending(SeckillMessage message) {
        message.setStatus(SeckillRecordStatus.MSG_PENDING);
        message.setRetryCount(0);
        message.setMaxRetry(properties.getMessageMaxRetry());
        message.setNextRetryAt(LocalDateTime.now());
        messageMapper.insert(message);
        log.debug("本地消息已落库, msgId={}, recordId={}", message.getMsgId(), message.getRecordId());
        return message;
    }

    /**
     * 捞取待投递 / 待重试的消息。
     *
     * @return 一批消息（可能为空）
     */
    public List<SeckillMessage> listNeedRetry() {
        return messageMapper.selectNeedRetry(LocalDateTime.now(), properties.getTaskBatchSize());
    }

    /**
     * 标记已成功投递到 MQ。
     * <p>
     * 投递成功不等于订单已创建，所以状态是 {@code sent} 而不是 {@code done}；
     * 订单创建成功的最终确认由订单服务回调（见
     * {@code SeckillInternalController#markOrderCreated}）来置 {@code done}。
     */
    public void markSent(Long messageId) {
        messageMapper.markSent(messageId);
    }

    /**
     * 记录一次投递失败，并按指数退避推迟下次重试。
     * <p>
     * 退避而不是固定间隔：订单服务短暂抖动时，固定 1 秒重试会在几秒内
     * 把 5 次重试次数耗光，然后就误判成失败了。拉开间隔能给下游恢复的时间。
     *
     * @param message 消息
     * @param reason  失败原因
     */
    public void markRetry(SeckillMessage message, String reason) {
        int nextRetryCount = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        long backoff = Math.min(BACKOFF_BASE_SECONDS * (1L << Math.min(nextRetryCount - 1, 10)),
                MAX_BACKOFF_SECONDS);
        LocalDateTime next = LocalDateTime.now().plusSeconds(backoff);
        messageMapper.markRetry(message.getId(), next, truncate(reason));
        log.warn("秒杀下单消息投递失败, 将重试, msgId={}, retryCount={}/{}, nextRetryAt={}, reason={}",
                message.getMsgId(), nextRetryCount, message.getMaxRetry(), next, truncate(reason));
    }

    /**
     * 判断某条消息是否已耗尽重试次数。
     *
     * @param message 消息
     */
    public boolean exhausted(SeckillMessage message) {
        int retry = message.getRetryCount() == null ? 0 : message.getRetryCount();
        int max = message.getMaxRetry() == null ? properties.getMessageMaxRetry() : message.getMaxRetry();
        return retry >= max;
    }

    /**
     * 标记消息流程完成（订单已创建）。
     */
    public void markDone(Long messageId) {
        messageMapper.markDone(messageId);
    }

    /**
     * 标记消息彻底失败（重试耗尽）。
     *
     * @param messageId 消息ID
     * @param reason    失败原因
     */
    public void markFailed(Long messageId, String reason) {
        messageMapper.markFailed(messageId, truncate(reason));
        log.error("秒杀下单消息重试耗尽，判定失败（库存将被回补）, messageId={}, reason={}",
                messageId, truncate(reason));
    }

    /**
     * 按 msgId 查询消息（订单服务回调时用来定位记录）。
     *
     * @param msgId 消息业务ID
     */
    public SeckillMessage getByMsgId(String msgId) {
        return messageMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillMessage>()
                        .eq(SeckillMessage::getMsgId, msgId));
    }

    /** error_msg 字段是 varchar(512)，超长会插入失败，统一截断 */
    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}

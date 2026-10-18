package com.shopai.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.seckill.entity.SeckillMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀本地消息 Mapper
 * <p>
 * 核心是 {@link #selectNeedRetry} + {@link #markRetry} 这一对：
 * 定时任务捞「到点该投递」的消息，投递失败就 {@code retry_count + 1}
 * 并推迟 {@code next_retry_at}（指数退避）。
 */
@Mapper
public interface SeckillMessageMapper extends BaseMapper<SeckillMessage> {

    /**
     * 捞取待投递 / 待重试的消息。
     * <p>
     * 条件：{@code status IN ('pending','sent') AND next_retry_at <= now AND retry_count < max_retry}。
     * <p>
     * 为什么把 'sent' 也算进来：投递到 MQ 成功不代表订单创建成功 ——
     * 消费者可能还没处理完。定时任务对 'sent' 的消息做「超时检查」，
     * 太久没变 'done' 就重新投递（消费端靠 msgId 幂等）。
     *
     * @param now   当前时间
     * @param limit 单批条数
     */
    List<SeckillMessage> selectNeedRetry(@Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    /**
     * 记录一次投递失败：重试次数 +1，并推迟下次重试时间。
     *
     * @param id          消息ID
     * @param nextRetryAt 下次重试时间
     * @param errorMsg    失败原因（截断后存入）
     * @return 影响行数
     */
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("errorMsg") String errorMsg);

    /**
     * 标记已投递到 MQ。
     */
    int markSent(@Param("id") Long id);

    /**
     * 标记流程完成（订单已创建）。
     */
    int markDone(@Param("id") Long id);

    /**
     * 标记彻底失败（重试耗尽）。
     */
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);
}

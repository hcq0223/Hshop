package com.shopai.seckill.task;

import com.shopai.seckill.service.SeckillCompensateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 秒杀定时任务
 * <p>
 * 四个任务共同保证「活动能按时开始、库存能按时预热、异常能自动收敛」。
 * 每个任务都<b>只做 DB 层面的幂等操作</b>（带状态条件的 UPDATE），
 * 因此多实例部署时即使几个实例同时触发，也不会重复处理。
 * <p>
 * 说明：当前用 Spring 自带的 {@code @Scheduled}，多实例下会「同时执行但结果幂等」。
 * 数据量上来之后建议换成分布式调度（XXL-JOB / ElasticJob），
 * 好处是只有一个实例真正执行、且能在控制台看到执行记录与手动重跑。
 */
@Component
public class SeckillScheduledTask {

    private static final Logger log = LoggerFactory.getLogger(SeckillScheduledTask.class);

    @Autowired
    private SeckillCompensateService compensateService;

    /**
     * 库存预热：每 30 秒扫一次，把「30 秒内进入预热窗口」的活动库存刷进 Redis。
     * <p>
     * <b>为什么不设 1 分钟以上</b>：预热窗口默认是开抢前 10 分钟。
     * 如果扫描间隔太长（比如 5 分钟），一次任务卡住就会错过预热窗口，
     * 用户进场时库存还没进 Redis（虽然会降级走数据库，但并发能力差很多）。
     * 30 秒的间隔足够密，单次扫描的量也很小（只捞即将开始的活动）。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 10_000L)
    public void preheatTask() {
        try {
            int count = compensateService.preheatDueActivities();
            if (count > 0) {
                log.info("[定时任务] 库存预热完成, SKU数={}", count);
            }
        } catch (Exception e) {
            // 定时任务绝不能把异常抛出去：未捕获的异常会让 Spring 的调度线程
            // 停止后续所有执行（fixedDelay 任务在抛异常后不再触发），
            // 表现为「预热突然再也不跑了」。所以这里必须吞掉。
            log.error("[定时任务] 库存预热异常", e);
        }
    }

    /**
     * 活动状态流转：每 10 秒推进一次 pending → running → ended。
     * <p>
     * 间隔比预热短：活动能否准时开抢是用户直接感知的，
     * 「到点了却还显示未开始」是明显的体验问题。
     * 同时它还要负责结束后清理 Redis 库存 Key。
     */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 5_000L)
    public void activityStatusTask() {
        try {
            int started = compensateService.startDueActivities();
            int ended = compensateService.endDueActivities();
            if (started > 0 || ended > 0) {
                log.info("[定时任务] 活动状态流转, 开始={}, 结束={}", started, ended);
            }
        } catch (Exception e) {
            log.error("[定时任务] 活动状态流转异常", e);
        }
    }

    /**
     * 消息重投：每 10 秒扫一次本地消息表，把没投成功/没确认的消息重投。
     * <p>
     * 这是「不会卡在中间态」的主要保障。间隔短一点让用户等待感更低 ——
     * 一条消息晚 10 秒投出去，用户就多等 10 秒才看到订单。
     * 实际重投时机由 {@code next_retry_at} 的指数退避控制，
     * 所以这个间隔只是「检查频率」，不会造成高频重试。
     */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void messageRetryTask() {
        try {
            int handled = compensateService.retryPendingMessages();
            if (handled > 0) {
                log.info("[定时任务] 消息重投处理, 条数={}", handled);
            }
        } catch (Exception e) {
            log.error("[定时任务] 消息重投异常", e);
        }
    }

    /**
     * 超时未成单回补：每分钟扫一次。
     * <p>
     * 频率最低是合理的：它处理的是「15 分钟都没成单」的兜底场景，
     * 早一分钟晚一分钟回补影响不大，而扫太勤会白白增加数据库压力。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void timeoutRestoreTask() {
        try {
            int restored = compensateService.restoreTimeoutRecords();
            if (restored > 0) {
                log.error("[定时任务] 超时未成单已回补库存, 条数={}", restored);
            }
        } catch (Exception e) {
            log.error("[定时任务] 超时回补异常", e);
        }
    }
}

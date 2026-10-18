package com.shopai.merchant.service;

import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 商户/资金缓存失效器
 * <p>
 * <b>为什么单独抽一个组件</b>：Merchant 实体上挂着三个资金字段
 * （{@code totalRevenue / availableBalance / frozenBalance}），而这些字段会随
 * 「申请提现 / 提现被拒退款 / 订单服务资金变更 / 周营收归零」等多个入口改变。
 * 这些入口分布在 {@link MerchantServiceImpl} 与 {@code MerchantInternalController} 两处，
 * 如果把「按 userId + merchantId 清三个缓存 key」的逻辑在每处各写一遍：
 * <ul>
 *   <li>很容易漏 —— 事实上改造前就有两处（拒绝提现退款、周营收归零）**一个缓存都没清**；</li>
 *   <li>而且 Controller 只拿到 merchantId，自己写还得额外查一次库拿 userId。</li>
 * </ul>
 * 抽成独立 Bean 后两个调用方都注入它（不会形成循环依赖：它只依赖 RedisService），
 * 资金变更只需调一个方法，不会再漏 key。
 * <p>
 * <b>需要清理的 key（缺一不可）</b>：
 * <ul>
 *   <li>{@code cache:merchant:{userId}} —— 写入方 {@code getMerchantById(userId)}，
 *       缓存整个 Merchant（含资金字段）；</li>
 *   <li>{@code cache:merchant:detail:{userId}} —— 写入方 {@code selectDetailByUserId(userId)}，
 *       同上，管理员视角的商户详情；</li>
 *   <li>{@code cache:merchant:public:{merchantId}} —— 写入方 {@code getMerchantPublicInfo(merchantId)}，
 *       按 merchantId 为键；其 SQL 只选公开字段（不含余额），但为保持
 *       「资金一变、相关缓存全失效」的一致语义，一并清理。</li>
 * </ul>
 * <p>
 * <b>事务边界</b>：统一走 {@link AfterCommitExecutor} —— 事务未提交就删缓存，
 * 并发读会立刻回源读到旧余额并写回缓存，反而制造脏数据。
 * 无事务时退化为立即执行，兼容 {@code @Scheduled} 等场景。
 */
@Component
public class MerchantFinanceCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(MerchantFinanceCacheEvictor.class);

    /** 延迟双删的延迟时间（毫秒），与项目其它缓存清理策略保持一致 */
    private static final long DELAY_MS = 300L;

    @Autowired
    private RedisService redisService;

    /** 复用项目统一的延迟任务调度器（不新起线程，也不阻塞当前线程 sleep） */
    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    /**
     * 资金相关字段变更后，失效该商户的详情/公开信息缓存
     * <p>
     * 必须在**事务提交后**调用（本方法内部已用 AfterCommitExecutor 保证这一点）。
     *
     * @param userId     商户对应的用户ID（可为 null，见 {@link #evictByMerchantId(Long)}）
     * @param merchantId 商户ID（可为 null）
     */
    public void evict(Long userId, Long merchantId) {
        if (userId == null && merchantId == null) {
            return;
        }
        AfterCommitExecutor.run(() -> {
            evictNow(userId, merchantId);

            // 延迟再删一次：应对「提交后仍有并发读把旧值写回」的窄窗口。
            // 用调度器异步执行，不阻塞当前线程。
            cacheDelayScheduler.schedule(() -> {
                try {
                    evictNow(userId, merchantId);
                    log.debug("延迟双删商户资金缓存成功, userId={}, merchantId={}", userId, merchantId);
                } catch (Exception e) {
                    // 延迟删除失败不影响主流程：TTL 会兜底（见 RedisService 的 TTL 策略）
                    log.error("延迟双删商户资金缓存失败, userId={}, merchantId={}", userId, merchantId, e);
                }
            }, DELAY_MS, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 只有 merchantId 时的便捷重载
     * <p>
     * 注意：此重载**无法清理按 userId 为键的两个 key**（{@code cache:merchant:{userId}} /
     * {@code cache:merchant:detail:{userId}}），因为拿不到 userId。
     * 调用方若已能拿到 userId，请优先用 {@link #evict(Long, Long)}。
     * 说明：这里刻意不主动查库补 userId —— 那会把缓存失效变成一次额外数据库往返，
     * 而这两个 key 也有 TTL 兜底（见 {@code RedisService.TTL_VERY_LONG}）。
     *
     * @param merchantId 商户ID
     */
    public void evictByMerchantId(Long merchantId) {
        evict(null, merchantId);
    }

    /** 实际执行删除（立即与延迟两处共用，避免两处写法不一致而漏 key） */
    private void evictNow(Long userId, Long merchantId) {
        if (userId != null) {
            redisService.deleteKey(RedisService.CACHE_MERCHANT + userId);
            redisService.deleteKey(RedisService.CACHE_MERCHANT_DETAIL + userId);
        }
        if (merchantId != null) {
            redisService.deleteKey(RedisService.CACHE_MERCHANT_PUBLIC + merchantId);
        }
        // 说明：不清理 cache:merchant:page:{hash} / cache:merchant:verified:{n}
        // —— 列表查询改造后商家列表已由 ES 索引承载（见 MerchantListIndexService），
        //    且资金字段不在 MerchantDocument 里，因此无需触发索引同步。
    }
}

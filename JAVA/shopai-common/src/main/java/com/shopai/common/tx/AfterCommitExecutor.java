package com.shopai.common.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后执行工具（统一「副作用」的执行时机）
 * <p>
 * <b>要解决的问题</b>：业务代码里经常在 {@code @Transactional} 方法内直接做两类「副作用」：
 * <ol>
 *   <li><b>删 Redis 缓存</b>：事务还没提交就删了缓存。此时并发读会立即回源查库，
 *       读到的仍是**未提交的旧值**，然后把这个旧值重新写回缓存 ——
 *       于是缓存里留下脏数据，且因为没再删一次而长期存在（典型的缓存一致性坑）；</li>
 *   <li><b>发 MQ 消息</b>：消息先于事务提交到达消费者。消费者回源查库时看不到这次修改，
 *       于是把**旧值**写进索引/下游库；更糟的是若本事务随后回滚，
 *       消息已经发出，下游永远停留在一个从未存在过的状态上。</li>
 * </ol>
 * 两者都应当在**事务成功提交之后**才执行。
 * <p>
 * <b>用法</b>：
 * <pre>
 * // 单个动作
 * AfterCommitExecutor.run(() -&gt; redisService.deleteKey(key));
 *
 * // 多个动作（按注册顺序执行）
 * AfterCommitExecutor.run(
 *         () -&gt; redisService.deleteKey(key),
 *         () -&gt; indexEventPublisher.publishUpsert(EntityType.ORDER, orderId));
 * </pre>
 * <p>
 * <b>无事务上下文时</b>（例如 {@code @Scheduled} 自调用导致事务代理未生效、
 * 或方法本身就没有 {@code @Transactional}）：直接执行，不抛异常 —— 此时不存在
 * 「提交时机」问题，行为与改造前一致，避免为了等待一个永远不会到来的提交而丢失副作用。
 * <p>
 * <b>回滚时</b>：{@code afterCommit} 不会被调用，副作用自然被丢弃，正是期望的行为。
 * <p>
 * 说明：本类只依赖 Spring 的 {@code TransactionSynchronizationManager}，
 * 放在公共库供各服务复用，避免每个服务各写一遍
 * {@code registerSynchronization(new TransactionSynchronization(){...})} 模板代码。
 */
public final class AfterCommitExecutor {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitExecutor.class);

    private AfterCommitExecutor() {
        // 工具类，禁止实例化
    }

    /** 待执行动作的抽象：允许抛异常，由本类统一捕获，不污染业务事务 */
    @FunctionalInterface
    public interface Action {
        void execute() throws Exception;
    }

    /**
     * 在事务提交后执行一个或多个动作；无事务上下文时立即执行
     *
     * @param actions 待执行动作（按传入顺序执行）
     */
    public static void run(Action... actions) {
        if (actions == null || actions.length == 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务上下文：立即执行（不存在提交时机问题）
            executeAll(actions);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executeAll(actions);
            }
        });
    }

    /**
     * 逐个执行动作，单个动作失败不影响其余动作
     * <p>
     * 为什么吞异常：此时事务**已经提交**，抛出异常既无法回滚数据，
     * 又会中断后续动作（例如缓存已删但索引没同步）。因此这里只记录错误日志，
     * 让每个副作用各自尽力完成。
     *
     * @param actions 待执行动作
     */
    private static void executeAll(Action[] actions) {
        for (Action action : actions) {
            try {
                action.execute();
            } catch (Exception e) {
                log.error("事务提交后动作执行失败（事务已提交，数据不回滚，仅记录以便排查）", e);
            }
        }
    }
}

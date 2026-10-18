package com.shopai.seckill.constant;

/**
 * 秒杀活动状态常量
 * 状态流转：
 * <pre>
 *   DRAFT ──发布──▶ PENDING ──到开始时间──▶ RUNNING ──到结束时间──▶ ENDED
 *     │                │                     │
 *     └────────────────┴──商家下线───────────┴──▶ OFFLINE
 * </pre>
 */
public final class SeckillStatus {

    /** 草稿：刚创建，库存和商品还能随便改，不对外可见 */
    public static final String DRAFT = "draft";

    /** 待开始：已发布，时间窗还没到。此时必须已经预热好 Redis 库存 */
    public static final String PENDING = "pending";

    /** 进行中：时间窗内，可以抢购 */
    public static final String RUNNING = "running";

    /** 已结束：时间窗过了，由定时任务自动推进 */
    public static final String ENDED = "ended";

    /** 已下线：商家/管理员手动停掉，即使还在时间窗内也不能抢 */
    public static final String OFFLINE = "offline";

    private SeckillStatus() {
    }

    /**
     * 是否处于「可抢购」状态。
     * <p>
     * 只有 RUNNING 可抢 —— 注意时间窗内也可能是 OFFLINE（被商家下线了），
     * 所以抢购入口必须同时校验 status 与时间窗。
     */
    public static boolean canBuy(String status) {
        return RUNNING.equals(status);
    }

    /**
     * 是否允许编辑（改库存、改商品、改价格）。
     * <p>
     * 只有草稿态可编辑：一旦发布，Redis 里已经有预热的库存数字，
     * 此时改 total_stock 会出现「数据库说 100、Redis 里还是 50」的错位。
     * 要改就先下线回草稿，改完重新发布并重新预热。
     */
    public static boolean editable(String status) {
        return DRAFT.equals(status) || OFFLINE.equals(status);
    }
}

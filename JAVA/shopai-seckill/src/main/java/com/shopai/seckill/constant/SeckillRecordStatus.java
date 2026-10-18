package com.shopai.seckill.constant;

/**
 * 秒杀记录 / 本地消息状态常量
 */
public final class SeckillRecordStatus {

    private SeckillRecordStatus() {
    }

    // ==================== 抢购记录（seckill_record.status） ====================

    /** 待成单：Redis 扣减已成功，订单还没创建出来 */
    public static final String PENDING = "pending";

    /** 已成单：订单服务已返回真实订单号 */
    public static final String SUCCESS = "success";

    /** 成单失败：重试耗尽后放弃，库存已回补 */
    public static final String FAILED = "failed";

    /** 已取消（人工介入或超时关闭） */
    public static final String CANCELLED = "cancelled";

    // ==================== 本地消息（seckill_message.status） ====================

    /** 待投递 / 待重投 */
    public static final String MSG_PENDING = "pending";

    /** 已投递到 MQ，等消费结果 */
    public static final String MSG_SENT = "sent";

    /** 消费完成，流程走完 */
    public static final String MSG_DONE = "done";

    /** 重试耗尽，判定失败（同时会回补库存） */
    public static final String MSG_FAILED = "failed";
}

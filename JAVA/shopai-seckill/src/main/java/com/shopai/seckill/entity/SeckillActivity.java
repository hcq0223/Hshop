package com.shopai.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动实体（对应 seckill_activity 表）
 * <p>
 * 一个活动下挂多个 SKU 额度（{@link SeckillStock}），活动本身只管时间窗、
 * 状态与限购规则；具体的库存/价格都在 SKU 维度。
 * <p>
 * 状态流转（由 {@code SeckillActivityStatusTask} 定时推进，也支持商家手动操作）：
 * <pre>
 *   draft ──发布──▶ pending ──到开始时间──▶ running ──到结束时间──▶ ended
 *     └──────────────商家下线────────────▶ offline
 * </pre>
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 活动名称，如「双11 零点场」 */
    private String title;

    /**
     * 所属商家ID。
     * <p>
     * 商家只能管理自己的活动：卖家端接口一律以「当前登录 userId →
     * Feign 调商家服务换 merchantId」的结果为准，绝不信任前端传入的 merchantId。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 开抢时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 状态：draft / pending / running / ended / offline，取值见 {@link com.shopai.seckill.constant.SeckillStatus} */
    private String status;

    /** 每人限购件数（0=不限） */
    private Integer perUserLimit;

    /** 活动说明 */
    private String description;

    /** 活动总库存（各 SKU 额度之和，冗余字段，发布时汇总写入） */
    private Integer totalStock;

    /** 已售件数（冗余统计，成单后累加） */
    private Integer soldCount;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 逻辑删除：0正常 1已删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ==================== 非数据库字段：状态判断辅助 ====================

    /**
     * 当前是否处于可抢购时间窗内。
     * <p>
     * 注意：这里只判断时间，不判断 status。抢购入口会把
     * 「时间窗内 && status=running」两者都校验，缺一不可 ——
     * 光看时间会让被商家下线（offline）的活动继续被抢。
     */
    public boolean inTimeWindow(LocalDateTime now) {
        return startTime != null && endTime != null
                && !now.isBefore(startTime) && now.isBefore(endTime);
    }
}

package com.shopai.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品库存实体（对应 seckill_stock 表）—— <b>本服务的核心表</b>
 * <p>
 * 一行 = 某个活动里某个 SKU 的<b>独立库存额度</b>。
 * <p>
 * <b>库存模型（重要）</b>：本表库存与 shopai-inventory 的 {@code inventory} 表
 * <b>完全独立、互不联动</b>。选择这个模型的原因与代价：
 * <ul>
 *   <li>好处：秒杀额度是一份干净的数字，抢购链路完全不碰主库存，不会因为
 *       跨服务调用把主库存链路拖下水；模型简单、可独立压测；</li>
 *   <li>代价：同一个 SKU 的秒杀额度与常规库存互不知情。常规下单照常扣
 *       {@code inventory}，秒杀照常扣本表，<b>两边都可能把货卖光</b>，
 *       极端情况下会「秒杀成功但仓库没货」。</li>
 *   <li>因此运营侧必须保证：某 SKU 参与秒杀时，为秒杀留出的备货量不大于
 *       {@code total_stock}，并相应减少常规可售库存；</li>
 *   <li>若后续要根治，改造成「活动创建时从主库存预占」（在 inventory 上
 *       增加 {@code locked_stock += 额度}，活动结束/售罄回补）即可。</li>
 * </ul>
 * <p>
 * <b>为什么冗余 productId / merchantId</b>：SKU 的规格与商品信息一律 Feign
 * 实时取（避免商品改价改名后这边出现脏数据），但 productId / merchantId
 * 是<b>归属关系、不会变</b>，冗余存下来可以让「按商品过滤」「按商家鉴权」
 * 「批量取商品信息」少绕一次服务调用。<b>originPrice 是快照</b>，
 * 只用于页面划线价展示，不是交易依据。
 */
@Data
@TableName("seckill_stock")
public class SeckillStock {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属活动ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /** SKU ID（product_skus.id，归属商品服务，此处只存 ID） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 商品ID（products.id），冗余存储便于按商品聚合 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 所属商家ID（从商品服务取得后冗余），用于权限校验与索引 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    // ==================== 独立额度 ====================

    /** 秒杀价（必须低于 SKU 日常价，创建/修改时校验） */
    private BigDecimal seckillPrice;

    /**
     * 原价快照：创建活动时从商品服务取，仅用于页面展示划线价。
     * <p>
     * 是快照而非实时值 —— 商品后续调价不会回溯修改历史活动的划线价，
     * 这符合「活动价格一旦发布就固定」的业务预期。
     */
    private BigDecimal originPrice;

    /** 本 SKU 秒杀总库存（独立额度，与 inventory 表无关） */
    private Integer totalStock;

    /** 当前可抢库存（Redis 落地值；DB 侧用乐观锁扣减兜底） */
    private Integer availableStock;

    /** 已扣减待成单的库存（MQ 消费成功转 soldStock，失败则回补） */
    private Integer lockedStock;

    /** 已成单库存 */
    private Integer soldStock;

    /** 乐观锁版本号（MyBatis-Plus @Version） */
    @Version
    private Integer version;

    // ==================== 限购 / 排序 ====================

    /** 每人限购件数（冗余自活动，便于单独调某个 SKU 的限购） */
    private Integer perUserLimit;

    /** 排序（越小越靠前） */
    private Integer sort;

    // ==================== 预热标记 ====================

    /** 是否已预热到 Redis：0未预热 1已预热 */
    private Integer preheated;

    /** 预热时间 */
    private LocalDateTime preheatTime;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 剩余可抢库存是否为 0。
     */
    public boolean soldOut() {
        return availableStock != null && availableStock <= 0;
    }
}

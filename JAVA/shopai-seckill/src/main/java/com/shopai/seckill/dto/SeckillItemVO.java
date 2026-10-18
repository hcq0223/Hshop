package com.shopai.seckill.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀商品展示视图（活动详情页的一个条目）
 * <p>
 * 由 {@code SeckillInfoService} 组装：库存/价格来自本地 {@code seckill_stock} 表，
 * 规格、图片、商品名、店铺名通过 Feign 实时取（见该类注释）。
 * <p>
 * 返回给前端时只暴露「展示 + 抢购」需要的字段，不外泄内部统计
 * （如 lockedStock）—— 那些对用户没意义，还会让人误解库存为什么对不上。
 */
@Data
public class SeckillItemVO {

    // ==================== 来自 seckill_stock ====================

    /** seckill_stock.id —— 抢购时用它定位库存 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 秒杀价 */
    private BigDecimal seckillPrice;

    /** 原价（快照，用于划线价展示） */
    private BigDecimal originPrice;

    /** 总库存 */
    private Integer totalStock;

    /**
     * 剩余可抢库存。
     * <p>
     * 活动进行中优先取 Redis 的值（那里才是真实余量，DB 的 available_stock
     * 是异步落库的，可能滞后）；Redis 没预热时回退到 DB 值。
     */
    private Integer availableStock;

    /** 已售件数 */
    private Integer soldStock;

    /** 每人限购件数 */
    private Integer perUserLimit;

    // ==================== 来自 Feign（商品服务） ====================

    /** 商品名称 */
    private String productName;

    /** 商品主图 */
    private String productImage;

    /** SKU 图片（优先展示，比商品主图更贴近用户选的规格） */
    private String skuImage;

    /** 规格描述，如「藏青色 / M」 */
    private String skuAttrs;

    /** 日常价（实时取，用于和秒杀价对比展示折扣） */
    private BigDecimal normalPrice;

    // ==================== 计算/状态字段 ====================

    /**
     * 是否已售罄。
     * <p>
     * 由服务端算好返回，不让前端自己比大小：前端的 availableStock 可能因为
     * 缓存/延迟而不准，售罄与否直接影响按钮是否可点，必须以后端为准。
     */
    private Boolean soldOut;

    /**
     * 当前用户是否已抢过（一人一单）。
     * <p>
     * 未登录时为 null —— 未登录用户谈不上「已抢过」。
     */
    private Boolean boughtByMe;

    /**
     * 商品服务拉取失败标记。
     * <p>
     * 为 true 时上面的 productName/skuAttrs 等展示字段为空。前端据此显示
     * 「商品信息加载失败」而不是把它们当成空商品名渲染出来 ——
     * 后者会让用户以为活动里挂的是无效商品。
     */
    private Boolean infoMissing;

    /**
     * 库存来源：{@code redis} = 已预热（真实余量），{@code db} = 未预热（回退值）。
     * <p>
     * 带上它纯粹是为了排查方便：用户反馈「明明显示有货却抢不到」时，
     * 一眼就能看出当时读的是哪个来源。
     */
    private String stockSource;
}

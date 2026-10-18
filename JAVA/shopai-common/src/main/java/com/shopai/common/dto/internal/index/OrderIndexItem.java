package com.shopai.common.dto.internal.index;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单索引视图 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：列表查询改造后，订单列表（顾客/商家/管理员）不再放 Redis 缓存，
 * 改由 shopai-search 的 {@code order} ES 索引承载。订单服务在状态流转后用
 * Feign 内部接口把本视图回源给搜索服务，由其写入索引。
 * <p>
 * 字段设计说明：
 * - 携带 {@code customerId / merchantId / status / createTime} 等列表筛选与排序所需字段；
 * - 冗余 {@code shopName / customerNickname}，让搜索服务无需再联查；
 * - 冗余 {@code itemSummary / itemCount}，列表页只需展示「共N件 + 商品名摘要」，
 *   避免为列表页返回整个订单项集合（原实现是查出 orderItems 再在内存里统计）。
 */
@Data
public class OrderIndexItem implements Serializable {

    /** 订单ID */
    private String id;

    private String orderNo;

    private String customerId;

    /** 客户昵称（冗余，来自用户服务；取不到时为 null，索引降级为 null 而非索引失败） */
    private String customerNickname;

    private String merchantId;

    /** 店铺名称（冗余，本服务内即可取到） */
    private String shopName;

    /** 店铺 Logo（冗余） */
    private String shopLogo;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    private BigDecimal freightAmount;

    /** pending_pay/paid/shipped/received/completed/cancelled/refunding */
    private String status;

    /** 是否已评价：1 已评价 / 0 未评价 */
    private Integer isReviewed;

    /** 是否处于终态（completed / cancelled），便于列表快速排除 */
    private Boolean finalized;

    /** 订单项数量合计（列表页「共 N 件」） */
    private Integer itemCount;

    /** 订单项商品名摘要（如「纯棉T恤、牛仔裤 等3件」），列表页直接展示 */
    private String itemSummary;

    /** 订单项主图（取第一个订单项图片，列表页展示） */
    private String itemImage;

    private LocalDateTime paidAt;

    private LocalDateTime shippedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createTime;
}

package com.shopai.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单搜索文档（Elasticsearch 的 order 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：原订单列表走 Redis 缓存
 * {@code cache:order:list:{hash}}，且任意订单变更就 {@code deleteByPrefix(CACHE_ORDER_LIST)}
 * 把**所有用户、所有页码**的订单列表缓存全部清空，失效代价过大。
 * 改造后订单列表（顾客/商家/管理员三处）由本索引承载：
 * - 写入：订单服务状态流转后发 {@code order.changed}，搜索服务回源组装本文档 upsert；
 * - 读取：订单服务保留原路由不变，内部通过 Feign 调搜索服务的统一列表查询接口。
 * <p>
 * 冗余说明：{@code shopName / customerNickname} 在回源时由订单服务装配好，
 * 使列表查询无需再跨服务补齐；{@code itemCount / itemSummary / itemImage} 让列表页
 * 不必返回整个订单项集合（原实现查出 orderItems 后在内存统计）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "order")
public class OrderDocument {

    /** 订单ID（雪花ID以 String 存储，避免精度丢失） */
    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    /** 订单号（支持按单号检索/精确查找） */
    @Field(type = FieldType.Keyword)
    private String orderNo;

    /** 下单客户ID */
    @Field(type = FieldType.Keyword)
    private String customerId;

    /** 客户昵称（冗余，列表展示与检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String customerNickname;

    /** 所属商家ID */
    @Field(type = FieldType.Keyword)
    private String merchantId;

    /** 店铺名称（冗余，列表展示与检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    /** 店铺 Logo（冗余，列表展示） */
    @Field(type = FieldType.Keyword, index = false)
    private String shopLogo;

    @Field(type = FieldType.Double)
    private BigDecimal totalAmount;

    @Field(type = FieldType.Double)
    private BigDecimal payAmount;

    @Field(type = FieldType.Double)
    private BigDecimal freightAmount;

    /** pending_pay/paid/shipped/received/completed/cancelled/refunding */
    @Field(type = FieldType.Keyword)
    private String status;

    /** 1 已评价 / 0 未评价 */
    @Field(type = FieldType.Integer)
    private Integer isReviewed;

    /** 是否终态（completed / cancelled），列表快速过滤用 */
    @Field(type = FieldType.Boolean)
    private Boolean finalized;

    /** 订单项数量合计（列表页「共 N 件」） */
    @Field(type = FieldType.Integer)
    private Integer itemCount;

    /** 订单项商品名摘要（列表页直接展示） */
    @Field(type = FieldType.Keyword, index = false)
    private String itemSummary;

    /** 订单项主图（列表页展示） */
    @Field(type = FieldType.Keyword, index = false)
    private String itemImage;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime paidAt;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime shippedAt;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime completedAt;

    /** 下单时间（列表默认按此倒序） */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createTime;
}

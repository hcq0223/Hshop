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

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价搜索文档（Elasticsearch 的 review 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：原评价列表走 Redis 缓存
 * （{@code cache:review:product:{id}}、{@code cache:review:customer:{id}}），
 * 删评/新增时按商品或客户维度失效。改造后评价列表由本索引承载：
 * - 写入：商家服务评价增删后发 {@code review.changed}，搜索服务回源组装本文档 upsert；
 * - 读取：商家服务保留原路由不变，内部通过 Feign 调搜索服务的统一列表查询接口。
 * <p>
 * 冗余说明：评价人昵称/头像、商品名、店铺名在回源时由商家服务通过用户/商品服务
 * 内部接口装配好，列表查询无需再跨服务补齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "review")
public class ReviewDocument {

    /** 评价ID */
    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Keyword)
    private String orderId;

    @Field(type = FieldType.Keyword)
    private String customerId;

    /** 评价人昵称（冗余，列表展示与检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String customerNickname;

    /** 评价人头像（冗余，列表展示） */
    @Field(type = FieldType.Keyword, index = false)
    private String customerAvatar;

    @Field(type = FieldType.Keyword)
    private String productId;

    /** 商品名称（冗余，列表展示与检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String productName;

    @Field(type = FieldType.Keyword)
    private String merchantId;

    /** 店铺名称（冗余，列表展示与检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String merchantName;

    /** 1-5 星（支持按星级筛选/统计） */
    @Field(type = FieldType.Integer)
    private Integer rating;

    /** 评价内容（分词检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /** 评价图片（仅存储不索引） */
    @Field(type = FieldType.Keyword, index = false)
    private List<String> images;

    /** 评价时间（列表默认按此倒序） */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createTime;
}

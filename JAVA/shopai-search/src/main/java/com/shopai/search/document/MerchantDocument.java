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
 * 商家搜索文档（Elasticsearch 的 merchant 索引）
 * <p>
 * 微服务拆分说明：原单体商家 LIKE 搜索（MerchantService.searchMerchantsPublic + MerchantMapper.selectMerchantSearchPage）
 * 迁至搜索服务，改用 ES 检索。本类对应 ES merchant 索引结构（见设计文档第 9.1 节）：
 * id, shop_name, logo_url, rating, is_verified, description, created_at。
 * <p>
 * 数据来源：商家服务在商家入驻/审核通过/店铺信息修改后发 MQ merchant.changed，
 * 本服务消费后从源库直查（merchants JOIN users）构建本文档 upsert 到 ES。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "merchant")
public class MerchantDocument {

    /** 商家ID（对应 merchants.id，19 位雪花 ID 以 String 存储避免前端精度丢失） */
    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    /** 店铺名称（分词检索主字段） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    /** 店铺 logo */
    @Field(type = FieldType.Keyword, index = false)
    private String logoUrl;

    /** 店铺评分 */
    @Field(type = FieldType.Double)
    private BigDecimal rating;

    /** 是否认证：0 未认证 / 1 已认证 */
    @Field(type = FieldType.Integer)
    private Integer isVerified;

    /** 店铺简介（分词检索辅助字段，对应 merchants.shop_description 列；前端字段名为 shopDescription） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopDescription;

    /** 创建时间（关联 users.created_at；原单体前端展示为 m.user.createTime，拆分后改为 m.createdAt） */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createdAt;
}

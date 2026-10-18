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
import java.util.List;

/**
 * 商品搜索文档（Elasticsearch 的 product 索引）
 * <p>
 * 微服务拆分说明：原单体商品 LIKE 搜索（ProductService.searchProducts + ProductMapper.selectPageByConditions）
 * 迁至搜索服务，改用 ES 检索。本类对应 ES product 索引结构（见设计文档第 9.1 节）：
 * id, name, description, category_id, category_name, parent_category_name, merchant_id,
 * shop_name, shop_logo, price_min, price_max, sales_count, status, created_at, images。
 * <p>
 * 数据来源：商品服务在商品增删改后发 MQ product.changed，本服务消费后从源库直查
 * （products JOIN merchants JOIN categories）构建本文档 upsert 到 ES。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "product")
public class ProductDocument {

    /** 商品ID（对应 products.id，19 位雪花 ID 以 String 存储避免前端精度丢失） */
    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    /** 商品名称（分词检索主字段） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    /** 商品描述（分词检索辅助字段） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    /** 商品主图（列表页展示用，原单体 Product.mainImage） */
    @Field(type = FieldType.Keyword, index = false)
    private String mainImage;

    /** 分类ID */
    @Field(type = FieldType.Integer)
    private Integer categoryId;

    /** 分类名称 */
    @Field(type = FieldType.Keyword)
    private String categoryName;

    /** 父分类名称 */
    @Field(type = FieldType.Keyword)
    private String parentCategoryName;

    /** 商家ID（String 化，避免精度丢失） */
    @Field(type = FieldType.Keyword)
    private String merchantId;

    /** 店铺名称（冗余，用于列表页展示 + 店铺名检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    /** 店铺 logo */
    @Field(type = FieldType.Keyword, index = false)
    private String shopLogo;

    /** 最低价 */
    @Field(type = FieldType.Double)
    private BigDecimal priceMin;

    /** 最高价 */
    @Field(type = FieldType.Double)
    private BigDecimal priceMax;

    /** 销量 */
    @Field(type = FieldType.Integer)
    private Integer salesCount;

    /** 状态：draft / on_sale / off_sale */
    @Field(type = FieldType.Keyword)
    private String status;

    /** 创建时间 */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createdAt;

    /** 图片列表 */
    @Field(type = FieldType.Keyword, index = false)
    private List<String> images;
}

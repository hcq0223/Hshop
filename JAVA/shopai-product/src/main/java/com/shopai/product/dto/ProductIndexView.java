package com.shopai.product.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品索引文档视图（商品服务侧读取用）
 * <p>
 * 微服务拆分说明（列表查询改造）：商品列表改由 ES 承载后，搜索服务把命中记录以
 * JSON 形式返回（通用列表接口的 {@code records} 是 {@code List&lt;Object&gt;}），
 * 商品服务需要按 ES 文档的结构反序列化。本类即该结构的镜像。
 * <p>
 * 为什么不直接用 {@link com.shopai.common.dto.internal.ProductIndexItem}：
 * 后者是「商品服务 → 搜索服务」**写入**方向契约（{@code images} 为 JSON 字符串，
 * 供索引构建时传输）；而 ES 文档里 {@code images} 是真正的字符串数组，
 * 且多了 {@code salesCount} 等索引字段。两者形状不同，强行合并会导致
 * 要么破坏既有索引写入链路，要么让反序列化因类型不匹配而静默丢字段，
 * 因此读写两个方向各保留一个轻量 DTO。
 */
@Data
public class ProductIndexView implements Serializable {

    private String id;

    private String name;

    private String description;

    private String mainImage;

    /** 图片列表（ES 文档中为 Keyword 数组） */
    private List<String> images;

    private Integer categoryId;

    private String categoryName;

    private String parentCategoryName;

    private String merchantId;

    private String shopName;

    private String shopLogo;

    private BigDecimal priceMin;

    private BigDecimal priceMax;

    private Integer salesCount;

    /** draft / on_sale / off_sale */
    private String status;

    private LocalDateTime createdAt;
}

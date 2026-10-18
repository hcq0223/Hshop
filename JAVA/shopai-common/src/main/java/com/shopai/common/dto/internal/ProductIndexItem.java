package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品索引条目 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：搜索服务原先通过 {@code ProductRefMapper.selectAllForIndex /
 * selectOneForIndex} 跨库直查 products，并 JOIN merchants（店铺名/Logo）、
 * categories（分类名/父分类名）补充可检索字段。
 * <p>
 * 拆分为「独立库」后，商品服务内部接口负责在本地库内完成这些 JOIN，
 * 搜索服务改为 Feign 拉取本 DTO 列表：
 * - 全量重建：GET /api/product/internal/index/products
 * - 增量同步：GET /api/product/internal/index/product?ids=1
 * <p>
 * 注意：约定 ID 字段在本 DTO 中均以 String 承载（ES 文档 id 本身即 String），
 * 避免雪花ID在 JSON ↔ Java 之间反复转换时的精度问题。
 */
@Data
public class ProductIndexItem implements Serializable {

    /** 商品ID */
    private String id;

    private String name;

    private String description;

    private String mainImage;

    /** 图片列表（JSON 字符串，与原 products.images 字段一致） */
    private String images;

    /** 分类ID（products.category_id 本身为自增 INT，与 ES 文档 FieldType.Integer 保持一致） */
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

    /** 备用：若上游改为多图数组，可直接承载 */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    private List<String> imageList;
}

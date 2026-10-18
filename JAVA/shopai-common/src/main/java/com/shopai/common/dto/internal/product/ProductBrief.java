package com.shopai.common.dto.internal.product;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品简要信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：商家服务的评价列表原先在 {@code ReviewMapper.xml} 中用
 * {@code LEFT JOIN products p} 补「商品名称」。拆分为「独立库」后，
 * 商家服务改为 Feign 调商品服务内部接口批量补商品名。
 * <p>
 * 用途：批量补齐，因此只含最小字段。
 */
@Data
public class ProductBrief implements Serializable {

    /** 商品ID（字符串承载，避免雪花ID精度问题） */
    private String id;

    /** 商品名称 */
    private String name;

    /** 商品主图 */
    private String mainImage;
}

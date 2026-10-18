package com.shopai.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 库存条目 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：库存独立为 shopai-inventory 服务后，商品服务(shopai-product)、
 * 订单服务(shopai-order)通过 Feign 调用库存服务查询/扣减库存。
 * 本 DTO 定义统一的库存返回结构，放在公共库供各服务共用，避免字段不一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockItem implements Serializable {

    /** SKU ID（对应 product_skus.id） */
    private Long skuId;

    /** 当前可售库存（来自 inventory 表 stock 字段） */
    private Integer stock;

}

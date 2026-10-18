package com.shopai.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.product.dto.ProductIndexView;
import com.shopai.product.entity.Category;
import com.shopai.product.entity.Merchant;
import com.shopai.product.entity.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 商品索引结果转换器
 * <p>
 * 微服务拆分说明（列表查询改造）：商品列表改由 ES 承载后，搜索服务返回的是
 * 通用列表结果（{@code records} 为 {@code List<Object>}，经 Feign 后是 Map）。
 * 本类把它转成商品服务原有的 {@link Product} 结构，从而**保持对外响应结构不变**。
 * <p>
 * 字段映射说明（对齐原 {@code ProductMapper.xml} 的查询列）：
 * - 原 {@code selectHotOnSale} / {@code selectByMerchantId} 返回的 Product 携带
 *   {@code shopName / shopLogo} 冗余字段 → 索引里已冗余，此处直接装配；
 * - 同时装配 {@code merchant} 关联对象与 {@code categoryName / parentCategoryName}，
 *   兼容前端对 {@code item.merchant.shopName}、{@code item.categoryName} 的读取。
 */
@Service
public class ProductIndexResultMapper {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexResultMapper.class);

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 把 ES 列表结果映射为商品列表
     *
     * @param result 搜索服务返回的通用列表结果
     * @return 商品列表（保序，与 ES 命中顺序一致）
     */
    public List<Product> toProducts(IndexListResult result) {
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            return Collections.emptyList();
        }
        List<Product> products = new ArrayList<>(result.getRecords().size());
        for (Object record : result.getRecords()) {
            ProductIndexView view = toView(record);
            if (view != null) {
                products.add(toProduct(view));
            }
        }
        return products;
    }

    /**
     * 单条记录反序列化为索引视图
     * <p>
     * 转换失败时跳过该条，不因一条脏数据导致整页失败。
     */
    private ProductIndexView toView(Object record) {
        if (record == null) {
            return null;
        }
        if (record instanceof ProductIndexView view) {
            return view;
        }
        try {
            return objectMapper.convertValue(record, ProductIndexView.class);
        } catch (Exception e) {
            log.error("商品索引记录转换失败，已跳过该条: {}", record, e);
            return null;
        }
    }

    /** 商品索引视图 → Product（对外响应结构） */
    private Product toProduct(ProductIndexView v) {
        Product p = new Product();
        p.setId(parseLong(v.getId()));
        p.setMerchantId(parseLong(v.getMerchantId()));
        p.setCategoryId(v.getCategoryId());
        p.setName(v.getName());
        p.setDescription(v.getDescription());
        p.setMainImage(v.getMainImage());
        p.setPriceMin(v.getPriceMin());
        p.setPriceMax(v.getPriceMax());
        p.setSalesCount(v.getSalesCount());
        p.setStatus(v.getStatus());
        p.setCreateTime(v.getCreatedAt());

        // 店铺冗余字段 + merchant 关联对象（原由 SQL JOIN merchants 填充，索引中已冗余）
        p.setShopName(v.getShopName());
        p.setShopLogo(v.getShopLogo());
        if (v.getMerchantId() != null) {
            Merchant merchant = new Merchant();
            merchant.setId(parseLong(v.getMerchantId()));
            merchant.setShopName(v.getShopName());
            merchant.setLogoUrl(v.getShopLogo());
            p.setMerchant(merchant);
        }

        // 分类冗余字段 + category 关联对象（原由 SQL JOIN categories 填充）
        p.setCategoryName(v.getCategoryName());
        p.setParentCategoryName(v.getParentCategoryName());
        if (v.getCategoryId() != null) {
            Category category = new Category();
            category.setId(v.getCategoryId());
            category.setName(v.getCategoryName());
            p.setCategory(category);
        }
        return p;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

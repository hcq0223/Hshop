package com.shopai.merchant.service;

import com.shopai.common.dto.internal.index.ReviewIndexItem;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerInfo;
import com.shopai.common.dto.internal.product.ProductBrief;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.Review;
import com.shopai.merchant.feign.CustomerInternalClient;
import com.shopai.merchant.feign.ProductInternalClient;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.mapper.ReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 评价索引视图组装服务
 * <p>
 * 微服务拆分说明（列表查询改造）：搜索服务需要「评价索引视图」才能写 ES，
 * 而视图里的跨服务冗余字段只有归属方能组装：评价人昵称/头像（用户服务）、
 * 商品名（商品服务）、店铺名（本服务）。组装后由搜索服务写入索引，
 * 搜索服务因此不需要反向依赖各业务的组装逻辑。
 * <p>
 * 说明：商家索引视图的组装由既有的 {@link MerchantIndexService} 负责
 * （含「已认证 + 账号正常」的过滤语义），本类只处理评价，避免两处实现同一套规则。
 * <p>
 * 降级策略：用户/商品信息取不到时对应字段留空，**不阻断索引写入** ——
 * 列表缺少昵称远好于整条记录查不出来。
 */
@Service
public class MerchantIndexAssembler {

    private static final Logger log = LoggerFactory.getLogger(MerchantIndexAssembler.class);

    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private CustomerInternalClient customerInternalClient;
    @Autowired
    private ProductInternalClient productInternalClient;

    // ==================== 评价索引视图 ====================

    /**
     * 组装单个评价的索引视图
     *
     * @param reviewId 评价ID
     * @return 索引视图；评价不存在时返回 null
     */
    public ReviewIndexItem assembleReview(Long reviewId) {
        if (reviewId == null) {
            return null;
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            return null;
        }
        List<ReviewIndexItem> items = assembleReviews(Collections.singletonList(review));
        return items.isEmpty() ? null : items.get(0);
    }

    /** 全量评价索引视图（评价索引全量重建用） */
    public List<ReviewIndexItem> assembleAllReviews() {
        List<Review> reviews = reviewMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Review>lambdaQuery()
                        .orderByAsc(Review::getId));
        return assembleReviews(reviews);
    }

    /**
     * Review 列表 → 索引视图列表
     * <p>
     * 三类冗余字段各做一次批量调用：
     * - 评价人昵称/头像（用户服务）
     * - 商品名（商品服务）
     * - 店铺名（本地 merchants 表）
     */
    private List<ReviewIndexItem> assembleReviews(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, CustomerInfo> customerMap = loadCustomers(reviews);
        Map<Long, String> productNames = loadProductNames(reviews);
        Map<Long, String> merchantNames = loadMerchantNames(reviews);

        List<ReviewIndexItem> items = new ArrayList<>(reviews.size());
        for (Review r : reviews) {
            ReviewIndexItem item = new ReviewIndexItem();
            item.setId(r.getId() == null ? null : String.valueOf(r.getId()));
            item.setOrderId(r.getOrderId() == null ? null : String.valueOf(r.getOrderId()));
            item.setCustomerId(r.getCustomerId() == null ? null : String.valueOf(r.getCustomerId()));
            item.setProductId(r.getProductId() == null ? null : String.valueOf(r.getProductId()));
            item.setMerchantId(r.getMerchantId() == null ? null : String.valueOf(r.getMerchantId()));
            item.setRating(r.getRating());
            item.setContent(r.getContent());
            item.setImages(r.getImages());
            item.setCreateTime(r.getCreateTime());

            if (r.getCustomerId() != null) {
                CustomerInfo c = customerMap.get(r.getCustomerId());
                if (c != null) {
                    item.setCustomerNickname(c.getNickname());
                    item.setCustomerAvatar(c.getAvatarUrl());
                }
            }
            if (r.getProductId() != null) {
                item.setProductName(productNames.get(r.getProductId()));
            }
            if (r.getMerchantId() != null) {
                item.setMerchantName(merchantNames.get(r.getMerchantId()));
            }
            items.add(item);
        }
        return items;
    }

    /** 批量取客户信息（Feign 调用户服务；降级返回空 Map） */
    private Map<Long, CustomerInfo> loadCustomers(List<Review> reviews) {
        Map<Long, CustomerInfo> map = new HashMap<>();
        String ids = reviews.stream()
                .map(Review::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<CustomerInfo>> r = customerInternalClient.listCustomers(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (CustomerInfo c : r.getData()) {
                    if (c.getId() != null) {
                        map.put(c.getId(), c);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取客户信息失败（评价索引将缺少昵称/头像），customerIds={}", ids, e);
        }
        return map;
    }

    /** 批量取商品名（Feign 调商品服务；降级返回空 Map） */
    private Map<Long, String> loadProductNames(List<Review> reviews) {
        Map<Long, String> map = new HashMap<>();
        String ids = reviews.stream()
                .map(Review::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<ProductBrief>> r = productInternalClient.listProductBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (ProductBrief p : r.getData()) {
                    if (p.getId() != null) {
                        map.put(Long.valueOf(p.getId()), p.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取商品名失败（评价索引将缺少商品名），productIds={}", ids, e);
        }
        return map;
    }

    /** 批量取店铺名（本服务本地查询） */
    private Map<Long, String> loadMerchantNames(List<Review> reviews) {
        Map<Long, String> map = new HashMap<>();
        List<Long> merchantIds = reviews.stream()
                .map(Review::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return map;
        }
        try {
            for (Merchant m : merchantMapper.selectBatchIds(merchantIds)) {
                map.put(m.getId(), m.getShopName());
            }
        } catch (Exception e) {
            log.error("批量获取店铺名失败（评价索引将缺少店铺名），merchantIds={}", merchantIds, e);
        }
        return map;
    }
}

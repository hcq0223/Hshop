package com.shopai.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.product.entity.Category;
import com.shopai.product.entity.Product;
import com.shopai.product.feign.MerchantInternalClient;
import com.shopai.product.mapper.CategoryMapper;
import com.shopai.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品搜索索引数据服务（供搜索服务 Feign 拉取）
 * <p>
 * 微服务拆分说明：搜索服务原先通过自己的 {@code ProductRefMapper} 跨库直查 products，
 * 并 {@code JOIN merchants}（店铺名/Logo）、{@code JOIN categories}（分类名/父分类名）
 * 组装可检索字段。按「独立库」处理后：
 * - products / categories 属于商品服务，可在本服务内本地联查；
 * - merchants 属于商家服务，只能 Feign 调用；
 * 因此索引源数据的组装职责上移到本服务，搜索服务只负责「拉取 + 写 ES」。
 * <p>
 * 本类不参与商品业务写入，仅供内部接口（{@code /api/product/internal/index/**}）使用。
 */
@Service
public class ProductIndexService {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private MerchantInternalClient merchantInternalClient;

    /**
     * 全量构建商品索引条目（搜索服务全量重建 product 索引用）
     */
    public List<ProductIndexItem> buildAll() {
        return assemble(productMapper.selectAllForIndex());
    }

    /**
     * 按商品ID批量构建索引条目（搜索服务 MQ 增量同步用）
     *
     * @param ids 商品ID集合
     */
    public List<ProductIndexItem> buildByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return assemble(productMapper.selectByIdsForIndex(ids));
    }

    /**
     * 组装索引条目
     * <p>
     * 步骤：批量取店铺名/Logo（一次 Feign）→ 批量取父分类名（本地一次查询）→ 逐条映射。
     */
    private List<ProductIndexItem> assemble(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 店铺名/Logo：商家表归属商家服务，一次批量 Feign 调用补齐（替代原 JOIN merchants）
        Map<Long, MerchantBrief> shopMap = loadShops(products);

        // 2. 父分类名：categories 归属本服务，本地一次批量查询补齐（替代原 JOIN categories pc）
        Map<Integer, String> parentCategoryNames = loadParentCategoryNames(products);

        // 3. 逐条映射为跨服务 DTO
        List<ProductIndexItem> items = new ArrayList<>(products.size());
        for (Product p : products) {
            items.add(toIndexItem(p, shopMap.get(p.getMerchantId()), parentCategoryNames));
        }
        return items;
    }

    /**
     * 批量加载店铺信息（店铺名/Logo）
     * <p>
     * 降级策略：商家服务不可用时返回空 Map，索引条目只缺店铺名/Logo，不影响商品本身可搜。
     */
    private Map<Long, MerchantBrief> loadShops(List<Product> products) {
        Map<Long, MerchantBrief> map = new HashMap<>();
        String ids = products.stream()
                .map(Product::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<MerchantBrief>> r = merchantInternalClient.listBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (MerchantBrief b : r.getData()) {
                    if (b.getId() != null) {
                        map.put(b.getId(), b);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取店铺信息失败（索引将缺少店铺名/Logo），merchantIds={}", ids, e);
        }
        return map;
    }

    /**
     * 批量加载父分类名：{@code parentId → 分类名}
     * <p>
     * 原 SQL 为 {@code LEFT JOIN categories pc ON c.parent_id = pc.id}，
     * categories 属于本服务，故本地一次批量查询即可。
     */
    private Map<Integer, String> loadParentCategoryNames(List<Product> products) {
        List<Integer> parentIds = products.stream()
                .map(Product::getCategory)
                .filter(Objects::nonNull)
                .map(Category::getParentId)
                .filter(Objects::nonNull)
                .filter(pid -> pid != 0)
                .distinct()
                .collect(Collectors.toList());
        if (parentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, String> result = new HashMap<>();
        try {
            List<Category> parents = categoryMapper.selectBatchIds(parentIds);
            for (Category c : parents) {
                result.put(c.getId(), c.getName());
            }
        } catch (Exception e) {
            log.error("批量获取父分类失败（索引将缺少父分类名），parentIds={}", parentIds, e);
        }
        return result;
    }

    /** Product → 跨服务 ProductIndexItem */
    private ProductIndexItem toIndexItem(Product p, MerchantBrief shop, Map<Integer, String> parentCategoryNames) {
        ProductIndexItem item = new ProductIndexItem();
        item.setId(p.getId() == null ? null : String.valueOf(p.getId()));
        item.setName(p.getName());
        item.setDescription(p.getDescription());
        item.setMainImage(p.getMainImage());
        // images 原为 List<String>，跨服务以「JSON 字符串 + 列表」双形态承载，搜索服务可任选
        item.setImages(toJson(p.getImages()));
        item.setImageList(p.getImages());
        item.setCategoryId(p.getCategoryId());
        item.setMerchantId(p.getMerchantId() == null ? null : String.valueOf(p.getMerchantId()));
        item.setPriceMin(p.getPriceMin());
        item.setPriceMax(p.getPriceMax());
        item.setSalesCount(p.getSalesCount());
        item.setStatus(p.getStatus());
        item.setCreatedAt(p.getCreateTime());

        // 店铺字段（来自商家服务 Feign）
        if (shop != null) {
            item.setShopName(shop.getShopName());
            item.setShopLogo(shop.getLogoUrl());
        }

        // 分类字段（categories 属于本服务，本地联查已填充）
        Category category = p.getCategory();
        if (category != null) {
            item.setCategoryName(category.getName());
            if (category.getParentId() != null) {
                item.setParentCategoryName(parentCategoryNames.get(category.getParentId()));
            }
        }
        return item;
    }

    /** List&lt;String&gt; → JSON 字符串（失败返回 null，不让索引构建整体失败） */
    private String toJson(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("商品 images 序列化失败", e);
            return null;
        }
    }
}

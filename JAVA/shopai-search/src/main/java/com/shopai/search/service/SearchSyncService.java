package com.shopai.search.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantIndexItem;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.search.document.MerchantDocument;
import com.shopai.search.document.ProductDocument;
import com.shopai.search.feign.MerchantInternalClient;
import com.shopai.search.feign.ProductInternalClient;
import com.shopai.search.repository.MerchantDocumentRepository;
import com.shopai.search.repository.ProductDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索索引同步服务（增量同步 + 全量重建）
 * <p>
 * 微服务拆分说明：原单体商品/商家的 LIKE 搜索迁至 ES 后，本服务负责维护 ES 索引与源数据的一致性：
 * 1. MQ 增量同步：消费 product.changed / merchant.changed 事件后，取源数据最新值 upsert 到 ES。
 * 2. 全量重建：POST /api/search/rebuild 时全量拉取源数据，重建 product / merchant 索引。
 * <p>
 * 【数据来源改造】（按「独立库」处理）
 * 原先本服务通过 {@code ProductRefMapper} / {@code MerchantRefMapper} 跨库直查
 * products / merchants，并 JOIN merchants、categories、users 组装可检索字段。
 * 现在这几张表分别归属 shopai-product / shopai-merchant / shopai-user 服务，
 * 搜索服务不再直连数据库，改为 Feign 调用商品 / 商家服务的内部接口：
 * - 商品索引源数据：ProductInternalClient（店铺名/Logo、分类名/父分类名已由商品服务组装）
 * - 商家索引源数据：MerchantInternalClient（创建时间、账号状态已由商家服务组装）
 * 本服务只负责「拉取 → 转换 → 写 ES」，不持有任何数据库访问代码。
 */
@Service
public class SearchSyncService {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncService.class);

    // 微服务拆分新增：Feign 客户端（替代原 *RefMapper 跨库直查）
    @Autowired
    private ProductInternalClient productInternalClient;
    @Autowired
    private MerchantInternalClient merchantInternalClient;

    @Autowired
    private ProductDocumentRepository productRepository;
    @Autowired
    private MerchantDocumentRepository merchantRepository;

    // ==================== 商品同步 ====================

    /**
     * 根据商品ID增量同步单个商品到 ES（create/update 共用）
     * <p>
     * 源数据通过 Feign 从商品服务拉取。这里必须区分三种情况
     * （见 {@link #loadProduct}）：
     * <ul>
     *   <li><b>失败（返回 null）</b>：商品服务不可用/超时/降级 → <b>跳过</b>，保持索引原样，
     *       等 MQ 重试或全量重建；</li>
     *   <li><b>确认不存在（返回空列表）</b>：源端确实没有该商品 → 从索引删除；</li>
     *   <li><b>有数据</b>：正常 upsert。</li>
     * </ul>
     * 不能把「失败」与「不存在」混为一谈 —— 那会在商品服务抖动时把在售商品
     * 从搜索索引里**静默删除**。
     *
     * @param productId 商品ID
     */
    public void syncProduct(Long productId) {
        try {
            List<ProductIndexItem> loaded = loadProduct(productId);
            if (loaded == null) {
                // 拉取失败：不判断存在性，保持索引原样（宁可暂时陈旧，不可误删）
                log.warn("商品索引回源失败，跳过同步（保持索引原样）: productId={}", productId);
                return;
            }
            if (loaded.isEmpty()) {
                // 源端确认已无该商品 → 从索引删除
                productRepository.deleteById(String.valueOf(productId));
                log.info("商品 [{}] 源端不存在，已从 product 索引删除", productId);
                return;
            }
            ProductDocument doc = toProductDocument(loaded.get(0));
            productRepository.save(doc);
            log.info("商品 [{}] 已同步到 product 索引, status={}", productId, loaded.get(0).getStatus());
        } catch (Exception e) {
            log.error("同步商品到 ES 失败, productId={}", productId, e);
            // 同步失败不抛出（MQ 消费侧另有重试 + 死信兜底）
        }
    }

    /**
     * 根据商品ID从 ES 索引删除（delete 事件）
     */
    public void deleteProduct(Long productId) {
        try {
            productRepository.deleteById(String.valueOf(productId));
            log.info("商品 [{}] 已从 product 索引删除", productId);
        } catch (Exception e) {
            log.error("从 ES 删除商品失败, productId={}", productId, e);
        }
    }

    // ==================== 商家同步 ====================

    /**
     * 根据商家ID增量同步单个商家到 ES（create/update 共用）
     * <p>
     * 源数据通过 Feign 从商家服务拉取（商家服务侧已按「已认证 + 账号正常」过滤）。
     * 三种情况分别处理（与 {@link #syncProduct} 一致）：
     * <ul>
     *   <li><b>拉取失败（null）</b> → 跳过，保持索引原样；</li>
     *   <li><b>确认不存在/未认证（空列表）</b> → 从索引删除，保证搜索仅返回可用商家；</li>
     *   <li><b>有数据</b> → upsert。</li>
     * </ul>
     *
     * @param merchantId 商家ID
     */
    public void syncMerchant(Long merchantId) {
        try {
            List<MerchantIndexItem> loaded = loadMerchant(merchantId);
            if (loaded == null) {
                // 拉取失败：不判断存在性，保持索引原样（宁可暂时陈旧，不可误删）
                log.warn("商家索引回源失败，跳过同步（保持索引原样）: merchantId={}", merchantId);
                return;
            }
            // 源端确认无该商家，或返回的商家未认证/账号不可用 → 从索引删除
            if (loaded.isEmpty()
                    || loaded.get(0).getIsVerified() == null
                    || loaded.get(0).getIsVerified() != 1) {
                merchantRepository.deleteById(String.valueOf(merchantId));
                log.info("商家 [{}] 源端不存在或未认证，已从 merchant 索引删除", merchantId);
                return;
            }
            merchantRepository.save(toMerchantDocument(loaded.get(0)));
            log.info("商家 [{}] 已同步到 merchant 索引", merchantId);
        } catch (Exception e) {
            log.error("同步商家到 ES 失败, merchantId={}", merchantId, e);
        }
    }

    /**
     * 根据商家ID从 ES 索引删除（delete 事件）
     */
    public void deleteMerchant(Long merchantId) {
        try {
            merchantRepository.deleteById(String.valueOf(merchantId));
            log.info("商家 [{}] 已从 merchant 索引删除", merchantId);
        } catch (Exception e) {
            log.error("从 ES 删除商家失败, merchantId={}", merchantId, e);
        }
    }

    // ==================== 全量重建 ====================

    /**
     * 全量重建 product + merchant 索引
     * <p>
     * 说明：Spring Data Elasticsearch 的 save 即 upsert，重建采用「删旧索引 → 全量写入」策略，
     * 保证索引结构与文档一致。生产环境大数据量时建议用 _reindex 或滚动重建，此处为演示实现。
     * <p>
     * 微服务拆分说明：源数据由商品 / 商家服务内部接口提供（不再跨库直查）。
     *
     * @return 重建的商品数、商家数
     */
    public int[] rebuildAll() {
        long start = System.currentTimeMillis();
        // 1. 删除旧索引
        productRepository.deleteAll();
        merchantRepository.deleteAll();

        // 2. 全量重建商品索引（Feign 调商品服务内部接口）
        List<ProductIndexItem> products = loadAllProducts();
        List<ProductDocument> productDocs = products.stream()
                .map(this::toProductDocument)
                .collect(Collectors.toList());
        productRepository.saveAll(productDocs);

        // 3. 全量重建商家索引（Feign 调商家服务内部接口，已仅返回已认证且账号正常的商家）
        List<MerchantIndexItem> merchants = loadAllMerchants();
        List<MerchantDocument> merchantDocs = merchants.stream()
                .map(this::toMerchantDocument)
                .collect(Collectors.toList());
        merchantRepository.saveAll(merchantDocs);

        long cost = System.currentTimeMillis() - start;
        log.info("全量重建索引完成：商品 {} 条，商家 {} 条，耗时 {} ms", productDocs.size(), merchantDocs.size(), cost);
        return new int[]{productDocs.size(), merchantDocs.size()};
    }

    // ==================== 跨服务数据拉取（Feign） ====================

    /**
     * 拉取单个商品的索引源数据
     * <p>
     * <b>返回语义（三态，调用方必须区分）</b>：
     * <ul>
     *   <li>{@code null} → <b>拉取失败</b>（服务不可用/超时/降级）：调用方应**跳过**同步，
     *       保持索引原样；</li>
     *   <li>空列表 → <b>源端确认不存在</b>：调用方应从索引删除；</li>
     *   <li>非空列表 → 正常数据。</li>
     * </ul>
     * 说明：本方法原先在「失败」与「不存在」时都返回 null，导致调用方一律删除索引文档 ——
     * 商品服务一抖动就会把在售商品从搜索里静默删掉。现已改为三态区分。
     */
    private List<ProductIndexItem> loadProduct(Long productId) {
        try {
            Result<List<ProductIndexItem>> r = productInternalClient.listByIdsForIndex(String.valueOf(productId));
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                return r.getData() == null ? Collections.emptyList() : r.getData();
            }
            log.warn("商品索引回源返回非 200: productId={}, code={}, msg={}",
                    productId, r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用商品服务拉取索引源数据失败, productId={}", productId, e);
        }
        return null;
    }

    /**
     * 拉取单个商家的索引源数据
     * <p>
     * 返回语义同 {@link #loadProduct}：null = 拉取失败（跳过），空列表 = 确认不存在（删除）。
     */
    private List<MerchantIndexItem> loadMerchant(Long merchantId) {
        try {
            Result<List<MerchantIndexItem>> r = merchantInternalClient.listByIdsForIndex(String.valueOf(merchantId));
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                return r.getData() == null ? Collections.emptyList() : r.getData();
            }
            log.warn("商家索引回源返回非 200: merchantId={}, code={}, msg={}",
                    merchantId, r == null ? null : r.getCode(), r == null ? null : r.getMessage());
        } catch (Exception e) {
            log.error("调用商家服务拉取索引源数据失败, merchantId={}", merchantId, e);
        }
        return null;
    }

    /** 拉取全量商品索引源数据（Feign） */
    private List<ProductIndexItem> loadAllProducts() {
        try {
            Result<List<ProductIndexItem>> r = productInternalClient.listAllForIndex();
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
        } catch (Exception e) {
            log.error("调用商品服务拉取全量索引源数据失败", e);
        }
        return Collections.emptyList();
    }

    /** 拉取全量商家索引源数据（Feign） */
    private List<MerchantIndexItem> loadAllMerchants() {
        try {
            Result<List<MerchantIndexItem>> r = merchantInternalClient.listAllForIndex();
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData();
            }
        } catch (Exception e) {
            log.error("调用商家服务拉取全量索引源数据失败", e);
        }
        return Collections.emptyList();
    }

    // ==================== 转换 ====================

    /**
     * ProductIndexItem（跨服务 DTO）→ ProductDocument（ES 文档）
     * <p>
     * 说明：店铺名/Logo、分类名/父分类名已由商品服务在拉取时组装完成，
     * 本服务不再做任何联查。
     */
    private ProductDocument toProductDocument(ProductIndexItem ref) {
        return ProductDocument.builder()
                .id(ref.getId())
                .name(ref.getName())
                .description(ref.getDescription())
                .mainImage(ref.getMainImage())
                .categoryId(ref.getCategoryId())
                .categoryName(ref.getCategoryName())
                .parentCategoryName(ref.getParentCategoryName())
                .merchantId(ref.getMerchantId())
                .shopName(ref.getShopName())
                .shopLogo(ref.getShopLogo())
                .priceMin(ref.getPriceMin())
                .priceMax(ref.getPriceMax())
                .salesCount(ref.getSalesCount())
                .status(ref.getStatus())
                .createdAt(ref.getCreatedAt())
                // 商品服务同时返回 imageList（List<String>）与 images（JSON 字符串），
                // ES 文档字段为 List<String>，取 imageList；缺失时退回空列表
                .images(ref.getImageList() != null ? ref.getImageList() : Collections.emptyList())
                .build();
    }

    /**
     * MerchantIndexItem（跨服务 DTO）→ MerchantDocument（ES 文档）
     * <p>
     * 说明：创建时间（原 JOIN users 取 users.created_at）已由商家服务组装完成。
     */
    private MerchantDocument toMerchantDocument(MerchantIndexItem ref) {
        return MerchantDocument.builder()
                .id(ref.getId())
                .shopName(ref.getShopName())
                .logoUrl(ref.getLogoUrl())
                .rating(ref.getRating())
                .isVerified(ref.getIsVerified())
                .shopDescription(ref.getShopDescription())
                .createdAt(ref.getCreatedAt())
                .build();
    }
}

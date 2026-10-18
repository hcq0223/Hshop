package com.shopai.seckill.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.seckill.dto.SeckillActivityVO;
import com.shopai.seckill.dto.SeckillItemVO;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.feign.ProductInternalClient;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀展示信息组装服务
 * <p>
 * 职责：把「本地库存/价格」与「商品服务的商品、SKU 信息」拼成前端能直接渲染的视图。
 * <p>
 * <b>为什么不把商品名/图片冗余进 seckill_stock</b>：那些是会变的
 * （商家改个商品名、换张主图），冗余进来就会出现「秒杀页还挂着两周前的旧图」。
 * 而归属关系（productId / merchantId）不会变，才适合冗余。
 * <p>
 * <b>批量取而不是逐条取</b>：一个活动可能有几十个商品，逐个 Feign 调用就是
 * N+1 次跨服务往返；这里一次性 {@code /products?ids=...} 批量拉回来再在内存里配对。
 */
@Service
public class SeckillInfoService {

    private static final Logger log = LoggerFactory.getLogger(SeckillInfoService.class);

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private ProductInternalClient productInternalClient;

    /**
     * 组装活动详情视图。
     *
     * @param activity 活动
     * @param userId   当前用户ID（用于标注「我是否已抢过」）；未登录传 null
     * @return 活动视图
     */
    public SeckillActivityVO buildActivityVO(SeckillActivity activity, Long userId) {
        SeckillActivityVO vo = new SeckillActivityVO();
        vo.setId(activity.getId());
        vo.setTitle(activity.getTitle());
        vo.setMerchantId(activity.getMerchantId());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setStatus(activity.getStatus());
        vo.setPerUserLimit(activity.getPerUserLimit());
        vo.setDescription(activity.getDescription());
        vo.setTotalStock(activity.getTotalStock());
        vo.setSoldCount(activity.getSoldCount());
        // 服务端时间是倒计时的权威依据，前端不信任本地时钟
        vo.setServerTime(LocalDateTime.now());

        List<SeckillStock> stocks = stockMapper.selectByActivityId(activity.getId());
        vo.setItems(buildItemVOs(stocks, userId));
        return vo;
    }

    /**
     * 批量组装商品条目视图。
     *
     * @param stocks 库存额度列表
     * @param userId 当前用户ID（可 null）
     */
    public List<SeckillItemVO> buildItemVOs(List<SeckillStock> stocks, Long userId) {
        if (stocks == null || stocks.isEmpty()) {
            return new ArrayList<>();
        }
        // 一次性把涉及的商品批量拉回来（避免 N+1 次跨服务调用）
        Set<Long> productIds = stocks.stream()
                .map(SeckillStock::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductInfo> productMap = loadProducts(productIds);

        // SKU 信息逐个取：商品服务没有「按多个SKU ID批量查」的内部接口，
        // 而 SKU 数量与商品数量同量级，这里的 N 不会失控。
        // 若后续活动商品数变多，可在商品服务补一个批量接口再换过来。
        Map<Long, ProductSkuInfo> skuMap = loadSkus(stocks);

        List<SeckillItemVO> items = new ArrayList<>(stocks.size());
        for (SeckillStock stock : stocks) {
            items.add(buildItemVO(stock, productMap.get(stock.getProductId()),
                    skuMap.get(stock.getSkuId()), userId));
        }
        return items;
    }

    /**
     * 组装单个条目。
     */
    private SeckillItemVO buildItemVO(SeckillStock stock, ProductInfo product,
                                     ProductSkuInfo sku, Long userId) {
        SeckillItemVO vo = new SeckillItemVO();
        vo.setStockId(stock.getId());
        vo.setSkuId(stock.getSkuId());
        vo.setProductId(stock.getProductId());
        vo.setSeckillPrice(stock.getSeckillPrice());
        vo.setOriginPrice(stock.getOriginPrice());
        vo.setTotalStock(stock.getTotalStock());
        vo.setSoldStock(stock.getSoldStock());
        vo.setPerUserLimit(stock.getPerUserLimit());

        // 剩余库存：以 DB 为准展示。
        // 活动进行中 Redis 才是真实余量，但 SeckillInfoService 不注入 Redis ——
        // 保持「展示层不碰热点存储」的边界；调用方（Controller）会在需要时
        // 用 SeckillRedisService 覆盖这个值。见 SeckillController 的实现。
        vo.setAvailableStock(stock.getAvailableStock());
        vo.setStockSource("db");

        boolean missing = false;
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductImage(product.getMainImage());
        } else {
            missing = true;
        }
        if (sku != null) {
            vo.setSkuImage(sku.getImageUrl());
            vo.setNormalPrice(sku.getPrice());
            vo.setSkuAttrs(buildSkuAttrs(sku));
        } else {
            missing = true;
        }
        if (vo.getSkuImage() == null) {
            vo.setSkuImage(vo.getProductImage());
        }
        vo.setInfoMissing(missing);
        vo.setSoldOut(stock.soldOut());
        // 未登录用户不判断「已抢过」
        vo.setBoughtByMe(null);
        return vo;
    }

    /**
     * 规格描述，如「藏青色 / M」。两个都为空时返回 null（前端不展示这一行）。
     */
    private String buildSkuAttrs(ProductSkuInfo sku) {
        StringBuilder sb = new StringBuilder();
        if (sku.getColor() != null && !sku.getColor().isBlank()) {
            sb.append(sku.getColor());
        }
        if (sku.getSize() != null && !sku.getSize().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(sku.getSize());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 批量拉商品信息。
     * <p>
     * 拉取失败时返回空 Map（视图里的商品字段留空、{@code infoMissing=true}），
     * 而不是抛异常把整个活动详情页打挂 —— 活动时间、库存、价格这些核心信息
     * 都在本地，商品名拉不到不该导致整页 500。
     */
    private Map<Long, ProductInfo> loadProducts(Set<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            Result<List<ProductInfo>> r = productInternalClient.listProducts(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData().stream()
                        .filter(p -> p.getId() != null)
                        .collect(Collectors.toMap(ProductInfo::getId, Function.identity(), (a, b) -> a));
            }
            log.warn("批量拉取商品信息失败, ids={}, msg={}", ids, r == null ? "null" : r.getMessage());
        } catch (Exception e) {
            log.error("批量拉取商品信息异常, ids={}", ids, e);
        }
        return Collections.emptyMap();
    }

    /**
     * 逐个拉 SKU 信息。
     * <p>
     * 同样：失败只让该条目的规格字段为空，不影响整个列表返回。
     */
    private Map<Long, ProductSkuInfo> loadSkus(List<SeckillStock> stocks) {
        Map<Long, ProductSkuInfo> map = new HashMap<>();
        for (SeckillStock stock : stocks) {
            if (stock.getSkuId() == null || map.containsKey(stock.getSkuId())) {
                continue;
            }
            try {
                Result<ProductSkuInfo> r = productInternalClient.getSku(stock.getSkuId());
                if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                    map.put(stock.getSkuId(), r.getData());
                } else {
                    log.warn("拉取SKU信息失败, skuId={}, msg={}", stock.getSkuId(),
                            r == null ? "null" : r.getMessage());
                }
            } catch (Exception e) {
                log.error("拉取SKU信息异常, skuId={}", stock.getSkuId(), e);
            }
        }
        return map;
    }
}

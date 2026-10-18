package com.shopai.seckill.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.seckill.constant.SeckillStatus;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.feign.ProductInternalClient;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动管理服务（商家端 / 管理端）
 * <p>
 * <b>权限原则</b>：所有涉及 merchantId 的方法都由调用方（Controller）传入
 * 「当前登录 userId 经 Feign 换取的真实 merchantId」，<b>绝不接受前端传来的 merchantId</b>。
 * 否则商家 A 只要把 merchantId 改成 B 的，就能改别人的活动。
 * <p>
 * <b>价格校验</b>：创建/修改库存额度时都会去商品服务核对 SKU 的真实价格，
 * 拒绝「秒杀价 >= 日常价」—— 那不叫秒杀，是欺骗用户。
 * 这也是为什么商品服务 Feign 的降级必须返回失败（见
 * {@code ProductInternalClientFallback}），返回默认价会放过非法活动。
 */
@Service
public class SeckillActivityService {

    private static final Logger log = LoggerFactory.getLogger(SeckillActivityService.class);

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private ProductInternalClient productInternalClient;

    @Autowired
    private SeckillRedisService redisService;

    // ==================== 活动 CRUD ====================

    /**
     * 创建活动（草稿态，此时还不能加商品）。
     *
     * @param merchantId 当前商家的 merchantId（由 Controller 解析后传入，可信）
     * @param activity   活动信息
     * @return 创建后的活动
     */
    @Transactional
    public SeckillActivity createActivity(Long merchantId, SeckillActivity activity) {
        validateTimeWindow(activity.getStartTime(), activity.getEndTime());
        activity.setMerchantId(merchantId);
        activity.setStatus(SeckillStatus.DRAFT);
        activity.setTotalStock(0);
        activity.setSoldCount(0);
        activity.setVersion(0);
        activity.setDeleted(0);
        activityMapper.insert(activity);
        log.info("秒杀活动已创建, activityId={}, merchantId={}, title={}",
                activity.getId(), merchantId, activity.getTitle());
        return activity;
    }

    /**
     * 更新活动基本信息（仅草稿/下线态可改）。
     */
    @Transactional
    public void updateActivity(Long merchantId, SeckillActivity activity) {
        SeckillActivity exist = getOwnedActivity(merchantId, activity.getId());
        if (!SeckillStatus.editable(exist.getStatus())) {
            // 一旦发布，Redis 里已经有预热的库存数字，改时间窗会让「预热过的活动」
            // 与实际开抢时间错位。要改先下线回草稿，改完重新发布并重新预热。
            throw new RuntimeException("活动已发布，请先下线再修改");
        }
        validateTimeWindow(activity.getStartTime(), activity.getEndTime());
        exist.setTitle(activity.getTitle());
        exist.setStartTime(activity.getStartTime());
        exist.setEndTime(activity.getEndTime());
        exist.setPerUserLimit(activity.getPerUserLimit());
        exist.setDescription(activity.getDescription());
        activityMapper.updateById(exist);
        log.info("秒杀活动已更新, activityId={}, merchantId={}", exist.getId(), merchantId);
    }

    /**
     * 逻辑删除活动（仅草稿/下线态可删）。
     */
    @Transactional
    public void deleteActivity(Long merchantId, Long activityId) {
        SeckillActivity exist = getOwnedActivity(merchantId, activityId);
        if (!SeckillStatus.editable(exist.getStatus())) {
            throw new RuntimeException("活动已发布，请先下线再删除");
        }
        // 连带删掉库存额度，避免留下孤儿记录
        List<SeckillStock> stocks = stockMapper.selectByActivityId(activityId);
        for (SeckillStock s : stocks) {
            stockMapper.deleteById(s.getId());
        }
        activityMapper.deleteById(activityId);
        log.info("秒杀活动已删除, activityId={}, merchantId={}, 同时清理{}条库存额度",
                activityId, merchantId, stocks.size());
    }

    /**
     * 查询商家自己的活动列表。
     *
     * @param merchantId 商家ID
     * @param status     状态筛选（可 null）
     */
    public List<SeckillActivity> listMyActivities(Long merchantId, String status) {
        return activityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getMerchantId, merchantId)
                        .eq(status != null && !status.isBlank(), SeckillActivity::getStatus, status)
                        .orderByDesc(SeckillActivity::getCreatedAt));
    }

    /**
     * 查询单个活动（商家归属校验）。
     */
    public SeckillActivity getOwnedActivity(Long merchantId, Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new RuntimeException("活动不存在");
        }
        if (!activity.getMerchantId().equals(merchantId)) {
            // 不区分「不存在」与「不属于你」，避免通过错误信息探测他人活动ID是否存在
            throw new RuntimeException("活动不存在");
        }
        return activity;
    }

    // ==================== 库存额度管理 ====================

    /**
     * 给活动添加/更新一个 SKU 的秒杀额度。
     * <p>
     * 幂等：同一活动同一 SKU 重复调用是「更新」而不是「新增」，
     * 对应 {@code uk_activity_sku} 唯一索引。
     *
     * @param merchantId   商家ID
     * @param activityId   活动ID
     * @param skuId        SKU ID
     * @param seckillPrice 秒杀价
     * @param totalStock   秒杀额度
     * @param perUserLimit 每人限购（可 null，取活动默认）
     * @param sort         排序
     */
    @Transactional
    public SeckillStock saveStock(Long merchantId, Long activityId, Long skuId,
                                  BigDecimal seckillPrice, Integer totalStock,
                                  Integer perUserLimit, Integer sort) {
        SeckillActivity activity = getOwnedActivity(merchantId, activityId);
        if (!SeckillStatus.editable(activity.getStatus())) {
            throw new RuntimeException("活动已发布，库存与商品不可修改");
        }
        if (skuId == null) {
            throw new RuntimeException("skuId 不能为空");
        }
        if (totalStock == null || totalStock <= 0) {
            throw new RuntimeException("秒杀库存必须大于 0");
        }
        if (seckillPrice == null || seckillPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("秒杀价必须大于 0");
        }

        // 去商品服务核对 SKU 真实信息（规格、日常价、所属商品）
        ProductSkuInfo sku = requireSku(skuId);
        ProductInfo product = requireProduct(sku.getProductId());

        // 校验：该商品必须属于当前商家，否则商家A能把商家B的商品挂到自己的秒杀里
        if (product.getMerchantId() == null || !product.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("该商品不属于当前商家");
        }
        // 校验：秒杀价必须低于日常价
        if (sku.getPrice() != null && seckillPrice.compareTo(sku.getPrice()) >= 0) {
            throw new RuntimeException("秒杀价必须低于商品日常价 " + sku.getPrice());
        }

        // 同一活动同一 SKU 已存在则更新，否则新增
        SeckillStock exist = stockMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillStock>()
                        .eq(SeckillStock::getActivityId, activityId)
                        .eq(SeckillStock::getSkuId, skuId));

        if (exist != null) {
            if (exist.getPreheated() != null && exist.getPreheated() == 1) {
                // 已预热说明 Redis 里有旧数字，改总额度会造成「库里说 100、Redis 里还是 50」
                throw new RuntimeException("该商品已预热，请先下线活动并清除预热后再修改");
            }
            exist.setSeckillPrice(seckillPrice);
            exist.setOriginPrice(sku.getPrice());
            exist.setTotalStock(totalStock);
            exist.setAvailableStock(totalStock);
            exist.setPerUserLimit(perUserLimit == null ? activity.getPerUserLimit() : perUserLimit);
            exist.setSort(sort == null ? 0 : sort);
            stockMapper.updateById(exist);
            log.info("秒杀库存额度已更新, stockId={}, skuId={}, totalStock={}",
                    exist.getId(), skuId, totalStock);
            return exist;
        }

        SeckillStock stock = new SeckillStock();
        stock.setActivityId(activityId);
        stock.setSkuId(skuId);
        stock.setProductId(sku.getProductId());
        stock.setMerchantId(merchantId);
        stock.setSeckillPrice(seckillPrice);
        // 原价是「创建时的快照」，只做划线价展示，不是交易依据
        stock.setOriginPrice(sku.getPrice());
        stock.setTotalStock(totalStock);
        stock.setAvailableStock(totalStock);
        stock.setLockedStock(0);
        stock.setSoldStock(0);
        stock.setVersion(0);
        stock.setPerUserLimit(perUserLimit == null ? activity.getPerUserLimit() : perUserLimit);
        stock.setSort(sort == null ? 0 : sort);
        stock.setPreheated(0);
        stock.setDeleted(0);
        stockMapper.insert(stock);
        log.info("秒杀库存额度已新增, stockId={}, activityId={}, skuId={}, totalStock={}",
                stock.getId(), activityId, skuId, totalStock);
        return stock;
    }

    /**
     * 删除某个 SKU 的秒杀额度（仅草稿/下线态）。
     */
    @Transactional
    public void deleteStock(Long merchantId, Long stockId) {
        SeckillStock stock = stockMapper.selectById(stockId);
        if (stock == null) {
            throw new RuntimeException("秒杀商品不存在");
        }
        SeckillActivity activity = getOwnedActivity(merchantId, stock.getActivityId());
        if (!SeckillStatus.editable(activity.getStatus())) {
            throw new RuntimeException("活动已发布，不可删除商品");
        }
        if (stock.getPreheated() != null && stock.getPreheated() == 1) {
            throw new RuntimeException("该商品已预热，请先下线活动并清除预热后再删除");
        }
        stockMapper.deleteById(stockId);
        log.info("秒杀库存额度已删除, stockId={}, activityId={}", stockId, stock.getActivityId());
    }

    // ==================== 发布 / 下线 / 预热 ====================

    /**
     * 发布活动：校验有商品、汇总总库存、置为待开始。
     * <p>
     * 发布后活动进入 {@code pending}，等待预热任务把库存刷进 Redis。
     * 真正开抢要等时间窗到达（由状态流转任务置为 running）。
     */
    @Transactional
    public void publishActivity(Long merchantId, Long activityId) {
        SeckillActivity activity = getOwnedActivity(merchantId, activityId);
        if (!SeckillStatus.editable(activity.getStatus())) {
            throw new RuntimeException("活动当前状态不可发布");
        }
        List<SeckillStock> stocks = stockMapper.selectByActivityId(activityId);
        if (stocks.isEmpty()) {
            throw new RuntimeException("活动未添加任何秒杀商品，不能发布");
        }
        validateTimeWindow(activity.getStartTime(), activity.getEndTime());
        if (activity.getEndTime().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("活动结束时间已过，不能发布");
        }

        activity.setStatus(SeckillStatus.PENDING);
        activityMapper.updateById(activity);
        // 汇总总库存进冗余字段，活动列表页就不用去聚合 seckill_stock
        activityMapper.refreshTotalStock(activityId);
        log.info("秒杀活动已发布, activityId={}, merchantId={}, sku数={}",
                activityId, merchantId, stocks.size());
    }

    /**
     * 下线活动：立即停止抢购（即使还在时间窗内），并清掉 Redis 预热库存。
     * <p>
     * 清 Redis 的原因：不清的话，活动下线后 Redis 里还留着库存数字，
     * 万一有代码路径漏判 status（比如只判了时间窗），用户还能继续抢。
     * 清掉后扣减脚本会因为 Key 不存在而返回 -2，多一层保险。
     */
    @Transactional
    public void offlineActivity(Long merchantId, Long activityId) {
        SeckillActivity activity = getOwnedActivity(merchantId, activityId);
        if (SeckillStatus.ENDED.equals(activity.getStatus())) {
            throw new RuntimeException("活动已结束，无需下线");
        }
        activity.setStatus(SeckillStatus.OFFLINE);
        activityMapper.updateById(activity);
        clearPreheat(activityId);
        log.info("秒杀活动已下线, activityId={}, merchantId={}", activityId, merchantId);
    }

    /**
     * 清除活动下所有 SKU 的预热标记与 Redis 库存 Key。
     * <p>
     * 下线、删除、以及「管理员手动重新预热」前都会调用。
     */
    @Transactional
    public void clearPreheat(Long activityId) {
        List<SeckillStock> stocks = stockMapper.selectByActivityId(activityId);
        for (SeckillStock s : stocks) {
            redisService.evictStock(activityId, s.getId());
            stockMapper.markPreheated(s.getId(), 0);
        }
        log.info("已清除活动预热, activityId={}, sku数={}", activityId, stocks.size());
    }

    // ==================== 私有工具 ====================

    /**
     * 校验时间窗合法性。
     */
    private void validateTimeWindow(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new RuntimeException("开始时间与结束时间不能为空");
        }
        if (!end.isAfter(start)) {
            throw new RuntimeException("结束时间必须晚于开始时间");
        }
    }

    /**
     * 取 SKU，取不到直接抛异常。
     * <p>
     * 这里刻意「失败即抛出」而不是返回 null 让调用方判空：创建活动是个写操作，
     * 拿不到 SKU 真实价格就无法做合法性校验，宁可创建失败也不能放行一个
     * 未经校验（可能秒杀价高于日常价）的活动。
     */
    private ProductSkuInfo requireSku(Long skuId) {
        Result<ProductSkuInfo> r = productInternalClient.getSku(skuId);
        if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
            throw new RuntimeException("无法获取SKU信息（商品服务不可用或SKU不存在）: " + skuId);
        }
        return r.getData();
    }

    /**
     * 取商品，取不到直接抛异常（理由同上）。
     */
    private ProductInfo requireProduct(Long productId) {
        Result<ProductInfo> r = productInternalClient.getProduct(productId);
        if (r == null || r.getCode() == null || r.getCode() != 200 || r.getData() == null) {
            throw new RuntimeException("无法获取商品信息（商品服务不可用或商品不存在）: " + productId);
        }
        return r.getData();
    }
}

package com.shopai.inventory.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.dto.StockItem;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.inventory.entity.Inventory;
import com.shopai.inventory.mapper.InventoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 库存服务实现
 * <p>
 * 微服务拆分说明：从单体 com.example.demo.service.ProductSkuService 迁移全部库存逻辑，
 * 并对接独立 inventory 表。原方法映射：
 * - decreaseStock       → 乐观锁扣减（数据库，供订单服务 Feign 调用）
 * - restoreStockBySkuId → restoreStock（回补，供订单取消/退款调用）
 * - preheatStock        → 秒杀库存预热（Redis）
 * - getSeckillStock     → 秒杀剩余库存查询（Redis）
 * - seckill             → 秒杀抢购（Redis 原子扣减 + 异步落库 + 补偿）
 * <p>
 * 新增方法：
 * - getStock（单个查询）、batchGetStock（批量查询）、initStock（SKU 创建时初始化）、increaseStock（补货）
 * <p>
 * 乐观锁防超卖：deductStock 走 inventory 表 WHERE stock >= #{qty} 原子判断。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;InventoryMapper, Inventory&gt; 实现 InventoryService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）；
 * 保留原 @Autowired 字段与方法体，现有 mapper 调用不变。
 */
@Service
public class InventoryServiceImpl extends ServiceImpl<InventoryMapper, Inventory> implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;

    // ==================== 库存查询 ====================

    /**
     * 查询单个 SKU 的库存
     *
     * @param skuId SKU ID
     * @return 库存数量；若 inventory 无记录则返回 0
     */
    @Override
    public Integer getStock(Long skuId) {
        Inventory inv = inventoryMapper.selectBySkuId(skuId);
        return inv == null ? 0 : inv.getStock();
    }

    /**
     * 批量查询 SKU 库存（商品服务 Feign 调用，用于 SKU 列表补库存字段）
     *
     * @param skuIds SKU ID 列表
     * @return 库存条目列表（StockItem：skuId + stock）
     */
    @Override
    public List<StockItem> batchGetStock(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<StockItem> result = new ArrayList<>(skuIds.size());
        for (Long skuId : skuIds) {
            Inventory inv = inventoryMapper.selectBySkuId(skuId);
            int stock = inv == null ? 0 : inv.getStock();
            result.add(new StockItem(skuId, stock));
        }
        return result;
    }

    // ==================== 扣减 / 回补 / 补货（数据库） ====================

    /**
     * 扣减库存（乐观锁，防超卖）
     * <p>
     * 微服务拆分说明：原 ProductSkuService.decreaseStock 扣 product_skus.stock，
     * 拆分后改为扣 inventory 表。本方法作为 Seata AT 分支事务的参与方，
     * 由订单服务 @GlobalTransactional 内通过 Feign 调用（POST /api/inventory/deduct）。
     *
     * @param skuId SKU ID
     * @param qty   扣减数量
     * @return true=扣减成功，false=库存不足或 SKU 不存在
     */
    @Override
    @Transactional
    public boolean deductStock(Long skuId, int qty) {
        int rows = inventoryMapper.deductStock(skuId, qty);
        if (rows > 0) {
            // 扣减成功后清除库存缓存（如有），保证读取一致性。
            // 事务安全：放到**事务提交后**执行。原实现在事务内直接删缓存，
            // 并发读会立刻回源查库、读到的还是扣减前的旧库存，再写回缓存 → 脏数据。
            AfterCommitExecutor.run(
                    () -> redisService.deleteKey(RedisService.CACHE_INVENTORY + skuId));
            log.debug("库存扣减成功, skuId={}, qty={}", skuId, qty);
            return true;
        }
        log.warn("库存扣减失败（库存不足或SKU不存在）, skuId={}, qty={}", skuId, qty);
        return false;
    }

    /**
     * 回补库存（订单取消/退款时调用）
     * <p>
     * 微服务拆分说明：原 ProductSkuService.restoreStockBySkuId，拆分后改为回补 inventory 表。
     *
     * @param skuId SKU ID
     * @param qty   回补数量
     */
    @Override
    @Transactional
    public void restoreStock(Long skuId, int qty) {
        inventoryMapper.restoreStock(skuId, qty);
        // 事务安全：提交后再删缓存（理由同 deductStock）
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_INVENTORY + skuId));
        log.debug("库存回补成功, skuId={}, qty={}", skuId, qty);
    }

    /**
     * 增加可售库存（SKU 补货用）
     *
     * @param skuId SKU ID
     * @param qty   增加数量
     */
    @Override
    @Transactional
    public void increaseStock(Long skuId, int qty) {
        inventoryMapper.increaseStock(skuId, qty);
        // 事务安全：提交后再删缓存（理由同 deductStock）
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_INVENTORY + skuId));
        log.debug("库存增加成功, skuId={}, qty={}", skuId, qty);
    }

    /**
     * 初始化库存（SKU 创建时调用）
     * <p>
     * 微服务拆分说明：库存独立后，SKU 创建时需在 inventory 表建立对应记录。
     * 幂等：若已存在该 skuId 的库存记录，则跳过（不重复初始化）。
     *
     * @param skuId    SKU ID
     * @param initStock 初始库存
     */
    @Override
    @Transactional
    public void initStock(Long skuId, int initStock) {
        Inventory existing = inventoryMapper.selectBySkuId(skuId);
        if (existing != null) {
            log.debug("库存记录已存在，跳过初始化, skuId={}", skuId);
            return;
        }
        Inventory inv = new Inventory();
        inv.setSkuId(skuId);
        inv.setStock(initStock);
        inv.setLockedStock(0);
        inv.setVersion(0);
        inventoryMapper.insert(inv);
        log.debug("库存初始化成功, skuId={}, stock={}", skuId, initStock);
    }

    // ==================== 秒杀（Redis） ====================

//    /**
//     * 秒杀库存预热（管理员在活动开始前调用，将库存加载到 Redis）
//     * <p>
//     * 预热后秒杀期间的库存查询和扣减都走 Redis，极大提升并发能力。
//     *
//     * @param skuId SKU ID
//     */
//    @Override
//    public void preheatStock(Long skuId) {
//        Inventory inv = inventoryMapper.selectBySkuId(skuId);
//        if (inv == null) {
//            throw new RuntimeException("库存记录不存在，SKU ID: " + skuId);
//        }
//        // 将数据库库存设置到 Redis，TTL 设置为 1 小时（秒杀活动时长）
//        String key = RedisService.SKU_STOCK + skuId;
//        redisService.setCache(key, inv.getStock(), 3600);
//        log.info("秒杀库存预热成功, skuId={}, stock={}", skuId, inv.getStock());
//    }
//
//    /**
//     * 查询秒杀剩余库存（从 Redis 直接读取，不走数据库）
//     * <p>
//     * 返回 -1 表示未预热或已过期，前端应提示"秒杀未开始或已结束"。
//     *
//     * @param skuId SKU ID
//     * @return 剩余库存，-1 表示未预热或已过期
//     */
//    @Override
//    public Long getSeckillStock(Long skuId) {
//        String key = RedisService.SKU_STOCK + skuId;
//        Integer stock = redisService.getCache(key, Integer.class);
//        if (stock == null) {
//            log.warn("秒杀库存未预热或已过期, skuId={}", skuId);
//            return -1L;
//        }
//        return stock.longValue();
//    }
//
//    /**
//     * 秒杀核心方法（用户点击立即购买）
//     * <p>
//     * 流程：
//     * 1. 防重复抢购（同一用户同一SKU加分布式锁 5 秒）
//     * 2. 原子扣减 Redis 库存（Lua 脚本防超卖）
//     * 3. 异步同步到数据库（补偿机制保证最终一致性）
//     *
//     * @param skuId    SKU ID
//     * @param quantity 购买数量
//     * @param userId   用户ID（用于防重复抢购）
//     * @return true=抢到了，false=库存不足或已抢过
//     */
//    @Override
//    public boolean seckill(Long skuId, int quantity, Long userId) {
//        // 2.1 防重复抢购（同一个用户只能抢一次，用 Redis 分布式锁或 SetNX）
//        String lockKey = "seckill:user:" + skuId + ":" + userId;
//        Boolean locked = redisService.tryLock(lockKey, String.valueOf(userId), 5000); // 5秒锁
//        if (!locked) {
//            log.warn("用户重复秒杀, skuId={}, userId={}", skuId, userId);
//            return false;
//        }
//
//        try {
//            // 2.2 原子扣减 Redis 库存（核心！）
//            Long result = redisService.deductStock(String.valueOf(skuId), quantity);
//
//            if (result == null || result < 0) {
//                // -1 库存不足，-2 缓存不存在或异常
//                if (result == -2) {
//                    log.warn("Redis库存Key不存在, 降级走数据库, skuId={}", skuId);
//                    // 降级方案：如果 Redis 缓存丢了，直接走数据库扣减（同步）
//                    return deductStock(skuId, quantity);
//                }
//                return false; // 库存不足
//            }
//
//            // 2.3 Redis 扣减成功，异步扣减数据库（保证最终一致性）
//            cacheDelayScheduler.schedule(() -> {
//                // 异步落库 + 补偿
//                asyncDeductDatabaseAndCompensate(skuId, quantity);
//            }, 0, TimeUnit.MILLISECONDS);
//
//            log.info("秒杀抢购成功, skuId={}, userId={}, 剩余库存={}", skuId, userId, result);
//            return true;
//
//        } finally {
//            // 释放防重复锁
//            redisService.unlock(lockKey, String.valueOf(userId));
//        }
//    }
//
//    /**
//     * 异步扣减数据库库存 + 失败补偿
//     */
//    private void asyncDeductDatabaseAndCompensate(Long skuId, int quantity) {
//        try {
//            // 执行数据库扣减（利用乐观锁条件 stock >= quantity）
//            boolean ok = deductStock(skuId, quantity);
//            if (ok) {
//                log.debug("数据库库存异步扣减成功, skuId={}, quantity={}", skuId, quantity);
//            } else {
//                // 数据库扣减失败（说明 DB 库存被其他事务扣光了，或者因为回滚）
//                log.error("数据库库存异步扣减失败，开始补偿回滚Redis, skuId={}", skuId);
//                // 补偿：把刚刚扣掉的 Redis 库存加回去！
//                redisService.restoreStock(String.valueOf(skuId), quantity);
//                log.warn("Redis库存已回滚补偿, skuId={}, 回滚数量={}", skuId, quantity);
//            }
//        } catch (Exception e) {
//            // 如果异步任务抛出异常（比如数据库连接超时），也要补偿回滚
//            log.error("异步扣减数据库异常，执行Redis回滚补偿, skuId={}", skuId, e);
//            redisService.restoreStock(String.valueOf(skuId), quantity);
//        }
//    }
}

package com.shopai.inventory.service;

import com.shopai.common.dto.StockItem;

import java.util.List;

/**
 * 库存服务接口
 * <p>
 * 微服务拆分说明：从单体 com.example.demo.service.ProductSkuService 迁移全部库存逻辑，
 * 并对接独立 inventory 表。原方法映射：
 * - decreaseStock       → 乐观锁扣减（数据库，供订单服务 Feign 调用）
 * - restoreStockBySkuId → restoreStock（回补，供订单取消/退款调用）
 * - preheatStock        → 秒杀库存预热（Redis）
 * - getSeckillStock     → 秒杀剩余库存查询（Redis）
 * - seckill             → 秒杀抢购（Redis 原子扣减 + 异步落库 + 补偿）
 * <p>
 * 分层改造（2026-10-05）：本接口 + InventoryServiceImpl（extends ServiceImpl&lt;InventoryMapper, Inventory&gt;）
 * 标准分层，实现类可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）。
 */
public interface InventoryService {

    /**
     * 查询单个 SKU 的库存
     *
     * @param skuId SKU ID
     * @return 库存数量；若 inventory 无记录则返回 0
     */
    Integer getStock(Long skuId);

    /**
     * 批量查询 SKU 库存（商品服务 Feign 调用，用于 SKU 列表补库存字段）
     *
     * @param skuIds SKU ID 列表
     * @return 库存条目列表（StockItem：skuId + stock）
     */
    List<StockItem> batchGetStock(List<Long> skuIds);

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
    boolean deductStock(Long skuId, int qty);

    /**
     * 回补库存（订单取消/退款时调用）
     * <p>
     * 微服务拆分说明：原 ProductSkuService.restoreStockBySkuId，拆分后改为回补 inventory 表。
     *
     * @param skuId SKU ID
     * @param qty   回补数量
     */
    void restoreStock(Long skuId, int qty);

    /**
     * 增加可售库存（SKU 补货用）
     *
     * @param skuId SKU ID
     * @param qty   增加数量
     */
    void increaseStock(Long skuId, int qty);

    /**
     * 初始化库存（SKU 创建时调用）
     * <p>
     * 微服务拆分说明：库存独立后，SKU 创建时需在 inventory 表建立对应记录。
     * 幂等：若已存在该 skuId 的库存记录，则跳过（不重复初始化）。
     *
     * @param skuId    SKU ID
     * @param initStock 初始库存
     */
    void initStock(Long skuId, int initStock);

//    /**
//     * 秒杀库存预热（管理员在活动开始前调用，将库存加载到 Redis）
//     * <p>
//     * 预热后秒杀期间的库存查询和扣减都走 Redis，极大提升并发能力。
//     *
//     * @param skuId SKU ID
//     */
//    void preheatStock(Long skuId);
//
//    /**
//     * 查询秒杀剩余库存（从 Redis 直接读取，不走数据库）
//     * <p>
//     * 返回 -1 表示未预热或已过期，前端应提示"秒杀未开始或已结束"。
//     *
//     * @param skuId SKU ID
//     * @return 剩余库存，-1 表示未预热或已过期
//     */
//    Long getSeckillStock(Long skuId);
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
//    boolean seckill(Long skuId, int quantity, Long userId);
}

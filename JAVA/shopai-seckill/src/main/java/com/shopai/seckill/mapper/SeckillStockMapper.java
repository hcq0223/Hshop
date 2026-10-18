package com.shopai.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.seckill.entity.SeckillStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 秒杀库存 Mapper
 * <p>
 * 除 MyBatis-Plus 的常规 CRUD 外，重点是几条<b>带条件的原子更新 SQL</b>：
 * 它们在 {@code WHERE} 里带上「余量足够」的判断，由数据库保证不会扣成负数。
 * 这是防超卖的第二道防线（第一道是 Redis Lua）。
 */
@Mapper
public interface SeckillStockMapper extends BaseMapper<SeckillStock> {

    /**
     * 原子扣减可抢库存（防超卖，DB 兜底）。
     * <p>
     * {@code WHERE available_stock >= #{qty}} 是关键：并发下两个事务同时执行，
     * InnoDB 的行锁会让它们串行，后一个执行时 {@code available_stock} 已被前一个
     * 减少，若不足则 {@code rows = 0}，从而扣不成负数。
     * <p>
     * 不用 MyBatis-Plus 的乐观锁 {@code @Version} 做这条扣减，是因为秒杀场景
     * 冲突极多，版本号重试会大量失败；直接用「条件更新」一次到位更合适。
     * {@code @Version} 保留给「活动编辑」这类低频冲突场景。
     *
     * @param stockId seckill_stock.id
     * @param qty     扣减数量
     * @return 影响行数，0 表示库存不足
     */
    int deductAvailable(@Param("stockId") Long stockId, @Param("qty") Integer qty);

    /**
     * 回补可抢库存（订单创建彻底失败 / 活动取消时调用）。
     * <p>
     * 上限保护：{@code available_stock + qty <= total_stock}，
     * 防止因重复回补把库存「补多了」（比如消息重投与超时任务同时回补一次）。
     *
     * @param stockId seckill_stock.id
     * @param qty     回补数量
     * @return 影响行数
     */
    int restoreAvailable(@Param("stockId") Long stockId, @Param("qty") Integer qty);

    /**
     * 扣减成功 → 成单成功：把 locked_stock 转成 sold_stock。
     *
     * @param stockId seckill_stock.id
     * @param qty     数量
     * @return 影响行数
     */
    int confirmSold(@Param("stockId") Long stockId, @Param("qty") Integer qty);

    /**
     * 扣减成功 → 成单失败：从 locked_stock 里减去并回补 available_stock。
     * <p>
     * 与 {@link #restoreAvailable} 的区别：本方法同时动 locked 和 available，
     * 用于「已经计入锁定但最终没成单」的正规回滚路径。
     *
     * @param stockId seckill_stock.id
     * @param qty     数量
     * @return 影响行数
     */
    int rollbackLocked(@Param("stockId") Long stockId, @Param("qty") Integer qty);

    /**
     * 标记已预热，并记录预热时间。
     *
     * @param stockId seckill_stock.id
     * @param preheated 0/1
     * @return 影响行数
     */
    int markPreheated(@Param("stockId") Long stockId, @Param("preheated") Integer preheated);

    /**
     * 按活动查询库存额度列表（活动详情页 / 预热用）。
     *
     * @param activityId 活动ID
     * @return 按 sort 升序
     */
    List<SeckillStock> selectByActivityId(@Param("activityId") Long activityId);

    /**
     * 查询某活动下还没预热的额度记录（预热任务用）。
     *
     * @param activityId 活动ID
     */
    List<SeckillStock> selectNotPreheated(@Param("activityId") Long activityId);

    /**
     * 批量按 SKU 查询正在参与秒杀的额度（商品详情页显示「秒杀中」标签用）。
     *
     * @param skuIds SKU ID 列表
     */
    List<SeckillStock> selectBySkuIds(@Param("skuIds") List<Long> skuIds);
}

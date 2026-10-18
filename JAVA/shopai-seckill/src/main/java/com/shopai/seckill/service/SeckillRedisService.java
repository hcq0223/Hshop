package com.shopai.seckill.service;

import com.shopai.seckill.config.SeckillProperties;
import com.shopai.seckill.constant.SeckillRedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀 Redis 操作服务
 * <p>
 * 秒杀的性能关键全在这里。职责边界很明确：
 * <ul>
 *   <li><b>预热</b>：把 {@code seckill_stock.available_stock} 写进 Redis；</li>
 *   <li><b>扣减</b>：走 Lua 脚本原子扣减 + 一人一单判定；</li>
 *   <li><b>回补</b>：订单彻底失败时把库存还回去，并把用户从「已抢集合」摘掉；</li>
 *   <li><b>结果缓存</b>：供前端轮询。</li>
 * </ul>
 * <p>
 * <b>为什么单独用 StringRedisTemplate 而不是公共库的 RedisService</b>：
 * {@code RedisService} 里的 {@code deductStock(skuId, qty)} 是按 <b>skuId</b> 分片的
 * 单库存模型（key = {@code sku:stock:{skuId}}），而秒杀是「一个 SKU 可以同时参加
 * 多个活动、每个活动一份独立额度」，必须按 <b>stockId</b> 分片。
 * 两者 key 维度不同、语义不同，硬套会导致同一 SKU 的多个活动互相扣对方的库存。
 * 因此这里用独立的模板与脚本，公共库那套继续留给 shopai-inventory 常规库存用。
 * <p>
 * 所有库存值的序列化用 String（{@code StringRedisTemplate}），
 * 因为 Lua 脚本里用 {@code tonumber(redis.call('GET', key))} 直接算 ——
 * 如果值被 JSON 包装成带引号的字符串，lua 会解析失败返回 -2，
 * 表现为「库存永远读不到」，非常难查。
 */
@Service
public class SeckillRedisService {

    private static final Logger log = LoggerFactory.getLogger(SeckillRedisService.class);

    /** 库存 Key 的 TTL：活动最长按 24 小时算，结束后自动回收，避免僵尸 Key 常驻 */
    private static final long STOCK_TTL_SECONDS = 24 * 3600L;

    /**
     * 「已购集合」TTL 下限（秒）。
     * <p>
     * 即使活动马上就要结束也至少保留一天 —— 结算、售后、排查「用户说抢到了却没订单」
     * 都会回看这个集合。
     */
    private static final long BOUGHT_SET_TTL_FLOOR_SECONDS = 24 * 3600L;

    /** 活动结束后「已购集合」再保留多久（秒），覆盖异步下单与补偿的时间窗 */
    private static final long BOUGHT_SET_TTL_GRACE_SECONDS = 3600L;

    /**
     * 计算「已购集合」应该用的 TTL 秒数。
     * <p>
     * 为什么不能直接用固定值：{@code seckill:bought:{activityId}} 承载的是
     * 「每人限购」的计数，<b>必须活到活动结束</b>。活动如果持续超过固定 TTL，
     * 键提前过期就会让限购失效（用户能再抢一次）。所以 TTL 按
     * 「距活动结束的时间 + 宽限」算，并设一个下限。
     * <p>
     * 每次抢购都用同一个活动结束时间重算，因此反复抢购只会把 TTL 重设成
     * 同一个目标时刻，不会无限延长。
     *
     * @param activityEndTime 活动结束时间（为空时按下限处理）
     * @return TTL 秒数，恒 &gt; 0
     */
    public static long boughtSetTtlSeconds(java.time.LocalDateTime activityEndTime) {
        long secondsToEnd = 0L;
        if (activityEndTime != null) {
            secondsToEnd = java.time.Duration.between(
                    java.time.LocalDateTime.now(), activityEndTime).getSeconds();
        }
        long ttl = Math.max(0L, secondsToEnd) + BOUGHT_SET_TTL_GRACE_SECONDS;
        return Math.max(ttl, BOUGHT_SET_TTL_FLOOR_SECONDS);
    }

    // ==================== Lua 返回值语义（与 SeckillRedisKeys 脚本注释一致） ====================
    /** 库存不足 */
    public static final long RESULT_NO_STOCK = -1L;
    /** 库存 Key 不存在（未预热或已过期） */
    public static final long RESULT_KEY_MISSING = -2L;
    /** 数量非法 */
    public static final long RESULT_BAD_QTY = -3L;
    /** 该用户已抢过（一人一单） */
    public static final long RESULT_ALREADY_BOUGHT = -4L;
    /** 超过每人限购件数 */
    public static final long RESULT_EXCEED_LIMIT = -5L;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillProperties properties;

    /** 扣减 + 一人一单（原子）。脚本在类加载时编译一次并缓存，不要每次调用都 new */
    private static final DefaultRedisScript<Long> DEDUCT_WITH_LIMIT_SCRIPT =
            new DefaultRedisScript<>(SeckillRedisKeys.DEDUCT_WITH_LIMIT_SCRIPT, Long.class);

    /** 回补库存 + 摘除「已抢」标记（原子） */
    private static final DefaultRedisScript<Long> RESTORE_WITH_USER_SCRIPT =
            new DefaultRedisScript<>(SeckillRedisKeys.RESTORE_WITH_USER_SCRIPT, Long.class);

    // ==================== 预热 ====================

    /**
     * 预热单个 SKU 的秒杀库存到 Redis。
     * <p>
     * 幂等：重复预热会直接覆盖为新值。这既是优点也是风险 ——
     * <b>活动进行中绝不能重新预热</b>，否则会把已经扣掉的库存「还原」回去，
     * 造成超卖。所以调用方（预热任务与手动预热接口）都必须在
     * 「活动还没开始」时调用，本方法内部也会拒绝 running 状态的重入
     * （由 {@code SeckillActivityService.preheatActivity} 负责校验）。
     *
     * @param activityId     活动ID（用作 Redis key 的 hash tag，保证与「已抢集合」同 slot）
     * @param stockId        seckill_stock.id
     * @param availableStock 可抢库存
     */
    public void preheatStock(Long activityId, Long stockId, int availableStock) {
        stringRedisTemplate.opsForValue().set(
                SeckillRedisKeys.stock(activityId, stockId),
                String.valueOf(availableStock),
                STOCK_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("秒杀库存预热成功, activityId={}, stockId={}, availableStock={}",
                activityId, stockId, availableStock);
    }

    /**
     * 读取 Redis 中的剩余库存。
     *
     * @param activityId 活动ID
     * @param stockId    seckill_stock.id
     * @return 剩余库存；Key 不存在返回 {@code null}（表示未预热或已过期）
     */
    public Integer getStock(Long activityId, Long stockId) {
        String v = stringRedisTemplate.opsForValue().get(SeckillRedisKeys.stock(activityId, stockId));
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            log.error("秒杀库存值非法（应为整数）, activityId={}, stockId={}, value={}",
                    activityId, stockId, v);
            return null;
        }
    }

    /**
     * 判断某 SKU 的库存是否已预热。
     */
    public boolean isPreheated(Long activityId, Long stockId) {
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(SeckillRedisKeys.stock(activityId, stockId)));
    }

    /**
     * 清除某个秒杀库存 Key（活动下线/删除时调用，避免残留 Key 被误读）。
     */
    public void evictStock(Long activityId, Long stockId) {
        stringRedisTemplate.delete(SeckillRedisKeys.stock(activityId, stockId));
    }

    // ==================== 扣减 / 回补 ====================

    /**
     * 原子扣减秒杀库存（含限购判定）。
     * <p>
     * 这是整个秒杀链路的第一道也是最关键的闸门：把并发判定与扣减放进
     * 一个 Lua 脚本，在 Redis 单线程内一次执行完，从根本上排除
     * 「先查后扣」之间的竞态导致的超卖。
     * <p>
     * <b>限购粒度是「活动内的单个 SKU」</b>：同一活动买了 A 商品后仍可买 B，
     * 不同 SKU 各自独立计数。
     *
     * @param activityId   活动ID（用于 hash tag 与已购记录分片）
     * @param stockId      seckill_stock.id
     * @param skuId        SKU ID（作为已购记录的 Hash field 维度）
     * @param userId       用户ID
     * @param quantity     购买数量
     * @param perUserLimit 每人限购件数（<=0 表示不限）
     * @param boughtTtlSeconds 「已购集合」的 TTL 秒数（<=0 表示不设过期）。
     *                         一般传 {@link #boughtSetTtlSeconds(java.time.LocalDateTime)} 的结果。
     * @return 扣减后的剩余库存；负数表示失败，语义见本类的 {@code RESULT_*} 常量
     */
    public long deduct(Long activityId, Long stockId, Long skuId, Long userId,
                       int quantity, int perUserLimit, long boughtTtlSeconds) {
        // 两个 key 共用 hash tag {activityId}，保证落在同一 slot —— 否则 Redis Cluster
        // 会直接拒绝执行（CROSSSLOT Keys in request don't hash to the same slot）。
        List<String> keys = Arrays.asList(
                SeckillRedisKeys.stock(activityId, stockId),
                SeckillRedisKeys.boughtUserSet(activityId));
        Long result = stringRedisTemplate.execute(
                DEDUCT_WITH_LIMIT_SCRIPT,
                keys,
                String.valueOf(quantity),
                String.valueOf(userId),
                String.valueOf(perUserLimit),
                String.valueOf(skuId),
                String.valueOf(boughtTtlSeconds));
        if (result == null) {
            // 脚本执行异常（连接断开等），当作「Key 不存在」处理：
            // 调用方会走数据库降级扣减，而不是直接判失败，避免误伤用户
            log.error("秒杀扣减脚本返回 null, stockId={}, skuId={}, userId={}",
                    stockId, skuId, userId);
            return RESULT_KEY_MISSING;
        }
        return result;
    }

    /**
     * 回补秒杀库存，并把这个用户在该 SKU 上的「已购件数」减回去。
     * <p>
     * <b>必须同时回退已购计数</b>：只把库存加回去而不减计数的话，
     * 用户会因为「已购记录里还有他」而永远抢不了这个 SKU —— 明明库存已经还回去了。
     * 页面表现为「该用户一直提示已抢过」，但活动还剩库存。
     *
     * @param activityId 活动ID
     * @param stockId    seckill_stock.id
     * @param skuId      SKU ID
     * @param userId     用户ID
     * @param quantity   回补数量
     */
    public void restore(Long activityId, Long stockId, Long skuId, Long userId, int quantity) {
        try {
            List<String> keys = Arrays.asList(
                    SeckillRedisKeys.stock(activityId, stockId),
                    SeckillRedisKeys.boughtUserSet(activityId));
            stringRedisTemplate.execute(
                    RESTORE_WITH_USER_SCRIPT,
                    keys,
                    String.valueOf(quantity),
                    String.valueOf(userId),
                    String.valueOf(skuId));
            log.warn("秒杀库存已回补, activityId={}, stockId={}, skuId={}, userId={}, qty={}",
                    activityId, stockId, skuId, userId, quantity);
        } catch (Exception e) {
            // 回补失败要吵：这是数据不一致，需要人工介入或由对账任务修正
            log.error("秒杀库存回补失败（需人工核对）, activityId={}, stockId={}, skuId={}, userId={}, qty={}",
                    activityId, stockId, skuId, userId, quantity, e);
        }
    }

    /**
     * 判断用户在该活动的某个 SKU 上是否已经买过（仅查询，不扣减）。
     * <p>
     * 现在记录结构是 Hash（field = {@code userId:skuId}，value = 已购件数），
     * 所以查询必须带上 skuId —— 同一活动里买过 A 不影响 B。
     * <p>
     * 只用于详情页展示「已抢过」与抢购前快速拦截，<b>不能</b>作为限购的唯一依据 ——
     * 判断与扣减分两步执行就会有竞态，真正的闸门是 Lua 脚本里的原子判定
     * （以及 DB 侧 {@code seckill_record} 的唯一索引兜底）。
     *
     * @param activityId 活动ID
     * @param skuId      SKU ID
     * @param userId     用户ID
     */
    public boolean hasBought(Long activityId, Long skuId, Long userId) {
        Object v = stringRedisTemplate.opsForHash()
                .get(SeckillRedisKeys.boughtUserSet(activityId), userId + ":" + skuId);
        if (v == null) {
            return false;
        }
        try {
            return Long.parseLong(v.toString()) > 0;
        } catch (NumberFormatException e) {
            // 脏数据/格式异常：按「未购买」处理，让 Lua 脚本去做权威判定，
            // 而不是因为一个异常值把用户永久挡在门外
            log.warn("已购记录值非法，按未购买处理: activityId={}, skuId={}, userId={}, value={}",
                    activityId, skuId, userId, v);
            return false;
        }
    }

    // ==================== 抢购结果（前端轮询） ====================

    /**
     * 写入抢购结果，供前端轮询。
     *
     * @param activityId 活动ID
     * @param userId     用户ID
     * @param resultJson 结果 JSON（由调用方组装）
     */
    public void saveResult(Long activityId, Long userId, String resultJson) {
        stringRedisTemplate.opsForValue().set(
                SeckillRedisKeys.result(activityId, userId),
                resultJson,
                properties.getResultTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 读取抢购结果。
     *
     * @return 结果 JSON；不存在返回 null（表示还没抢、或结果已过期）
     */
    public String getResult(Long activityId, Long userId) {
        return stringRedisTemplate.opsForValue().get(SeckillRedisKeys.result(activityId, userId));
    }
}

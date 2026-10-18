package com.shopai.seckill.constant;

/**
 * 秒杀 Redis Key 与 Lua 脚本常量
 */
public final class SeckillRedisKeys {

    private SeckillRedisKeys() {
    }

    // ==================== Key 前缀 ====================

    /** 秒杀库存：{@code seckill:stock:{stockId}} → String（剩余可抢数量） */
    public static final String STOCK = "seckill:stock:";

    /** 已抢购用户集合：{@code seckill:bought:{activityId}} → Set（成员为 userId） */
    public static final String BOUGHT_USER_SET = "seckill:bought:";

    /** 抢购结果（前端轮询）：{@code seckill:result:{activityId}:{userId}} → String（JSON） */
    public static final String RESULT = "seckill:result:";

    /** 活动预热标记：{@code seckill:preheated:{activityId}} → String */
    public static final String PREHEATED = "seckill:preheated:";

    // ==================== 拼装方法 ====================
    //
    // 【Redis Cluster 关键约束：hash tag】
    //   Cluster 把 key 按 hash 分到 16384 个 slot，而 **Lua 脚本里涉及的所有 key
    //   必须落在同一个 slot**，否则直接抛：
    //       CROSSSLOT Keys in request don't hash to the same slot
    //
    //   我们的扣减脚本同时操作两个 key：
    //       KEYS[1] 库存        （原设计按 stockId 分片）
    //       KEYS[2] 已抢用户集合（按 activityId 分片）
    //   两个 key 主体不同 → hash 到不同 slot → 整个抢购接口 500。
    //   （注意：单 key 的 SET/GET 不受影响，所以「预热成功、一抢就报错」就是这个现象。）
    //
    //   解法：用 Redis 的 hash tag 语法 `{...}`。花括号内的内容才参与 slot 计算，
    //   只要两个 key 的 `{}` 内容相同，就必然同 slot。
    //   这里两个 key 的共同维度是 **activityId**（「一人一单」本来就是活动维度的限购），
    //   因此统一写成：
    //       seckill:stock:{activityId}:{stockId}
    //       seckill:bought:{activityId}
    //   两者 {activityId} 相同 → 同 slot → Lua 可执行。
    //
    //   附带好处：同一活动的全部库存与已抢集合都落在同一个 slot，
    //   后续若要扩成「一个活动多 SKU 在一个脚本里批量扣减」也不必再改 key 结构。

    /**
     * 库存 Key。
     * <p>
     * 形如 {@code seckill:stock:{activityId}:{stockId}}。
     * <b>必须带 activityId</b> —— 它同时是 hash tag，保证与
     * {@link #boughtUserSet(Long)} 落在同一 slot（见上方说明）。
     *
     * @param activityId 活动ID（作为 hash tag，参与 slot 计算）
     * @param stockId    seckill_stock.id
     */
    public static String stock(Long activityId, Long stockId) {
        return STOCK + "{" + activityId + "}:" + stockId;
    }

    /**
     * 已抢购用户集合 Key。
     * <p>
     * 形如 {@code seckill:bought:{activityId}}，与 {@link #stock(Long, Long)}
     * 共用 hash tag {@code {activityId}}，从而与库存 Key 同 slot。
     *
     * @param activityId 活动ID（作为 hash tag）
     */
    public static String boughtUserSet(Long activityId) {
        return BOUGHT_USER_SET + "{" + activityId + "}";
    }

    /**
     * 抢购结果 Key（前端轮询用）。
     * <p>
     * 形如 {@code seckill:result:{activityId}:{userId}}。
     * 单个 key 的读写不参与 Lua 脚本，本来不需要 hash tag；
     * 这里加上是为了与同活动的其它 key 保持同一 slot —— 将来若把
     * 「写结果」也并入扣减脚本，不需要再改 key。
     *
     * @param activityId 活动ID
     * @param userId     用户ID
     */
    public static String result(Long activityId, Long userId) {
        return RESULT + "{" + activityId + "}:" + userId;
    }

    /**
     * 活动预热标记 Key。
     *
     * @param activityId 活动ID
     */
    public static String preheated(Long activityId) {
        return PREHEATED + activityId;
    }

    // ==================== Lua 脚本 ====================

    /**
     * 原子扣减 + 限购判定（一段脚本内完成，避免两次往返的竞态）。
     * <p>
     * <b>限购粒度：活动内的单个商品(SKU)，不是整个活动。</b>
     * 同一个活动里有多个 SKU 时，用户买了 A 之后仍然可以买 B ——
     * 这是电商秒杀的常规语义（活动是「场」，商品才是「货」）。
     * 若需改为「整个活动一人只能买一件」，把 boughtKey 换成按 activityId 分片、
     * 且 field 固定为常量即可，但那样多商品活动基本失去意义。
     * <p>
     * <b>为什么用 Hash 而不是 Set</b>：Set 只能表达「买过/没买过」，
     * 无法支撑「每人限购 N 件」的累计判断。Hash 的 field=userId、value=已购件数，
     * 一次 {@code HINCRBY} 就能同时完成「累计 + 判定」，且仍是单个原子操作。
     * <p>
     * 返回值约定（所有失败分支都<b>不修改任何数据</b>，调用方可安全认为「负数 = 没扣到」）：
     * <ul>
     *   <li>{@code >= 0}：成功，返回扣减后剩余库存；</li>
     *   <li>{@code -1}：库存不足；</li>
     *   <li>{@code -2}：库存 Key 不存在（未预热/已过期）；</li>
     *   <li>{@code -3}：数量非法；</li>
     *   <li>{@code -4}：该用户在此 SKU 上已买过（限购 1 件时的等价语义）；</li>
     *   <li>{@code -5}：超过该 SKU 的每人限购件数。</li>
     * </ul>
     * <p>
     * KEYS[1]=库存Key  KEYS[2]=已购记录Hash（{activityId} 作 hash tag，与库存同 slot）
     * ARGV[1]=购买数量  ARGV[2]=userId  ARGV[3]=每人限购件数（0 或负数表示不限）
     * ARGV[4]=skuId（作为 Hash field，实现「按活动内商品」限购）
     * ARGV[5]=已购集合的 TTL 秒数（&lt;=0 表示不设过期）
     */
    public static final String DEDUCT_WITH_LIMIT_SCRIPT = """
            local stockKey = KEYS[1]
            local boughtKey = KEYS[2]
            local qty = tonumber(ARGV[1])
            local userId = ARGV[2]
            local perUserLimit = tonumber(ARGV[3])
            local skuId = ARGV[4]
            local boughtTtl = tonumber(ARGV[5])

            if qty == nil or qty <= 0 then
                return -3
            end

            -- 已购件数按「活动 + 用户 + SKU」累计。
            -- 不同 SKU 用不同的 field，因此买过 A 不影响买 B。
            local field = userId .. ':' .. skuId
            local bought = tonumber(redis.call('HGET', boughtKey, field)) or 0

            if redis.call('EXISTS', stockKey) == 0 then
                return -2
            end
            local stock = tonumber(redis.call('GET', stockKey))
            if stock == nil then
                return -2
            end
            if stock < qty then
                return -1
            end

            -- 限购判定：已购 + 本次 > 上限则拒绝
            if perUserLimit ~= nil and perUserLimit > 0 then
                if bought + qty > perUserLimit then
                    -- 已买过（哪怕只买了一部分）时用 -4，让前端能区分
                    -- 「已参与过」与「本次超量」
                    if bought > 0 then
                        return -4
                    end
                    return -5
                end
            end

            local remain = redis.call('DECRBY', stockKey, qty)
            -- 扣减成功后才记入已购数量，保证失败不影响后续重试
            redis.call('HINCRBY', boughtKey, field, qty)

            -- 给「已购集合」补过期时间。
            -- 这个 Hash 是懒创建的：预热时它还不存在，那时调 EXPIRE 只会打空，
            -- 所以只能在这里设。不设的话它会【永不过期】（TTL = -1），
            -- 每个活动都留一个再也用不到的 Hash 常驻 Redis。
            -- TTL 由调用方按「活动结束时间 + 宽限」算好传进来，每次抢购都重设成
            -- 同一个目标时刻，不会因为反复抢购而无限延长。
            if boughtTtl ~= nil and boughtTtl > 0 then
                redis.call('EXPIRE', boughtKey, boughtTtl)
            end
            return remain
            """;

    /**
     * 回补库存并清除「已购」记录（订单创建彻底失败时调用）。
     * <p>
     * <b>回补必须同时把用户的已购件数减回去</b>，否则用户会因为
     * 「已购记录里还有他」而永远无法再次抢购 —— 明明库存已经还回去了。
     * <p>
     * 这里用 {@code HINCRBY -qty} 而不是 {@code HDEL}：一次买了 2 件、
     * 只回补其中 1 件时（理论上不会发生，但保持语义严谨），
     * 减回去比整条删掉更准确。减到 0 时顺手删掉 field，避免 Hash 无限膨胀。
     * <p>
     * KEYS[1]=库存Key  KEYS[2]=已购记录Hash
     * ARGV[1]=回补数量  ARGV[2]=userId  ARGV[3]=skuId
     */
    public static final String RESTORE_WITH_USER_SCRIPT = """
            local stockKey = KEYS[1]
            local boughtKey = KEYS[2]
            local qty = tonumber(ARGV[1])
            local userId = ARGV[2]
            local skuId = ARGV[3]

            if redis.call('EXISTS', stockKey) == 1 then
                redis.call('INCRBY', stockKey, qty)
            end

            local field = userId .. ':' .. skuId
            local left = redis.call('HINCRBY', boughtKey, field, -qty)
            if tonumber(left) <= 0 then
                redis.call('HDEL', boughtKey, field)
            end
            return 1
            """;
}

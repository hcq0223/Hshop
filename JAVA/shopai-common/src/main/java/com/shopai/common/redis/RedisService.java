package com.shopai.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis服务类 - 分布式锁 + 通用缓存 + Token黑名单/登录限流 + 电商专属能力
 * <p>
 * 设计原则：
 * 读操作：先Redis，后MySQL（缓存穿透保护：空值标记）
 * 写操作：MySQL事务包裹，成功即删Redis缓存
 * 删操作：逻辑删除（改status），永久保留数据痕迹
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.RedisService 迁移至公共库。
 * 各服务共享缓存 key 前缀常量，避免跨服务 key 冲突。
 */
@Service
public class RedisService {
    private static final Logger log = LoggerFactory.getLogger(RedisService.class);

    @Autowired
    private StringRedisTemplate redis;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ==================== 缓存Key常量 ====================
    private static final String TOKEN_BL = "token:bl:";
    private static final String LOGIN_FAIL = "login:fail:";

    /**
     * 空值标记（缓存穿透保护）
     * <p>
     * 查库结果为空时写入该标记，避免同一无效 key 反复打到数据库；
     * 读取时识别到该标记直接返回 null。
     */
    private static final String NULL_MARKER = "__NULL__";


    // 商品 & SKU & 购物车（新增）
    public static final String CACHE_PRODUCT = "cache:product:";       // cache:product:{id}
    public static final String CACHE_PRODUCT_LIST = "cache:product:list:";  // cache:product:list:{hash}
    public static final String CART_KEY = "cart:";                // cart:{customerId} (Hash)
    public static final String SKU_STOCK = "sku:stock:";           // sku:stock:{skuId}

    // 订单缓存
    public static final String CACHE_ORDER = "cache:order:";    // 单个订单: cache:order:{id}
    public static final String CACHE_ORDER_LIST = "cache:order:list:";  // 订单列表: cache:order:list:{hash}
    public static final String CACHE_ORDER_STATS = "cache:order:stats";  // 订单统计
    public static final String CACHE_ORDER_TREND = "cache:order:trend";  // 7日趋势

    // 用户缓存
    public static final String CACHE_USER = "cache:user:";  // 单个用户: cache:user:{id}
    public static final String CACHE_USER_LIST = "cache:user:list:";  // 用户列表: cache:user:list:{hash}
    public static final String CACHE_USER_STATS = "cache:user:stats";  // 用户统计

    public static final String CACHE_CUSTOMER = "cache:customer:";  // 单个用户: cache:customer:{userid}
    public static final String CACHE_CUSTOMER_DETAIL = "cache:customer:detail:";  // 单个用户详情: cache:customer:detail:{userid}
    public static final String CACHE_CUSTOMER_LIST = "cache:customer:list:";  // 客户列表: cache:customer:list:{hash}

    public static final String CACHE_CUSTOMER_ID = "cache:customer:userid:";  // 客户ID（带冒号，与 CACHE_CUSTOMER 风格一致）


    public static final String CACHE_MERCHANT_PAGE = "cache:merchant:page:";  // 商户分页列表（带冒号，与 CACHE_MERCHANT 风格一致）
    public static final String CACHE_MERCHANT = "cache:merchant:";  // 单个商户
    public static final String CACHE_MERCHANT_DETAIL = "cache:merchant:detail:";  // 单个商户详情（带冒号）

    public static final String CACHE_MERCHANT_PUBLIC = "cache:merchant:public:";  // 单个商户公开信息（带冒号）


    // 地址缓存
    public static final String CACHE_ADDRESS = "cache:address:"; // 单个地址: cache:address:{id}
    public static final String CACHE_ADDRESS_LIST = "cache:address:list:"; // 地址列表: cache:address:list:{customerId}

    // 分类缓存
    public static final String CACHE_CATEGORY = "cache:category:"; // 单个分类
    public static final String CACHE_CATEGORY_ALL = "cache:category:all"; // 全部分类列表
    public static final String CACHE_CATEGORY_TOP = "cache:category:top"; // 顶级分类列表
    public static final String CACHE_CATEGORY_SUB = "cache:category:sub:"; // 子分类列表：sub:{parentId}

    // 商品SKU表缓存,颜色，尺码，款式，库存等缓存
    public static final String CACHE_SKU = "cache:sku:"; // 单个SKU
    public static final String CACHE_SKU_LIST = "cache:sku:list:"; // 商品下的SKU列表

    // 购物车列表缓存：cache:cart:list:{customerId}
    public static final String CACHE_CART_LIST = "cache:cart:list:";

    // 评价缓存
    public static final String CACHE_REVIEW_PRODUCT = "cache:review:product:";   // 商品下的评价列表
    public static final String CACHE_REVIEW_CUSTOMER = "cache:review:customer:"; // 客户下的评价列表

    // 库存缓存（库存服务独立后新增的专属前缀，避免与 sku:stock 混淆）
    public static final String CACHE_INVENTORY = "cache:inventory:"; // 单个库存: cache:inventory:{skuId}

    // 聊天缓存
    public static final String CACHE_CHAT_MSGS = "cache:chat:msgs:";  // 订单聊天: cache:chat:msgs:{orderId}

    // 问题缓存
    public static final String CACHE_ISSUE_MY = "cache:issue:my:";  // 我的问题: cache:issue:my:{userId}
    public static final String CACHE_ISSUE_ALL = "cache:issue:all";  // 全部问题

    // 交易缓存
    public static final String CACHE_TXN_MY = "cache:txn:my:";  // 我的交易: cache:txn:my:{userId}:{page}
    public static final String CACHE_TXN_LIST = "cache:txn:list:";  // 交易列表: cache:txn:list:{hash}

    // 仪表盘缓存
    public static final String CACHE_DASHBOARD = "cache:dashboard";  // 管理端仪表盘

    // ==================== 缓存 TTL 策略（秒） ====================
    //
    // 设计原则：**Redis 里的每一个 key 都必须有过期时间**，否则会长期占用内存导致 OOM，
    // 且在数据删除后残留「僵尸缓存」。TTL 按「数据变更频率 × 容忍的陈旧时长」来定：
    //
    //   变更越频繁 / 容忍越短  →  TTL 越短
    //   几乎不变的基础数据      →  TTL 可以很长
    //
    // 注意：TTL 是「最终一致性的上限」，不是让用户等这么久才看到新数据 ——
    // 每个写操作都会主动删对应 key（点失效），TTL 只是兜底（防止删漏/删失败导致永久脏数据）。

    /** 极短（60s）：变化很快，且短时间陈旧可接受 —— 列表/统计这类聚合结果 */
    public static final long TTL_VERY_SHORT = 60L;

    /** 较短（120s）：用户自己的动态数据，要求较快反映最新状态 */
    public static final long TTL_SHORT = 120L;

    /** 中（300s）：一般业务实体的详情 */
    public static final long TTL_MEDIUM = 300L;

    /** 较长（600s）：变化不频繁，且是高频读取的热点 —— 分类、SKU */
    public static final long TTL_LONG = 600L;

    /** 长（1800s）：几乎不变的基础数据 —— 商家公开信息、热门榜单 */
    public static final long TTL_VERY_LONG = 1800L;

    /** 购物车（30 天）：用户资产类数据，要留够用户回访的时间 */
    public static final long TTL_CART = 30L * 24 * 3600;

    /** 秒杀库存（1 小时）：与秒杀活动时长对齐，活动结束即释放 */
    public static final long TTL_SECKILL_STOCK = 3600L;

    /** 库存缓存（300s）：库存变动频繁，但读取量大，折中取中值 */
    public static final long TTL_INVENTORY = 300L;

    /** 商品详情（300s） */
    public static final long TTL_PRODUCT = 300L;

    /** 订单详情（300s）：订单状态会流转，不宜过长 */
    public static final long TTL_ORDER = 300L;

    /** 用户/客户/商户详情（300s） */
    public static final long TTL_USER = 300L;

    /** 地址（600s）：地址列表随用户增删改，变化不频繁 */
    public static final long TTL_ADDRESS = 600L;

    /** 评价统计（300s）：评分分布是聚合结果 */
    public static final long TTL_REVIEW_STATS = 300L;

    /**
     * 缓存 Key 前缀 → 建议 TTL（秒）的集中登记表
     * <p>
     * 为什么要有一张表：需要「添加 TTL」的调用点分散在各服务的 Service 里，
     * 若在每处各写一个数字，既容易漏、也容易互相不一致。这里把策略集中登记，
     * 各服务可以直接查表取值，也可以通过 {@link #ttlOf(String)} 由 key 反查。
     * <p>
     * 匹配规则：按**最长前缀优先**匹配（例如 {@code cache:review:product:1:stats}
     * 同时匹配 {@code cache:review:product:} 与 {@code cache:review:}，
     * 取更长的那个，从而让 :stats 用 300s 而不是评价列表的默认值）。
     */
    private static final Map<String, Long> TTL_BY_PREFIX = new LinkedHashMap<>();

    static {
        // 商品 / SKU
        TTL_BY_PREFIX.put(CACHE_PRODUCT, TTL_PRODUCT);
        TTL_BY_PREFIX.put(CACHE_SKU, TTL_LONG);
        TTL_BY_PREFIX.put(CACHE_CATEGORY, TTL_LONG);
        // 订单 / 购物车 / 地址
        TTL_BY_PREFIX.put(CACHE_ORDER, TTL_ORDER);
        TTL_BY_PREFIX.put(CACHE_CART_LIST, TTL_SHORT);
        TTL_BY_PREFIX.put(CART_KEY, TTL_CART);
        TTL_BY_PREFIX.put(CACHE_ADDRESS, TTL_ADDRESS);
        // 用户 / 客户 / 商户
        TTL_BY_PREFIX.put(CACHE_USER, TTL_USER);
        TTL_BY_PREFIX.put(CACHE_CUSTOMER, TTL_USER);
        TTL_BY_PREFIX.put(CACHE_MERCHANT, TTL_VERY_LONG);
        // 评价（:stats 单独登记，靠最长前缀优先命中）
        TTL_BY_PREFIX.put(CACHE_REVIEW_PRODUCT, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_REVIEW_PRODUCT + ":stats", TTL_REVIEW_STATS);
        TTL_BY_PREFIX.put(CACHE_REVIEW_CUSTOMER, TTL_SHORT);
        // 库存 / 秒杀
        TTL_BY_PREFIX.put(CACHE_INVENTORY, TTL_INVENTORY);
        TTL_BY_PREFIX.put(SKU_STOCK, TTL_SECKILL_STOCK);
        // 其他
        TTL_BY_PREFIX.put(CACHE_CHAT_MSGS, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_ISSUE_MY, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_ISSUE_ALL, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_TXN_MY, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_TXN_LIST, TTL_SHORT);
        // 已迁移到 ES 但常量仍在的列表 key：给个兜底 TTL，避免残留 key 永不过期
        TTL_BY_PREFIX.put(CACHE_PRODUCT_LIST, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_ORDER_LIST, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_USER_LIST, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_CUSTOMER_LIST, TTL_SHORT);
        TTL_BY_PREFIX.put(CACHE_MERCHANT_PAGE, TTL_SHORT);
        // 统计 / 仪表盘
        TTL_BY_PREFIX.put(CACHE_ORDER_STATS, TTL_VERY_SHORT);
        TTL_BY_PREFIX.put(CACHE_ORDER_TREND, TTL_VERY_SHORT);
        TTL_BY_PREFIX.put(CACHE_USER_STATS, TTL_VERY_SHORT);
        TTL_BY_PREFIX.put(CACHE_DASHBOARD, TTL_VERY_SHORT);
    }

    /** 兜底 TTL：查不到前缀时使用，避免出现「没有过期时间」的 key */
    public static final long TTL_DEFAULT = TTL_MEDIUM;

    /**
     * 按 key 反查建议 TTL（按最长前缀优先匹配）
     * <p>
     * 用途：改造存量代码时，调用处只需写 {@code ttlOf(key)} 而不必记住具体秒数；
     * 策略调整只改登记表一处即可全局生效。
     *
     * @param key 缓存 Key
     * @return 建议 TTL（秒）；未登记时返回 {@link #TTL_DEFAULT}
     */
    public static long ttlOf(String key) {
        if (key == null || key.isEmpty()) {
            return TTL_DEFAULT;
        }
        long best = -1;
        int bestLen = -1;
        for (Map.Entry<String, Long> e : TTL_BY_PREFIX.entrySet()) {
            String prefix = e.getKey();
            if (key.startsWith(prefix) && prefix.length() > bestLen) {
                best = e.getValue();
                bestLen = prefix.length();
            }
        }
        return best > 0 ? best : TTL_DEFAULT;
    }

    // ==================== Lua脚本 ====================
    private static final DefaultRedisScript<String> LOCK_SCRIPT;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private static final DefaultRedisScript<Long> DEDUCT_STOCK_SCRIPT;

    static {
        LOCK_SCRIPT = new DefaultRedisScript<>();
        LOCK_SCRIPT.setScriptText("return redis.call('SET',KEYS[1],ARGV[1],'NX','PX',ARGV[2])");
        LOCK_SCRIPT.setResultType(String.class);

        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);

        // 库存扣减Lua：原子校验+扣减，防止超卖
        DEDUCT_STOCK_SCRIPT = new DefaultRedisScript<>();
        DEDUCT_STOCK_SCRIPT.setScriptText(
                "local stock = tonumber(redis.call('GET', KEYS[1])) " +
                        "if stock == nil then return -2 end " +
                        "if stock >= tonumber(ARGV[1]) then " +
                        "  return redis.call('DECRBY', KEYS[1], ARGV[1]) " +
                        "else return -1 end");
        DEDUCT_STOCK_SCRIPT.setResultType(Long.class);
    }

    // ==================== 分布式锁 ====================

    /**
     * 尝试获取分布式锁（非阻塞）
     *
     * @param lockKey   锁的Key
     * @param lockValue 锁的值（用于释放时校验，建议使用UUID）
     * @param expireMs  锁的过期时间（毫秒）
     * @return true 表示获取成功，false 表示获取失败
     */
    public boolean tryLock(String lockKey, String lockValue, long expireMs) {
        try {
            String result = redis.execute(LOCK_SCRIPT,
                    Collections.singletonList(lockKey), lockValue, String.valueOf(expireMs));
            return "OK".equals(result);
        } catch (Exception e) {
            log.error("Redis tryLock error key={}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * 释放分布式锁（仅当值匹配时删除）
     *
     * @param lockKey   锁的Key
     * @param lockValue 锁的值（必须与获取时一致）
     */
    public void unlock(String lockKey, String lockValue) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.error("Redis unlock error key={}: {}", lockKey, e.getMessage());
        }
    }

    /**
     * 带超时等待的分布式锁执行模板
     *
     * @param lockKey  锁的Key
     * @param waitMs   最大等待时间（毫秒）
     * @param expireMs 锁持有时间（毫秒）
     * @param action   需要加锁执行的业务逻辑
     * @param <T>      业务返回值类型
     * @return 业务执行结果
     * @throws RuntimeException 超时或线程中断时抛出
     */
    public <T> T executeWithLock(String lockKey, long waitMs, long expireMs, Supplier<T> action) {
        String lockValue = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        while (!tryLock(lockKey, lockValue, expireMs)) {
            if (System.currentTimeMillis() - start > waitMs) {
                throw new RuntimeException("获取锁超时: " + lockKey);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted");
            }
        }
        try {
            return action.get();
        } finally {
            unlock(lockKey, lockValue);
        }
    }

    // ==================== 通用缓存操作 ====================

    /**
     * 缓存穿透保护型获取：先读缓存，若不存在则调用数据库Supplier，并缓存结果（null值存为"__NULL__"）
     *
     * @param key        缓存Key
     * @param clazz      目标类型Class
     * @param ttlSeconds 缓存有效期（秒）
     * @param dbSupplier 数据库查询Supplier
     * @param <T>        目标类型
     * @return 缓存或数据库查询结果，可能为null
     */
    public <T> T getOrSet(String key, Class<T> clazz, long ttlSeconds, Supplier<T> dbSupplier) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                if (NULL_MARKER.equals(json)) return null;
                return objectMapper.readValue(json, clazz);
            }
        } catch (Exception e) {
            log.warn("Redis getOrSet read error key={}: {}", key, e.getMessage());
        }
        T value = dbSupplier.get();
        setCache(key, value, ttlSeconds);
        return value;
    }

    /**
     * 泛型安全型缓存读取（支持List、PageResult等复杂泛型）
     *
     * @param key        缓存Key
     * @param typeRef    类型引用，例如 {@code new TypeReference<List<Product>>() {}}
     * @param ttlSeconds 缓存有效期（秒）
     * @param dbSupplier 数据库查询Supplier
     * @param <T>        目标类型（由TypeReference决定）
     * @return 缓存或数据库查询结果
     */
    public <T> T getOrSetTyped(String key, TypeReference<T> typeRef, long ttlSeconds, Supplier<T> dbSupplier) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                if (NULL_MARKER.equals(json)) return null;
                //手动反序列化：把json反序列化到对象
                return objectMapper.readValue(json, typeRef);
            }
        } catch (Exception e) {
            log.warn("Redis getOrSetTyped read error key={}: {}", key, e.getMessage());
        }
        T value = dbSupplier.get();
        setCache(key, value, ttlSeconds);
        return value;
    }

    /**
     * 将对象序列化为JSON存入Redis，null值存储为"__NULL__"
     * <p>
     * TTL 处理规则（防止出现永不过期的 key）：
     * <ul>
     *   <li>{@code ttlSeconds > 0}：使用调用方显式指定的 TTL；</li>
     *   <li>{@code ttlSeconds <= 0}：视为「未指定」，自动按 key 查
     *       {@link #ttlOf(String)} 取建议值，兜底 {@link #TTL_DEFAULT}。
     *       这样即使某个调用点漏传 TTL，也不会写出永不过期的缓存。</li>
     * </ul>
     *
     * @param key        缓存Key
     * @param value      待缓存对象（可为null）
     * @param ttlSeconds 缓存有效期（秒）；&le;0 表示按 key 自动取建议 TTL
     */
    public void setCache(String key, Object value, long ttlSeconds) {
        long ttl = resolveTtl(key, ttlSeconds);
        try {
            if (value == null) {
                // 空值标记（防穿透）用更短的 TTL，避免长时间把「本来就没数据」当成结论
                redis.opsForValue().set(key, NULL_MARKER, Math.min(ttl, TTL_VERY_SHORT), TimeUnit.SECONDS);
            } else {
                //手动序列化：把json序列化放到redis中，而不是直接存对象
                String json = objectMapper.writeValueAsString(value);
                redis.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis setCache serialize error key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 解析最终生效的 TTL：显式值优先，未指定时按 key 查表兜底
     *
     * @param key        缓存Key
     * @param ttlSeconds 调用方传入的 TTL（&le;0 视为未指定）
     * @return 最终 TTL（秒），保证 &gt; 0
     */
    private static long resolveTtl(String key, long ttlSeconds) {
        if (ttlSeconds > 0) {
            return ttlSeconds;
        }
        long suggested = ttlOf(key);
        log.debug("缓存未指定 TTL，按 key 取建议值: key={}, ttl={}s", key, suggested);
        return suggested;
    }

    /**
     * 直接从Redis获取缓存并反序列化为指定类型
     *
     * @param key   缓存Key
     * @param clazz 目标类型Class
     * @param <T>   目标类型
     * @return 缓存值，若不存在或反序列化失败则返回null
     */
    public <T> T getCache(String key, Class<T> clazz) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || NULL_MARKER.equals(json)) return null;
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("Redis getCache error key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 从Redis获取缓存并反序列化为List集合
     *
     * @param key          缓存Key
     * @param elementClass 列表元素类型Class
     * @param <T>          元素类型
     * @return 缓存列表，若不存在则返回空列表（非null），异常时返回null
     */
    public <T> List<T> getCacheList(String key, Class<T> elementClass) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || NULL_MARKER.equals(json)) return Collections.emptyList();
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementClass));
        } catch (Exception e) {
            log.warn("Redis getCacheList error key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 删除单个缓存Key
     *
     * @param key 缓存Key
     */
    public void deleteKey(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.error("Redis delete error key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 根据前缀批量删除缓存（使用SCAN游标，避免阻塞Redis）
     *
     * @param prefix 缓存Key前缀（不含通配符，内部自动拼接"*"）
     */
    public void deleteByPrefix(String prefix) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(200).build();
            Cursor<String> cursor = redis.scan(options);
            List<String> keys = new ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
                if (keys.size() >= 200) {
                    redis.delete(keys);
                    keys.clear();
                }
            }
            if (!keys.isEmpty()) redis.delete(keys);
        } catch (Exception e) {
            log.error("Redis deleteByPrefix error prefix={}: {}", prefix, e.getMessage());
        }
    }

    // ==================== 电商专属：库存扣减（防超卖） ====================

    /**
     * 原子扣减SKU库存（Lua脚本保证原子性）
     * <p>
     * 微服务拆分说明：秒杀/库存预扣逻辑已迁移至 shopai-inventory 库存服务，
     * 本方法保留在公共库供库存服务调用（Redis 层原子扣减能力复用）。
     *
     * @param skuId    SKU唯一标识
     * @param quantity 扣减数量（正数）
     * @return 扣减后剩余库存（>=0），-1表示库存不足，-2表示库存Key不存在或异常
     */
    public Long deductStock(String skuId, int quantity) {
        try {
            return redis.execute(DEDUCT_STOCK_SCRIPT,
                    Collections.singletonList(SKU_STOCK + skuId), String.valueOf(quantity));
        } catch (Exception e) {
            log.error("Redis deductStock error skuId={}: {}", skuId, e.getMessage());
            return -2L;
        }
    }

    /**
     * 回补库存（用于订单取消或扣减失败回滚）
     * <p>
     * TTL 说明：秒杀库存 key 由 {@code preheatStock} 预热时设置 1 小时过期；
     * 但 {@code INCRBY} 这类「只改值不改 TTL」的操作在 key 已过期后会**新建一个永不过期的 key**，
     * 导致库存 key 永久驻留（且值为回补量，语义已失真）。因此这里在回补后**显式续期**，
     * 保证该 key 始终有过期时间。
     *
     * @param skuId    SKU唯一标识
     * @param quantity 回补数量（正数）
     */
    public void restoreStock(String skuId, int quantity) {
        try {
            String key = SKU_STOCK + skuId;
            redis.opsForValue().increment(key, quantity);
            // 续期：无论 key 是刚被新建还是原本存在，都保证它有过期时间
            redis.expire(key, TTL_SECKILL_STOCK, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Redis restoreStock error skuId={}: {}", skuId, e.getMessage());
        }
    }

    // ==================== 电商专属：购物车操作 ====================

    /**
     * 向购物车添加商品（覆盖式更新）
     * <p>
     * TTL 说明：购物车是 Hash 结构，key 为 {@code cart:{customerId}}。
     * 原实现只 {@code HSET} 不设过期，导致**购物车 key 永久驻留**：
     * 用户加过商品后即使再也不回来，这条 Hash 也会一直占内存。
     * 这里在写入后统一续期 {@link #TTL_CART}（30 天），
     * 既保证活跃用户的购物车不会中途消失，也能回收长期无访问的数据。
     *
     * @param customerId   客户ID
     * @param skuId        SKU ID
     * @param cartItemJson 购物车商品JSON字符串（包含数量、规格等信息）
     */
    public void cartPut(Long customerId, String skuId, String cartItemJson) {
        String key = CART_KEY + customerId;
        redis.opsForHash().put(key, skuId, cartItemJson);
        // 续期：每次写入都刷新，活跃购物车不会过期，僵尸购物车 30 天后自动回收
        redis.expire(key, TTL_CART, TimeUnit.SECONDS);
    }

    /**
     * 获取购物车中指定SKU的商品信息
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     * @return 商品JSON字符串，若不存在则返回null
     */
    public String cartGet(Long customerId, String skuId) {
        Object val = redis.opsForHash().get(CART_KEY + customerId, skuId);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取用户整个购物车（所有SKU的JSON数据）
     *
     * @param customerId 客户ID
     * @return Map<SKU_ID, 商品JSON>，按插入顺序返回
     */
    public Map<String, String> cartGetAll(Long customerId) {
        Map<Object, Object> entries = redis.opsForHash().entries(CART_KEY + customerId);
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /**
     * 从购物车删除指定SKU
     *
     * @param customerId 客户ID
     * @param skuId      SKU ID
     */
    public void cartDelete(Long customerId, String skuId) {
        redis.opsForHash().delete(CART_KEY + customerId, skuId);
    }

    /**
     * 清空用户购物车
     *
     * @param customerId 客户ID
     */
    public void cartClear(Long customerId) {
        redis.delete(CART_KEY + customerId);
    }

    // ==================== Token黑名单 & 登录限流 ====================

    /**
     * 将Token加入黑名单，在有效期内拒绝该Token访问
     *
     * @param token     JWT Token字符串
     * @param expireSec 黑名单有效期（秒）
     */
    public void addTokenBlacklist(String token, long expireSec) {
        redis.opsForValue().set(TOKEN_BL + token, "1", expireSec, TimeUnit.SECONDS);
    }

    /**
     * 检查Token是否在黑名单中
     *
     * @param token JWT Token字符串
     * @return true 表示已拉黑，false 表示未拉黑
     */
    public boolean isTokenBlacklisted(String token) {
        return Boolean.TRUE.equals(redis.hasKey(TOKEN_BL + token));
    }

    /**
     * 记录登录失败次数（用于限流防暴力破解）
     *
     * @param userId 用户ID（由调用层从数据库查出后传入）
     * @return 当前累计失败次数，若Redis异常则返回0
     */
    public int recordLoginFail(Long userId) {
        String k = buildLoginFailKey(userId);
        Long c = redis.opsForValue().increment(k, 1);
        if (c != null && c == 1) redis.expire(k, 50, TimeUnit.MINUTES);
        return c != null ? c.intValue() : 0;
    }

    /**
     * 清除登录失败计数（登录成功后调用）
     *
     * @param userId 用户ID
     */
    public void clearLoginFail(Long userId) {
        redis.delete(buildLoginFailKey(userId));  // 保持一致
    }


    /**
     * 清除登录失败计数（登录成功后调用）
     *
     * @param userId 用户Id
     */
    // 记录失败（入参改为 userId）
    private String buildLoginFailKey(Long userId) {
        return LOGIN_FAIL + userId;
    }


    // ==================== 缓存清除便捷方法 ====================
    //
    // 列表查询改造说明：以下方法原先会一并清除「列表类」缓存
    // （CACHE_PRODUCT_LIST / CACHE_ORDER_LIST / CACHE_MERCHANT_PAGE / CACHE_USER_LIST）。
    // 这类缓存键由查询参数哈希生成，一次 deleteByPrefix 会清空**所有筛选组合、所有页码**
    // 的缓存，失效代价过大；现已改为由 shopai-search 的 ES 索引承载列表查询
    // （见各服务的 *ListIndexService），索引实时性由 MQ 事件保证。
    // 因此这里只保留「点缓存」（单个实体 / 统计 / 仪表盘）的清理。


    /**
     * 清除商品相关缓存（单个商品、仪表盘）
     * <p>
     * 说明：列表缓存已由 ES 索引替代，不再清理 {@code CACHE_PRODUCT_LIST}。
     *
     * @param productId 商品ID
     */
    public void clearProductCache(Long productId) {
        deleteKey(CACHE_PRODUCT + productId);
        deleteKey(CACHE_DASHBOARD);
    }

    /**
     * 清除订单相关缓存（单个订单、统计数据、趋势图、仪表盘）
     * <p>
     * 说明：列表缓存已由 ES 索引替代，不再清理 {@code CACHE_ORDER_LIST}。
     *
     * @param orderId 订单ID
     */
    public void clearOrderCache(Long orderId) {
        deleteKey(CACHE_ORDER + orderId);
        deleteKey(CACHE_ORDER_STATS);
        deleteKey(CACHE_ORDER_TREND);
        deleteKey(CACHE_DASHBOARD);
    }

    /**
     * 清除订单统计相关缓存（统计数据、趋势图、仪表盘），用于批量更新后刷新
     * <p>
     * 说明：列表缓存已由 ES 索引替代，不再清理 {@code CACHE_ORDER_LIST}。
     */
    public void clearOrderListCache() {
        deleteKey(CACHE_ORDER_STATS);
        deleteKey(CACHE_ORDER_TREND);
        deleteKey(CACHE_DASHBOARD);
    }

    /**
     * 清除用户相关缓存（单个用户、用户统计、仪表盘）
     * <p>
     * 说明：列表查询改造后用户列表已由 ES 索引承载，本方法本就不清列表缓存，
     * 保留签名不变以便调用方无需改动。
     *
     * @param userId 用户ID
     */
    public void clearUserCache(Long userId) {
        deleteKey(CACHE_USER + userId);
        deleteKey(CACHE_USER_STATS);
        deleteKey(CACHE_DASHBOARD);
    }

    public void clearCustomerCache(Long userId) {
        deleteKey(CACHE_CUSTOMER+userId);
        deleteKey(CACHE_CUSTOMER_DETAIL+userId);
    }

    public void clearShopCache(Long userId) {
        deleteKey(CACHE_MERCHANT+userId);
        deleteKey(CACHE_MERCHANT_DETAIL+userId);
    }

    /**
     * 清除指定订单的聊天缓存
     *
     * @param orderId 订单ID
     */
    public void clearChatCache(Long orderId) {
        deleteKey(CACHE_CHAT_MSGS + orderId);
    }

    /**
     * 清除问题相关缓存（我的问题列表、全部问题列表）
     *
     * @param userId 用户ID（若为null则不清理我的问题，只清理全局）
     */
    public void clearIssueCache(Long userId) {
        if (userId != null) deleteKey(CACHE_ISSUE_MY + userId);
        deleteKey(CACHE_ISSUE_ALL);
    }

    /**
     * 清除交易记录相关缓存（我的交易列表、全部交易列表）
     *
     * @param userId 用户ID（若为null则不清理我的交易，只清理全局）
     */
    public void clearTransactionCache(Long userId) {
        if (userId != null) deleteByPrefix(CACHE_TXN_MY + userId + ":");
        deleteByPrefix(CACHE_TXN_LIST);
    }

    /**
     * 根据查询参数生成缓存列表的哈希Key（用于列表缓存差异化）
     *
     * @param params 查询参数Map（只包含非空值）
     * @return 哈希字符串，用作缓存Key的一部分
     */
    public static String buildListHash(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        params.entrySet().stream()
                .filter(e -> e.getValue() != null && !"".equals(e.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append("&"));
        return Integer.toHexString(Objects.hash(sb.toString()));
    }
}

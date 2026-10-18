package com.shopai.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.Result;
import com.shopai.common.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 统一认证全局过滤器（网关层）
 * <p>
 * 认证收敛到网关，业务服务只读 {@code X-User-*} 头、不再各自验签。
 * <p>
 * <b>鉴权模型：默认拒绝（fail-closed）</b>
 * <ul>
 *   <li>命中「公开接口」白名单 —— 匿名放行；若带了有效 Token，额外注入身份头（便于下游个性化）；</li>
 *   <li>其余所有接口 —— 必须携带<b>有效</b> Token，否则 401。
 *       Token 无效/过期/已进黑名单，一律 401，<b>不再"放行但不注入身份"</b>。</li>
 *   <li>{@code /api/admin/**}、{@code /api/seckill/admin/**} 额外要求 admin 角色；</li>
 *   <li>商家自助接口额外要求 merchant 角色。</li>
 * </ul>
 * <p>
 * <b>为什么必须改成默认拒绝</b>（真实事故）：
 * 原实现对「Token 无效」的处理是 {@code chain.filter(exchange)} —— 放行但<b>不写 X-User-Id</b>，
 * 而且只有 admin / 商家自助前缀才返回 401。于是：
 * <pre>
 *   过期 Token → 网关放行 → 下游 CurrentUser.getUserId() 得到 null
 *             → OrderController.resolveCustomerId() 得到 null
 *             → OrderListIndexService.query() 是「有值才加过滤」的写法，
 *               customerId 为 null 时不追加 customer_id 条件
 *             → ES 返回【全平台订单】
 * </pre>
 * 即：一个过期 Token、甚至完全不带 Token，就能从顾客订单列表读到别人的订单。
 * 只有购物车（有 requireCustomer 守卫）和地址（SQL 等值匹配 null 不命中）碰巧安全。
 * <p>
 * 原代码里 {@code PUBLIC_PATHS} / {@code isPublicPath()} 是<b>死代码</b> —— 定义了却从未被调用，
 * 这正是上面漏洞的直接原因：网关从未真正执行「非公开路径必须登录」。本次修改让它生效。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jwt.header:Authorization}")
    private String header;

    /** 黑名单 Redis key 前缀（与 common.RedisService 保持一致：token:bl:） */
    private static final String TOKEN_BLACKLIST_PREFIX = "token:bl:";

    /* ==================== 公开接口白名单 ==================== */

    /**
     * 任何 HTTP 方法都公开的前缀。
     * <ul>
     *   <li>{@code /api/auth/} —— 登录、注册、找回密码（此时必然还没有 Token）；</li>
     *   <li>{@code /uploads/} —— 商品图、店铺 Logo、头像等静态资源。
     *       <b>千万别漏</b>：它走网关路由 {@code shopai-user-uploads}，漏了整站图片 401；</li>
     *   <li>{@code /error} —— 网关自身的错误转发。</li>
     * </ul>
     */
    private static final List<String> PUBLIC_ANY_PREFIXES = List.of(
            "/api/auth/",
            "/uploads/",
            "/error"
    );

    /**
     * <b>仅 GET</b> 公开的前缀：读接口匿名可看，写接口必须登录。
     * <p>
     * 之所以按方法区分：{@code /api/product/**} 里既有 {@code GET /api/product/{id}}
     * 这种公开浏览，也有 {@code POST /api/product}、{@code DELETE /api/product/{id}}
     * 这种必须登录的写操作。原来按前缀一刀切会连写接口一起放行。
     * <p>
     * 对应的前端匿名场景（首页 / 商品列表 / 商品详情 / 全部商家 / 店铺页 / 秒杀列表）：
     * {@code /product/hot}、{@code /product/{id}}、{@code /sku/list/{pid}}、{@code /sku/{id}}、
     * {@code /category/all}、{@code /search/product}、{@code /search/merchant}、
     * {@code /search/product/merchant/{mid}}、{@code /review/product/{pid}}、
     * {@code /review/product/{pid}/stats}、{@code /seckill/detail/{id}}。
     */
    private static final List<String> PUBLIC_GET_PREFIXES = List.of(
            "/api/product/",
            "/api/sku/",
            "/api/category/",
            "/api/search/",
            "/api/review/product/",
            "/api/seckill/detail/"
    );

    /**
     * <b>仅 GET</b> 公开的精确路径。
     * <p>
     * 它们不能写成前缀：{@code /api/merchant/} 这个前缀下面还有
     * {@code /api/merchant/me*}、{@code /withdraw*}、{@code /apply} 等必须登录的接口。
     */
    private static final List<String> PUBLIC_GET_EXACT = List.of(
            "/api/merchant/list",
            "/api/merchant/rank",
            "/api/seckill/list"
    );

    /**
     * 管理员专属接口前缀。
     * <p>
     * 必须列全：网关只按前缀判断"是否管理接口"，而后端的 admin 接口并不都住在
     * {@code /api/admin/} 下面。漏掉某一项，该前缀下的管理接口就只剩「需要登录」
     * 这一道门槛，任何顾客/商家都能打到 —— 真实事故：
     * <pre>
     *   顾客 token → GET /api/review/admin/list   → 200，拿到全平台评价
     *   顾客 token → GET /api/review/complaint/list → 200，拿到全部申诉
     * </pre>
     * 逐项说明：
     * <ul>
     *   <li>{@code /api/admin/} —— 用户 / 商家等既有管理接口（shopai-user、shopai-merchant）</li>
     *   <li>{@code /api/seckill/admin/} —— 秒杀服务管理接口。之所以不并入
     *       {@code /api/admin/}：那会和「用户服务负责 /api/admin/users、商家服务负责
     *       /api/admin/merchants」这套按资源划分的既有约定混在一起，路由归属不清</li>
     *   <li>{@code /api/order/admin/} —— 全部订单、强制取消/退款/完成</li>
     *   <li>{@code /api/review/admin/} —— 全部评价、按 id 搜索评价</li>
     * </ul>
     */
    private static final List<String> ADMIN_PATH_PREFIXES = List.of(
            "/api/admin/",
            "/api/seckill/admin/",
            "/api/order/admin/",
            "/api/review/admin/"
    );

    /**
     * 商家自助接口（必须 merchant 角色）——精确列出，避免误伤公开的商家查询接口。
     */
    private static final List<String> MERCHANT_SELF_PATHS = List.of(
            "/api/merchant/apply",             // 入驻申请
            "/api/merchant/me",                // 我的店铺详情（含 /me/store、/me/finance 等子路径）
            "/api/merchant/withdraw",          // 申请提现
            "/api/merchant/withdraws",         // 提现记录
            "/api/merchant/weekly-revenue",    // 历史周营收
            "/api/merchant/real-name",         // 实名认证（查/提交/改/删）
            "/api/seckill/merchant"            // 秒杀商家端全部接口（活动管理、库存额度、预热）
    );

    public JwtAuthGlobalFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        String token = request.getHeaders().getFirst(header);
        boolean hasToken = token != null && !token.isBlank();
        String cleanToken = hasToken ? token.trim() : null;

        // ========== 1. 公开接口：匿名放行 ==========
        // 注意：白名单判定必须在最前面，否则首页/商品页/店铺页/图片等匿名浏览会直接被 401。
        if (isPublicPath(path, method)) {
            if (!hasToken) {
                return chain.filter(exchange);
            }
            // 带了 Token 就尽量把身份注入下去（下游可用它做个性化），
            // 但 Token 无效 / 已拉黑 / Redis 不可用都【不阻断】公开接口。
            return passThroughPublic(exchange, chain, cleanToken);
        }

        // ========== 2. 其余接口：默认拒绝，必须有有效 Token ==========
        if (!hasToken) {
            return unauthorized(exchange, "请先登录");
        }

        return redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + cleanToken)
                .flatMap(blacklisted -> {
                    // 2.1 已退出登录（黑名单）
                    if (Boolean.TRUE.equals(blacklisted)) {
                        return unauthorized(exchange, "Token已失效，请重新登录");
                    }

                    // 2.2 签名 / 过期校验
                    if (!jwtUtil.validateToken(cleanToken)) {
                        return unauthorized(exchange, "Token无效，请重新登录");
                    }

                    // 2.3 解析身份（校验通过后仍可能因声明的类型不符抛异常，兜底成 401）
                    Long userId;
                    String phone;
                    String role;
                    try {
                        userId = jwtUtil.getUserId(cleanToken);
                        phone = jwtUtil.getPhone(cleanToken);
                        role = jwtUtil.getRole(cleanToken);
                    } catch (Exception e) {
                        log.warn("Token 校验通过但解析身份失败: {}", e.getMessage());
                        return unauthorized(exchange, "Token无效，请重新登录");
                    }
                    if (userId == null) {
                        return unauthorized(exchange, "Token无效，请重新登录");
                    }

                    // 2.4 角色校验：管理员接口需 admin
                    if (isAdminPath(path) && !"admin".equalsIgnoreCase(role)) {
                        return forbidden(exchange, "无权访问，权限不足");
                    }

                    // 2.5 角色校验：商家自助接口需 merchant
                    //     与 admin 一致，只认 merchant；其他角色（含 customer）一律 403。
                    //     管理后台走的是 /api/admin/merchants/**，不依赖这些接口，
                    //     因此不需要给 admin 额外放行。
                    if (isMerchantSelfPath(path) && !"merchant".equalsIgnoreCase(role)) {
                        return forbidden(exchange, "无权访问，仅商家可操作");
                    }

                    // 2.6 注入身份头后转发
                    return chain.filter(withIdentity(exchange, userId, role, phone));
                });
    }

    /**
     * 公开接口的放行逻辑：能注入身份就注入，注入不了就按匿名放行。
     * <p>
     * 实现上刻意<b>先同步算出身份</b>再拼 Redis 查询，而不是用 {@code onErrorResume}
     * 兜底 —— 因为 {@code onErrorResume} 也会捕获 {@code chain.filter()} 自身抛出的异常，
     * 那样会对同一个请求执行两次过滤链（重复调用下游）。
     */
    private Mono<Void> passThroughPublic(ServerWebExchange exchange, GatewayFilterChain chain, String cleanToken) {
        ServerWebExchange identified;
        try {
            if (!jwtUtil.validateToken(cleanToken)) {
                return chain.filter(exchange);
            }
            Long userId = jwtUtil.getUserId(cleanToken);
            if (userId == null) {
                return chain.filter(exchange);
            }
            identified = withIdentity(exchange, userId, jwtUtil.getRole(cleanToken), jwtUtil.getPhone(cleanToken));
        } catch (Exception e) {
            // Token 是坏的也不影响公开接口
            return chain.filter(exchange);
        }

        // 已退出的 Token 不注入身份（但仍放行：公开接口本来就不需要身份）
        return redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + cleanToken)
                .map(blacklisted -> !Boolean.TRUE.equals(blacklisted))
                .onErrorReturn(true)   // Redis 不可用时不能把公开接口一起打挂
                .defaultIfEmpty(true)
                .flatMap(notBlacklisted -> chain.filter(notBlacklisted ? identified : exchange));
    }

    /** 把身份写入下游请求头 */
    private ServerWebExchange withIdentity(ServerWebExchange exchange, Long userId, String role, String phone) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Role", role == null ? "" : role)
                .header("X-User-Phone", phone == null ? "" : phone)
                .build();
        return exchange.mutate().request(mutated).build();
    }

    /* ==================== 白名单判定 ==================== */

    /**
     * 是否为公开接口（无需登录即可访问）。
     *
     * @param path   请求路径
     * @param method HTTP 方法；{@code null} 时按 GET 处理（只会更宽松）
     */
    private boolean isPublicPath(String path, HttpMethod method) {
        if (path == null) {
            return false;
        }
        // 预检请求不带凭证，交给网关的 globalcors 处理，这里一律放行
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }

        for (String p : PUBLIC_ANY_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }

        boolean isRead = method == null || HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        if (!isRead) {
            return false;
        }

        for (String p : PUBLIC_GET_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        if (PUBLIC_GET_EXACT.contains(path)) {
            return true;
        }
        return isPublicMerchantDetail(path);
    }

    /**
     * {@code GET /api/merchant/{数字id}} —— 店铺详情页，匿名可看。
     * <p>
     * 用「路径末段全是数字」把资源详情与 {@code /me}、{@code /list}、{@code /rank}
     * 这类语义路径区分开，既不用维护前缀清单，也不会把
     * {@code /api/merchant/me}（必须登录）误放行。
     */
    private boolean isPublicMerchantDetail(String path) {
        if (!path.startsWith("/api/merchant/")) {
            return false;
        }
        String rest = path.substring("/api/merchant/".length());
        if (rest.isEmpty() || rest.indexOf('/') >= 0) {
            return false;
        }
        return rest.chars().allMatch(Character::isDigit);
    }

    /**
     * 判断是否为管理员专属接口。
     * <p>
     * 通过 {@link #ADMIN_PATH_PREFIXES} 统一维护，见该常量的说明
     * （漏列前缀 = 该前缀下的管理接口对所有登录用户开放）。
     */
    private boolean isAdminPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为商家自助接口（需 merchant 角色）。
     * <p>
     * 匹配规则：命中路径本身，或命中其后紧跟 "/" 的子路径。
     * 例如 "/api/merchant/me/store" 命中前缀 "/api/merchant/me"，
     * 而 "/api/merchant/members" 不会被误判为 "/api/merchant/me" 的子路径。
     */
    private boolean isMerchantSelfPath(String path) {
        if (path == null) {
            return false;
        }
        for (String p : MERCHANT_SELF_PATHS) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /* ==================== 响应写出 ==================== */

    /** 返回 401 未登录 */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        return writeJson(exchange, HttpStatus.UNAUTHORIZED, Result.error(401, msg));
    }

    /** 返回 403 无权限 */
    private Mono<Void> forbidden(ServerWebExchange exchange, String msg) {
        return writeJson(exchange, HttpStatus.FORBIDDEN, Result.error(403, msg));
    }

    /**
     * 写出统一 JSON 响应（与前端响应拦截器兼容）
     */
    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, Result<?> result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":500,\"message\":\"系统错误\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 过滤器执行顺序：越小越先执行。认证过滤器应在路由转发前执行。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}

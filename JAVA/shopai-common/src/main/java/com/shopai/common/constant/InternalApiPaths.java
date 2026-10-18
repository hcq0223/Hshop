package com.shopai.common.constant;

/**
 * 服务间内部接口路径常量（共享库）
 * <p>
 * 微服务拆分说明：各服务按「独立库」处理后，跨服务数据访问一律通过 Feign 调用对方服务的
 * 内部接口完成。为避免提供方（@RequestMapping）与消费方（@FeignClient path）各写一份字符串
 * 而产生漂移，内部接口路径统一在此声明，双方共同引用。
 * <p>
 * 安全约定：{@code /api/**&#47;internal/**} 这类内部接口不对网关暴露
 * （网关路由不匹配 → 404），仅允许服务间 Feign 直连（见 shopai-gateway 的 application.yml）。
 */
public final class InternalApiPaths {

    private InternalApiPaths() {
    }

    /** 内部接口统一前缀片段（用于判断与网关排除） */
    public static final String INTERNAL_SEGMENT = "/internal/";

    // ==================== shopai-user ====================
    /** 用户服务内部接口根路径 */
    public static final String USER_INTERNAL = "/api/user/internal";
    /** 用户服务：客户内部接口根路径 */
    public static final String CUSTOMER_INTERNAL = "/api/customer/internal";

    // ==================== shopai-merchant ====================
    /** 商家服务内部接口根路径 */
    public static final String MERCHANT_INTERNAL = "/api/merchant/internal";

    // ==================== shopai-product ====================
    /** 商品服务内部接口根路径 */
    public static final String PRODUCT_INTERNAL = "/api/product/internal";

    // ==================== shopai-order ====================
    /** 订单服务内部接口根路径 */
    public static final String ORDER_INTERNAL = "/api/order/internal";

    // ==================== shopai-inventory ====================
    /** 库存服务内部接口根路径 */
    public static final String INVENTORY_INTERNAL = "/api/inventory";

    // ==================== shopai-search ====================
    /** 搜索服务内部接口根路径（列表查询改造后，各业务服务的列表查询由此承载） */
    public static final String SEARCH_INTERNAL = "/api/search/internal";

    // ==================== shopai-seckill ====================
    /**
     * 秒杀服务内部接口根路径。
     * <p>
     * 用途：管理端与其它服务运维调用（活动状态流转、库存回补、预热重试等），
     * 这些接口不对网关暴露，仅服务间直连。
     * <p>
     * 注意：秒杀服务**对外**的买家/卖家接口走 {@code /api/seckill/**}，
     * 由网关路由并对商家侧做角色校验。
     */
    public static final String SECKILL_INTERNAL = "/api/seckill/internal";

    // ==================== 服务名（Nacos 注册名，Feign name 使用） ====================
    public static final String SERVICE_USER = "shopai-user";
    public static final String SERVICE_MERCHANT = "shopai-merchant";
    public static final String SERVICE_PRODUCT = "shopai-product";
    public static final String SERVICE_ORDER = "shopai-order";
    public static final String SERVICE_INVENTORY = "shopai-inventory";
    public static final String SERVICE_SEARCH = "shopai-search";
    public static final String SERVICE_SECKILL = "shopai-seckill";
}

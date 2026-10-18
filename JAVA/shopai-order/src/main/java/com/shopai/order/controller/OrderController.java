package com.shopai.order.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.order.entity.Order;
import com.shopai.order.service.CustomerResolver;
import com.shopai.order.service.OrderEnrichService;
import com.shopai.order.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单控制器 — 完整状态机 API
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Controller.OrderController 迁移至 shopai-order 服务。
 * 鉴权改造：不再各自验签 JWT（网关统一认证），身份从 X-User-* 请求头读取（CurrentUser）。
 * <p>
 * 微服务拆分说明（独立库）：
 * - userId → customerId 映射：原 CustomerRefMapper 跨库直查 customers 表
 *   → CustomerResolver（内部 Feign 调用户服务客户内部接口）；
 * - userId → merchantId 映射：原 MerchantRefMapper 跨库直查 merchants 表
 *   → OrderEnrichService.resolveMerchantIdByUserId（内部 Feign 调商家服务）。
 * <p>
 * 路径：/api/order/** 保持不变（见设计文档 5.4）。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    // 微服务拆分：原 CustomerRefMapper（直查 customers 表）→ Feign 调用户服务内部接口
    private final CustomerResolver customerResolver;
    // 微服务拆分：原 MerchantRefMapper（直查 merchants 表）→ 内部走商家服务 Feign
    private final OrderEnrichService orderEnrichService;
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    // ==================== ID 解析辅助 ====================

    /**
     * 从请求头解析 userId，再映射为 customerId。
     * <p>
     * 微服务拆分说明：原 customerService.UserIdes(userId) → CustomerRefMapper.selectIdByUserId
     * （共享单库直查）→ 现改为 Feign 调用户服务客户内部接口。
     *
     * @return 客户ID；未创建客户资料或用户服务不可用时返回 null
     */
    private Long resolveCustomerId(HttpServletRequest request) {
        return customerResolver.toCustomerId(CurrentUser.getUserId(request));
    }

    /**
     * 从请求头解析 userId，再映射为 merchantId。
     * <p>
     * 微服务拆分说明：原 merchantService.getMerchantIdByToken(token) →
     * MerchantRefMapper.selectByUserId(userId).getId()（共享单库直查）
     * → 现改为经 OrderEnrichService 走商家服务 Feign。
     */
    private Long resolveMerchantId(HttpServletRequest request) {
        return orderEnrichService.resolveMerchantIdByUserId(CurrentUser.getUserId(request));
    }

    /**
     * 顾客列表类接口的登录校验（fail-closed）。
     * <p>
     * 为什么必须在这里拦：{@code OrderListIndexService.query} 采用「有值才加过滤」的写法，
     * customerId 为 null 时不追加 {@code customer_id} 条件，ES 会返回全平台订单。
     * 一旦网关把「Token 无效」当成匿名放行，这个组合就是一条越权读取路径。
     *
     * @return null 表示通过；否则是应直接返回的错误响应
     */
    private Result<?> requireCustomer(Long customerId) {
        return customerId == null ? Result.error(401, "请先登录") : null;
    }

    /**
     * 商家列表类接口的登录校验（fail-closed）。
     *
     * @see #requireCustomer(Long)
     */
    private Result<?> requireMerchant(Long merchantId) {
        return merchantId == null ? Result.error(401, "请先登录") : null;
    }

    private boolean isAdmin(HttpServletRequest request) {
        return CurrentUser.isRole(request, "admin");
    }

    /**
     * 订单归属校验：判断当前请求者是否有权查看该订单。
     * <p>
     * 安全修复说明（越权读取 BOLA / IDOR）：
     * 拆分前 {@code getOrderDetail(id)} 只要拿到 orderId 就无条件返回订单详情，
     * 而 orderId 是自增主键、极易枚举，任何已登录用户（顾客/商家）都能读取
     * 他人订单中的收货人姓名、电话、详细地址、金额与商品明细。
     * <p>
     * 放行规则（三者满足其一即可）：
     * <ul>
     *   <li>管理员（X-User-Role = admin）—— 管理后台需要查看全平台订单；</li>
     *   <li>下单顾客本人 —— order.customerId == 当前 userId 映射出的 customerId；</li>
     *   <li>订单所属商家 —— order.merchantId == 当前 userId 映射出的 merchantId。</li>
     * </ul>
     * <p>
     * 说明：
     * - customerId / merchantId 均由 Feign 向用户服务、商家服务换取（独立库后不再直查表）；
     * - 二者解析失败时返回 null，此时一律不放行，即「宁可拒绝也不越权」；
     * - 本方法只做读权限判定，不改变任何路由与响应结构。
     *
     * @param request HTTP 请求（承载网关下发的 X-User-Id / X-User-Role）
     * @param order   待校验的订单
     * @return true = 允许访问
     */
    private boolean canAccessOrder(HttpServletRequest request, Order order) {
        if (order == null) {
            return false;
        }
        // 1. 管理员放行
        if (isAdmin(request)) {
            return true;
        }
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) {
            return false;
        }
        // 2. 下单顾客本人
        Long customerId = resolveCustomerId(request);
        if (customerId != null && customerId.equals(order.getCustomerId())) {
            return true;
        }
        // 3. 订单所属商家
        Long merchantId = resolveMerchantId(request);
        return merchantId != null && merchantId.equals(order.getMerchantId());
    }

    // ==================== 查询 ====================

    @GetMapping("/detail/{id}")
    public Result<?> getOrderDetail(HttpServletRequest request, @PathVariable Long id) {
        Order order = orderService.getOrderDetail(id);
        if (order == null) return Result.error("订单不存在");
        // 安全修复：越权读取防护。原实现无归属校验，任何登录用户可枚举自增 orderId
        // 读取他人订单（收货人姓名/电话/详细地址/金额/商品明细）。
        // 无权时统一返回「订单不存在」，不暴露该订单是否真实存在。
        if (!canAccessOrder(request, order)) {
            log.warn("订单越权读取已拦截: orderId={}, userId={}, role={}",
                    id, CurrentUser.getUserId(request), CurrentUser.getRole(request));
            return Result.error("订单不存在");
        }
        return Result.ok(order);
    }

    @GetMapping("/byOrderNo")
    public Result<?> getByOrderNo(HttpServletRequest request, @RequestParam String orderNo) {
        Order order = orderService.getOrderByOrderNo(orderNo);
        if (order == null) return Result.error("订单不存在");
        // 安全修复：与 /detail/{id} 同理 —— orderNo 同样可被枚举，必须做相同的归属校验，
        // 否则只堵住 id 入口，越权读取仍可从这个入口绕过去。
        if (!canAccessOrder(request, order)) {
            log.warn("订单越权读取已拦截(byOrderNo): orderNo={}, userId={}, role={}",
                    orderNo, CurrentUser.getUserId(request), CurrentUser.getRole(request));
            return Result.error("订单不存在");
        }
        return Result.ok(order);
    }

    @GetMapping("/customer/orders")
    public Result<?> getCustomerOrders(HttpServletRequest request,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(required = false) String status) {
        Long customerId = resolveCustomerId(request);
        // 安全修复（越权读取，fail-closed）：底层 ES 列表查询在 customerId 为 null 时
        // **不会追加 customer_id 过滤条件**，返回的是「全平台订单」。而网关对
        // 无效/过期 Token 的处理是「放行但不注入 X-User-Id」（只有 /api/admin/** 与
        // 商家自助前缀才返回 401），所以一个过期 Token、甚至不带 Token，
        // 直接打这个接口就能读到别人的订单列表。
        // 这里必须显式拒绝，不能指望下游兜底。
        Result<?> deny = requireCustomer(customerId);
        if (deny != null) return deny;
        if (status != null && !status.isEmpty()) {
            return Result.ok("我的订单", orderService.getOrdersByStatus(status, customerId, null));
        }
        return Result.ok("我的订单", orderService.getOrdersByCustomer(customerId, page, size));
    }

    // 顾客订单历史（已完成+已取消）
    @GetMapping("/customer/history")
    public Result<?> getCustomerHistory(HttpServletRequest request) {
        Long customerId = resolveCustomerId(request);
        Result<?> deny = requireCustomer(customerId);
        if (deny != null) return deny;
        return Result.ok("订单历史", orderService.getHistoryOrders(customerId, null));
    }

    @GetMapping("/merchant/orders")
    public Result<?> getMerchantOrders(HttpServletRequest request,
                                       @RequestParam(required = false) String status) {
        Long merchantId = resolveMerchantId(request);
        // 与顾客列表同理：merchantId 为 null 时 ES 查询会退化成「全平台订单」
        Result<?> deny = requireMerchant(merchantId);
        if (deny != null) return deny;
        if (status != null && !status.isEmpty())
            return Result.ok("店铺订单", orderService.getOrdersByStatus(status, null, merchantId));
        return Result.ok("店铺订单", orderService.getOrdersByMerchant(merchantId));
    }

    // 商家订单历史（已完成+已取消）
    @GetMapping("/merchant/history")
    public Result<?> getMerchantHistory(HttpServletRequest request) {
        Long merchantId = resolveMerchantId(request);
        Result<?> deny = requireMerchant(merchantId);
        if (deny != null) return deny;
        return Result.ok("订单历史", orderService.getHistoryOrders(null, merchantId));
    }

    // ==================== 创建订单 ====================

    @PostMapping("/create")
    public Result<?> createOrder(HttpServletRequest request,
                                 @RequestBody Map<String, Object> body) {
        Long customerId = resolveCustomerId(request);
        Long addressId = parseLong(body.get("addressId"));

        // 直接购买
        if (body.containsKey("skuId")) {
            Long skuId = parseLong(body.get("skuId"));
            int quantity = body.get("quantity") != null ? toInt(body.get("quantity")) : 1;
            if (addressId == null) return Result.error("需要 addressId");
            try {
                Order order = orderService.createOrderDirect(customerId, skuId, quantity, addressId);
                return Result.ok("订单创建成功", order);
            } catch (RuntimeException e) {
                return Result.error(e.getMessage());
            }
        }

        // 购物车结算
        Long merchantId = parseLong(body.get("merchantId"));
        @SuppressWarnings("unchecked")
        List<Object> cartIdsRaw = (List<Object>) body.get("selectedCartIds");
        if (merchantId == null || addressId == null || cartIdsRaw == null || cartIdsRaw.isEmpty())
            return Result.error("需要 merchantId、selectedCartIds、addressId");

        List<Long> selectedCartIds = cartIdsRaw.stream()
                .map(o -> o instanceof Number n ? n.longValue() : Long.parseLong(o.toString())).toList();
        try {
            List<Order> orders = orderService.createOrder(customerId, merchantId, selectedCartIds, addressId);
            return Result.ok("订单创建成功", orders);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 顾客操作 ====================

    /** 顾客支付 */
    @PutMapping("/pay/{orderId}")
    public Result<?> payOrder(HttpServletRequest request, @PathVariable Long orderId) {
        Long customerId = resolveCustomerId(request);
        try {
            orderService.payOrder(orderId, customerId);
            return Result.ok("支付成功");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 顾客取消（待支付） */
    @PostMapping("/cancel/{orderId}")
    public Result<?> cancelOrder(HttpServletRequest request, @PathVariable Long orderId) {
        Long customerId = resolveCustomerId(request);
        try {
            orderService.cancelOrderByCustomer(orderId, customerId);
            return Result.ok("订单已取消");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 顾客确认收货 */
    @PutMapping("/receive/{orderId}")
    public Result<?> receiveOrder(HttpServletRequest request, @PathVariable Long orderId) {
        Long customerId = resolveCustomerId(request);
        try {
            orderService.receiveOrder(orderId, customerId);
            return Result.ok("已确认收货");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 顾客确认完成（仅 received 状态可用） */
    @PutMapping("/complete/{orderId}")
    public Result<?> completeOrder(HttpServletRequest request, @PathVariable Long orderId) {
        Long customerId = resolveCustomerId(request);
        try {
            orderService.completeOrder(orderId, "customer", customerId);
            return Result.ok("订单已完成");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 顾客申请退款 */
    @PostMapping("/refund/request/{orderId}")
    public Result<?> requestRefund(HttpServletRequest request,
                                   @PathVariable Long orderId,
                                   @RequestBody Map<String, Object> body) {
        Long customerId = resolveCustomerId(request);
        try {
            orderService.requestRefund(orderId, customerId,
                    (String) body.get("refundType"),
                    body.get("refundAmount") != null ? new BigDecimal(body.get("refundAmount").toString()) : null,
                    (String) body.get("refundReason"),
                    (String) body.get("returnTrackingNumber"),
                    (String) body.get("returnTrackingCompany"));
            return Result.ok("退款申请已提交");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    // ==================== 商户操作 ====================

    /** 商户发货 */
    @PutMapping("/ship/{orderId}")
    public Result<?> shipOrder(HttpServletRequest request,
                               @PathVariable Long orderId,
                               @RequestBody Map<String, Object> body) {
        String role = CurrentUser.getRole(request);
        Long merchantId = resolveMerchantId(request);
        if (!"merchant".equals(role) && !"admin".equals(role)) return Result.error("无权操作");
        try {
            orderService.shipOrder(orderId, merchantId,
                    (String) body.get("trackingNumber"),
                    (String) body.get("trackingCompany"));
            return Result.ok("发货成功");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 商户更新物流 */
    @PutMapping("/tracking/{orderId}")
    public Result<?> updateTracking(HttpServletRequest request,
                                    @PathVariable Long orderId,
                                    @RequestBody Map<String, Object> body) {
        Long merchantId = resolveMerchantId(request);
        try {
            orderService.updateTracking(orderId, merchantId,
                    (String) body.get("trackingNumber"),
                    (String) body.get("trackingCompany"));
            return Result.ok("物流信息已更新");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 商户强制关闭 */
    @PostMapping("/merchant/force-close/{orderId}")
    public Result<?> forceCloseByMerchant(HttpServletRequest request, @PathVariable Long orderId) {
        Long merchantId = resolveMerchantId(request);
        try {
            orderService.forceCloseByMerchant(orderId, merchantId);
            return Result.ok("订单已关闭");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 商户同意退款 */
    @PostMapping("/refund/approve/{orderId}")
    public Result<?> approveRefund(HttpServletRequest request, @PathVariable Long orderId) {
        Long merchantId = resolveMerchantId(request);
        try {
            orderService.approveRefund(orderId, merchantId);
            return Result.ok("退款已处理");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 商户拒绝退款 */
    @PostMapping("/refund/reject/{orderId}")
    public Result<?> rejectRefund(HttpServletRequest request, @PathVariable Long orderId) {
        Long merchantId = resolveMerchantId(request);
        try {
            orderService.rejectRefund(orderId, merchantId);
            return Result.ok("已拒绝退款");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    // ==================== 管理员操作 ====================

    /** 管理员查询全平台订单 */
    @GetMapping("/admin/orders")
    public Result<?> getAdminOrders(HttpServletRequest request,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "200") int size,
                                    @RequestParam(required = false) String status) {
        if (!isAdmin(request)) return Result.error("无权操作");
        if (status != null && !status.isEmpty()) {
            return Result.ok("全部订单", orderService.getOrdersByStatus(status, null, null));
        }
        return Result.ok("全部订单", orderService.getAllOrders(page, size));
    }

    // 管理员订单历史（已完成+已取消）
    @GetMapping("/admin/history")
    public Result<?> getAdminHistory(HttpServletRequest request) {
        if (!isAdmin(request)) return Result.error("无权操作");
        return Result.ok("订单历史", orderService.getHistoryOrders(null, null));
    }

    /** 管理员强制取消 */
    @PostMapping("/admin/force-cancel/{orderId}")
    public Result<?> forceCancelByAdmin(HttpServletRequest request, @PathVariable Long orderId) {
        if (!isAdmin(request)) return Result.error("无权操作");
        Long adminId = CurrentUser.getUserId(request);
        try {
            orderService.forceCancelByAdmin(orderId, adminId);
            return Result.ok("已强制取消");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 管理员强制退款 */
    @PostMapping("/admin/force-refund/{orderId}")
    public Result<?> forceRefundByAdmin(HttpServletRequest request,
                                        @PathVariable Long orderId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        if (!isAdmin(request)) return Result.error("无权操作");
        Long adminId = CurrentUser.getUserId(request);
        BigDecimal refundAmount = body != null && body.get("refundAmount") != null
                ? new BigDecimal(body.get("refundAmount").toString()) : null;
        try {
            orderService.forceRefundByAdmin(orderId, adminId, refundAmount);
            return Result.ok("已强制退款");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 管理员强制完成 */
    @PostMapping("/admin/force-complete/{orderId}")
    public Result<?> forceCompleteByAdmin(HttpServletRequest request, @PathVariable Long orderId) {
        if (!isAdmin(request)) return Result.error("无权操作");
        Long adminId = CurrentUser.getUserId(request);
        try {
            orderService.forceCompleteByAdmin(orderId, adminId);
            return Result.ok("已强制完成");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    /** 管理员强制退款完成（退款中状态下） */
    @PostMapping("/admin/force-refund-complete/{orderId}")
    public Result<?> forceRefundComplete(HttpServletRequest request, @PathVariable Long orderId) {
        if (!isAdmin(request)) return Result.error("无权操作");
        Long adminId = CurrentUser.getUserId(request);
        try {
            orderService.forceRefundComplete(orderId, adminId);
            return Result.ok("退款已完成");
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    // ==================== 兼容旧接口 ====================

    /** @deprecated 用 /pay/{orderId} 替代 */
    @PutMapping("/status/{orderId}")
    public Result<?> updateStatus(HttpServletRequest request,
                                  @PathVariable Long orderId,
                                  @RequestParam String status) {
        Long userId = CurrentUser.getUserId(request);
        String role = CurrentUser.getRole(request);
        try {
            switch (status) {
                case "paid" -> {
                    Long cid = resolveCustomerId(request);
                    orderService.payOrder(orderId, cid);
                }
                case "shipped" -> {
                    Long mid = resolveMerchantId(request);
                    orderService.shipOrder(orderId, mid, null, null);
                }
                case "received" -> {
                    Long cid = resolveCustomerId(request);
                    orderService.receiveOrder(orderId, cid);
                }
                case "completed" -> {
                    orderService.completeOrder(orderId, role, userId);
                }
                case "cancelled" -> {
                    if ("admin".equals(role)) orderService.forceCancelByAdmin(orderId, userId);
                    else {
                        Long cid = resolveCustomerId(request);
                        orderService.cancelOrderByCustomer(orderId, cid);
                    }
                }
                default -> { return Result.error("不支持的状态"); }
            }
            return Result.ok("订单状态已更新为: " + status);
        } catch (RuntimeException e) { return Result.error(e.getMessage()); }
    }

    // ── 工具 ──
    private static Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }
    private static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return 0; }
    }
}

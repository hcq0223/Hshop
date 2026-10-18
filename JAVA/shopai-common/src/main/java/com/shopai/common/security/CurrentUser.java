package com.shopai.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前登录用户上下文工具类
 * <p>
 * 微服务拆分说明（网关统一认证模式）：
 * 网关(shopai-gateway)统一解析 JWT 并校验黑名单后，将用户身份写入以下请求头：
 *   - X-User-Id    ：用户ID（对应 users.id）
 *   - X-User-Role  ：角色（customer / merchant / admin）
 *   - X-User-Phone ：手机号
 * 业务服务不再各自验签 JWT，而是通过本工具类从请求头读取当前用户信息。
 * <p>
 * 用途：替代原单体中 Controller 通过 token 解析角色/用户ID 的方式（jwtUtil.getRole(token) 等）。
 */
public final class CurrentUser {

    /** 网关透传的用户ID请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 网关透传的角色请求头 */
    public static final String HEADER_USER_ROLE = "X-User-Role";
    /** 网关透传的手机号请求头 */
    public static final String HEADER_USER_PHONE = "X-User-Phone";

    private CurrentUser() {
        // 工具类，禁止实例化
    }

    /**
     * 从请求头获取当前用户ID
     *
     * @param request HTTP 请求
     * @return 用户ID，未登录时为 null
     */
    public static Long getUserId(HttpServletRequest request) {
        String v = request.getHeader(HEADER_USER_ID);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从请求头获取当前用户角色
     *
     * @param request HTTP 请求
     * @return 角色字符串（customer/merchant/admin），未登录时为 null
     */
    public static String getRole(HttpServletRequest request) {
        String v = request.getHeader(HEADER_USER_ROLE);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 从请求头获取当前用户手机号
     *
     * @param request HTTP 请求
     * @return 手机号，未登录时为 null
     */
    public static String getPhone(HttpServletRequest request) {
        String v = request.getHeader(HEADER_USER_PHONE);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 判断当前用户是否为指定角色
     *
     * @param request HTTP 请求
     * @param role    目标角色（customer/merchant/admin）
     * @return true 表示匹配
     */
    public static boolean isRole(HttpServletRequest request, String role) {
        String r = getRole(request);
        return r != null && r.equalsIgnoreCase(role);
    }
}

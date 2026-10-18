package com.shopai.common.exception;

import com.shopai.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 统一将 Spring Security 的认证/授权异常转换为前端友好的 JSON 格式。
 * Controller 层未捕获的 RuntimeException 也会被此处理器兜底。
 * <p>
 * 响应格式示例：
 * - 未登录：{"code":401,"message":"请先登录","data":null,"timestamp":...}
 * - 无权限：{"code":403,"message":"无权访问","data":null,"timestamp":...}
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.GlobalExceptionHandler 迁移至公共库。
 * 各业务服务通过 @SpringBootApplication 的组件扫描自动装配（见各服务的启动类注释）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 认证异常（未登录 / Token无效）
     * <p>
     * Spring Security 在用户未认证时抛出 AuthenticationException 或其子类，
     * 此处统一返回 HTTP 401 + JSON 格式错误信息。
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED) // HTTP 401
    public Result<?> handleAuthenticationException(AuthenticationException e, HttpServletRequest request) {
        log.warn("认证失败, URI={}, message={}", request.getRequestURI(), e.getMessage());
        return Result.error(401, "请先登录");
    }

    /**
     * 权限异常（已登录但角色不匹配，如普通用户访问管理员接口）
     * <p>
     * Spring Security 在用户权限不足时抛出 AccessDeniedException，
     * 此处统一返回 HTTP 403 + JSON 格式错误信息。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN) // HTTP 403
    public Result<?> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.warn("权限不足, URI={}, message={}", request.getRequestURI(), e.getMessage());
        return Result.error(403, "无权访问，权限不足");
    }

    /**
     * 业务异常兜底（RuntimeException）
     * <p>
     * Controller/Service 中抛出的 RuntimeException 统一返回 HTTP 500 + JSON。
     * 如果你有自定义业务异常类，可以在这里新增对应的 @ExceptionHandler。
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // HTTP 500
    public Result<?> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("业务异常, URI={}, message={}", request.getRequestURI(), e.getMessage(), e);
        return Result.error(e.getMessage());
    }

    /**
     * 兜底异常（所有未处理的异常）
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // HTTP 500
    public Result<?> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常, URI={}", request.getRequestURI(), e);
        return Result.error("服务器内部错误，请稍后重试");
    }
}

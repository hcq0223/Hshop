package com.shopai.common.feign;

import feign.FeignException;
import feign.RetryableException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Feign 降级原因描述工具。
 * <p>
 * 统一把 Throwable 转成简短可读的字符串，用于降级日志和监控标签，
 * 避免每个 FallbackFactory 里重复写 if-else。
 */
public final class FeignFallbackReason {

    private FeignFallbackReason() {}

    /**
     * 返回简短的失败原因，适合打日志 / 作监控标签。
     * 例：timeout、connect_refused、http_500、http_404、retryable、unknown
     */
    public static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
            return "timeout";
        }
        if (cause instanceof ConnectException) {
            return "connect_refused";
        }
        if (cause instanceof RetryableException) {
            // RetryableException 通常是连接失败或读超时，具体看 cause
            Throwable inner = cause.getCause();
            if (inner instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (inner instanceof ConnectException) {
                return "connect_refused";
            }
            return "retryable";
        }
        if (cause instanceof FeignException.NotFound) {
            return "http_404";
        }
        if (cause instanceof FeignException) {
            return "http_" + ((FeignException) cause).status();
        }
        return cause.getClass().getSimpleName();
    }
}
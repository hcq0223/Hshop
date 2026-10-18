package com.shopai.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果类 - 所有Controller返回此格式
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.common.Result 迁移至公共库，
 * 各业务服务统一引用，保证响应格式一致。
 */
@Data
public class Result<T> implements Serializable {
    private Integer code;
    private String message;
    private T data;
    private Long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok() {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "操作成功";
        return r;
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = ok();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok(String msg, T data) {
        Result<T> r = ok();
        r.message = msg;
        r.data = data;
        return r;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> r = new Result<>();
        r.code = 500;
        r.message = msg;
        return r;
    }

    public static <T> Result<T> error(Integer code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = msg;
        return r;
    }

}

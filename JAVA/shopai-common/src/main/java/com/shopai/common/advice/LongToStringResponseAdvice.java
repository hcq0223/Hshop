package com.shopai.common.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 全局 Long → String 响应转换（解决前端 JS Number 精度丢失）
 * <p>
 * 在所有 @ResponseBody 写出 JSON 之前，将响应对象转成 Map/List 树，
 * 递归遍历替换所有 Long 为 String，绕过 Jackson 版本/桥接包兼容问题。
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.LongToStringResponseAdvice 迁移至公共库。
 * 各业务服务自动装配（注意：依赖 spring-boot-starter-web，网关(WebFlux)不引入本类）。
 */
@ControllerAdvice
public class LongToStringResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public LongToStringResponseAdvice() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // 关键：禁用时间戳格式，让 LocalDateTime 序列化为 "2026-07-19T18:30:00" 而不是数组 [2026,7,19,...]
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) return null;

        // String / byte[] 等不需要转换
        if (body instanceof String || body instanceof byte[]) return body;

        try {
            // 实体对象 → Map/List 树
            Object tree = objectMapper.convertValue(body, Object.class);
            return convertLongToString(tree);
        } catch (Exception e) {
            // 转换失败时原样返回
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object convertLongToString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long) return obj.toString();
        if (obj instanceof Integer || obj instanceof Boolean || obj instanceof Double
                || obj instanceof Float || obj instanceof Short || obj instanceof Byte) {
            return obj;
        }
        if (obj instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) obj);
            for (Map.Entry<String, Object> e : copy.entrySet()) {
                e.setValue(convertLongToString(e.getValue()));
            }
            return copy;
        }
        if (obj instanceof Collection) {
            List<Object> list = new ArrayList<>(((Collection<?>) obj).size());
            for (Object item : (Collection<?>) obj) {
                list.add(convertLongToString(item));
            }
            return list;
        }
        return obj;
    }
}

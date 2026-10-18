package com.shopai.common;

import lombok.Data;

/**
 * 分页查询请求DTO
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.dto.PageRequest 迁移至公共库，
 * 供各服务 Controller 统一接收分页参数。
 */
@Data
public class PageRequest {
    /**
     * 当前页码
     */
    private Integer page = 1;
    /**
     * 每页条数
     */
    private Integer size = 10;
    /**
     * 搜索关键词
     */
    private String keyword;
    /**
     * 排序字段
     */
    private String orderBy;
    /**
     * 排序方向
     */
    private String orderDir;

    public int getOffset() {
        return (page - 1) * size;
    }

}

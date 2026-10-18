package com.shopai.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结果类
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.common.PageResult 迁移至公共库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {
    /**
     * 总记录数
     */
    private Long total;
    /**
     * 当前页码
     */
    private Integer page;
    /**
     * 每页条数
     */
    private Integer size;
    /**
     * 数据列表
     */
    private List<T> records;

    public String toCacheString() {
        return "total:" + total + ",page:" + page + ",size:" + size;
    }

}

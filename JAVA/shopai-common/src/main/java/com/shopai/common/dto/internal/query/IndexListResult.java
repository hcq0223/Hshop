package com.shopai.common.dto.internal.query;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通用列表查询响应（跨服务契约）
 * <p>
 * 微服务拆分说明：与 {@link IndexListQuery} 配对使用。搜索服务把 ES 命中结果
 * 与分页信息回给调用方（各业务服务），由业务服务转换成自己原有的对外响应结构，
 * 因此**前端契约不变**。
 * <p>
 * {@code records} 用 {@code Object} 承载：不同索引返回不同文档类型
 * （ProductDocument / MerchantDocument / OrderDocument / ...），
 * 由搜索服务序列化为 JSON，调用方按自己的类型反序列化（Feign 侧用具体泛型）。
 */
@Data
public class IndexListResult implements Serializable {

    /** 当前页记录（各索引的文档对象） */
    private List<Object> records;

    /** 命中总数 */
    private long total;

    /** 当前页码 */
    private int page;

    /** 每页条数 */
    private int size;

    /** 总页数 */
    private long pages;
}

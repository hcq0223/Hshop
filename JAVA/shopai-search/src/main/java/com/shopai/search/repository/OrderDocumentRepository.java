package com.shopai.search.repository;

import com.shopai.search.document.OrderDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 订单搜索仓储（Elasticsearch 的 order 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：订单列表由 Redis 缓存改为 ES 索引承载，
 * 订单服务通过本服务内部接口查询列表，本仓储负责 order 索引的读写。
 */
public interface OrderDocumentRepository extends ElasticsearchRepository<OrderDocument, String> {
}

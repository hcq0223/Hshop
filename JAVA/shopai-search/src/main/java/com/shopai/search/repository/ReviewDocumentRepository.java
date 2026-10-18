package com.shopai.search.repository;

import com.shopai.search.document.ReviewDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 评价搜索仓储（Elasticsearch 的 review 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：评价列表由 Redis 缓存改为 ES 索引承载，
 * 商家服务通过本服务内部接口查询列表，本仓储负责 review 索引的读写。
 */
public interface ReviewDocumentRepository extends ElasticsearchRepository<ReviewDocument, String> {
}

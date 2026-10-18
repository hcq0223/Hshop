package com.shopai.search.repository;

import com.shopai.search.document.UserDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 用户搜索仓储（Elasticsearch 的 user 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：后台用户列表由 Redis 缓存改为 ES 索引承载，
 * 用户服务通过本服务内部接口查询列表，本仓储负责 user 索引的读写。
 * <p>
 * 安全说明：user 索引不含 passwordHash 等凭据字段（见 {@link UserDocument}）。
 */
public interface UserDocumentRepository extends ElasticsearchRepository<UserDocument, String> {
}

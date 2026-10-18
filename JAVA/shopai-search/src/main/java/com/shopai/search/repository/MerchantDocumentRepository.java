package com.shopai.search.repository;

import com.shopai.search.document.MerchantDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 商家搜索仓储（Elasticsearch 的 merchant 索引）
 * <p>
 * 微服务拆分说明：原单体商家 LIKE 搜索迁至搜索服务，改用 Spring Data Elasticsearch
 * 的 Repository 对 merchant 索引做增删改查。
 */
public interface MerchantDocumentRepository extends ElasticsearchRepository<MerchantDocument, String> {
}

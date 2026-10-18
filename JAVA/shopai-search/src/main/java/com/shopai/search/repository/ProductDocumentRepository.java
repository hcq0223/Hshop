package com.shopai.search.repository;

import com.shopai.search.document.ProductDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 商品搜索仓储（Elasticsearch 的 product 索引）
 * <p>
 * 微服务拆分说明：原单体商品 LIKE 搜索迁至搜索服务，改用 Spring Data Elasticsearch
 * 的 Repository 对 product 索引做增删改查。
 */
public interface ProductDocumentRepository extends ElasticsearchRepository<ProductDocument, String> {
}

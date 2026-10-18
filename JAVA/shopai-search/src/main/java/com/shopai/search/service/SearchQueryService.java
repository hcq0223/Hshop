package com.shopai.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.shopai.common.PageResult;
import com.shopai.search.document.MerchantDocument;
import com.shopai.search.document.ProductDocument;
import com.shopai.search.dto.CategoryFacet;
import com.shopai.search.dto.FacetSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.AggregationsContainer;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索查询服务（Elasticsearch 检索）
 * <p>
 * 微服务拆分说明：原单体商品/商家 LIKE 搜索（ProductService.searchProducts、
 * MerchantService.searchMerchantsPublic）迁至本服务，改用 ES 检索。
 * <p>
 * 实现方式（2026-10-05 改造）：改用 Spring Data Elasticsearch 的 NativeQuery +
 * Elasticsearch Java Client 的类型化查询 DSL（Query/BoolQuery/MultiMatchQuery/TermQuery/
 * TermsQuery/RangeQuery/Aggregation），替代原先的 StringQuery 原生 JSON 拼接。
 * 原因：分类分面搜索需要「聚合(aggs) + 后置过滤(post_filter)」，StringQuery 仅能承载 query 段，装不下。
 * <p>
 * 排序策略：
 * - 有关键词时按相关性(_score)优先，再按销量/价格兜底；
 * - 无关键词时沿用原单体排序（商品：price_min DESC, sales_count DESC, created_at DESC；商家：rating DESC）。
 */
@Service
public class SearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryService.class);

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    /** 商品搜索的 multi_match 字段（与 ProductDocument 的 text 字段对应） */
    private static final List<String> PRODUCT_SEARCH_FIELDS = List.of("name", "shopName", "description");

    /** 店铺内商品搜索字段 */
    private static final List<String> PRODUCT_IN_MERCHANT_FIELDS = List.of("name", "description");

    /** 商家搜索字段 */
    private static final List<String> MERCHANT_SEARCH_FIELDS = List.of("shopName", "shopDescription");

    /** 分类分面聚合名 */
    private static final String CATEGORY_FACET_AGG = "categoryFacets";

    /** 分面桶返回的最大分类数 */
    private static final int CATEGORY_FACET_SIZE = 100;

    // ==================== 商品搜索 ====================

    /**
     * 前台商品搜索（支持关键词、分类、价格区间）
     * 旧路径：/api/product/search → 新路径：/api/search/product
     *
     * @param keyword     关键词（匹配 name / shopName / description）
     * @param categoryId  分类ID（可选）
     * @param categoryIds 分类ID列表（可选，逗号分隔传入，命中其一）
     * @param minPrice    最低价（可选）
     * @param maxPrice    最高价（可选）
     * @param page        页码
     * @param size        每页条数
     */
    public PageResult<ProductDocument> searchProducts(String keyword, Integer categoryId,
                                                      List<Integer> categoryIds,
                                                      BigDecimal minPrice,
                                                      BigDecimal maxPrice,
                                                      int page, int size) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        addKeyword(must, keyword, PRODUCT_SEARCH_FIELDS);
        addCategory(filter, categoryId, categoryIds);
        addPriceRange(filter, minPrice, maxPrice);
        filter.add(termEquals("status", "on_sale")); // 仅搜索在售商品

        Query query = boolQuery(must, filter);
        return executeProductQuery(query, productSort(keyword), page, size);
    }

    /**
     * 店铺内商品搜索（限定某商户范围）
     * 旧路径：/api/product/merchant/{merchantId}/search → 新路径：/api/search/product/merchant/{merchantId}
     *
     * @param merchantId 商户ID
     * @param keyword    关键词（匹配 name / description）
     * @param categoryId 分类ID（可选）
     */
    public PageResult<ProductDocument> searchProductsInMerchant(Long merchantId, String keyword,
                                                                Integer categoryId, int page, int size) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();

        addKeyword(must, keyword, PRODUCT_IN_MERCHANT_FIELDS);
        filter.add(termEquals("merchantId", String.valueOf(merchantId))); // 限定商户
        if (categoryId != null) {
            filter.add(termEquals("categoryId", categoryId.longValue()));
        }
        filter.add(termEquals("status", "on_sale"));

        Query query = boolQuery(must, filter);
        return executeProductQuery(query, productSort(keyword), page, size);
    }

    // ==================== 商品分面搜索（复合搜索 + 桶聚合 + post_filter） ====================

    /**
     * 商品「分面搜索」：复合查询 + 分类桶聚合 + post_filter 后置过滤
     * <p>
     * 典型电商筛选场景：
     * - 主查询（一致性/scope query）= 关键词 + 价格 + 在售（**不含分类**），决定聚合的统计范围；
     * - 桶聚合 categoryFacets = 按 categoryId 统计各分类命中数；
     * - post_filter = 仅当传了 categoryId/categoryIds 时生效，过滤命中结果但**不影响聚合计数**。
     * <p>
     * 因此前端「选中某分类」后，分类侧栏的计数仍是全量分布（而非塌缩成 0），是可用的分面检索。
     * 注：分类名由前端用 /api/category/all 的本地映射表补全（不在 ES 里做子聚合）。
     *
     * @param keyword     关键词（可选）
     * @param categoryId  选中的分类ID（可选，作为 post_filter）
     * @param categoryIds 选中的分类ID列表（可选，作为 post_filter，命中其一）
     * @param minPrice    最低价（可选）
     * @param maxPrice    最高价（可选）
     * @param page        页码
     * @param size        每页条数
     * @return 分页结果 + 分类分面桶
     */
    public FacetSearchResult<ProductDocument> searchProductFacets(String keyword, Integer categoryId,
                                                                  List<Integer> categoryIds,
                                                                  BigDecimal minPrice,
                                                                  BigDecimal maxPrice,
                                                                  int page, int size) {
        // 1) 一致性查询（scope）：关键词 + 价格 + 在售，不含分类
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();
        addKeyword(must, keyword, PRODUCT_SEARCH_FIELDS);
        addPriceRange(filter, minPrice, maxPrice);
        filter.add(termEquals("status", "on_sale"));
        Query scopeQuery = boolQuery(must, filter);

        // 2) 组装 NativeQuery：query + sort + 分类桶聚合
        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(scopeQuery)
                .withSort(productSort(keyword))
                .withAggregation(CATEGORY_FACET_AGG, Aggregation.of(a ->
                        a.terms(t -> t.field("categoryId").size(CATEGORY_FACET_SIZE))));

        // 3) post_filter：选中分类时只过滤命中结果，不影响上面的聚合计数
        Query postFilter = buildCategoryPostFilter(categoryId, categoryIds);
        if (postFilter != null) {
            builder.withFilter(postFilter);
        }

        NativeQuery query = builder.build();
        query.setPageable(PageRequest.of(page - 1, size));

        SearchHits<ProductDocument> hits = elasticsearchOperations.search(query, ProductDocument.class);

        List<ProductDocument> records = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
        List<CategoryFacet> facets = extractCategoryFacets(hits);

        return new FacetSearchResult<>(hits.getTotalHits(), page, size, records, facets);
    }

    // ==================== 商家搜索 ====================

    /**
     * 商家搜索（关键词匹配店铺名/简介）
     * 旧路径：/api/user/customer/searchMerchants → 新路径：/api/search/merchant
     */
    public PageResult<MerchantDocument> searchMerchants(String keyword, int page, int size) {
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();
        addKeyword(must, keyword, MERCHANT_SEARCH_FIELDS);
        // 仅搜索已认证商家（增量同步时未认证商家已从索引删除，此处再兜底过滤一次）
        filter.add(termEquals("isVerified", 1L));

        Query query = boolQuery(must, filter);

        List<SortOptions> sort = new ArrayList<>();
        if (hasKeyword(keyword)) {
            sort.add(scoreSortDesc());
        }
        sort.add(fieldSortDesc("rating"));

        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withSort(sort)
                .build();
        nativeQuery.setPageable(PageRequest.of(page - 1, size));

        SearchHits<MerchantDocument> hits = elasticsearchOperations.search(nativeQuery, MerchantDocument.class);
        return toPageResult(hits, page, size);
    }

    // ==================== 查询构建辅助 ====================

    /** 关键词匹配（有值时加入 must） */
    private void addKeyword(List<Query> must, String keyword, List<String> fields) {
        if (hasKeyword(keyword)) {
            must.add(Query.of(q -> q.multiMatch(m -> m.query(keyword).fields(fields))));
        }
    }

    /** 分类过滤（categoryId 或 categoryIds，命中其一即匹配） */
    private void addCategory(List<Query> filter, Integer categoryId, List<Integer> categoryIds) {
        if (categoryId != null) {
            filter.add(termEquals("categoryId", categoryId.longValue()));
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            List<FieldValue> values = categoryIds.stream()
                    .map(id -> FieldValue.of(id.longValue()))
                    .collect(Collectors.toList());
            filter.add(Query.of(q -> q.terms(t -> t.field("categoryId")
                    .terms(tf -> tf.value(values)))));
        }
    }

    /** 价格区间过滤（沿用原单体语义：min+max 时 price_min BETWEEN；仅 min 时 price_min>=min；仅 max 时 price_max<=max） */
    private void addPriceRange(List<Query> filter, BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null) {
            filter.add(Query.of(q -> q.range(r -> r.field("priceMin")
                    .gte(JsonData.of(minPrice)).lte(JsonData.of(maxPrice)))));
        } else if (minPrice != null) {
            filter.add(Query.of(q -> q.range(r -> r.field("priceMin").gte(JsonData.of(minPrice)))));
        } else if (maxPrice != null) {
            filter.add(Query.of(q -> q.range(r -> r.field("priceMax").lte(JsonData.of(maxPrice)))));
        }
    }

    /** 构造 bool 查询：must（相关性）+ filter（不参与打分） */
    private Query boolQuery(List<Query> must, List<Query> filter) {
        return Query.of(q -> q.bool(b -> {
            if (must != null && !must.isEmpty()) b.must(must);
            if (filter != null && !filter.isEmpty()) b.filter(filter);
            return b;
        }));
    }

    /** 精确匹配（term）查询 */
    private Query termEquals(String field, Object value) {
        FieldValue fv = (value instanceof String s) ? FieldValue.of(s) : FieldValue.of(((Number) value).longValue());
        return Query.of(q -> q.term(t -> t.field(field).value(fv)));
    }

    /** 当存在分类筛选时，构建 post_filter；否则返回 null */
    private Query buildCategoryPostFilter(Integer categoryId, List<Integer> categoryIds) {
        List<Query> conditions = new ArrayList<>();
        if (categoryId != null) {
            conditions.add(termEquals("categoryId", categoryId.longValue()));
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            List<FieldValue> values = categoryIds.stream()
                    .map(id -> FieldValue.of(id.longValue()))
                    .collect(Collectors.toList());
            conditions.add(Query.of(q -> q.terms(t -> t.field("categoryId")
                    .terms(tf -> tf.value(values)))));
        }
        if (conditions.isEmpty()) {
            return null;
        }
        // 多个分类条件用 should（命中其一即可）——选中多分类时取并集
        return Query.of(q -> q.bool(b -> b.should(conditions).minimumShouldMatch("1")));
    }

    // ==================== 排序辅助 ====================

    /** 商品排序：有关键词时 _score 优先，再按 priceMin/salesCount/createdAt 降序 */
    private List<SortOptions> productSort(String keyword) {
        List<SortOptions> sort = new ArrayList<>();
        if (hasKeyword(keyword)) {
            sort.add(scoreSortDesc());
        }
        sort.add(fieldSortDesc("priceMin"));
        sort.add(fieldSortDesc("salesCount"));
        sort.add(fieldSortDesc("createdAt"));
        return sort;
    }

    /** _score 降序 */
    private SortOptions scoreSortDesc() {
        return SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc)));
    }

    /** 字段降序排序 */
    private SortOptions fieldSortDesc(String field) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(SortOrder.Desc)));
    }

    private boolean hasKeyword(String keyword) {
        return keyword != null && !keyword.isBlank();
    }

    // ==================== 执行与结果转换 ====================

    /** 执行商品查询并转为分页结果 */
    private PageResult<ProductDocument> executeProductQuery(Query query, List<SortOptions> sort, int page, int size) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withSort(sort)
                .build();
        nativeQuery.setPageable(PageRequest.of(page - 1, size));

        SearchHits<ProductDocument> hits = elasticsearchOperations.search(nativeQuery, ProductDocument.class);
        return toPageResult(hits, page, size);
    }

    /** 从 SearchHits 中提取分类分面桶 */
    private List<CategoryFacet> extractCategoryFacets(SearchHits<ProductDocument> hits) {
        List<CategoryFacet> facets = new ArrayList<>();
        if (hits == null || !hits.hasAggregations()) {
            return facets;
        }
        AggregationsContainer<?> container = hits.getAggregations();
        if (!(container instanceof ElasticsearchAggregations aggregations)) {
            return facets;
        }
        ElasticsearchAggregation aggWrapper = aggregations.get(CATEGORY_FACET_AGG);
        if (aggWrapper == null) {
            return facets;
        }
        Aggregate aggregate = aggWrapper.aggregation().getAggregate();
        // categoryId 为 integer 字段，ES 客户端反序列化为 LongTermsAggregate
        if (!aggregate.isLterms()) {
            return facets;
        }
        LongTermsAggregate terms = aggregate.lterms();
        for (LongTermsBucket bucket : terms.buckets().array()) {
            facets.add(new CategoryFacet((int) bucket.key(), bucket.docCount()));
        }
        return facets;
    }

    /** SearchHits → PageResult（前端沿用原单体分页结构：total/page/size/records） */
    private <T> PageResult<T> toPageResult(SearchHits<T> hits, int page, int size) {
        List<T> records = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
        return new PageResult<>(hits.getTotalHits(), page, size, records);
    }
}

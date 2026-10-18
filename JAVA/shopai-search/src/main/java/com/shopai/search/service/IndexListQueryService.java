package com.shopai.search.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.shopai.common.dto.internal.query.IndexListQuery;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.search.document.MerchantDocument;
import com.shopai.search.document.OrderDocument;
import com.shopai.search.document.ProductDocument;
import com.shopai.search.document.ReviewDocument;
import com.shopai.search.document.UserDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引列表查询服务（统一入口，列表查询改造核心）
 * <p>
 * 背景：改造前各服务的分页列表走 Redis 缓存（{@code cache:xxx:list:{hash}}），
 * 任意一条数据变更就 {@code deleteByPrefix} 把该列表**所有用户、所有页码**的缓存全部清空，
 * 失效代价过大；且缓存键由查询参数哈希而来，缓存命中率随筛选条件组合急剧下降。
 * <p>
 * 改造后：列表查询统一由本服务基于 Elasticsearch 完成（过滤 + 全文检索 + 排序 + 分页），
 * 各业务服务保留自己的对外路由不变，内部通过 Feign 调 {@code /api/search/internal/list}。
 * ES 天然支持任意条件组合的检索与深分页，不再需要「缓存 + 全量失效」这套机制。
 * <p>
 * 设计要点：
 * 1. **一个入口通吃五类索引**：用 {@code indexType} 分发到对应文档类型，
 *    避免为每个列表写一套请求/响应类型与查询逻辑；
 * 2. **过滤键按索引类型解释**：{@code filters} 是键值对，本服务按索引类型决定
 *    每个键是 term 还是 terms、是 Keyword 还是 Integer 字段；
 * 3. **排序白名单**：只允许各索引声明的字段参与排序，方向仅 asc/desc，
 *    防止通过排序字段注入（ES 排序字段不支持脚本，但仍需限定范围）；
 * 4. **降级**：ES 查询异常时向上抛出，由调用方决定降级策略（各业务服务已做兜底）。
 */
@Service
public class IndexListQueryService {

    private static final Logger log = LoggerFactory.getLogger(IndexListQueryService.class);

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    // ==================== 各索引的可检索 / 可排序 / 可过滤字段约定 ====================

    /** 各索引的全文检索字段（keyword 落到这些字段的 multi_match） */
    private static final Map<String, List<String>> SEARCH_FIELDS = Map.of(
            IndexType.PRODUCT, List.of("name", "description", "shopName", "categoryName"),
            IndexType.MERCHANT, List.of("shopName", "shopDescription"),
            IndexType.ORDER, List.of("orderNo", "shopName", "customerNickname"),
            IndexType.REVIEW, List.of("content", "productName", "merchantName", "customerNickname"),
            IndexType.USER, List.of("username", "phone", "email", "customerNickname", "shopName")
    );

    /**
     * 各索引允许的排序字段白名单。
     * 说明：只暴露「数值 / 日期 / Keyword」字段；text 字段不参与排序（ES 默认不允许，且语义无意义）。
     */
    private static final Map<String, Set<String>> SORTABLE_FIELDS = Map.of(
            IndexType.PRODUCT, Set.of("id", "priceMin", "priceMax", "salesCount", "createdAt"),
            IndexType.MERCHANT, Set.of("id", "rating", "createdAt"),
            IndexType.ORDER, Set.of("id", "payAmount", "totalAmount", "createTime", "paidAt", "completedAt"),
            IndexType.REVIEW, Set.of("id", "rating", "createTime"),
            IndexType.USER, Set.of("id", "status", "createTime", "updateTime")
    );

    /** 各索引的默认排序（无显式排序时使用），保持与原 SQL/原实现一致的列表顺序 */
    private static final Map<String, String> DEFAULT_SORT_FIELD = Map.of(
            IndexType.PRODUCT, "salesCount",
            IndexType.MERCHANT, "rating",
            IndexType.ORDER, "createTime",
            IndexType.REVIEW, "createTime",
            IndexType.USER, "id"
    );

    /** 各索引的数值型（Integer/Long/Double/Boolean）字段，用于 term 时转换类型 */
    private static final Set<String> NUMERIC_FIELDS = Set.of(
            "categoryId", "salesCount", "isVerified", "isReviewed", "rating", "status"
    );

    /**
     * 统一列表查询入口
     *
     * @param request 查询请求（索引类型 + 分页 + 关键字 + 过滤 + 排序）
     * @return 命中记录与分页信息
     */
    public IndexListResult list(IndexListQuery request) {
        if (request == null || request.getIndexType() == null) {
            throw new IllegalArgumentException("indexType 不能为空");
        }
        String indexType = request.getIndexType();
        Class<?> documentClass = documentClassOf(indexType);

        // 1. 组装查询条件（must: 全文检索；filter: 精确过滤，不参与打分、可利用缓存）
        List<Query> must = new ArrayList<>();
        List<Query> filter = new ArrayList<>();
        addKeyword(must, request.getKeyword(), SEARCH_FIELDS.get(indexType));
        addFilters(filter, indexType, request.getFilters());

        Query query = Query.of(q -> q.bool(b -> {
            if (!must.isEmpty()) {
                b.must(must);
            }
            if (!filter.isEmpty()) {
                b.filter(filter);
            }
            return b;
        }));

        // 2. 组装排序（白名单校验）
        List<Sort.Order> orders = buildSort(indexType, request.getSortField(), request.getSortOrder());

        // 3. 执行分页查询
        int page = request.safePage();
        int size = request.safeSize();
        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(query)
                .withSort(Sort.by(orders));
        NativeQuery nativeQuery = builder.build();
        nativeQuery.setPageable(PageRequest.of(page - 1, size));

        SearchHits<?> hits = elasticsearchOperations.search(nativeQuery, documentClass);

        List<Object> records = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());

        long total = hits.getTotalHits();
        IndexListResult result = new IndexListResult();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size <= 0 ? 0 : (total + size - 1) / size);
        log.debug("索引列表查询完成: indexType={}, total={}, page={}, size={}, keyword={}",
                indexType, total, page, size, request.getKeyword());
        return result;
    }

    // ==================== 索引类型 → 文档类型 ====================

    private Class<?> documentClassOf(String indexType) {
        switch (indexType) {
            case IndexType.PRODUCT:
                return ProductDocument.class;
            case IndexType.MERCHANT:
                return MerchantDocument.class;
            case IndexType.ORDER:
                return OrderDocument.class;
            case IndexType.REVIEW:
                return ReviewDocument.class;
            case IndexType.USER:
                return UserDocument.class;
            default:
                throw new IllegalArgumentException("不支持的索引类型: " + indexType);
        }
    }

    // ==================== 条件组装 ====================

    /** 关键字 → multi_match（落到该索引声明的检索字段） */
    private void addKeyword(List<Query> must, String keyword, List<String> fields) {
        if (keyword == null || keyword.isBlank() || fields == null || fields.isEmpty()) {
            return;
        }
        must.add(Query.of(q -> q.multiMatch(m -> m
                .query(keyword.trim())
                .fields(fields))));
    }

    /**
     * 精确过滤 → terms（IN 集合）/ must_not terms（排除集合）/ term / 主键集合
     * <p>
     * 通用键：
     * - {@code statuses}：逗号分隔，状态 **IN** 集合（如订单历史 {@code completed,cancelled}）；
     * - {@code excludeStatuses}：逗号分隔，**排除**这些状态（原订单列表的
     *   {@code status NOT IN ('completed','cancelled')} 语义）；
     * - {@code ids}：逗号分隔主键，按 ID 集合过滤（用于按 ID 批量取数）。
     */
    private void addFilters(List<Query> filter, String indexType, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        // 1) 状态 IN 集合：terms
        String statuses = filters.get("statuses");
        if (statuses != null && !statuses.isBlank()) {
            List<String> values = splitCsv(statuses);
            if (!values.isEmpty()) {
                filter.add(termsQuery("status", values));
            }
        }

        // 2) 排除状态：must_not terms
        String excludeStatuses = filters.get("excludeStatuses");
        if (excludeStatuses != null && !excludeStatuses.isBlank()) {
            List<String> values = splitCsv(excludeStatuses);
            if (!values.isEmpty()) {
                Query inner = termsQuery("status", values);
                filter.add(Query.of(b -> b.bool(bb -> bb.mustNot(inner))));
            }
        }

        // 3) 主键集合：terms on _id（用于按 ID 集合取数）
        String ids = filters.get("ids");
        if (ids != null && !ids.isBlank()) {
            List<String> values = splitCsv(ids);
            if (!values.isEmpty()) {
                filter.add(termsQuery("_id", values));
            }
        }

        // 4) 其余键值：term 精确过滤
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()
                    || "statuses".equals(field) || "excludeStatuses".equals(field) || "ids".equals(field)) {
                continue;
            }
            if (!isAllowedFilterField(indexType, field)) {
                log.warn("忽略非法过滤字段: indexType={}, field={}", indexType, field);
                continue;
            }
            filter.add(buildTerm(field, value));
        }
    }

    /** 构造 terms（IN）查询，值统一按字符串 FieldValue 处理（Keyword 字段） */
    private Query termsQuery(String field, List<String> values) {
        return Query.of(q -> q.terms(t -> t
                .field(field)
                .terms(tv -> tv.value(values.stream()
                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                        .collect(Collectors.toList())))));
    }

    /** 构造 term 查询，按字段类型做值转换（数值字段不能以字符串比较） */
    private Query buildTerm(String field, String value) {
        if (NUMERIC_FIELDS.contains(field)) {
            try {
                long num = Long.parseLong(value.trim());
                return Query.of(q -> q.term(t -> t.field(field)
                        .value(co.elastic.clients.elasticsearch._types.FieldValue.of(num))));
            } catch (NumberFormatException e) {
                // 数值字段传了非数字（如 status 传 "completed" 之类误用），退化为字符串 term
                log.warn("数值字段收到非数字过滤值，按字符串处理: field={}, value={}", field, value);
            }
        }
        return Query.of(q -> q.term(t -> t.field(field)
                .value(co.elastic.clients.elasticsearch._types.FieldValue.of(value.trim()))));
    }

    /**
     * 过滤字段白名单（按索引类型）。
     * <p>
     * 限定范围的目的：既防止误用不存在的字段导致 ES 报错，也避免外部通过过滤构造
     * 非预期的查询（如对 text 字段做 term 得不到想要的结果）。
     */
    private boolean isAllowedFilterField(String indexType, String field) {
        // 通用过滤键（所有索引都支持）
        if ("statuses".equals(field) || "excludeStatuses".equals(field) || "ids".equals(field)) {
            return true;
        }
        Set<String> allowed;
        switch (indexType) {
            case IndexType.PRODUCT:
                allowed = Set.of("status", "merchantId", "categoryId", "id", "shopName");
                break;
            case IndexType.MERCHANT:
                allowed = Set.of("isVerified", "id");
                break;
            case IndexType.ORDER:
                allowed = Set.of("status", "customerId", "merchantId", "isReviewed", "orderNo", "id");
                break;
            case IndexType.REVIEW:
                allowed = Set.of("productId", "customerId", "merchantId", "rating", "orderId", "id");
                break;
            case IndexType.USER:
                allowed = Set.of("role", "status", "id");
                break;
            default:
                return false;
        }
        return allowed.contains(field);
    }

    // ==================== 排序 ====================

    /**
     * 组装排序：显式排序（白名单校验）→ 否则各索引默认排序
     * <p>
     * 统一追加 {@code id desc} 作为兜底排序键：当主排序字段取值相同（如评分相同、
     * 销量相同）时，避免 ES 在分页之间返回不稳定顺序导致「同一页出现重复/漏项」。
     */
    private List<Sort.Order> buildSort(String indexType, String sortField, String sortOrder) {
        Set<String> allowed = SORTABLE_FIELDS.getOrDefault(indexType, Collections.emptySet());
        String field = sortField;
        if (field == null || field.isBlank() || !allowed.contains(field)) {
            if (field != null && !field.isBlank()) {
                log.warn("忽略非法排序字段: indexType={}, field={}", indexType, field);
            }
            field = DEFAULT_SORT_FIELD.get(indexType);
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder)
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        List<Sort.Order> orders = new ArrayList<>(2);
        orders.add(new Sort.Order(direction, field));
        if (!"id".equals(field)) {
            orders.add(new Sort.Order(Sort.Direction.DESC, "id"));
        }
        return orders;
    }

    // ==================== 工具 ====================

    private static List<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 索引类型常量（与 {@link com.shopai.common.mq.IndexChangedEvent.EntityType} 取值保持一致） */
    public static final class IndexType {
        public static final String PRODUCT = "product";
        public static final String MERCHANT = "merchant";
        public static final String ORDER = "order";
        public static final String REVIEW = "review";
        public static final String USER = "user";

        private IndexType() {
        }
    }
}

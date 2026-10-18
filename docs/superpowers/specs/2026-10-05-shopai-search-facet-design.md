# ShopAi 商品分类分面搜索（复合搜索 + 桶聚合 + post_filter）设计文档

日期：2026-10-05
范围：`shopai-search` 服务（仅后端）

## 1. 背景与现状

- 商品搜索 `/api/search/product` 目前只做「复合查询 + 分页」，返回命中记录，**没有分类分面**。
- 商品 ES 文档 `ProductDocument` 已冗余 `categoryId` / `categoryName` / `parentCategoryName`。
- 分类数据（`categories` 表）由 `shopai-product` 的 `/api/category/{all,top,sub}` 提供，前端全量拉取后本地过滤，**无关键词搜索接口**。
- 前端商品搜索分类通过 `categoryId`（子分类）或 `categoryIds`（父分类展开的子分类集合）传参。

## 2. 目标（用户确认）

实现标准电商**分面检索（faceted search）**：
- **复合搜索**：关键词 + 价格 + 在售，用 bool（must + filter）组合。
- **桶聚合**：按 `categoryId` 返回各分类命中数（用于筛选侧栏）。
- **post_filter 后置过滤**：选中分类时只过滤命中结果，**不影响聚合计数** —— 这样选中某分类后，侧栏其他分类计数仍为全量分布，而非塌缩成 0。
- **仅做后端接口**，前端后续再接入。

## 3. 技术方案

### 3.1 为什么改用 NativeQuery

原实现用 Spring Data ES 的 `StringQuery`（原生 JSON DSL），只能承载 `query` 段。
聚合（aggs）与后置过滤（post_filter）超出其能力，故改用 **`NativeQuery` + Elasticsearch Java Client 类型化 DSL**。

- 依赖：`spring-boot-starter-data-elasticsearch`（含 `co.elastic.clients:elasticsearch-java`），无需新增 pom 依赖。
- 版本（Boot 3.2.4）：Spring Data ES 5.2.4，解析到的 ES Java Client 为 **8.10.4**。

### 3.2 一致查询 vs post_filter（关键语义）

| 段 | 内容 | 作用 |
|---|---|---|
| `query`（scope） | 关键词(multi_match name/shopName/description) + 价格(range) + 在售(term status) | 决定**聚合统计范围** |
| `aggs.categoryFacets` | terms on `categoryId`，size=100 | 各分类命中计数 |
| `post_filter` | 选中分类（categoryId 或 categoryIds，OR） | **只过滤命中**，不影响 aggs |

因此：`total` 与 `records` 反映「选中分类后」的真实结果；`categories` 始终反映「关键词+价格+在售」下的全量分布。

### 3.3 新增接口

`GET /api/search/product/facets`（公开，与 `/api/search/product` 同参）

参数：`page`、`size`、`keyword`、`categoryId`、`categoryIds`、`minPrice`、`maxPrice`

返回 `Result<FacetSearchResult<ProductDocument>>`：
```json
{
  "total": 123, "page": 1, "size": 10,
  "records": [ ...ProductDocument... ],
  "categories": [ { "categoryId": 5, "count": 42 }, { "categoryId": 8, "count": 17 } ]
}
```

> 分面桶仅返回 `categoryId + count`；**分类名由前端用 `/api/category/all` 的本地映射表补全**，不在 ES 里做子聚合（`categoryName` 是 keyword 类型，做 terms 聚合与 ID 聚合重复且更耗资源）。

### 3.4 保留不动的部分

- `GET /api/search/product`、`/api/search/product/merchant/{id}`、`/api/search/merchant` 行为与路径不变（仅内部由 StringQuery 改写为 NativeQuery，语义等价）。
- 搜索相关 DTO 之外，`SearchSyncService`、`ProductDocument`、MQ 同步、`POST /api/search/rebuild` 均不动。

### 3.5 新增文件

- `dto/CategoryFacet.java`：分类分面桶（categoryId + count）。
- `dto/FacetSearchResult.java`：分面搜索结果（total/page/size/records/categories）。

## 4. 边界（不做）

- 不做独立分类索引（分类名沿用前端本地映射）。
- 不做分类聚合子聚合（不返回 categoryName）。
- 不改前端。

## 5. 验证

沙箱 Java 11 无法编译（项目 Java 21 + Boot 3.2.4）。已通过反编译 ES Client 8.10.4 / Spring Data ES 5.2.4 的 class 文件，逐一核对所用 API 签名：
`NativeQuery.builder().withQuery/withSort/withAggregation/withFilter`、`BaseQuery.setPageable`、
`Query/BoolQuery/MultiMatchQuery/TermQuery/TermsQuery/RangeQuery` 构建器、
`Aggregation.of(...).terms(...)`、`Aggregate.lterms()`、`LongTermsAggregate.buckets().array()`、
`LongTermsBucket.key()`(long)/`docCount()`、`SearchHits.getAggregations()`、`ElasticsearchAggregations.get(name)`。

最终编译由用户本地执行。

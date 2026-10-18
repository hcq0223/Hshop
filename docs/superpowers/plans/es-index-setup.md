# ShopAi Elasticsearch 索引搭建说明

- 日期：2026-10-05
- 适用：`shopai-search` 服务（商品/商家搜索 + 商品分面搜索）
- ES 地址：http://localhost:9200

---

## 0. 一句话结论

索引 **`product` / `merchant` 不需要你手动建**——`shopai-search` 启动后首次同步（MQ 消费）或调用 `POST /api/search/rebuild` 时，Spring Data ES 会按 `@Document`/`@Field` 注解**自动建索引并写入 mapping**。

**但必须先装 IK 分词器插件**，否则字段里的 `ik_max_word`/`ik_smart` 解析不到，建索引会失败：

```
analyzer [ik_max_word] not found
```

> ES 没有"数据库"这层概念，只有"索引（index）"。你的两个索引就是 `product`、`merchant`。

---

## 1. 安装 IK 分词器（必做）

IK 插件版本**必须与 ES 版本完全一致**。以 ES 8.11.0 为例：

```bash
# 方式一：官方推荐（在线装，需 ES 容器能联网）
docker exec -it es bin/elasticsearch-plugin install \
  https://github.com/infinilabs/analysis-ik/releases/download/v8.11.0/elasticsearch-analysis-ik-8.11.0.zip

# 装完重启 ES
docker restart es
```

ES 7.17 则用对应 7.17.0 的 ik 包。装好后验证：

```bash
curl "http://localhost:9200/_cat/plugins?v"
# 应能看到 analysis-ik
```

> 注意：ES 8.x 默认开启安全认证。本地联调建议启动时加 `-e "xpack.security.enabled=false"`（见 infra-config.md），
> 使文档中的所有 http://localhost:9200 命令免认证可直连。

---

## 2. 建索引（二选一）

### 方式 A：不手动建，交给应用自动建（推荐）

1. 装好 IK 插件、启动 ES；
2. 启动 `shopai-search`；
3. 触发一次全量重建：`POST /api/search/rebuild`（管理员 token），或等 MQ 首次同步。
4. 校验：

```bash
curl "http://localhost:9200/_cat/indices?v"
# 应出现 product / merchant 两个索引
```

> 自动建索引依赖 `SimpleElasticsearchRepository` 的 `createWithMapping`，
> 在首次 `save()` 时按实体注解生成 mapping。若 mapping 有变，需先删索引重建。

### 方式 B：手动显式建索引（可控，便于核对 mapping）

先用 IK 就绪的 ES，执行以下两条 `PUT`。

**product 索引：**

```bash
curl -X PUT "http://localhost:9200/product" -H 'Content-Type: application/json' -d'
{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "id":                 { "type": "keyword" },
      "name":               { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "description":        { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "mainImage":          { "type": "keyword", "index": false },
      "categoryId":         { "type": "integer" },
      "categoryName":       { "type": "keyword" },
      "parentCategoryName": { "type": "keyword" },
      "merchantId":         { "type": "keyword" },
      "shopName":           { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "shopLogo":           { "type": "keyword", "index": false },
      "priceMin":           { "type": "double" },
      "priceMax":           { "type": "double" },
      "salesCount":         { "type": "integer" },
      "status":             { "type": "keyword" },
      "createdAt":          { "type": "date", "format": "yyyy-MM-dd HH:mm:ss" },
      "images":             { "type": "keyword", "index": false }
    }
  }
}'
```

**merchant 索引：**

```bash
curl -X PUT "http://localhost:9200/merchant" -H 'Content-Type: application/json' -d'
{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "id":              { "type": "keyword" },
      "shopName":        { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "logoUrl":         { "type": "keyword", "index": false },
      "rating":          { "type": "double" },
      "isVerified":      { "type": "integer" },
      "shopDescription": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "createdAt":       { "type": "date", "format": "yyyy-MM-dd HH:mm:ss" }
    }
  }
}'
```

> 手动建完记得再触发一次 `POST /api/search/rebuild` 灌数据。

---

## 3. 重建 / 排错

```bash
# 删除重建（mapping 变更时）
curl -X DELETE "http://localhost:9200/product"
curl -X DELETE "http://localhost:9200/merchant"
# 再走方式 A 或 B

# 看某索引的 mapping
curl "http://localhost:9200/product/_mapping?pretty"

# 看数据量
curl "http://localhost:9200/product/_count"
curl "http://localhost:9200/merchant/_count"
```

---

## 4. 分面搜索相关

商品分面搜索 `GET /api/search/product/facets` 依赖商品索引的 `categoryId`（integer）做 terms 聚合，
**无额外索引要求**；只要商品索引存在且字段类型正确即可。分类名由前端用 `/api/category/all` 本地映射补全，不在 ES 里做子聚合。

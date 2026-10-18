# ShopAi 列表查询 ES 化改造（Redis 列表缓存 → Elasticsearch + MQ 同步）

日期：2026-10-05
范围：`D:\ShopAi\JAVA`（`shopai-*` 各微服务模块）
目标：把「分页列表」的承载从 Redis 缓存改为 Elasticsearch 索引，用 MQ 做增量同步。
约束：**所有 Controller 对外路由与响应结构保持不变**（只换数据来源）。

---

## 1. 为什么要改（原设计的问题）

改造前，各服务的分页列表都是「Redis 缓存 + 回源查库」：

```java
String cacheKey = RedisService.CACHE_ORDER_LIST + RedisService.buildListHash(params);
return redisService.getOrSetTyped(cacheKey, typeRef, 120L, () -> orderMapper.selectByStatus(...));
```

两个结构性问题：

1. **失效粒度极粗**：缓存键由查询参数哈希而来（一个筛选组合一套键），
   而任何一次写操作都会按前缀全清。例如订单服务：
   ```java
   private void evictOrderCacheNow(...) {
       redisService.deleteByPrefix(RedisService.CACHE_ORDER_LIST);   // 清掉所有用户、所有筛选、所有页码
       ...
   }
   ```
   结果是：只要有人下单或改状态，**全站订单列表缓存全废**。
   商品（`CACHE_PRODUCT_LIST`）、商家（`CACHE_MERCHANT_PAGE`）、
   用户（`CACHE_USER_LIST`）、评价（`CACHE_REVIEW_*`）同理。

2. **缓存命中率低**：键 = hash(关键字, 角色, 状态, 页码, 每页条数…)，
   组合数随筛选项维度指数增长，缓存基本只在「不加筛选的首页」有效。

Elasticsearch 本身支持任意条件组合的过滤、全文检索、排序与深分页，
因此列表查询不需要「缓存 + 全量失效」这一层。

---

## 2. 改造后的架构

```
写侧：归属服务（增删改） ──发 MQ──▶ shopai-search ──Feign 回源──▶ 归属服务组装索引视图 ──▶ 写 ES
读侧：业务 Controller（路由不变） ──▶ 业务 Service ──Feign /api/search/internal/list──▶ ES 查询
```

关键设计取舍：

| 决策 | 选择 | 理由 |
|---|---|---|
| 列表由谁查 | **统一的搜索服务** | 五个索引一套查询逻辑；`IndexListQuery` 一个请求契约通吃，避免每加一个列表就新增一套类型 |
| 事件载荷 | **只带 `entityType + operation + id`** | 消息小、无版本耦合；消费侧回源拿到的一定是最新值，重复/乱序消费天然幂等 |
| 索引视图谁组装 | **数据归属方** | 只有归属方知道自己业务的关联关系（店铺名、分类名、评价人昵称），搜索服务不反向依赖业务逻辑 |
| 路由 | **业务服务保留原路由** | 满足「路由不能变」；业务 Service 内部把数据来源换成 ES 查询（多一跳 Feign，换掉缓存层） |
| 降级 | 搜索服务不可用 → 返回空列表 + 错误日志 | 避免搜索服务故障放大成下单/上架等主链路不可用 |

### 五个索引

| 索引 | 归属服务 | 承载的列表 | 替代的原缓存键 |
|---|---|---|---|
| `product` | shopai-product | 店铺商品列表、热销榜 | `cache:product:list:{hash}`、`cache:product:hot:{n}` |
| `merchant` | shopai-merchant | 商家分页列表 | `cache:merchant:page:{hash}` |
| `order` | shopai-order | 顾客/商家/管理员订单列表、历史订单 | `cache:order:list:{hash}` |
| `review` | shopai-merchant | 商品评价列表、我的评价、后台评价 | `cache:review:product:{id}`、`cache:review:customer:{id}` |
| `user` | shopai-user | 后台用户列表 | `cache:user:list:{hash}` |

> 购物车 / 地址这类「逐用户、强实时、只读自己」的列表**未迁移**：
> 放 ES 反而更慢且有一致性风险。

---

## 3. MQ 契约

公共库新增 `com.shopai.common.mq.IndexChangedEvent`（统一载荷）与
`com.shopai.common.mq.IndexEventPublisher`（统一投递，含路由键映射、msgId、异常兜底）。

路由键（`MqConstants`）：

| 实体 | upsert | delete |
|---|---|---|
| 商品 | `product.changed` | `product.changed.delete` |
| 商家 | `merchant.changed` | `merchant.changed.delete` |
| 订单 | `order.changed` | `order.changed.delete` |
| 评价 | `review.changed` | `review.changed.delete` |
| 用户 | `user.changed` | `user.changed.delete` |

队列（搜索服务消费端声明）与绑定：

| 队列 | 绑定 |
|---|---|
| `shopai.search.product.changed.queue` | `product.changed.#` |
| `shopai.search.merchant.changed.queue` | `merchant.changed.#` |
| `shopai.search.order.changed.queue` | `order.changed.#` |
| `shopai.search.review.changed.queue` | `review.changed.#` |
| `shopai.search.user.changed.queue` | `user.changed.#` |

用 topic 通配 `.<base>.#` 绑定，使 upsert 与 delete 进同一队列，由消费者按
`operation` 分流；后续新增子类型无需改绑定。

**顺带修掉的一个隐患**：原队列定义带 `.ttl(10000)`，消费积压（全量重建期间、ES 抖动）
会把未消费的索引同步消息直接判为过期丢进死信，导致索引静默缺数据。索引同步消息很小，
不应设过期时间，已移除。

---

## 4. 服务间接口（新增）

### 4.1 搜索服务提供

| 路径 | 说明 |
|---|---|
| `POST /api/search/internal/list` | **统一列表查询**（五类索引共用） |
| `POST /api/search/internal/upsert/order` `review` `user` | 业务服务回源写索引 |
| `DELETE /api/search/internal/order/{id}` `review/{id}` `user/{id}` | 从索引删除 |
| `POST /api/search/internal/rebuild/order` `review` `user` | 全量重建这三类索引 |
| `GET /api/search/internal/stats` | 五个索引文档数（排查「列表为空」） |

### 4.2 业务服务提供（供搜索服务回源）

| 路径 | 说明 |
|---|---|
| `GET /api/order/internal/index/order/{orderId}` | 订单索引视图（含店铺名+客户昵称+订单项摘要） |
| `GET /api/order/internal/index/orders` | 订单索引视图全量 |
| `GET /api/merchant/internal/index/review/{reviewId}` | 评价索引视图（含昵称/头像+商品名+店铺名） |
| `GET /api/merchant/internal/index/reviews` | 评价索引视图全量 |
| `GET /api/user/internal/index/user/{userId}` | 用户索引视图（含客户昵称+店铺名） |
| `GET /api/user/internal/index/users?page=&size=` | 用户索引视图分页全量 |

网关已在业务路由**之前**新增拦截：`/api/search/internal/**` → `uri: no://op`
（此前已有 user/customer/merchant/product/order 五条），确保内部接口不被
`/api/search/**` 等通配路由暴露出去。

---

## 5. 各服务改动明细

### 5.1 shopai-search（枢纽）

- **新增** `OrderDocument` / `ReviewDocument` / `UserDocument` + 三个 Repository。
- **新增** `IndexListQueryService`：统一列表查询。按 `indexType` 分发到文档类型，
  按索引类型解释 `filters`（term / terms / must_not terms），
  **排序字段白名单**校验，统一追加 `id desc` 兜底排序（避免同值分页不稳定导致重复/漏项）。
- **新增** `ListIndexSyncService`：order/review/user 三类索引的增量同步与全量重建。
- **新增** `SearchInternalController`：统一查询 + 回源写索引 + 重建 + stats。
- **新增** Feign 回源客户端：`OrderSourceClient` / `ReviewSourceClient` / `UserSourceClient`。
- **重写** `RabbitMqConfig`：五队列 + topic 通配绑定；移除可疑的队列 TTL。
- **重写** 消费者（`IndexEventConsumer`）：五个 `@RabbitListener` 统一按 `IndexChangedEvent` 分发。
  （原 `SearchEventConsumer` 与 `ProductChangedEvent`/`MerchantChangedEvent` 已删除）
- `SearchController.rebuild`：从「重建商品+商家」扩展为**重建全部五个索引**，
  这是 MQ 丢消息后恢复列表可用的唯一手段。

### 5.2 shopai-product

- **新增** `feign/SearchIndexClient`、`dto/ProductIndexView`、`service/ProductIndexResultMapper`。
- `ProductServiceImpl`：
  - `getHotProducts`：由「Redis 缓存 `cache:product:hot:{n}`」改为查 ES
    （`status=on_sale` + `salesCount desc`）；
  - `listByMerchant`：由「直查库、全量返回」改为查 ES，**并补上了分页**
    （原实现无分页，店铺商品多时一次性全量返回，是潜在性能问题）；
  - `incrementSalesCount`：销量是热销榜排序字段，改后发 MQ 同步索引（不再清列表缓存）；
  - `evictProductCacheAfterCommit`：移除两条 `deleteByPrefix(CACHE_PRODUCT_LIST)`（立即 + 延迟）。
- `ProductMapper.xml`：新增 `selectAllForIndex` / `selectByIdsForIndex`（索引重建用，已在上一轮改造加入）。

### 5.3 shopai-merchant

- **新增** `feign/SearchIndexClient`、`dto/MerchantIndexView`、`dto/ReviewIndexView`、
  `service/MerchantListIndexService`（商家/评价列表查询）、`service/MerchantIndexAssembler`（评价索引视图组装）。
- `MerchantServiceImpl.queryMerchantPage`：由 Redis 分页缓存改为查 ES（`rating desc`）。
- `ReviewServiceImpl`：商品评价列表、我的评价、后台评价列表改为查 ES；
  **把索引中平铺的 `customerNickname/customerAvatar` 装配回嵌套 `customer` 对象**，
  保证前端 `r.customer.nickname` 读取不变。
- 移除所有 `deleteByPrefix(CACHE_MERCHANT_PAGE)`；评价列表缓存清理改为只清
  `:stats`（评分统计是聚合统计，仍由本服务本地 `COUNT/GROUP BY` 提供并缓存）。
- 评价新增/删除发 `review.changed`；商家入驻/审核/改资料/评分变化发 `merchant.changed`。

### 5.4 shopai-order

- **新增** `feign/SearchIndexClient`、`service/OrderIndexAssembler`（索引视图组装）、
  `service/OrderListIndexService`（列表查询）、`service/OrderIndexResultMapper`。
- `OrderServiceImpl`：五个列表方法（顾客/商家/管理员/历史/按状态）全部改为查 ES；
  **15 处订单状态流转**统一挂上 `publishOrderIndexAfterCommit(orderId)`
  （事务提交后才投递，避免搜索服务回源读到未提交的旧值）。
- `evictOrderCacheNow`：移除两条 `deleteByPrefix(CACHE_ORDER_LIST)`，
  只保留详情/统计/趋势/购物车/店铺维度缓存清理。
- `OrderMapper` / `OrderItemMapper` 新增 `selectAllForIndex`、`selectByOrderIds`（批量取项，避免 N+1）。
- 订单索引视图**冗余**了 `shopName/shopLogo`（原 JOIN merchants）与
  `customerNickname`（原 JOIN customers），以及 `itemCount/itemSummary/itemImage`
  （原要查出整个 `orderItems` 集合再在内存统计件数与商品名）。

### 5.5 shopai-user

- **新增** `feign/SearchIndexClient`、`dto/UserIndexView`、
  `service/UserListIndexService`（列表查询）、`service/UserIndexAssembler`（索引视图组装）。
- `UserServiceImpl.listUsers`：由「Redis 缓存 + 跨库 `LEFT JOIN customers/merchants`」改为查 ES。
  索引中冗余 `customerNickname`（本地 customers 表）与 `shopName`（Feign 调商家服务），
  于是**在没有跨库 JOIN 的前提下依然支持「按昵称/店铺名搜用户」**。
- `evictUserCacheAfterCommit`：移除 `deleteByPrefix(CACHE_USER_LIST)`，改为发 MQ；
  销号走 `publishDelete`（而非 upsert）。
- `UserMapper` / `CustomerMapper` 新增 `selectPageForIndex`、`selectByUserIds`。
- **安全**：`UserDocument` 与 `UserIndexItem` 均**不含** `passwordHash`，
  索引只服务列表筛选/检索，不携带任何凭据字段。

### 5.6 shopai-common

- **新增** `mq/IndexChangedEvent`（含 `EntityType` / `Operation` 常量）、`mq/IndexEventPublisher`。
- **新增** `constant/IndexTypes`（五个索引类型名，供 `indexType` / `entityType` 共用）。
- **新增** DTO：`dto/internal/index/{OrderIndexItem, ReviewIndexItem, UserIndexItem}`、
  `dto/internal/query/{IndexListQuery, IndexListResult}`。
- `InternalApiPaths` 新增 `SEARCH_INTERNAL`。
- `RedisService.clearProductCache / clearOrderCache / clearOrderListCache`：
  移除列表缓存清理（仅保留点缓存与统计缓存），并在注释里说明原因。

---

## 6. 部署与启用步骤（重要）

索引是**新的空索引**，上线后必须做一次全量重建，否则列表页会返回空数据。

1. **先启动 MQ 与 ES**，再启动搜索服务（消费端负责声明队列/绑定，避免先启动生产者导致消息丢失）。
2. 启动全部服务后，用管理员账号触发一次全量重建。

### 6.1 正确的调用方式（必须经网关）

**鉴权头是网关注入的，所以重建接口要走网关 `8080`，不要直连 `8085`。**

业务服务的鉴权模型是「不自行验签 JWT，只读网关注入的 `X-User-Id / X-User-Role / X-User-Phone` 头」
（见 `CurrentUser` 与 `JwtAuthGlobalFilter`）。直连业务服务端口时这些头不存在，
即使 token 完全正确也会被判定为无权限。

```bash
# 1) 登录拿 token（注意：JwtUtil 生成的 token 自带 "Bearer " 前缀）
curl -X POST "http://localhost:8080/api/auth/NameLogin" \
  -H "Content-Type: application/json" \
  -d '{"username":"Hcqhcq","password":"hcq159357"}'

# 2) 经网关重建全部五个索引（网关会解析 JWT 并注入 X-User-Role: admin）
curl -X POST "http://localhost:8080/api/search/rebuild" \
  -H "Authorization: Bearer <上一步返回的 token>"
```

返回各索引写入条数：`productCount / merchantCount / orderCount / reviewCount / userCount`。

> **常见误判**：直接在 `8085` 上调 `/api/search/rebuild` 会返回
> `403 无权操作`，这不是 token 或账号问题，而是绕过了网关注入身份头的环节。

### 6.2 直连排障（已提供兜底）

为方便运维排障，`rebuild` 已支持「直连时自行验签」：
若 `X-User-Role` 头缺失（说明是直连），则从 `Authorization` 头解析 JWT 判断角色，
因此下面这种直连调用现在也能成功：

```bash
curl -X POST "http://localhost:8085/api/search/rebuild" \
  -H "Authorization: Bearer <token>"
```

注意这**只是排障便利**，不是推荐链路：常规业务请求仍应经网关，
由网关统一完成验签、黑名单校验与身份头注入。

### 6.3 校验索引就绪

```bash
# 服务间直连（经网关会 503 拦截，因为 /api/search/internal/** 是内部接口）
curl "http://localhost:8085/api/search/internal/stats"
```

排查「列表为空」时先看这里：若某个索引文档数为 0，说明该实体还没重建或 MQ 未投递。

4. 全量重建也可在服务端直接调用 `ListIndexSyncService.rebuildAll()`
   （订单/评价/用户）与 `SearchSyncService.rebuildAll()`（商品/商家）。

---

## 7. 验证情况

- 全部 9 个模块 `mvn clean compile` **通过**（Java 21 / Spring Boot 3.2.4）。
- （上一轮同类问题）`mvn package` 的 `repackage` 阶段在当前沙箱因
  `commons-collections4:4.4` 无法从中央仓库下载而失败，与本次改动无关；编译阶段无错误。

---

## 7.1 排障记录：网关所有请求返回 503

**现象**：经网关 `8080` 调用任何接口（包括 `/api/category/all` 这类公开接口）都返回

```json
{"timestamp":"...","path":"/api/category/all","status":503,
 "error":"Service Unavailable","requestId":"..."}
```

**排查过程**（这个顺序值得复用，能快速排除误判）：

| 步骤 | 命令 / 方法 | 结论 |
|---|---|---|
| 1 | 看错误体形状 | `timestamp/path/status/error/requestId` 是**网关注册的 Spring Boot 默认错误页**，说明请求没到业务服务就被网关拒了 → 排除「业务服务自身异常」 |
| 2 | Nacos 服务列表 | `GET http://127.0.0.1:8848/nacos/v1/ns/service/list` → 7 个服务全部注册 → 排除「服务没注册」 |
| 3 | Nacos 实例健康 | `GET .../nacos/v1/ns/instance/list?serviceName=shopai-product` → `healthy=True`，地址 `192.168.88.1:8082` → 排除「实例不健康」 |
| 4 | **直连后端** | `curl http://127.0.0.1:8082/api/category/all` → **200**；`curl http://192.168.88.1:8082/...` → **200** → 排除「后端不可达 / 网络问题」 |
| 5 | 经网关 | `curl http://127.0.0.1:8080/api/category/all` → **503** → 问题锁定在**网关自身** |

**根因**：`shopai-gateway/pom.xml` 缺少 `spring-cloud-starter-loadbalancer`。

网关路由的 uri 是 `lb://shopai-product` 这种「服务名」形式，
需要 `ReactiveLoadBalancerClientFilter`（由 `spring-cloud-starter-loadbalancer` 提供）
把服务名解析成具体实例地址。

关键坑在于：`spring-cloud-gateway-server:4.1.2` 的 pom **确实列了** loadbalancer，
但标的是 `optional=true`：

```xml
<artifactId>spring-cloud-loadbalancer</artifactId>
<scope>compile</scope>
<optional>true</optional>   <!-- optional 依赖不会传递给使用者 -->
```

所以业务服务（POM 里显式加了 loadbalancer）正常，唯独网关没加就没法解析 `lb://`，
表现为「服务注册得好好的、后端也通，但过网关一律 503」。
Spring Cloud Gateway 官方文档同样要求：使用 `lb://` 时自行引入该 starter。

**修复**：在 `shopai-gateway/pom.xml` 显式声明

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

**结论：503 与 token 无关。** `/api/search/**`、`/api/category/**` 等在网关白名单里是
直接放行的，不带 token 也能过；公开接口 503 一定是路由/服务发现问题。

**修复后必须重启网关**（依赖变更需要重新构建并重启进程），然后复验：

```bash
curl "http://localhost:8080/api/category/all"     # 期望 200
```

---

## 8. 遗留与后续建议

1. **客户列表（后台 / 客户资料）仍走 Redis**（`cache:customer:list:{hash}`）：
   不在本次确认的迁移范围内，如需一并 ES 化可按同样模式扩展。
2. **评价的评分统计**（平均分 + 星级分布）仍是「本地 `COUNT/GROUP BY` + Redis 缓存 `:stats`」：
   它是聚合统计而非列表，数据量小、规则稳定，留在数据库侧更合适；
   若以后要按店铺/时间维度做复杂聚合，可考虑迁到 ES 的 aggregation。
3. **订单列表索引的实时性依赖 MQ**：消息投递失败时该订单在列表中会短暂显示旧状态，
   直到下一次变更或全量重建。若要更强保证，可对订单索引加一条「按需回源」的兜底
   （列表命中后校验状态版本）。
4. **深分页**：ES 默认 `from + size ≤ 10000`。当前各列表 `size` 上限 200，
   若前端出现「翻到很后面」的场景，需要改用 `search_after`。
5. **索引别名/滚动重建**：当前全量重建是「删旧 → 全量写入」，重建期间列表会短暂为空。
   生产环境建议改为写新索引 + 原子切换 alias。

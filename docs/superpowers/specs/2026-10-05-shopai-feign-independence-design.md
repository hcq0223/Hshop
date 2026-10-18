# ShopAi 微服务「独立库」改造说明（Feign 化）

日期：2026-10-05
范围：`D:\ShopAi\JAVA`（`shopai-*` 各微服务模块）
目标：把「共享单库」阶段遗留的跨库直查/跨库 JOIN，全部改为按「独立库」语义的服务间 Feign 调用。
约束：**所有 Controller 对外路由保持不变**（只改数据来源，不改路径）。

---

## 1. 改造原则

原先各服务虽然已拆成独立模块，但仍连同一个 MySQL（`hcq_shop`），于是大量出现：

- `*RefMapper`：用别人库的表建自己的 Mapper（如订单服务查 `merchants`、`customers`）；
- 跨库 `JOIN`：在本服务的 XML 里直接 `JOIN` 别人库的表（如 `orders JOIN merchants`）；
- 跨库读写：资金、账号状态等直接读改写别的服务的表。

本次把上述三类都当作「表在别的库、只能通过接口访问」处理，规则如下：

| 原做法 | 现做法 |
|---|---|
| 本服务 XML 里 `JOIN` 他人表 | XML 只查本服务表；关联字段由 Service 层调 Feign 后置补齐 |
| `*RefMapper` 直查他人表 | 删除 Mapper，改为 Feign 客户端 |
| 跨库 `selectById + updateById`（读改写） | 改为对方服务的**原子增量接口**，在对方本地事务内完成 |
| 关键字搜索跨库联查（`LIKE` 他人表） | 由表归属方返回命中的 ID 集合，本服务只在本地表做 `IN` 过滤 |

配套约定：

- 服务间接口统一挂在 `/api/<域>/internal/**`，路径常量集中在
  `shopai-common` 的 `com.shopai.common.constant.InternalApiPaths`，避免两端各写一份字符串而漂移。
- 跨服务传参/返回统一用 `shopai-common` 的 `com.shopai.common.dto.internal.*` DTO，只含叶子字段。
- 网关**拦截**所有 `/api/**/internal/**`（`uri: no://op`，命中即 503），防止内部接口对外暴露。

---

## 2. 服务间接口契约（新增）

### 2.1 shopai-user 提供

| 路径 | 说明 | 原被谁直查 |
|---|---|---|
| `GET /api/user/internal/{userId}` | 查用户信息 | 商家服务 `UserRefMapper.selectByUserId`、`MerchantMapper.xml JOIN users` |
| `GET /api/user/internal/batch?ids=` | 批量查用户 | 商家索引重建 `JOIN users` |
| `POST /api/user/internal/{userId}/activate` | 激活账号（status 0→1） | 商家服务 `UserRefMapper.updateStatusToActive` |
| `POST /api/user/internal/{userId}/account` | 改账号信息 | 商家服务 `UserRefMapper` 读改写 |
| `GET /api/customer/internal/ids?userIds=` | userId → customerId | 订单/商家服务 `CustomerRefMapper.selectIdByUserId` |
| `GET /api/customer/internal/batch?ids=` | 批量查客户（昵称/头像） | 商家评价 `JOIN customers` |
| `GET /api/customer/internal/user-ids-by-nickname` | 按昵称查命中 userId | （用户服务自用，替代自身 `JOIN customers`） |

### 2.2 shopai-merchant 提供

| 路径 | 说明 | 原被谁直查 |
|---|---|---|
| `GET /api/merchant/internal/by-user?userId=` | 按 userId 查商家 | 订单 / 商品 / 用户服务 `MerchantRefMapper` |
| `GET /api/merchant/internal/{id}` | 按商家ID查商家 | — |
| `GET /api/merchant/internal/briefs?ids=` | 批量店铺名/Logo/评分/认证 | 订单、商品、用户、搜索的 `JOIN merchants` |
| `GET /api/merchant/internal/user-ids-by-shop-name` | 按店铺名查命中 userId | 用户服务 `JOIN merchants` |
| `GET /api/merchant/internal/funds?merchantId=` | 查询资金（只读） | 订单服务资金校验 |
| `POST /api/merchant/internal/funds/change` | **原子增量**变更资金 | 订单服务 8 处资金读改写 |
| `GET /api/merchant/internal/index/merchants` | 商家全量索引源数据 | 搜索 `MerchantRefMapper.selectAllForIndex` |
| `GET /api/merchant/internal/index/merchant?ids=` | 商家增量索引源数据 | 搜索 `selectOneForIndex` |

### 2.3 shopai-product 提供

| 路径 | 说明 | 原被谁直查 |
|---|---|---|
| `GET /api/product/internal/product-count` | 商品总数 | 用户仪表盘 `DashboardMapper.countProducts` |
| `GET /api/product/internal/product/{id}` | 商品信息 | 订单 `ProductRefMapper.selectProductById` |
| `GET /api/product/internal/products?ids=` | 批量商品信息 | 订单购物车结算 |
| `GET /api/product/internal/product-briefs?ids=` | 商品简要（名称/主图） | 商家评价 `JOIN products` |
| `GET /api/product/internal/sku/{id}` | SKU 信息（不含库存） | 订单 `ProductSkuRefMapper.selectSkuById` |
| `GET /api/product/internal/sku/by-product/{productId}` | 某商品全部 SKU | — |
| `POST /api/product/internal/sales` | 累加销量 | 订单直调 `ProductMapper.incrementSalesCount` |
| `GET /api/product/internal/index/products` | 商品全量索引源数据 | 搜索 `ProductRefMapper.selectAllForIndex` |
| `GET /api/product/internal/index/product?ids=` | 商品增量索引源数据 | 搜索 `selectOneForIndex` |

### 2.4 shopai-order 提供

| 路径 | 说明 | 原被谁直查 |
|---|---|---|
| `GET /api/order/internal/{orderId}` | 查订单 | 商家服务 `OrderRefMapper.selectById` |
| `POST /api/order/internal/{orderId}/reviewed` | 标记已评价 | 商家服务 `OrderRefMapper.updateById` |
| `GET /api/order/internal/count` | 区间内完成订单数 | 商家服务 `OrderRefMapper.countCompletedOrders` |
| `GET /api/order/internal/dashboard/order-count` | 订单总数 | 用户仪表盘 |
| `GET /api/order/internal/dashboard/daily?days=` | 近N天每日订单 | 用户仪表盘 |
| `GET /api/order/internal/dashboard/status-distribution` | 订单状态分布 | 用户仪表盘 |
| `GET /api/order/internal/dashboard/merchant-sales?limit=&order=` | 商家销售额排名 | 用户仪表盘 |

---

## 3. 各服务改动明细

### 3.1 shopai-user

- **删除**：`mapper/MerchantRefMapper.java`(+xml)、`mapper/DashboardMapper.java`(+xml)。
- **新增**：`controller/UserInternalController`、`controller/CustomerInternalController`、
  `feign/MerchantInternalClient`、`feign/OrderInternalClient`、`feign/ProductInternalClient`。
- `UserMapper.xml`：去掉 `LEFT JOIN customers / merchants`，改为传入
  `customerUserIds / merchantUserIds` 两个集合做本地 `IN` 过滤；
  `UserMapper.selectByConditions/countByConditions` 同步增加两个参数。
- `UserServiceImpl`：
  - 登录状态判断 → `MerchantInternalClient.getByUserId`；
  - 仪表盘 → 商品/订单/商家三个 Feign 客户端；商家销售额排名的店铺名改为
    「订单服务聚合 merchantId+totalSales」+「商家服务批量补 shopName」两步组装；
  - 用户列表关键字搜索 → 先取客户昵称命中集合（本服务 `CustomerMapper`）与
    商家店铺名命中集合（Feign），再查 `users`。
- `UserService` / `UserServiceImpl` 类注释同步更新。

### 3.2 shopai-product

- **删除**：`mapper/MerchantRefMapper.java`(+xml)、`controller/InternalSalesController`（合并进新控制器）。
- **新增**：`controller/ProductInternalController`、`feign/MerchantInternalClient`、
  `service/ProductIndexService`（搜索索引源数据组装）。
- `ProductMapper.xml`：去掉 `JOIN merchants`（`selectDetailById`、`selectHotOnSale`），
  新增 `selectAllForIndex`、`selectByIdsForIndex`、`countAll`。
- `ProductServiceImpl`：
  - `getDetail` / `getHotProducts` 查询后调商家服务补齐 `merchant / shopName / shopLogo`；
  - 热销榜按 merchantId 去重后**一次批量** Feign，避免 N+1。
- `ProductController.addProduct`：`userId → merchantId` 改为 `MerchantInternalClient.getByUserId`。

### 3.3 shopai-merchant

- **删除**：`mapper/UserRefMapper`、`mapper/OrderRefMapper`、`mapper/CustomerRefMapper`（含 xml），
  `entity/OrderRef`。
- **新增**：`controller/MerchantInternalController`、
  `feign/UserInternalClient`、`feign/CustomerInternalClient`、`feign/OrderInternalClient`、
  `feign/ProductInternalClient`、`service/CustomerResolver`、`service/MerchantIndexService`。
- `MerchantMapper.xml`：去掉 `JOIN users`（`selectPublicInfoById`、`selectDetailByUserId`）
  与 `JOIN products`（`selectMerchantPage` 的商品数统计）。
- `ReviewMapper.xml`：去掉 `JOIN customers / products / merchants`，只查 `reviews`。
- `MerchantServiceImpl`：
  - 入驻激活账号 → Feign `activateUser`；
  - `getMerchantById` / `selectDetailByUserId` → 本地查商户 + Feign 补 `Merchant.user`；
  - `getMerchantPublicInfo` → 本地查商户 + Feign 校验账号状态（等价原 `u.status = 1` 过滤）；
  - `updateAccount` → Feign 调用户服务完成实际更新并回写结果；
  - `countWeeklyCompletedOrders` → Feign 调订单服务计数接口。
- `ReviewServiceImpl`：评价列表/详情的客户、商品名、商家名改为
  `enrichReviews()` 批量补齐；`addReview` 的订单校验与 `is_reviewed` 标记改为 Feign。
- `ReviewController`：`customerId` 映射改用 `CustomerResolver`。

### 3.4 shopai-order

- **删除**：`mapper/CustomerRefMapper`、`MerchantRefMapper`、`ProductRefMapper`、
  `ProductSkuRefMapper`（含 4 个 xml）。
- **新增**：`controller/OrderInternalController`、`feign/ProductInternalClient`、
  `feign/MerchantInternalClient`、`feign/CustomerInternalClient`、
  `service/OrderEnrichService`、`service/CustomerResolver`。
- `OrderMapper.xml`：去掉 `JOIN merchants / products / customers`
  （`selectByCustomerId`、`selectByMerchantId`、`selectByOrderNo`、`selectByStatus`、
  `selectAllOrders`、`selectDetailById`、`selectHistoryOrders`）；
  新增仪表盘聚合、`countCompletedOrders`、`selectIsReviewed`、`markReviewed`、
  `merchantSalesRanking`（不再 JOIN 取店铺名）。
- `ShoppingCartMapper.xml`：去掉 `JOIN product_skus / products`，只查 `shopping_cart`。
- `ShoppingCartServiceImpl`：新增 `enrichCartInfo()`，按 skuId 取 SKU、按 productId 去重批量取商品。
- `OrderServiceImpl`：
  - 下单查 SKU/商品 → `getSkuViaFeign` / `getProductViaFeign`；
  - 资金 8 处 → `changeMerchantFunds(MerchantFundsChangeRequest...)` 原子增量；
  - 查询方法（详情/列表/历史/按状态/按单号）统一走 `OrderEnrichService` 补店铺信息。
- `OrderController` / `CartController` / `AddressController`：ID 解析改用
  `CustomerResolver` 与 `OrderEnrichService.resolveMerchantIdByUserId`。

### 3.5 shopai-search

- **删除**：整个 `com.shopai.search.ref` 包（`ProductRef`、`MerchantRef`、两个 RefMapper）
  与 `resources/mapper/` 目录；`SearchApplication` 移除 `@MapperScan`，改为 `@EnableFeignClients`。
- **新增**：`feign/ProductInternalClient`、`feign/MerchantInternalClient`。
- `SearchSyncService`：所有源数据改由 Feign 拉取（全量 + 增量），
  `rebuildAll()` 不再直查数据库；文档转换改为 `ProductIndexItem / MerchantIndexItem → ES Document`。
- `application.yml`：**移除 datasource 与 mybatis-plus 配置**（本服务不再连库），
  新增 Feign 超时配置（全量重建读超时放宽到 30s）。

### 3.6 shopai-common

- **新增** `constant/InternalApiPaths`：服务名与内部接口路径常量。
- **新增** `dto/internal/**`：`UserInfo`、`CustomerIdPair`、`CustomerInfo`、`MerchantInfo`、
  `MerchantBrief`、`ProductInfo`、`ProductSkuInfo`、`ProductBrief`、`OrderInfo`、
  `MerchantFunds`、`MerchantFundsChangeRequest`、`ProductIndexItem`、`MerchantIndexItem`、
  `UserIdItem`、`order/{DailyOrderStat, OrderStatusCount, MerchantSalesStat}`。

### 3.7 shopai-gateway

- `application.yml` 路由表**最前面**新增 5 条内部接口拦截路由
  （`/api/user|customer|merchant|product|order/internal/**` → `uri: no://op`）。
  必须置于业务通配路由之前，否则会被 `/api/merchant/**` 等转发出去。
- 业务路由（含前端依赖的路径）**未做任何改动**。

### 3.8 POM 依赖

`shopai-user`、`shopai-merchant`、`shopai-search` 三个模块新增
`spring-cloud-starter-openfeign` 与 `spring-cloud-starter-loadbalancer`
（`shopai-product`、`shopai-order` 原本已有）。

---

## 4. 需要留意的一处行为变化

`shopai-merchant` 的商户分页列表 `selectMerchantPage` 原返回
`product_count`（该商户商品数），数据来自跨库 `JOIN products`。
本次按「独立库」要求去掉了该 JOIN，**该字段不再返回**。
前端 `Vue/src` 全局检索未发现使用 `productCount`，故未影响现有页面；
若后续需要展示，请调用 `shopai-product` 的内部计数接口按商家维度统计。

---

## 5. 验证情况

- 全部 9 个模块 `mvn clean compile` **通过**（Java 21 / Spring Boot 3.2.4）。
- `mvn package` 的 `repackage` 阶段在当前沙箱内因
  `org.apache.commons:commons-collections4:4.4` 无法从 Maven 中央仓库下载而失败
  （`.lastUpdated` 标记被缓存，与本次改造无关）；**编译阶段无错误**。
  本地联网后执行一次 `mvn -U -DskipTests clean package` 即可正常打包。

---

## 6. 后续仍未完成（不属于本次范围）

1. **数据库尚未真正拆分**：各服务 `application.yml` 仍指向同一个 `hcq_shop`。
   本次只把「代码层的跨库访问」改成了 Feign，真正拆库时只需改各自 datasource
   的库名/账号，业务代码无需再动。
2. **Seata 未启用**：`shopai-order` / `shopai-inventory` 的 `seata` 配置仍被注释，
   下单链路的 `@GlobalTransactional` 目前退化为本地事务 +
   Feign 调用（资金、库存、销量跨服务的一致性最终依赖 Seata，建议按设计文档第 8 节补齐）。
3. **商家销售额排名的两次调用**（订单聚合 + 商家补名）之间不是强一致快照；
   若要求严格一致，可在订单服务侧落一份商家名冗余。

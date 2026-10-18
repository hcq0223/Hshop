# ShopAi 单体 → 微服务 拆分设计文档

- 日期：2026-10-04（2026-10-05 更新：版本矩阵降级到 Spring Boot 3 稳定线）
- 状态：已评审（待实现计划）
- 原项目：Spring Boot 4.0.6 + Java 21 + MyBatis-Plus + Redis + JWT + Spring Security（单体，`D:\ShopAi\JAVA`）
- 前端：Vue 3 + Vite + axios（`D:\ShopAi\Vue`）
- **当前版本矩阵（已降级）**：Spring Boot 3.2.4 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0（Nacos 2.3.2 / Seata 2.0.0）。
  降级原因：IDEA 2023.3.4 的运行器不兼容 Boot 4 带的 JUnit Platform 6。选 2023.0.1.0 是为保留 Seata 2.0.0 的 `io.seata` 包（`@GlobalTransactional` 仍可用，无需改代码）。

---

## 1. 目标与背景

将现有 ShopAi 服装商城单体后端拆分为微服务，同时引入 Elasticsearch 搜索、独立库存服务，并同步改造 Vue 前端以适配新路径。核心约束：

1. **保留所有中文注释**，拆分过程中不删注释，新代码补充中文注释。
2. **先共享单库，后拆独立库**：本期所有服务仍连同一个 MySQL（`hcq_shop`），仅 `inventory` 新建独立表为后续拆库铺路。
3. **前端同步改造**：允许路径规范化调整，前端需跟随修改。

## 2. 关键技术决策（已与用户确认）

| 决策点 | 选择 |
|---|---|
| 代码组织 | 方案A：单仓库多模块 Maven 工程 |
| 服务粒度 | 7 个服务 + 1 个公共库 = 8 个模块 |
| 数据库 | 共享单库起步，`inventory` 独立表为拆库铺路 |
| 微服务栈 | Nacos（注册+配置）+ OpenFeign（服务间调用）+ Spring Cloud Gateway（网关） |
| JWT 认证 | 网关统一认证，下游读 `X-User-*` 头 |
| 公共代码 | 独立 `shopai-common` Maven 模块 |
| 搜索 | Elasticsearch，商品 + 商家搜索进 ES，移除对应 LIKE 查询 |
| ES 同步 | RabbitMQ 异步同步（生产者发事件，搜索服务消费） |
| 消息队列 | RabbitMQ |
| 库存 | 独立 `shopai-inventory` 服务 + 独立 `inventory` 表 |
| 下单一致性 | Seata AT 分布式事务（`@GlobalTransactional`） |

## 3. 最终模块划分（8 个模块）

```
ShopAi/
├── pom.xml                 ← 父 POM（聚合 + 依赖版本管理）
├── shopai-common/          ← 公共库（非服务）
├── shopai-gateway/         ← 网关
├── shopai-user/            ← 用户/认证服务
├── shopai-product/         ← 商品服务
├── shopai-order/           ← 订单服务
├── shopai-merchant/        ← 商家/评价服务
├── shopai-inventory/       ← 库存服务
└── shopai-search/          ← 搜索服务（ES）
```

| 模块 | 类型 | 端口 | 职责 |
|---|---|---|---|
| shopai-common | 公共库 | — | `Result`、`PageResult`、`GlobalExceptionHandler`、`LongToStringResponseAdvice`、`JwtUtil`、`RedisService`、通用 DTO、雪花 ID 处理 |
| shopai-gateway | 网关 | 8080 | 统一 JWT 认证、路由、限流 |
| shopai-user | 服务 | 8081 | 认证/注册/登录/客户资料/管理员用户管理/仪表盘 |
| shopai-product | 服务 | 8082 | 商品、分类、SKU 基本信息、热销榜 |
| shopai-order | 服务 | 8083 | 购物车、地址、订单状态机、自动收货/完成定时任务 |
| shopai-merchant | 服务 | 8084 | 商家入驻/审核/提现/财务/周营收、实名、评价、评价投诉 |
| shopai-inventory | 服务 | 8086 | 库存查询、扣减、回补、秒杀、预热 |
| shopai-search | 服务 | 8085 | 商品/商家搜索（ES） |

## 4. 各服务归属明细

### 4.1 shopai-user（用户/认证）
- Controller：`AuthController`、`UserController` 中客户/管理员用户部分
- Service：`UserService`、`CustomerService`、`JwtUtil`、`RedisService`
- 实体：`User`、`Customer` + 对应 Mapper
- 职责：登录/注册/忘记密码/改密/管理员登录、客户资料维护、管理员用户管理（用户列表/详情/客户列表/状态/销号/重置密码/仪表盘）

### 4.2 shopai-product（商品）
- Controller：`ProductController`、`CategoryController`、`SKUController`
- Service：`ProductService`（瘦身）、`CategoryService`、`ProductSkuService`（瘦身，仅规格信息）
- 实体：`Product`、`ProductSku`、`Category` + Mapper
- 职责：商品/分类/SKU 增删改查、热销榜；SKU 查询时通过 Feign 批量补库存字段
- 事件：商品增删改后发 MQ `product.changed`

### 4.3 shopai-order（订单）
- Controller：`OrderController`、`CartController`、`AddressController`
- Service：`OrderService`、`OrderItemService`、`ShoppingCartService`、`AddressService`
- 实体：`Order`、`OrderItem`、`OrderActionLog`、`ShoppingCart`、`Address` + Mapper
- 职责：购物车、下单、订单状态机、退款、定时任务
- 跨服务：下单/退款通过 Seata + Feign 调 `shopai-inventory` 扣减/回补库存

### 4.4 shopai-merchant（商家/评价）
- Controller：`MerchantRealNameController`、`ReviewController`、`ReviewComplaintController`、`UserController` 中商户/管理员审核部分
- Service：`MerchantService`、`MerchantRealNameService`、`ReviewService`、`ReviewComplaintService`
- 实体：`Merchant`、`MerchantRealName`、`MerchantModification`、`MerchantWithdraw`、`MerchantWeeklyRevenue`、`Review`、`ReviewComplaint` + Mapper
- 职责：商家入驻/审核、店铺/实名修改审核、提现、财务、周营收、评价、投诉
- 事件：商家信息/审核通过后发 MQ `merchant.changed`

### 4.5 shopai-inventory（库存）
- Controller：`InventoryController`
- Service：`InventoryService`（从 `ProductSkuService` 迁移库存逻辑）
- 实体：`Inventory` + Mapper
- 职责：库存查询/扣减/回补/秒杀/预热
- 数据表：新建 `inventory`（见第 7 节）

### 4.6 shopai-search（搜索）
- Controller：`SearchController`
- 职责：商品搜索、店铺内搜索、商家搜索（全部走 ES）、全量重建索引
- 消费 MQ：`product.changed`、`merchant.changed`

## 5. 路径规范化映射（旧 → 新）

> 所有映射需在实现时以中文注释标注到对应 Controller。

### 5.1 用户服务（shopai-user）

| 旧路径 | 新路径 | 说明 |
|---|---|---|
| `/api/auth/**` | `/api/auth/**` | 不变 |
| `/api/user/customer/addCustomer` | `/api/customer/add` | 新增客户 |
| `/api/user/customer/updateCustomer` | `/api/customer/update` | 更新客户 |
| `/api/user/customer/getCustomer` | `/api/customer/me` | 查自己客户信息 |
| `/api/user/customer/getCustomerMax` | `/api/customer/me/detail` | 联表查个人信息 |
| `/api/user/admin/dashboard` | `/api/admin/dashboard` | 仪表盘 |
| `/api/user/admin/dashboard-stats` | `/api/admin/dashboard/stats` | 仪表盘全量统计 |
| `/api/user/admin/getUsers` | `/api/admin/users` | 用户列表 |
| `/api/user/admin/getUser` | `/api/admin/users/{id}` | 用户详情 |
| `/api/user/admin/getCustomer` | `/api/admin/customers/{userId}` | 客户详情 |
| `/api/user/admin/getCustomers` | `/api/admin/customers` | 客户列表 |
| `/api/user/admin/updateStatus` | `/api/admin/users/{id}/status` | 更新状态/角色 |
| `/api/user/admin/deleteUser` | `/api/admin/users/{id}` | 销号 |
| `/api/user/admin/resetPassword` | `/api/admin/users/{id}/reset-password` | 重置密码 |

### 5.2 商家服务（shopai-merchant）

| 旧路径 | 新路径 | 说明 |
|---|---|---|
| `/api/user/merchant/addMerchant` | `/api/merchant/apply` | 入驻申请 |
| `/api/user/merchant/getMerchant`（token） | `/api/merchant/me` | 查自己店铺 |
| `/api/user/merchant/updateMerchant` | `/api/merchant/me/store` | 提交店铺修改 |
| `/api/user/merchant/updateRealName` | `/api/merchant/me/real-name` | 提交实名修改 |
| `/api/user/merchant/updateAccount` | `/api/merchant/me/account` | 更新账号信息 |
| `/api/user/merchant/check-status` | `/api/merchant/me/check-status` | 校验审核状态 |
| `/api/user/merchant/me` | `/api/merchant/me/id` | 取 merchantId |
| `/api/user/merchant/finance` | `/api/merchant/me/finance` | 财务概览 |
| `/api/user/merchant/withdraw` | `/api/merchant/withdraw` | 申请提现 |
| `/api/user/merchant/withdraws` | `/api/merchant/withdraws` | 提现记录 |
| `/api/user/merchant/weekly-revenue` | `/api/merchant/weekly-revenue` | 周营收 |
| `/api/user/merchant/real-name/add` | `/api/merchant/real-name` | 实名认证 |
| `/api/user/customer/getMerchants` | `/api/merchant/list` | 商家列表（公开） |
| `/api/user/customer/getMerchant` | `/api/merchant/{id}` | 商家详情（公开） |
| `/api/user/customer/getMerchantRank` | `/api/merchant/rank` | 推荐排行 |
| `/api/user/admin/verifyMerchant` | `/api/admin/merchants/{id}/verify` | 审核入驻 |
| `/api/user/admin/getPendingMerchants` | `/api/admin/merchants/pending` | 待审核列表 |
| `/api/user/admin/getModifications` | `/api/admin/merchants/modifications` | 修改申请列表 |
| `/api/user/admin/approveModification` | `/api/admin/merchants/modifications/{id}/approve` | 同意修改 |
| `/api/user/admin/rejectModification` | `/api/admin/merchants/modifications/{id}/reject` | 拒绝修改 |
| `/api/user/admin/pendingWithdraws` | `/api/admin/merchants/withdraws/pending` | 待处理提现 |
| `/api/user/admin/handleWithdraw` | `/api/admin/merchants/withdraws/{id}` | 处理提现 |

### 5.3 商品服务（shopai-product）

| 旧路径 | 新路径 | 说明 |
|---|---|---|
| `/api/product/detail/{id}` | `/api/product/{id}` | 商品详情 |
| `/api/product/merchant/{id}` | `/api/product/merchant/{id}` | 店铺商品列表（不变） |
| `/api/product/add` | `/api/product` | 新增 |
| `/api/product/update` | `/api/product/{id}` | 编辑 |
| `/api/product/status/{id}` | `/api/product/{id}/status` | 状态 |
| `/api/product/delete/{id}` | `/api/product/{id}` | 删除 |
| `/api/product/hot` | `/api/product/hot` | 热销榜（不变） |
| `/api/category/**`、`/api/sku/**` | 不变 | 分类/SKU |

### 5.4 订单服务（shopai-order）
- `/api/order/**`、`/api/cart/**`、`/api/address/**` — 全部保持不变

### 5.5 搜索服务（shopai-search）

| 旧路径 | 新路径 | 说明 |
|---|---|---|
| `/api/product/search` | `/api/search/product` | 商品搜索（ES） |
| `/api/product/merchant/{id}/search` | `/api/search/product/merchant/{id}` | 店铺内搜索（ES） |
| `/api/user/customer/searchMerchants` | `/api/search/merchant` | 商家搜索（ES） |

### 5.6 库存服务（shopai-inventory，内部接口）

| 新路径 | 说明 |
|---|---|
| `GET /api/inventory/sku/{skuId}` | 查单个库存 |
| `GET /api/inventory/sku/batch?skuIds=1,2,3` | 批量查库存 |
| `POST /api/inventory/deduct` | 扣减（乐观锁） |
| `POST /api/inventory/restore` | 回补 |
| `POST /api/inventory/init` | SKU 创建时初始化 |
| `POST /api/inventory/seckill` | 秒杀 |

## 6. 网关设计（shopai-gateway）

- 对外保持 `8080` 端口，前端 dev 代理（`/api`、`/uploads`）无需改动。
- 统一认证：解析 JWT → 校验 Redis 黑名单 → 写入 `X-User-Id`、`X-User-Role`、`X-User-Phone` 头转发。
- 路由规则：

| 前缀 | 目标服务 |
|---|---|
| `/api/auth/**`、`/api/customer/**`、`/api/admin/users/**`、`/api/admin/customers/**` | shopai-user |
| `/api/admin/merchants/**`、`/api/merchant/**`、`/api/review/**` | shopai-merchant |
| `/api/product/**`、`/api/category/**`、`/api/sku/**` | shopai-product |
| `/api/order/**`、`/api/cart/**`、`/api/address/**` | shopai-order |
| `/api/search/**` | shopai-search |
| `/api/inventory/**` | 网关默认拦截（仅服务间 Feign 直连） |
| `/uploads/**` | 静态资源（托管方案另定） |

- 鉴权延续：公开接口网关放行；需 `ADMIN` 的接口网关校验 `X-User-Role`，下游可再用 `@PreAuthorize` 兜底。

## 7. 库存表设计（新建 `inventory`）

```sql
CREATE TABLE inventory (
  id            BIGINT       NOT NULL COMMENT '主键(雪花)',
  sku_id        BIGINT       NOT NULL COMMENT '关联 product_skus.id',
  stock         INT          NOT NULL DEFAULT 0 COMMENT '当前可售库存',
  locked_stock  INT          NOT NULL DEFAULT 0 COMMENT '锁定库存(秒杀/预扣用)',
  version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  updated_at    DATETIME     COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_id (sku_id)
) COMMENT='库存表';
```

- 迁移：原 `product_skus.stock` 值初始化到 `inventory`；`product_skus.stock` 字段保留但不再由商品服务读写。
- 乐观锁：`UPDATE inventory SET stock = stock - #{qty}, version = version + 1 WHERE sku_id = #{skuId} AND stock >= #{qty}`。

## 8. 下单链路改造（Seata AT 分布式事务）

- 原 `createOrder`/`createOrderDirect`：单库本地事务（扣库存 + 写订单 + 累销量 + 清购物车）。
- 改造后：`@GlobalTransactional` 标注，跨服务扣库存。
  - 本地：写 orders/order_items、累销量、清购物车。
  - Feign → `inventory.deduct` 扣库存（Seata AT 分支事务）。
  - 任一步失败 → Seata 全局回滚。
- 回补：取消订单、退款通过时，`restoreStock` 改为 Feign 调 `inventory.restore`，与订单状态变更同一全局事务。
- Seata 部署：新增 Seata Server（TC）；订单、库存相关库加 `undo_log` 表；业务服务引入 seata-starter 并配置 TC 地址与事务分组。

## 9. 搜索服务（Elasticsearch）

### 9.1 索引

- `product`：`id, name, description, category_id, category_name, parent_category_name, merchant_id, shop_name, shop_logo, price_min, price_max, sales_count, status, created_at, images`
- `merchant`：`id, shop_name, logo_url, rating, is_verified, description, created_at`

### 9.2 对外接口

| 新路径 | 说明 |
|---|---|
| `GET /api/search/product?keyword=&categoryId=&minPrice=&maxPrice=&page=&size=` | 商品搜索 |
| `GET /api/search/product/merchant/{merchantId}?keyword=&page=&size=` | 店铺内搜索 |
| `GET /api/search/merchant?keyword=&page=&size=` | 商家搜索 |
| `POST /api/search/rebuild` | 全量重建索引 |

## 10. MQ 同步（RabbitMQ）

- 交换机：`shopai.data.exchange`（topic）
- 路由键：`product.changed`、`merchant.changed`
- 生产者：shopai-product（商品增删改）、shopai-merchant（商家信息/审核通过）
- 消费者：shopai-search，收到事件后增量 upsert 到对应索引
- 可靠性：生产者 publisher-confirm + 消息持久化；消费者手动 ack，失败重试 3 次后进死信队列；提供全量重建兜底

## 11. 错误处理与降级

- `GlobalExceptionHandler` 下沉到 `shopai-common`，统一 `{code, message}` 响应。
- Feign 统一降级：库存查询失败 → 商品详情不显示库存；商家信息查询失败 → 返回基本字段；避免跨服务雪崩。
- 网关统一返回 401/403 JSON，与现有前端响应拦截器兼容。

## 12. 前端改造（Vue）

- 涉及文件：25 个 `.vue` + `http.js` + 可能的 store/router。
- 主要改动点：路径映射（见第 5 节），集中在 `UserController` 大杂烩路径（`/api/user/customer/**`、`/api/user/merchant/**`、`/api/user/admin/**`）。
- 搜索接口三处改走 `/api/search/**`。
- `vite.config.js` 代理目标保持 `8080` 不变。
- 需逐一核对 60+ 个 API 调用点。

## 13. 测试与验证

- 各服务启动测试（改造原 `ShopAiApplicationTests`）。
- 重点：下单 Seata 全局回滚（扣库存成功后订单写库失败 → 库存回滚）。
- MQ 同步：商品上架 → 消费 → ES 可搜到。
- 前端回归：60+ API 调用点逐一核对新路径。

## 14. 风险与注意事项

1. Seata 是新增重量级组件，部署与调优需额外投入。
2. 共享单库下，跨服务仍可能直接 JOIN，需在实现时明确"逐步收敛"，避免拆分后仍隐性耦合。
3. `UserController` 拆分为 user/merchant 两个服务，路径变更是前端改动的主要来源，需谨慎核对。
4. `RedisService`、`JwtUtil` 等被多服务复用，必须放入 `shopai-common`，避免重复维护。

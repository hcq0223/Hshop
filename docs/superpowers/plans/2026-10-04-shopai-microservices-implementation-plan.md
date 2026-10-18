# ShopAi 单体 → 微服务 实现计划

- 日期：2026-10-04
- 前置 spec：`docs/superpowers/specs/2026-10-04-shopai-microservices-design.md`
- 状态：待执行

> 执行原则：每个阶段独立可编译、可启动、可验收。按依赖顺序推进，前一阶段验收通过再进入下一阶段。

---

## 阶段总览与依赖关系

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 基础设施准备（Nacos/RabbitMQ/ES/Seata + SQL 脚本） | 无 |
| P1 | 父 POM + shopai-common 公共模块 | P0 |
| P2 | shopai-gateway 网关 | P1 |
| P3 | shopai-user 用户服务 | P1 |
| P4 | shopai-product 商品服务 | P1 |
| P5 | shopai-inventory 库存服务 + Seata | P1、P4 |
| P6 | shopai-order 订单服务（接库存） | P5 |
| P7 | shopai-merchant 商家/评价服务 | P1 |
| P8 | shopai-search 搜索服务 + ES + MQ | P4、P7 |
| P9 | 前端 Vue 路径改造 | P3–P8 |
| P10 | 联调回归与验收 | 全部 |

---

## P0：基础设施准备

**目标**：部署并验证所有外部中间件，产出配置与 SQL 脚本。

**任务清单**：
1. 部署 Nacos Server（单机），创建命名空间 `shopai`，确认 `http://localhost:8848` 可访问。
2. 部署 RabbitMQ，创建交换机 `shopai.data.exchange`（topic）、队列 `search.product.changed`、`search.merchant.changed`、死信交换机与队列。
3. 部署 Elasticsearch（7.x/8.x），确认 `http://localhost:9200` 可访问。
4. 部署 Seata Server（TC），确认 `http://localhost:7091` 可访问。
5. 编写 SQL 脚本：
   - `sql/inventory.sql`：新建 `inventory` 表（见 spec 第 7 节）。
   - `sql/seata_undo_log.sql`：订单库、库存库加 `undo_log` 表。
   - `sql/inventory_init.sql`：从 `product_skus.stock` 初始化 `inventory` 数据。
6. 产出 `docs/superpowers/plans/infra-config.md`，记录各中间件端口、账号、启动命令。

**验收标准**：四个中间件均能访问；`inventory` 表建好且数据初始化正确（`SELECT COUNT(*)` 与 `product_skus` 库存非空数一致）。

---

## P1：父 POM + shopai-common

**目标**：建立多模块骨架，抽离公共代码，保证公共库独立可编译。

**任务清单**：
1. 创建父 `pom.xml`（`packaging=pom`，聚合 8 个子模块，`dependencyManagement` 统一 Spring Boot 4.0.6、Spring Cloud、Spring Cloud Alibaba、MyBatis-Plus、JJWT、Hutool、Lombok 版本）。
2. 创建 `shopai-common` 模块，迁移：
   - `common/Result.java`、`common/PageResult.java`
   - `Config/GlobalExceptionHandler.java`、`Config/LongToStringResponseAdvice.java`
   - `security/JwtUtil.java`
   - `service/RedisService.java`
   - `dto/PageRequest.java` 及通用 DTO
3. 处理依赖：`shopai-common` 引入 web、security、data-redis、mybatis-plus、jackson、jjwt、hutool 的 provided/compile 依赖。
4. 修正包名：统一从 `com.example.demo` → `com.shopai.common`。

**验收标准**：`mvn -pl shopai-common install` 成功；公共类无对业务实体/Mapper 的依赖（可被任意服务复用）。

---

## P2：shopai-gateway

**目标**：搭建网关，实现统一认证与路由。

**任务清单**：
1. 创建 `shopai-gateway` 模块，引入 `spring-cloud-starter-gateway`、`spring-cloud-starter-alibaba-nacos-discovery`、`spring-cloud-starter-alibaba-nacos-config`。
2. 实现 `JwtAuthGlobalFilter`：解析 JWT → 校验 Redis 黑名单 → 写 `X-User-Id`/`X-User-Role`/`X-User-Phone` 头。
3. 配置路由（spec 第 6 节路由表），`application.yml` 从 Nacos 拉配置。
4. 配置跨域 CORS，与现有前端保持一致。
5. 公开/需登录/管理员三层鉴权落到网关过滤器。

**验收标准**：网关启动注册到 Nacos；手动请求 `/api/auth/**` 公开路径能转发；携带有效 Token 能透传用户头到下游。

---

## P3：shopai-user 用户服务

**目标**：拆分认证 + 客户 + 管理员用户管理。

**任务清单**：
1. 创建 `shopai-user`，包名 `com.shopai.user`。
2. 迁移 `AuthController`、`UserController`（仅客户/管理员用户部分）、`UserService`、`CustomerService`。
3. 迁移实体 `User`、`Customer` + `UserMapper`、`CustomerMapper` + XML。
4. 路径规范化（spec 5.1），中文注释标注旧→新映射。
5. 保留认证黑名单逻辑（`RedisService.isTokenBlacklisted`）。
6. 删除原 `JwtAuthenticationFilter` 中对下游的验签职责（下游改读 `X-User-*` 头，见 P3–P8 各服务 `CurrentUser` 工具类）。

**验收标准**：`shopai-user` 启动成功；登录/注册/客户资料/管理员用户接口按新路径可调用。

---

## P4：shopai-product 商品服务

**目标**：拆分商品/分类/SKU 基本信息，剥离库存与搜索。

**任务清单**：
1. 创建 `shopai-product`，包名 `com.shopai.product`。
2. 迁移 `ProductController`、`CategoryController`、`SKUController`、`ProductService`（瘦身）、`CategoryService`、`ProductSkuService`（瘦身，仅规格）。
3. 迁移实体 `Product`、`ProductSku`、`Category` + Mapper + XML。
4. 移除 `ProductSkuService` 中库存相关方法（`decreaseStock`/`restoreStockBySkuId`/`seckill`/`preheatStock`/`getSeckillStock`），迁移到 P5 库存服务。
5. 移除 `ProductService.searchProducts`（迁到 P8 搜索服务）。
6. SKU 查询通过 Feign 批量补库存字段（降级：库存查询失败不显示库存）。
7. 商品增删改后发 MQ `product.changed`（P8 再建消费者，先只发）。

**验收标准**：商品/分类/SKU 接口按新路径可调用；SKU 列表能通过 Feign 显示库存。

---

## P5：shopai-inventory 库存服务 + Seata

**目标**：库存独立服务，落地 Seata 分支。

**任务清单**：
1. 创建 `shopai-inventory`，包名 `com.shopai.inventory`。
2. 新建实体 `Inventory` + `InventoryMapper` + XML，对接 `inventory` 表。
3. 迁移库存逻辑：`decreaseStock`、`restoreStockBySkuId`、`seckill`、`preheatStock`、`getSeckillStock`，新增 `getStock`、`batchGetStock`、`initStock`、`increaseStock`。
4. `InventoryController` 实现 spec 5.6 接口。
5. 引入 `seata-starter`，配置 TC 地址、事务分组、`undo_log`。
6. 乐观锁扣减 SQL 落地。

**验收标准**：库存接口可独立调用；`deduct` 并发下不超卖；SKU 创建时能 `init` 库存。

---

## P6：shopai-order 订单服务（接库存）

**目标**：订单服务 + Seata 下单链路。

**任务清单**：
1. 创建 `shopai-order`，包名 `com.shopai.order`。
2. 迁移 `OrderController`、`CartController`、`AddressController`、`OrderService`、`OrderItemService`、`ShoppingCartService`、`AddressService`。
3. 迁移实体 `Order`、`OrderItem`、`OrderActionLog`、`ShoppingCart`、`Address` + Mapper + XML。
4. `createOrder`/`createOrderDirect` 标注 `@GlobalTransactional`，扣库存改 Feign 调 `inventory.deduct`。
5. `restoreStock` 改 Feign 调 `inventory.restore`，取消/退款路径同样走全局事务。
6. 累销量逻辑保留本地（`productMapper.incrementSalesCount` 若跨服务需改 Feign，见风险 4）。
7. 定时任务（`scheduleAutoReceive`/`scheduleAutoComplete`）迁移，注意自调用事务失效问题。
8. 引入 `seata-starter` + `feign`。

**验收标准**：下单成功链路通；构造"扣库存成功后写订单失败"场景，验证 Seata 全局回滚、库存被还原。

---

## P7：shopai-merchant 商家/评价服务

**目标**：拆分商家/评价/投诉。

**任务清单**：
1. 创建 `shopai-merchant`，包名 `com.shopai.merchant`。
2. 迁移 `MerchantRealNameController`、`ReviewController`、`ReviewComplaintController` + `UserController` 商户/管理员审核部分。
3. 迁移 `MerchantService`、`MerchantRealNameService`、`ReviewService`、`ReviewComplaintService`。
4. 迁移实体 `Merchant`、`MerchantRealName`、`MerchantModification`、`MerchantWithdraw`、`MerchantWeeklyRevenue`、`Review`、`ReviewComplaint` + Mapper + XML。
5. 路径规范化（spec 5.2）。
6. 商家信息/审核通过后发 MQ `merchant.changed`（P8 建消费者）。

**验收标准**：商家入驻/审核/提现/财务/评价/投诉接口按新路径可调用。

---

## P8：shopai-search 搜索服务 + ES + MQ

**目标**：ES 搜索 + MQ 消费同步。

**任务清单**：
1. 创建 `shopai-search`，包名 `com.shopai.search`，引入 `spring-boot-starter-data-elasticsearch`（或 `elasticsearch-rest-high-level-client`）+ `spring-boot-starter-amqp`。
2. 定义 `ProductDocument`、`MerchantDocument` 索引模型，建索引。
3. `SearchController` 实现 spec 9.2 接口（`/api/search/product`、`/api/search/product/merchant/{id}`、`/api/search/merchant`、`/api/search/rebuild`）。
4. 消费者监听 `product.changed`、`merchant.changed`，增量 upsert；手动 ack + 重试 + 死信。
5. 全量重建：从 `products`/`merchants` 表批量灌入 ES。
6. 删除原商品服务/商家服务中的 LIKE 搜索（`selectPageByConditions` 的 keyword 分支、`searchMerchantsPublic` 等）。

**验收标准**：搜索接口走 ES 返回正确结果；商品上架 → 消费事件 → ES 可搜到；死信重试生效。

---

## P9：前端 Vue 路径改造

**目标**：同步前端 API 路径，保证零功能回归。

**任务清单**：
1. 按 spec 第 5 节，逐一替换 25 个 `.vue` + 相关文件中的旧路径为新路径。
2. 重点：`/api/user/customer/**`、`/api/user/merchant/**`、`/api/user/admin/**` 的大杂烩路径拆解。
3. 搜索三处改 `/api/search/**`。
4. 每个改动点加中文注释标注旧→新。
5. `vite.config.js` 代理保持 `8080` 不变；`http.js` 不变。

**验收标准**：`npm run dev` 启动；对照 60+ 调用点清单逐项核对无遗漏。

---

## P10：联调回归与验收

**目标**：全链路联调，确认无回归。

**任务清单**：
1. 启动全部 8 模块 + 4 中间件。
2. 走通核心业务流：注册→登录→商家入驻→发布商品→顾客搜索→加购→下单→支付→发货→收货→评价→提现。
3. 验证 Seata 回滚、MQ 同步、ES 搜索、库存不超卖、黑名单登出。
4. 前端全页面回归。

**验收标准**：全流程无报错；搜索走 ES（日志确认）；库存并发无超卖；登出后旧 Token 失效。

---

## 风险登记与注意事项

1. **Seata 是新增重量级组件**：部署、undo_log、事务分组配置需谨慎，P0 先验证 TC 连通。
2. **共享单库仍可能隐性 JOIN**：实现时明确"逐步收敛"，禁止新代码跨服务直查对方表。
3. **`UserController` 拆两服务**：前端路径改动是主要回归来源，P9 需逐一核对。
4. **累销量跨服务**：`incrementSalesCount` 原在订单服务本地事务内，若 `products` 表归属商品服务，需改 Feign 并纳入 Seata，否则销量与订单不一致——实现时重点确认此点。
5. **`RedisService` 常量与多服务共享**：缓存 key 前缀统一放 `shopai-common`，避免各服务 key 冲突。
6. **`LongToStringResponseAdvice` 全局生效**：各服务都要配置，统一放 common 后由各服务自动装配。
7. **注释保留**：所有迁移文件保留原中文注释，新增代码补中文注释；路径变更处加"旧→新"标注。

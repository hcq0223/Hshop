# 前端 / Python Agent 路由适配审计（2026-10-11）

## 背景

Java 侧已完成「单体 → 微服务」拆分（网关 8080 统一入口 + 7 个业务服务）。
本次审计目标：确认 `D:\ShopAi\Vue`（前端）与 `D:\ShopAi\Python`（AI Agent 服务）
是否还在调用**单体旧路由**，并完成适配。

拆分后的约定：

| 项 | 说明 |
| --- | --- |
| 唯一入口 | 网关 `http://localhost:8080`，所有外部调用（Vue / Python）只走网关 |
| 路径前缀 | 所有业务路由统一 `/api/**` |
| 服务间调用 | 仅 `/api/*/internal/**` + Feign，网关对这些路径直接返回 503（`no://op` 拦截） |
| 路由不可变 | 本次不新增/不修改任何 Controller 路由，只改调用方 |

---

## 一、Vue 前端：无需修改

对 `Vue/src` 全量扫描 axois 调用（239 处匹配，去重后 99 个 API 路径），
逐条与 Java Controller 对照，**全部命中，无旧路径**。抽样核对结论：

| Vue 调用 | Java 落点 |
| --- | --- |
| `/auth/*`、`/common/upload` | shopai-user `AuthController` / `CommonController` |
| `/customer/me`、`/customer/add`、`/customer/update` | shopai-user `CustomerController` |
| `/admin/dashboard/stats`、`/admin/users`、`/admin/users/{id}/status` | shopai-user `AdminUserController` |
| `/admin/merchants/pending`、`/modifications`、`/withdraws/pending`、`/{id}/verify` | shopai-merchant `AdminMerchantController` |
| `/merchant/me`、`/me/id`、`/me/store`、`/me/real-name`、`/me/account`、`/me/finance`、`/me/check-status`、`/withdraw`、`/withdraws`、`/weekly-revenue`、`/apply`、`/real-name` | shopai-merchant `MerchantController` / `MerchantRealNameController` |
| `/product/**`、`/sku/**`、`/category/**` | shopai-product 三个 Controller |
| `/order/**`、`/cart/**`、`/address/**` | shopai-order 三个 Controller |
| `/review/**`、`/review/complaint/**` | shopai-merchant `ReviewController` / `ReviewComplaintController` |
| `/search/product`、`/search/merchant`、`/search/product/merchant/{id}` | shopai-search `SearchController` |

另外确认：

- `vite.config.js` 代理 `/api` → `http://localhost:8080`（网关）、`/uploads` → 8080、`/agent` → 8090，正确。
- `utils/http.js` `baseURL: '/api'`，与网关前缀一致。
- Vue 未直接调用任何 `/api/*/internal/**` 内部接口。

**结论：Vue 侧零改动。**

---

## 二、Python Agent：全部 API 路径错误（已修复）

`Python` 通过 `services/java_api.py::call_java_api()` 调 Java，`JAVA_API_BASE`
已含 `/api`。拆分后该模块里 **41 处 `path=` 全部仍是单体旧路由**，
其中 `/user/**` 在网关已无任何路由（`/api/user/**` 不存在，只有
`/api/user/internal/**` 且被拦截），因此**所有工具调用都会 404/403**。

### 2.1 `tools/admin_tools.py`（14 处）

| 旧路径 | 新路径 |
| --- | --- |
| `/user/admin/dashboard` | `/admin/dashboard` |
| `/user/admin/dashboard-stats` | `/admin/dashboard/stats` |
| `/user/admin/getUsers` | `/admin/users` |
| `/user/admin/getUser?id=` | `/admin/users/{id}`（改路径变量，去掉 params） |
| `/user/admin/getCustomers` | `/admin/customers` |
| `/user/admin/getCustomer?userId=` | `/admin/customers/{userId}` |
| `/user/admin/getMerchant?userId=` | `/admin/merchants/by-user/{userId}` |
| `/user/admin/getPendingMerchants` | `/admin/merchants/pending` |
| `/user/admin/getModifications` | `/admin/merchants/modifications` |
| `/user/admin/pendingWithdraws` | `/admin/merchants/withdraws/pending` |
| `/user/admin/verifyMerchant?merchantId=` | `/admin/merchants/{id}/verify`（`verified` 仍为 query） |
| `/order/admin/orders` | 不变（网关 `/api/order/**`） |
| `/order/admin/history` | 不变 |
| `/category/all` | 不变 |

### 2.2 `agents/customer_agent.py`（7 处）

| 旧路径 | 新路径 | 备注 |
| --- | --- | --- |
| `/user/customer/getCustomerMax` | `/customer/me/detail` | 必须用 `/me/detail`：`/customer/me` 只查 customers 表，不含 username/phone/email |
| `/user/customer/searchMerchants` | `/search/merchant` | 走 ES |
| `/user/customer/getMerchant?id=` | `/merchant/{id}` | 改路径变量 |
| `/product/search` | `/search/product` | 走 ES（`ProductController` 无 `/search`） |
| `/product/detail/{pid}` | `/product/{pid}` | |
| `/address/*`、`/cart/*`、`/order/customer/*`、`/product/hot`、`/category/all` | 不变 | 网关已覆盖 |

顺带修正 `get_use_h` 读字段：`default_address_id` → `defaultAddressId`
（Java Jackson 为驼峰，旧字段名永远取到 `None`）。

### 2.3 `agents/merchant_agent.py`（6 处）

| 旧路径 | 新路径 |
| --- | --- |
| `/user/merchant/getMerchant` | `/merchant/me` |
| `/user/merchant/withdraws` | `/merchant/withdraws` |
| `/user/merchant/weekly-revenue` | `/merchant/weekly-revenue` |
| `/user/merchant/finance` | `/merchant/me/finance` |
| `/user/merchant/me` | `/merchant/me/id` |
| `/order/merchant/orders`、`/order/merchant/history`、`/product/merchant/{id}` | 不变 |

### 2.4 `services/java_api.py`

更新 docstring：明确 `path` 相对 `JAVA_API_BASE`、不得使用单体旧路径、
不得调用 `/api/*/internal/**`。

---

## 三、顺带确认的其它项

| 项 | 结论 |
| --- | --- |
| Python JWT 密钥 | `Python/.env` 的 `JWT_SECRET` 与 Java `jwt.secret` 逐字节一致（SHA256 相同），`core/auth.py` 可正常解析 token |
| Agent 路由前缀 | Python `APIRouter(prefix="/agent")` ↔ Vue `fetch('/agent/chat/stream')` + Vite 代理 8090，一致 |
| 旧单体 `JAVA/src/main/java/com/example/demo` | 未列入父 `pom.xml` 的 `<modules>`，不参与构建、不占 8080，可作参考保留 |
| `core/database.py` 直连 MySQL | **已废弃**：Worker 侧无任何调用方；独立库后单库查不到跨服务数据，且绕过网关鉴权。已加废弃说明，新工具一律走 `call_java_api()` |
| ID 类型 | 各服务扫描 `com.shopai.common`，`LongToStringResponseAdvice` 生效，所有 Long ID 以 JSON 字符串返回；Python 侧 `f"/merchant/{id}"` 等字符串拼接不受影响 |

---

## 四、本次未改动的已知问题（仅记录）

1. ~~`GET /api/order/detail/{id}` 无归属校验~~ —— **已修复，见第五节。**
2. ~~网关只对 `/api/admin/**` 做角色校验，`/api/merchant/me` 不校验 merchant 角色~~
   —— **已修复，见第五节。**
3. **Python 工具调用未做「无参数」防御**：如 `getCustomer` 缺 `userId` 时
   会请求 `/admin/customers/None`（旧写法同样会失败），
   工具 schema 已声明 `required`，暂不额外处理。

---

## 五、安全修复（2026-10-13）

### 5.1 订单越权读取（BOLA / IDOR）

**问题**：`OrderController.getOrderDetail` / `getByOrderNo` 拿到 id / orderNo
就无条件返回订单详情，而 `orderId` 是自增主键、`orderNo` 亦可枚举，
任何已登录用户都能读取他人订单的收货人姓名、电话、详细地址、金额与商品明细。

**修复**（`shopai-order` `OrderController`）：

- 新增私有方法 `canAccessOrder(request, order)`，放行规则三选一：
  管理员（`X-User-Role = admin`）／下单顾客本人／订单所属商家；
- `getOrderDetail`、`getByOrderNo` 均改为 `HttpServletRequest request` 入参并调用该校验；
- 无权时**统一返回「订单不存在」**（而非「无权操作」），避免通过错误信息判断订单是否存在；
- 无权访问时打 `log.warn` 记录 `orderId / userId / role` 便于审计；
- 归属比对用的 `customerId` / `merchantId` 仍经 `CustomerResolver`、
  `OrderEnrichService.resolveMerchantIdByUserId` 走 Feign 换取，
  **解析失败返回 null 时一律不放行**（宁可拒绝也不越权）。

**路由与响应结构未变**，前端与 Python 无需改动。

**副作用**：每次订单详情请求多一次 `userId → customerId` 的 Feign 调用；
仅当该用户不是下单顾客时才会再调一次 `userId → merchantId` 的 Feign。

### 5.2 网关补商家角色校验

**问题**：网关原先只对 `/api/admin/**` 做角色校验，其余路径仅注入
`X-User-Id / X-User-Role`，鉴权完全下沉。而 merchant 服务的
`MerchantController` / `MerchantRealNameController` 直接取 `X-User-Id`
且**没有角色兜底**，任意已登录顾客都能调用 `/api/merchant/me`、
`/api/merchant/me/finance`、`/api/merchant/withdraws` 等（当前只会返回
空/null，无数据泄露，但语义不严）。

**修复**（`shopai-gateway` `JwtAuthGlobalFilter`）：

- 新增 `MERCHANT_SELF_PATHS` 白名单常量，**逐个精确列出**商家自助接口：
  `/api/merchant/apply`、`/me`、`/withdraw`、`/withdraws`、
  `/weekly-revenue`、`/real-name`；
- 新增 `isMerchantSelfPath(path)`：命中路径本身或其 `/` 子路径才算匹配，
  因此 `/api/merchant/me/store`、`/me/finance`、`/me/check-status` 等
  子路径同样受校验，而 `/api/merchant/members` 不会被误判；
- 三处分支与 admin 完全对齐：无 Token → 401；Token 无效 → 401；
  角色非 `merchant` → 403（`仅商家可操作`）。

**为什么不直接写 `/api/merchant/**`**：`MerchantController` 中公开接口与
自助接口共用同一前缀 —— `GET /api/merchant/list`（商家列表）、
`GET /api/merchant/rank`（排行）、`GET /api/merchant/{id}`（公开详情）
都必须继续对未登录访客开放，通配会一并拦掉。

**管理后台不受影响**：后台走的是 `/api/admin/merchants/**`，
不依赖这些 `/api/merchant/**` 自助接口。

**验证**：已执行 `mvnw -pl shopai-order,shopai-gateway -am -DskipTests clean compile`
通过，两个 class 均已重新产出。


# ShopAi Feign 降级（FallbackFactory）改造与复审

日期：2026-10-10
范围：`D:\ShopAi\JAVA`（`shopai-*` 微服务模块的 `feign` 包与各服务 `application.yml`）

---

## 一、对你已写代码的评审

### 1.1 写得对的部分

| 点 | 评价 |
|---|---|
| 用 `FallbackFactory` 而不是 `fallback` | ✅ 正确选择。`fallbackFactory` 能拿到 `Throwable`，因此可以记录「为什么降级」；`fallback` 只能返回一个静态实现，排查时不知道是超时还是 500。 |
| 抽 `FeignFallbackReason.describe(cause)` | ✅ 好抽象。把 `SocketTimeoutException/ConnectException/RetryableException/FeignException` 的判定收敛到一处，各 Fallback 不再重复 if-else；而且它**先解包 `RetryableException.getCause()`** 再判断，这点很关键（Feign 的连接失败/读超时通常被包成 `RetryableException`，不解包只能得到笼统的 "retryable"）。 |
| 每个 client 加 `contextId` | ✅ 必须的。同一个 `name`（如多个服务都用 `shopai-user`）注册多个 Feign 接口时，没有 `contextId` 会报 bean 名冲突。 |
| `shopai-common` 里 `spring-cloud-starter-openfeign` 用 `<optional>true</optional>` | ✅ 正确。`FeignFallbackReason` 依赖 `feign.FeignException`，所以 common 自己需要该依赖才能编译；标 optional 又不会把它强推给下游（网关是 WebFlux，不该被拖入）。 |
| 降级里用 `log.warn/error` 并带上方法名与参数 | ✅ 便于定位。 |

### 1.2 发现的问题（已修）

#### 问题 1：~~`feign.circuitbreaker.enabled: false` 导致降级不生效~~（**此结论已撤回**）

> **更正说明**：我最初把 `shopai-product` 里的 `feign.circuitbreaker.enabled: false`
> 判定为「所有降级都不生效」，并把五个服务的 yml 改成 `true` 还新增了 `feign` 配置块。
> **这个判断和那些改动都是错的，已全部回退。**
>
> 撤回原因：本项目走的是 **Sentinel 路线**（`feign.sentinel.enabled`，Spring Cloud Alibaba），
> 而 `feign.circuitbreaker.enabled` 属于 **Spring Cloud OpenFeign 自己的断路器开关**
> （Resilience4j 那条线）。两者是**不同框架的两个开关**，因此
> `feign.circuitbreaker.enabled: false` 并不等于「关掉降级」。
> 我当时的结论只考虑了 OpenFeign 那条线，措辞过于绝对。
>
> 关于这两个属性同时存在时的覆盖优先级，我没有可靠依据，**不作为结论**。
>
> **仍然成立的部分**：无论走哪条线，降级能否生效最终都取决于
> 「Sentinel 的 `SentinelInvocationHandler` 是否解析 `@FeignClient` 上的 `fallbackFactory`」。
> 这一点我无法在沙箱内验证（本地仓库无 sources jar，shell 不可用），需要实测，见第四节。

#### 问题 2：`UserInternalClientFallback`（商家侧）所有方法 `return null`

原实现 4 个方法全部 `return null`。商家服务对这些返回值是**直接解引用**的，
例如 `MerchantServiceImpl.updateAccount` 里：

```java
Result<Integer> r = userInternalClient.updateAccount(...);
if (r == null || r.getCode() == null || ...) { throw ... }
```

虽然多数调用点有 `r != null` 判空，但 `updateAccount` 的分支里还会走
`r.getMessage()`。降级路径恰恰是「下游已经出问题」的时刻，最不该再抛 NPE 掩盖真实原因。

**已修**：所有方法返回**结构完整的 `Result`**：
- `getUser` → `Result.ok(null)`（调用方按 data 为 null 安全跳过，商家详情缺账号信息但页面正常）
- `listUsers` → `Result.ok(空列表)`（**刻意如此**：`MerchantIndexService` 取不到用户信息就跳过该商家不进索引，宁可少索引也不把「账号状态未知」的商家放进搜索结果）
- `activateUser` → `Result.error(...)`（调用方对失败是「记录告警、不阻断入驻流程」）
- `updateAccount` → `Result.error(...)`（写操作**不能静默成功**，要商家看到明确失败）

#### 问题 3（本轮最有价值的发现）：搜索服务的降级若返回「空列表」会**静默清空索引**

这是设计层面的坑，不是笔误。搜索服务的索引同步逻辑把**「源端返回空」理解为「该数据已删除 → 从索引移除」**：

```java
// SearchSyncService.syncProduct（改造前）
ProductIndexItem ref = loadProduct(productId);
if (ref == null) {                       // 而 loadProduct 在「失败」与「不存在」时都返回 null
    productRepository.deleteById(...);    // → 商品服务一抖动就把在售商品从索引删掉
}
```

因此如果给 `search` 的这几个 client 加上「降级返回空列表」，
就会在**下游抖动期间把大量在售商品 / 已认证商家从搜索索引里静默删除**，
而且极难排查（数据库里数据完好）。

**已修（两层防护）**：

1. **降级返回「失败」而非「空」**：`search` 的 5 个 Fallback 全部返回 `Result.error(...)`，
   语义是「回源失败」，绝不表达成「不存在」；
2. **调用方改为三态判断**：`SearchSyncService.loadProduct / loadMerchant` 现在返回
   `List`（`null` = 拉取失败 → 跳过同步保持索引原样；空列表 = 确认不存在 → 删除），
   `syncProduct / syncMerchant` 相应区分处理。原文注释曾写「失败时删索引更安全」，
   这是**错的**，已改正。

#### 问题 4：全量重建在拉取失败时会「用残缺数据覆盖索引」

`ListIndexSyncService.rebuildUsers` 把「返回空列表」当作**翻页终止条件**，
而所有 break 之后都会执行 `userRepository.deleteAll()`。于是：

- 用户服务抖动 → 降级返回空 → 被当成「已翻到最后一页」→ **清空 user 索引并写入前面几页的不完整数据**；
- 更糟的是这一路没有异常，`rebuildAll()` 看起来「重建成功」了。

同类隐患也在 `rebuildOrders` / `rebuildReviews`：原代码即使拿到非 200 也会继续
`deleteAll()` 然后写空集合（当前因「无 fallback + 异常进 catch」而侥幸安全，
但一旦给它们加降级就会真的清空索引）。

**已修**：
- `rebuildUsers` 用显式失败分支：任一页拉取失败 → 记 error 并 **return 0，保留旧索引**，不做任何删改；
- `rebuildOrders` / `rebuildReviews` 同样改为「先校验 Result 成功、失败即返回」，
  只有完整拉取成功才动索引。

---

## 二、本轮补齐的降级实现（共 19 个）

你已写：`product` 3 个 + `merchant` 4 个。本轮补：`order` 6 + `user` 4 + `search` 5 + `merchant` 1（改写）= 16 新增/改写。
现在 **23 个 `@FeignClient` 全部配置了 `fallbackFactory`**。

### 降级语义总原则

> **读操作可以降级，写/关键链路不能「降级成成功」。**

| 服务 | Client | 降级行为 | 理由 |
|---|---|---|---|
| order | `InventoryClient` | `deduct`/`restore` **返回失败** | 静默成功 → 超卖/少卖；失败才能让 Seata/本地事务回滚 |
| order | `MerchantInternalClient` | `changeFunds` **返回失败**；`by-user`/`funds`/`briefs` 降级为 null/空 | 资金变更静默成功 → 订单已流转但商户余额没变（资损） |
| order | `ProductInternalClient` | 返回 null / 空列表 | 下单必需输入（价格/商家），失败由调用方抛异常回滚 |
| order | `ProductSalesClient` | **降级为成功**（记录错误日志） | `salesCount` 只是统计值；为一个统计字段回滚整笔成交不划算 |
| order | `CustomerInternalClient` | 空列表 | 只读；失败时购物车/地址给业务提示而非 500 |
| order | `SearchIndexClient` | 空分页 | 列表页显示「暂无数据」，用 `log.error` 便于区分「真没数据」 |
| user | 4 个 client | 空值 / 0 / 空分页 | 全是「登录入驻判断 + 管理员仪表盘统计 + 用户列表」，避免搜索/订单抖动导致登录或仪表盘整体不可用 |
| merchant | `UserInternalClient` | 读→安全值；**写（activate/updateAccount）→ 失败** | 见上文问题 2 |
| search | 5 个 client | **全部返回「失败」** | 见上文问题 3：绝不能表达成「不存在」，否则误删索引 |

---

## 三、改动清单

**新增 Fallback（16）**
- `shopai-order/feign/`：`InventoryClientFallback`、`MerchantInternalClientFallback`、
  `ProductInternalClientFallback`、`ProductSalesClientFallback`、
  `CustomerInternalClientFallback`、`SearchIndexClientFallback`
- `shopai-user/feign/`：`MerchantInternalClientFallback`、`OrderInternalClientFallback`、
  `ProductInternalClientFallback`、`SearchIndexClientFallback`
- `shopai-search/feign/`：`ProductInternalClientFallback`、`MerchantInternalClientFallback`、
  `OrderSourceClientFallback`、`ReviewSourceClientFallback`、`UserSourceClientFallback`

**改写 Fallback（1）**：`shopai-merchant/feign/UserInternalClientFallback`（原全部 return null）

**Client 挂载 fallbackFactory（19 个接口）** + `shopai-merchant/UserInternalClient` 补挂。

**配置文件：未做任何改动**（最初误改了五个 yml 的 `feign.circuitbreaker.enabled` 并新增
`feign` 配置块，已全部回退；`shopai-product` 的配置已与未改动的构建产物
`target/classes/application.yml` 逐字一致）。

**逻辑加固（2 个类）**：
- `shopai-search/SearchSyncService`：`loadProduct` / `loadMerchant` 改三态返回，
  `syncProduct` / `syncMerchant` 区分「失败跳过」与「确认不存在才删」；
- `shopai-search/ListIndexSyncService`：`rebuildUsers` / `rebuildOrders` / `rebuildReviews`
  改为「拉取失败即放弃重建、保留旧索引」。

---

## 四、验证情况与待你确认

- 仍然是**静态审查**：沙箱运行器临时目录 `...\Temp\dsh-iAptE1` 缺失，shell 不可用，
  **未能执行 `mvn compile`**。已完成静态核对：
  1. 23 个 `@FeignClient` 全部有 `fallbackFactory`；
  2. 23 个 Fallback 类全部标 `@Component`（否则注入不到，`fallbackFactory` 解析会失败）；
  3. 用 lambda 写法的两处（`ProductSalesClientFallback` / user 的 `SearchIndexClientFallback`）
     已确认对应接口**只有一个抽象方法**，lambda 合法；
  4. `@Component` 未误标到任何 Feign 接口上（interface 上加 `@Component` 会导致启动报错）。
- **待你实测确认**：`fallbackFactory` 是否真的被触发。最直接的验证方式：
  停掉 `shopai-inventory`，再调 `GET /api/sku/list/{productId}`，
  若日志出现 `[降级触发] <InventoryClient-product> ... reason=connect_refused`
  且接口返回 200（库存为空）→ 降级链路通。

### 尚未处理、需要你决策

1. **Sentinel 与 OpenFeign `fallbackFactory` 的协同未经实测**（本文件唯一的关键未知项）。
   本项目走 Sentinel 路线（`feign.sentinel.enabled`），而 `fallbackFactory` 是否被
   Sentinel 的 `SentinelInvocationHandler` 解析，取决于该实现是否读取
   `@FeignClient` 的 `fallbackFactory` 属性。我无法在沙箱内确认（无 sources jar、shell 不可用）。
   验证方式见上一节（停掉 inventory 调 SKU 列表看日志）。
   若实测发现降级未触发，可选方案：
   - 改用 Resilience4j（`spring-cloud-starter-circuitbreaker-resilience4j`
     + `feign.circuitbreaker.enabled: true`），与 `fallbackFactory` 是官方组合；
   - 或改用 Sentinel 自己的 `blockHandler` / `fallback` 机制。
2. **降级依赖超时真正生效**：product/merchant/user 的 Feign 读超时为 5000ms
   （未改动，保持你的原值），`search` 为 30000ms。
   若发现列表偶发「空数据」，应优先排查 ES 性能或调整读超时，而不是放宽降级。
3. **`product` 的 `InventoryClientFallback.init()` 返回 null**：该方法目前无调用方
   （仅 `batchGetStock` 被使用），保留原样；若后续启用「SKU 初始化库存」，
   应改为 `Result.error(...)`（写操作不能静默成功）。

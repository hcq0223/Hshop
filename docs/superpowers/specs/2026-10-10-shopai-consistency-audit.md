# ShopAi 事务边界 / 缓存一致性 复审报告

日期：2026-10-10
范围：`D:\ShopAi\JAVA`（`shopai-*` 微服务模块；`JAVA/src` 下的旧单体不动）
起因：在群里发现 `MerchantServiceImpl.updateAccount` 的问题后，对全项目做同类问题排查。

排查的四类问题：

| 类型 | 危害 |
|---|---|
| **A. 副作用在事务提交前执行** | 未提交就删缓存 → 并发读回源读到旧值并写回；未提交就发 MQ → 消费者读到旧值，事务回滚后下游永久脏 |
| **B. 业务方法只写库、不清缓存/不同步索引** | 缓存与索引长期停留旧值（直到 TTL 到期或全量重建） |
| **C. 内部接口重复实现业务逻辑** | 复制粘贴时漏掉后续步骤（B 的常见成因） |
| **D. `registerSynchronization` 无事务守卫** | 无事务时抛 `IllegalStateException`，导致业务方法直接失败 |

---

## 一、本轮新发现并修复（共 9 处）

### A 类：副作用在事务提交前执行（2 处）

| 位置 | 问题 | 修复 |
|---|---|---|
| `MerchantServiceImpl.addMerchant` | 事务内调用跨服务 `activateUserViaFeign` → 用户服务端立即提交并同步 user 索引；若本事务回滚，商家资料没落库但账号已激活（与已修的 `CustomerServiceImpl.addCustomer` 同类） | 改到 `AfterCommitExecutor` 提交后调用 |
| `ProductSkuServiceImpl.updateSku` / `deleteSku` | 事务内直接 `redisService.deleteKey(CACHE_PRODUCT + productId)` | 改到提交后执行 |

### B 类：只写库、不清缓存/不同步索引（3 处）

| 位置 | 问题 | 影响 | 修复 |
|---|---|---|---|
| `MerchantInternalController.changeFunds` | 直接 `merchantMapper.updateById` 改资金，**既不清商家详情缓存** | `getMerchantById(userId)` / `getMerchantDetail(userId)` 会把整个 `Merchant`（含 `totalRevenue / availableBalance / frozenBalance`）缓存 300 秒 → 商家在「财务中心」/店铺资料页最长 300 秒看到旧余额 | 新增 `MerchantService.changeFunds`，在 Service 内完成累加 + 事务提交后清 `CACHE_MERCHANT` / `CACHE_MERCHANT_DETAIL` / `CACHE_MERCHANT_PUBLIC` |
| `OrderInternalController.markReviewed` | 直接 `orderMapper.markReviewed`，**不清订单详情缓存、不同步 order 索引** | ① 订单详情缓存（300s）仍显示「未评价」；② order 索引的 `isReviewed` 字段不更新 → 后台订单列表展示与筛选不对 | 新增 `OrderService.markOrderReviewed`，内聚缓存清理 + 索引同步 |
| `ProductSkuServiceImpl.addSku` / `updateSku` / `deleteSku` | 会重算 `products.price_min / price_max / images`，**但不同步 product 索引** | product 索引里的 `priceMin/priceMax` 是「按价格区间筛选商品」的依据 → 改价后按新价格区间搜不到、旧区间又能搜到；列表价格也不更新 | 三处写操作后发 `product.changed` 同步索引 |
| `CategoryServiceImpl.updateCategory` | 分类名会被冗余进 product 索引的 `categoryName / parentCategoryName`，改名后**不同步索引** | 商品列表长期显示旧分类名，索引里 `categoryName` 检索字段也陈旧 | 新增 `syncAffectedProductsIndex`：事务提交后按 categoryId 扇出同步（带 500 条阈值保护，详见第三节） |

### C 类：内部接口重复实现（2 处）

| 位置 | 问题 | 修复 |
|---|---|---|
| `UserInternalController.activate` | 直接 `userMapper.updateStatus`，漏掉 `UserService.activateUser` 里的「清缓存 + 同步 user 索引」 | 委托 `userService.activateUser` |
| `UserInternalController.updateAccount` | （上一轮已修）直接写 mapper，漏掉同样两步 | 委托 `userService.updateAccount` |

> 设计结论：**内部接口一律委托 Service 方法，不在 Controller 里重写业务逻辑。**
> 这类 bug 单看任一段代码都没问题，靠 review 很难发现；只有「一处实现」才能从根上避免。

### D 类：无事务守卫（转化 6 个方法）

以下方法原先直接 `registerSynchronization`，**无事务时抛 `IllegalStateException`**；
已全部改用带守卫的 `AfterCommitExecutor`：

- `MerchantServiceImpl`：`evictMerchantCacheAfterCommit`、`verifyMerchant`（内联）、
  `approveModification`（内联 ×2）
- `CustomerServiceImpl.evictCustomerCacheAfterCommit`
- `AddressServiceImpl.evictCacheAfterCommit`
- `ShoppingCartServiceImpl.evictCartCacheAfterCommit`
  （购物车风险最高：`getCartItemBySku` 等**非事务**public 方法也会触发缓存清理）
- `ProductSkuServiceImpl.evictSkuCacheAfterCommit`
- `CategoryServiceImpl.evictCategoryCacheAfterCommit`

顺带把「立即删 + 延迟再删」两段重复代码抽成 `evictXxxCacheNow(...)`，避免两处写法不一致。

---

## 二、全项目现状（修复后）

`TransactionSynchronizationManager.registerSynchronization` 的剩余调用点：

| 位置 | 是否安全 |
|---|---|
| `AfterCommitExecutor`（工具自身） | ✅ 含 `isSynchronizationActive()` 守卫 |
| `OrderServiceImpl.evictOrderCacheAfterCommit` / `publishOrderIndexAfterCommit` | ✅ 含守卫 |
| `UserServiceImpl.evictUserCacheAfterCommit` | ✅ 含守卫 |
| `ReviewServiceImpl.updateMerchantRatingAfterCommit` / `evictReviewCacheAfterCommit` | ✅ 含守卫 |
| `JAVA/src/main/java/com/example/demo/**`（旧单体） | ⚪ 不在本次范围，未改动 |

即：**微服务模块内已无「无守卫的 registerSynchronization」**。

`AfterCommitExecutor` 已接入 11 个文件：
order（`OrderServiceImpl` / `ShoppingCartServiceImpl` / `AddressServiceImpl`）、
product（`ProductServiceImpl` / `ProductSkuServiceImpl` / `CategoryServiceImpl`）、
merchant（`MerchantServiceImpl` / `ReviewServiceImpl`）、
user（`UserServiceImpl` / `CustomerServiceImpl`）、
inventory（`InventoryServiceImpl`）。

---

## 三、遗留项处理结果

原列的 4 项已按「能修的修、该说明的写注释」处理完毕。

### 3.1 分类改名 → product 索引 `categoryName` 陈旧（**已修，带阈值保护**）

**实现**：`CategoryServiceImpl.updateCategory` 在检测到**名称变化**或**父分类变化**时，
调用新增的 `syncAffectedProductsIndex(categoryId)`：

```
事务提交后 → 按 categoryId 探规模（LIMIT 阈值+1）
           → 未超阈值：组装索引视图 → 一次性批量 Feign upsert（1 次往返，而非 N 次 MQ）
           → 超阈值  ：打 WARN 并提示调用 POST /api/search/rebuild 全量重建
```

新增的配套接口/方法：

| 位置 | 内容 |
|---|---|
| `ProductMapper.selectIdsByCategoryId(categoryId, limit)` | 只回 ID，便于先判规模再决定是否扇出 |
| `ProductIndexService.buildByIds(ids)` | 复用既有实现组装索引视图（含店铺名/分类名） |
| `SearchIndexClient.upsertProducts(items)` | 新增 Feign 方法 |
| `SearchInternalController POST /api/search/internal/upsert/products` | 新增批量 upsert 入口 |

**阈值 `MAX_FANOUT_PRODUCTS = 500`** 的取舍（已写入代码注释）：
一次改名可能影响该分类下全部商品。若上万商品一次性组装并推送，会造成
① Feign 报文过大 ② 商品服务内存占用升高 ③ 长时间占用线程。
因此做了三重保护：先探规模、SQL 侧 `LIMIT`、只取 ID 再批量组装。

**超过阈值不静默失败**：明确 WARN 并提示走全量重建。
分类改名属低频运维操作，用一次全量重建兜底是可接受的权衡；
反过来为此实现分片/游标式增量扇出，复杂度与收益不成正比。

`deleteCategory` **刻意不做扇出**，理由写在方法注释里：
删分类前运营一般已迁走商品；即使有残留，索引里的旧 categoryName 只影响展示、
不影响筛选（按该 categoryId 仍能搜到这些商品）；在低频删除动作上触发大批索引改写，风险大于收益。

### 3.2 重置密码后 `cache:user:{id}` 可能陈旧（**已评估，刻意保持现状 + 注释**）

结论：**无需修改**。`cache:user:{id}` 存的是 `UserDto`，只有
id/username/phone/email/role/status 等展示字段，**不含任何密码字段**；
登录/改密都从数据库读 `password_hash`，因此不存在「缓存里是旧密码」的正确性问题。
即便不清，也有 `TTL_USER = 300s` 兜底。
同理不发 `user.changed`：密码不在 user 索引里（`UserDocument` 刻意不含凭据字段）。

已在 `UserServiceImpl.resetPassword` 写入上述评估结论。

### 3.3 `MerchantService.updateRating` 即时 publish（**已评估，保持现状 + 注释**）

结论：**当前时机已正确**。该方法的唯一调用方是
`ReviewServiceImpl.updateMerchantRating`，后者在 `afterCommit` 回调中调用 ——
即调用时事务**已提交**。「即时 publish」只是写法，不是时机问题。

不加 `AfterCommitExecutor` 的理由：那只会多一层「判断有无事务」的逻辑，
却改变不了当前行为（无事务时它同样立即执行）。已在方法注释中写明，
并标注**若将来被事务内直接调用必须改为 after-commit**。

### 3.4 商家资金字段的缓存失效（**本轮追加修复**）

排查「资金字段不在 ES 索引里 → 商家还能提现吗？」时发现的**新 bug**，与索引无关，属缓存问题。

`Merchant` 实体上挂着 `totalRevenue / availableBalance / frozenBalance`，
而缓存里存的是**整个 Merchant 对象**，因此这些字段会随缓存一起被读出。
经核对，「读余额」与「提现」两条链路本身**都不走缓存、直接查库**：

| 链路 | 是否走缓存 |
|---|---|
| 商家财务页 `getFinanceOverview(userId)` → `merchantMapper.selectByUserId` | ❌ 不缓存，实时查库 |
| 申请提现 `applyWithdraw` → `selectByUserId` 校验余额 | ❌ 不缓存，实时查库 |
| 提现记录 `getWithdrawRecords` → `selectByUserId` | ❌ 不缓存，实时查库 |
| 管理员提现列表 `getPendingWithdraws` | ❌ 不缓存，直接查 withdraw 表 |

所以**提现功能与余额校验是准确的，不存在「不能看提取多少钱」的问题**。

但仍有 2 处「改了资金却一个缓存都没清」（改造前遗留）：

| 位置 | 问题 |
|---|---|
| `handleWithdraw`（拒绝提现 → 退还余额） | 退还 `availableBalance` 后**未清任何缓存** → `cache:merchant:{userId}` / `detail:{userId}` / `public:{id}` 里的旧余额存活到 TTL 到期 |
| `archiveWeeklyRevenue`（周营收归零） | `totalRevenue` 归零后**未清任何缓存** → 商户详情里仍显示归零前的旧营收 |

另外两处虽清了缓存但**清得不全**：`applyWithdraw` 只删了 `CACHE_MERCHANT + userId`
（漏 `detail` 与 `public`），`changeFunds` 手写三个 key（与其它入口写法不一致，易再漏）。

**修复**：新增 `MerchantFinanceCacheEvictor`（独立 Bean，注入 `RedisService` + 调度器，无循环依赖），
把「按 userId + merchantId 清三个 key」的逻辑收口到一处：

| key | 写入方 |
|---|---|
| `cache:merchant:{userId}` | `getMerchantById(userId)` |
| `cache:merchant:detail:{userId}` | `selectDetailByUserId(userId)` |
| `cache:merchant:public:{merchantId}` | `getMerchantPublicInfo(merchantId)` |

四个资金变更入口（`applyWithdraw` / `handleWithdraw` / `archiveWeeklyRevenue` / `changeFunds`）
统一调用 `evict(userId, merchantId)`；内部在**事务提交后**执行 + 用调度器做延迟双删
（不阻塞线程、不新起线程）。

**为什么不把余额放进 ES 索引**：余额是**强一致、逐商户、按 userId 读**的私有数据。
放进 ES 会引入「资金变更 → 索引同步延迟」这一额外不一致窗口，
对「提现前校验余额」这类场景是不可接受的；而按 userId 查单条记录的数据库读本来就很快，
不需要靠索引加速。**这也是本节问题的关键区分**：
「列表/检索类」数据适合放 ES，「单条强一致资金数据」必须留在数据库。

---

## 四、验证情况

- 本轮仍是**纯静态审查 + 静态核对**：沙箱运行器临时目录
  `C:\Users\86153\AppData\Local\Temp\dsh-iAptE1` 缺失，所有 shell 命令无法执行
  （`windows-acl-run: --temp is not an existing directory`），因此**未能执行 `mvn compile`**。
- 已做的静态核对：
  1. 11 个文件均已正确 `import com.shopai.common.tx.AfterCommitExecutor`；
  2. 被移除 `TransactionSynchronization*` import 的文件已确认无残留引用
     （`ProductServiceImpl` / `ProductSkuServiceImpl` / `CategoryServiceImpl` /
     `MerchantServiceImpl` / `CustomerServiceImpl` / `AddressServiceImpl` /
     `ShoppingCartServiceImpl`）；
  3. `MerchantServiceImpl` 仍保留 10 处 `@Transactional`（import 未失效）；
  4. 新增的 `MerchantService.changeFunds` / `OrderService.markOrderReviewed` 已在接口中声明；
  5. 替换 `registerSynchronization` 代码块后逐个复查了大括号配平（`ShoppingCartServiceImpl`
     与 `CustomerServiceImpl` 曾出现多余右括号，已修正）。
- **待补**：shell 恢复后执行
  ```bash
  mvnw.cmd -q -T 1C -DskipTests clean compile
  ```
  确认全绿。注意：自「列表 ES 化改造收尾」之后的全部改动都**尚未编译验证**。

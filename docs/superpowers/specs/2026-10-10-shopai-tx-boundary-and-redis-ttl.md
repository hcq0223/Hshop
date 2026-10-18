# ShopAi 事务边界与 Redis TTL 规范化

日期：2026-10-10
范围：`D:\ShopAi\JAVA`（`shopai-common` + 各业务服务）
目的：
1. 把「事务未提交就删缓存 / 发 MQ」的副作用改为**提交后执行**；
2. 给 Redis 的所有 key 补上**合适的过期时间**，并集中管理 TTL 策略。

---

## 一、问题：副作用发生在事务提交之前

### 1.1 为什么这是 bug

在 `@Transactional` 方法内直接做两类副作用，都会破坏一致性：

| 副作用 | 事务内执行的后果 |
|---|---|
| **删 Redis 缓存** | 事务未提交就删了缓存 → 并发读立刻回源查库 → 读到**尚未提交的旧值** → 把旧值重新写回缓存 → 缓存长期是脏的（除非再删一次）。 |
| **发 MQ 消息** | 消息先于提交到达消费者 → 消费者回源查库看不到本次修改 → 把**旧值**写进索引/下游库；若本事务随后回滚，消息已发出，下游永久停留在一个从未存在过的状态。 |

### 1.2 本次修复的位置

| 文件 | 方法 | 原问题 |
|---|---|---|
| `ProductServiceImpl` | `addProduct` | 事务内 `publish("create")` |
| `ProductServiceImpl` | `updateProduct` | 事务内 `publish("update")` |
| `ProductServiceImpl` | `updateStatus` | 事务内 `publish("update")`（上下架状态会影响 ES 可搜状态） |
| `ProductServiceImpl` | `deleteProduct` | 事务内 `publish("delete")`（回滚后商品会从列表凭空消失） |
| `ProductServiceImpl` | `incrementSalesCount` | 事务内 `deleteKey` + `publish`（销量是热销榜排序字段） |
| `InventoryServiceImpl` | `deductStock` / `restoreStock` / `increaseStock` | 事务内 `deleteKey(CACHE_INVENTORY)` |
| `ReviewServiceImpl` | `addReview` | 事务内 `publishUpsert(REVIEW)` |
| `ReviewServiceImpl` | `deleteReview` | 事务内 `publishDelete(REVIEW)` |
| `CustomerServiceImpl` | `addCustomer` | 事务内调用跨服务的 `userService.activateUser(userId)`（该方法自身带事务且提交后发 MQ） |

`CustomerServiceImpl.addCustomer` 那一处尤其隐蔽：`activateUser` 自己会在提交后发 MQ 同步 user 索引，
若外层「写客户资料」的事务随后回滚，**用户账号已被激活、索引也已同步成已激活**，
而客户资料并不存在 —— 数据与索引双向不一致。改为提交后调用，使「客户资料落库」成为激活的前提。

### 1.3 新增公共工具：`AfterCommitExecutor`

`shopai-common` 新增 `com.shopai.common.tx.AfterCommitExecutor`：

```java
// 单个动作
AfterCommitExecutor.run(() -> redisService.deleteKey(key));

// 多个动作（按注册顺序执行）
AfterCommitExecutor.run(
        () -> redisService.deleteKey(key),
        () -> indexEventPublisher.publishUpsert(EntityType.ORDER, orderId));
```

语义：

- **有事务**：注册 `TransactionSynchronization.afterCommit`，提交后才执行；回滚则不执行（正确行为）；
- **无事务**：立即执行。覆盖 `@Scheduled` 自调用导致事务代理未生效、或方法本身无 `@Transactional` 的场景，
  避免为了等待一个永远不会到来的提交而丢失副作用；
- **吞异常但记日志**：此时事务已提交，抛异常既无法回滚数据、又会中断后续动作
  （例如缓存已删但索引没同步），因此让每个动作各自尽力完成。

原先各服务各写一遍 `registerSynchronization(new TransactionSynchronization(){...})` 模板代码，
现已统一收敛到该工具；`ProductServiceImpl` 的缓存清理也改为复用它。

---

## 二、Redis TTL：无一遗漏

### 2.1 修改前的问题

`shopai-common` 的 `RedisService` 里有两个真正的「没有过期时间」的写操作：

| 方法 | 问题 |
|---|---|
| `cartPut(customerId, skuId, json)` | 只 `HSET`，**从不设过期** → 用户加过商品后即使再也不回来，`cart:{customerId}` 这个 Hash 永久占内存（僵尸购物车）。 |
| `restoreStock(skuId, qty)` | `INCRBY` 在 key 已过期后会**新建一个永不过期的 key**，且值是「回补量」而非完整库存，语义已失真却永久驻留。 |

其余（`setCache` / `addTokenBlacklist` / `recordLoginFail`）本来就有 TTL。

### 2.2 集中式 TTL 策略

在 `RedisService` 中新增策略常量与「key 前缀 → TTL」登记表，并提供反查方法：

```java
public static long ttlOf(String key)   // 按最长前缀优先匹配，未登记时返回 TTL_DEFAULT
```

**最长前缀优先**是有意设计的：`cache:review:product:1:stats` 同时匹配
`cache:review:product:` 与 `cache:review:product::stats`，取更长的那个，
从而让「评分统计」用 300s 而不是评价列表的 120s。

TTL 取值理由（「变更频率 × 可容忍的陈旧时长」）：

| 数据 | TTL | 理由 |
|---|---|---|
| 统计/仪表盘 | 60s | 聚合结果，变化快、短时陈旧可接受 |
| 列表类 / 用户动态数据 | 120s | 要求较快反映最新状态 |
| 商品/订单/用户/客户详情、库存、评价统计 | 300s | 一般业务实体 |
| 分类、SKU | 600s | 变化不频繁且是高频热点 |
| 商家公开信息 | 1800s | 几乎不变 |
| 购物车 | 30 天 | 用户资产类，要留够回访时间 |
| 秒杀库存 | 1 小时 | 与秒杀活动时长对齐 |

> **重要澄清**：TTL 是「最终一致性的上限」，不是让用户等这么久才看到新数据。
> 每个写操作都会主动删对应 key（点失效），TTL 只是兜底 ——
> 防止删漏、删失败、或进程崩溃导致永久脏数据。

### 2.3 防漏保险

`setCache` 现在会兜底 TTL：

```java
public void setCache(String key, Object value, long ttlSeconds) {
    long ttl = resolveTtl(key, ttlSeconds);   // ttlSeconds <= 0 → 按 key 查 ttlOf(key)
    ...
}
```

即**即使某个调用点漏传 TTL，也不会写出永不过期的缓存**，而是按 key 自动取建议值。

同时把散落的 `"__NULL__"` 字面量收敛为 `NULL_MARKER` 常量（空值标记的 TTL 上限从 60s 改为
`TTL_VERY_SHORT`，语义一致）。

---

## 三、复盘：`MerchantServiceImpl.updateAccount` 的时机是否也有问题？

结论：**时机本身没问题，但这段代码有两个别的问题**（其中一个是我自己引入的重复实现造成的）。

### 3.1 时机为什么是对的

```java
try { query = userInternalClient.getUser(userId); }            // ① 远程读
...
try { rows = userInternalClient.updateAccount(...); }           // ② 远程写
...
redisService.deleteKey(RedisService.CACHE_MERCHANT + userId);   // ③ 清本地缓存
```

关键在于：**Feign 调用返回时，用户服务端的写已经提交了**。
用户服务的 `users` 更新是在它自己的事务里完成的，②返回即代表已落库提交。
因此③「清缓存」发生在「数据已提交」之后，不存在
「事务未提交就删缓存 → 并发读回源读到旧值」的风险 —— 与本轮修复的那批问题**性质不同**。

### 3.2 问题一：`@Transactional` 是误导性的（已移除）

本方法对**本地库没有任何写操作**，只有两次 Feign 调用 + 清本地 Redis key。
加 `@Transactional` 会让人以为「这些操作是原子的、能一起回滚」，但事实是：

- Feign 调用在**远端**执行并立即提交，本服务的事务回滚**无法**撤销它
  （真正的跨服务原子性需要 Seata 全局事务，本项目当前未启用）；
- 一个空事务白白占着数据库连接。

已移除该注解，并在方法注释里写明原因。

### 3.3 问题二：内部接口重复实现，漏了「清缓存 + 同步索引」（已修复）

这是**真正的 bug**，而且是我在「独立库」改造时引入的：

- `UserServiceImpl.updateAccount(...)` 是账号更新的**正牌实现**，
  它做全了：更新 users → 清用户缓存 → 发 user 索引同步事件；
- 但我新写的 `UserInternalController.updateAccount(...)` **没有复用它**，
  而是自己写了一遍 mapper 更新逻辑，结果两步都漏了。

后果：商家通过 `/api/merchant/me/account` 改完账号后，
`cache:user:{id}` 里的旧值最长 **300 秒**才过期，
且 **user 索引不会更新** —— 后台用户列表「按新用户名/手机号搜不到这条用户」，
直到有人手动触发全量重建。

修复方式：让内部接口**委托 Service 方法**执行，并给 Service 方法补上索引同步事件：

```java
// UserInternalController
userService.updateAccount(userId, username, phone, email);

// UserServiceImpl.updateAccount —— 补上索引同步
AfterCommitExecutor.run(
        () -> indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.USER, userId));
```

这样从根上避免「同一业务两处实现、其中一处漏步骤」——
这类问题靠 code review 很难发现，因为两段代码单看都没错。

### 3.4 顺带加固

`MerchantServiceImpl.updateAccount` 里清缓存的两个 `deleteKey` 改用
`AfterCommitExecutor.run(...)` 包裹：当前无事务 → 立即执行（行为不变）；
将来若有人给本方法加上事务，会自动延后到提交后，避免重新引入本轮的 bug。

---

## 五、验证情况

- 本次改动为**纯静态审查**，未能执行 `mvn compile`：
  构建过程中沙箱的运行器临时目录 `C:\Users\86153\AppData\Local\Temp\dsh-iAptE1`
  被移除，导致所有 shell 命令无法执行（`windows-acl-run: --temp is not an existing directory`）。
  这是环境问题，与本仓库代码无关。
- 已做的静态核对：
  1. `RedisService` 中静态登记表引用的 **28 个 key 常量**全部存在于同类（已逐一比对）；
  2. `ttlOf` / `resolveTtl` / `NULL_MARKER` 均已定义，无重名、无重复的静态初始化块；
  3. `ProductServiceImpl` 已无 `TransactionSynchronization*` 残留引用（import 已清理）；
  4. 五个文件（product / inventory / merchant / user service + user internal controller）
     均已正确 import `AfterCommitExecutor`；
  5. `MerchantServiceImpl` 移除 `updateAccount` 的 `@Transactional` 后，该注解在本类仍有 10 处使用，
     import 未变成无用；
  6. `UserInternalController` 现依赖 `UserService`，`shopai-user` 中该接口只有一个实现
     （`@Service("userService") UserServiceImpl`），注入无歧义、无循环依赖。
- **待补**：shell 恢复后执行一次
  ```bash
  mvnw.cmd -q -T 1C -DskipTests clean compile
  ```
  确认全绿。**注意**：自上次编译通过（列表 ES 化改造收尾）之后的所有改动都还未编译验证过，
  包括本轮的事务/TTL 改动与 `updateAccount` 重构。

---

## 六、仍未覆盖（如需可继续）

1. `MerchantServiceImpl.updateRating` 自身不带 `@Transactional`，其 `publish` 为即时发出。
   它由 `ReviewServiceImpl.updateMerchantRating`（已在 afterCommit 中调用）触发，当前时机正确；
   但若将来被事务内直接调用，需要一并改为 after-commit。
2. `CustomerServiceImpl` 的客户列表缓存仍走 Redis（`cache:customer:list:{hash}` + `deleteByPrefix`），
   属此前未纳入 ES 迁移范围的列表，失效粒度仍偏粗。
3. 秒杀链路（`InventoryServiceImpl.seckill`）中 Redis 扣减与数据库落库是「异步 + 补偿」模型，
   不是本地事务，本次未改动；若要更强一致需引入消息表/事务消息。

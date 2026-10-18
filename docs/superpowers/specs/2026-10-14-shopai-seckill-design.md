# shopai-seckill 秒杀服务设计（2026-10-14）

## 一、需求与已确认的设计选择

需求：新建秒杀服务，设计一张秒杀商品库存表（复用 SKU / 商品信息），并把框架搭起来。

三个关键决策（已与需求方确认）：

| 决策点 | 选择 | 影响 |
| --- | --- | --- |
| 库存模型 | **C. 完全独立，不与主库存联动** | `seckill_stock` 是一份独立额度，`inventory` 表不参与。代价见 2.2 |
| 框架范围 | 全量 | 建表 + 预热 + Lua 扣减 + MQ 异步下单 + 本地消息表 + 定时补偿 + 限流 + 管理端 CRUD |
| 落地模块 | **新建 `shopai-seckill`（8087）** | 与现有 7 个服务平级；`shopai-inventory` 里原有的秒杀逻辑不再承担此职责 |

---

## 二、数据模型

建表脚本：`JAVA/docs/sql/seckill.sql`

### 2.1 秒杀商品库存表（核心表）

`seckill_stock` —— 一行 = 某个活动里某个 SKU 的独立库存额度。

| 字段组 | 字段 | 说明 |
| --- | --- | --- |
| 关联 | `activity_id` / `sku_id` / `product_id` / `merchant_id` | `(activity_id, sku_id)` 唯一索引 |
| 独立额度 | `seckill_price` / `origin_price` / `total_stock` / `available_stock` / `locked_stock` / `sold_stock` | `origin_price` 是创建时快照，只做划线价 |
| 并发 | `version` | 乐观锁，用于活动编辑这类低频冲突 |
| 限购 | `per_user_limit` / `sort` | 冗余自活动，可单独调某 SKU |
| 预热 | `preheated` / `preheat_time` | 防重复预热覆盖已扣减库存 |

**SKU 与商品信息不落库**：规格（颜色/尺码）、图片、商品名、店铺名一律经 Feign 调 `shopai-product` 实时取。原因：这些会变（改名、换图、调价），冗余进来就会出现「秒杀页还挂着两周前的旧图」。而 `product_id` / `merchant_id` 是**归属关系、不会变**，所以冗余下来减少一次跨服务调用。

其余四张表：

- `seckill_activity` —— 活动（时间窗、状态、限购、冗余统计）
- `seckill_record` —— 抢购记录，`(activity_id, user_id)` **唯一索引 = 一人一单的最终兜底**
- `seckill_order` —— 秒杀订单**流水视图**（真实订单在 `shopai-order.orders`）
- `seckill_message` —— **本地消息表**，异步下单最终一致性的载体

### 2.2 库存模型 C 的代价（必须让运营知道）

选择「完全独立」后，同一个 SKU 的秒杀额度与常规库存**互不知情**：

- 常规下单照常扣 `inventory`，秒杀照常扣 `seckill_stock`；
- 两边都可能把货卖光 → 极端情况会「秒杀成功但仓库没货」；
- **要求**：某 SKU 参与秒杀时，运营必须为秒杀留出备货，并相应减少常规可售库存；
  即保证 `seckill_stock.total_stock ≤` 该 SKU 留出的备货量。

**另一个必须知道的连带效应**：异步下单走的是订单服务的
`createSeckillOrder`，它**不扣主库存**（已刻意如此，否则一笔交易扣两处）。
但这也意味着**主库存不会被秒杀消耗**。如果运营把 `seckill_stock.total_stock`
设得比实际备货多，超卖的部分在发货环节才会暴露。这是模型 C 的固有属性，
不是实现缺陷 —— 要根治需改成「活动创建时从主库存预占」
（`inventory.locked_stock += 额度`，活动结束/售罄回补）。

---

## 三、抢购链路

### 3.1 主流程

```
0. 校验活动状态 + 时间窗        失败 → 拒绝（不碰任何存储）
1. Redis Lua 原子扣减 + 一人一单  失败 → 拒绝（库存不足/已抢过/超限购）
                                 成功 ↓
2. 本地事务：写抢购记录 + 本地消息  失败 → 回补 Redis（关键！）
                                 成功 ↓
3. 投递 MQ（尽力而为）            失败 → 不回滚，交给重投任务
                                 成功 ↓
4. 返回「排队中」，前端轮询 /result/{activityId}
```

### 3.2 三个「为什么」

**为什么第 2 步失败必须回补 Redis**：Redis 扣减已生效，本地事务失败而不回补，
这部分库存就凭空消失了（Redis 里扣了、数据库里没记录，永远不会有人来还）。

**为什么第 3 步投递失败不回滚第 2 步**：本地消息表已落库，重投任务一定会投出去。
回滚反而会造成「消息表里没有、Redis 库存已扣」的最坏情况。

**为什么必须用 Lua 而不是 GET + DECRBY**：两次网络往返之间会被插入，
出现「两个请求都读到 1 → 都判定有货 → 都扣 → 库存变成 -1」的超卖。
Lua 在 Redis 单线程内一次执行完，判定与扣减之间没有插入点。

### 3.3 防超卖的两道闸门

| 闸门 | 位置 | 作用 |
| --- | --- | --- |
| 第一道：Redis Lua | `SeckillRedisService.deduct` | 挡住 99%+ 并发，扣减与判定原子 |
| 第二道：DB 条件更新 | `SeckillStockMapper.deductAvailable` | `WHERE available_stock >= #{qty}`，Redis 丢数据/未预热时兜底 |

任一道失效另一道仍能兜住。另外 `seckill_record` 的 `(activity_id, user_id)`
唯一索引是「一人一单」的最后防线 —— Redis 的 Set 可能因主从切换、过期而丢数据，
数据库约束不会。

### 3.4 降级路径

Redis 里没有库存 Key（忘记预热 / Redis 重启 / TTL 设短了）时，
`SeckillBuyService.fallbackToDatabase` 直接扣数据库，并**顺手补一次预热**
让后续请求回到正常路径。并发能力差很多，但可用性优先 —— 没预热的活动也有人来抢。

---

## 四、最终一致性

### 4.1 本地消息表

矛盾在于：Redis 扣减**无事务**，订单创建是**远程调用**（可能超时/失败/成功但响应丢失）。
把「写抢购记录」与「写待投递消息」放进**同一个本地事务**，之后由定时任务不断重投，
就保证这笔秒杀一定走向「成单」或「重试耗尽后回补库存」两个终态之一。

### 4.2 投递是「至少一次」→ 靠订单号幂等

- 秒杀侧用固定订单号 `SK + seckillRecordId`；
- 订单服务按 `orderNo` 查重，重复投递返回同一笔订单，并回 `idempotentHit=true`；
- 秒杀侧据此区分「真的建了单」与「命中幂等」，**只有前者才累加销量**。

### 4.3 订单服务回调（双向调用）

订单创建成功后，订单服务回调 `POST /api/seckill/internal/order-created` 落账
（记录置成功、写流水、锁定库存转已售、累加销量、本地消息置 done）。

**回调失败必须吞掉，绝不能让订单创建回滚** —— 订单已建好，回滚只会让用户既没订单、
库存也白扣。回调失败时秒杀侧消息仍是 `sent`，会被重投；重投时订单服务按 `orderNo`
幂等返回同一笔订单，于是再次触发回调直到成功。这就是「允许短暂不一致，不允许丢单」。

### 4.4 时序约束（改配置时务必复核）

> `seckill.order-timeout-seconds`（默认 900s）
> **必须明显大于** `seckill.message-max-retry` × 退避间隔之和。

否则会出现最坏情况：消息还在正常重试中，超时任务先把库存回补了；
之后消息终于投递成功、订单建出来了，而库存已被还回去 → **超卖**。
退避间隔上限被压在 `MAX_BACKOFF_SECONDS = 60s`，就是为了让这个约束可控。

### 4.5 回补的顺序纪律

`SeckillCompensateService.giveUp` 与 `restoreTimeoutRecords` 都是
**先用带条件的 `markFailed` 改状态，只有返回行数 > 0 才回补库存**。

因为 `markFailed` 带 `WHERE status = 'pending'`：
返回 0 说明这笔已被别的流程改成 `success`，此时若还回补库存，就会
「订单已存在 + 库存被还回去」双重错误。

### 4.6 事务边界：为什么 `SeckillRecordWriter` 是独立 Bean

「写抢购记录」与「写本地消息」必须在**同一个数据库事务**里：

- 只写记录不写消息 → 库存扣了，但没有任何人去创建订单，用户永远等不到；
- 只写消息不写记录 → 丢失幂等依据（消息的 `msgId` 就是记录 ID），重复投递会建出多笔订单。

**坑点**：Spring 的 `@Transactional` 基于代理，**同一个类内部的方法自调用不经过代理，
注解会静默失效** —— 代码看起来有事务，实际没有，直到线上出现「记录写了、消息没写」才暴露。

因此这段写入被抽成独立 Bean `SeckillRecordWriter`，由 `SeckillBuyService` 注入调用，
调用经过代理、事务真正生效。

另外，这也意味着**不能**把写入拆成「本类方法 + 调 `SeckillMessageService.createPending`」：
后者自身也带 `@Transactional`，在本事务内调用会形成嵌套事务语义，
一旦它回滚而记录已提交，又会出现「只写记录不写消息」。
所以 `SeckillRecordWriter` 直接使用 `SeckillMessageMapper`，让两条插入由
**同一个 `@Transactional` 方法**统一管控。

### 4.7 阻塞窗口

第 1 步（Redis 扣减）与第 2 步（本地事务）之间，服务实例若崩溃，
会留下「Redis 已扣、DB 无记录」的不一致 —— 这部分额度没人知道需要回补。
当前靠 Key 的 24 小时 TTL 自然回收，**但活动进行中的这 24 小时内会少卖**。
彻底解决需要「Redis 扣减流水 + 对账任务」，已列入第八节 TODO。

---

## 五、模块结构

```
JAVA/shopai-seckill/                     端口 8087
├── pom.xml                              无 Seata（秒杀无分布式事务，见下）
└── src/main/
    ├── java/com/shopai/seckill/
    │   ├── SeckillApplication.java       @EnableScheduling（4 个定时任务）/ @EnableFeignClients
    │   ├── config/SeckillProperties.java seckill.* 业务参数
    │   ├── constant/
    │   │   ├── SeckillStatus.java        draft/pending/running/ended/offline
    │   │   ├── SeckillRecordStatus.java  记录与消息状态
    │   │   └── SeckillRedisKeys.java     Key 常量 + 3 段 Lua 脚本
    │   ├── entity/                       5 个实体
    │   ├── mapper/ + resources/mapper/   5 个 Mapper + XML（原子 SQL 都在这里）
    │   ├── dto/                          SeckillItemVO / SeckillActivityVO / SeckillResultVO
    │   ├── feign/                        商品 / 用户(客户) / 商家 / 订单 4 组 + Fallback
    │   ├── mq/                           SeckillOrderMessage + 配置 + 投递器
    │   ├── service/
    │   │   ├── SeckillRedisService       预热 / Lua 扣减 / 回补 / 结果缓存
    │   │   ├── SeckillInfoService        本地库存 + Feign 商品信息 组装视图
    │   │   ├── SeckillActivityService    活动与额度 CRUD、发布/下线、价格校验
    │   │   ├── SeckillBuyService         ← 抢购核心
    │   │   ├── SeckillMessageService     本地消息表
    │   │   ├── SeckillCompensateService  ← 预热/流转/重投/超时回补
    │   │   └── SeckillOrderResultService 成单落账
    │   ├── controller/                   买家端 / 商家端 / 管理端 / 内部接口
    │   └── task/SeckillScheduledTask.java 4 个定时任务
    └── resources/application.yml
```

### 5.1 为什么不需要 Seata

秒杀库存扣减在 Redis（无事务），订单创建在订单服务（远程），
两者本就不在一个本地事务里，**分布式事务也包不住 Redis 操作**。
一致性改由「本地消息表 + 定时补偿」保证，所以本模块刻意不引入 Seata。

### 5.2 为什么不用公共库的 `RedisService.deductStock`

`RedisService.deductStock(skuId, qty)` 按 **skuId** 分片（key = `sku:stock:{skuId}`），
是单一库存模型。而秒杀是「一个 SKU 可同时参加多个活动、每个活动一份独立额度」，
必须按 **stockId**（`seckill_stock.id`）分片。两者 key 维度不同，
硬套会让同一 SKU 的多个活动互相扣对方的库存。
公共库那套继续留给 `shopai-inventory` 常规库存用。

### 5.3 库存值用 String 序列化

Lua 里 `tonumber(redis.call('GET', key))` 直接算。若值被 JSON 包装成带引号的字符串，
lua 解析失败返回 -2，表现为「库存永远读不到」，非常难查。故用 `StringRedisTemplate`。

---

## 六、对现有服务的改动

| 文件 | 改动 |
| --- | --- |
| `shopai-common` `InternalApiPaths` | 加 `SECKILL_INTERNAL`、`SERVICE_SECKILL` |
| `shopai-common` `MqConstants` | 加 `ROUTING_SECKILL_ORDER_CREATE`、`QUEUE_SECKILL_ORDER_CREATE` |
| `shopai-common` `OrderInfo` | 加 `orderNo` 字段（秒杀侧要回写订单号） |
| `shopai-common` `dto/internal/seckill/` | 新增 `SeckillOrderCreateRequest`（含 `buildOrderNo` 幂等号规则）、`SeckillOrderResult` |
| `shopai-order` `OrderService(+Impl)` | 加 `createSeckillOrder`：**不扣主库存**、成交价用秒杀价、按 `orderNo` 幂等 |
| `shopai-order` `OrderInternalController` | 加 `POST /seckill/order`，并在建单成功后回调秒杀服务 |
| `shopai-order` `feign/SeckillInternalClient(+Fallback)` | 新增回调客户端 |
| `shopai-gateway` `application.yml` | 加 `/api/seckill/**` 路由 + 加 `block-internal-seckill` 拦截 |
| `shopai-gateway` `JwtAuthGlobalFilter` | 加 `SECKILL_ADMIN_PATH_PREFIX`；`MERCHANT_SELF_PATHS` 加 `/api/seckill/merchant` |
| `JAVA/pom.xml` | `<modules>` 加 `shopai-seckill` |
| `shopai-inventory` | **未改动**。其中 `seckill`/`preheatStock`/`getSeckillStock` 三个方法现在无人调用，属遗留死代码，建议后续清理（本次未动，避免影响其对外 Feign 契约） |

### 6.1 路由规划

| 路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/seckill/list` | 公开 | 进行中/即将开始的活动 |
| `GET /api/seckill/detail/{id}` | 公开 | 活动详情（登录后含「我是否已抢过」） |
| `POST /api/seckill/buy` | 需登录 | 抢购（Sentinel 资源名 `seckillBuy`） |
| `GET /api/seckill/result/{activityId}` | 需登录 | 轮询抢购结果 |
| `GET /api/seckill/my` | 需登录 | 我的秒杀记录 |
| `/api/seckill/merchant/**` | **merchant 角色** | 商家端活动管理 |
| `/api/seckill/admin/**` | **admin 角色** | 管理端监管与手动补偿 |
| `/api/seckill/internal/**` | **网关 503** | 仅供订单服务 Feign 回调 |

> 注意 `POST /api/order/create` 等既有路由未做任何改动。

### 6.2 安全设计要点

1. **商家端不信任前端 merchantId**：每次请求都经 `X-User-Id → Feign 商家服务`
   换出可信 merchantId，后续查询/写入一律以它为条件。
2. **订单归属同理**：不让前端同时传 `activityId` 与 `stockId`（可组合出
   「A 的活动 ID + B 的 stockId」绕过按活动维度的一人一单），活动 ID 由服务端反查。
3. **内部接口必须拦截**：`/api/seckill/internal/order-created` 若不拦，
   任何人都能伪造「订单已创建」把抢购记录改成成功。故加了 `block-internal-seckill`。
4. **价格合法性在服务端校验**：创建额度时经商品服务核对真实价格，
   拒绝「秒杀价 ≥ 日常价」，并校验商品归属当前商家（否则商家 A 能把商家 B 的商品挂进自己的秒杀）。
   → 这也是商品服务 Feign **降级必须返回失败**的原因：返回默认价会放过非法活动。
5. **预热不可重入**：活动 `running` 时拒绝重新预热，因为重新预热会把已扣减的库存
   「还原」回 `total_stock`，直接超卖。

---

## 七、降级策略一览

| Feign 客户端 | 降级返回 | 原因 |
| --- | --- | --- |
| `ProductInternalClient` | **失败** | 返回默认价会放过「秒杀价 > 日常价」的非法活动；返回空会让详情页误显示「商品已下架」 |
| `MerchantInternalClient` | **失败** | 返回 null 会被理解成「你不是商家」，引发大量客服工单 |
| `OrderInternalClient` | **失败** | 返回成功会让用户以为下单成功却根本没有订单 —— 最严重的一类错误 |
| `CustomerInternalClient` | **成功 + 空列表** | 空 = 「暂时查不到映射」，可安全重试（由超时任务补解析），不该判抢购失败 |

同一句「降级返回空」，在商品侧是「商品不存在」（危险），
在客户映射侧是「暂时查不到」（可重试）—— **语义完全不同，所以策略相反**。

---

## 八、前端实现（2026-10-14 补充）

### 8.1 新增页面

| 文件 | 路由 | 权限 | 说明 |
| --- | --- | --- | --- |
| `Vue/src/views/SeckillList.vue` | `/seckill` | 公开 | 活动列表、倒计时、抢购进度条、售罄蒙层 |
| `Vue/src/views/SeckillDetail.vue` | `/seckill/:id` | 公开浏览 | 选品 + 数量 + 地址 + 抢购，含结果轮询弹层 |
| `Vue/src/views/MySeckill.vue` | `/my-seckill` | 需登录 | 我的秒杀记录与成单结果 |
| `Vue/src/views/MerchantSeckill.vue` | `/merchant/seckill` | merchant/admin | 活动 CRUD、发布/下线、商品额度管理 |
| `AdminDashboard.vue` 新增「秒杀监管」页签 | `/admin` | admin | 活动监管、抢购记录排查、异步消息、手动补偿 |

导航入口：`AppHeader.vue` 顶部加「限时秒杀」（用红色区分），用户下拉菜单按角色分别加
「我的秒杀」/「秒杀活动」。

### 8.2 前端必须处理的三个后端约定

1. **倒计时以服务端时间为准**：详情接口返回 `serverTime`，前端算出
   `偏移量 = 服务端时间 - 本地时间`，之后所有倒计时都用 `本地时间 + 偏移`。
   直接用本地时钟的话，用户系统时间差几分钟就会看到错误的开抢倒计时。
   （列表接口不返回 `serverTime`，那里只能接受本地时钟的精度。）

2. **售罄与否以后端 `soldOut` 为准**，前端不自己拿 `availableStock` 比大小 ——
   活动进行中该值是后端从 Redis 取的真实余量，前端缓存里的可能是旧的。

3. **`infoMissing` 要显式提示**：商品服务拉取失败时 `productName`/`skuAttrs` 为空，
   此时显示「商品信息加载失败」而不是渲染出空白商品名 ——
   后者会让用户以为活动里挂的是无效商品。

### 8.3 抢购交互

抢购是「快速返回 + 轮询」两段式：`POST /seckill/buy` 只做到 Redis 扣减就返回
`status=pending`，前端弹层显示「正在生成订单…」并按 1.5 秒间隔轮询
`GET /seckill/result/{activityId}`，拿到 `success` 显示订单号并给「去支付」入口，
拿到 `failed` 显示具体原因。

- 轮询上限 60 次（约 90 秒）；超时后提示到「我的秒杀」查看，避免弹层永久卡住。
- 单次轮询失败不中断轮询（网络抖动很常见）。
- **抢购失败是业务结果而非异常**，后端仍返回 HTTP 200 + `status=failed`，
  前端按 `status` 展示原因。

**一个细节**：抢购后刷新余量时调用 `fetchDetail(false)` 而不是 `fetchDetail()`。
带 `autoSelect` 的刷新会把商品重选一遍、数量重置成 1，而弹层还开着，
用户会看到数量莫名变化。

### 8.4 本次前端联带修复的后端问题

抢购接口原先**不校验 `addressId`**。前端做了必填校验，但接口层面仍缺：
不拦的后果是库存被扣、消息投出去，直到异步建单时才发现缺地址，
接着重试耗尽 → 回补库存 → 用户最终看到「下单失败」，
等于白扣一次库存还占用了重试资源。已在 `SeckillController.buy` 补上入口校验。

---

## 九、仍需完善的 TODO

1. **Sentinel 限流规则未落库**：`SeckillRedisService` 的限流资源名约定为
   `seckillBuy`，但**抢购接口上尚未真正添加 `@SentinelResource` 注解**
   （目前只有注释说明），且 QPS 阈值需在 Sentinel 控制台配置并持久化到 Nacos。
   接入时两件事都要做，否则规则无从绑定。
2. **地址选择时机**：当前 `addressId` 由抢购请求传入，前端在详情页强制选择。
   若要「抢到后再选地址」，需额外一个「补地址触发下单」的接口（属后续演进）。
3. **`shopai-inventory` 的秒杀遗留方法**未清理（见第六节表格）。
4. **分布式调度**：现用 `@Scheduled`，多实例下靠幂等保证结果正确但会重复执行。
   数据量上来后建议换 XXL-JOB / ElasticJob。
5. **Redis 与 DB 库存的对账任务**：目前若 Redis 回补失败只打 error 日志并提示人工介入
   （`SeckillRedisService.restore` 的 catch），缺少定期对账把
   `seckill_stock.available_stock` 与 Redis 值拉齐。高并发场景建议补上。
6. **未做真实联调**：Java 侧
   `mvnw -T 1C -DskipTests clean compile`（全 10 模块）**BUILD SUCCESS**；
   前端 `npm run build` 通过（141 模块，4 个新页面均产出 chunk）。
   但后端服务与 Nacos / Redis / RabbitMQ / MySQL **均未启动**，
   实际联调需重点验证：跨域、`serverTime` 校准、轮询拿到 `success` 的完整链路。

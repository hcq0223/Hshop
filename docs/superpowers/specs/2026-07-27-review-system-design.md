# 商品评论与申诉系统 — 设计规格

日期：2026-07-27

## 一、概述

为 ShopAi 平台增加完整的商品评论与申诉系统。顾客在订单收货后可对整个订单评价，评价影响商家评分；商品详情页展示分页加载的评论列表；管理员可管理评论和申诉。

## 二、数据模型

### 2.1 Review 表变更

现有 `reviews` 表从"按订单项评价"改为"按订单评价"：

- `order_item_id` (BIGINT) → `order_id` (BIGINT NOT NULL) — 一个订单一条评价
- 新增 `merchant_id` (BIGINT NOT NULL) — 所属商家，用于计算商家评分
- `customer_id`, `product_id`, `rating` (INT 1-5), `content` (TEXT), `images` (JSON), `create_time` (DATETIME) 保留
- `order_id` 加唯一索引：一个订单只能评价一次

### 2.2 商家评分

- `merchants.rating` = 该商家所有评价 rating 的平均值，精确到小数点后 1 位
- 取值范围 [0, 5]
- 每次新增评价时异步更新；评价被管理员删除时同步更新

### 2.3 申诉表 review_complaints（新增）

```sql
CREATE TABLE review_complaints (
    id BIGINT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending / rejected / approved
    admin_note VARCHAR(500),
    create_time DATETIME NOT NULL,
    update_time DATETIME,
    UNIQUE KEY uk_review_pending (review_id, status)  -- 每条评论最多一条待处理申诉
);
```

- status='approved' 时视为删除该评论
- status='rejected' 后商家可再次发起新申诉

## 三、后端 API

### 3.1 ReviewController — 现有改动

| 变更 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 改写 | POST | `/api/review/add` | 参数改为 `orderId`（替代 `orderItemId`），增加 `merchantId`；校验：该订单未评价过、订单属于该顾客、订单状态为 received；写入后异步更新 `merchants.rating` |
| 改写 | GET | `/api/review/product/{productId}` | 增加分页参数 `page`(默认1) / `size`(默认10)；返回 `{ list, total, hasMore }`；支持 orderBy 控制排序 |
| 新增 | DELETE | `/api/review/{id}` | 仅管理员可调用；删除评论同时删除关联申诉 |
| 新增 | GET | `/api/review/order/{orderId}` | 查某订单的评价，供前端判断是否已评价 |

### 3.2 ReviewComplaintController — 新增

Base path: `/api/review/complaint`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/add` | merchant | 商家申诉某条评论，校验 merchantId 是否属于该评论商品所属商家 |
| GET | `/list` | admin | 查所有申诉列表，参数 `status` 可筛选 pending/rejected/approved |
| PUT | `/{id}/reject` | admin | 驳回申诉，body 含 `adminNote` |
| PUT | `/{id}/approve` | admin | 同意申诉→删除对应评论+标记 approved |
| GET | `/my` | merchant | 商家查自己的申诉记录 |

### 3.3 商家评分更新逻辑

在 `ReviewService` 中：
- `addReview()` 提交后调用 `updateMerchantRating(merchantId)`：查询该商家所有 review 的 rating 平均值，写入 `merchants.rating`
- 管理员删除评论后同样触发此逻辑

## 四、前端

### 4.1 订单评价入口

**位置**：顾客订单列表（OrderList.vue）和订单详情（OrderDetail.vue）

- `received` 状态的订单显示"去评价"按钮
- 已评价的订单显示"已评价"（灰色不可点击），通过调用 `GET /api/review/order/{orderId}` 判断
- 点击"去评价"弹出评价弹窗（Modal）：
  - 5 星点击打分
  - 文字评价输入框（可选）
  - 提交 `POST /api/review/add`

### 4.2 商品详情页评论区

**位置**：ProductDetail.vue（替换现有简单评价展示）

- 顶部统计栏：平均分（大号数字）+ 星级分布柱状图（5条横条，显示各星级数量）
- 评论列表：每条显示用户昵称/头像、星级、时间、内容文字
- 滚动加载：每页 10 条，触底自动加载下一页；到底显示"— 没有更多评论了 —"
- 管理员登录时每条评论右侧显示红色删除按钮
- 商家登录时对自己商品的评论右侧显示申诉按钮（已申诉的灰色不可点）

### 4.3 管理后台评论管理

**位置**：AdminDashboard.vue 新 tab

- 侧边栏「评论管理」：评论列表（排序、筛选、删除）
- 侧边栏「评论申诉」：申诉列表，status 筛选（pending/rejected/approved），操作驳回/通过

### 4.4 路由

无需新增路由，所有功能集成在现有页面中。

## 五、数据库迁移计划

1. 在 `JAVA/sql/` 目录创建 `review_upgrade.sql`：
   - `ALTER TABLE reviews DROP COLUMN order_item_id, ADD COLUMN order_id BIGINT NOT NULL, ADD COLUMN merchant_id BIGINT NOT NULL, ADD UNIQUE INDEX idx_order_id (order_id)`
   - `CREATE TABLE review_complaints (...)`

## 六、实现顺序

1. 数据库迁移（SQL）
2. 后端：Review 实体改动 + ReviewMapper/XML 改动
3. 后端：ReviewService 改写入逻辑 + 商家评分计算
4. 后端：ReviewController 路由改造 + ReviewComplaintController 新增
5. 前端：商品详情页评论区改造（分页加载）
6. 前端：订单评价入口（弹窗 + 提交）
7. 前端：管理后台评论管理 + 申诉管理 tab
8. 前端：商家商品评论申诉入口

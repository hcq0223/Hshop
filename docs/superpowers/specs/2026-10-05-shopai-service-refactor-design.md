# ShopAi Service 层规范化改造（MyBatis-Plus 接口 + Impl）设计文档

日期：2026-10-05
范围：`D:\ShopAi\JAVA` 微服务工程

## 1. 背景与问题

当前各业务服务的 Service 是普通 `@Service` 类，直接注入 `XxxMapper`（`@Autowired`），
因此无法使用 MyBatis-Plus（MP）`IService`/`ServiceImpl` 上的便捷方法
（`list` / `getById` / `save` / `updateById` / `removeById` / `page` / `lambdaQuery` / `lambdaUpdate`）。

问题有两个根因：

1. **Service 未继承 `ServiceImpl`**：MP 的便捷方法定义在 `IService`/`ServiceImpl` 上，未继承自然无法调用。
2. **分页插件失效**：`shopai-common` 的 `MyBatisPlusConfig` 手工定义了 `SqlSessionFactory`，
   但未把 `MybatisPlusInterceptor` set 进去。定义自定义 `SqlSessionFactory` 会使 MP 自动配置失效，
   导致 `PaginationInnerInterceptor` 被创建却从未挂载，`page()` 分页实际不生效。这是现存 bug。

## 2. 目标

1. 让每个表驱动的 Service 继承 `ServiceImpl<XxxMapper, Entity>` 并使 MP 便捷方法可用。
2. 修复 `MyBatisPlusConfig` 分页插件失效问题。
3. 服务分层规范化：**接口 + Impl**。

## 3. 定稿方案

### 3.1 分层约定

对每个表驱动的服务：

- **接口 `XxxService`**：仅包含现有全部 public 方法签名 + 原中文 Javadoc。
- **实现 `XxxServiceImpl`**：
  - `@Service`（保留原 bean 名，如 `@Service("userService")`）
  - `extends ServiceImpl<XxxMapper, Entity> implements XxxService`
  - 原 `@Autowired` 字段、方法体、私有方法、Javadoc/中文注释**逐字保留**，不替换任何 `mapper.xxx()` 调用。
- **Controller / 跨服务注入 / 测试无需改动**：它们已按接口名 `XxxService` 注入
  （`@RequiredArgsConstructor private final XxxService`）。

### 3.2 实施手法（避免文件删除）

环境限制：工作区 shell 不能 `rm`。因此采用：

1. **覆盖**原 `service/XxxService.java` → 接口内容。
2. **新建** `service/XxxServiceImpl.java` → 原类体（类名改 `XxxServiceImpl`）。

不产生“需要删除的旧文件”，Controller 等按 `XxxService` 注入的代码天然继续编译。

### 3.3 改造清单（14 个服务）

| 模块 | 服务 | ServiceImpl 继承 |
|---|---|---|
| user | UserService | `ServiceImpl<UserMapper, User>` |
| user | CustomerService | `ServiceImpl<CustomerMapper, Customer>` |
| product | ProductService | `ServiceImpl<ProductMapper, Product>` |
| product | ProductSkuService | `ServiceImpl<ProductSkuMapper, ProductSku>` |
| product | CategoryService | `ServiceImpl<CategoryMapper, Category>` |
| order | OrderService | `ServiceImpl<OrderMapper, Order>` |
| order | OrderItemService | `ServiceImpl<OrderItemMapper, OrderItem>` |
| order | ShoppingCartService | `ServiceImpl<ShoppingCartMapper, ShoppingCart>` |
| order | AddressService | `ServiceImpl<AddressMapper, Address>` |
| merchant | MerchantService | `ServiceImpl<MerchantMapper, Merchant>` |
| merchant | ReviewService | `ServiceImpl<ReviewMapper, Review>` |
| merchant | ReviewComplaintService | `ServiceImpl<ReviewComplaintMapper, ReviewComplaint>` |
| merchant | MerchantRealNameService | `ServiceImpl<MerchantRealNameMapper, MerchantRealName>` |
| inventory | InventoryService | `ServiceImpl<InventoryMapper, Inventory>` |

**排除**：`shopai-search` 的 `SearchSyncService`、`SearchQueryService`（无表 Mapper，不继承 `ServiceImpl`），
保持原样不动。

### 3.4 MyBatisPlusConfig 修复

删除手工的 `sqlSessionFactory()` 与 `sqlSessionTemplate()` 两个 `@Bean`，仅保留
`mybatisPlusInterceptor()`。交回 MP 自动配置装配：自动配置会把该拦截器应用到它创建的
`SqlSessionFactory`，并按各服务 `application.yml` 的 `mapper-locations` / `type-aliases-package` 生效。
一次性修复分页。

## 4. 已知注意点

- **`getById` 重名**：`UserService.getById(Long)` 返回 `UserDto`，与 `IService.getById(Serializable)`
  返回 `T=User` 构成重载（参数类型不同，合法）。调 `getById(Long)` 命中业务方法，行为不变。
- **`ServiceImpl` 自带 `baseMapper`**：现有 `@Autowired private XxxMapper` 字段保留，方法体内调用不变。
- **无 `new XxxService()` 直接实例化**（已 grep 确认），命名安全。

## 5. 边界（不做）

- 不改 Controller / Mapper / XML / 实体 / 前端。
- 不替换现有 `mapper.xxx()` 调用为 MP 方法（用户明确“仅加继承，不动现有调用”）。
- search 模块完全不动。

## 6. 验证

沙箱为 Java 11，项目为 Java 21 + Spring Boot 4.0.6，**无法在沙箱编译**。静态核验：

1. grep 确认无残留旧类引用（如 `extends XxxService` 之类误用）。
2. 确认每个 Controller / 跨服务注入的 `XxxService` 均有对应接口。
3. 确认 `ServiceImpl` 泛型（Mapper、Entity）正确。
4. 确认 `MyBatisPlusConfig` 只剩拦截器 Bean。

最终编译由用户本地执行。

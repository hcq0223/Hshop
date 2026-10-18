package com.shopai.order.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.common.dto.internal.ProductInfo;
import com.shopai.common.dto.internal.ProductSkuInfo;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.common.mq.IndexChangedEvent;
import com.shopai.common.mq.IndexEventPublisher;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.order.entity.*;
import com.shopai.order.feign.InventoryClient;
import com.shopai.order.feign.MerchantInternalClient;
import com.shopai.order.feign.ProductInternalClient;
import com.shopai.order.feign.ProductSalesClient;
import com.shopai.order.mapper.*;

import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.OrderService 迁移至 shopai-order 服务。
 * 主要改造点（按「独立库」处理，全部跨服务访问改走 Feign）：
 * 1. 扣库存：原 productSkuService.decreaseStockSync → Feign 调 inventory.deduct（Seata AT 分支事务）。
 * 2. 回补库存：原 productSkuService.restoreStockBySkuId → Feign 调 inventory.restore。
 * 3. 累销量：原 productMapper.incrementSalesCount → Feign 调商品服务内部接口 internal/sales。
 * 4. 查 SKU/商品：原 ProductSkuRefMapper / ProductRefMapper 跨库直查 product_skus / products
 *    → Feign 调商品服务内部接口（ProductInternalClient.getSku / getProduct）。
 * 5. 商户资金（营收/余额/冻结）：原 MerchantRefMapper 跨库读改写 merchants 资金字段
 *    → Feign 调商家服务内部接口 changeFunds，由商家服务在本地事务内原子累加。
 * 6. 店铺名/Logo：原 OrderMapper.xml 的 JOIN merchants → 查询后由 OrderEnrichService
 *    通过 Feign 批量补齐（见 OrderEnrichService）。
 * 7. 下单/退款链路标注 @GlobalTransactional（Seata AT 全局事务）。
 * <p>
 * 状态机：pending_pay → paid → shipped → received → completed
 * 逆向：pending_pay → cancelled | * → refunding → cancelled/completed/original
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;OrderMapper, Order&gt; 实现 OrderService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法（list/getById/save/updateById/page/lambdaQuery 等）；
 * 保留原 @Autowired 字段与方法体，现有 mapper 调用不变。
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private AddressService addressService;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private OrderActionLogMapper actionLogMapper;
    @Autowired
    private ScheduledExecutorService cacheDelayScheduler;
    // 微服务拆分新增：Feign 客户端（库存 / 商品 / 商家服务内部接口）
    @Autowired
    private InventoryClient inventoryClient;
    @Autowired
    private ProductSalesClient productSalesClient;
    @Autowired
    private ProductInternalClient productInternalClient;
    @Autowired
    private MerchantInternalClient merchantInternalClient;
    // 微服务拆分新增：店铺信息后置补齐（替代原 SQL 中 JOIN merchants）
    @Autowired
    private OrderEnrichService orderEnrichService;
    // 列表查询改造新增：列表走 ES 索引（替代原 Redis 列表缓存 + deleteByPrefix 全量失效）
    @Autowired
    private OrderListIndexService orderListIndexService;
    @Autowired
    private OrderIndexResultMapper orderIndexResultMapper;
    // 列表查询改造新增：订单变更后发 MQ，搜索服务同步 order 索引
    @Autowired
    private IndexEventPublisher indexEventPublisher;

    /**
     * 商家资金变更消息服务（付款与结算解耦）
     * <p>
     * 付款事务内只落一条消息（与订单状态同事务），提交后再投递商家服务，
     * 失败由 {@link com.shopai.order.task.MerchantFundsRetryTask} 重试。
     */
    @Autowired
    private MerchantFundsMessageService merchantFundsMessageService;

    // 订单状态常量
    public static final String STATUS_PENDING_PAY = "pending_pay";
    public static final String STATUS_PAID = "paid";
    public static final String STATUS_SHIPPED = "shipped";
    public static final String STATUS_RECEIVED = "received";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_REFUNDING = "refunding";

    // 终态
    private static final Set<String> FINAL_STATUSES = Set.of(STATUS_COMPLETED, STATUS_CANCELLED);

    // ==================== 查询（缓存） ====================

    /**
     * 订单详情
     * <p>
     * 微服务拆分说明：店铺名/Logo 原由 SQL {@code JOIN merchants} 填充，独立库后
     * 由 OrderEnrichService 通过 Feign 调商家服务补齐。
     */
    @Override
    public Order getOrderDetail(Long id) {
        String key = RedisService.CACHE_ORDER + id;
        Order order = redisService.getOrSet(key, Order.class, 300L,
                () -> orderMapper.selectDetailById(id));
        orderEnrichService.enrich(order);
        return order;
    }

    @Override
    public List<Order> getOrdersByCustomer(Long customerId, int page, int size) {
        // 列表查询改造：原走 Redis 缓存 cache:order:list:{hash} + 回源查库，
        // 且任意订单变更 deleteByPrefix 全清。现改为查 shopai-search 的 order 索引。
        //
        // 【fail-closed】customerId 为 null 时绝不能查：OrderListIndexService 采用
        // 「有值才加过滤」的写法，null 意味着不加 customer_id 条件 → 返回全平台订单。
        // 身份缺失（Token 过期/无 Token）时返回空列表，不泄露任何数据。
        if (customerId == null) {
            return Collections.emptyList();
        }
        IndexListResult result = orderListIndexService.query(
                null, customerId, null, true, null, page, size);
        return orderIndexResultMapper.toOrders(result);
    }

    @Override
    public List<Order> getOrdersByMerchant(Long merchantId) {
        // 【fail-closed】理由同 getOrdersByCustomer：null 会退化成「全平台订单」
        if (merchantId == null) {
            return Collections.emptyList();
        }
        // 原实现无分页（selectByMerchantId 全量返回），此处用较大 size 保持等价语义
        IndexListResult result = orderListIndexService.query(
                null, null, merchantId, true, null, 1, 200);
        return orderIndexResultMapper.toOrders(result);
    }

    @Override
    public List<Order> getAllOrders(int page, int size) {
        IndexListResult result = orderListIndexService.query(
                null, null, null, true, null, page, size);
        return orderIndexResultMapper.toOrders(result);
    }

    // 订单历史（已完成 + 已取消）
    @Override
    public List<Order> getHistoryOrders(Long customerId, Long merchantId) {
        // 【fail-closed】两个归属条件都为 null 时，查询就等于「全平台历史订单」。
        // 顾客/商家两种调用至少有一个非 null，都为空说明身份没解析出来。
        if (customerId == null && merchantId == null) {
            return Collections.emptyList();
        }
        IndexListResult result = orderListIndexService.queryHistory(customerId, merchantId, 1, 200);
        return orderIndexResultMapper.toOrders(result);
    }

    @Override
    public List<Order> getOrdersByStatus(String status, Long customerId, Long merchantId) {
        // 注意：管理员视图会传两个 null（需要看全平台），所以这里不能直接用
        // 「二者皆空即拒绝」的判断 —— 管理员入口已在网关按 /api/admin/** 强制 admin 角色，
        // 且 OrderController 的三个调用点分别由 requireCustomer / requireMerchant 兜底。
        IndexListResult result = orderListIndexService.query(
                status, customerId, merchantId, false, null, 1, 200);
        return orderIndexResultMapper.toOrders(result);
    }

    @Override
    public Order getOrderByOrderNo(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        orderEnrichService.enrich(order);
        return order;
    }

    // ==================== 创建订单 ====================

    /**
     * 购物车结算下单（Seata AT 全局事务）
     * <p>
     * 微服务拆分说明：原单库本地事务（扣库存 + 写订单 + 累销量 + 清购物车），
     * 拆分后标注 @GlobalTransactional，扣库存/累销量为跨服务 Feign 调用（Seata 分支事务）。
     * 任一步失败 → Seata 全局回滚。
     */
    @Override
    @GlobalTransactional
    @Transactional
    public List<Order> createOrder(Long customerId, Long merchantId, List<Long> selectedCartIds, Long addressId) {
        Address address = addressService.getAddressById(addressId);
        if (address == null || !address.getCustomerId().equals(customerId))
            throw new RuntimeException("地址不存在或不属于该客户");

        Map<String, Object> addressSnapshot = buildAddressSnapshot(address);

        List<ShoppingCart> cartItems = shoppingCartMapper.selectBatchIds(selectedCartIds)
                .stream().filter(c -> c.getCustomerId().equals(customerId) && c.getSelected() == 1)
                .collect(Collectors.toList());
        if (cartItems.isEmpty()) throw new RuntimeException("购物车中无选中的商品");

        // 按商家分组：通过 SKU → Product → merchantId（均为 Feign 调商品服务，不再跨库直查）
        Map<Long, List<ShoppingCart>> groups = new LinkedHashMap<>();
        for (ShoppingCart cart : cartItems) {
            // 微服务拆分说明：原 ProductSkuRefMapper 跨库直查 product_skus → Feign 调商品服务内部接口
            ProductSkuInfo sku = getSkuViaFeign(cart.getSkuId());
            if (sku == null) throw new RuntimeException("SKU不存在，ID: " + cart.getSkuId());
            // 微服务拆分说明：原 ProductRefMapper 跨库直查 products → Feign 调商品服务内部接口
            ProductInfo product = getProductViaFeign(sku.getProductId());
            if (product == null) throw new RuntimeException("商品不存在，SKU ID: " + cart.getSkuId());
            Long mId = product.getMerchantId();
            groups.computeIfAbsent(mId, k -> new ArrayList<>()).add(cart);
        }

        List<Order> createdOrders = new ArrayList<>();
        for (Map.Entry<Long, List<ShoppingCart>> entry : groups.entrySet()) {
            Long groupMerchantId = entry.getKey();
            List<ShoppingCart> groupItems = entry.getValue();

            BigDecimal totalAmount = BigDecimal.ZERO;
            List<OrderItem> orderItems = new ArrayList<>();
            for (ShoppingCart cart : groupItems) {
                ProductSkuInfo sku = getSkuViaFeign(cart.getSkuId());
                if (sku == null) throw new RuntimeException("SKU不存在，ID: " + cart.getSkuId());
                // 微服务拆分说明：原 productSkuService.decreaseStockSync → Feign 调 inventory.deduct（Seata 分支事务）
                deductStockViaFeign(cart.getSkuId(), cart.getQuantity());

                OrderItem item = new OrderItem();
                item.setProductId(sku.getProductId());
                item.setSkuId(sku.getId());
                ProductInfo product = getProductViaFeign(sku.getProductId());
                item.setProductName(product != null ? product.getName() : "未知商品");
                item.setSkuAttrs(sku.getColor() + "/" + sku.getSize());
                item.setPrice(sku.getPrice());
                item.setQuantity(cart.getQuantity());
                item.setImageUrl(sku.getImageUrl() != null && !sku.getImageUrl().isEmpty()
                        ? sku.getImageUrl() : (product != null ? product.getMainImage() : null));
                orderItems.add(item);
                totalAmount = totalAmount.add(sku.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
            }

            // 微服务拆分说明：原 productMapper.incrementSalesCount → Feign 调商品服务 internal/sales
            for (OrderItem item : orderItems)
                productSalesClient.incrementSalesCount(item.getProductId(), item.getQuantity());

            String orderNo = generateOrderNo();
            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setCustomerId(customerId);
            order.setMerchantId(groupMerchantId);
            order.setTotalAmount(totalAmount);
            order.setPayAmount(totalAmount);
            order.setFreightAmount(BigDecimal.ZERO);
            order.setStatus(STATUS_PENDING_PAY);
            order.setReceiverSnapshot(addressSnapshot);
            order.setCreateTime(LocalDateTime.now());
            orderMapper.insert(order);

            for (OrderItem item : orderItems) item.setOrderId(order.getId());
            orderItemMapper.insertBatch(orderItems);
            logAction(order.getId(), "create", "customer", customerId, "创建订单");
            evictOrderCacheAfterCommit(order.getId(), customerId, groupMerchantId);
            publishOrderIndexAfterCommit(order.getId());
            createdOrders.add(order);
        }

        shoppingCartMapper.deleteByIds(selectedCartIds);
        return createdOrders;
    }

    /**
     * 直接购买下单（Seata AT 全局事务）
     */
    @Override
    @GlobalTransactional
    @Transactional
    public Order createOrderDirect(Long customerId, Long skuId, int quantity, Long addressId) {
        Address address = addressService.getAddressById(addressId);
        if (address == null || !address.getCustomerId().equals(customerId))
            throw new RuntimeException("地址不存在或不属于该客户");

        Map<String, Object> addressSnapshot = buildAddressSnapshot(address);
        // 微服务拆分说明：原 ProductSkuRefMapper 跨库直查 product_skus → Feign 调商品服务内部接口
        ProductSkuInfo sku = getSkuViaFeign(skuId);
        if (sku == null) throw new RuntimeException("SKU不存在，ID: " + skuId);
        // 微服务拆分说明：原 ProductRefMapper 跨库直查 products → Feign 调商品服务内部接口
        ProductInfo product = getProductViaFeign(sku.getProductId());
        if (product == null) throw new RuntimeException("商品不存在");
        // 微服务拆分说明：原 decreaseStockSync → Feign 调 inventory.deduct（Seata 分支事务）
        deductStockViaFeign(skuId, quantity);

        BigDecimal totalAmount = sku.getPrice().multiply(BigDecimal.valueOf(quantity));
        OrderItem item = new OrderItem();
        item.setProductId(product.getId());
        item.setSkuId(sku.getId());
        item.setProductName(product.getName());
        item.setSkuAttrs(sku.getColor() + "/" + sku.getSize());
        item.setPrice(sku.getPrice());
        item.setQuantity(quantity);
        item.setImageUrl(sku.getImageUrl() != null && !sku.getImageUrl().isEmpty()
                ? sku.getImageUrl() : product.getMainImage());

        // 微服务拆分说明：原 productMapper.incrementSalesCount → Feign 调商品服务 internal/sales
        productSalesClient.incrementSalesCount(product.getId(), quantity);

        String orderNo = generateOrderNo();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setCustomerId(customerId);
        order.setMerchantId(product.getMerchantId());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setFreightAmount(BigDecimal.ZERO);
        order.setStatus(STATUS_PENDING_PAY);
        order.setReceiverSnapshot(addressSnapshot);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        item.setOrderId(order.getId());
        orderItemMapper.insertBatch(Collections.singletonList(item));

        logAction(order.getId(), "create", "customer", customerId, "直接购买创建订单");
        evictOrderCacheAfterCommit(order.getId(), customerId, order.getMerchantId());
        // 必须发订单索引同步事件：订单列表查询由 shopai-search 的 order 索引承载
        // （见 OrderController.getCustomerOrders → orderListIndexService）。
        // 漏了这一行的后果：订单在库里正常存在、详情页能打开，
        // 但「我的订单」列表里看不到它 —— 因为 ES 里根本没有这个文档。
        publishOrderIndexAfterCommit(order.getId());
        return order;
    }

    // ==================== 状态流转 ====================

    /**
     * 秒杀下单（供秒杀服务 Feign 调用，不扣主库存）
     * <p>
     * <b>与 createOrderDirect 的三处差异（都写在方法体注释里）</b>：
     * <ol>
     *   <li>没有 {@code deductStockViaFeign} —— 秒杀库存由秒杀服务的
     *       {@code seckill_stock} 独立管理，调用方已经扣过了；</li>
     *   <li>订单金额用传入的 {@code seckillPrice}，不是 SKU 日常价；</li>
     *   <li>按 {@code orderNo} 做幂等 —— 秒杀消息是至少一次投递，重复调用会先
     *       查到这个订单号已存在并直接返回，不会创建第二笔订单。</li>
     * </ol>
     * <p>
     * <b>不加 @GlobalTransactional</b>：本方法只写自己库的 orders / order_items，
     * 唯一的跨服务调用是「累加商品销量」，那是非关键的统计动作（其 Fallback 降级为
     * 成功，且销量可以由定时任务重算），不值得为它引入全局事务。
     * 秒杀链路的一致性由秒杀侧的本地消息表保证。
     */
    @Override
    @Transactional
    public SeckillOrderResult createSeckillOrder(Long customerId, Long skuId, int quantity, Long addressId,
                                                 BigDecimal seckillPrice, String orderNo) {
        if (seckillPrice == null) {
            throw new RuntimeException("秒杀订单必须指定成交价");
        }
        // 幂等：同一订单号重复投递时直接返回已有订单。
        // 秒杀侧的消息投递是「至少一次」，同一条消息可能到达多次，这里必须挡住第二次建单。
        if (orderNo != null && !orderNo.isBlank()) {
            Order exist = orderMapper.selectByOrderNo(orderNo);
            if (exist != null) {
                log.info("秒杀订单已存在，幂等返回, orderNo={}, orderId={}", orderNo, exist.getId());
                return toSeckillResult(exist, true);
            }
        }

        Address address = addressService.getAddressById(addressId);
        if (address == null || !address.getCustomerId().equals(customerId)) {
            throw new RuntimeException("地址不存在或不属于该客户");
        }
        Map<String, Object> addressSnapshot = buildAddressSnapshot(address);
        ProductSkuInfo sku = getSkuViaFeign(skuId);
        if (sku == null) throw new RuntimeException("SKU不存在，ID: " + skuId);
        ProductInfo product = getProductViaFeign(sku.getProductId());
        if (product == null) throw new RuntimeException("商品不存在");

        // 注意：这里【故意】没有 deductStockViaFeign。
        // 秒杀额度在秒杀服务的 seckill_stock 表里扣，主库存不参与本单，
        // 否则等于一笔交易扣两处库存。

        BigDecimal totalAmount = seckillPrice.multiply(BigDecimal.valueOf(quantity));
        OrderItem item = new OrderItem();
        item.setProductId(product.getId());
        item.setSkuId(sku.getId());
        item.setProductName(product.getName());
        item.setSkuAttrs(sku.getColor() + "/" + sku.getSize());
        // 成交价用秒杀价，不用 sku.getPrice()
        item.setPrice(seckillPrice);
        item.setQuantity(quantity);
        item.setImageUrl(sku.getImageUrl() != null && !sku.getImageUrl().isEmpty()
                ? sku.getImageUrl() : product.getMainImage());

        // 销量累加：非关键统计，失败不影响下单（Fallback 降级为成功）
        productSalesClient.incrementSalesCount(product.getId(), quantity);

        String finalOrderNo = (orderNo == null || orderNo.isBlank()) ? generateOrderNo() : orderNo;
        Order order = new Order();
        order.setOrderNo(finalOrderNo);
        order.setCustomerId(customerId);
        order.setMerchantId(product.getMerchantId());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setFreightAmount(BigDecimal.ZERO);
        order.setStatus(STATUS_PENDING_PAY);
        order.setReceiverSnapshot(addressSnapshot);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);

        item.setOrderId(order.getId());
        orderItemMapper.insertBatch(Collections.singletonList(item));

        logAction(order.getId(), "create", "customer", customerId, "秒杀下单创建订单");
        evictOrderCacheAfterCommit(order.getId(), customerId, order.getMerchantId());
        // 同上：秒杀下单同样走 createOrder* 家族，也必须同步订单索引，
        // 否则秒杀订单在「我的订单」里同样不可见。
        // 注意这里用的是 orderId（真实订单ID），索引文档以 orders.id 作为 _id。
        publishOrderIndexAfterCommit(order.getId());
        log.info("秒杀订单创建成功, orderNo={}, orderId={}, customerId={}, skuId={}, qty={}, price={}",
                finalOrderNo, order.getId(), customerId, skuId, quantity, seckillPrice);
        return toSeckillResult(order, false);
    }

    /**
     * 订单实体 → 秒杀下单结果 DTO。
     * <p>
     * 只挑秒杀侧需要的字段，不把整个订单实体（30+ 字段，含退款/物流/结算）
     * 暴露给秒杀服务，避免秒杀耦合到订单的内部结构。
     *
     * @param order         订单
     * @param idempotentHit 是否命中幂等（已有订单）
     */
    private SeckillOrderResult toSeckillResult(Order order, boolean idempotentHit) {
        SeckillOrderResult result = new SeckillOrderResult();
        result.setOrderId(order.getId());
        result.setOrderNo(order.getOrderNo());
        result.setCustomerId(order.getCustomerId());
        result.setMerchantId(order.getMerchantId());
        result.setPayAmount(order.getPayAmount());
        result.setStatus(order.getStatus());
        result.setIdempotentHit(idempotentHit);
        return result;
    }

    /**
     * 校验状态流转合法性
     */
    private void assertStatus(Order order, String expected) {
        if (!expected.equals(order.getStatus()))
            throw new RuntimeException("当前订单状态不允许此操作");
    }

    /**
     * 校验非终态（终态不可再变更）
     */
    private void assertNotFinal(Order order) {
        if (FINAL_STATUSES.contains(order.getStatus()))
            throw new RuntimeException("订单已结束，不可操作");
    }

    /**
     * 顾客支付
     * <p>
     * <b>幂等改造（并发安全）</b>：原实现是「selectById → assertStatus(pending_pay)
     * → updateById」，属于「先读后判」。并发下两个请求都读到 pending_pay、都判定合法、
     * 都继续执行，后果有三：
     * <ol>
     *   <li><b>商户营收被累加两次</b> —— 直接多给商户钱；</li>
     *   <li>同一订单被写两次，日志/动作表出现重复记录；</li>
     *   <li>两条事务争同一把 orders 行锁，互相等待，进一步拉长持锁时间
     *       （这正是线上出现「多条 UPDATE orders SET status='paid' 同时 LOCK WAIT」的原因）。</li>
     * </ol>
     * 改为条件更新（CAS）后，<b>只有第一个事务能拿到 rows=1</b>，
     * 其余请求 rows=0 → 视为「已被处理」直接幂等返回，
     * <b>不再执行任何副作用</b>（不改资金、不发事件、不写动作日志）。
     * <p>
     * 注意顺序：CAS 必须在 enqueueFundsChange <b>之前</b>，
     * 否则重复请求仍会把资金加两次 —— 幂等的闸门要放在最前面。
     */
    @Override
    @Transactional
    public void payOrder(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_PENDING_PAY);

        // 抢执行权：把状态判断下推到 WHERE，由数据库行锁保证只有一个请求能成功
        int rows = orderMapper.casUpdateStatus(
                orderId, STATUS_PENDING_PAY, STATUS_PAID, LocalDateTime.now());
        if (rows == 0) {
            // 已被其它并发请求抢先支付（或状态已变）。
            // 这里【幂等成功返回】而不是抛异常：用户重复点击/前端重试不该报错，
            // 订单确实已支付，对调用方而言是「目标状态已达成」。
            log.info("订单已被处理，跳过重复支付, orderId={}, customerId={}", orderId, customerId);
            return;
        }

        logAction(orderId, "pay", "customer", customerId, "支付成功");

        // ==================== 资金变更：从付款事务中解耦（关键改造） ====================
        //
        // 【改造前】这里直接同步 Feign 调商家服务改资金，造成一个结构性卡点：
        //   本事务持有 orders 行锁的整段时间，横跨了一次跨服务调用；
        //   而商家服务要改的 merchants 行若被占，它会等满 50 秒（InnoDB 锁等待超时）
        //   才返回 500，本事务随之回滚 —— 用户付不了款、订单停在待付款。
        //
        // 【改造后】统一走 enqueueFundsChange：事务内只落消息，提交后再投递。
        // 幂等依据：(merchant_id, biz_key) 唯一索引 + bizKey = "pay:{orderId}"。
        enqueueFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "pay")
                .revenue(order.getPayAmount())
                .available(order.getPayAmount()), orderId);

        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 落一条资金变更消息，并在<b>事务提交后</b>投递给商家服务 —— 所有资金变更的统一入口
     * <p>
     * <b>为什么所有资金变更都要走这里</b>：付款、退款、强制取消、冻结/解冻等 8 处
     * 原先都是「事务内同步 Feign 调商家服务」。它们共享同一个结构性缺陷 ——
     * <b>持有 orders 行锁的时间横跨了一次跨服务调用</b>。只要商家服务要改的
     * {@code merchants} 行被占，对方就会等满 50 秒锁等待超时才返回，
     * 本事务随之回滚，用户侧表现为「操作失败且不知原因」。
     * 统一走本方法后，锁在提交瞬间就释放，跨服务调用发生在锁之外。
     * <p>
     * <b>幂等设计</b>：{@code bizKey = reason + ":" + orderId}，
     * 配合 {@code (merchant_id, biz_key)} 唯一索引 —— 同一笔订单的同一种资金动作
     * 只会产生一条消息，重试、重复请求都不会重复加减钱。
     * <p>
     * <b>退款类动作必须带轮次后缀</b>：上面的键是「永久唯一」的，但一笔订单可以
     * 合法地经历多轮退款（申请 → 拒绝 → 再申请 → 同意）。若第二轮沿用同一个键，
     * 第二轮的 {@code freeze} 会被幂等索引静默丢弃，而随后的 {@code refund_approve}
     * 照常执行 {@code frozen -= payAmount}，于是冻结余额被扣成负数（真实发生过）。
     * 因此退款类动作（freeze / refund_approve / refund_reject / force_refund_*）
     * 必须传 {@code refundSeq} 作为后缀，见 {@link #enqueueRefundFundsChange}。
     * <p>
     * <b>安全性</b>：之所以能推迟，是因为「订单状态」才是业务事实来源，
     * 资金字段是可由状态推导的派生数据。状态提交成功后，资金变更按增量累加即可补齐，
     * 与状态变更之间的先后顺序不影响最终结果（增量之间可交换）。
     *
     * @param request 资金变更请求（增量为 null 表示该字段不变动）
     * @param orderId 订单ID，用于构造幂等键
     */
    private void enqueueFundsChange(MerchantFundsChangeRequest request, Long orderId) {
        enqueueFundsChange(request, orderId, null);
    }

    /**
     * 退款类资金变更：幂等键追加退款轮次，避免多轮退款互相覆盖
     *
     * @param order 已经带有本轮 {@code refundSeq} 的订单
     */
    private void enqueueRefundFundsChange(MerchantFundsChangeRequest request, Order order) {
        if (order == null) {
            return;
        }
        enqueueFundsChange(request, order.getId(), refundCycleTag(order));
    }

    /** 退款轮次后缀；历史订单 refundSeq 为 null 时按 0 处理 */
    private static String refundCycleTag(Order order) {
        Integer seq = order == null ? null : order.getRefundSeq();
        return String.valueOf(seq == null ? 0 : seq);
    }

    /** 进入新一轮退款：轮次 +1（幂等键随之改变） */
    private static void beginRefundCycle(Order order) {
        Integer seq = order == null ? null : order.getRefundSeq();
        order.setRefundSeq((seq == null ? 0 : seq) + 1);
    }

    /**
     * 落库一条资金变更消息（不做事务外的投递）
     *
     * @param cycleTag 退款轮次后缀；非退款类动作为 null（键保持 {@code reason:orderId} 不变）
     */
    private void enqueueFundsChange(MerchantFundsChangeRequest request, Long orderId, String cycleTag) {
        if (request == null || request.getMerchantId() == null || orderId == null) {
            // 商家ID缺失（历史脏数据）时无可变更，直接跳过，避免无意义调用
            return;
        }
        String bizKey = request.getReason() + ":" + orderId
                + (cycleTag == null ? "" : ":" + cycleTag);
        MerchantFundsMessage msg = merchantFundsMessageService.enqueue(request, bizKey);
        if (msg != null) {
            // 提交后再投递。AfterCommitExecutor 在无事务上下文时会立即执行，
            // 因此 @Scheduled 等非事务调用路径行为一致。
            AfterCommitExecutor.run(() -> deliverFundsChange(msg));
        }
    }

    /**
     * 投递一条资金变更消息给商家服务（事务外执行）。
     * <p>
     * <b>失败只记日志，绝不抛异常</b>：此时订单事务已经提交，抛异常既回滚不了
     * 任何东西，又会把「操作成功」变成「前端看到报错」。失败的消息仍是
     * {@code pending}，由 {@link com.shopai.order.task.MerchantFundsRetryTask} 重试。
     */
    private void deliverFundsChange(MerchantFundsMessage msg) {
        try {
            MerchantFundsChangeRequest req = MerchantFundsChangeRequest.of(msg.getMerchantId(), msg.getReason())
                    .revenue(msg.getRevenueDelta())
                    .available(msg.getAvailableDelta())
                    .frozen(msg.getFrozenDelta());
            Result<?> r = merchantInternalClient.changeFunds(req);
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                merchantFundsMessageService.markDone(msg.getId());
            } else {
                log.warn("资金变更即时投递失败，留待重试任务处理: id={}, bizKey={}, msg={}",
                        msg.getId(), msg.getBizKey(), r == null ? "无响应" : r.getMessage());
            }
        } catch (Exception e) {
            // 包含 Feign 降级、网络异常、对方 500 等。都不影响已提交的业务事务。
            log.warn("资金变更即时投递异常，留待重试任务处理: id={}, bizKey={}, reason={}",
                    msg.getId(), msg.getBizKey(), e.getMessage());
        }
    }

    /**
     * 商户发货
     */
    @Override
    @Transactional
    public void shipOrder(Long orderId, Long merchantId, String trackingNumber, String trackingCompany) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_PAID);
        // 退款中禁止发货
        if (STATUS_REFUNDING.equals(order.getStatus()))
            throw new RuntimeException("退款中不可发货");
        order.setStatus(STATUS_SHIPPED);
        order.setShippedAt(LocalDateTime.now());
        order.setTrackingNumber(trackingNumber);
        if (trackingCompany != null) order.setTrackingCompany(trackingCompany);
        orderMapper.updateById(order);
        logAction(orderId, "ship", "merchant", merchantId, "发货 物流:" + trackingNumber);
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 更新物流单号（已发货状态，商户可多次修改）
     */
    @Override
    @Transactional
    public void updateTracking(Long orderId, Long merchantId, String trackingNumber, String trackingCompany) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_SHIPPED);
        order.setTrackingNumber(trackingNumber);
        if (trackingCompany != null) order.setTrackingCompany(trackingCompany);
        orderMapper.updateById(order);
        logAction(orderId, "ship", "merchant", merchantId, "更新物流:" + trackingNumber);
    }

    /**
     * 顾客确认收货
     */
    @Override
    @Transactional
    public void receiveOrder(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        // 越权防护（与 completeOrder 一致，但提示语不泄露订单是否存在）：
        // 原实现只校验状态、不校验归属 —— 任何已登录顾客都能把别人的订单改成「已收货」，
        // 而 orderId 是雪花主键，配合收货接口可被枚举利用。实测用「userId 未映射到该顾客」
        // 的 token 也能改成功，故必须补上。
        // customerId 由 CustomerResolver 映射，用户服务不可用时为 null，此处同样拒绝（fail-closed）。
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new RuntimeException("订单不存在");
        }
        assertStatus(order, STATUS_SHIPPED);
        order.setStatus(STATUS_RECEIVED);
        orderMapper.updateById(order);
        logAction(orderId, "receive", "customer", customerId, "确认收货");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 系统自动签收（发货超10天）
     */
    @Override
    @Transactional
    public void autoReceive(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !STATUS_SHIPPED.equals(order.getStatus())) return;
        order.setStatus(STATUS_RECEIVED);
        order.setAutoReceived(1);
        orderMapper.updateById(order);
        logAction(orderId, "auto_receive", "system", null, "超时自动签收");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 完成订单（顾客或系统触发：收货后4天无售后）
     */
    @Override
    @Transactional
    public void completeOrder(Long orderId, String operatorType, Long operatorId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        // 顾客只能完成自己的 received 订单
        if ("customer".equals(operatorType)) {
            if (!order.getCustomerId().equals(operatorId))
                throw new RuntimeException("无权操作");
            if (!STATUS_RECEIVED.equals(order.getStatus()))
                throw new RuntimeException("当前订单状态不可确认完成");
        }
        // 允许从 received 或直接从 paid/shipped 强制完成（管理员）
        order.setStatus(STATUS_COMPLETED);
        order.setCompletedAt(LocalDateTime.now());
        if ("system".equals(operatorType)) order.setAutoCompleted(1);
        orderMapper.updateById(order);
        logAction(orderId, "complete", operatorType, operatorId, "订单完成");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 顾客取消订单（待支付，15分钟内）
     * <p>
     * 微服务拆分说明：回补库存改为 Feign 调 inventory.restore，标注 @GlobalTransactional。
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void cancelOrderByCustomer(Long orderId, Long customerId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_PENDING_PAY);
        restoreStock(orderId);
        order.setStatus(STATUS_CANCELLED);
        orderMapper.updateById(order);
        logAction(orderId, "cancel", "customer", customerId, "顾客取消");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 商户强制关闭订单（待支付）
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void forceCloseByMerchant(Long orderId, Long merchantId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_PENDING_PAY);
        restoreStock(orderId);
        order.setStatus(STATUS_CANCELLED);
        orderMapper.updateById(order);
        logAction(orderId, "force_cancel", "merchant", merchantId, "商户强制关闭");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 管理员强制取消（任意非终态）
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void forceCancelByAdmin(Long orderId, Long adminId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertNotFinal(order);

        // 资金调整（微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更）
        // 退款中需先解冻资金；如果不是待支付，已付过款的需要扣减营收
        MerchantFundsChangeRequest funds =
                MerchantFundsChangeRequest.of(order.getMerchantId(), "force_cancel_admin");
        if (STATUS_REFUNDING.equals(order.getStatus())) {
            // 解冻：冻结余额 -payAmount，可用余额 +payAmount
            funds.frozen(order.getPayAmount().negate()).available(order.getPayAmount());
        }
        if (!STATUS_PENDING_PAY.equals(order.getStatus())) {
            // 已付款：营收与可用余额各扣减 payAmount
            funds.revenue(order.getPayAmount().negate()).available(order.getPayAmount().negate());
        }
        enqueueFundsChange(funds, orderId);

        restoreStock(orderId);
        order.setStatus(STATUS_CANCELLED);
        order.setCompletedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        logAction(orderId, "force_cancel", "admin", adminId, "管理员强制取消");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    // ==================== 售后 ====================

    /**
     * 顾客申请退款/退货退款
     */
    @Override
    @Transactional
    public void requestRefund(Long orderId, Long customerId, String refundType, BigDecimal refundAmount,
                              String refundReason, String returnTrackingNumber, String returnTrackingCompany) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");

        // 允许从 paid(未发货仅退款)、shipped(退货退款/仅退款)、received(4天内) 发起
        if (!STATUS_PAID.equals(order.getStatus())
                && !STATUS_SHIPPED.equals(order.getStatus())
                && !STATUS_RECEIVED.equals(order.getStatus()))
            throw new RuntimeException("当前状态不可申请退款");

        // 已收货超过4天不可售后
        if (STATUS_RECEIVED.equals(order.getStatus()) && order.getShippedAt() != null) {
            if (LocalDateTime.now().isAfter(order.getShippedAt().plusDays(4)))
                throw new RuntimeException("已超过售后有效期");
        }

        // 先开启新一轮退款（轮次 +1），再按新轮次冻结资金。
        // 顺序很重要：freeze 的幂等键要带上本轮轮次，否则第二轮申请的冻结
        // 会与第一轮撞键被静默丢弃，最终把冻结余额扣成负数。
        beginRefundCycle(order);

        // 冻结资金
        freezeFunds(order);

        order.setPreRefundStatus(order.getStatus());
        order.setStatus(STATUS_REFUNDING);
        order.setRefundType(refundType);
        order.setRefundAmount(refundAmount != null ? refundAmount : order.getPayAmount());
        order.setRefundReason(refundReason);
        order.setRefundRequestedAt(LocalDateTime.now());
        // 新一轮尚未处理完，清掉上一轮的处理时间（依赖实体上的 FieldStrategy.ALWAYS）
        order.setRefundResolvedAt(null);
        if (returnTrackingNumber != null) {
            order.setReturnTrackingNumber(returnTrackingNumber);
            order.setReturnTrackingCompany(returnTrackingCompany);
        }
        orderMapper.updateById(order);
        logAction(orderId, "refund_request", "customer", customerId, refundType + " 金额:" + order.getRefundAmount());
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 商户同意退款
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void approveRefund(Long orderId, Long merchantId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_REFUNDING);

        BigDecimal refundAmount = order.getRefundAmount() != null ? order.getRefundAmount() : order.getPayAmount();
        boolean fullRefund = refundAmount.compareTo(order.getPayAmount()) >= 0;

        // 统一在一处算出最终资金变动（微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更）
        // 解冻：冻结 -payAmount；从营收中扣除退款金额；可用余额 = 解冻回来的 - 退款
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "refund_approve")
                .frozen(order.getPayAmount().negate())
                .revenue(refundAmount.negate())
                .available(order.getPayAmount().subtract(refundAmount)), order);

        if (fullRefund) {
            restoreStock(orderId);
            order.setStatus(STATUS_CANCELLED);
        } else {
            order.setStatus(STATUS_COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        }
        order.setRefundResolvedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        logAction(orderId, "refund_approve", "merchant", merchantId, "同意退款 金额:" + refundAmount);
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 商户拒绝退款
     */
    @Override
    @Transactional
    public void rejectRefund(Long orderId, Long merchantId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_REFUNDING);

        // 回退到退款前状态
        String originalStatus = order.getPreRefundStatus();
        if (originalStatus == null) originalStatus = STATUS_PAID;
        order.setStatus(originalStatus);
        order.setPreRefundStatus(null);

        // 解冻（仅把冻结归还到可用余额，营收不变）
        // 微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "refund_reject")
                .frozen(order.getPayAmount().negate())
                .available(order.getPayAmount()), order);

        orderMapper.updateById(order);
        logAction(orderId, "refund_reject", "merchant", merchantId, "拒绝退款 回退至:" + originalStatus);
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 管理员强制退款（直接进入退款中并执行退款）
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void forceRefundByAdmin(Long orderId, Long adminId, BigDecimal refundAmount) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        if (!STATUS_PAID.equals(order.getStatus())
                && !STATUS_SHIPPED.equals(order.getStatus())
                && !STATUS_RECEIVED.equals(order.getStatus()))
            throw new RuntimeException("当前状态不可强制退款");

        // 强制退款也是一轮独立退款：轮次 +1 后再冻结，避免与顾客自发的退款轮次撞键
        beginRefundCycle(order);

        freezeFunds(order);
        order.setPreRefundStatus(order.getStatus());
        order.setStatus(STATUS_REFUNDING);
        order.setRefundAmount(refundAmount != null ? refundAmount : order.getPayAmount());
        order.setRefundRequestedAt(LocalDateTime.now());
        order.setRefundResolvedAt(null);
        orderMapper.updateById(order);
        logAction(orderId, "force_refund", "admin", adminId, "管理员强制退款");

        // 立即执行退款
        BigDecimal actualRefund = refundAmount != null ? refundAmount : order.getPayAmount();
        boolean fullRefund = actualRefund.compareTo(order.getPayAmount()) >= 0;

        // 微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "force_refund_admin")
                .frozen(order.getPayAmount().negate())
                .revenue(actualRefund.negate())
                .available(order.getPayAmount().subtract(actualRefund)), order);

        if (fullRefund) {
            restoreStock(orderId);
            order.setStatus(STATUS_CANCELLED);
        } else {
            order.setStatus(STATUS_COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        }
        order.setRefundResolvedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 管理员强制完成（跳过确认收货）
     */
    @Override
    @Transactional
    public void forceCompleteByAdmin(Long orderId, Long adminId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        if (!STATUS_SHIPPED.equals(order.getStatus())
                && !STATUS_RECEIVED.equals(order.getStatus()))
            throw new RuntimeException("当前状态不可强制完成");

        order.setStatus(STATUS_COMPLETED);
        order.setCompletedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        logAction(orderId, "force_complete", "admin", adminId, "管理员强制完成");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    /**
     * 管理员强制退款完成（绕过商户，在退款中状态下执行）
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void forceRefundComplete(Long orderId, Long adminId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new RuntimeException("订单不存在");
        assertStatus(order, STATUS_REFUNDING);

        BigDecimal refundAmount = order.getRefundAmount() != null ? order.getRefundAmount() : order.getPayAmount();
        boolean fullRefund = refundAmount.compareTo(order.getPayAmount()) >= 0;

        // 微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "force_refund_complete")
                .frozen(order.getPayAmount().negate())
                .revenue(refundAmount.negate())
                .available(order.getPayAmount().subtract(refundAmount)), order);

        if (fullRefund) {
            restoreStock(orderId);
            order.setStatus(STATUS_CANCELLED);
        } else {
            order.setStatus(STATUS_COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        }
        order.setRefundResolvedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        logAction(orderId, "refund_approve", "admin", adminId, "管理员强制退款完成");
        evictOrderCacheAfterCommit(orderId, order.getCustomerId(), order.getMerchantId());
        publishOrderIndexAfterCommit(orderId);
    }

    // ==================== 跨服务内部接口：标记已评价 ====================

    /**
     * 标记订单已评价（供商家服务评价落库后调用）
     * <p>
     * 微服务拆分说明：替代商家服务原先 {@code OrderRefMapper.updateById} 的跨库直写。
     * <p>
     * <b>为什么走 Service 而不是在内部接口里直接调 mapper</b>：{@code isReviewed} 有两处下游——
     * <ol>
     *   <li>订单详情缓存 {@code cache:order:{id}}：会缓存该字段（TTL 300 秒）；
     *       只改库不清缓存 → 详情页最多 300 秒仍显示「未评价」；</li>
     *   <li>order 索引的 {@code isReviewed} 字段：后台订单列表用它展示/筛选；
     *       不同步 → 列表里长期显示未评价，且筛选结果不对。</li>
     * </ol>
     * 这两步统一放在本方法内，避免「接口自己写一遍、漏掉后续步骤」。
     *
     * @param orderId 订单ID
     * @return 影响行数（0 表示订单不存在）
     */
    @Override
    @Transactional
    public int markOrderReviewed(Long orderId) {
        if (orderId == null) {
            return 0;
        }
        int rows = orderMapper.markReviewed(orderId);
        if (rows <= 0) {
            log.warn("标记订单已评价失败，订单可能不存在，orderId={}", orderId);
            return 0;
        }
        // 事务安全：清缓存 + 发索引事件都放到提交后执行
        Long id = orderId;
        AfterCommitExecutor.run(
                () -> redisService.deleteKey(RedisService.CACHE_ORDER + id),
                () -> publishOrderIndexNow(id));
        return rows;
    }

    // ==================== 定时任务 ====================

    /**
     * 每10分钟扫描：发货超10天自动签收
     */
    @Override
    @Scheduled(fixedDelay = 600_000)
    public void scheduleAutoReceive() {
        List<Order> orders = orderMapper.selectByStatus(STATUS_SHIPPED, null, null);
        LocalDateTime threshold = LocalDateTime.now().minusDays(10);
        for (Order o : orders) {
            if (o.getShippedAt() != null && o.getShippedAt().isBefore(threshold)) {
                try {
                    autoReceive(o.getId());
                } catch (Exception e) {
                    log.error("自动签收失败 orderId={}", o.getId(), e);
                }
            }
        }
    }

    /**
     * 每10分钟扫描：收货超4天无售后自动完成
     */
    @Override
    @Scheduled(fixedDelay = 600_000)
    public void scheduleAutoComplete() {
        List<Order> orders = orderMapper.selectByStatus(STATUS_RECEIVED, null, null);
        LocalDateTime threshold = LocalDateTime.now().minusDays(4);
        for (Order o : orders) {
            if (o.getShippedAt() != null && o.getShippedAt().isBefore(threshold)) {
                try {
                    completeOrder(o.getId(), "system", null);
                } catch (Exception e) {
                    log.error("自动完成失败 orderId={}", o.getId(), e);
                }
            }
        }
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> buildAddressSnapshot(Address address) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("receiverName", address.getReceiverName());
        snapshot.put("receiverPhone", address.getReceiverPhone());
        snapshot.put("province", address.getProvince());
        snapshot.put("city", address.getCity());
        snapshot.put("district", address.getDistrict());
        snapshot.put("detailAddress", address.getDetailAddress());
        return snapshot;
    }

    /**
     * 通过 Feign 扣减库存（Seata AT 分支事务）
     * <p>
     * 微服务拆分说明：原 productSkuService.decreaseStockSync 返回 boolean 由调用方判断，
     * 拆分后改为 Feign 调 inventory.deduct，返回 Result.code=200 成功，否则抛异常触发 Seata 回滚。
     */
    private void deductStockViaFeign(Long skuId, int quantity) {
        Result<?> r = inventoryClient.deduct(skuId, quantity);
        if (r == null || r.getCode() == null || r.getCode() != 200) {
            throw new RuntimeException("商品库存不足，SKU ID: " + skuId);
        }
    }

    /**
     * 通过 Feign 查询 SKU 信息
     * <p>
     * 微服务拆分说明：原 ProductSkuRefMapper.selectSkuById 跨库直查 product_skus，
     * 「独立库」后改为 Feign 调商品服务内部接口。
     * 调用失败直接抛出异常（下单链路不能带着缺失的 SKU 信息继续）。
     */
    private ProductSkuInfo getSkuViaFeign(Long skuId) {
        Result<ProductSkuInfo> r = productInternalClient.getSku(skuId);
        if (r == null || r.getCode() == null || r.getCode() != 200) {
            throw new RuntimeException("查询SKU失败，SKU ID: " + skuId);
        }
        return r.getData();
    }

    /**
     * 通过 Feign 查询商品信息
     * <p>
     * 微服务拆分说明：原 ProductRefMapper.selectProductById 跨库直查 products，
     * 「独立库」后改为 Feign 调商品服务内部接口。
     */
    private ProductInfo getProductViaFeign(Long productId) {
        Result<ProductInfo> r = productInternalClient.getProduct(productId);
        if (r == null || r.getCode() == null || r.getCode() != 200) {
            throw new RuntimeException("查询商品失败，商品 ID: " + productId);
        }
        return r.getData();
    }

    /**
     * 回补库存（订单取消/退款时调用）
     * <p>
     * 微服务拆分说明：原 productSkuService.restoreStockBySkuId → Feign 调 inventory.restore。
     */
    private void restoreStock(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectByOrderId(orderId);
        for (OrderItem item : items)
            inventoryClient.restore(item.getSkuId(), item.getQuantity());
    }

    /**
     * 冻结商户资金
     * <p>
     * 微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更
     * （可用余额 -payAmount，冻结余额 +payAmount）。
     * <p>
     * 注意：原实现仅在「可用余额 >= 冻结金额」时才冻结（静默跳过不足的情况）；
     * 改为原子增量后由商家服务侧保证非负校验语义，此处保持同样的业务分支不做额外拦截。
     */
    private void freezeFunds(Order order) {
        BigDecimal freezeAmount = order.getPayAmount();
        if (freezeAmount == null) return;
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "freeze")
                .available(freezeAmount.negate())
                .frozen(freezeAmount), order);
    }

    /**
     * 解冻商户资金（当前无调用点，保留以备后续「仅解冻不退款」场景）
     * <p>
     * 微服务拆分说明：原 MerchantRefMapper 跨库读改写 → Feign 原子变更
     * （冻结余额 -payAmount，可用余额 +payAmount）。
     * <p>
     * 与 {@link #freezeFunds} 一致，走带退款轮次的幂等键：解冻同样属于退款资金动作，
     * 若沿用无轮次的 {@code release:orderId}，多轮退款时会被静默去重而漏解冻。
     */
    private void releaseFrozenFunds(Order order) {
        BigDecimal freezeAmount = order.getPayAmount();
        if (freezeAmount == null) return;
        enqueueRefundFundsChange(MerchantFundsChangeRequest.of(order.getMerchantId(), "release")
                .frozen(freezeAmount.negate())
                .available(freezeAmount), order);
    }

    private void logAction(Long orderId, String action, String operatorType, Long operatorId, String detail) {
        OrderActionLog logEntry = new OrderActionLog();
        logEntry.setOrderId(orderId);
        logEntry.setAction(action);
        logEntry.setOperatorType(operatorType);
        logEntry.setOperatorId(operatorId);
        logEntry.setDetail(detail);
        logEntry.setCreatedAt(LocalDateTime.now());
        actionLogMapper.insert(logEntry);
    }

    // ==================== 缓存失效 ====================

    /**
     * 订单变更后发布索引同步事件（事务提交后）
     * <p>
     * 微服务拆分说明（列表查询改造）：订单列表改由 ES 承载，任一状态流转都会改变
     * 列表展示（状态、金额、是否已评价），因此每个写操作都需要通知搜索服务同步该订单。
     * <p>
     * 为什么放在**事务提交后**：若在事务内发消息，搜索服务可能在订单尚未提交时就回源读取，
     * 读到的是旧值（甚至读不到），导致索引写入过期数据。注册事务同步回调可保证
     * 「本地事务已提交」才投递。
     * <p>
     * 无事务上下文时（如 {@code @Scheduled} 自调用导致 AOP 事务代理未生效）直接投递，
     * 与 {@link #evictOrderCacheAfterCommit} 的处理保持一致。
     */
    private void publishOrderIndexAfterCommit(Long orderId) {
        if (orderId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishOrderIndexNow(orderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishOrderIndexNow(orderId);
            }
        });
    }

    /** 立即投递订单索引同步事件（发送失败不影响主流程，由发布器内部兜底记录） */
    private void publishOrderIndexNow(Long orderId) {
        indexEventPublisher.publishUpsert(IndexChangedEvent.EntityType.ORDER, orderId);
    }

    private void evictOrderCacheAfterCommit(Long orderId, Long customerId, Long merchantId) {
        // 没有事务上下文（如 @Scheduled 自调用导致 AOP 事务代理失效），直接执行缓存清理
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictOrderCacheNow(orderId, customerId, merchantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictOrderCacheNow(orderId, customerId, merchantId);
            }
        });
    }

    /**
     * 订单变更后的 Redis 缓存清理
     * <p>
     * 列表查询改造说明：原实现里有两条 {@code deleteByPrefix(CACHE_ORDER_LIST)}（立即 + 延迟双删），
     * 用于清空**所有用户、所有页码**的订单列表缓存 —— 这正是本次改造要消除的重失效。
     * 订单列表已改为查 ES 索引（见 {@link OrderListIndexService}），
     * 因此这里**不再清理列表缓存**，只保留单订单详情与统计/趋势缓存的清理。
     * <p>
     * 保留的清理项说明：
     * - {@code CACHE_ORDER + orderId}：订单详情缓存，必须清（否则详情页读到旧状态）；
     * - {@code CACHE_ORDER_STATS / CACHE_ORDER_TREND}：统计类缓存，量小且与列表无关；
     * - {@code CACHE_CART_LIST + customerId}：下单会清购物车，需同步失效购物车缓存；
     * - {@code clearShopCache(merchantId)}：店铺维度缓存（商品/商家相关）。
     */
    private void evictOrderCacheNow(Long orderId, Long customerId, Long merchantId) {
        if (orderId != null) redisService.deleteKey(RedisService.CACHE_ORDER + orderId);
        redisService.deleteKey(RedisService.CACHE_ORDER_STATS);
        redisService.deleteKey(RedisService.CACHE_ORDER_TREND);
        if (customerId != null) redisService.deleteKey(RedisService.CACHE_CART_LIST + customerId);
        if (merchantId != null) redisService.clearShopCache(merchantId);

        cacheDelayScheduler.schedule(() -> {
            try {
                if (orderId != null) redisService.deleteKey(RedisService.CACHE_ORDER + orderId);
                redisService.deleteKey(RedisService.CACHE_ORDER_STATS);
                redisService.deleteKey(RedisService.CACHE_ORDER_TREND);
                if (customerId != null) redisService.deleteKey(RedisService.CACHE_CART_LIST + customerId);
                if (merchantId != null) redisService.clearShopCache(merchantId);
            } catch (Exception e) {
                log.error("延迟双删订单缓存失败", e);
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    private String generateOrderNo() {
        String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = (int) (Math.random() * 900000) + 100000;
        return prefix + random;
    }
}

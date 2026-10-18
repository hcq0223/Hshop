package com.shopai.order.service;

import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.order.entity.Order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单服务接口
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.OrderService 迁移至 shopai-order 服务。
 * 主要改造点：
 * 1. 扣库存：原 productSkuService.decreaseStockSync → Feign 调 inventory.deduct（Seata AT 分支事务）。
 * 2. 回补库存：原 productSkuService.restoreStockBySkuId → Feign 调 inventory.restore。
 * 3. 累销量：原 productMapper.incrementSalesCount → Feign 调商品服务内部接口 internal/sales。
 * 4. 查 SKU/商品：原 productSkuService.getSkuById / productMapper.selectById
 *    → Feign 调商品服务内部接口（ProductInternalClient.getSku / getProduct）。
 * 5. 商户资金（营收/余额/冻结）：原 merchantMapper.selectById/updateById
 *    → Feign 调商家服务资金接口，由其原子累加（MerchantInternalClient.changeFunds）。
 * 6. 店铺名/Logo：原 OrderMapper.xml 的 JOIN merchants
 *    → 查询后由 OrderEnrichService 通过 Feign 批量补齐。
 * 7. 下单/退款链路标注 @GlobalTransactional（Seata AT 全局事务）。
 * <p>
 * 状态机：pending_pay → paid → shipped → received → completed
 * 逆向：pending_pay → cancelled | * → refunding → cancelled/completed/original
 * <p>
 * 分层改造（2026-10-05）：本接口 + OrderServiceImpl（extends ServiceImpl&lt;OrderMapper, Order&gt;）标准分层。
 */
public interface OrderService {

    // ==================== 查询（缓存） ====================

    Order getOrderDetail(Long id);

    List<Order> getOrdersByCustomer(Long customerId, int page, int size);

    List<Order> getOrdersByMerchant(Long merchantId);

    List<Order> getAllOrders(int page, int size);

    // 订单历史（已完成 + 已取消）
    List<Order> getHistoryOrders(Long customerId, Long merchantId);

    List<Order> getOrdersByStatus(String status, Long customerId, Long merchantId);

    Order getOrderByOrderNo(String orderNo);

    // ==================== 创建订单 ====================

    /**
     * 购物车结算下单（Seata AT 全局事务）
     * <p>
     * 微服务拆分说明：原单库本地事务（扣库存 + 写订单 + 累销量 + 清购物车），
     * 拆分后标注 @GlobalTransactional，扣库存/累销量为跨服务 Feign 调用（Seata 分支事务）。
     * 任一步失败 → Seata 全局回滚。
     */
    List<Order> createOrder(Long customerId, Long merchantId, List<Long> selectedCartIds, Long addressId);

    /**
     * 直接购买下单（Seata AT 全局事务）
     */
    Order createOrderDirect(Long customerId, Long skuId, int quantity, Long addressId);

    /**
     * 秒杀下单（供秒杀服务经 Feign 异步调用，<b>不扣主库存</b>）
     * <p>
     * 与 {@link #createOrderDirect} 的关键区别：
     * <ul>
     *   <li><b>不调用 inventory 扣减</b>：秒杀库存由 shopai-seckill 自己的
     *       {@code seckill_stock} 表独立管理（独立库存模型），调用方在进入
     *       本方法之前<b>已经扣好了</b>它那份额度。这里再扣一次主库存，
     *       等于一笔交易扣两处，会把常规库存白白吃掉。</li>
     *   <li><b>成交价用调用方传入的 seckillPrice</b>：不能用 SKU 的日常价，
     *       否则用户抢到 9.9 的秒杀、订单金额却是原价 199。</li>
     *   <li><b>订单号可传入</b>：秒杀消息是「至少一次」投递，调用方用固定订单号
     *       做幂等，重复投递时先按订单号查已有订单直接返回。</li>
     * </ul>
     *
     * @param customerId  客户ID
     * @param skuId       SKU ID
     * @param quantity    数量
     * @param addressId   收货地址ID
     * @param seckillPrice 秒杀成交单价（必须由调用方给定，不接受 null）
     * @param orderNo      指定订单号（幂等键）。传 null 则自动生成。
     * @return 下单结果（含订单ID/订单号，以及是否命中幂等）
     */
    SeckillOrderResult createSeckillOrder(Long customerId, Long skuId, int quantity, Long addressId,
                                          BigDecimal seckillPrice, String orderNo);

    // ==================== 状态流转 ====================

    /**
     * 顾客支付
     */
    void payOrder(Long orderId, Long customerId);

    /**
     * 商户发货
     */
    void shipOrder(Long orderId, Long merchantId, String trackingNumber, String trackingCompany);

    /**
     * 更新物流单号（已发货状态，商户可多次修改）
     */
    void updateTracking(Long orderId, Long merchantId, String trackingNumber, String trackingCompany);

    /**
     * 顾客确认收货
     */
    void receiveOrder(Long orderId, Long customerId);

    /**
     * 系统自动签收（发货超10天）
     */
    void autoReceive(Long orderId);

    /**
     * 完成订单（顾客或系统触发：收货后4天无售后）
     */
    void completeOrder(Long orderId, String operatorType, Long operatorId);

    /**
     * 顾客取消订单（待支付，15分钟内）
     * <p>
     * 微服务拆分说明：回补库存改为 Feign 调 inventory.restore，标注 @GlobalTransactional。
     */
    void cancelOrderByCustomer(Long orderId, Long customerId);

    /**
     * 商户强制关闭订单（待支付）
     */
    void forceCloseByMerchant(Long orderId, Long merchantId);

    /**
     * 管理员强制取消（任意非终态）
     */
    void forceCancelByAdmin(Long orderId, Long adminId);

    // ==================== 售后 ====================

    /**
     * 顾客申请退款/退货退款
     */
    void requestRefund(Long orderId, Long customerId, String refundType, BigDecimal refundAmount,
                       String refundReason, String returnTrackingNumber, String returnTrackingCompany);

    /**
     * 商户同意退款
     */
    void approveRefund(Long orderId, Long merchantId);

    /**
     * 商户拒绝退款
     */
    void rejectRefund(Long orderId, Long merchantId);

    /**
     * 管理员强制退款（直接进入退款中并执行退款）
     */
    void forceRefundByAdmin(Long orderId, Long adminId, BigDecimal refundAmount);

    /**
     * 管理员强制完成（跳过确认收货）
     */
    void forceCompleteByAdmin(Long orderId, Long adminId);

    /**
     * 管理员强制退款完成（绕过商户，在退款中状态下执行）
     */
    void forceRefundComplete(Long orderId, Long adminId);

    /**
     * 标记订单已评价（供商家服务评价落库时通过内部接口调用）
     * <p>
     * 除更新 {@code orders.is_reviewed} 外，还会清理订单详情缓存
     * 并同步 order 索引（{@code isReviewed} 是后台订单列表展示与筛选的字段）。
     *
     * @param orderId 订单ID
     * @return 影响行数（0 表示订单不存在）
     */
    int markOrderReviewed(Long orderId);

    // ==================== 定时任务 ====================

    /**
     * 每10分钟扫描：发货超10天自动签收
     */
    void scheduleAutoReceive();

    /**
     * 每10分钟扫描：收货超4天无售后自动完成
     */
    void scheduleAutoComplete();
}

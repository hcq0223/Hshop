package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 订单 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.OrderMapper 迁移至 shopai-order 服务，
 * 包名变更为 com.shopai.order.mapper，XML namespace 同步更新。
 * <p>
 * 【跨库 JOIN 去除说明】原查询通过 {@code JOIN merchants / products / customers}
 * 填充店铺名、商品销量、客户昵称。按「独立库」处理后这些表分别归属
 * 商家 / 商品 / 用户服务，本 Mapper 的 SQL **只访问 orders 与 order_items**：
 * - 店铺名/Logo 由 Service 层通过 Feign 调商家服务补齐（见 OrderEnrichService）；
 * - 商品名/图片在下单时已冗余写入 order_items，无需联查 products。
 * <p>
 * 本 Mapper 同时提供商家服务所需的内部查询（周营收归档计数、评价标记），
 * 通过订单服务的内部接口（{@code /api/order/internal/**}）对外暴露。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 根据客户ID查询其所有订单（分页插件拦截，按创建时间倒序）
     */
    List<Order> selectByCustomerId(@Param("customerId") Long customerId,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /**
     * 根据商户ID查询订单（商户后台订单管理）
     */
    List<Order> selectByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * 根据订单号查询
     */
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 查询订单详情（联查订单项；店铺字段由 Service 层 Feign 补齐）
     */
    Order selectDetailById(@Param("id") Long id);

    /**
     * 根据状态查询订单（可同时指定客户或商户过滤，参数可选）
     * 状态通常为 'pending_pay','paid','shipped','received','completed','cancelled' 之一
     */
    List<Order> selectByStatus(@Param("status") String status,
                               @Param("customerId") Long customerId,
                               @Param("merchantId") Long merchantId);

    /**
     * 管理员查询全平台订单（不按客户/商户过滤，按创建时间倒序）
     */
    List<Order> selectAllOrders(@Param("offset") int offset, @Param("limit") int limit);

    /** 近N天每日订单汇总：日期、订单数、总金额（供用户服务仪表盘） */
    List<java.util.Map<String, Object>> dailyOrderStats(@Param("days") int days);

    /** 按状态统计订单数（供用户服务仪表盘） */
    List<java.util.Map<String, Object>> countGroupByStatus();

    /** 统计订单总数（供用户服务仪表盘） */
    long countAll();

    /**
     * 商户销售额排名（供用户服务仪表盘）
     * <p>
     * 已去除 {@code JOIN merchants}：只返回 {@code merchantId + totalSales}，
     * 店铺名由用户服务再调商家服务补齐。
     *
     * @param limit   取前N条
     * @param orderBy 排序方向，仅允许 "desc" / "asc"（由 Service 层做白名单校验）
     */
    List<java.util.Map<String, Object>> merchantSalesRanking(@Param("limit") int limit,
                                                            @Param("orderBy") String orderBy);

    /**
     * 统计某商家在时间区间内的指定状态订单数（供商家服务周营收归档）
     * <p>
     * 原调用方：商家服务 OrderRefMapper.countCompletedOrders（跨库直查 orders）。
     *
     * @param merchantId 商家ID
     * @param statuses   订单状态集合（completed / received）
     * @param start      起始时间（含）
     * @param end        结束时间（含）
     */
    int countCompletedOrders(@Param("merchantId") Long merchantId,
                             @Param("statuses") Collection<String> statuses,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    /**
     * 查询订单的 is_reviewed 标记（供商家服务评价落库做二次校验）
     * <p>
     * 原调用方：商家服务 OrderRefMapper.selectById(orderId).getIsReviewed()。
     *
     * @param orderId 订单ID
     * @return 1=已评价 / 0=未评价；订单不存在时返回 null
     */
    Integer selectIsReviewed(@Param("orderId") Long orderId);

    /**
     * 标记订单已评价（供商家服务评价落库时调用）
     * <p>
     * 原调用方：商家服务 OrderRefMapper.updateById(order)（把 is_reviewed 改为 1）。
     *
     * @param orderId 订单ID
     * @return 影响行数
     */
    int markReviewed(@Param("orderId") Long orderId);

    /**
     * 条件式流转订单状态（CAS），用于需要「抢到执行权」的状态变更
     * <p>
     * <b>解决什么问题</b>：原实现是「selectById 读订单 → assertStatus 校验
     * → updateById 写回」。这是典型的「先读后判」，在并发下会同时通过校验：
     * 两个支付请求都读到 {@code pending_pay}、都判定合法、都执行，于是
     * 商户营收被累加两次、订单被写两次、两条事务互相争锁。
     * <p>
     * 改成把状态判断下推到 {@code WHERE} 条件里，由数据库行锁保证原子性：
     * <b>只有第一个事务能拿到 rows=1，其余全部得到 rows=0</b>，据此即可判定
     * 「我被别人抢先了」，从而安全地跳过后续副作用（累加资金、发事件等）。
     * <p>
     * 调用方约定：
     * <pre>
     *   int rows = orderMapper.casUpdateStatus(orderId, "pending_pay", "paid", now);
     *   if (rows == 0) {
     *       // 已被其它请求处理（或状态不符）→ 幂等返回，不要再做任何副作用
     *   }
     * </pre>
     *
     * @param orderId       订单ID
     * @param expectStatus  期望的当前状态（CAS 的 compare 值）
     * @param newStatus     目标状态
     * @param paidAt        支付时间；仅支付流转传值，其它流转传 null（null 时不更新该列）
     * @return 影响行数；<b>0 表示状态已被别人改过，本次不应继续</b>
     */
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("expectStatus") String expectStatus,
                        @Param("newStatus") String newStatus,
                        @Param("paidAt") java.time.LocalDateTime paidAt);

    /** 按多个状态+角色条件查询已完成/已取消的历史订单 */
    List<Order> selectHistoryOrders(@Param("statusList") List<String> statusList,
                                   @Param("customerId") Long customerId,
                                   @Param("merchantId") Long merchantId);

    /**
     * 全量查询订单（供全量重建 order 索引使用）
     * <p>
     * 微服务拆分说明（列表查询改造）：订单列表迁至 ES 后，需要一个「全量源数据」
     * 入口用于索引全量重建（MQ 消息丢失或索引结构变更时兜底）。
     */
    List<Order> selectAllForIndex();
}

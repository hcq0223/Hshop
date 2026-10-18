package com.shopai.order.controller;

import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.OrderInfo;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.dto.internal.order.DailyOrderStat;
import com.shopai.common.dto.internal.order.MerchantSalesStat;
import com.shopai.common.dto.internal.order.OrderStatusCount;
import com.shopai.common.dto.internal.seckill.SeckillOrderCreateRequest;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.order.entity.Order;
import com.shopai.order.feign.SeckillInternalClient;
import com.shopai.order.mapper.OrderMapper;
import com.shopai.order.service.OrderIndexAssembler;
import com.shopai.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 订单服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明：{@code orders} 表归属 shopai-order 服务。按「独立库」处理跨服务访问后，
 * 原先其他服务对 orders 表的直查/直写全部改为调用本控制器：
 * <p>
 * 1. shopai-merchant（原 OrderRefMapper）：
 *    - 周营收归档统计完成订单数 → {@code GET /count}
 *    - 评价落库校验并标记 is_reviewed → {@code GET /{orderId}} + {@code POST /{orderId}/reviewed}
 * 2. shopai-user（原 DashboardMapper）：
 *    - 订单总数 / 近N天每日订单 / 订单状态分布 / 商家销售额排名 → {@code /dashboard/**}
 * <p>
 * 安全说明：{@code /api/order/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.ORDER_INTERNAL)
@RequiredArgsConstructor
public class OrderInternalController {

    private static final Logger log = LoggerFactory.getLogger(OrderInternalController.class);

    /** 与 OrderMapper.xml 的 dailyOrderStats 保持一致：DATE() 返回 yyyy-MM-dd */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OrderMapper orderMapper;
    // 微服务拆分新增：order 索引视图组装（列表查询改造）
    private final OrderIndexAssembler orderIndexAssembler;
    // 「标记已评价」委托给 Service 执行（含清缓存与索引同步，避免两处实现漏步骤）
    private final OrderService orderService;
    // 秒杀服务回调：订单创建成功后通知秒杀侧落账（见 createSeckillOrder）
    private final SeckillInternalClient seckillInternalClient;

    // ==================== 列表索引回源（搜索服务 Feign 调用） ====================

    /**
     * 取单个订单的索引视图（搜索服务消费 order.changed 后回源调用）
     * <p>
     * 微服务拆分说明（列表查询改造）：订单列表改由 ES 承载后，索引文档需要
     * 「店铺名 + 客户昵称 + 订单项摘要」等冗余字段，这些只有订单服务能组装
     * （需调商家/用户服务内部接口），故由本接口回源提供给搜索服务。
     *
     * @param orderId 订单ID
     * @return 索引视图；订单不存在时 data 为 null
     */
    @GetMapping("/index/order/{orderId}")
    public Result<OrderIndexItem> indexOrder(@PathVariable("orderId") Long orderId) {
        return Result.ok(orderIndexAssembler.assemble(orderId));
    }

    /**
     * 取全量订单索引视图（order 索引全量重建用）
     */
    @GetMapping("/index/orders")
    public Result<List<OrderIndexItem>> indexAllOrders() {
        return Result.ok(orderIndexAssembler.assembleAll());
    }

    // ==================== 商家服务所需 ====================

    /**
     * 按订单ID查询订单信息
     * <p>
     * 原调用方：商家服务 OrderRefMapper.selectById（共享单库直查 orders）。
     *
     * @param orderId 订单ID
     * @return 订单信息；不存在时 data 为 null
     */
    @GetMapping("/{orderId}")
    public Result<OrderInfo> getOrder(@PathVariable("orderId") Long orderId) {
        return Result.ok(toOrderInfo(orderMapper.selectById(orderId)));
    }

    /**
     * 标记订单已评价（is_reviewed = 1）
     * <p>
     * 原调用方：商家服务 OrderRefMapper.updateById（把 is_reviewed 改为 1）。
     * <p>
     * <b>实现说明</b>：本接口**委托 {@link OrderService#markOrderReviewed}** 执行，
     * 而不是直接调 mapper —— 「标记已评价」还需清订单详情缓存并同步 order 索引
     * （{@code isReviewed} 是后台订单列表的展示与筛选字段），
     * 这两步已在 Service 方法内统一处理。
     *
     * @param orderId 订单ID
     * @return 影响行数
     */
    @PostMapping("/{orderId}/reviewed")
    public Result<Integer> markReviewed(@PathVariable("orderId") Long orderId) {
        return Result.ok(orderService.markOrderReviewed(orderId));
    }

    // ==================== 秒杀服务所需 ====================

    /**
     * 秒杀下单（秒杀服务经 Feign 调用）
     * <p>
     * <b>为什么不复用外部的 {@code POST /api/order/create}</b>：
     * <ol>
     *   <li>外部接口从网关 X-User-Id 头取客户身份，而秒杀是 MQ 异步调用，
     *       没有用户请求上下文，拿不到那个头；</li>
     *   <li>外部接口走 {@code createOrderDirect}，它会<b>再扣一次主库存</b>。
     *       秒杀额度由秒杀服务的 {@code seckill_stock} 独立管理，
     *       调用方在发这条消息之前已经扣过了，这里再扣就是一笔交易扣两处；</li>
     *   <li>外部接口的成交价取 SKU 日常价，秒杀必须用秒杀价。</li>
     * </ol>
     * <p>
     * <b>幂等</b>：秒杀侧的消息投递是「至少一次」语义，同一条消息可能到达多次。
     * 本接口以 {@code orderNo} 为幂等键 —— 秒杀服务用固定的
     * {@code SK + seckillRecordId} 作为订单号，重复调用时
     * {@code createSeckillOrder} 会查到已有订单直接返回，不会重复下单。
     * <p>
     * 安全说明：{@code /api/order/internal/**} 不对网关暴露，仅服务间 Feign 直连。
     *
     * @param req 秒杀下单请求（见 {@link SeckillOrderCreateRequest}）
     * @return 下单结果（见 {@link SeckillOrderResult}，含是否命中幂等）
     */
    @PostMapping("/seckill/order")
    public Result<SeckillOrderResult> createSeckillOrder(@RequestBody SeckillOrderCreateRequest req) {
        if (req.getCustomerId() == null || req.getSkuId() == null || req.getAddressId() == null) {
            return Result.error("customerId、skuId、addressId 均不能为空");
        }
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            return Result.error("quantity 必须为正整数");
        }
        if (req.getSeckillPrice() == null) {
            return Result.error("seckillPrice 不能为空");
        }
        try {
            SeckillOrderResult result = orderService.createSeckillOrder(
                    req.getCustomerId(), req.getSkuId(), req.getQuantity(), req.getAddressId(),
                    req.getSeckillPrice(), req.getOrderNo());
            // 回调秒杀服务落账（抢购记录置成功、写流水、锁定库存转已售、累加销量）。
            //
            // 【关键设计】回调失败必须吞掉，绝不能让订单创建回滚：
            //   订单已经建好了，回滚只会让用户既没订单、库存也白扣。
            //   回调失败由秒杀侧兜住 —— 它的本地消息仍是 sent 状态，
            //   会被重投任务重新投递，而本方法按 orderNo 幂等，
            //   于是重投时会再次触发回调并成功落账。
            //   这就是「最终一致」的体现：允许短暂不一致，但不允许丢单。
            try {
                seckillInternalClient.orderCreated(result);
            } catch (Exception e) {
                log.error("回调秒杀服务落账失败（订单已创建，将由秒杀侧重投兜底）, orderNo={}, orderId={}",
                        result.getOrderNo(), result.getOrderId(), e);
            }
            return Result.ok("秒杀订单创建成功", result);
        } catch (RuntimeException e) {
            log.warn("秒杀订单创建失败, customerId={}, skuId={}, addressId={}, orderNo={}, reason={}",
                    req.getCustomerId(), req.getSkuId(), req.getAddressId(), req.getOrderNo(),
                    e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 统计某商家在时间区间内的完成订单数（商家服务周营收归档用）
     * <p>
     * 原调用方：商家服务 OrderRefMapper.countCompletedOrders。
     *
     * @param merchantId 商家ID
     * @param statuses   逗号分隔的订单状态，如 "completed,received"
     * @param start      起始时间（含），格式 yyyy-MM-ddTHH:mm:ss
     * @param end        结束时间（含），格式 yyyy-MM-ddTHH:mm:ss
     * @return 订单数
     */
    @GetMapping("/count")
    public Result<Integer> countCompletedOrders(@RequestParam("merchantId") Long merchantId,
                                               @RequestParam(value = "statuses", defaultValue = "completed,received") String statuses,
                                               @RequestParam("start") String start,
                                               @RequestParam("end") String end) {
        List<String> statusList = new ArrayList<>();
        for (String s : statuses.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                statusList.add(v);
            }
        }
        if (statusList.isEmpty()) {
            statusList = List.of("completed", "received");
        }
        LocalDateTime startTime = parseDateTime(start);
        LocalDateTime endTime = parseDateTime(end);
        if (startTime == null || endTime == null) {
            return Result.error("时间参数格式错误，应形如 2026-10-05T00:00:00");
        }
        return Result.ok(orderMapper.countCompletedOrders(merchantId, statusList, startTime, endTime));
    }

    // ==================== 用户服务仪表盘所需 ====================

    /**
     * 统计订单总数（用户服务仪表盘）
     * <p>
     * 原调用方：用户服务 DashboardMapper.countOrders（共享单库直查 orders）。
     */
    @GetMapping("/dashboard/order-count")
    public Result<Long> countOrders() {
        return Result.ok(orderMapper.countAll());
    }

    /**
     * 近N天每日订单汇总（用户服务仪表盘）
     * <p>
     * 原调用方：用户服务 DashboardMapper.dailyOrderStats。
     * 返回结构 {date, count, amount} 与原 SQL 别名一致，前端无需改动。
     */
    @GetMapping("/dashboard/daily")
    public Result<List<DailyOrderStat>> dailyOrderStats(@RequestParam("days") int days) {
        List<Map<String, Object>> rows = orderMapper.dailyOrderStats(days);
        List<DailyOrderStat> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            DailyOrderStat stat = new DailyOrderStat();
            stat.setDate(parseDate(row.get("date")));
            stat.setCount(toLong(row.get("count")));
            stat.setAmount(toBigDecimal(row.get("amount")));
            result.add(stat);
        }
        return Result.ok(result);
    }

    /**
     * 按状态统计订单数（用户服务仪表盘）
     * <p>
     * 原调用方：用户服务 DashboardMapper.countGroupByStatus。
     * 返回结构 {status, count} 与原 SQL 别名一致。
     */
    @GetMapping("/dashboard/status-distribution")
    public Result<List<OrderStatusCount>> countGroupByStatus() {
        List<Map<String, Object>> rows = orderMapper.countGroupByStatus();
        List<OrderStatusCount> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            OrderStatusCount item = new OrderStatusCount();
            item.setStatus(row.get("status") == null ? null : String.valueOf(row.get("status")));
            item.setCount(toLong(row.get("count")));
            result.add(item);
        }
        return Result.ok(result);
    }

    /**
     * 商家销售额排名（用户服务仪表盘）
     * <p>
     * 原调用方：用户服务 DashboardMapper.top/bottomMerchantsBySales
     * （原 SQL 为 {@code orders INNER JOIN merchants} 跨库联查店铺名）。
     * <p>
     * 按「独立库」处理后，本接口只按 orders 本地聚合 {@code merchantId + totalSales}，
     * 店铺名由用户服务再调商家服务批量补齐（因此前端看到的 {shopName, totalSales} 结构不变）。
     *
     * @param limit 取前N条
     * @param order 排序方向：desc（Top，默认）/ asc（Bottom）
     */
    @GetMapping("/dashboard/merchant-sales")
    public Result<List<MerchantSalesStat>> merchantSalesRanking(@RequestParam("limit") int limit,
                                                               @RequestParam(value = "order", defaultValue = "desc") String order) {
        // 白名单校验，防止 ${} 拼接注入（orderBy 在 XML 中以 ${} 拼接）
        String orderBy = "asc".equalsIgnoreCase(order) ? "asc" : "desc";
        List<Map<String, Object>> rows = orderMapper.merchantSalesRanking(limit, orderBy);
        List<MerchantSalesStat> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            MerchantSalesStat stat = new MerchantSalesStat();
            stat.setMerchantId(toLong(row.get("merchantId")));
            stat.setTotalSales(toBigDecimal(row.get("totalSales")));
            result.add(stat);
        }
        return Result.ok(result);
    }

    // ==================== 辅助方法 ====================

    /** Order 实体 → 跨服务 OrderInfo DTO */
    private OrderInfo toOrderInfo(Order order) {
        if (order == null) {
            return null;
        }
        OrderInfo info = new OrderInfo();
        info.setId(order.getId());
        info.setCustomerId(order.getCustomerId());
        info.setMerchantId(order.getMerchantId());
        info.setTotalAmount(order.getTotalAmount());
        info.setPayAmount(order.getPayAmount());
        info.setStatus(order.getStatus());
        info.setIsReviewed(order.getIsReviewed());
        info.setCreateTime(order.getCreateTime());
        return info;
    }

    /** 解析 ISO 风格日期时间（容忍空格分隔） */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(v);
        } catch (Exception e) {
            // 兼容仅日期（yyyy-MM-dd），按当天 00:00:00 处理
            try {
                return LocalDate.parse(v).atStartOfDay();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 解析日期（兼容 DATE() → java.sql.Date 与 String 两种返回） */
    private static LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (Exception e) {
            try {
                return LocalDate.parse(value.toString(), DATE_FORMAT);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 宽松转换为 Long */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 宽松转换为 BigDecimal */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}

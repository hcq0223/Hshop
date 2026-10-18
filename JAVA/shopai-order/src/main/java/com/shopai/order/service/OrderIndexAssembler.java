package com.shopai.order.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.CustomerIdPair;
import com.shopai.common.dto.internal.CustomerInfo;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.order.entity.Order;
import com.shopai.order.entity.OrderItem;
import com.shopai.order.feign.CustomerInternalClient;
import com.shopai.order.feign.MerchantInternalClient;
import com.shopai.order.mapper.OrderItemMapper;
import com.shopai.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单索引视图组装服务
 * <p>
 * 微服务拆分说明（列表查询改造）：订单列表改由 ES 承载后，搜索服务需要「订单索引视图」。
 * 该视图包含跨服务/跨聚合的冗余字段，只有订单服务能组装：
 * <ul>
 *   <li>{@code shopName / shopLogo}：来自商家服务内部接口（原为 SQL JOIN merchants）；</li>
 *   <li>{@code customerNickname}：来自用户服务客户内部接口（原为 SQL JOIN customers）；</li>
 *   <li>{@code itemCount / itemSummary / itemImage}：来自本服务的 order_items 表
 *       （原实现要查出整个 orderItems 集合再在内存里统计件数与商品名）。</li>
 * </ul>
 * 组装完再回给搜索服务写索引，搜索服务因此不需要反向依赖各业务的组装逻辑。
 * <p>
 * 降级策略：商家/客户信息取不到时对应字段留空，**不阻断索引写入**——
 * 列表页缺少店铺名远好于整条订单查不出来。
 */
@Service
public class OrderIndexAssembler {

    private static final Logger log = LoggerFactory.getLogger(OrderIndexAssembler.class);

    /** 列表页商品名摘要最多展示的名称个数 */
    private static final int SUMMARY_MAX_NAMES = 3;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private MerchantInternalClient merchantInternalClient;
    @Autowired
    private CustomerInternalClient customerInternalClient;

    /**
     * 组装单个订单的索引视图
     *
     * @param orderId 订单ID
     * @return 索引视图；订单不存在时返回 null
     */
    public OrderIndexItem assemble(Long orderId) {
        if (orderId == null) {
            return null;
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return null;
        }
        List<OrderIndexItem> list = assembleBatch(Collections.singletonList(order));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 批量组装订单索引视图（全量重建用）
     * <p>
     * 批量处理的好处：店铺名与客户昵称各只需一次 Feign 调用（避免 N+1）。
     */
    public List<OrderIndexItem> assembleAll() {
        List<Order> orders = orderMapper.selectAllForIndex();
        return assembleBatch(orders);
    }

    /**
     * 批量组装：先收集去重后的 merchantId / customerId 各做一次批量调用，再逐条映射
     */
    private List<OrderIndexItem> assembleBatch(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 店铺信息（一次批量 Feign；原为 JOIN merchants）
        Map<Long, MerchantBrief> shopMap = loadShops(orders);

        // 2. 客户昵称（一次批量 Feign；原为 JOIN customers）
        Map<Long, String> nicknameMap = loadCustomerNicknames(orders);

        // 3. 订单项摘要（一次批量本地查询；原为查出 orderItems 集合后在内存统计）
        Map<Long, List<OrderItem>> itemMap = loadItems(orders);

        List<OrderIndexItem> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            result.add(toIndexItem(order, shopMap, nicknameMap, itemMap));
        }
        return result;
    }

    /** 批量加载店铺信息（降级：返回空 Map） */
    private Map<Long, MerchantBrief> loadShops(List<Order> orders) {
        Map<Long, MerchantBrief> map = new HashMap<>();
        String ids = orders.stream()
                .map(Order::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<MerchantBrief>> r = merchantInternalClient.listBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (MerchantBrief b : r.getData()) {
                    if (b.getId() != null) {
                        map.put(b.getId(), b);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取店铺信息失败（订单索引将缺少店铺字段），merchantIds={}", ids, e);
        }
        return map;
    }

    /**
     * 批量加载客户昵称（降级：返回空 Map）
     * <p>
     * 两步：customerId → userId（其实订单里存的就是 customerId），再按 customerId 批量取昵称。
     */
    private Map<Long, String> loadCustomerNicknames(List<Order> orders) {
        Map<Long, String> map = new HashMap<>();
        String ids = orders.stream()
                .map(Order::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<CustomerInfo>> r = customerInternalClient.listCustomers(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (CustomerInfo c : r.getData()) {
                    if (c.getId() != null) {
                        map.put(c.getId(), c.getNickname());
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取客户昵称失败（订单索引将缺少客户昵称），customerIds={}", ids, e);
        }
        return map;
    }

    /** 批量加载订单项（本服务本地查询） */
    private Map<Long, List<OrderItem>> loadItems(List<Order> orders) {
        List<Long> orderIds = orders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return orderItemMapper.selectByOrderIds(orderIds).stream()
                    .filter(i -> i.getOrderId() != null)
                    .collect(Collectors.groupingBy(OrderItem::getOrderId));
        } catch (Exception e) {
            log.error("批量获取订单项失败（订单索引将缺少件数/摘要），orderIds={}", orderIds, e);
            return Collections.emptyMap();
        }
    }

    /** Order + 各冗余数据 → 跨服务 OrderIndexItem */
    private OrderIndexItem toIndexItem(Order order, Map<Long, MerchantBrief> shopMap,
                                       Map<Long, String> nicknameMap, Map<Long, List<OrderItem>> itemMap) {
        OrderIndexItem item = new OrderIndexItem();
        item.setId(order.getId() == null ? null : String.valueOf(order.getId()));
        item.setOrderNo(order.getOrderNo());
        item.setCustomerId(order.getCustomerId() == null ? null : String.valueOf(order.getCustomerId()));
        item.setCustomerNickname(order.getCustomerId() == null ? null : nicknameMap.get(order.getCustomerId()));
        item.setMerchantId(order.getMerchantId() == null ? null : String.valueOf(order.getMerchantId()));

        MerchantBrief shop = order.getMerchantId() == null ? null : shopMap.get(order.getMerchantId());
        if (shop != null) {
            item.setShopName(shop.getShopName());
            item.setShopLogo(shop.getLogoUrl());
        }

        item.setTotalAmount(order.getTotalAmount());
        item.setPayAmount(order.getPayAmount());
        item.setFreightAmount(order.getFreightAmount());
        item.setStatus(order.getStatus());
        item.setIsReviewed(order.getIsReviewed());
        item.setFinalized(OrderServiceImpl.STATUS_COMPLETED.equals(order.getStatus())
                || OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus()));
        item.setPaidAt(order.getPaidAt());
        item.setShippedAt(order.getShippedAt());
        item.setCompletedAt(order.getCompletedAt());
        item.setCreateTime(order.getCreateTime());

        // 订单项摘要：列表页「共 N 件 + 商品名摘要 + 主图」
        List<OrderItem> items = order.getId() == null
                ? null : itemMap.get(order.getId());
        if (items != null && !items.isEmpty()) {
            int count = 0;
            for (OrderItem i : items) {
                count += i.getQuantity() == null ? 0 : i.getQuantity();
            }
            item.setItemCount(count);
            List<String> names = items.stream()
                    .map(OrderItem::getProductName)
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
            if (!names.isEmpty()) {
                String head = names.stream().limit(SUMMARY_MAX_NAMES).collect(Collectors.joining("、"));
                item.setItemSummary(names.size() > SUMMARY_MAX_NAMES
                        ? head + " 等" + names.size() + "件" : head);
            }
            item.setItemImage(items.stream()
                    .map(OrderItem::getImageUrl)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse(null));
        } else {
            item.setItemCount(0);
        }
        return item;
    }
}

package com.shopai.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopai.common.dto.internal.index.OrderIndexItem;
import com.shopai.common.dto.internal.query.IndexListResult;
import com.shopai.order.entity.MerchantRef;
import com.shopai.order.entity.Order;
import com.shopai.order.entity.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单索引结果转换器
 * <p>
 * 微服务拆分说明（列表查询改造）：{@link IndexListResult#getRecords()} 是
 * {@code List<Object>}（通用列表查询接口要同时服务五类索引，无法用单一泛型），
 * 经 Feign/JSON 反序列化后元素是 {@code LinkedHashMap}。本类负责把它转成
 * {@link OrderIndexItem}，再映射为订单服务原有的 {@link Order} 结构，
 * 从而**保持对外响应结构不变**。
 * <p>
 * 字段映射说明：
 * - {@code merchant}（店铺名/Logo）：原由 SQL JOIN merchants 填充，索引里已冗余，
 *   此处直接装配为 {@link MerchantRef}，前端 {@code item.merchant.shopName} 照常可用；
 * - {@code orderItems}：索引只存「件数 + 商品名摘要 + 主图」用于列表展示，
 *   为兼容前端对 {@code orderItems} 数组的读取（件数统计与商品名展示），
 *   此处合成一个「汇总项」；下单明细的真实明细仍以订单详情接口为准。
 */
@Service
public class OrderIndexResultMapper {

    private static final Logger log = LoggerFactory.getLogger(OrderIndexResultMapper.class);

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 把索引查询结果映射为订单列表
     *
     * @param result 搜索服务返回的通用列表结果
     * @return 订单列表（保序，与 ES 命中顺序一致）
     */
    public List<Order> toOrders(IndexListResult result) {
        if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
            return Collections.emptyList();
        }
        List<Order> orders = new ArrayList<>(result.getRecords().size());
        for (Object record : result.getRecords()) {
            OrderIndexItem item = toIndexItem(record);
            if (item != null) {
                orders.add(toOrder(item));
            }
        }
        return orders;
    }

    /**
     * 把单个索引记录反序列化为 {@link OrderIndexItem}
     * <p>
     * 单条记录转换失败时跳过该条（不因一条脏数据导致整页失败）。
     */
    private OrderIndexItem toIndexItem(Object record) {
        if (record == null) {
            return null;
        }
        if (record instanceof OrderIndexItem item) {
            return item;
        }
        try {
            return objectMapper.convertValue(record, OrderIndexItem.class);
        } catch (Exception e) {
            log.error("订单索引记录转换失败，已跳过该条: {}", record, e);
            return null;
        }
    }

    /** OrderIndexItem（索引文档）→ Order（对外响应结构） */
    private Order toOrder(OrderIndexItem i) {
        Order order = new Order();
        order.setId(parseLong(i.getId()));
        order.setOrderNo(i.getOrderNo());
        order.setCustomerId(parseLong(i.getCustomerId()));
        order.setMerchantId(parseLong(i.getMerchantId()));
        order.setTotalAmount(i.getTotalAmount());
        order.setPayAmount(i.getPayAmount());
        order.setFreightAmount(i.getFreightAmount());
        order.setStatus(i.getStatus());
        order.setIsReviewed(i.getIsReviewed());
        order.setPaidAt(i.getPaidAt());
        order.setShippedAt(i.getShippedAt());
        order.setCompletedAt(i.getCompletedAt());
        order.setCreateTime(i.getCreateTime());

        // 店铺信息：原本由 SQL JOIN merchants 填充，索引中已冗余
        if (i.getMerchantId() != null) {
            MerchantRef merchant = new MerchantRef();
            merchant.setId(order.getMerchantId());
            merchant.setShopName(i.getShopName());
            merchant.setLogoUrl(i.getShopLogo());
            order.setMerchant(merchant);
        }

        // 订单项：索引只保留列表展示所需信息，合成为一个汇总项以兼容前端读取
        int count = i.getItemCount() == null ? 0 : i.getItemCount();
        if (count > 0 || i.getItemSummary() != null) {
            OrderItem summary = new OrderItem();
            summary.setOrderId(order.getId());
            summary.setProductName(i.getItemSummary());
            summary.setQuantity(count);
            summary.setImageUrl(i.getItemImage());
            // 列表页的每一行都展示同一店铺（下单时按商家分组，单个订单只属于一个商家）
            summary.setMerchant(order.getMerchant());
            order.setOrderItems(Collections.singletonList(summary));
        } else {
            order.setOrderItems(Collections.emptyList());
        }
        return order;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

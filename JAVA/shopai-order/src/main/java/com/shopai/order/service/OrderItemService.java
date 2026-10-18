package com.shopai.order.service;

import com.shopai.order.entity.OrderItem;

import java.util.List;

/**
 * 订单明细服务接口（主要提供查询，写操作由 OrderService 负责）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.OrderItemService 迁移至 shopai-order 服务，
 * 包名变更为 com.shopai.order.service。
 * <p>
 * 分层改造（2026-10-05）：本接口 + OrderItemServiceImpl（extends ServiceImpl&lt;OrderItemMapper, OrderItem&gt;）标准分层。
 */
public interface OrderItemService {

    /**
     * 根据订单ID查询订单明细列表（缓存）
     */
    List<OrderItem> getOrderItemsByOrderId(Long orderId);

    /**
     * 清除订单明细缓存（由 OrderService 在写操作后调用）
     */
    void clearCache(Long orderId);
}

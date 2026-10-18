package com.shopai.order.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.redis.RedisService;
import com.shopai.order.entity.OrderItem;
import com.shopai.order.mapper.OrderItemMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单明细服务实现（主要提供查询，写操作由 OrderService 负责）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.OrderItemService 迁移至 shopai-order 服务，
 * 包名变更为 com.shopai.order.service。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;OrderItemMapper, OrderItem&gt; 实现 OrderItemService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class OrderItemServiceImpl extends ServiceImpl<OrderItemMapper, OrderItem> implements OrderItemService {

    private static final Logger log = LoggerFactory.getLogger(OrderItemService.class);

    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private RedisService redisService;

    // 缓存Key
    private static final String CACHE_ORDER_ITEMS = "cache:order:items:";

    /**
     * 根据订单ID查询订单明细列表（缓存）
     */
    @Override
    public List<OrderItem> getOrderItemsByOrderId(Long orderId) {
        String key = CACHE_ORDER_ITEMS + orderId;
        return redisService.getOrSetTyped(
                key,
                new TypeReference<List<OrderItem>>() {},
                300L,
                () -> orderItemMapper.selectByOrderId(orderId)
        );
    }

    /**
     * 清除订单明细缓存（由 OrderService 在写操作后调用）
     */
    @Override
    public void clearCache(Long orderId) {
        redisService.deleteKey(CACHE_ORDER_ITEMS + orderId);
    }
}

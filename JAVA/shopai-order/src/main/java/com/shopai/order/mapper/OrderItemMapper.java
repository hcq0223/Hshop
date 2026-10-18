package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 订单明细 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.OrderItemMapper 迁移至 shopai-order 服务。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 根据订单ID查询所有明细
     */
    List<OrderItem> selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 按订单ID集合批量查询明细
     * <p>
     * 微服务拆分说明（列表查询改造）：组装 order 索引视图时，需要一次性取回
     * 多个订单的明细来统计「件数 + 商品名摘要」。逐单查询会形成 N+1，
     * 故提供批量入口。
     */
    List<OrderItem> selectByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    /**
     * 批量插入订单明细（用于创建订单时一次性写入）
     */
    int insertBatch(@Param("items") List<OrderItem> items);
}

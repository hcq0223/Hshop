package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.OrderActionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单操作日志 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.OrderActionLogMapper 迁移至 shopai-order 服务。
 */
@Mapper
public interface OrderActionLogMapper extends BaseMapper<OrderActionLog> {
}

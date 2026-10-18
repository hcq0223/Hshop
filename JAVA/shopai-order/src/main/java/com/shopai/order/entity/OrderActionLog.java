package com.shopai.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单操作日志实体（对应 order_action_logs 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.OrderActionLog 迁移至 shopai-order 服务。
 */
@Data
@TableName("order_action_logs")
public class OrderActionLog {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 操作类型: create/pay/cancel/ship/receive/complete/refund_request/refund_approve/refund_reject/force_cancel/force_refund/force_complete/auto_receive/auto_complete */
    private String action;

    /** 操作人类型: customer/merchant/admin/system */
    private String operatorType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /** 操作详情JSON */
    private String detail;

    private LocalDateTime createdAt;
}

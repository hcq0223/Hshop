package com.shopai.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存实体（对应 inventory 表）
 * <p>
 * 微服务拆分说明：库存从单体 ProductSku.stock 字段独立出来，由 shopai-inventory 服务
 * 通过本实体 + 独立 inventory 表管理，为后续"库存独立数据库"铺路。
 * <p>
 * 乐观锁：version 字段配合扣减 SQL（WHERE stock >= #{qty} AND version = ...）防止并发超卖。
 */
@Data
@TableName("inventory")
public class Inventory {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 关联 product_skus.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 当前可售库存 */
    private Integer stock;

    /** 锁定库存（秒杀/预扣用） */
    private Integer lockedStock;

    /** 乐观锁版本号 */
    private Integer version;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

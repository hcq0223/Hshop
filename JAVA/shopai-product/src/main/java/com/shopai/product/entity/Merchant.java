package com.shopai.product.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商家实体（商品服务内的只读精简版）
 * <p>
 * 微服务拆分说明：merchants 表归属 shopai-merchant 服务。商品详情(selectDetailById)、
 * 热销榜(selectHotOnSale) 通过 SQL 联查 merchants 表
 * 填充店铺冗余字段（shop_name / logo_url / rating / is_verified）。
 * 当前「共享单库」阶段可直查；后续拆独立库需改为 Feign 调用商家服务补字段。
 * <p>
 * 本类仅保留商品服务需要展示的字段，避免引入商家服务的完整实体，减少跨服务耦合。
 */
@Data
@TableName("merchants")
public class Merchant {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo */
    private String logoUrl;

    /** 店铺评分 */
    private BigDecimal rating;

    /** 0:未认证 1:已认证 */
    private Integer isVerified;

    /** 挂载关联的 User（非数据库字段，商品服务暂不使用，保留占位以兼容前端） */
    @TableField(exist = false)
    private Object user;
}

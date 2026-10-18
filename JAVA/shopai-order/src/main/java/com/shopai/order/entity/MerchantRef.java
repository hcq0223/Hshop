package com.shopai.order.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 商户引用视图对象（订单服务内的展示用精简版）
 * <p>
 * 微服务拆分说明：merchants 表归属 shopai-merchant 服务。订单详情/列表原先通过
 * SQL {@code JOIN merchants} 填充店铺展示字段；按「独立库」处理后不再跨库联查，
 * 改由 {@link com.shopai.order.service.OrderEnrichService} 通过 Feign 调商家服务
 * 内部接口，把结果装配到本对象，再挂到 {@code Order.merchant} 与
 * {@code OrderItem.merchant}（前端读取 {@code item.merchant.shopName / logoUrl}）。
 * <p>
 * 注意：本类**不是数据库实体**，不带任何 MyBatis 注解，也不会被 Mapper 使用；
 * 资金字段（营收/余额/冻结）已全部改由商家服务内部接口原子变更，
 * 故此处只保留展示所需的字段。
 */
@Data
public class MerchantRef {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo */
    private String logoUrl;

    /** 0:未认证 1:已认证 */
    private Integer isVerified;
}

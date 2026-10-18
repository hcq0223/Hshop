package com.shopai.common.dto.internal.merchant;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家简要信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：以下跨库联查在「独立库」后无法继续，改为 Feign 批量补店铺字段：
 * - 商家服务 {@code ReviewMapper.xml}：{@code LEFT JOIN merchants m} 补评价的商家名；
 * - 用户服务 {@code DashboardMapper.xml}：{@code INNER JOIN merchants m} 补销售额排名的店铺名；
 * - 商品服务 {@code ProductMapper.xml}：{@code JOIN merchants m} 补商品/热销榜的店铺名、Logo、
 *   评分（rating）与认证状态（is_verified）；
 * - 订单服务 {@code OrderMapper.xml}：{@code JOIN merchants m} 补订单/订单项的店铺名与 Logo。
 * <p>
 * 本 DTO 覆盖上述场景所需的全部叶子字段（评分与认证状态用于商品详情页的店铺展示）。
 */
@Data
public class MerchantBrief implements Serializable {

    /** 商家ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 Logo */
    private String logoUrl;

    /** 店铺评分（原 JOIN 取 merchants.rating，商品详情页展示「店铺评分」用） */
    private java.math.BigDecimal rating;

    /** 是否已认证：1 已认证 / 0 待审核（原 JOIN 取 merchants.is_verified） */
    private Integer isVerified;
}

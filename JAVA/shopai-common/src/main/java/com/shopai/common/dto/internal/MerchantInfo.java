package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code merchants} 表归属 shopai-merchant 服务。以下调用方原先直查 merchants 表：
 * - 商品服务：新增/编辑商品时按 userId 解析当前商家ID（原 product 模块 MerchantRefMapper）
 * - 订单服务：按 userId 解析当前商家ID（原 order 模块 MerchantRefMapper）
 * - 用户服务：登录时判断 merchant 角色用户是否已提交商家资料（原 user 模块 MerchantRefMapper）
 * <p>
 * 拆分为「独立库」后统一改为 Feign 调用商家服务内部接口，本 DTO 是该链路的统一返回结构。
 */
@Data
public class MerchantInfo implements Serializable {

    /** 商家ID（merchants.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 关联用户ID（merchants.user_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 Logo 地址 */
    private String logoUrl;

    /** 店铺评分 */
    private java.math.BigDecimal rating;

    /** 是否已认证：1 已认证 / 0 待审核 */
    private Integer isVerified;
}

package com.shopai.merchant.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家索引文档视图（商家服务侧读取用）
 * <p>
 * 微服务拆分说明（列表查询改造）：商家分页列表改由 ES 承载后，搜索服务把命中记录
 * 以 JSON 返回（通用列表接口的 {@code records} 是 {@code List&lt;Object&gt;}），
 * 商家服务需要按 ES 文档结构反序列化。本类即该结构的镜像。
 * <p>
 * 与 {@link com.shopai.common.dto.internal.MerchantIndexItem} 的区别同商品侧：
 * 后者是「商家服务 → 搜索服务」**写入**方向契约（含 userId 等索引不需要的字段），
 * 本类是**读取**方向契约（与 MerchantDocument 字段一致）。
 */
@Data
public class MerchantIndexView implements Serializable {

    private String id;

    private String shopName;

    private String logoUrl;

    private BigDecimal rating;

    /** 0 未认证 / 1 已认证 */
    private Integer isVerified;

    private String shopDescription;

    private LocalDateTime createdAt;
}

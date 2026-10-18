package com.shopai.common.dto.internal;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家索引条目 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：搜索服务原先通过 {@code MerchantRefMapper.selectAllForIndex /
 * selectOneForIndex} 跨库直查 merchants，并 JOIN users 取创建时间、
 * 按 {@code is_verified = 1 AND u.status = 1} 过滤已认证且账号正常的商家。
 * <p>
 * 拆分为「独立库」后，商家服务内部接口负责在本地库内完成这些查询（账号状态改为
 * Feign 调用户服务校验），搜索服务改为 Feign 拉取本 DTO 列表：
 * - 全量重建：GET /api/merchant/internal/index/merchants
 * - 增量同步：GET /api/merchant/internal/index/merchant?ids=1
 * <p>
 * 约定：ID 字段以 String 承载，与 ES 文档 id 保持一致。
 */
@Data
public class MerchantIndexItem implements Serializable {

    /** 商家ID */
    private String id;

    private String userId;

    private String shopName;

    private String logoUrl;

    private String shopDescription;

    private BigDecimal rating;

    /** 是否已认证：1 已认证 / 0 待审核 */
    private Integer isVerified;

    /** 创建时间（原 JOIN users 取 users.created_at） */
    private LocalDateTime createdAt;
}

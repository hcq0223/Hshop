package com.shopai.common.dto.internal.index;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户索引视图 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：列表查询改造后，后台用户列表不再放 Redis 缓存
 * （原来是 {@code cache:user:list:{hash}} 且任意用户变更即 {@code deleteByPrefix} 全清），
 * 改由 shopai-search 的 {@code user} ES 索引承载。用户服务在注册/状态变更/资料变更后
 * 发 MQ，搜索服务回源本视图写入索引。
 * <p>
 * 安全说明（重要）：本视图**绝不包含** {@code passwordHash} 等凭据字段。
 * 索引只用于后台列表的筛选/分页/关键字检索，因此仅包含展示与过滤所需的非敏感字段。
 */
@Data
public class UserIndexItem implements Serializable {

    /** 用户ID */
    private String id;

    private String username;

    private String phone;

    private String email;

    /** customer / merchant / admin */
    private String role;

    /** 1:正常 0:禁用 */
    private Integer status;

    /**
     * 冗余检索字段：客户昵称（role=customer 时由 customers.nickname 提供）
     * <p>
     * 原实现靠 {@code users LEFT JOIN customers} 支撑「按客户昵称搜索用户」，
     * 改造后由用户服务在装配该视图时补齐，搜索服务无需联查。
     */
    private String customerNickname;

    /**
     * 冗余检索字段：店铺名（role=merchant 时由商家服务提供）
     * <p>
     * 原实现靠 {@code users LEFT JOIN merchants} 支撑「按店铺名搜索用户」，
     * 改造后由用户服务在装配该视图时通过商家服务内部接口补齐。
     */
    private String shopName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

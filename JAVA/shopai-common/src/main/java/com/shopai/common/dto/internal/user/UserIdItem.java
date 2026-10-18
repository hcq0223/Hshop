package com.shopai.common.dto.internal.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户简要信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：用户服务的后台用户列表搜索（{@code UserMapper.xml} 的
 * {@code LEFT JOIN customers / merchants}）原先按「客户昵称 / 店铺名」模糊匹配 users。
 * 拆分为「独立库」后无法跨库联查，改为：
 * 1. 用户服务先调客户服务 / 商家服务按关键字查询，拿到匹配的 userId 集合（本 DTO 的 id 字段）；
 * 2. 再用该集合作为 {@code users.id IN (...)} 条件查询 users 表。
 * <p>
 * 本 DTO 用于第 1 步的返回：只需 id（用户ID）。
 */
@Data
public class UserIdItem implements Serializable {

    /** 用户ID（users.id，字符串承载避免精度问题） */
    private String id;
}

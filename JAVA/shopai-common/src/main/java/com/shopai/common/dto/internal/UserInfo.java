package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code users} 表归属 shopai-user 服务。其他服务（商家服务的「商家详情联查」、
 * 「更新账号信息」等）原先通过 userId 直查 users 表。拆分为「独立库」后，这些访问统一改为
 * Feign 调用用户服务内部接口，本 DTO 即该调用链路的统一返回结构，放在公共库供各服务共用，
 * 避免字段不一致。
 * <p>
 * 注意：本类只包含跨服务真正需要的叶子字段，不含任何 Mapper / Entity 引用。
 */
@Data
public class UserInfo implements Serializable {

    /** 用户ID（users.id，雪花ID → 字符串序列化，避免 JS 精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String username;

    private String phone;

    private String email;

    /** customer / merchant / admin */
    private String role;

    /** 1:正常 0:禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

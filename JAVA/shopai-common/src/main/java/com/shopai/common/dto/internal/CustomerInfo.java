package com.shopai.common.dto.internal;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 客户信息 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：{@code customers} 表归属 shopai-user 服务。商家服务的评价列表/详情原先通过
 * {@code ReviewMapper.xml} 的 {@code JOIN customers} 填充评价人昵称与头像。
 * <p>
 * 拆分为「独立库」后，商家服务先查本地 reviews 表拿到 customerId 集合，
 * 再通过 Feign 调用户服务内部接口批量补齐昵称/头像，在 Java 侧组装，
 * 不再跨库 JOIN。本 DTO 即该批量补齐的返回结构。
 */
@Data
public class CustomerInfo implements Serializable {

    /** 客户ID（customers.id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 关联用户ID（customers.user_id） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户昵称 */
    private String nickname;

    /** 客户头像地址 */
    private String avatarUrl;
}

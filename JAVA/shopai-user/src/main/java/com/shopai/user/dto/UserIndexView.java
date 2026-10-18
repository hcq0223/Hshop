package com.shopai.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户索引文档视图（用户服务侧读取用）
 * <p>
 * 微服务拆分说明（列表查询改造）：后台用户列表改由 ES 承载后，搜索服务把命中记录
 * 以 JSON 返回（通用列表接口的 {@code records} 是 {@code List&lt;Object&gt;}），
 * 用户服务按 ES 文档结构反序列化。本类即该结构的镜像。
 * <p>
 * 安全说明：索引与视图都**不含** passwordHash 等凭据字段。
 */
@Data
public class UserIndexView implements Serializable {

    private String id;

    private String username;

    private String phone;

    private String email;

    /** customer / merchant / admin */
    private String role;

    /** 1:正常 0:禁用 */
    private Integer status;

    /** 客户昵称（索引中冗余，替代原 users LEFT JOIN customers） */
    private String customerNickname;

    /** 店铺名（索引中冗余，替代原 users LEFT JOIN merchants） */
    private String shopName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

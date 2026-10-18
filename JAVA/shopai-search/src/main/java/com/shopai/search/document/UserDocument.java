package com.shopai.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * 用户搜索文档（Elasticsearch 的 user 索引）
 * <p>
 * 微服务拆分说明（列表查询改造）：原后台用户列表走 Redis 缓存
 * {@code cache:user:list:{hash}}，任意用户变更即 {@code deleteByPrefix(CACHE_USER_LIST)} 全清；
 * 且原查询还要 {@code users LEFT JOIN customers / merchants} 才能按昵称/店铺名搜索。
 * 改造后由本索引承载：
 * - 写入：用户服务在注册/状态变更/资料变更后发 {@code user.changed}，搜索服务回源本文档 upsert；
 * - 读取：用户服务保留原路由不变，内部通过 Feign 调搜索服务的统一列表查询接口。
 * <p>
 * 安全说明（重要）：本索引**不包含 passwordHash** 等任何凭据字段。
 * 它只服务后台列表的筛选/分页/关键字检索，因此仅索引展示与过滤所需的非敏感字段。
 * <p>
 * 冗余说明：{@code customerNickname / shopName} 在回源时由用户服务装配
 * （昵称来自本服务 customers 表，店铺名来自商家服务内部接口），
 * 从而在没有跨库 JOIN 的前提下依然支持「按昵称/店铺名搜用户」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "user")
public class UserDocument {

    /** 用户ID */
    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    /** 用户名（分词 + 精确，支持模糊搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String username;

    /** 手机号（Keyword，支持精确/前缀检索） */
    @Field(type = FieldType.Keyword)
    private String phone;

    /** 邮箱（Keyword，支持精确检索） */
    @Field(type = FieldType.Keyword)
    private String email;

    /** customer / merchant / admin（列表按角色筛选） */
    @Field(type = FieldType.Keyword)
    private String role;

    /** 1:正常 0:禁用（列表按状态筛选） */
    @Field(type = FieldType.Integer)
    private Integer status;

    /** 客户昵称（冗余：替代原 users LEFT JOIN customers 的昵称搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String customerNickname;

    /** 店铺名（冗余：替代原 users LEFT JOIN merchants 的店铺名搜索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createTime;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime updateTime;
}

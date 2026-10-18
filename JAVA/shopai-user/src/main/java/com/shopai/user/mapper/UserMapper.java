package com.shopai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 用户 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.UserMapper 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.mapper，XML namespace 同步更新。
 * <p>
 * 【跨库 JOIN 去除说明】原 selectByConditions / countByConditions 在 SQL 中
 * {@code LEFT JOIN customers / merchants} 按客户昵称、店铺名模糊匹配 users。
 * 按「独立库」处理后 customers / merchants 分别归属用户服务与商家服务，不再允许跨服务 JOIN，
 * 故关键字匹配拆为两个入参集合：
 * - customerUserIds：客户服务按昵称匹配出的 userId 集合（服务内本地查询）
 * - merchantUserIds：商家服务按店铺名匹配出的 userId 集合（Feign 调商家服务获取）
 * 本 Mapper 的 SQL 只访问 users 表。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询（查重 / 登录前检查）
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 根据手机号查询（查重 / 快捷登录前检查）
     */
    User selectByPhone(@Param("phone") String phone);

    /**
     * 根据邮箱查询（查重 / 邮箱登录前检查）
     */
    User selectByEmail(@Param("email") String email);

    /**
     * 组合条件分页查询用户列表（后台管理）
     * <p>
     * 关键字匹配范围：username / phone / email（users 表本地），
     * 以及 customerUserIds（客户昵称命中）与 merchantUserIds（店铺名命中）。
     *
     * @param role            角色过滤，可选
     * @param status          状态过滤（1:正常 0:禁用），可选
     * @param keyword         关键字模糊匹配，可选
     * @param customerUserIds 客户昵称匹配到的 userId 集合，可为 null/空
     * @param merchantUserIds 店铺名匹配到的 userId 集合，可为 null/空
     * @param offset          分页偏移量（从0开始）
     * @param limit           每页条数
     */
    List<User> selectByConditions(@Param("role") String role,
                                  @Param("status") Integer status,
                                  @Param("keyword") String keyword,
                                  @Param("customerUserIds") Collection<Long> customerUserIds,
                                  @Param("merchantUserIds") Collection<Long> merchantUserIds,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /**
     * 统计符合条件的记录总数（用于分页计算总页数）
     * <p>
     * 条件与 {@link #selectByConditions} 完全一致（含跨服务关键字命中的 userId 集合）。
     */
    long countByConditions(@Param("role") String role,
                           @Param("status") Integer status,
                           @Param("keyword") String keyword,
                           @Param("customerUserIds") Collection<Long> customerUserIds,
                           @Param("merchantUserIds") Collection<Long> merchantUserIds);

    /**
     * 根据角色统计用户数
     * @param role
     * @return
     */
    long countByRole(@Param("role") String role);

    /**
     * 统计今日新增用户数
     * @return
     */
    long countTodayNew();

    /**
     * 近N天每日新增用户数
     */
    List<java.util.Map<String, Object>> dailyNewUsers(@Param("days") int days);

    /** 按角色统计用户分布 */
    List<java.util.Map<String, Object>> countGroupByRole();

    /**
     * 更新用户状态
     * @param id
     * @param status
     * @return
     */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 分页查询用户（供全量重建 user 索引使用）
     * <p>
     * 微服务拆分说明（列表查询改造）：后台用户列表迁至 ES 后，需要一个「全量源数据」
     * 入口用于索引全量重建（MQ 消息丢失或索引结构变更时兜底）。
     *
     * @param offset 偏移量（从 0 开始）
     * @param limit  每页条数
     */
    List<User> selectPageForIndex(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 登录验证：匹配账号、密码、状态正常且未删除
     */
    User selectForLogin(@Param("username") String username,
                        @Param("passwordHash") String passwordHash);


}

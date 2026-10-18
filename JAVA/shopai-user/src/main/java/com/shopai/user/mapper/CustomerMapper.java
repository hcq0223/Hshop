package com.shopai.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.user.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 客户 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.CustomerMapper 迁移至 shopai-user 服务，
 * 包名变更为 com.shopai.user.mapper，XML namespace 同步更新。
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

    /**
     * 根据 userId 查询客户id
     */
    Long selectIdByUserId(@Param("userId") Long userId);

    /**
     * 按 userId 集合批量查询客户
     * <p>
     * 微服务拆分说明（列表查询改造）：组装 user 索引视图时需要「userId → 客户昵称」映射
     * （原为 SQL {@code users LEFT JOIN customers}）。逐条查询会形成 N+1，故提供批量入口。
     *
     * @param userIds 用户ID集合
     */
    List<Customer> selectByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 根据 userId 查询客户扩展信息（单表）
     */
    Customer selectByUserId(@Param("userId") Long userId);

    /**
     * 根据 userId 联查客户信息 + 关联的 User 基础信息
     */
    Customer selectDetailByUserId(@Param("userId") Long userId);

    /**
     * 根据昵称模糊查询客户列表（可用于搜索）
     */
    List<Customer> selectByNickname(@Param("nickname") String nickname,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    Long countByNickname(@Param("nickname") String nickname);
}

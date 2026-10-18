package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.Address;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收货地址 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.AddressMapper 迁移至 shopai-order 服务。
 */
@Mapper
public interface AddressMapper extends BaseMapper<Address> {


    /**
     * 查询某个客户的所有地址（按是否默认降序 + id 倒序）
     */
    List<Address> selectByCustomerId(@Param("customerId") Long customerId);

    /**
     * 查询某个客户的默认地址
     */
    Address selectDefaultByCustomerId(@Param("customerId") Long customerId);

    /**
     * 查询单个地址详情
     */
    Address selectById(@Param("id") Long id);

    /**
     * 将某客户的所有地址设为非默认
     */
    int clearDefaultByCustomerId(@Param("customerId") Long customerId);

    /**
     * 设置指定地址为默认地址
     */
    int setDefault(@Param("id") Long id, @Param("customerId") Long customerId);
}

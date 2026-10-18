package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.ShoppingCart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 购物车 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.ShoppingCartMapper 迁移至 shopai-order 服务，
 * 联查字段改用精简引用实体 ProductSkuRef / ProductRef。
 */
@Mapper
public interface ShoppingCartMapper extends BaseMapper<ShoppingCart> {

    /**
     * 查询某客户的所有购物车条目，联查 SKU 和商品信息
     */
    List<ShoppingCart> selectByCustomerId(@Param("customerId") Long customerId);

    /**
     * 查询某客户的购物车中指定 SKU 的条目
     */
    ShoppingCart selectByCustomerIdAndSkuId(@Param("customerId") Long customerId,
                                            @Param("skuId") Long skuId);

    /**
     * 插入或累加数量（利用唯一约束 customer_id + sku_id）
     */
    int insertOrAddQuantity(@Param("customerId") Long customerId,
                            @Param("skuId") Long skuId,
                            @Param("quantity") Integer quantity);

    /**
     * 更新购物车条目的数量
     */
    int updateQuantity(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 更新单个条目的选中状态
     */
    int updateSelected(@Param("id") Long id, @Param("selected") Integer selected);

    /**
     * 批量更新选中状态（全选/取消全选）
     */
    int updateSelectedBatch(@Param("ids") List<Long> ids,
                            @Param("selected") Integer selected);

    /**
     * 根据客户ID和SKU ID删除指定条目
     */
    int deleteByCustomerIdAndSkuId(@Param("customerId") Long customerId,
                                   @Param("skuId") Long skuId);

    /**
     * 批量删除购物车条目（根据主键ID列表）
     */
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 清空某客户的购物车
     */
    int deleteByCustomerId(@Param("customerId") Long customerId);
}

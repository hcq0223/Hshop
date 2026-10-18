package com.shopai.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.product.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SKU Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.ProductSkuMapper 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.mapper，XML namespace 同步更新。
 * 已移除 decreaseStock 方法（库存扣减迁至 shopai-inventory 服务）。
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * 根据商品ID查询所有SKU（一般用于商品详情页选择规格）
     */
    List<ProductSku> selectByProductId(@Param("productId") Long productId);

    /**
     * 按主键查询单个SKU
     */
    ProductSku selectBySkuId(@Param("id") Long id);

    /**
     * 根据SKU编码查询
     */
    ProductSku selectBySkuCode(@Param("skuCode") String skuCode);

}

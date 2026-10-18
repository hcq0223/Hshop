package com.shopai.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 商品 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.ProductMapper 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.mapper，XML namespace 同步更新。
 * <p>
 * 【跨库 JOIN 去除说明】原 {@code selectDetailById} / {@code selectHotOnSale} 在 SQL 中
 * {@code JOIN merchants} 取店铺名/Logo。按「独立库」处理后 merchants 表归属 shopai-merchant 服务，
 * 本 Mapper 的 SQL 只访问 products / categories / product_skus，
 * 店铺字段一律由 Service 层通过 Feign 调商家服务补齐。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 按商户查询商品列表（状态不限，常用于商户后台管理）
     */
    List<Product> selectByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * 查询商品详情（联查分类与 SKU，店铺字段由 Service 层 Feign 补齐）
     */
    Product selectDetailById(@Param("id") Long id);

    /**
     * 查询热销在售商品（按销量降序，取前N条；店铺字段由 Service 层 Feign 补齐）
     */
    List<Product> selectHotOnSale(@Param("limit") Integer limit);

    /**
     * 全量查询商品（含分类名/父分类名），供搜索服务全量重建 product 索引
     * <p>
     * 微服务拆分说明：原由搜索服务 ProductRefMapper 跨库直查 products 并 JOIN merchants/categories。
     * 按「独立库」处理后，products/categories 由本服务在本地联查（categories 属于本服务），
     * 店铺名/Logo 由本服务再调商家服务补齐后回给搜索服务。
     */
    List<Product> selectAllForIndex();

    /**
     * 按ID批量查询商品（含分类名/父分类名），供搜索服务 MQ 增量同步
     */
    List<Product> selectByIdsForIndex(@Param("ids") Collection<Long> ids);

    /**
     * 统计商品总数（供用户服务仪表盘跨服务调用）
     */
    long countAll();

    /**
     * 按分类ID查询商品ID列表（分类改名/删除后同步索引用）
     * <p>
     * 背景：product 索引里的 {@code categoryName / parentCategoryName} 是从
     * categories 表冗余过去的。分类改名后这些冗余字段会陈旧，需要找出受影响的商品
     * 重新同步索引。本方法只回 ID（不回整行），便于先判断数量再决定是否值得扇出。
     *
     * @param categoryId 分类ID
     * @param limit      最多返回条数（用于「超过阈值就不做扇出」的保护）
     */
    List<Long> selectIdsByCategoryId(@Param("categoryId") Integer categoryId,
                                     @Param("limit") int limit);

    /**
     * 累加商品销量（下单后调用）
     * <p>
     * 微服务拆分说明：products 表归属商品服务，本方法保留在商品服务内，
     * 通过内部 Feign 接口（/api/product/internal/sales）暴露给订单服务调用。
     * @param productId 商品ID
     * @param delta 增量
     */
    void incrementSalesCount(@Param("productId") Long productId, @Param("delta") int delta);

}

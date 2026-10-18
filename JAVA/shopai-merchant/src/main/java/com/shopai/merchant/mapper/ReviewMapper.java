package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 评价 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.ReviewMapper 迁移至 shopai-merchant 服务，
 * 包名变更为 com.shopai.merchant.mapper，XML namespace 同步更新。
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /**
     * 按商品ID分页查询评价（带客户信息）
     */
    List<Review> selectWithCustomerByProductId(@Param("productId") Long productId,
                                               @Param("offset") int offset,
                                               @Param("size") int size);

    /**
     * 统计某商品的评价总数
     */
    long countByProductId(@Param("productId") Long productId);

    /**
     * 统计某商品各星级的评价数量（用于评分分布图）
     */
    List<Map<String, Object>> countRatingByProductId(@Param("productId") Long productId);

    /**
     * 根据客户ID查询其所有评价
     */
    List<Review> selectByCustomerId(@Param("customerId") Long customerId);

    /**
     * 根据订单ID查评价（判断是否已评价）
     */
    Review selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 判断订单是否已存在评价
     */
    int existsByOrderId(@Param("orderId") Long orderId);

    /**
     * 查询某商家所有评价的 rating（用于计算均分）
     */
    List<Integer> selectRatingsByMerchantId(@Param("merchantId") Long merchantId);

    /**
     * 管理员分页查评价（带客户+商品名+商家名）
     */
    List<Review> selectPageWithDetails(@Param("offset") int offset, @Param("size") int size);

    /**
     * 统计评价总数
     */
    long countAll();

    /**
     * 管理员按评论ID搜索（带客户+商品名+商家名）
     */
    Review selectByIdWithDetails(@Param("id") Long id);
}

package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.MerchantWeeklyRevenue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商家周营收归档 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.MerchantWeeklyRevenueMapper 迁移至 shopai-merchant 服务。
 */
@Mapper
public interface MerchantWeeklyRevenueMapper extends BaseMapper<MerchantWeeklyRevenue> {

    /** 按商户ID查询历史周营收 */
    List<MerchantWeeklyRevenue> selectByMerchantId(@Param("merchantId") Long merchantId);
}

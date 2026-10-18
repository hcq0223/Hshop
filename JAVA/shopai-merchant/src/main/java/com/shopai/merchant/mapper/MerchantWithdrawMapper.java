package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.MerchantWithdraw;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家提现记录 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.MerchantWithdrawMapper 迁移至 shopai-merchant 服务。
 */
@Mapper
public interface MerchantWithdrawMapper extends BaseMapper<MerchantWithdraw> {
}

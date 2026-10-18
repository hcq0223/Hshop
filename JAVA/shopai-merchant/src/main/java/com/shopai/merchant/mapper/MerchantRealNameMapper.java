package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.MerchantRealName;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家实名认证 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.MerchantRealNameMapper 迁移至 shopai-merchant 服务。
 */
@Mapper
public interface MerchantRealNameMapper extends BaseMapper<MerchantRealName> {
}

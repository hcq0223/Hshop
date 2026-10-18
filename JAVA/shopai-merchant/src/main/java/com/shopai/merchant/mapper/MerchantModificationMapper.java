package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.MerchantModification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家信息修改申请 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.MerchantModificationMapper 迁移至 shopai-merchant 服务。
 */
@Mapper
public interface MerchantModificationMapper extends BaseMapper<MerchantModification> {
}

package com.shopai.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 秒杀订单流水 Mapper
 */
@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 按抢购记录ID查询流水（幂等：同一 recordId 只应有一条）。
     *
     * @param recordId seckill_record.id
     */
    SeckillOrder selectByRecordId(@Param("recordId") Long recordId);
}

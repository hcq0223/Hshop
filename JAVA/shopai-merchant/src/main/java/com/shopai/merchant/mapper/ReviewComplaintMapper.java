package com.shopai.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.merchant.entity.ReviewComplaint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 评价投诉（申诉）Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.ReviewComplaintMapper 迁移至 shopai-merchant 服务。
 */
@Mapper
public interface ReviewComplaintMapper extends BaseMapper<ReviewComplaint> {

    /** 查询某评论的待处理申诉（用于判断是否已有待处理申诉） */
    @Select("SELECT COUNT(1) FROM review_complaints WHERE review_id = #{reviewId} AND status = 'pending'")
    int countPendingByReviewId(Long reviewId);
}

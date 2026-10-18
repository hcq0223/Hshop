package com.shopai.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价投诉（申诉）实体
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.ReviewComplaint 迁移至 shopai-merchant 服务。
 */
@Data
@TableName("review_complaints")
public class ReviewComplaint {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private String reason;

    /** pending / rejected / approved */
    private String status;

    private String adminNote;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    /** 关联的评论（非数据库字段） */
    @TableField(exist = false)
    private Review review;

    /** 关联的商家名称（非数据库字段） */
    @TableField(exist = false)
    private String merchantName;
}

package com.shopai.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分类实体（对应 categories 表）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.entity.Category 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.entity。
 */
@Data
@TableName("categories")
public class Category {
    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 父分类ID，0为顶级 */
    private Integer parentId;
    private String name;
    private Integer sortOrder;
}

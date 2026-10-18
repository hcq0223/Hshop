package com.shopai.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.product.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分类 Mapper
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.mapper.CategoryMapper 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.mapper，XML namespace 同步更新。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 查询所有顶级分类（parent_id = 0），按 sort_order 排序
     */
    List<Category> selectTopLevel();

    /**
     * 根据父分类ID查询直接子分类，按 sort_order 排序
     */
    List<Category> selectByParentId(@Param("parentId") Integer parentId);

    /**
     * 按主键查询单个分类
     */
    Category selectByIde(@Param("id") Integer id);

    /**
     * 查询所有分类（按 parent_id, sort_order 排序，用于树形组装）
     */
    List<Category> selectAllSorted();
}

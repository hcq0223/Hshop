package com.shopai.product.service;

import com.shopai.product.entity.Category;

import java.util.List;

/**
 * 商品分类服务接口
 * <p>
 * 缓存策略：
 * - 顶级分类列表：cache:category:top （TTL 600秒）
 * - 子分类列表：cache:category:sub:{parentId} （TTL 600秒）
 * - 单个分类：cache:category:{id} （TTL 600秒）
 * <p>
 * 失效策略（写操作后）：
 * 1. 事务提交后立即删除相关缓存
 * 2. 异步延迟300ms再次删除（防止并发读线程在事务提交前将旧数据写回缓存）
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.service.CategoryService 迁移至 shopai-product 服务，
 * 包名变更为 com.shopai.product.service。
 * <p>
 * 分层改造（2026-10-05）：本接口 + CategoryServiceImpl（extends ServiceImpl&lt;CategoryMapper, Category&gt;）标准分层。
 * 注意：本接口的 getById(Integer) 是业务方法（带缓存、返回 Category），与 IService.getById(Serializable)
 * 构成重载（参数类型不同），调用时命中业务方法。
 */
public interface CategoryService {

    /**
     * 获取所有分类（按 parent_id, sort_order 排序）
     * 缓存Key：RedisService.CACHE_CATEGORY_ALL
     * 缓存时长：600秒
     *
     * @return 全部分类列表
     */
    List<Category> getAll();

    /**
     * 获取所有顶级分类（parent_id = 0）
     * 缓存Key：RedisService.CACHE_CATEGORY_TOP
     * 缓存时长：600秒（分类数据变更频率低）
     *
     * @return 顶级分类列表
     */
    List<Category> getTopLevel();

    /**
     * 根据父分类ID获取直接子分类列表
     * 缓存Key：RedisService.CACHE_CATEGORY_SUB + parentId
     *
     * @param parentId 父分类ID
     * @return 子分类列表
     */
    List<Category> getByParentId(Integer parentId);

    /**
     * 根据ID获取单个分类详情
     * 缓存Key：RedisService.CACHE_CATEGORY + id
     *
     * @param id 分类ID
     * @return 分类实体，不存在则返回null
     */
    Category getById(Integer id);

    /**
     * 新增分类
     * 事务提交后，清除顶级分类、该分类本身、以及其父分类下的子列表缓存
     *
     * @param category 分类实体（需包含parentId等信息）
     */
    void addCategory(Category category);

    /**
     * 更新分类信息（可修改名称、父分类、排序等）
     * 若父分类发生变化，需同时清除旧父分类和新父分类下的子列表缓存
     *
     * @param category 包含待更新字段的实体（id必填）
     */
    void updateCategory(Category category);

    /**
     * 删除分类（物理删除）
     * 清除该分类本身、顶级分类、以及其父分类下的子列表缓存
     *
     * @param id 分类ID
     */
    void deleteCategory(Integer id);
}

package com.shopai.product.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shopai.common.Result;
import com.shopai.common.dto.internal.ProductIndexItem;
import com.shopai.common.redis.RedisService;
import com.shopai.common.tx.AfterCommitExecutor;
import com.shopai.product.entity.Category;
import com.shopai.product.feign.SearchIndexClient;
import com.shopai.product.mapper.CategoryMapper;
import com.shopai.product.mapper.ProductMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 商品分类服务实现
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
 * 微服务拆分说明（列表查询改造）：分类名会被冗余进 product 索引
 * （{@code categoryName / parentCategoryName}），因此改名后需要扇出同步受影响商品的索引，
 * 详见 {@link #syncAffectedProductsIndex}。
 * <p>
 * 分层改造（2026-10-05）：继承 ServiceImpl&lt;CategoryMapper, Category&gt; 实现 CategoryService 接口，
 * 可直接使用 MyBatis-Plus 的 IService 便捷方法；保留原 @Autowired 字段与方法体。
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /**
     * 分类变更后「扇出同步商品索引」的商品数上限
     * <p>
     * 超过该值则跳过扇出，改为提示运营调用全量重建（见 {@link #syncAffectedProductsIndex}）。
     * 取 500 的理由：一次 Feign 推送 500 条索引视图（每条约 10 个字段）报文在百 KB 级，
     * 组装与写入耗时可控（秒级），既覆盖大多数正常分类规模，又避免极端情况拖垮服务。
     */
    private static final int MAX_FANOUT_PRODUCTS = 500;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private ScheduledExecutorService cacheDelayScheduler; // 用于异步延迟删除

    // 列表查询改造新增：分类改名后扇出同步 product 索引
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductIndexService productIndexService;
    @Autowired
    private SearchIndexClient searchIndexClient;

    // ==================== 查询方法（均走缓存） ====================

    /**
     * 获取所有分类（按 parent_id, sort_order 排序）
     * 缓存Key：RedisService.CACHE_CATEGORY_ALL
     * 缓存时长：600秒
     *
     * @return 全部分类列表
     */
    @Override
    public List<Category> getAll() {
        String key = RedisService.CACHE_CATEGORY_ALL;
        return redisService.getOrSetTyped(
                key,
                new TypeReference<List<Category>>() {},
                600L,
                categoryMapper::selectAllSorted
        );
    }

    /**
     * 获取所有顶级分类（parent_id = 0）
     * 缓存Key：RedisService.CACHE_CATEGORY_TOP
     * 缓存时长：600秒（分类数据变更频率低）
     *
     * @return 顶级分类列表
     */
    @Override
    public List<Category> getTopLevel() {
        return redisService.getOrSetTyped(
                RedisService.CACHE_CATEGORY_TOP,
                new TypeReference<List<Category>>() {},
                600L,
                categoryMapper::selectTopLevel
        );
    }

    /**
     * 根据父分类ID获取直接子分类列表
     * 缓存Key：RedisService.CACHE_CATEGORY_SUB + parentId
     *
     * @param parentId 父分类ID
     * @return 子分类列表
     */
    @Override
    public List<Category> getByParentId(Integer parentId) {
        String key = RedisService.CACHE_CATEGORY_SUB + parentId;
        return redisService.getOrSetTyped(
                key,
                new TypeReference<List<Category>>() {},
                600L,
                () -> categoryMapper.selectByParentId(parentId)
        );
    }

    /**
     * 根据ID获取单个分类详情
     * 缓存Key：RedisService.CACHE_CATEGORY + id
     *
     * @param id 分类ID
     * @return 分类实体，不存在则返回null
     */
    @Override
    public Category getById(Integer id) {
        String key = RedisService.CACHE_CATEGORY + id;
        return redisService.getOrSet(key, Category.class, 600L,
                () -> categoryMapper.selectByIde(id)
        );
    }

    // ==================== 写操作（事务 + 缓存失效） ====================

    /**
     * 新增分类
     * 事务提交后，清除顶级分类、该分类本身、以及其父分类下的子列表缓存
     *
     * @param category 分类实体（需包含parentId等信息）
     */
    @Override
    @Transactional
    public void addCategory(Category category) {
        categoryMapper.insert(category);
        // 注册事务同步回调，在事务提交后执行缓存清除
        evictCategoryCacheAfterCommit(category.getId(), category.getParentId());
    }

    /**
     * 更新分类信息（可修改名称、父分类、排序等）
     * 若父分类发生变化，需同时清除旧父分类和新父分类下的子列表缓存
     * <p>
     * 微服务拆分说明（列表查询改造）：分类**名称**会被冗余进 product 索引的
     * {@code categoryName / parentCategoryName}（供商品列表展示与检索），
     * 因此改名后必须把受影响商品的索引文档重新同步，否则商品列表仍显示旧分类名。
     * 具体扇出策略与阈值见 {@link #syncAffectedProductsIndex}。
     *
     * @param category 包含待更新字段的实体（id必填）
     */
    @Override
    @Transactional
    public void updateCategory(Category category) {
        // 先查询旧数据，用于缓存清除 + 判断是否改名
        Category old = categoryMapper.selectByIde(category.getId());
        if (old == null) {
            throw new RuntimeException("分类不存在");
        }

        // 使用 LambdaUpdateWrapper 只更新传入的非空字段（此处简单全量更新）
        LambdaUpdateWrapper<Category> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Category::getId, category.getId())
                .set(Category::getName, category.getName())
                .set(Category::getParentId, category.getParentId())
                .set(Category::getSortOrder, category.getSortOrder());
        categoryMapper.update(null, wrapper);

        // 如果父分类ID变了，需要清除旧父分类和新父分类下的子列表缓存
        if (!old.getParentId().equals(category.getParentId())) {
            evictCategoryCacheAfterCommit(category.getId(), old.getParentId());
            evictCategoryCacheAfterCommit(category.getId(), category.getParentId());
        } else {
            // 父分类不变，只需清除当前父分类下的子列表缓存
            evictCategoryCacheAfterCommit(category.getId(), old.getParentId());
        }

        // 名称变化 → product 索引里冗余的 categoryName 会陈旧，需要扇出同步
        boolean nameChanged = old.getName() == null
                ? category.getName() != null
                : !old.getName().equals(category.getName());
        if (nameChanged) {
            syncAffectedProductsIndex(category.getId());
        }
        // 父分类变化 → 本分类下商品的「父分类名」会变（parentCategoryName 取自父分类）
        if (!old.getParentId().equals(category.getParentId())) {
            syncAffectedProductsIndex(category.getId());
        }
    }

    /**
     * 把某个分类下受影响商品的索引文档重新同步到 ES
     * <p>
     * <b>背景</b>：{@code ProductDocument.categoryName / parentCategoryName} 是从
     * categories 表冗余过去的。分类改名后如果不处理，商品列表（以及索引里的
     * categoryName 检索字段）会一直显示旧分类名，直到有人手动全量重建索引。
     * <p>
     * <b>为什么需要阈值保护</b>：一次改名可能影响该分类下的**全部商品**。
     * 若分类下有上万商品，一次性组装并推送索引会造成：
     * ① 单次 Feign 报文过大；② 商品服务内存占用升高；③ 长时间占用线程。
     * 因此这里做了三重保护：
     * <ol>
     *   <li>先用 {@code COUNT}-风格查询（LIMIT 阈值+1）判断规模，超过阈值直接跳过；</li>
     *   <li>SQL 侧 {@code LIMIT} 限制最多取 {@link #MAX_FANOUT_PRODUCTS} 条；</li>
     *   <li>只取 ID 再批量组装，避免一次性把全字段商品对象读进内存。</li>
     * </ol>
     * <p>
     * <b>超过阈值怎么办</b>：不静默失败——打 WARN 日志明确提示
     * 「请调用 POST /api/search/rebuild 做一次全量重建」。
     * 分类改名属低频运维操作，用一次全量重建来兜底是可接受的权衡；
     * 反过来若为此实现分片/游标式的增量扇出，复杂度与收益不成正比。
     * <p>
     * <b>事务边界</b>：本方法在事务提交后执行（内部用 {@code AfterCommitExecutor}）。
     * 若在事务内就推送索引，搜索服务回源读到的可能是未提交的旧分类名（双重陈旧）；
     * 且事务回滚后索引已被改写。
     *
     * @param categoryId 发生变化的分类ID
     */
    private void syncAffectedProductsIndex(Integer categoryId) {
        if (categoryId == null) {
            return;
        }
        AfterCommitExecutor.run(() -> {
            try {
                // 1. 先探规模：多取 1 条用于判断是否「超过阈值」
                List<Long> ids = productMapper.selectIdsByCategoryId(categoryId, MAX_FANOUT_PRODUCTS + 1);
                if (ids == null || ids.isEmpty()) {
                    log.debug("分类 [{}] 下没有商品，无需同步索引", categoryId);
                    return;
                }
                if (ids.size() > MAX_FANOUT_PRODUCTS) {
                    log.warn("分类 [{}] 下商品数超过扇出阈值 {}，已跳过分类名索引同步。"
                                    + "商品列表的分类名将暂时陈旧，请调用 POST /api/search/rebuild 做一次全量重建。",
                            categoryId, MAX_FANOUT_PRODUCTS);
                    return;
                }

                // 2. 组装索引视图（含店铺名/分类名/父分类名）
                List<ProductIndexItem> items = productIndexService.buildByIds(ids);
                if (items.isEmpty()) {
                    return;
                }

                // 3. 一次性批量推送（1 次 Feign，而不是 N 次 MQ）
                Result<Integer> r = searchIndexClient.upsertProducts(items);
                if (r != null && r.getCode() != null && r.getCode() == 200) {
                    log.info("分类 [{}] 改名后已同步 {} 个商品的索引文档", categoryId, r.getData());
                } else {
                    log.warn("分类 [{}] 索引同步返回异常: code={}, msg={}",
                            categoryId, r == null ? null : r.getCode(), r == null ? null : r.getMessage());
                }
            } catch (Exception e) {
                // 索引同步失败不影响分类改名本身（已在各自事务中提交），
                // 仅记录日志；陈旧数据可由全量重建兜底。
                log.error("分类 [{}] 变更后同步商品索引失败（可由全量重建兜底）", categoryId, e);
            }
        });
    }

    /**
     * 删除分类（物理删除）
     * 清除该分类本身、顶级分类、以及其父分类下的子列表缓存
     * <p>
     * 微服务拆分说明（列表查询改造）：<b>本方法刻意不做 product 索引扇出</b>，理由：
     * <ul>
     *   <li>删分类前运营一般已把该分类下商品迁走或下架，受影响商品通常为空；</li>
     *   <li>即使仍有残留商品，其 {@code categoryName} 留在索引里只是「显示一个已删除的分类名」，
     *       不会造成筛选错误（按该 categoryId 筛选仍能搜到它们，符合「先删分类、后清理商品」的实际流程）；</li>
     *   <li>若在这里也扇出，删除分类这种低频运维动作会连带触发大批商品索引改写，
     *       收益低而风险高（见 {@link #syncAffectedProductsIndex} 的阈值说明）。</li>
     * </ul>
     * 如需彻底一致，可在清理完该分类下商品后调用 POST /api/search/rebuild 全量重建。
     *
     * @param id 分类ID
     */
    @Override
    @Transactional
    public void deleteCategory(Integer id) {
        Category category = categoryMapper.selectByIde(id);
        if (category == null) {
            throw new RuntimeException("分类不存在");
        }
        categoryMapper.deleteById(id);
        evictCategoryCacheAfterCommit(id, category.getParentId());
    }

    // ==================== 缓存失效核心逻辑（延迟双删） ====================

    /**
     * 在事务提交后执行分类缓存清除（延迟双删模式）
     * <p>
     * 为何需要延迟双删？
     * - 立即删除：让缓存尽快失效，后续读请求能读到最新数据。
     * - 延迟再删：在事务提交后，可能有其他线程在事务提交前读到了旧数据并写回缓存，
     *   延迟删除可以清除这个被写回的旧缓存，进一步保证最终一致性。
     * <p>
     * 使用异步调度（ScheduledExecutorService）避免阻塞主线程。
     * <p>
     * 事务安全：改用公共库的 {@link AfterCommitExecutor}。
     * 原实现直接调用 {@code registerSynchronization}，在**没有事务**时会抛
     * {@code IllegalStateException}；AfterCommitExecutor 在无事务时退化为立即执行，
     * 不会因为调用方式变化而丢失缓存清理。
     *
     * @param categoryId 被影响的分类ID（可为null）
     * @param parentId   被影响的父分类ID（可为null）
     */
    private void evictCategoryCacheAfterCommit(Integer categoryId, Integer parentId) {
        AfterCommitExecutor.run(() -> {
            // 1. 立即删除相关缓存
            evictCategoryCacheNow(categoryId, parentId);

            // 2. 异步延迟300ms后再删一次（防止并发读造成的脏缓存）
            cacheDelayScheduler.schedule(() -> {
                try {
                    evictCategoryCacheNow(categoryId, parentId);
                    log.debug("延迟双删分类缓存成功, categoryId={}, parentId={}", categoryId, parentId);
                } catch (Exception e) {
                    log.error("延迟双删分类缓存失败", e);
                }
            }, 300, TimeUnit.MILLISECONDS);
        });
    }

    /** 实际执行分类相关缓存的删除（立即与延迟两处共用） */
    private void evictCategoryCacheNow(Integer categoryId, Integer parentId) {
        if (categoryId != null) {
            redisService.deleteKey(RedisService.CACHE_CATEGORY + categoryId);
        }
        redisService.deleteKey(RedisService.CACHE_CATEGORY_TOP);
        redisService.deleteKey(RedisService.CACHE_CATEGORY_ALL);
        if (parentId != null) {
            redisService.deleteKey(RedisService.CACHE_CATEGORY_SUB + parentId);
        }
    }
}

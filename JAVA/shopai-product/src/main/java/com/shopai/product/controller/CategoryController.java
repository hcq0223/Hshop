package com.shopai.product.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.product.entity.Category;
import com.shopai.product.service.CategoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 商品分类控制器
 * <p>
 * 顶级分类和子分类查询对所有用户公开（用于商品浏览页的分类筛选），
 * 新增/编辑/删除分类仅限管理员操作。
 * <p>
 * 微服务拆分说明（网关统一认证）：原单体通过 jwtUtil.getRole(token) 校验管理员，
 * 拆分后改用 CurrentUser.isRole(request, "admin")。
 * 路径 /api/category/** 保持不变（见设计文档 5.3）。
 */
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;
    private static final Logger log = LoggerFactory.getLogger(CategoryController.class);

    // ==================== 查询（公开） ====================

    /**
     * 查询所有分类（按层级排序，用于管理后台展示树形结构）
     */
    @GetMapping("/all")
    public Result<?> getAllCategories() {
        return Result.ok("全部分类列表", categoryService.getAll());
    }

    /**
     * 查询所有顶级分类（parent_id = 0），用于首页分类导航
     */
    @GetMapping("/top")
    public Result<?> getTopCategories() {
        return Result.ok("顶级分类列表", categoryService.getTopLevel());
    }

    /**
     * 根据父分类ID查询子分类列表，用于分类页二级/三级展示
     *
     * @param parentId 父分类ID（必填）
     */
    @GetMapping("/sub")
    public Result<?> getSubCategories(@RequestParam Integer parentId) {
        return Result.ok("子分类列表", categoryService.getByParentId(parentId));
    }

    /**
     * 根据ID查询单个分类详情
     *
     * @param id 分类ID
     */
    @GetMapping("/{id}")
    public Result<?> getCategoryById(@PathVariable Integer id) {
        Category category = categoryService.getById(id);
        if (category == null) {
            return Result.error("分类不存在");
        }
        return Result.ok(category);
    }

    // ==================== 写操作（仅管理员） ====================

    /**
     * 新增分类（仅管理员）
     * <p>
     * 请求体示例：{ "parentId": 0, "name": "上衣", "sortOrder": 1 }
     */
    @PostMapping("/add")
    public Result<?> addCategory(HttpServletRequest request, @RequestBody Category category) {
        // 网关统一认证：仅管理员可新增分类
        if (!CurrentUser.isRole(request, "admin")) {
            return Result.error("无权操作，仅管理员可新增分类");
        }
        try {
            categoryService.addCategory(category);
            log.info("管理员新增分类: id={}, name={}", category.getId(), category.getName());
            return Result.ok("分类新增成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑分类信息（仅管理员）
     * <p>
     * 可修改名称、父分类、排序等，若切换父分类会自动清理相关缓存
     */
    @PutMapping("/update")
    public Result<?> updateCategory(HttpServletRequest request, @RequestBody Category category) {
        // 网关统一认证：仅管理员可编辑分类
        if (!CurrentUser.isRole(request, "admin")) {
            return Result.error("无权操作，仅管理员可编辑分类");
        }
        try {
            categoryService.updateCategory(category);
            log.info("管理员更新分类: id={}, name={}", category.getId(), category.getName());
            return Result.ok("分类更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除分类（仅管理员，物理删除）
     * <p>
     * 注意：删除前应确保该分类下无子分类和商品，否则可能造成数据不一致。
     * 业务上建议先检查子分类和商品数量。
     */
    @DeleteMapping("/delete/{id}")
    public Result<?> deleteCategory(HttpServletRequest request, @PathVariable Integer id) {
        // 网关统一认证：仅管理员可删除分类
        if (!CurrentUser.isRole(request, "admin")) {
            return Result.error("无权操作，仅管理员可删除分类");
        }
        try {
            categoryService.deleteCategory(id);
            log.info("管理员删除分类: id={}", id);
            return Result.ok("分类删除成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}

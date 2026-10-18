package com.shopai.product.controller;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.security.CurrentUser;
import com.shopai.product.feign.MerchantInternalClient;
import com.shopai.product.entity.Product;
import com.shopai.product.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品（SPU）控制器
 * <p>
 * 查询类接口对登录用户开放，写操作接口需商户身份校验。
 * <p>
 * 微服务拆分说明（网关统一认证）：
 * 原单体通过 token（jwtUtil.getRole/getUserId）解析身份，拆分后网关已解析 JWT
 * 并写入 X-User-Id / X-User-Role 头，本控制器改用 CurrentUser 工具类读取。
 * <p>
 * 微服务拆分说明（独立库）：新增/编辑商品需要把 X-User-Id 解析为 merchantId，
 * 原通过 MerchantRefMapper 直查 merchants 表；按「独立库」处理后
 * merchants 表归属 shopai-merchant 服务，改为 MerchantInternalClient（Feign）调用。
 * <p>
 * 路径规范化（旧 → 新）：
 * - /api/product/detail/{id}        → GET    /api/product/{id}
 * - /api/product/add                → POST   /api/product
 * - /api/product/update             → PUT    /api/product/{id}
 * - /api/product/status/{id}        → PUT    /api/product/{id}/status
 * - /api/product/delete/{id}        → DELETE /api/product/{id}
 * - /api/product/merchant/{id}      → 不变
 * - /api/product/hot                → 不变
 * - /api/product/search             → 迁至 /api/search/product（shopai-search 服务）
 * - /api/product/merchant/{id}/search → 迁至 /api/search/product/merchant/{id}
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    // 微服务拆分：原 MerchantRefMapper（直查 merchants 表）→ Feign 调商家服务内部接口
    private final MerchantInternalClient merchantInternalClient;
    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    // ==================== 查询 ====================

    /**
     * 商品详情（联查商户、分类、SKU列表）
     * <p>
     * 旧路径：/api/product/detail/{id} → 新路径：/api/product/{id}
     *
     * @param id 商品ID
     */
    @GetMapping("/{id}")
    public Result<?> getDetail(@PathVariable Long id) {
        Product product = productService.getDetail(id);
        if (product == null) {
            return Result.error("商品不存在或已下架");
        }
        return Result.ok(product);
    }

    /**
     * 根据商户ID查询商品列表（用于商户店铺页展示，路径不变）
     *
     * @param merchantId 商户ID
     */
    @GetMapping("/merchant/{merchantId}")
    public Result<?> listByMerchant(@PathVariable Long merchantId) {
        List<Product> products = productService.listByMerchant(merchantId);
        return Result.ok("商户商品列表", products);
    }

    /**
     * 热销商品排行榜（按销量降序取前 N 条，路径不变）
     *
     * @param limit 数量（默认10）
     */
    @GetMapping("/hot")
    public Result<?> getHot(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok("热销商品", productService.getHotProducts(limit));
    }

    // ==================== 写操作（商户） ====================

    /**
     * 新增商品（仅商户）
     * <p>
     * 旧路径：/api/product/add → 新路径：POST /api/product
     * 从 X-User-Id 头解析当前商户，自动填充 merchantId。
     * 请求体示例：{ "categoryId": 5, "name": "纯棉T恤", ... }
     */
    @PostMapping
    public Result<?> addProduct(HttpServletRequest request, @RequestBody Product product) {
        // 网关统一认证：仅商户可发布商品
        if (!CurrentUser.isRole(request, "merchant")) {
            return Result.error("无权操作，仅商户可发布商品");
        }
        try {
            // 从 X-User-Id 头解析当前用户ID，再通过 Feign 调商家服务解析 merchantId，自动填充
            // 微服务拆分说明：原 MerchantRefMapper.selectByUserId(userId) 直查 merchants 表（共享单库），
            // 独立库后 merchants 表只有商家服务能访问，故改为调商家服务内部接口。
            Long userId = CurrentUser.getUserId(request);
            MerchantInfo merchant = resolveMerchant(userId);
            if (merchant == null) {
                return Result.error("未找到关联的商家信息");
            }
            product.setMerchantId(merchant.getId());
            productService.addProduct(product);
            log.info("商户新增商品: id={}, name={}, merchantId={}", product.getId(), product.getName(), product.getMerchantId());
            return Result.ok("商品发布成功", product);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 编辑商品信息（仅该商品所属商户）
     * <p>
     * 旧路径：/api/product/update → 新路径：PUT /api/product/{id}
     * 注意：当前 Service 未做商户归属校验，仅做角色校验，建议后续在 Service 层补充归属校验。
     */
    @PutMapping("/{id}")
    public Result<?> updateProduct(HttpServletRequest request,
                                   @PathVariable Long id,
                                   @RequestBody Product product) {
        // 网关统一认证：仅商户可编辑商品
        if (!CurrentUser.isRole(request, "merchant")) {
            return Result.error("无权操作，仅商户可编辑商品");
        }
        try {
            product.setId(id);
            productService.updateProduct(product);
            log.info("商户更新商品: id={}", id);
            return Result.ok("商品更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改商品状态（上架/下架/草稿，仅商户/管理员）
     * <p>
     * 旧路径：/api/product/status/{id} → 新路径：PUT /api/product/{id}/status
     *
     * @param id     商品ID
     * @param status 新状态：draft / on_sale / off_sale
     */
    @PutMapping("/{id}/status")
    public Result<?> updateStatus(HttpServletRequest request,
                                  @PathVariable Long id,
                                  @RequestParam String status) {
        // 网关统一认证：仅商户或管理员可修改商品状态
        if (!CurrentUser.isRole(request, "merchant") && !CurrentUser.isRole(request, "admin")) {
            return Result.error("无权操作，仅商户或管理员可修改商品状态");
        }
        try {
            productService.updateStatus(id, status);
            log.info("商户更新商品状态: id={}, status={}", id, status);
            return Result.ok("商品状态更新为: " + status);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除商品（物理删除，仅商户/管理员）
     * <p>
     * 旧路径：/api/product/delete/{id} → 新路径：DELETE /api/product/{id}
     * 删除前应确认该商品无未完成订单。生产环境建议改为逻辑删除。
     */
    @DeleteMapping("/{id}")
    public Result<?> deleteProduct(HttpServletRequest request, @PathVariable Long id) {
        // 网关统一认证：仅商户或管理员可删除商品
        if (!CurrentUser.isRole(request, "merchant") && !CurrentUser.isRole(request, "admin")) {
            return Result.error("无权操作，仅商户或管理员可删除商品");
        }
        try {
            productService.deleteProduct(id);
            log.info("商户删除商品: id={}", id);
            return Result.ok("商品删除成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 把网关下发的 userId 解析为当前商户
     * <p>
     * 微服务拆分说明：原 {@code MerchantRefMapper.selectByUserId(userId)} 直查 merchants 表；
     * 按「独立库」处理后 merchants 表归属 shopai-merchant 服务，改为 Feign 调其内部接口。
     * 降级策略：商家服务不可用时返回 null，由调用方给出「未找到关联的商家信息」提示，
     * 不影响商品服务的其它接口。
     *
     * @param userId 用户ID（来自网关 X-User-Id 头）
     * @return 商家信息；未入驻或商家服务不可用时返回 null
     */
    private MerchantInfo resolveMerchant(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<MerchantInfo> r = merchantInternalClient.getByUserId(userId);
            if (r != null && r.getCode() != null && r.getCode() == 200) {
                return r.getData();
            }
        } catch (Exception e) {
            log.error("调用商家服务解析 merchantId 失败, userId={}", userId, e);
        }
        return null;
    }
}

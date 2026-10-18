package com.shopai.order.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.order.entity.ShoppingCart;
import com.shopai.order.service.CustomerResolver;
import com.shopai.order.service.ShoppingCartService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 购物车控制器
 * <p>
 * 所有操作需要先通过请求头（网关统一认证）获取 customerId，
 * 确保用户只能操作自己的购物车。
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Controller.CartController 迁移至 shopai-order 服务。
 * 鉴权改造：不再各自验签 JWT，userId 由网关 X-User-Id 头提供（CurrentUser）；
 * userId → customerId 映射改用 CustomerResolver（Feign 调用户服务客户内部接口，
 * 替代原 CustomerRefMapper 跨库直查 customers 表）。
 * <p>
 * 路径：/api/cart/** 保持不变（见设计文档 5.4）。
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final ShoppingCartService cartService;
    private final CustomerResolver customerResolver;
    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    /**
     * 从请求头提取当前登录用户的 customerId
     * <p>
     * 说明：X-User-Id 中存的是 users.id，而购物车业务使用 customers.id。
     * 这里通过 CustomerResolver 将 users.id 映射为 customers.id（内部 Feign 调用户服务）。
     */
    private Long getCustomerId(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        String role = CurrentUser.getRole(request);
        if (!"customer".equals(role)) {
            return null;
        }
        return customerResolver.toCustomerId(userId);
    }

    private Result<?> requireCustomer(HttpServletRequest request) {
        Long customerId = getCustomerId(request);
        if (customerId == null) {
            return Result.error("仅顾客身份可操作购物车");
        }
        return null;
    }

    // ==================== 查询 ====================

    /**
     * 获取当前用户的购物车列表（联查 SKU 和商品信息）
     * <p>
     * 返回结果包含：购物车条目ID、SKU信息（颜色/尺码/价格/库存/图片）、
     * 商品名称、数量、选中状态等。
     */
    @GetMapping("/list")
    public Result<?> getCartList(HttpServletRequest request) {
        Result<?> check = requireCustomer(request);
        if (check != null) return check;
        Long customerId = getCustomerId(request);
        List<ShoppingCart> cartItems = cartService.getCartByCustomerId(customerId);
        return Result.ok("购物车列表", cartItems);
    }

    /**
     * 检查某个SKU是否已在购物车中（用于前端按钮文案：加入购物车 / 已添加）
     *
     * @param skuId SKU ID
     */
    @GetMapping("/exists")
    public Result<?> existsInCart(HttpServletRequest request,
                                  @RequestParam Long skuId) {
        Long customerId = getCustomerId(request);
        boolean exists = cartService.existsInCart(customerId, skuId);
        return Result.ok(exists ? "已在购物车中" : "未加入购物车", exists);
    }

    /**
     * 获取购物车中某个SKU的具体条目（含ID、数量、选中状态）
     *
     * @param skuId SKU ID
     */
    @GetMapping("/item")
    public Result<?> getCartItem(HttpServletRequest request,
                                 @RequestParam Long skuId) {
        Long customerId = getCustomerId(request);
        ShoppingCart item = cartService.getCartItemBySku(customerId, skuId);
        if (item == null) {
            return Result.ok("该商品不在购物车中", null);
        }
        return Result.ok(item);
    }

    // ==================== 写操作 ====================

    /**
     * 添加商品到购物车（如已存在则累加数量）
     * <p>
     * 请求参数：skuId（SKU ID）+ quantity（数量，必须 > 0）
     *
     * @param skuId    SKU ID
     * @param quantity 数量
     */
    @PostMapping("/add")
    public Result<?> addToCart(HttpServletRequest request,
                               @RequestParam Long skuId,
                               @RequestParam(defaultValue = "1") Integer quantity) {
        Result<?> check = requireCustomer(request);
        if (check != null) return check;
        Long customerId = getCustomerId(request);
        try {
            cartService.addToCart(customerId, skuId, quantity);
            log.debug("添加购物车: customerId={}, skuId={}, quantity={}", customerId, skuId, quantity);
            return Result.ok("已添加到购物车");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新购物车中某商品的数量（根据购物车条目ID）
     *
     * @param cartId   购物车条目ID
     * @param quantity 新数量（必须 > 0）
     */
    @PutMapping("/updateQuantity/{cartId}")
    public Result<?> updateQuantity(HttpServletRequest request,
                                    @PathVariable Long cartId,
                                    @RequestParam Integer quantity) {
        try {
            cartService.updateQuantity(cartId, quantity);
            return Result.ok("数量更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 根据SKU ID更新购物车数量（前端只传 skuId + 数量，不传 cartId 的场景）
     * <p>
     * 如果该SKU不在购物车中，会自动新增一条记录。
     *
     * @param skuId    SKU ID
     * @param quantity 数量（必须 > 0）
     */
    @PutMapping("/updateBySku")
    public Result<?> updateQuantityBySku(HttpServletRequest request,
                                         @RequestParam Long skuId,
                                         @RequestParam Integer quantity) {
        Long customerId = getCustomerId(request);
        try {
            cartService.updateQuantityBySku(customerId, skuId, quantity);
            return Result.ok("数量更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新单个购物车条目的选中状态
     *
     * @param cartId   购物车条目ID
     * @param selected 1=选中，0=取消选中
     */
    @PutMapping("/select/{cartId}")
    public Result<?> updateSelected(HttpServletRequest request,
                                    @PathVariable Long cartId,
                                    @RequestParam Integer selected) {
        try {
            cartService.updateSelected(cartId, selected);
            return Result.ok("选中状态更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量更新购物车选中状态（全选 / 取消全选）
     * <p>
     * 请求体示例：{ "ids": [1, 2, 3], "selected": 1 }
     *
     * @param ids      购物车条目ID列表
     * @param selected 1=全选，0=取消全选
     */
    @PutMapping("/selectBatch")
    public Result<?> updateSelectedBatch(HttpServletRequest request,
                                         @RequestParam List<Long> ids,
                                         @RequestParam Integer selected) {
        Long customerId = getCustomerId(request);
        try {
            cartService.updateSelectedBatch(customerId, ids, selected);
            return Result.ok("批量选中状态更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除购物车中的某个商品（根据SKU ID）
     *
     * @param skuId SKU ID
     */
    @DeleteMapping("/deleteBySku")
    public Result<?> deleteBySku(HttpServletRequest request,
                                 @RequestParam Long skuId) {
        Long customerId = getCustomerId(request);
        try {
            cartService.deleteCartItem(customerId, skuId);
            return Result.ok("商品已从购物车移除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量删除购物车条目（根据购物车条目ID列表）
     * <p>
     * 用于用户在前端勾选后点击"删除选中"按钮的场景。
     */
    @DeleteMapping("/deleteBatch")
    public Result<?> deleteBatch(HttpServletRequest request,
                                 @RequestParam List<Long> ids) {
        Long customerId = getCustomerId(request);
        try {
            cartService.deleteCartItems(customerId, ids);
            return Result.ok("选中商品已从购物车移除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 清空购物车
     */
    @DeleteMapping("/clear")
    public Result<?> clearCart(HttpServletRequest request) {
        Long customerId = getCustomerId(request);
        try {
            cartService.clearCart(customerId);
            return Result.ok("购物车已清空");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}

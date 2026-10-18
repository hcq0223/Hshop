package com.shopai.user.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.user.entity.Customer;
import com.shopai.user.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 客户控制器（顾客端）
 * <p>
 * 微服务拆分说明：从原单体 UserController 的「客户段」拆出，路径规范化（见设计文档 5.1）：
 * - 旧 /api/user/customer/addCustomer    → 新 /api/customer/add
 * - 旧 /api/user/customer/updateCustomer → 新 /api/customer/update
 * - 旧 /api/user/customer/getCustomer    → 新 /api/customer/me
 * - 旧 /api/user/customer/getCustomerMax → 新 /api/customer/me/detail
 * <p>
 * 鉴权改造：userId 由网关 X-User-Id 头提供（CurrentUser），不再从 token 解析。
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /**
     * 新增客户信息
     * 旧路径：/api/user/customer/addCustomer → 新路径：/api/customer/add
     */
    @PostMapping("/add")
    public Result<?> addCustomer(HttpServletRequest request,
                                 @RequestBody Customer customer){
        Long userId = CurrentUser.getUserId(request);
        int rows = customerService.addCustomer(customer, userId);
        if (rows != 1) return Result.error("新增失败");
        return Result.ok("客户信息添加成功");
    }

    /**
     * 更新
     * 旧路径：/api/user/customer/updateCustomer → 新路径：/api/customer/update
     */
    @PutMapping("/update")
    public Result<?> updateCustomer(HttpServletRequest request,
                                    @RequestBody Customer customer){
        Long userId = CurrentUser.getUserId(request);
        int rows = customerService.updateCustomer(customer, userId);
        if (rows != 1) return Result.error("更新失败");
        return Result.ok("客户信息更新成功");
    }

    /**
     * 用户查看个人客户表信息
     * 旧路径：/api/user/customer/getCustomer → 新路径：/api/customer/me
     */
    @GetMapping("/me")
    public Result<?> getMe(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        return Result.ok("用户信息", customerService.getCustomerByUserId(userId));
    }

    /**
     * 用户联表查看个人信息
     * 旧路径：/api/user/customer/getCustomerMax → 新路径：/api/customer/me/detail
     */
    @GetMapping("/me/detail")
    public Result<?> getMeDetail(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        return Result.ok("用户信息", customerService.getMyCustomerDetail(userId));
    }

}

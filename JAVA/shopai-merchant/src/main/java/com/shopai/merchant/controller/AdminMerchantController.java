package com.shopai.merchant.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.merchant.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员商家管理控制器
 * <p>
 * 微服务拆分说明：从原单体 UserController 的「管理员-商家段」拆出，路径规范化（见设计文档 5.2）：
 * - 旧 /api/user/admin/verifyMerchant       → 新 /api/admin/merchants/{id}/verify         (PUT)
 * - 旧 /api/user/admin/getPendingMerchants  → 新 /api/admin/merchants/pending             (GET)
 * - 旧 /api/user/admin/getMerchant          → 新 /api/admin/merchants/by-user/{userId}     (GET)
 * - 旧 /api/user/admin/getModifications     → 新 /api/admin/merchants/modifications        (GET)
 * - 旧 /api/user/admin/approveModification  → 新 /api/admin/merchants/modifications/{id}/approve (PUT)
 * - 旧 /api/user/admin/rejectModification   → 新 /api/admin/merchants/modifications/{id}/reject  (PUT)
 * - 旧 /api/user/admin/pendingWithdraws     → 新 /api/admin/merchants/withdraws/pending    (GET)
 * - 旧 /api/user/admin/handleWithdraw       → 新 /api/admin/merchants/withdraws/{id}       (PUT)
 * <p>
 * 鉴权改造：原控制器用 jwtUtil.getRole(token) 校验 admin；拆分后由网关统一校验
 * /api/admin/** 前缀需 admin 角色（JwtAuthGlobalFilter），本控制器读取 X-User-Role 头兜底。
 */
@RestController
@RequestMapping("/api/admin/merchants")
@RequiredArgsConstructor
public class AdminMerchantController {

    private static final Logger log = LoggerFactory.getLogger(AdminMerchantController.class);

    private final MerchantService merchantService;

    /**
     * 管理员权限兜底校验（网关已校验 /api/admin/** 需 admin，此处二次确认）
     */
    private void checkAdmin(HttpServletRequest request) {
        if (!CurrentUser.isRole(request, "admin")) {
            throw new RuntimeException("无权操作");
        }
    }

    /**
     * 管理员审核商家（通过/拒绝）
     * 旧路径：/api/user/admin/verifyMerchant?merchantId=xxx&verified=1 → 新路径：/api/admin/merchants/{id}/verify?verified=1
     */
    @PutMapping("/{id}/verify")
    public Result<?> verifyMerchant(@PathVariable Long id,
                                    @RequestParam Integer verified,
                                    HttpServletRequest request) {
        checkAdmin(request);
        if (verified != 0 && verified != 1) return Result.error("审核状态只能为 0（拒绝）或 1（通过）");
        merchantService.verifyMerchant(id, verified);
        log.info("管理员审核了商家 [merchantId={}] 状态: {}", id, verified == 1 ? "通过" : "拒绝");
        return Result.ok("商家 [merchantId=" + id + "] 审核" + (verified == 1 ? "通过" : "拒绝"));
    }

    /**
     * 管理员查询待审核商家列表
     * 旧路径：/api/user/admin/getPendingMerchants → 新路径：/api/admin/merchants/pending
     */
    @GetMapping("/pending")
    public Result<?> getPendingMerchants(@RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer size,
                                         HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("待审核商家列表", merchantService.getPendingMerchants(page, size));
    }

    /**
     * 管理员查询商户信息（按用户的 userId 查询）
     * 旧路径：/api/user/admin/getMerchant?userId=xxx → 新路径：/api/admin/merchants/by-user/{userId}
     */
    @GetMapping("/by-user/{userId}")
    public Result<?> getMerchantByUserId(@PathVariable Long userId, HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("商户userId: " + userId + "详情: ", merchantService.selectDetailByUserId(userId));
    }

    /**
     * 管理员查询店铺信息修改申请列表
     * 旧路径：/api/user/admin/getModifications?status=pending → 新路径：/api/admin/merchants/modifications
     */
    @GetMapping("/modifications")
    public Result<?> getModifications(@RequestParam(defaultValue = "pending") String status,
                                      HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("修改申请列表", merchantService.getModifications(status));
    }

    /**
     * 管理员同意店铺信息修改（将修改应用到 merchants 表）
     * 旧路径：/api/user/admin/approveModification?modId=xxx → 新路径：/api/admin/merchants/modifications/{id}/approve
     */
    @PutMapping("/modifications/{id}/approve")
    public Result<?> approveModification(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        merchantService.approveModification(id);
        return Result.ok("修改已通过并生效");
    }

    /**
     * 管理员拒绝店铺信息修改
     * 旧路径：/api/user/admin/rejectModification?modId=xxx → 新路径：/api/admin/merchants/modifications/{id}/reject
     */
    @PutMapping("/modifications/{id}/reject")
    public Result<?> rejectModification(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        merchantService.rejectModification(id);
        return Result.ok("修改已拒绝");
    }

    /**
     * 管理员查询待处理提现列表
     * 旧路径：/api/user/admin/pendingWithdraws → 新路径：/api/admin/merchants/withdraws/pending
     */
    @GetMapping("/withdraws/pending")
    public Result<?> getPendingWithdraws(HttpServletRequest request) {
        checkAdmin(request);
        return Result.ok("待处理提现", merchantService.getPendingWithdraws());
    }

    /**
     * 管理员处理提现（通过/拒绝）
     * 旧路径：/api/user/admin/handleWithdraw?withdrawId=xxx&action=approved → 新路径：/api/admin/merchants/withdraws/{id}?action=approved
     */
    @PutMapping("/withdraws/{id}")
    public Result<?> handleWithdraw(@PathVariable Long id,
                                    @RequestParam String action,
                                    HttpServletRequest request) {
        checkAdmin(request);
        merchantService.handleWithdraw(id, action);
        return Result.ok("提现处理完成");
    }
}

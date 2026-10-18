package com.shopai.merchant.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.entity.ReviewComplaint;
import com.shopai.merchant.service.MerchantService;
import com.shopai.merchant.service.ReviewComplaintService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评价投诉（申诉）控制器
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.controller.ReviewComplaintController 迁移至 shopai-merchant 服务，
 * 路径保持不变（/api/review/complaint）。改造点：
 * 1. 鉴权方式：原通过 token 解析 userId 后再 merchantService.getByUserId(userId) 查 merchantId；
 *    拆分后 userId 由网关 X-User-Id 头提供，merchantId 仍通过 merchantService.getByUserId(userId) 查。
 */
@RestController
@RequestMapping("/api/review/complaint")
@RequiredArgsConstructor
public class ReviewComplaintController {

    private final ReviewComplaintService complaintService;
    private final MerchantService merchantService;
    private static final Logger log = LoggerFactory.getLogger(ReviewComplaintController.class);

    /** 商家发起申诉 */
    @PostMapping("/add")
    public Result<?> add(HttpServletRequest request,
                         @RequestBody ReviewComplaint complaint) {
        Long userId = CurrentUser.getUserId(request);
        try {
            // 获取商家的 merchantId
            Merchant m = merchantService.getByUserId(userId);
            if (m == null) return Result.error("未找到对应商家");
            complaint.setMerchantId(m.getId());
            complaintService.addComplaint(complaint);
            return Result.ok("申诉提交成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 管理员查申诉列表
     * <p>
     * 权限修复：原实现既没有 {@code HttpServletRequest} 也没有任何角色校验，
     * 任何登录用户（顾客/商家）都能拉到全平台申诉列表（含评价内容、商家、
     * 处理备注），实测顾客 token 直接返回 4 条记录。
     */
    @GetMapping("/list")
    public Result<?> list(HttpServletRequest request,
                          @RequestParam(required = false) String status) {
        Result<?> deny = requireAdmin(request);
        if (deny != null) return deny;
        List<Map<String, Object>> list = complaintService.list(status);
        return Result.ok("申诉列表", list);
    }

    /** 商家查自己的申诉 */
    @GetMapping("/my")
    public Result<?> my(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        Merchant m = merchantService.getByUserId(userId);
        if (m == null) return Result.error("未找到对应商家");
        List<Map<String, Object>> list = complaintService.listByMerchant(m.getId());
        return Result.ok("我的申诉", list);
    }

    /**
     * 管理员驳回申诉
     * <p>
     * 权限修复：原实现无任何鉴权，任何登录用户都能驳回任意申诉。
     */
    @PutMapping("/{id}/reject")
    public Result<?> reject(HttpServletRequest request,
                            @PathVariable Long id,
                            @RequestBody(required = false) Map<String, String> body) {
        Result<?> deny = requireAdmin(request);
        if (deny != null) return deny;
        try {
            String adminNote = body == null ? "" : body.getOrDefault("adminNote", "");
            complaintService.reject(id, adminNote);
            return Result.ok("申诉已驳回");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 管理员同意申诉（删除评论）
     * <p>
     * 权限修复：原实现无任何鉴权 —— 任何登录用户（包括被申诉评价的商家本人）
     * 都能「同意」申诉，而本方法内部会调用 {@code reviewService.deleteReview}，
     * 等于让商家一键删掉对自己不利的评价。
     */
    @PutMapping("/{id}/approve")
    public Result<?> approve(HttpServletRequest request,
                             @PathVariable Long id,
                             @RequestBody(required = false) Map<String, String> body) {
        Result<?> deny = requireAdmin(request);
        if (deny != null) return deny;
        try {
            String adminNote = body == null ? "" : body.getOrDefault("adminNote", "");
            complaintService.approve(id, adminNote);
            return Result.ok("申诉通过，评论已删除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 管理员校验（fail-closed）。
     * <p>
     * 为什么不放在网关：{@code /api/review/complaint/**} 是混合前缀 ——
     * {@code /add}（商家提交申诉）、{@code /my}（商家查自己的）不是管理员接口，
     * 不能整段按 admin 前缀拦截，只能在方法级逐个校验。
     *
     * @return null 表示通过；否则是应直接返回的 403
     */
    private Result<?> requireAdmin(HttpServletRequest request) {
        String role = CurrentUser.getRole(request);
        if ("admin".equalsIgnoreCase(role)) {
            return null;
        }
        log.warn("申诉管理接口越权已拦截: userId={}, role={}",
                CurrentUser.getUserId(request), role);
        return Result.error(403, "无权访问，权限不足");
    }
}

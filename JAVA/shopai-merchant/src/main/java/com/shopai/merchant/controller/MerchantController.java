package com.shopai.merchant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 商家控制器（商家自助 + 客户端公开查询）
 * <p>
 * 微服务拆分说明：从原单体 UserController 的「商户段 + 顾客段商家查询」拆出，路径规范化（见设计文档 5.2）：
 * - 旧 /api/user/merchant/addMerchant        → 新 /api/merchant/apply            (POST)
 * - 旧 /api/user/merchant/getMerchant(token)  → 新 /api/merchant/me             (GET)
 * - 旧 /api/user/merchant/updateMerchant     → 新 /api/merchant/me/store        (PUT)
 * - 旧 /api/user/merchant/updateRealName     → 新 /api/merchant/me/real-name    (PUT)
 * - 旧 /api/user/merchant/updateAccount      → 新 /api/merchant/me/account      (PUT)
 * - 旧 /api/user/merchant/check-status       → 新 /api/merchant/me/check-status (GET)
 * - 旧 /api/user/merchant/me                 → 新 /api/merchant/me/id           (GET)
 * - 旧 /api/user/merchant/finance            → 新 /api/merchant/me/finance      (GET)
 * - 旧 /api/user/merchant/withdraw           → 新 /api/merchant/withdraw        (POST)
 * - 旧 /api/user/merchant/withdraws          → 新 /api/merchant/withdraws       (GET)
 * - 旧 /api/user/merchant/weekly-revenue     → 新 /api/merchant/weekly-revenue  (GET)
 * - 旧 /api/user/customer/getMerchants       → 新 /api/merchant/list            (GET, 公开)
 * - 旧 /api/user/customer/getMerchant        → 新 /api/merchant/{id}            (GET, 公开)
 * - 旧 /api/user/customer/getMerchantRank    → 新 /api/merchant/rank            (GET, 公开)
 * <p>
 * 鉴权改造：userId 由网关 X-User-Id 头提供（CurrentUser），不再从 token 解析。
 */
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private static final Logger log = LoggerFactory.getLogger(MerchantController.class);

    /**
     * 从请求头解析当前登录用户的 userId。
     */
    private Long resolveUserId(HttpServletRequest request) {
        return CurrentUser.getUserId(request);
    }

    // ==================== 商家自助 ====================

    /**
     * 商家入驻申请
     * 旧路径：/api/user/merchant/addMerchant → 新路径：/api/merchant/apply
     */
    @PostMapping("/apply")
    public Result<?> addMerchant(HttpServletRequest request, @RequestBody Merchant merchant) {
        Long userId = resolveUserId(request);
        int rows = merchantService.addMerchant(userId, merchant);
        if (rows != 1) return Result.error("新增失败");
        return Result.ok("商户信息添加成功");
    }

    /**
     * 商家查询自身详情（联查 users 基础信息）
     * 旧路径：/api/user/merchant/getMerchant → 新路径：/api/merchant/me
     */
    @GetMapping("/me")
    public Result<?> getMerchant(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return Result.ok("商户详情 ", merchantService.getMerchantById(userId));
    }

    /**
     * 提交店铺信息修改（写入修改申请表等待审核）
     * 旧路径：/api/user/merchant/updateMerchant → 新路径：/api/merchant/me/store
     */
    @PutMapping("/me/store")
    public Result<?> updateMerchant(HttpServletRequest request, @RequestBody Merchant merchant) {
        Long userId = resolveUserId(request);
        merchantService.submitModification(userId, merchant);
        return Result.ok("修改已提交，等待管理员审核");
    }

    /**
     * 提交实名信息修改（写入修改申请表等待审核）
     * 旧路径：/api/user/merchant/updateRealName → 新路径：/api/merchant/me/real-name
     */
    @PutMapping("/me/real-name")
    public Result<?> updateRealName(HttpServletRequest request, @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(request);
        merchantService.submitRealNameModification(userId,
                body.get("realName"),
                body.get("cardId"),
                body.get("cardUrl"));
        return Result.ok("实名信息修改已提交，等待管理员审核");
    }

    /**
     * 商家更新账号基础信息（username / phone / email）
     * 旧路径：/api/user/merchant/updateAccount → 新路径：/api/merchant/me/account
     */
    @PutMapping("/me/account")
    public Result<?> updateAccount(HttpServletRequest request, @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(request);
        String username = body.get("username");
        String phone = body.get("phone");
        String email = body.get("email");
        merchantService.updateAccount(userId, username, phone, email);
        return Result.ok("账号信息更新成功");
    }

    /**
     * 检查商家审核状态
     * 旧路径：/api/user/merchant/check-status → 新路径：/api/merchant/me/check-status
     */
    @GetMapping("/me/check-status")
    public Result<?> checkMerchantStatus(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        Merchant merchant = merchantService.getMerchantById(userId);
        if (merchant == null || merchant.getIsVerified() == null || merchant.getIsVerified() != 1) {
            return Result.ok("商家审核中，暂不可使用商家功能", java.util.Map.of("isVerified", 0));
        }
        return Result.ok("商家已认证", java.util.Map.of("isVerified", 1));
    }

    /**
     * 获取当前商户的 merchantId
     * 旧路径：/api/user/merchant/me → 新路径：/api/merchant/me/id
     */
    @GetMapping("/me/id")
    public Result<?> getCurrentMerchant(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        Merchant merchant = merchantService.getMerchantById(userId);
        if (merchant == null) return Result.error("未找到关联的商家信息");
        return Result.ok(java.util.Map.of("merchantId", merchant.getId()));
    }

    /**
     * 获取商家财务概览（总营收、可用余额、冻结余额）
     * 旧路径：/api/user/merchant/finance → 新路径：/api/merchant/me/finance
     */
    @GetMapping("/me/finance")
    public Result<?> getFinance(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return Result.ok("财务概览", merchantService.getFinanceOverview(userId));
    }

    /**
     * 商家申请提现
     * 旧路径：/api/user/merchant/withdraw → 新路径：/api/merchant/withdraw
     */
    @PostMapping("/withdraw")
    public Result<?> applyWithdraw(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(request);
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String remark = (String) body.get("remark");
        merchantService.applyWithdraw(userId, amount, remark);
        return Result.ok("提现申请已提交");
    }

    /**
     * 查询商家提现记录
     * 旧路径：/api/user/merchant/withdraws → 新路径：/api/merchant/withdraws
     */
    @GetMapping("/withdraws")
    public Result<?> getWithdraws(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return Result.ok("提现记录", merchantService.getWithdrawRecords(userId));
    }

    /**
     * 查询商家历史周营收
     * 旧路径：/api/user/merchant/weekly-revenue → 新路径：/api/merchant/weekly-revenue
     */
    @GetMapping("/weekly-revenue")
    public Result<?> getWeeklyRevenueHistory(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return Result.ok("历史周营收", merchantService.getWeeklyRevenueHistory(userId));
    }

    // ==================== 客户端公开查询 ====================

    /**
     * 查询商户列表（公开）
     * 旧路径：/api/user/customer/getMerchants → 新路径：/api/merchant/list
     */
    @GetMapping("/list")
    public Result<?> listMerchant(@RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size,
                                  @RequestParam(required = false) String keyword) {
        Page<Merchant> req = new Page<>(page, size);
        return Result.ok("商户列表", merchantService.queryMerchantPage(req, keyword));
    }

    /**
     * 查询商户详情（公开）
     * 旧路径：/api/user/customer/getMerchant → 新路径：/api/merchant/{id}
     */
    @GetMapping("/{id}")
    public Result<?> getMerchant(@PathVariable Long id) {
        return Result.ok("商户详情", merchantService.getMerchantPublicInfo(id));
    }

    /**
     * 推荐商家排行（公开）
     * 旧路径：/api/user/customer/getMerchantRank → 新路径：/api/merchant/rank
     */
    @GetMapping("/rank")
    public Result<?> getMerchantRank() {
        return Result.ok("推荐商家排行", merchantService.getVerifiedMerchantsWithMinRating(new BigDecimal("5.0")));
    }

}

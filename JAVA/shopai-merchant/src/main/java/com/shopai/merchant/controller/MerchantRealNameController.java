package com.shopai.merchant.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.merchant.entity.MerchantRealName;
import com.shopai.merchant.service.MerchantRealNameService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 商家实名认证控制器
 * <p>
 * 微服务拆分说明：从原单体 MerchantRealNameController 迁入 shopai-merchant 服务，路径规范化（见设计文档 5.2）：
 * - 旧 /api/user/merchant/real-name/get    → 新 /api/merchant/real-name     (GET)
 * - 旧 /api/user/merchant/real-name/add    → 新 /api/merchant/real-name     (POST)
 * - 旧 /api/user/merchant/real-name/update → 新 /api/merchant/real-name     (PUT)
 * - 旧 /api/user/merchant/real-name/delete → 新 /api/merchant/real-name     (DELETE)
 * <p>
 * 鉴权改造：原方法通过 token 解析 userId；拆分后 userId 由网关 X-User-Id 头提供（CurrentUser），
 * Service 方法签名由 (token, ...) 改为 (userId, ...)。
 */
@RestController
@RequestMapping("/api/merchant/real-name")
@RequiredArgsConstructor
public class MerchantRealNameController {

    private final MerchantRealNameService realNameService;
    private static final Logger log = LoggerFactory.getLogger(MerchantRealNameController.class);

    /**
     * 查询当前商家的实名认证信息
     * 旧路径：/api/user/merchant/real-name/get → 新路径：/api/merchant/real-name
     */
    @GetMapping
    public Result<?> getRealName(HttpServletRequest request) {
        try {
            Long userId = CurrentUser.getUserId(request);
            MerchantRealName info = realNameService.getMyRealName(userId);
            if (info == null) {
                return Result.ok(null); // 未提交过，返回空
            }
            // 脱敏：身份证号仅显示前6位和后4位，中间用 * 替代
            if (info.getCardId() != null && info.getCardId().length() >= 6) {
                int len = info.getCardId().length();
                String masked = info.getCardId().substring(0, 6)
                        + "********"
                        + info.getCardId().substring(len - 4);
                info.setCardId(masked);
            }
            return Result.ok("查询成功", info);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 新增（提交）实名认证信息
     * 旧路径：/api/user/merchant/real-name/add → 新路径：/api/merchant/real-name
     * 请求体：{ realName, cardId, cardUrl }
     */
    @PostMapping
    public Result<?> addRealName(HttpServletRequest request,
                                 @RequestBody MerchantRealName realName) {
        try {
            Long userId = CurrentUser.getUserId(request);
            int rows = realNameService.addRealName(userId, realName);
            if (rows != 1) {
                return Result.error("新增失败");
            }
            return Result.ok("实名认证信息提交成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新实名认证信息
     * 旧路径：/api/user/merchant/real-name/update → 新路径：/api/merchant/real-name
     * 请求体：{ realName, cardId, cardUrl }
     */
    @PutMapping
    public Result<?> updateRealName(HttpServletRequest request,
                                    @RequestBody MerchantRealName realName) {
        try {
            Long userId = CurrentUser.getUserId(request);
            int rows = realNameService.updateRealName(userId, realName);
            if (rows != 1) {
                return Result.error("更新失败");
            }
            return Result.ok("实名认证信息更新成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除实名认证信息
     * 旧路径：/api/user/merchant/real-name/delete → 新路径：/api/merchant/real-name
     */
    @DeleteMapping
    public Result<?> deleteRealName(HttpServletRequest request) {
        try {
            Long userId = CurrentUser.getUserId(request);
            int rows = realNameService.deleteRealName(userId);
            if (rows == 0) {
                return Result.error("无实名认证记录可删除");
            }
            return Result.ok("实名认证信息已删除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}

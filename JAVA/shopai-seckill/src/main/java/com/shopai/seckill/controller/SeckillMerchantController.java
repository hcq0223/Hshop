package com.shopai.seckill.controller;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.security.CurrentUser;
import com.shopai.seckill.dto.SeckillActivityVO;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.feign.MerchantInternalClient;
import com.shopai.seckill.mapper.SeckillStockMapper;
import com.shopai.seckill.service.SeckillActivityService;
import com.shopai.seckill.service.SeckillCompensateService;
import com.shopai.seckill.service.SeckillInfoService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 秒杀商家端接口（对网关暴露：{@code /api/seckill/merchant/**}）
 * <p>
 * <b>归属校验的统一做法</b>：每个方法第一步都调 {@link #requireMerchantId}，
 * 由「网关 X-User-Id → Feign 调商家服务」换出可信的 merchantId。
 * 后续所有查询/写入都以这个 merchantId 为条件，
 * <b>绝不使用前端传来的 merchantId</b> —— 否则商家 A 改个参数就能操作商家 B 的活动。
 * <p>
 * 网关侧需对 {@code /api/seckill/merchant/**} 做 merchant 角色校验
 * （见 shopai-gateway 的 JwtAuthGlobalFilter），本类的校验是第二道防线：
 * 即使网关规则漏配，这里也拿不到别人的 merchantId。
 */
@RestController
@RequestMapping("/api/seckill/merchant")
public class SeckillMerchantController {

    private static final Logger log = LoggerFactory.getLogger(SeckillMerchantController.class);

    @Autowired
    private SeckillActivityService activityService;

    @Autowired
    private SeckillInfoService infoService;

    @Autowired
    private SeckillCompensateService compensateService;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private MerchantInternalClient merchantInternalClient;

    // ==================== 活动管理 ====================

    /**
     * 我的秒杀活动列表。
     *
     * @param status 状态筛选（可空）
     */
    @GetMapping("/activities")
    public Result<List<SeckillActivity>> listActivities(HttpServletRequest request,
                                                       @RequestParam(required = false) String status) {
        Long merchantId = requireMerchantId(request);
        return Result.ok("我的秒杀活动", activityService.listMyActivities(merchantId, status));
    }

    /**
     * 活动详情（含商品与实时余量，供商家预览）。
     *
     * @param id 活动ID
     */
    @GetMapping("/activities/{id}")
    public Result<SeckillActivityVO> getActivity(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        SeckillActivity activity = activityService.getOwnedActivity(merchantId, id);
        return Result.ok("活动详情", infoService.buildActivityVO(activity, null));
    }

    /**
     * 创建活动（草稿态）。
     * <p>
     * 请求体字段：{@code title}、{@code startTime}、{@code endTime}、
     * {@code perUserLimit}、{@code description}
     */
    @PostMapping("/activities")
    public Result<SeckillActivity> createActivity(HttpServletRequest request,
                                                 @RequestBody Map<String, Object> body) {
        Long merchantId = requireMerchantId(request);
        SeckillActivity activity = new SeckillActivity();
        activity.setTitle(str(body.get("title")));
        activity.setStartTime(parseDateTime(body.get("startTime")));
        activity.setEndTime(parseDateTime(body.get("endTime")));
        activity.setPerUserLimit(body.get("perUserLimit") == null
                ? 1 : parseInt(body.get("perUserLimit"), 1));
        activity.setDescription(str(body.get("description")));
        return Result.ok("活动创建成功", activityService.createActivity(merchantId, activity));
    }

    /**
     * 修改活动（仅草稿/下线态）。
     */
    @PutMapping("/activities/{id}")
    public Result<?> updateActivity(HttpServletRequest request, @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Long merchantId = requireMerchantId(request);
        SeckillActivity activity = new SeckillActivity();
        activity.setId(id);
        activity.setTitle(str(body.get("title")));
        activity.setStartTime(parseDateTime(body.get("startTime")));
        activity.setEndTime(parseDateTime(body.get("endTime")));
        activity.setPerUserLimit(body.get("perUserLimit") == null
                ? 1 : parseInt(body.get("perUserLimit"), 1));
        activity.setDescription(str(body.get("description")));
        activityService.updateActivity(merchantId, activity);
        return Result.ok("活动更新成功");
    }

    /**
     * 删除活动（仅草稿/下线态）。
     */
    @DeleteMapping("/activities/{id}")
    public Result<?> deleteActivity(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        activityService.deleteActivity(merchantId, id);
        return Result.ok("活动已删除");
    }

    /**
     * 发布活动：校验商品、汇总总库存、进入待开始状态。
     * <p>
     * 发布后由预热任务在开抢前自动把库存刷进 Redis。
     */
    @PutMapping("/activities/{id}/publish")
    public Result<?> publishActivity(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        activityService.publishActivity(merchantId, id);
        return Result.ok("活动已发布，将在开抢前自动预热库存");
    }

    /**
     * 下线活动：立即停止抢购并清除 Redis 预热库存。
     */
    @PutMapping("/activities/{id}/offline")
    public Result<?> offlineActivity(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        activityService.offlineActivity(merchantId, id);
        return Result.ok("活动已下线，预热库存已清除");
    }

    // ==================== 商品与库存额度 ====================

    /**
     * 活动的商品列表（含预热量与余量）。
     */
    @GetMapping("/activities/{id}/stocks")
    public Result<List<SeckillStock>> listStocks(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        activityService.getOwnedActivity(merchantId, id);
        return Result.ok("秒杀商品列表", stockMapper.selectByActivityId(id));
    }

    /**
     * 新增/更新一个 SKU 的秒杀额度。
     * <p>
     * 请求体：{@code skuId}、{@code seckillPrice}、{@code totalStock}、
     * {@code perUserLimit}、{@code sort}
     * <p>
     * 服务端会去商品服务核对 SKU 真实价格与所属商家，拒绝
     * 「秒杀价 >= 日常价」以及「挂别人家商品」两种非法情况。
     */
    @PostMapping("/activities/{id}/stocks")
    public Result<SeckillStock> saveStock(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        Long merchantId = requireMerchantId(request);
        Long skuId = parseLong(body.get("skuId"));
        BigDecimal seckillPrice = parseDecimal(body.get("seckillPrice"));
        Integer totalStock = body.get("totalStock") == null ? null : parseInt(body.get("totalStock"), 0);
        Integer perUserLimit = body.get("perUserLimit") == null
                ? null : parseInt(body.get("perUserLimit"), 0);
        Integer sort = body.get("sort") == null ? 0 : parseInt(body.get("sort"), 0);
        return Result.ok("秒杀商品已保存",
                activityService.saveStock(merchantId, id, skuId, seckillPrice, totalStock,
                        perUserLimit, sort));
    }

    /**
     * 删除某个 SKU 的秒杀额度（仅草稿/下线态、未预热）。
     */
    @DeleteMapping("/stocks/{stockId}")
    public Result<?> deleteStock(HttpServletRequest request, @PathVariable Long stockId) {
        Long merchantId = requireMerchantId(request);
        activityService.deleteStock(merchantId, stockId);
        return Result.ok("秒杀商品已移除");
    }

    /**
     * 手动预热库存到 Redis（活动未开始时可用）。
     * <p>
     * 正常流程由定时任务自动预热；这个接口是给商家/运维在「活动快开始了但还没预热」
     * 时手动补一下的。已开始的活动会被拒绝 —— 重新预热会把已扣减的库存还原，直接超卖。
     */
    @PostMapping("/activities/{id}/preheat")
    public Result<?> preheat(HttpServletRequest request, @PathVariable Long id) {
        Long merchantId = requireMerchantId(request);
        SeckillActivity activity = activityService.getOwnedActivity(merchantId, id);
        if ("running".equals(activity.getStatus())) {
            throw new RuntimeException("活动已开始，不能重新预热（会把已售库存还原，导致超卖）");
        }
        int count = compensateService.preheatActivity(activity);
        return Result.ok("已预热 " + count + " 个秒杀商品");
    }

    // ==================== 私有工具 ====================

    /**
     * 解析当前登录商家的 merchantId（不可信来源一律不采纳）。
     * <p>
     * 用 {@code userId} 经 Feign 向商家服务换取，而不是读 JWT 里的某个字段：
     * merchantId 与 userId 是两张表的主键，JWT 里只有 userId。
     *
     * @throws RuntimeException 未登录或该用户不是商家
     */
    private Long requireMerchantId(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) {
            throw new RuntimeException("请先登录");
        }
        Result<MerchantInfo> r = merchantInternalClient.getByUserId(userId);
        if (r == null || r.getCode() == null || r.getCode() != 200) {
            // 商家服务不可用：区分于「不是商家」，提示重试而不是让用户以为身份丢了
            throw new RuntimeException(r == null ? "无法确认商家身份" : r.getMessage());
        }
        if (r.getData() == null || r.getData().getId() == null) {
            throw new RuntimeException("当前账号不是商家");
        }
        return r.getData().getId();
    }

    private static String str(Object val) {
        return val == null ? null : val.toString();
    }

    private static Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        String s = val.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(Object val, int defaultValue) {
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static BigDecimal parseDecimal(Object val) {
        if (val == null) return null;
        try {
            return new BigDecimal(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析时间。
     * <p>
     * 兼容 {@code 2026-11-11T00:00:00} 与 {@code 2026-11-11 00:00:00} 两种写法：
     * 前者是 ISO-8601（前端 {@code Date.toISOString()} 的产物），
     * 后者是更常见的手写/表单格式。只支持一种会让前端莫名其妙报参数错误。
     */
    private static java.time.LocalDateTime parseDateTime(Object val) {
        if (val == null) return null;
        String s = val.toString().trim().replace('T', ' ');
        // 去掉可能的毫秒与时区后缀（如 2026-11-11T00:00:00.000Z）
        int dot = s.indexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        try {
            return java.time.LocalDateTime.parse(s,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(s,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (Exception e2) {
                throw new RuntimeException("时间格式错误，应为 yyyy-MM-dd HH:mm:ss，收到: " + val);
            }
        }
    }
}

package com.shopai.seckill.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillMessage;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillMessageMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.seckill.service.SeckillActivityService;
import com.shopai.seckill.service.SeckillCompensateService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 秒杀管理端接口（对网关暴露：{@code /api/seckill/admin/**}）
 * <p>
 * 主要用途：平台运营监管秒杀活动、查看异常数据、手动触发补偿。
 * <p>
 * 权限：网关对 {@code /api/admin/**} 与本前缀都做 admin 角色校验；
 * 本类再用 {@link CurrentUser#isRole} 二次确认（与 AdminUserController 一致的做法）。
 */
@RestController
@RequestMapping("/api/seckill/admin")
public class SeckillAdminController {

    private static final Logger log = LoggerFactory.getLogger(SeckillAdminController.class);

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillRecordMapper recordMapper;

    @Autowired
    private SeckillMessageMapper messageMapper;

    @Autowired
    private SeckillActivityService activityService;

    @Autowired
    private SeckillCompensateService compensateService;

    /**
     * 全平台秒杀活动列表（可按状态、商家筛选）。
     */
    @GetMapping("/activities")
    public Result<List<SeckillActivity>> listActivities(HttpServletRequest request,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Long merchantId) {
        checkAdmin(request);
        List<SeckillActivity> list = activityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillActivity>()
                        .eq(status != null && !status.isBlank(), SeckillActivity::getStatus, status)
                        .eq(merchantId != null, SeckillActivity::getMerchantId, merchantId)
                        .orderByDesc(SeckillActivity::getCreatedAt));
        return Result.ok("秒杀活动列表", list);
    }

    /**
     * 强制下线活动（管理员权限，可下线任意商家的活动）。
     */
    @PutMapping("/activities/{id}/offline")
    public Result<?> forceOffline(HttpServletRequest request, @PathVariable Long id) {
        checkAdmin(request);
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            return Result.error("活动不存在");
        }
        // 管理员下线复用商家的下线逻辑（含清 Redis 预热），
        // 直接传活动归属的 merchantId 以通过归属校验
        activityService.offlineActivity(activity.getMerchantId(), id);
        log.warn("管理员强制下线秒杀活动, activityId={}, merchantId={}, adminUserId={}",
                id, activity.getMerchantId(), CurrentUser.getUserId(request));
        return Result.ok("活动已下线");
    }

    /**
     * 抢购记录查询（按活动 / 用户 / 状态）。
     * <p>
     * 排查「用户说抢到了却没订单」时最常用的接口：先按 userId + activityId 查到记录，
     * 看它的 status 是 pending（还没成单）还是 failed（已回补库存）。
     */
    @GetMapping("/records")
    public Result<List<SeckillRecord>> listRecords(HttpServletRequest request,
                                                  @RequestParam(required = false) Long activityId,
                                                  @RequestParam(required = false) Long userId,
                                                  @RequestParam(required = false) String status) {
        checkAdmin(request);
        List<SeckillRecord> list = recordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillRecord>()
                        .eq(activityId != null, SeckillRecord::getActivityId, activityId)
                        .eq(userId != null, SeckillRecord::getUserId, userId)
                        .eq(status != null && !status.isBlank(), SeckillRecord::getStatus, status)
                        .orderByDesc(SeckillRecord::getCreatedAt)
                        .last("LIMIT 500"));
        return Result.ok("抢购记录", list);
    }

    /**
     * 本地消息表查询（排查异步下单卡在哪一步）。
     */
    @GetMapping("/messages")
    public Result<List<SeckillMessage>> listMessages(HttpServletRequest request,
                                                    @RequestParam(required = false) String status) {
        checkAdmin(request);
        List<SeckillMessage> list = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillMessage>()
                        .eq(status != null && !status.isBlank(), SeckillMessage::getStatus, status)
                        .orderByDesc(SeckillMessage::getCreatedAt)
                        .last("LIMIT 500"));
        return Result.ok("本地消息列表", list);
    }

    /**
     * 手动触发一次补偿（投递待重试消息 + 超时记录回补）。
     * <p>
     * 用途：定时任务停摆、或运维想立刻确认数据是否收敛时手动跑一轮。
     * 两个动作都是幂等的，重复执行不会重复回补（DB 侧的
     * {@code markFailed} 带 status 条件做闸门）。
     */
    @PostMapping("/compensate")
    public Result<Map<String, Object>> compensate(HttpServletRequest request) {
        checkAdmin(request);
        int messages = compensateService.retryPendingMessages();
        int records = compensateService.restoreTimeoutRecords();
        log.warn("管理员手动触发秒杀补偿, adminUserId={}, 处理消息={}, 回补记录={}",
                CurrentUser.getUserId(request), messages, records);
        return Result.ok("补偿已执行", Map.of(
                "retriedMessages", messages,
                "restoredRecords", records));
    }

    /**
     * 管理员权限二次确认（网关已校验，这里是兜底）。
     */
    private void checkAdmin(HttpServletRequest request) {
        if (!CurrentUser.isRole(request, "admin")) {
            throw new RuntimeException("无权操作");
        }
    }
}

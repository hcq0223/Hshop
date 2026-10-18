package com.shopai.seckill.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.seckill.constant.SeckillStatus;
import com.shopai.seckill.dto.SeckillActivityVO;
import com.shopai.seckill.dto.SeckillItemVO;
import com.shopai.seckill.dto.SeckillResultVO;
import com.shopai.seckill.entity.SeckillActivity;
import com.shopai.seckill.entity.SeckillRecord;
import com.shopai.seckill.entity.SeckillStock;
import com.shopai.seckill.mapper.SeckillActivityMapper;
import com.shopai.seckill.mapper.SeckillRecordMapper;
import com.shopai.seckill.mapper.SeckillStockMapper;
import com.shopai.seckill.service.SeckillBuyService;
import com.shopai.seckill.service.SeckillInfoService;
import com.shopai.seckill.service.SeckillRedisService;
import com.shopai.seckill.feign.CustomerInternalClient;
import com.shopai.common.dto.internal.CustomerIdPair;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 秒杀买家端接口（对网关暴露：{@code /api/seckill/**}）
 * <p>
 * 限流说明：抢购接口 {@code POST /api/seckill/buy} 用
 * {@code @SentinelResource} 声明了资源名 {@code seckillBuy}，
 * 需要在 Sentinel 控制台为它配置 QPS 限流规则（建议持久化到 Nacos）。
 * <p>
 * 限流的定位要清楚：<b>Sentinel 限流是保护服务的，不是防超卖的</b>。
 * 真正的防超卖靠 Redis Lua 原子扣减。限流只是让超出的流量快速失败，
 * 不至于把 Tomcat 线程池打满导致整个服务不可用。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private static final Logger log = LoggerFactory.getLogger(SeckillController.class);

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private SeckillRecordMapper recordMapper;

    @Autowired
    private SeckillStockMapper stockMapper;

    @Autowired
    private SeckillInfoService infoService;

    @Autowired
    private SeckillRedisService redisService;

    @Autowired
    private SeckillBuyService buyService;

    @Autowired
    private CustomerInternalClient customerInternalClient;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 进行中 / 即将开始的秒杀活动列表（公开）。
     * <p>
     * 只返回 pending 与 running 的活动。不返回草稿（还没发布）与
     * 已结束/已下线（用户点进去也抢不了，只会造成困扰）。
     * <p>
     * <b>注意这里也做了 Redis 余量覆盖</b>：列表页的「已抢 X%」进度条
     * 就是按 availableStock 算的，不覆盖的话活动进行中会一直显示 0%，
     * 与实际卖出情况严重脱节。
     */
    @GetMapping("/list")
    public Result<List<SeckillActivityVO>> list(HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillActivity> activities = activityMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillActivity>()
                        .in(SeckillActivity::getStatus, SeckillStatus.PENDING, SeckillStatus.RUNNING)
                        .gt(SeckillActivity::getEndTime, now)
                        .orderByAsc(SeckillActivity::getStartTime));

        // 列表页同样标注「我是否已买过」：登录用户看到买过的商品会被标记，未登录传 null
        Long userId = CurrentUser.getUserId(request);
        List<SeckillActivityVO> list = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            SeckillActivityVO vo = infoService.buildActivityVO(activity, userId);
            overlayRealtimeStock(vo, userId);
            list.add(vo);
        }
        return Result.ok("秒杀活动列表", list);
    }

    /**
     * 活动详情（公开，登录后可看到「我是否已抢过」）。
     *
     * @param id 活动ID
     */
    @GetMapping("/detail/{id}")
    public Result<SeckillActivityVO> detail(HttpServletRequest request, @PathVariable Long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            return Result.error("活动不存在");
        }
        Long userId = CurrentUser.getUserId(request);
        SeckillActivityVO vo = infoService.buildActivityVO(activity, userId);
        overlayRealtimeStock(vo, userId);
        return Result.ok("活动详情", vo);
    }

    /**
     * 用 Redis 的真实余量覆盖 VO 里的 DB 值（列表与详情共用）。
     * <p>
     * <b>为什么必须覆盖</b>：DB 的 {@code available_stock} 只在「订单创建成功」后
     * 由 {@code confirmSold} 异步更新，而抢购时的扣减发生在 Redis ——
     * 活动进行中两者会差出一截（尤其是抢得正热的时候）。
     * 用户看到的「还剩几件」「已抢 X%」必须基于实时值，
     * 否则会出现「显示还剩 80 件，实际早抢光了」的严重误导。
     * <p>
     * 另外两件事也在这里做：
     * <ul>
     *   <li>{@code stockSource} 标记数据来源，便于排查「显示有货却抢不到」；</li>
     *   <li>登录用户标注「该商品我是否已买过」（按 SKU 维度，买过 A 不影响 B）。</li>
     * </ul>
     *
     * @param vo     活动视图（会被就地修改）
     * @param userId 当前用户ID；未登录传 null（此时不查询「已买过」）
     */
    private void overlayRealtimeStock(SeckillActivityVO vo, Long userId) {
        if (vo == null || vo.getItems() == null) {
            return;
        }
        for (SeckillItemVO item : vo.getItems()) {
            Integer redisStock = redisService.getStock(vo.getId(), item.getStockId());
            if (redisStock != null) {
                item.setAvailableStock(redisStock);
                item.setStockSource("redis");
                item.setSoldOut(redisStock <= 0);
            }
            if (userId != null) {
                item.setBoughtByMe(redisService.hasBought(vo.getId(), item.getSkuId(), userId));
            }
        }
    }

    /**
     * 抢购（核心接口，需登录）。
     * <p>
     * 快速返回：只做完 Redis 扣减与本地消息落库就返回「排队中」，
     * 真实订单由 MQ 异步创建，前端轮询 {@code /result/{activityId}} 拿结果。
     * 这样接口耗时与订单服务解耦，秒杀时不会因为下游慢而堆积线程。
     *
     * @param body 请求体：{@code stockId}、{@code quantity}、{@code addressId}
     */
    @PostMapping("/buy")
    public Result<SeckillResultVO> buy(HttpServletRequest request,
                                       @RequestBody Map<String, Object> body) {
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        Long stockId = parseLong(body.get("stockId"));
        Long addressId = parseLong(body.get("addressId"));
        int quantity = body.get("quantity") == null ? 1 : parseInt(body.get("quantity"), 1);
        if (stockId == null) {
            return Result.error("stockId 不能为空");
        }
        if (quantity <= 0) {
            return Result.error("购买数量必须大于 0");
        }
        // 收货地址是下单必需项，必须在抢购入口就拦住。
        // 不拦的后果：库存会被扣掉、消息也会投出去，直到异步建单时才发现缺地址，
        // 接着重试耗尽 → 回补库存 → 用户最终看到「下单失败」，
        // 等于白扣一次库存、还占用了重试资源。提前校验用户立刻就能得到明确提示。
        if (addressId == null) {
            return Result.error("请选择收货地址后再抢购");
        }

        // 反查活动：不让前端直接传 activityId，避免「传 A 活动的 stockId 配 B 活动的 id」
        // 这类不一致组合绕过按活动维度的限购判定。
        SeckillStock stock = stockMapper.selectById(stockId);
        if (stock == null) {
            return Result.error("秒杀商品不存在");
        }
        Long activityId = stock.getActivityId();

        // 解析 customerId：MQ 消费时没有请求上下文，身份必须在此刻解析好随消息带走。
        // 解析不出来也放行（传 null），由超时重试任务再试 —— 不因为用户服务抖动
        // 就把一个已经扣到库存的用户判失败。
        Long customerId = resolveCustomerId(userId);

        SeckillResultVO vo = buyService.buy(activityId, stockId, userId, customerId, quantity, addressId);
        if ("failed".equals(vo.getStatus())) {
            // 抢购失败是业务结果而非系统错误，仍返回 200 + status=failed，
            // 前端根据 status 展示原因（区分「抢完了」与「系统繁忙」）
            return Result.ok(vo.getMessage(), vo);
        }
        return Result.ok("抢购成功，正在生成订单", vo);
    }

    /**
     * 查询抢购结果（前端轮询，需登录）。
     * <p>
     * 先查 Redis（快路径），没有则回源数据库 —— 结果缓存的 TTL 是 30 分钟，
     * 用户隔一段时间再回来看时缓存已过期，此时必须能查到持久化的记录。
     *
     * @param activityId 活动ID
     */
    @GetMapping("/result/{activityId}")
    public Result<SeckillResultVO> queryResult(HttpServletRequest request,
                                              @PathVariable Long activityId) {
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        String cached = redisService.getResult(activityId, userId);
        if (cached != null) {
            try {
                // 用注入的 ObjectMapper，而不是 new 一个：
                // 注入的是 Spring Boot 按 application.yml 配置好的那个
                // （日期格式 yyyy-MM-dd HH:mm:ss、时区 Asia/Shanghai），
                // 而 new ObjectMapper() 是全默认配置，两边格式不一致会在
                // 反序列化 LocalDateTime 时直接抛异常。
                SeckillResultVO vo = objectMapper.readValue(cached, SeckillResultVO.class);
                vo.setServerTime(LocalDateTime.now());
                return Result.ok(vo);
            } catch (Exception e) {
                // 缓存反序列化失败就回源数据库，不影响用户拿到结果
                log.warn("抢购结果缓存解析失败，回源数据库, activityId={}, userId={}", activityId, userId, e);
            }
        }
        return Result.ok(buildResultFromDb(activityId, userId));
    }

    /**
     * 我的秒杀记录（需登录）。
     */
    @GetMapping("/my")
    public Result<List<SeckillRecord>> myRecords(HttpServletRequest request) {
        Long userId = CurrentUser.getUserId(request);
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        List<SeckillRecord> records = recordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillRecord>()
                        .eq(SeckillRecord::getUserId, userId)
                        .orderByDesc(SeckillRecord::getCreatedAt));
        return Result.ok("我的秒杀记录", records);
    }

    // ==================== 私有工具 ====================

    /**
     * 从数据库记录组装结果（Redis 缓存未命中时的回源路径）。
     */
    private SeckillResultVO buildResultFromDb(Long activityId, Long userId) {
        SeckillRecord record = recordMapper.selectByActivityAndUser(activityId, userId);
        SeckillResultVO vo = new SeckillResultVO();
        vo.setActivityId(activityId);
        vo.setServerTime(LocalDateTime.now());
        if (record == null) {
            // 没有记录 = 这个用户在本次活动里没抢到过
            vo.setStatus("failed");
            vo.setMessage("您未参与本活动或未抢到");
            return vo;
        }
        vo.setRecordId(record.getId());
        vo.setQuantity(record.getQuantity());
        vo.setSeckillPrice(record.getSeckillPrice());
        vo.setStatus(record.getStatus());
        vo.setOrderId(record.getOrderId());
        vo.setOrderNo(record.getOrderNo());
        if ("success".equals(record.getStatus())) {
            vo.setMessage("下单成功，请尽快完成支付");
        } else if ("pending".equals(record.getStatus())) {
            vo.setMessage("抢购成功，正在生成订单");
        } else {
            vo.setMessage(record.getFailReason() == null ? "抢购失败" : record.getFailReason());
        }
        return vo;
    }

    /**
     * 解析 customerId（userId → customers.id）。
     *
     * @return 客户ID；解析不出返回 null（不阻断抢购，由重试任务补）
     */
    private Long resolveCustomerId(Long userId) {
        try {
            var r = customerInternalClient.listIdsByUserIds(String.valueOf(userId));
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (CustomerIdPair pair : r.getData()) {
                    if (pair.getCustomerId() != null) {
                        return pair.getCustomerId();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 customerId 失败（不阻断抢购，稍后重试）, userId={}", userId, e);
        }
        return null;
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
}

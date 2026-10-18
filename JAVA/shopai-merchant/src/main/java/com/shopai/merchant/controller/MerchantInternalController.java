package com.shopai.merchant.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopai.common.Result;
import com.shopai.common.constant.InternalApiPaths;
import com.shopai.common.dto.internal.MerchantFunds;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.MerchantIndexItem;
import com.shopai.common.dto.internal.index.ReviewIndexItem;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.common.dto.internal.user.UserIdItem;
import com.shopai.merchant.entity.Merchant;
import com.shopai.merchant.mapper.MerchantMapper;
import com.shopai.merchant.service.MerchantIndexAssembler;
import com.shopai.merchant.service.MerchantIndexService;
import com.shopai.merchant.service.MerchantService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 商家服务内部接口（供其他服务通过 Feign 调用，不对网关暴露）
 * <p>
 * 微服务拆分说明：{@code merchants} 表归属 shopai-merchant 服务。按「独立库」处理跨服务访问后，
 * 原先其他服务对 merchants 表的直查/直写全部改为调用本控制器：
 * <p>
 * 1. shopai-order（原 MerchantRefMapper / OrderMapper.xml 的 JOIN merchants）：
 *    - userId → merchantId 身份映射 → {@code GET /by-user}
 *    - 订单店铺名/Logo 展示 → {@code GET /briefs}
 *    - 资金读改写（8 处营收/余额/冻结变更）→ {@code POST /funds/change}（原子累加）
 * 2. shopai-product（原 MerchantRefMapper / ProductMapper.xml 的 JOIN merchants）：
 *    - 新增商品解析 merchantId → {@code GET /by-user}
 *    - 商品/索引的店铺名/Logo → {@code GET /briefs}
 * 3. shopai-user（原 MerchantRefMapper / DashboardMapper.xml 的 JOIN merchants）：
 *    - 登录判断是否已入驻 → {@code GET /by-user}
 *    - 后台用户列表按店铺名搜索 → {@code GET /user-ids-by-shop-name}
 *    - 仪表盘商家销售额排名补店铺名 → {@code GET /briefs}
 * 4. shopai-search（原 MerchantRefMapper.selectAllForIndex / selectOneForIndex）：
 *    - 商家索引全量重建 / 增量同步 → {@code GET /index/merchants}、{@code GET /index/merchant}
 * <p>
 * 安全说明：{@code /api/merchant/internal/**} 不应被网关对外暴露，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping(InternalApiPaths.MERCHANT_INTERNAL)
@RequiredArgsConstructor
public class MerchantInternalController {

    private static final Logger log = LoggerFactory.getLogger(MerchantInternalController.class);

    private final MerchantMapper merchantMapper;
    private final MerchantService merchantService;
    private final MerchantIndexService merchantIndexService;
    // 列表查询改造新增：评价索引视图组装（含评价人/商品/店铺冗余字段）
    private final MerchantIndexAssembler merchantIndexAssembler;

    // ==================== 身份映射（order / product / user 共用） ====================

    /**
     * 按 userId 查询商家（替代各服务对 merchants 表的直查）
     *
     * @param userId 用户ID（来自网关 X-User-Id 头）
     * @return 商家信息；该用户尚未入驻时 data 为 null
     */
    @GetMapping("/by-user")
    public Result<MerchantInfo> getByUserId(@RequestParam("userId") Long userId) {
        return Result.ok(toMerchantInfo(merchantMapper.selectByUserId(userId)));
    }

    /**
     * 按商家ID查询商家
     *
     * @param id 商家ID
     * @return 商家信息；不存在时 data 为 null
     */
    @GetMapping("/{id}")
    public Result<MerchantInfo> getById(@PathVariable("id") Long id) {
        return Result.ok(toMerchantInfo(merchantMapper.selectById(id)));
    }

    // ==================== 批量店铺简要信息（替代跨库 JOIN 取店铺名） ====================

    /**
     * 按商家ID批量查询店铺简要信息（店铺名/Logo/评分/认证状态）
     * <p>
     * 替代以下跨库 JOIN：
     * - OrderMapper.xml 的 {@code LEFT JOIN merchants}（订单/订单项店铺名）
     * - ProductMapper.xml 的 {@code JOIN merchants}（商品/热销榜店铺名）
     * - DashboardMapper.xml 的 {@code INNER JOIN merchants}（销售额排名店铺名）
     * - ProductRefMapper/MerchantRefMapper 的索引店铺名
     *
     * @param ids 逗号分隔的商家ID列表，如 "1,2,3"
     * @return 店铺简要信息列表（不存在的ID自动跳过）
     */
    @GetMapping("/briefs")
    public Result<List<MerchantBrief>> listBriefs(@RequestParam("ids") String ids) {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        List<Merchant> merchants = merchantMapper.selectBatchIds(idList);
        List<MerchantBrief> result = new ArrayList<>(merchants.size());
        for (Merchant m : merchants) {
            MerchantBrief brief = new MerchantBrief();
            brief.setId(m.getId());
            brief.setShopName(m.getShopName());
            brief.setLogoUrl(m.getLogoUrl());
            // 评分与认证状态：原各服务 JOIN merchants 时取 merchants.rating / is_verified
            brief.setRating(m.getRating());
            brief.setIsVerified(m.getIsVerified());
            result.add(brief);
        }
        return Result.ok(result);
    }

    /**
     * 按店铺名关键字查询命中的 userId 集合
     * <p>
     * 原调用方：用户服务后台用户列表搜索——原 SQL 为
     * {@code users LEFT JOIN merchants m ... OR m.shop_name LIKE %}。
     * merchants 表归属本服务，因此在本服务内完成匹配，
     * 把「匹配到的 userId 集合」回传给用户服务再查 users 表，从而去掉跨库 JOIN。
     *
     * @param keyword 店铺名关键字
     * @param limit   最多返回条数（防止集合过大）
     * @return 匹配的 userId 列表
     */
    @GetMapping("/user-ids-by-shop-name")
    public Result<List<UserIdItem>> userIdsByShopName(@RequestParam("keyword") String keyword,
                                                      @RequestParam(value = "limit", defaultValue = "1000") int limit) {
        LambdaQueryWrapper<Merchant> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(Merchant::getShopName, keyword)
                .orderByDesc(Merchant::getId)
                .last("LIMIT " + Math.max(1, limit));
        List<Merchant> merchants = merchantMapper.selectList(wrapper);
        List<UserIdItem> result = new ArrayList<>(merchants.size());
        for (Merchant m : merchants) {
            if (m.getUserId() == null) {
                continue;
            }
            UserIdItem item = new UserIdItem();
            item.setId(String.valueOf(m.getUserId()));
            result.add(item);
        }
        return Result.ok(result);
    }

    // ==================== 资金（替代订单服务的跨库读改写） ====================

    /**
     * 查询商家资金（只读，供订单服务前置校验）
     *
     * @param merchantId 商家ID
     * @return 资金信息；商家不存在时 data 为 null
     */
    @GetMapping("/funds")
    public Result<MerchantFunds> getFunds(@RequestParam("merchantId") Long merchantId) {
        Merchant m = merchantMapper.selectById(merchantId);
        if (m == null) {
            return Result.ok((MerchantFunds) null);
        }
        MerchantFunds funds = new MerchantFunds();
        funds.setMerchantId(m.getId());
        funds.setTotalRevenue(nvl(m.getTotalRevenue()));
        funds.setAvailableBalance(nvl(m.getAvailableBalance()));
        funds.setFrozenBalance(nvl(m.getFrozenBalance()));
        return Result.ok(funds);
    }

    /**
     * 原子变更商家资金（替代订单服务原 {@code selectById + updateById} 的跨库读改写）
     * <p>
     * 语义：各 Delta 字段为「增量」，null 表示本次不变动该字段，由本服务在同一本地事务内累加。
     * 相比原来的「读-改-写」，既消除了跨库写，也避免了并发下的资金覆盖问题。
     * <p>
     * 调用场景（订单服务）：
     * - 支付成功：revenue +payAmount, available +payAmount
     * - 申请退款：frozen +payAmount, available -payAmount
     * - 同意退款：frozen -payAmount, revenue -refund, available +payAmount-refund
     * - 拒绝退款：frozen -payAmount, available +payAmount
     * <p>
     * <b>实现说明</b>：本接口**委托 {@link MerchantService#changeFunds}** 执行，
     * 而不是自己读改写。资金字段是商家详情缓存的一部分，
     * 只更新数据库会让「财务中心」/店铺资料页最多 300 秒（TTL）仍显示旧余额 ——
     * Service 方法内已包含清缓存逻辑，委托调用可从根上避免漏步骤。
     *
     * @param request 资金变动请求（merchantId 必填）
     * @return 通用 Result
     */
    @PostMapping("/funds/change")
    public Result<?> changeFunds(@RequestBody MerchantFundsChangeRequest request) {
        if (request == null || request.getMerchantId() == null) {
            return Result.error("merchantId 不能为空");
        }
        boolean ok = merchantService.changeFunds(request);
        if (!ok) {
            return Result.error("商家不存在，merchantId=" + request.getMerchantId());
        }
        return Result.ok("资金变更成功");
    }

    // ==================== 搜索索引源数据 ====================

    /**
     * 全量商家索引条目（搜索服务全量重建 merchant 索引用）
     * <p>
     * 原调用方：搜索服务 MerchantRefMapper.selectAllForIndex
     * （跨库直查 merchants 并 {@code INNER JOIN users} 取创建时间、按账号状态过滤）。
     * 拆分后由本服务在本地查询，账号状态改由 Feign 调用户服务校验。
     */
    @GetMapping("/index/merchants")
    public Result<List<MerchantIndexItem>> indexAllMerchants() {
        return Result.ok(merchantIndexService.buildAll());
    }

    /**
     * 按商家ID批量获取索引条目（搜索服务 MQ 增量同步用）
     * <p>
     * 原调用方：搜索服务 MerchantRefMapper.selectOneForIndex。
     *
     * @param ids 逗号分隔的商家ID列表
     */
    @GetMapping("/index/merchant")
    public Result<List<MerchantIndexItem>> indexMerchants(@RequestParam(value = "ids", required = false) String ids) {
        return Result.ok(merchantIndexService.buildByIds(parseIds(ids)));
    }

    // ==================== 列表查询改造：评价索引回源 ====================

    /**
     * 取单个评价的索引视图（搜索服务消费 review.changed 后回源调用）
     * <p>
     * 微服务拆分说明（列表查询改造）：评价列表改由 ES 承载后，索引文档需要
     * 「评价人昵称/头像 + 商品名 + 店铺名」等冗余字段，只有商家服务能组装
     * （需调用户服务与商品服务内部接口），故由本接口回源提供给搜索服务。
     *
     * @param reviewId 评价ID
     * @return 索引视图；评价不存在时 data 为 null
     */
    @GetMapping("/index/review/{reviewId}")
    public Result<ReviewIndexItem> indexReview(@PathVariable("reviewId") Long reviewId) {
        return Result.ok(merchantIndexAssembler.assembleReview(reviewId));
    }

    /**
     * 取全量评价索引视图（review 索引全量重建用）
     */
    @GetMapping("/index/reviews")
    public Result<List<ReviewIndexItem>> indexAllReviews() {
        return Result.ok(merchantIndexAssembler.assembleAllReviews());
    }

    // ==================== 辅助方法 ====================

    /** Merchant 实体 → 跨服务 MerchantInfo DTO */
    private MerchantInfo toMerchantInfo(Merchant m) {
        if (m == null) {
            return null;
        }
        MerchantInfo info = new MerchantInfo();
        info.setId(m.getId());
        info.setUserId(m.getUserId());
        info.setShopName(m.getShopName());
        info.setLogoUrl(m.getLogoUrl());
        info.setRating(m.getRating());
        info.setIsVerified(m.getIsVerified());
        return info;
    }

    /** null 安全的 BigDecimal 取值 */
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 解析逗号分隔的ID串，忽略非法片段 */
    private static List<Long> parseIds(String ids) {
        List<Long> result = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            return result;
        }
        for (String part : ids.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.valueOf(s));
            } catch (NumberFormatException ignored) {
                // 忽略非法ID片段
            }
        }
        return result;
    }
}

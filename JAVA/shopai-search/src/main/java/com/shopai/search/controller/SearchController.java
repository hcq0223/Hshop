package com.shopai.search.controller;

import com.shopai.common.Result;
import com.shopai.common.security.CurrentUser;
import com.shopai.common.security.JwtUtil;
import com.shopai.search.document.MerchantDocument;
import com.shopai.search.document.ProductDocument;
import com.shopai.search.service.ListIndexSyncService;
import com.shopai.search.service.SearchQueryService;
import com.shopai.search.service.SearchSyncService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索控制器（Elasticsearch）
 * <p>
 * 微服务拆分说明：原单体的商品 LIKE 搜索（/api/product/search、/api/product/merchant/{id}/search）
 * 与商家 LIKE 搜索（/api/user/customer/searchMerchants）统一迁至本服务，路径规范化（见设计文档 5.5）：
 * - 旧 /api/product/search                  → 新 /api/search/product                    (GET)
 * - 旧 /api/product/merchant/{id}/search     → 新 /api/search/product/merchant/{id}      (GET)
 * - 旧 /api/user/customer/searchMerchants    → 新 /api/search/merchant                   (GET)
 * - （新增）POST /api/search/rebuild        → 全量重建全部列表索引                       (POST, 仅管理员)
 * <p>
 * 列表查询改造：本服务同时成为各业务服务「分页列表」的数据来源
 * （商品/商家/订单/评价/用户五类索引），列表查询走 {@code /api/search/internal/list}
 * （内部接口，不对网关暴露），本控制器的 {@code /api/search/**} 仍是对外的搜索入口。
 * <p>
 * 鉴权：搜索接口均为公开（网关白名单 /api/search/**）；全量重建为管理员专属（网关校验 admin，
 * 本控制器再读 X-User-Role 兜底）；{@code /internal/**} 由网关拦截，仅服务间 Feign 直连。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchQueryService searchQueryService;
    private final SearchSyncService searchSyncService;
    // 列表查询改造新增：订单/评价/用户三类索引的同步与重建
    private final ListIndexSyncService listIndexSyncService;
    // 仅用于「直连本服务」时的兜底鉴权（常规链路鉴权在网关完成）
    private final JwtUtil jwtUtil;

    /**
     * 前台商品搜索（支持关键词、分类、价格区间）
     * 旧路径：/api/product/search → 新路径：/api/search/product
     */
    @GetMapping("/product")
    public Result<?> searchProducts(@RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "10") Integer size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer categoryId,
                                    @RequestParam(required = false) String categoryIds,
                                    @RequestParam(required = false) BigDecimal minPrice,
                                    @RequestParam(required = false) BigDecimal maxPrice) {
        // 将逗号分隔的 categoryIds 字符串转为 List<Integer>（沿用原单体参数格式）
        List<Integer> categoryIdList = parseCategoryIds(categoryIds);
        return Result.ok(searchQueryService.searchProducts(keyword, categoryId, categoryIdList,
                minPrice, maxPrice, page, size));
    }

    /**
     * 前台商品「分面搜索」：复合搜索 + 分类桶聚合 + post_filter 后置过滤
     * <p>
     * 新路径：GET /api/search/product/facets（shopai-search 新增，公开）
     * <p>
     * 与 {@link #searchProducts} 的区别：本接口额外返回分类分面桶（各分类命中数）。
     * 主查询（关键词+价格+在售）决定聚合范围，选中分类走 post_filter，只过滤结果、不影响桶计数，
     * 因此前端选中某分类后，侧栏各分类计数仍为全量分布（标准电商分面检索）。
     * 分类名由前端用 /api/category/all 的本地映射表补全。
     */
    @GetMapping("/product/facets")
    public Result<?> searchProductFacets(@RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer categoryId,
                                         @RequestParam(required = false) String categoryIds,
                                         @RequestParam(required = false) BigDecimal minPrice,
                                         @RequestParam(required = false) BigDecimal maxPrice) {
        List<Integer> categoryIdList = parseCategoryIds(categoryIds);
        return Result.ok(searchQueryService.searchProductFacets(keyword, categoryId, categoryIdList,
                minPrice, maxPrice, page, size));
    }

    /**
     * 将逗号分隔的 categoryIds 字符串解析为 List&lt;Integer&gt;（空则返回 null）
     */
    private List<Integer> parseCategoryIds(String categoryIds) {
        if (categoryIds == null || categoryIds.trim().isEmpty()) {
            return null;
        }
        return java.util.Arrays.stream(categoryIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::valueOf)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 店铺内商品搜索（限定某商户范围）
     * 旧路径：/api/product/merchant/{merchantId}/search → 新路径：/api/search/product/merchant/{merchantId}
     */
    @GetMapping("/product/merchant/{merchantId}")
    public Result<?> searchProductsInMerchant(@PathVariable Long merchantId,
                                              @RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "10") Integer size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer categoryId) {
        return Result.ok(searchQueryService.searchProductsInMerchant(merchantId, keyword,
                categoryId, page, size));
    }

    /**
     * 商家搜索（关键词匹配店铺名/简介）
     * 旧路径：/api/user/customer/searchMerchants → 新路径：/api/search/merchant
     */
    @GetMapping("/merchant")
    public Result<?> searchMerchants(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "10") Integer size,
                                     @RequestParam(required = false) String keyword) {
        return Result.ok(searchQueryService.searchMerchants(keyword, page, size));
    }

    /**
     * 全量重建全部列表索引（product / merchant / order / review / user，仅管理员）
     * <p>
     * 说明：作为 MQ 增量同步的最终一致性兜底。删旧索引 → 全量拉取源数据写入。
     * <p>
     * 列表查询改造说明：改造后业务服务的分页列表全部由 ES 索引承载
     * （见 {@link com.shopai.search.service.IndexListQueryService}），
     * 因此本接口从「重建商品+商家两个索引」扩展为「重建全部五个索引」——
     * 这是 MQ 消息丢失或索引结构变更后恢复列表可用的唯一手段，必须覆盖全部索引。
     */
    @PostMapping("/rebuild")
    public Result<?> rebuild(HttpServletRequest request) {
        // 管理员权限校验。
        // 正常链路：走网关 8080 → JwtAuthGlobalFilter 解析 JWT 并注入 X-User-Role → 此处读头放行。
        // 兜底：直连本服务（如 8085）时没有网关注入的头，此时若带了 Authorization
        //      （JwtUtil 生成的 token 自带 "Bearer " 前缀）则本服务自行验签取角色，
        //      避免「明明是同一个人、带对了 token，却因为绕开网关而报无权」的误判。
        if (!CurrentUser.isRole(request, "admin") && !isAdminByToken(request)) {
            return Result.error(403, "无权操作：本接口仅管理员可用。"
                    + "请经网关调用（http://localhost:8080/api/search/rebuild）并携带 Authorization 头；"
                    + "直连本服务时也会校验该 Token 的角色。");
        }
        try {
            // 1. 商品 + 商家（既有实现）
            int[] pm = searchSyncService.rebuildAll();
            // 2. 订单 + 评价 + 用户（列表查询改造新增）
            int[] oru = listIndexSyncService.rebuildAll();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("productCount", pm[0]);
            data.put("merchantCount", pm[1]);
            data.put("orderCount", oru[0]);
            data.put("reviewCount", oru[1]);
            data.put("userCount", oru[2]);
            log.info("管理员触发全量重建索引：商品 {} 条，商家 {} 条，订单 {} 条，评价 {} 条，用户 {} 条",
                    pm[0], pm[1], oru[0], oru[1], oru[2]);
            return Result.ok("索引重建完成", data);
        } catch (Exception e) {
            log.error("全量重建索引失败", e);
            return Result.error("索引重建失败：" + e.getMessage());
        }
    }

    /**
     * 直连本服务时的兜底鉴权：从 Authorization 头解析 JWT 并判断角色是否为 admin
     * <p>
     * 说明：业务服务的标准做法是「只读网关注入的 X-User-* 头，不自行验签」。
     * 本方法是为「运维/排障时直连某服务」这一场景提供的便利，不算入常规鉴权链路：
     * 若 X-User-Role 头已存在，则以头为准（网关链路优先）。
     *
     * @param request HTTP 请求
     * @return true 表示该 Token 的角色是 admin
     */
    private boolean isAdminByToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            // JwtUtil 内部会处理 "Bearer " 前缀，并校验签名与过期时间
            return "admin".equalsIgnoreCase(jwtUtil.getRole(token));
        } catch (Exception e) {
            log.debug("直连重建接口时 Token 解析失败: {}", e.getMessage());
            return false;
        }
    }
}

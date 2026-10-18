package com.shopai.order.service;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantInfo;
import com.shopai.common.dto.internal.merchant.MerchantBrief;
import com.shopai.order.entity.MerchantRef;
import com.shopai.order.entity.Order;
import com.shopai.order.entity.OrderItem;
import com.shopai.order.feign.MerchantInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单展示信息补齐服务（跨服务数据装配）
 * <p>
 * 微服务拆分说明：订单查询原先在 {@code OrderMapper.xml} 中通过
 * {@code JOIN merchants} 直接取店铺名/Logo，按「独立库」处理后 merchants 表归属
 * shopai-merchant 服务，订单服务不能跨库联查，改为本服务在查询结果上做「后置补齐」：
 * - 收集订单/订单项涉及的 merchantId；
 * - 一次批量 Feign 调用商家服务内部接口取店铺简要信息；
 * - 填充到 {@code Order.merchant} 与 {@code OrderItem.merchant}
 *   （前端 OrderDetail.vue / OrderHistory.vue 读取 {@code item.merchant.shopName/logoUrl}）。
 * <p>
 * 降级策略：商家服务不可用时静默跳过（店铺字段为空），订单本身信息照常返回，
 * 避免跨服务雪崩。
 */
@Service
public class OrderEnrichService {

    private static final Logger log = LoggerFactory.getLogger(OrderEnrichService.class);

    @Autowired
    private MerchantInternalClient merchantInternalClient;

    /**
     * 为单个订单补齐店铺信息（订单级 + 订单项级）
     */
    public void enrich(Order order) {
        if (order == null) {
            return;
        }
        enrichBatch(java.util.Collections.singletonList(order));
    }

    /**
     * 为一批订单补齐店铺信息（订单级 + 订单项级）
     * <p>
     * 一次 Feign 调用处理所有 merchantId，避免逐条调用造成 N+1。
     */
    public void enrichBatch(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        // 1. 收集所有涉及到的 merchantId（订单所属商家 + 订单项所属商家）
        //    说明：订单项的商家等同于订单商家（下单时按商家分组），但仍统一收集以防数据异常。
        List<Long> merchantIds = new ArrayList<>();
        for (Order o : orders) {
            if (o.getMerchantId() != null) {
                merchantIds.add(o.getMerchantId());
            }
        }
        merchantIds = merchantIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return;
        }

        // 2. 一次批量 Feign 调用
        Map<Long, MerchantBrief> shopMap = loadShopBriefs(merchantIds);

        // 3. 回填到订单与订单项
        for (Order o : orders) {
            MerchantBrief brief = o.getMerchantId() == null ? null : shopMap.get(o.getMerchantId());
            if (brief == null) {
                continue;
            }
            o.setMerchant(toMerchantRef(brief));
            if (o.getOrderItems() != null) {
                for (OrderItem item : o.getOrderItems()) {
                    // 订单项展示的店铺即订单所属店铺（与前端 OrderDetail/OrderHistory 的展示语义一致）
                    item.setMerchant(toMerchantRef(brief));
                }
            }
        }
    }

    /**
     * 调用商家服务内部接口批量查询店铺简要信息
     * <p>
     * 降级：异常或非 200 返回空 Map。
     */
    private Map<Long, MerchantBrief> loadShopBriefs(Collection<Long> merchantIds) {
        Map<Long, MerchantBrief> map = new HashMap<>();
        String ids = merchantIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<MerchantBrief>> r = merchantInternalClient.listBriefs(ids);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                for (MerchantBrief b : r.getData()) {
                    if (b.getId() != null) {
                        map.put(b.getId(), b);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取店铺信息失败（订单将缺少店铺字段），merchantIds={}", ids, e);
        }
        return map;
    }

    /** MerchantBrief（跨服务 DTO）→ 订单服务内的 MerchantRef（展示用） */
    private MerchantRef toMerchantRef(MerchantBrief brief) {
        MerchantRef ref = new MerchantRef();
        ref.setId(brief.getId());
        ref.setShopName(brief.getShopName());
        ref.setLogoUrl(brief.getLogoUrl());
        ref.setIsVerified(brief.getIsVerified());
        return ref;
    }

    /**
     * 把网关下发的 userId 解析为 merchantId（替代原 MerchantRefMapper.selectByUserId）
     * <p>
     * microservice 说明：merchants 表归属商家服务，改为 Feign 调用。
     * 降级：商家服务不可用或该用户未入驻时返回 null。
     *
     * @param userId 用户ID（来自网关 X-User-Id 头）
     */
    public Long resolveMerchantIdByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<MerchantInfo> r = merchantInternalClient.getByUserId(userId);
            if (r != null && r.getCode() != null && r.getCode() == 200 && r.getData() != null) {
                return r.getData().getId();
            }
        } catch (Exception e) {
            log.error("调用商家服务解析 merchantId 失败, userId={}", userId, e);
        }
        return null;
    }
}

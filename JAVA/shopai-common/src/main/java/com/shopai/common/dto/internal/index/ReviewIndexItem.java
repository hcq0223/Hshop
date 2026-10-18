package com.shopai.common.dto.internal.index;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 评价索引视图 DTO（跨服务契约）
 * <p>
 * 微服务拆分说明：列表查询改造后，评价列表（商品评价页 / 顾客我的评价 / 后台评价管理）
 * 不再放 Redis 缓存，改由 shopai-search 的 {@code review} ES 索引承载。
 * 商家服务在评价增删后用 Feign 内部接口把本视图回源给搜索服务。
 * <p>
 * 字段设计说明：
 * - 冗余 {@code customerNickname / customerAvatar}、{@code productName}、{@code merchantName}，
 *   让搜索服务直接可查可展示（这三项在归属方通过用户/商品服务的内部接口装配）；
 * - 商品评价列表原本还要返回「平均分 + 星级分布」，属聚合统计，继续由商家服务本地
 *   `COUNT/GROUP BY` 提供（数据量小且规则稳定），不放进 ES。
 */
@Data
public class ReviewIndexItem implements Serializable {

    /** 评价ID */
    private String id;

    private String orderId;

    private String customerId;

    /** 评价人昵称（冗余，来自用户服务） */
    private String customerNickname;

    /** 评价人头像（冗余，来自用户服务） */
    private String customerAvatar;

    private String productId;

    /** 商品名称（冗余，来自商品服务） */
    private String productName;

    private String merchantId;

    /** 店铺名称（冗余，本服务内即可取到） */
    private String merchantName;

    /** 1-5 星 */
    private Integer rating;

    private String content;

    /** 评价图片列表 */
    private List<String> images;

    private java.time.LocalDateTime createTime;
}

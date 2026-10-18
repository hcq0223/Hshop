package com.shopai.merchant.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价索引文档视图（商家服务侧读取用）
 * <p>
 * 微服务拆分说明（列表查询改造）：评价列表改由 ES 承载后，搜索服务把命中记录
 * 以 JSON 返回，商家服务按 ES 文档结构反序列化。本类即该结构的镜像。
 * <p>
 * 注意：评价接口对外的响应结构里，评价人信息是嵌套的 {@code customer} 对象
 * （前端读 {@code r.customer.nickname}）。本视图把索引里平铺的
 * {@code customerNickname / customerAvatar} 承载下来，由
 * {@code ReviewIndexResultMapper} 装配回嵌套结构，从而保持对外契约不变。
 */
@Data
public class ReviewIndexView implements Serializable {

    private String id;

    private String orderId;

    private String customerId;

    /** 评价人昵称（索引中平铺存储） */
    private String customerNickname;

    /** 评价人头像（索引中平铺存储） */
    private String customerAvatar;

    private String productId;

    private String productName;

    private String merchantId;

    private String merchantName;

    /** 1-5 星 */
    private Integer rating;

    private String content;

    private List<String> images;

    private LocalDateTime createTime;
}

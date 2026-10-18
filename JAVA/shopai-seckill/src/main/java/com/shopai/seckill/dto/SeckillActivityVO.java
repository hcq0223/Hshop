package com.shopai.seckill.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动详情视图
 */
@Data
public class SeckillActivityVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String title;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String status;

    private Integer perUserLimit;

    private String description;

    private Integer totalStock;

    private Integer soldCount;

    /** 活动商品列表（含实时余量与商品展示信息） */
    private List<SeckillItemVO> items;

    /**
     * 服务端当前时间。
     * <p>
     * <b>为什么要返回它</b>：秒杀是强时间敏感场景，如果直接信任客户端时钟做倒计时，
     * 用户把系统时间往前调就能提前看到「开抢」（虽然抢不到，但体验很怪），
     * 或者时间调错导致倒计时差几分钟。前端用本字段与 startTime 的差值算倒计时，
     * 服务端时间才是唯一权威。
     */
    private LocalDateTime serverTime;
}

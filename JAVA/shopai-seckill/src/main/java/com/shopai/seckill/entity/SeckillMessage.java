package com.shopai.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀本地消息实体（对应 seckill_message 表）
 * <p>
 * <b>为什么需要本地消息表</b>：
 * 秒杀的关键矛盾是「Redis 扣减」与「订单创建」跨了两种存储、跨了两个服务：
 * <ul>
 *   <li>Redis 扣减<b>无事务</b>，扣完就生效；</li>
 *   <li>订单创建是<b>远程调用</b>，可能超时、可能失败、可能成功但响应丢失。</li>
 * </ul>
 * 直接在抢购接口里同步调订单服务，一旦失败就会出现「库存扣了、订单没有」，
 * 而且这种数据在高峰期会成片出现。把「扣减」与「写一条待投递消息」放进
 * <b>同一个本地事务</b>，之后由定时任务不断重投，就能保证最终一定会成单，
 * 或者（超过重试上限后）一定会回补库存 —— 不会卡在中间态。
 * <p>
 * 投递是<b>至少一次</b>语义，所以消费端必须幂等：幂等键为 {@link #msgId}
 * （等于 seckill_record.id），订单服务按该键查重。
 */
@Data
@TableName("seckill_message")
public class SeckillMessage {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 消息业务ID（幂等键），等于 seckill_record.id。
     * <p>
     * 表上有唯一索引：同一笔秒杀只会有一条待投递消息，
     * 重复插入直接失败，天然防重。
     */
    private String msgId;

    /** 活动ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    /** seckill_stock.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    /** seckill_record.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long recordId;

    /** 用户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 客户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    /** SKU ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /** 数量 */
    private Integer quantity;

    /**
     * 完整消息体 JSON。
     * <p>
     * 存下来是为了重投时能「原样再发一遍」：如果重投时重新组装，
     * 期间任何字段来源变化（比如商品下架）都会让重投的消息与首次不同，
     * 排查问题时对不上。保持首次投递的内容不变最可控。
     */
    private String payload;

    /** 状态：pending待投递 / sent已投递 / done已完成 / failed已失败放弃 */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数，超过则回补库存并置 failed */
    private Integer maxRetry;

    /**
     * 下次可投递时间（指数退避）。
     * <p>
     * 不用「固定间隔重试」：订单服务短暂抖动时，固定 1 秒重试会在几秒内
     * 把重试次数耗光。退避让重试间隔逐步拉长，给下游恢复的时间。
     */
    private LocalDateTime nextRetryAt;

    /** 最后一次失败原因 */
    private String errorMsg;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.shopai.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.seckill.entity.SeckillRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀成功记录 Mapper
 */
@Mapper
public interface SeckillRecordMapper extends BaseMapper<SeckillRecord> {

    /**
     * 按「活动 + 用户」查询抢购记录（一人一单的 DB 判定）。
     *
     * @param activityId 活动ID
     * @param userId     用户ID
     * @return 记录；不存在返回 null
     */
    SeckillRecord selectByActivityAndUser(@Param("activityId") Long activityId,
                                          @Param("userId") Long userId);

    /**
     * 查询「超时仍未成单」的记录（超时回补任务用）。
     * <p>
     * 条件：{@code status = 'pending' AND created_at < 截止时间}。
     * 这些记录意味着库存被扣了但订单一直没建出来，需要回补。
     *
     * @param deadline 截止时间（now - 超时秒数）
     * @param limit    单批条数
     */
    List<SeckillRecord> selectTimeoutPending(@Param("deadline") LocalDateTime deadline,
                                             @Param("limit") int limit);

    /**
     * 标记成单成功。
     *
     * @param recordId 记录ID
     * @param orderId  订单ID
     * @param orderNo  订单号
     * @return 影响行数；0 表示记录已被其它流程改过（幂等保护）
     */
    int markSuccess(@Param("recordId") Long recordId,
                    @Param("orderId") Long orderId,
                    @Param("orderNo") String orderNo);

    /**
     * 标记成单失败。
     * <p>
     * {@code WHERE status = 'pending'} 是幂等保护：只有还在待成单态才允许改成失败，
     * 避免「已经成功的记录被超时任务误标为失败并回补库存」。
     *
     * @param recordId   记录ID
     * @param failReason 失败原因
     * @return 影响行数；0 表示状态已变，调用方不应再回补库存
     */
    int markFailed(@Param("recordId") Long recordId,
                   @Param("failReason") String failReason);

    /**
     * 更新客户ID（首次抢购时用户服务可能还没映射出 customerId，成单后回填）。
     */
    int updateCustomerId(@Param("recordId") Long recordId,
                         @Param("customerId") Long customerId);
}

package com.shopai.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动 Mapper
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 查询「待开始且已进入预热窗口」的活动（预热定时任务用）。
     * <p>
     * 只挑还没预热过的（{@code preheated} 标记按活动维度不能直接用，
     * 因为一个活动有多个 SKU，这里通过「该活动下还有未预热的 SKU」来判定）。
     *
     * @param now   当前时间
     * @param until 预热窗口上界 = now + 提前量
     * @return 活动列表
     */
    List<SeckillActivity> selectNeedPreheat(@Param("now") LocalDateTime now,
                                            @Param("until") LocalDateTime until);

    /**
     * 查询「已到开始时间但状态还是 pending」的活动（状态流转任务用）。
     *
     * @param now 当前时间
     */
    List<SeckillActivity> selectShouldStart(@Param("now") LocalDateTime now);

    /**
     * 查询「已到结束时间但状态还是 running」的活动（状态流转任务用）。
     *
     * @param now 当前时间
     */
    List<SeckillActivity> selectShouldEnd(@Param("now") LocalDateTime now);

    /**
     * 累加已售件数（成单成功后调用）。
     * <p>
     * 用 {@code sold_count = sold_count + qty} 而不是「查出来再写回」：
     * 后者在并发成单时会丢失更新。
     *
     * @param activityId 活动ID
     * @param qty        增加件数
     * @return 影响行数
     */
    int increaseSoldCount(@Param("activityId") Long activityId, @Param("qty") Integer qty);

    /**
     * 汇总并回写活动总库存（发布活动时调用）。
     *
     * @param activityId 活动ID
     * @return 影响行数
     */
    int refreshTotalStock(@Param("activityId") Long activityId);
}

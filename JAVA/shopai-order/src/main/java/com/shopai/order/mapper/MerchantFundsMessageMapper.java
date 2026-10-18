package com.shopai.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.order.entity.MerchantFundsMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家资金变更本地消息 Mapper
 */
@Mapper
public interface MerchantFundsMessageMapper extends BaseMapper<MerchantFundsMessage> {

    /**
     * 捞取「到点该重试」且未耗尽次数的消息。
     *
     * @param now   当前时间
     * @param limit 单批条数
     */
    List<MerchantFundsMessage> selectNeedRetry(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

    /**
     * 记录一次失败：重试次数 +1，并推迟下次重试时间。
     *
     * @return 影响行数
     */
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("errorMsg") String errorMsg);

    /**
     * 标记完成。
     * <p>
     * 带 {@code status IN ('pending','failed')} 条件：
     * 已 done 的消息不会被重复标记，也让「标记成功」具备幂等语义。
     */
    int markDone(@Param("id") Long id);

    /**
     * 标记重试耗尽（不再处理，等待人工介入）。
     */
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);
}

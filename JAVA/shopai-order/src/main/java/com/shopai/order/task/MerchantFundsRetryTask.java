package com.shopai.order.task;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.order.entity.MerchantFundsMessage;
import com.shopai.order.feign.MerchantInternalClient;
import com.shopai.order.service.MerchantFundsMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商家资金变更重试任务
 * <p>
 * 付款与结算解耦后，资金变更靠本任务保证最终一致：
 * 每 10 秒捞一批 pending 消息重新投递给商家服务，成功置 done，
 * 失败按指数退避推迟，耗尽次数置 failed（等人工对账）。
 * <p>
 * 因为消息表在 {@code (merchant_id, biz_key)} 上有唯一索引，
 * 同一笔业务只会有一条消息 —— <b>重试再多也不会重复加钱</b>。
 */
@Component
public class MerchantFundsRetryTask {

    private static final Logger log = LoggerFactory.getLogger(MerchantFundsRetryTask.class);

    @Autowired
    private MerchantFundsMessageService messageService;

    @Autowired
    private MerchantInternalClient merchantInternalClient;

    /**
     * 每 10 秒重试一次。
     * <p>
     * 间隔短一点让资金到账更及时（用户付款后商户很快看到余额变化），
     * 而实际重试时机由 {@code next_retry_at} 的指数退避控制，
     * 所以这里只是「检查频率」，不会造成高频重试。
     */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 20_000L)
    public void retryFundsChanges() {
        List<MerchantFundsMessage> list;
        try {
            list = messageService.listNeedRetry();
        } catch (Exception e) {
            // 定时任务绝不能把异常抛出去：未捕获异常会让 Spring 调度线程停止后续所有执行，
            // 表现为「重试突然再也不跑了」。这里必须吞掉。
            log.error("[资金变更重试] 查询待重试消息失败", e);
            return;
        }
        if (list.isEmpty()) {
            return;
        }

        int done = 0, failed = 0, retried = 0;
        for (MerchantFundsMessage msg : list) {
            if (messageService.exhausted(msg)) {
                messageService.markFailed(msg.getId(), "重试耗尽: " + msg.getErrorMsg());
                failed++;
                continue;
            }
            try {
                MerchantFundsChangeRequest req = MerchantFundsChangeRequest.of(msg.getMerchantId(), msg.getReason())
                        .revenue(msg.getRevenueDelta())
                        .available(msg.getAvailableDelta())
                        .frozen(msg.getFrozenDelta());
                Result<?> r = merchantInternalClient.changeFunds(req);
                if (r != null && r.getCode() != null && r.getCode() == 200) {
                    messageService.markDone(msg.getId());
                    done++;
                    log.info("[资金变更重试] 成功: id={}, bizKey={}, merchantId={}",
                            msg.getId(), msg.getBizKey(), msg.getMerchantId());
                } else {
                    messageService.markRetry(msg, r == null ? "商家服务无响应" : r.getMessage());
                    retried++;
                }
            } catch (Exception e) {
                // 单条失败不影响本批其它消息
                messageService.markRetry(msg, e.getMessage());
                retried++;
            }
        }
        log.info("[资金变更重试] 本轮处理 {} 条: 成功={}, 待重试={}, 放弃={}", list.size(), done, retried, failed);
    }
}

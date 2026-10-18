package com.shopai.order.service;

import com.shopai.common.dto.internal.MerchantFundsChangeRequest;
import com.shopai.order.entity.MerchantFundsMessage;
import com.shopai.order.mapper.MerchantFundsMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家资金变更消息服务（付款与结算解耦）
 * <p>
 * <b>它解决的结构性问题</b>：原实现把「累加商家资金」放在付款事务内同步 Feign 调用，
 * 于是付款事务持有 orders 行锁的整段时间横跨了一次跨服务调用。当商家服务那边
 * 因锁竞争/抖动而变慢时，付款会等满 50 秒（InnoDB 锁等待超时）后回滚 ——
 * 用户付不了款，且订单仍是待付款。
 * <p>
 * <b>改成异步后的语义</b>：
 * <ul>
 *   <li>付款只改本地 orders 并提交，<b>行锁立刻释放</b>，用户马上成功；</li>
 *   <li>提交后再通知商家服务；通知失败或对方失败 → 落本表，定时任务重试；</li>
 *   <li>资金到账从「阻塞在付款关键路径」变成「最终一致」（秒级~分钟级延迟）。</li>
 * </ul>
 * <p>
 * <b>幂等</b>：{@code (merchant_id, biz_key)} 唯一索引保证同一笔业务只有一条消息；
 * 重试是「至少一次」，但因为消息唯一 + 商家侧按增量累加，不会重复加钱。
 * <p>
 * <b>取舍</b>：换来的是「付款永远成功」，代价是「资金可能延迟到账」。
 * 对电商而言这个取舍方向是对的 —— 用户付不了款是硬故障，资金晚几秒是软延迟。
 * 但如果业务要求资金必须与付款同时落定，则应保留同步调用、改为在商家侧解决锁竞争。
 */
@Service
public class MerchantFundsMessageService {

    private static final Logger log = LoggerFactory.getLogger(MerchantFundsMessageService.class);

    /** 退避基数（秒）：第 n 次重试等待 = BASE * 2^(n-1) */
    private static final long BACKOFF_BASE_SECONDS = 5L;

    /** 退避上限（秒） */
    private static final long MAX_BACKOFF_SECONDS = 300L;

    /** 单批处理条数 */
    private static final int BATCH_SIZE = 200;

    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_RETRY = 10;

    @Autowired
    private MerchantFundsMessageMapper messageMapper;

    /**
     * 落一条待处理的资金变更消息（在付款事务内调用）。
     * <p>
     * 必须与「更新订单状态」在同一事务里：否则订单状态提交了、消息却丢了，
     * 就会出现「订单已支付但商户永远收不到钱」。
     * <p>
     * 唯一索引冲突（同一笔业务重复落库）时视为已存在，直接忽略 ——
     * 这正是幂等想要的行为，不该抛异常打断付款。
     *
     * @param request 资金变更请求
     * @param bizKey  业务幂等键，如 {@code pay:{orderId}}
     * @return 落库后的消息；若已存在则返回 null
     */
    public MerchantFundsMessage enqueue(MerchantFundsChangeRequest request, String bizKey) {
        if (request == null || request.getMerchantId() == null || bizKey == null) {
            return null;
        }
        MerchantFundsMessage msg = new MerchantFundsMessage();
        msg.setMerchantId(request.getMerchantId());
        msg.setBizKey(bizKey);
        msg.setRevenueDelta(request.getRevenueDelta());
        msg.setAvailableDelta(request.getAvailableDelta());
        msg.setFrozenDelta(request.getFrozenDelta());
        msg.setReason(request.getReason());
        msg.setStatus("pending");
        msg.setRetryCount(0);
        msg.setMaxRetry(DEFAULT_MAX_RETRY);
        // 立即可处理：提交后由定时任务（或提交后的即时投递）取走
        msg.setNextRetryAt(LocalDateTime.now());
        try {
            messageMapper.insert(msg);
            log.info("资金变更消息已落库: merchantId={}, bizKey={}, reason={}, revenueDelta={}, availableDelta={}",
                    msg.getMerchantId(), bizKey, msg.getReason(),
                    msg.getRevenueDelta(), msg.getAvailableDelta());
            return msg;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同一笔业务已有消息（重复付款/重试）→ 幂等忽略
            log.info("资金变更消息已存在，幂等忽略: merchantId={}, bizKey={}", request.getMerchantId(), bizKey);
            return null;
        }
    }

    /** 捞待重试消息 */
    public List<MerchantFundsMessage> listNeedRetry() {
        return messageMapper.selectNeedRetry(LocalDateTime.now(), BATCH_SIZE);
    }

    /** 记录一次失败并按指数退避推迟 */
    public void markRetry(MerchantFundsMessage msg, String reason) {
        int next = (msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1;
        long backoff = Math.min(BACKOFF_BASE_SECONDS * (1L << Math.min(next - 1, 10)), MAX_BACKOFF_SECONDS);
        messageMapper.markRetry(msg.getId(), LocalDateTime.now().plusSeconds(backoff), truncate(reason));
        log.warn("资金变更投递失败，将重试: id={}, bizKey={}, retry={}/{}, 下次={}s后, reason={}",
                msg.getId(), msg.getBizKey(), next, msg.getMaxRetry(), backoff, truncate(reason));
    }

    /** 是否已耗尽重试次数 */
    public boolean exhausted(MerchantFundsMessage msg) {
        int retry = msg.getRetryCount() == null ? 0 : msg.getRetryCount();
        int max = msg.getMaxRetry() == null ? DEFAULT_MAX_RETRY : msg.getMaxRetry();
        return retry >= max;
    }

    public void markDone(Long id) {
        messageMapper.markDone(id);
    }

    public void markFailed(Long id, String reason) {
        messageMapper.markFailed(id, truncate(reason));
        log.error("资金变更重试耗尽，已放弃（需人工对账）: id={}, reason={}", id, truncate(reason));
    }

    /** error_msg 是 varchar(512)，超长会插入失败，统一截断 */
    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}

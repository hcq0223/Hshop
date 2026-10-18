package com.shopai.order.mq;

import com.shopai.common.Result;
import com.shopai.common.dto.internal.seckill.SeckillOrderCreateRequest;
import com.shopai.common.dto.internal.seckill.SeckillOrderMessage;
import com.shopai.common.dto.internal.seckill.SeckillOrderResult;
import com.shopai.common.mq.MqConstants;
import com.shopai.order.feign.SeckillInternalClient;
import com.shopai.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 秒杀异步下单消费者（订单服务）
 * <p>
 * <b>它在整条链路里的位置</b>：
 * <pre>
 *   用户抢购 → 秒杀服务 Redis 原子扣减 → 写本地消息表 → 投 MQ
 *                                                        ↓
 *                                        本消费者：创建真实订单
 *                                                        ↓
 *                                        回调秒杀服务落账（记录置成功/写流水/库存 locked→sold）
 * </pre>
 * <p>
 * <b>幂等</b>：消息投递是「至少一次」，同一条消息可能到达多次。
 * 订单号固定为 {@code SK + msgId}（msgId = seckill_record.id），
 * {@link OrderService#createSeckillOrder} 会先按订单号查重，
 * 命中则直接返回已存在的订单并标记 {@code idempotentHit=true}，
 * <b>不会重复下单</b>。
 * <p>
 * <b>失败处理</b>：本方法抛异常让消息重回队列（受 application.yml 的
 * retry 配置约束：重试 3 次后仍失败则 {@code default-requeue-rejected=false}
 * 不再重回队列，进死信交换机）。同时秒杀侧的本地消息表另有重投任务兜底，
 * 两边共同保证「要么成单、要么回补库存」，不会卡在中间态。
 */
@Component
public class SeckillOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);

    @Autowired
    private OrderService orderService;

    @Autowired
    private SeckillInternalClient seckillInternalClient;

    /**
     * 消费秒杀下单消息。
     *
     * @param message 秒杀下单消息（见 {@link SeckillOrderMessage}）
     */
    @RabbitListener(queues = MqConstants.QUEUE_SECKILL_ORDER_CREATE)
    public void onSeckillOrderCreate(SeckillOrderMessage message) {
        log.info("收到秒杀下单消息: msgId={}, recordId={}, activityId={}, userId={}, skuId={}, qty={}, addressId={}",
                message.getMsgId(), message.getRecordId(), message.getActivityId(),
                message.getUserId(), message.getSkuId(), message.getQuantity(), message.getAddressId());

        // 必要字段校验：缺 customerId 时订单服务无法建单（它需要 customers.id）。
        // 这种情况通常发生在抢购瞬间用户服务抖动、customerId 没解析出来。
        // 直接抛异常让消息重试，而不是静默丢弃 —— 用户已经扣过库存了，
        // 静默失败会导致「扣了库存永远没订单」。重试耗尽后由秒杀侧回补库存。
        if (message.getCustomerId() == null) {
            log.error("秒杀下单消息缺少 customerId，无法建单（将重试）: msgId={}, userId={}",
                    message.getMsgId(), message.getUserId());
            throw new IllegalStateException("秒杀下单消息缺少 customerId, msgId=" + message.getMsgId());
        }
        if (message.getAddressId() == null) {
            log.error("秒杀下单消息缺少 addressId，无法建单（将重试）: msgId={}, userId={}",
                    message.getMsgId(), message.getUserId());
            throw new IllegalStateException("秒杀下单消息缺少 addressId, msgId=" + message.getMsgId());
        }

        // 订单号是幂等键：同一 msgId 重复投递 → 同一订单号 → 不会重复建单
        String orderNo = SeckillOrderCreateRequest.buildOrderNo(message.getRecordId());

        SeckillOrderCreateRequest req = new SeckillOrderCreateRequest();
        req.setCustomerId(message.getCustomerId());
        req.setSkuId(message.getSkuId());
        req.setQuantity(message.getQuantity() == null ? 1 : message.getQuantity());
        req.setAddressId(message.getAddressId());
        req.setSeckillPrice(message.getSeckillPrice());
        req.setOrderNo(orderNo);
        req.setSeckillRecordId(message.getRecordId());
        req.setActivityId(message.getActivityId());

        SeckillOrderResult result;
        try {
            // 直接调 Service（本进程内），不绕一圈 Feign 调自己 ——
            // 少一次网络往返，也避免「订单服务调自己的内部接口」这种不必要的耦合。
            result = orderService.createSeckillOrder(
                    req.getCustomerId(), req.getSkuId(), req.getQuantity(),
                    req.getAddressId(), req.getSeckillPrice(), req.getOrderNo());
        } catch (Exception e) {
            log.error("秒杀下单失败（将重试）: msgId={}, recordId={}, orderNo={}, reason={}",
                    message.getMsgId(), message.getRecordId(), orderNo, e.getMessage(), e);
            throw e;
        }

        log.info("秒杀订单创建成功: orderId={}, orderNo={}, msgId={}, idempotentHit={}",
                result.getOrderId(), result.getOrderNo(), message.getMsgId(), result.getIdempotentHit());

        // 回调秒杀服务落账（抢购记录置成功、写流水、锁定库存转已售、累加销量）。
        // 与订单创建成败解耦：回调失败不抛异常，否则消息会重投、
        // 而订单已经建好了（重投时按 orderNo 幂等返回同一笔订单，只是再来一次回调）。
        // 秒杀侧另有超时回补任务兜底，不会因一次回调失败就卡死。
        try {
            Result<String> r = seckillInternalClient.orderCreated(result);
            if (r == null || r.getCode() == null || r.getCode() != 200) {
                log.warn("回调秒杀服务落账失败（订单已创建，由秒杀侧重投兜底）: orderNo={}, msg={}",
                        result.getOrderNo(), r == null ? "无响应" : r.getMessage());
            }
        } catch (Exception e) {
            log.warn("回调秒杀服务落账异常（订单已创建，由秒杀侧重投兜底）: orderNo={}, reason={}",
                    result.getOrderNo(), e.getMessage());
        }
    }
}

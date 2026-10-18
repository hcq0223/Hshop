package com.shopai.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 索引变更事件（通用载荷，跨服务契约）
 * <p>
 * 微服务拆分说明：列表查询改造后，各业务服务（商品 / 商家 / 订单 / 评价 / 用户）不再把
 * 分页列表放 Redis 缓存，而是由 shopai-search 维护对应的 ES 索引来承载列表查询。
 * 为此，数据归属方在增删改后发本事件，搜索服务消费后增量同步 ES。
 * <p>
 * 事件只携带「实体标识 + 操作类型」，不携带实体全量字段。这样做的好处：
 * 1. 消息体小、投递快，且不会因为字段演进导致生产者/消费者版本耦合；
 * 2. 搜索服务收到事件后通过 Feign 回源拉取该实体的**索引视图**（由归属方组装好
 *    跨服务字段，如店铺名、分类名、评价人昵称），保证索引数据来自权威来源；
 * 3. 回源发生在消费侧，即使消息乱序/重复，最终写入的都是「当前最新值」，天然幂等。
 * <p>
 * 幂等性说明：upsert 操作以实体ID为 ES 文档 _id，重复消费结果一致；
 * delete 操作对不存在的文档也视为成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexChangedEvent implements Serializable {

    /**
     * 实体类型，取值见 {@link EntityType}。
     * 说明：用字符串而非枚举，避免生产/消费双方因新增枚举值而需要同步发版。
     */
    private String entityType;

    /**
     * 操作类型，取值见 {@link Operation}。
     * upsert = 新增/修改统一按最新值写入；delete = 从索引移除。
     */
    private String operation;

    /** 实体主键ID（雪花ID，字符串承载避免 JS/JSON 精度问题） */
    private String id;

    /** 事件产生时间（毫秒时间戳，仅用于排查与日志） */
    private Long timestamp;

    // ==================== 工厂方法 ====================

    public static IndexChangedEvent upsert(String entityType, Object id) {
        return new IndexChangedEvent(entityType, Operation.UPSERT, String.valueOf(id), System.currentTimeMillis());
    }

    public static IndexChangedEvent delete(String entityType, Object id) {
        return new IndexChangedEvent(entityType, Operation.DELETE, String.valueOf(id), System.currentTimeMillis());
    }

    public boolean isDelete() {
        return Operation.DELETE.equalsIgnoreCase(operation);
    }

    /** 实体类型常量 */
    public static final class EntityType {
        public static final String PRODUCT = "product";
        public static final String MERCHANT = "merchant";
        public static final String ORDER = "order";
        public static final String REVIEW = "review";
        public static final String USER = "user";

        private EntityType() {
        }
    }

    /** 操作类型常量 */
    public static final class Operation {
        public static final String UPSERT = "upsert";
        public static final String DELETE = "delete";

        private Operation() {
        }
    }
}

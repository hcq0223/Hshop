package com.shopai.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopai.inventory.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 库存 Mapper
 * <p>
 * 微服务拆分说明：对接独立 inventory 表。扣减/回补通过 XML 中的乐观锁 SQL 完成，
 * 保证并发下不超卖（与设计文档第 7 节一致）。
 */
@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 根据 SKU ID 查询库存
     */
    Inventory selectBySkuId(@Param("skuId") Long skuId);

    /**
     * 乐观锁扣减库存：stock = stock - #{qty}, version = version + 1
     * 仅当 stock >= #{qty} 时成功（防止超卖）。
     *
     * @param skuId SKU ID
     * @param qty   扣减数量
     * @return 受影响行数（1=成功，0=库存不足或 SKU 不存在）
     */
    int deductStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 回补库存：stock = stock + #{qty}（订单取消/退款时调用）
     *
     * @param skuId SKU ID
     * @param qty   回补数量
     * @return 受影响行数
     */
    int restoreStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 新增库存（增加可售库存，SKU 补货/初始化用）
     *
     * @param skuId SKU ID
     * @param qty   增加数量
     * @return 受影响行数
     */
    int increaseStock(@Param("skuId") Long skuId, @Param("qty") int qty);
}

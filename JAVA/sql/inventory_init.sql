-- ============================================================
-- 库存数据初始化说明
-- 2026-10-04
-- 说明：
--   微服务拆分后，库存由 shopai-inventory 服务管理。
--   原 product_skus.stock 中的库存值需迁移到 inventory 表。
--
--   由于 inventory.id 使用雪花ID（由应用层生成），本迁移
--   由应用层 InventoryService.initStock() 批量完成，逻辑如下：
--     1. 查询 product_skus 表（id, stock）
--     2. 为每条 SKU 生成雪花ID，写入 inventory（sku_id, stock, locked_stock=0, version=0）
--     3. 已存在的 sku_id 跳过（幂等）
--
--   本 SQL 脚本仅用于迁移前/后的核对校验，不直接执行数据写入。
-- ============================================================

-- 迁移前校验：查看 product_skus 中有库存的记录数
-- SELECT COUNT(*) FROM product_skus WHERE stock IS NOT NULL;

-- 迁移后校验：inventory 记录数应与 product_skus 库存非空记录数一致
-- SELECT COUNT(*) FROM inventory;

-- 迁移后核对：抽查若干 SKU 的库存是否一致
-- SELECT ps.id AS sku_id, ps.stock AS old_stock, inv.stock AS new_stock
-- FROM product_skus ps
-- LEFT JOIN inventory inv ON inv.sku_id = ps.id
-- WHERE ps.stock IS NOT NULL
--   AND (inv.stock IS NULL OR inv.stock <> ps.stock)
-- LIMIT 20;
-- 若上述查询返回 0 行，说明迁移一致。

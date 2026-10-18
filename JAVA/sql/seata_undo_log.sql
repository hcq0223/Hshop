-- ============================================================
-- Seata AT 模式 undo_log 表
-- 2026-10-04
-- 说明：
--   Seata AT 分布式事务要求每个参与全局事务的业务库
--   都创建 undo_log 表（用于记录本地事务的前镜像/后镜像，支持回滚）。
--   当前为"共享单库"阶段，仅需在 hcq_shop 库执行一次即可；
--   后续拆独立库时，订单库、库存库需各自执行。
--
--   标准建表语句来自 Seata 官方（AT 模式）：
--   1.4.0 及以上版本使用以下结构。
-- ============================================================

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT '分支事务ID',
    xid           VARCHAR(128) NOT NULL COMMENT '全局事务ID',
    context       VARCHAR(128) NOT NULL COMMENT '上下文',
    rollback_info LONGBLOB     NOT NULL COMMENT '回滚信息(前/后镜像序列化)',
    log_status    INT          NOT NULL COMMENT '日志状态: 0=正常 1=全局完成',
    log_created   DATETIME(6)  NOT NULL COMMENT '创建时间',
    log_modified  DATETIME(6)  NOT NULL COMMENT '修改时间',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seata AT 模式 undo_log 表';

-- 可选：为 log_created 建立索引，便于定期清理过期 undo_log
-- CREATE INDEX idx_log_created ON undo_log (log_created);

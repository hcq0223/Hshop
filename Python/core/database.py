# ============================================
# MySQL 数据库连接（只读）
# 使用连接池，用于工具函数直接查询数据
#
# ⚠️ 微服务拆分后【已废弃，请勿使用】
#   Java 侧已按「独立库」原则拆分为 shopai-user / product / order /
#   merchant / search / inventory，每个服务只访问自己的库，
#   服务间数据一律走 Feign 内部接口，不再有跨库 JOIN。
#   Agent 属于外部调用方，只能经网关（JAVA_API_BASE）走 HTTP，
#   直连 MySQL 会绕过网关鉴权与各服务的数据边界，
#   且单库里已查不到跨服务的数据。
#   新增工具请用 services/java_api.py 的 call_java_api()。
#   本模块当前无任何调用方，保留仅为兼容旧脚本。
# ============================================

from contextlib import contextmanager
from typing import List, Dict, Any
from dbutils.pooled_db import PooledDB
import pymysql
from config import settings

# ============================================
# 连接池初始化（懒加载，首次使用时创建）
# ============================================
_pool: PooledDB = None


def _get_pool() -> PooledDB:
    """获取数据库连接池（单例）"""
    global _pool
    if _pool is None:
        _pool = PooledDB(
            creator=pymysql,
            maxconnections=10,          # 最大连接数
            mincached=2,                # 最小空闲连接
            maxcached=5,                # 最大空闲连接
            blocking=True,              # 连接池满时阻塞等待
            host=settings.MYSQL_HOST,
            port=settings.MYSQL_PORT,
            user=settings.MYSQL_USER,
            password=settings.MYSQL_PASSWORD,
            database=settings.MYSQL_DATABASE,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,  # 返回字典格式
        )
    return _pool


@contextmanager
def get_connection():
    """
    获取一个数据库连接（上下文管理器，自动归还连接池）

    用法:
        with get_connection() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT ...")
            rows = cursor.fetchall()
    """
    pool = _get_pool()
    conn = pool.connection()
    try:
        yield conn
    finally:
        conn.close()  # 归还到连接池


def query(sql: str, params: tuple = None) -> List[Dict[str, Any]]:
    """
    执行只读查询，返回字典列表

    用法:
        rows = query("SELECT * FROM products WHERE id = %s", (1,))
        # [{"id": 1, "name": "T恤", "price": 99.00}, ...]
    """
    with get_connection() as conn:
        with conn.cursor() as cursor:
            cursor.execute(sql, params or ())
            return cursor.fetchall()


def query_one(sql: str, params: tuple = None) -> Dict[str, Any]:
    """执行只读查询，返回单条记录"""
    rows = query(sql, params)
    return rows[0] if rows else None

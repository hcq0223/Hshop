# ============================================
# Java 后端 API 调用模块
# 用于将需要权限控制的写操作转发到 Java 后端
# ============================================

import httpx
from typing import Optional, Dict, Any
from config import settings


# ============================================
# 全局 HTTP 客户端（连接池复用）
# 每次调用都新建 AsyncClient 会重复 TCP 握手，
# 一次对话里工具可能连调 5 次，开销放大明显
# ============================================
_client: Optional[httpx.AsyncClient] = None


def _get_client() -> httpx.AsyncClient:
    """获取全局复用客户端（懒加载，必须在协程里调用）"""
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=30.0,
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=20),
        )
    return _client


async def close_client() -> None:
    """关闭连接池，由 main.py 的 lifespan 在服务退出时调用"""
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


async def call_java_api(
    token: str,
    method: str,
    path: str,
    params: Optional[Dict[str, Any]] = None,
    json_body: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """
    转发请求到 Java 后端

    Args:
        token: 用户的 JWT token（直接从请求头透传）
        method: HTTP 方法（GET / POST / PUT / DELETE）
        path: API 路径（相对 JAVA_API_BASE，不含 /api 前缀），
              如 "/admin/merchants/pending"、f"/product/{pid}"
        params: URL 查询参数（可选）
        json_body: JSON 请求体（可选，用于 POST/PUT）

    Returns:
        Java 后端返回的 JSON 响应

    注意:
        JAVA_API_BASE 已含 "/api"，且该地址指向网关（8080），
        因此 path 必须是网关暴露的路由。微服务拆分后不要再用单体旧路径
        （如 /user/admin/getUsers），也不要调用 /api/*/internal/** ——
        内部接口仅供 Feign 服务间调用，网关会直接拦截。
    """
    url = f"{settings.JAVA_API_BASE}{path}"

    headers = {
        "Authorization": token if token.startswith("Bearer ") else f"Bearer {token}",
    }

    client = _get_client()
    response = await client.request(
        method=method,
        url=url,
        headers=headers,
        params=params,
        json=json_body,
    )

    if response.status_code >= 400:
        # Java 侧异常也带 JSON 体（Result.error），优先把中文 message 交回模型，
        # 而不是只给一句 "Client error '403 Forbidden'"
        try:
            body = response.json()
        except ValueError:
            body = {}
        if not isinstance(body, dict):
            body = {}
        return {
            "code": response.status_code,
            "message": body.get("message") or body.get("detail") or f"请求失败(HTTP {response.status_code})",
        }
    return response.json()
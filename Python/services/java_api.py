# ============================================
# Java 后端 API 调用模块
# 用于将需要权限控制的写操作转发到 Java 后端
# ============================================

import httpx
from typing import Optional, Dict, Any
from config import settings


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

    用法:
        result = await call_java_api(
            token, "GET", "/admin/merchants/pending",
            params={"page": 1, "size": 20}
        )
    """
    url = f"{settings.JAVA_API_BASE}{path}"

    headers = {
        "Authorization": token if token.startswith("Bearer ") else f"Bearer {token}",
    }

    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.request(
            method=method,
            url=url,
            headers=headers,
            params=params,
            json=json_body,
        )
        response.raise_for_status()
        return response.json()

# ============================================
# 对话路由
# 处理前端的聊天请求，根据角色分发到对应 Agent
# ============================================

from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import StreamingResponse
from models.schemas import ChatRequest, ChatResponse
from core.auth import get_role_from_token, get_user_id_from_token
from agents.customer_agent import CustomerAgent
from agents.merchant_agent import MerchantAgent
from agents.admin_agent import AdminAgent

# 路由前缀从 /api/agent 改为 /agent，避免与 Java 后端的 /api 前缀冲突
# 前端通过 vite 代理 /agent → http://localhost:8090 访问
router = APIRouter(prefix="/agent", tags=["对话"])

# ---------- 初始化三个 Agent 实例（全局单例） ----------
_customer_agent = CustomerAgent()
_merchant_agent = MerchantAgent()
_admin_agent = AdminAgent()


def _get_agent(token: str):
    """
    根据 token 中的角色返回对应的 Agent 实例

    Raises:
        HTTPException 403: 角色无效或无权访问
    """
    role = get_role_from_token(token)
    agent_map = {
        "customer": _customer_agent,
        "merchant": _merchant_agent,
        "admin": _admin_agent,
    }
    agent = agent_map.get(role)
    if not agent:
        raise HTTPException(status_code=403, detail=f"未知角色或无权访问: {role}")
    return agent


# ============================================
# 普通对话接口（非流式）
# ============================================
@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, req: Request):
    """
    发送消息给 Agent 并获取完整回复。

    请求头需要携带 Authorization: Bearer <token>
    """
    # 从请求头获取 token
    token = req.headers.get("Authorization", "")
    if not token:
        raise HTTPException(status_code=401, detail="未提供认证 Token")

    # 解析用户身份
    user_id = get_user_id_from_token(token)
    role = get_role_from_token(token)

    # 获取对应角色的 Agent
    agent = _get_agent(token)

    # 执行对话
    reply = await agent.chat(
        user_id=user_id,
        message=request.message,
        token=token,
        history=[h.model_dump() for h in (request.history or [])],
    )

    return ChatResponse(reply=reply, role=role)


# ============================================
# 流式对话接口（SSE）
# ============================================
@router.post("/chat/stream")
async def chat_stream(request: ChatRequest, req: Request):
    """
    流式对话接口。

    前端通过 EventSource 或 fetch + ReadableStream 接收逐字输出。
    响应格式为 SSE (Server-Sent Events):
        data: 文字内容
        data: [DONE]
    """
    token = req.headers.get("Authorization", "")
    if not token:
        raise HTTPException(status_code=401, detail="未提供认证 Token")

    user_id = get_user_id_from_token(token)
    agent = _get_agent(token)

    return StreamingResponse(
        agent.chat_stream(
            user_id=user_id,
            message=request.message,
            token=token,
            history=[h.model_dump() for h in (request.history or [])],
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # 禁用 Nginx 缓冲
        },
    )

from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from agents.admin_agent import AdminAgent
from agents.customer_agent import CustomerAgent
from agents.merchant_agent import MerchantAgent


# ============================================
# 应用生命周期（lifespan）
# 替代已弃用的 @app.on_event，官方推荐写法
# ============================================
@asynccontextmanager
async def lifespan(app: FastAPI):
    """服务启动/关闭时的资源初始化与释放"""
    print("[Agent] 服务启动中...")
    # 后续在此处初始化：
    # - MySQL 连接池
    # - 验证 LLM API Key 是否可用
    print("[Agent] 服务启动完成，监听端口 8090")
    yield
    print("[Agent] 服务关闭，释放资源...")


# ---------- 创建 FastAPI 实例 ----------
app = FastAPI(title="ShopAi Agent", lifespan=lifespan)

# ---------- 跨域配置（允许 Vue 前端访问） ----------
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",   # Vite 开发服务器
        "http://localhost:3000",   # 备用前端端口
    ],
    allow_credentials=True,
    allow_methods=["*"],           # 允许所有 HTTP 方法
    allow_headers=["*"],           # 允许所有请求头
)

# 初始化各角色 Agent（单例）
customer_agent = CustomerAgent()
merchant_agent = MerchantAgent()
admin_agent = AdminAgent()


# ============================================
# 健康检查端点
# ============================================
@app.get("/health")
async def health_check():
    """Agent 服务健康检查，返回运行状态"""
    return {
        "status": "running",
        "service": "ShopAi Python Agent",
        "version": "1.0.0",
    }


# ============================================
# 注册路由模块
# ============================================
from routers import chat
app.include_router(chat.router, tags=["对话"])


# ============================================
# 直接运行（开发调试用）
# ============================================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8090,
        reload=True,   # 代码变更自动重启
    )

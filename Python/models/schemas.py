# ============================================
# 请求 / 响应 数据模型
# FastAPI 会根据这些模型自动生成 Swagger 文档
# ============================================

from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any


# ============================================
# 对话相关模型
# ============================================

class ChatMessage(BaseModel):
    """单条对话消息"""
    role: str = Field(..., description="消息角色：user / assistant / system")
    content: str = Field(..., description="消息内容")

    class Config:
        json_schema_extra = {
            "example": {"role": "user", "content": "帮我推荐一件夏季T恤"}
        }


class ChatRequest(BaseModel):
    """对话请求体"""
    message: str = Field(..., description="用户当前输入的消息", min_length=1)
    history: Optional[List[ChatMessage]] = Field(
        default=None,
        description="历史对话记录，最多保留最近10轮"
    )

    class Config:
        json_schema_extra = {
            "example": {
                "message": "我的订单到哪了？",
                "history": [
                    {"role": "user", "content": "你好"},
                    {"role": "assistant", "content": "你好！有什么可以帮你的？"},
                ],
            }
        }


class ChatResponse(BaseModel):
    """普通对话响应（非流式）"""
    reply: str = Field(..., description="Agent 回复内容")
    role: Optional[str] = Field(default=None, description="当前用户角色")


# ============================================
# 工具调用结果模型
# ============================================

class ToolResult(BaseModel):
    """工具函数执行结果"""
    success: bool = Field(..., description="是否执行成功")
    data: Any = Field(default=None, description="返回的数据")
    error: Optional[str] = Field(default=None, description="错误信息")


# ============================================
# 商品搜索模型
# ============================================

class ProductSearchParams(BaseModel):
    """商品搜索参数"""
    keyword: str = Field(..., description="搜索关键词")
    category: Optional[str] = Field(default=None, description="分类名称")
    min_price: Optional[float] = Field(default=None, description="最低价格")
    max_price: Optional[float] = Field(default=None, description="最高价格")
    sort_by: Optional[str] = Field(
        default="sales",
        description="排序方式",
        examples=["sales", "price_asc", "price_desc", "rating"],
    )


# ============================================
# 订单查询模型
# ============================================

class OrderQueryParams(BaseModel):
    """订单查询参数"""
    status: Optional[str] = Field(
        default=None,
        description="订单状态筛选",
        examples=["pending_pay", "paid", "shipped", "received", "completed", "cancelled", "refunding"],
    )
    page: int = Field(default=1, ge=1, description="页码")
    size: int = Field(default=20, ge=1, le=100, description="每页条数")


# ============================================
# 用户搜索模型（管理员用）
# ============================================

class UserSearchParams(BaseModel):
    """用户搜索参数"""
    keyword: Optional[str] = Field(default=None, description="用户名/手机号搜索")
    role: Optional[str] = Field(
        default=None,
        description="按角色筛选",
        examples=["customer", "merchant", "admin"],
    )
    status: Optional[int] = Field(default=None, description="按状态筛选：0=禁用, 1=正常")


# ============================================
# 错误响应模型
# ============================================

class ErrorResponse(BaseModel):
    """统一错误响应"""
    detail: str = Field(..., description="错误描述")
    code: Optional[str] = Field(default=None, description="错误码")

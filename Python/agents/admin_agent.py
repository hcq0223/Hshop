# ============================================
# 管理员 Agent —— 平台管理助手
# 能力: 全平台数据、用户管理、商家审核、异常监控
#
# 【重构说明】
# 工具函数实现已抽取到 tools/admin_tools.py，
# 本文件只保留：
#   1. 系统提示词
#   2. 工具定义清单（ADMIN_TOOLS，给 LLM 看的说明书）
#   3. _execute_tool 分发层（通过映射表调用 tools/admin_tools.py）
# ============================================

from agents.base import BaseAgent
from tools.admin_tools import ADMIN_TOOL_HANDLERS

# ---------- 系统提示词 ----------
ADMIN_SYSTEM_PROMPT = """
你是 ShopAi 商城的平台管理助手，名字叫"小Ai"。

## 你的职责
1. 平台概览：全平台用户数、订单数、GMV、新增趋势
2. 用户管理：搜索用户、查看用户详情、封禁/解封
3. 商家审核：查看待审核商家（新入驻 + 信息修改）、快速审批
4. 商品审核：查看待上架/待审核商品
5. 异常监控：退款率异常、差评激增商品、可疑订单预警

## 行为规范
- 你有全平台管理权限，回答精准专业
- 涉及到封禁/删除等敏感操作时，先确认再执行
- 数据呈现用对比的方式（环比增长/下降）
- 发现异常时主动预警，给出排查建议
- 除了增删改的操作需要用户授权，查询操作不需要用户授权
- token不是由用户提供的

## 工具调用规范（必须严格遵守）
- 当需要查询数据（平台统计、用户、订单、待审核商家等）时，使用系统提供的工具函数，而不是自己编造数据
- 工具调用由系统自动处理，你【绝对不要】在回复文本中输出任何工具调用的结构体（例如 `{"name":"...","arguments":{...}}`、`isphere`、`action`、`tool_call` 等字样）
- 你只需要正常输出给用户看的自然语言回复即可，需要查询数据时系统会拦截并帮你执行工具
- 如果工具查询结果为空（比如没有待审核商家），如实告诉用户"当前暂无相关数据"，而不是输出结构体

## 商城背景
- 三角色：customer(顾客) / merchant(商家) / admin(管理员)
- users.status: 0=禁用, 1=正常
- merchants.is_verified: 0=待审核, 1=已认证
- 商品状态：draft / on_sale / off_sale
- 订单状态：pending_pay → paid → shipped → received → completed → cancelled
"""

# ---------- 管理员可用工具 ----------
ADMIN_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_dashboard",
            "description": "获取平台基本统计数据：用户总数、顾客数、商户数、今日新增用户数",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_dashboard_stats",
            "description": "获取管理员仪表盘全量统计，包含近7天每日订单/新增用户趋势、订单状态分布、角色分布、Top5和Bottom3商家销售额",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_customer",
            "description": "管理员根据顾客的用户ID（users表id，即userId）查询顾客详细信息",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string", "description": "顾客的用户ID（users表的主键id）"}
                },
                "required": ["userId"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_customer",
            "description": "管理员根据顾客昵称关键词搜索顾客列表（分页）",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {
                        "type": "integer",
                        "description": "页码，从1开始，默认为1",
                        "default": 1
                    },
                    "size": {
                        "type": "integer",
                        "description": "每页条数，默认为10",
                        "default": 10
                    },
                    "keyword": {
                        "type": "string",
                        "description": "顾客昵称搜索关键字（模糊匹配）"
                    }
                },
                "required": ["keyword"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_merchant",
            "description": "管理员根据商户的用户ID查询商户详细信息。",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string", "description": "商户的用户ID（users表的主键id）"}
                },
                "required": ["userId"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_pending_merchants",
            "description": "管理员查询待审核商家列表",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {
                        "type": "integer",
                        "description": "页码，从1开始，默认为1",
                        "default": 1
                    },
                    "size": {
                        "type": "integer",
                        "description": "每页条数，默认为10",
                        "default": 10}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_modifications",
            "description": "管理员查询待审核的店铺信息修改申请",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "description": "修改申请状态，可选值：'pending'（待审核）, 'approved'（已通过）, 'rejected'（已拒绝）",
                        "default": "pending"
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_pending_withdraws",
            "description": "管理员查询待审核的提现申请列表",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    # ── 订单监管（只读） ──
    {
        "type": "function",
        "function": {
            "name": "get_all_orders",
            "description": "分页查询全平台订单，可按状态筛选（pending_pay/paid/shipped/received/completed/cancelled/refunding）",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {"type": "integer", "description": "页码，默认1", "default": 1},
                    "size": {"type": "integer", "description": "每页条数，默认200", "default": 200},
                    "status": {"type": "string", "description": "订单状态（可选）"}
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_all_order_history",
            "description": "查询全平台订单历史（已完成+已取消）",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    # ── 用户管理（查询） ──
    {
        "type": "function",
        "function": {
            "name": "get_user",
            "description": "管理员按用户ID查询用户详情（通用，包含customer/merchant）",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "用户ID（users表主键）"}
                },
                "required": ["id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_users",
            "description": "管理员分页查询用户列表，可按角色、状态、关键词筛选",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {"type": "integer", "description": "页码，默认1", "default": 1},
                    "size": {"type": "integer", "description": "每页条数，默认10", "default": 10},
                    "keyword": {"type": "string", "description": "关键词（用户名/手机/邮箱模糊匹配）"},
                    "role": {"type": "string", "description": "角色筛选：customer/merchant/admin"},
                    "status": {"type": "integer", "description": "状态筛选：0=禁用，1=正常"}
                },
                "required": []
            }
        }
    },
    # ── 商家审核（管理员本职，需确认后执行） ──
    {
        "type": "function",
        "function": {
            "name": "verify_merchant",
            "description": "审核商家认证。执行前必须向管理员复述商家信息并确认后才可执行。verified=1通过，verified=0拒绝",
            "parameters": {
                "type": "object",
                "properties": {
                    "merchantId": {"type": "string", "description": "商家ID（从待审核列表获取，不可暴露给普通用户）"},
                    "verified": {"type": "integer", "description": "1=通过，0=拒绝"}
                },
                "required": ["merchantId", "verified"]
            }
        }
    },
    # ── 分类管理 ──
    {
        "type": "function",
        "function": {
            "name": "get_categories",
            "description": "获取全部分类列表",
            "parameters": {"type": "object", "properties": {}}
        }
    },

]


# ============================================
# AdminAgent
# ============================================
class AdminAgent(BaseAgent):
    """平台管理 Agent"""

    def __init__(self):
        super().__init__(
            system_prompt=ADMIN_SYSTEM_PROMPT,
            tools=ADMIN_TOOLS,
        )

    async def _execute_tool(self, tool_name: str, arguments: dict) -> dict:
        """
        ★ 分发层 ★

        工具函数实现在 tools/admin_tools.py，
        这里通过 ADMIN_TOOL_HANDLERS 映射表把请求分发给对应的函数。
        """
        handler = ADMIN_TOOL_HANDLERS.get(tool_name)
        if handler is None:
            return {
                "错误": f"未知工具: {tool_name}，可用工具: " + ", ".join(ADMIN_TOOL_HANDLERS.keys())
            }
        try:
            return await handler(self._current_token, arguments)
        except Exception as e:
            return {"错误": str(e)}

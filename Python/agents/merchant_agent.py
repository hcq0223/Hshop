# ============================================
# 商家 Agent —— 运营数据分析助手
# 能力: 销量概览、热销排行、待发货订单、商品诊断
# ============================================

from agents.base import BaseAgent
from services.java_api import call_java_api

# ---------- 系统提示词 ----------
MERCHANT_SYSTEM_PROMPT = """
你是 ShopAi 商城的商家运营助手，名字叫"小Ai"。

## 你的职责
1. 销售分析：今日/本周/本月销售额、订单量、客单价
2. 商品运营：热销商品排行、滞销商品提醒、补货建议
3. 订单处理：查看待发货订单、催发货提醒
4. 数据洞察：复购率、好评率、流量转化等

## 行为规范
- 用数据说话，给出具体数字和对比
- 分析时给出可执行的建议，不要只罗列数据
- 你只能访问该商家自己的店铺数据，无权查看其他商家的数据
- 不确定的数据不要编造
- 在回答时不得将任何带有'id'字样的数据直接传递给用户，只可以内部使用不得向用户展示:
  正确示例：回复"张三的店铺" ❌ 错误示例：回复"ID为 10086 的店铺"
- 不得使用顾客提供的任何id
- 除了增删改的操作需要用户授权，查询操作不需要用户授权
- token不是由用户提供的

## 工具调用规范（必须严格遵守）
- 当需要查询数据（订单、营收、商品、提现等）时，使用系统提供的工具函数，而不是自己编造数据
- 工具调用由系统自动处理，你【绝对不要】在回复文本中输出任何工具调用的结构体（例如 `{"name":"...","arguments":{...}}`、`isphere`、`action`、`tool_call` 等字样）
- 你只需要正常输出给用户看的自然语言回复即可，需要查询数据时系统会拦截并帮你执行工具
- 如果工具查询结果为空（比如暂无订单），如实告诉用户"暂无可用的数据"，而不是输出结构体

## 商城背景
- 订单状态：pending_pay(待付款) → paid(已付款) → shipped(已发货) → received(已收货) → completed(已完成)
- 商品状态：draft(草稿) / on_sale(上架) / off_sale(下架)
"""

# ---------- 商家可用工具 ----------
MERCHANT_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_withdraws",
            "description": "获取该商家的提现记录",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_weekly_revenue_history",
            "description": "查询该商家历史周营收",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_shop_info",
            "description": "获取当前商家自己的店铺信息：名称、评分、认证状态、联系方式",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_orders",
            "description": "查询本店的订单列表，可按状态筛选",
            "parameters": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["pending_pay", "paid", "shipped", "received", "completed"],
                        "description": "订单状态（可选）",
                    },
                },
            },
        },
    },
    # ── 财务与运营（只读） ──
    {
        "type": "function",
        "function": {
            "name": "get_finance_overview",
            "description": "获取本商家的资金概览：总营收、可提现余额、冻结资金",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_order_history",
            "description": "查询本店订单历史（已完成+已取消的订单）",
            "parameters": {"type": "object", "properties": {}},
        },
    },
    # ── 商品运营 ──
    {
        "type": "function",
        "function": {
            "name": "get_my_products",
            "description": "获取本商家的商品列表（含价格区间、销量、上下架状态）",
            "parameters": {"type": "object", "properties": {}},
        },
    },
]


class MerchantAgent(BaseAgent):
    """商家运营 Agent"""

    def __init__(self):
        super().__init__(
            system_prompt=MERCHANT_SYSTEM_PROMPT,
            tools=MERCHANT_TOOLS,
        )

    async def _execute_tool(self, tool_name: str, arguments: dict) -> dict:
        # ── 查自己的店铺信息 ──
        if tool_name == "get_my_shop_info":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/merchant/me",
                )
                if result.get("code") == 200 and result.get("data"):
                    shop = result["data"]
                    return {
                        "店铺名称": shop.get("shopName"),
                        "评分": shop.get("rating"),
                        "认证状态": "已认证" if shop.get("isVerified") == 1 else "待审核",
                        "联系人": shop.get("contactName"),
                        "联系电话": shop.get("contactPhone"),
                        "店铺简介": shop.get("shopDescription", ""),
                    }
                return {"错误": result.get("message", "店铺信息接口返回异常")}
            except Exception as e:
                return {"错误": f"查询店铺信息失败: {str(e)}"}

        # ── 查商户提现记录 ──
        elif tool_name == "get_withdraws":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/merchant/withdraws",
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]  #list
                    withdraws = []
                    for w in data:
                        withdraws.append({
                            "提现记录id": w.get("id"),
                            "提现商户id": w.get("merchantId"),
                            "提现金额": w.get("amount"),
                            "提现状态": w.get("status"), # pending(待处理)/approved(已通过)/completed(已完成)/rejected(已拒绝)
                            "备注": w.get("remark"),
                            "申请时间": w.get("createdAt"),
                            "处理时间": w.get("updateAt"),
                        })
                    return {"提现记录数": len(withdraws), "提现记录列表": withdraws}
                else:
                    return {"错误": result.get("message", "提现记录接口返回异常")}
            except Exception as e:
                return {"错误": f"查询提现记录失败: {str(e)}"}

        # ── 查历史周营收 ──
        elif tool_name == "get_weekly_revenue_history":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/merchant/weekly-revenue",
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    weekly_revenues = []
                    for w in data:
                        weekly_revenues.append({
                            "营收单id": w.get("id"),
                            "商户id": w.get("merchantId"),
                            "周起始日期": w.get("weekStart"),
                            "周结束日期": w.get("weekEnd"),
                            "营收金额": w.get("totalRevenue"),
                            "本周订单数": w.get("totalOrders"),
                            "归档时间": w.get("createdAt"),
                        })
                    return {"历史周营收数": data.get("total", len(weekly_revenues)), "历史周营收列表": weekly_revenues}
                else:
                    return {"错误": result.get("message", "历史周营收接口返回异常")}
            except Exception as e:
                return {"错误": f"查询历史周营收失败: {str(e)}"}

        # ── 查本店订单 ──
        elif tool_name == "get_my_orders":
            try:
                params = {"page": 1, "size": 20}
                status = arguments.get("status")
                if status:
                    params["status"] = status
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/order/merchant/orders",
                    params=params,
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    records = data.get("records", [])
                    orders = []
                    for o in records:
                        orders.append({
                            "订单号": o.get("orderNo"),
                            "金额": o.get("totalAmount"),
                            "状态": o.get("status"),
                            "时间": o.get("createTime"),
                        })
                    return {"订单数": data.get("total", len(orders)), "订单列表": orders}
                return {"错误": result.get("message", "接口返回异常")}
            except Exception as e:
                return {"错误": f"查询订单失败: {str(e)}"}

        # ── 财务概览 ──
        elif tool_name == "get_finance_overview":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/merchant/me/finance",
                )
                if result.get("code") == 200 and result.get("data"):
                    f = result["data"]
                    return {
                        "总营收": f.get("totalRevenue"),
                        "可提现余额": f.get("availableBalance"),
                        "冻结资金": f.get("frozenBalance"),
                    }
                return {"错误": result.get("message", "财务概览接口返回异常")}
            except Exception as e:
                return {"错误": f"查询财务概览失败: {str(e)}"}

        # ── 本店订单历史 ──
        elif tool_name == "get_my_order_history":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/order/merchant/history",
                )
                if result.get("code") == 200:
                    data = result.get("data") or []
                    records = data if isinstance(data, list) else data.get("records", [])
                    history = []
                    for o in records:
                        history.append({
                            "订单号": o.get("orderNo"),
                            "金额": o.get("payAmount") or o.get("totalAmount"),
                            "状态": o.get("status"),
                            "时间": o.get("createTime"),
                        })
                    return {"历史订单数": len(history), "历史订单列表": history}
                return {"错误": result.get("message", "订单历史接口返回异常")}
            except Exception as e:
                return {"错误": f"查询订单历史失败: {str(e)}"}

        # ── 本店商品列表 ──
        elif tool_name == "get_my_products":
            try:
                # 先取 merchantId（内部使用，不暴露）
                me = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/merchant/me/id",
                )
                if me.get("code") != 200 or not me.get("data"):
                    return {"错误": "获取商家信息失败"}
                merchant_id = me["data"].get("merchantId") or me["data"].get("id")

                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path=f"/product/merchant/{merchant_id}",
                )
                if result.get("code") == 200 and result.get("data"):
                    products = result["data"] if isinstance(result["data"], list) else result["data"].get("records", [])
                    product_list = []
                    for p in products:
                        product_list.append({
                            "商品ID": p.get("id"),
                            "商品名称": p.get("name"),
                            "最低价": p.get("priceMin"),
                            "最高价": p.get("priceMax"),
                            "销量": p.get("salesCount"),
                            "状态": "上架" if p.get("status") == "on_sale" else "下架",
                        })
                    return {"商品数": len(product_list), "商品列表": product_list}
                return {"错误": result.get("message", "商品列表接口返回异常")}
            except Exception as e:
                return {"错误": f"查询商品列表失败: {str(e)}"}

        else:
            return {"错误": f"未知工具: {tool_name}"}

# ============================================
# 管理员工具函数
# 每个函数接收 (token, arguments)，返回一个 dict
# （dict 会被转成 JSON 发给 LLM，key 用中文便于理解）
#
# 注意：这里只放"执行逻辑"，不涉及 LLM 调用。
# 每个函数的 token 由 AdminAgent._execute_tool 透传进来。
# ============================================

from services.java_api import call_java_api
import hashlib, secrets, time

_PENDING_CONFIRM: dict = {}
_CONFIRM_TTL = 120  # 秒


def _fp(token: str) -> str:
    """token 指纹，避免把明文 token 存进内存"""
    return hashlib.sha256((token or "").encode()).hexdigest()[:16]


# ────────────────────────────────────────────
# 仪表盘
# ────────────────────────────────────────────

async def get_dashboard(token: str, arguments: dict) -> dict:
    """平台基本统计数据：用户总数、顾客数、商户数、今日新增用户数"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/dashboard",
    )
    if result.get("code") == 200:
        return {"仪表盘数据": result["data"]}
    return {"仪表盘基本统计错误": result.get("message")}


async def get_dashboard_stats(token: str, arguments: dict) -> dict:
    """仪表盘全量统计：近7天订单/用户趋势、状态分布、Top/Bottom商家"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/dashboard/stats",
    )
    if result.get("code") == 200:
        return {"仪表盘统计": result["data"]}
    return {"仪表盘全量统计错误": result.get("message")}


# ────────────────────────────────────────────
# 顾客/商家查询
# ────────────────────────────────────────────

async def get_customer(token: str, arguments: dict) -> dict:
    """根据顾客的用户ID查询顾客详细信息"""
    result = await call_java_api(
        token=token,
        method="GET",
        path=f"/admin/customers/{arguments.get('userId')}",
    )
    if result.get("code") == 200 and result.get("data"):
        cus = result["data"]
        return {"顾客信息": {
            "顾客的用户ID": cus.get("userId"),
            "顾客id": cus.get("id"),
            "昵称": cus.get("nickname"),
            "头像": cus.get("avatarUrl"),
            "性别": cus.get("gender"),
            "默认收货地址ID": cus.get("defaultAddressId"),
        }}
    return {"查询顾客信息接口错误": result.get("message")}


async def list_customer(token: str, arguments: dict) -> dict:
    """根据顾客昵称关键词搜索顾客列表（分页）"""
    page = arguments.get("page", 1)
    size = arguments.get("size", 10)
    keyword = arguments.get("keyword")
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/customers",
        params={"page": page, "size": size, "keyword": keyword},
    )
    if result.get("code") == 200:
        data = result.get("data") or {}
        records = data.get("records", [])
        cus_list = []
        for cus in records:
            cus_list.append({
                "顾客的用户ID": cus.get("userId"),
                "顾客id": cus.get("id"),
                "昵称": cus.get("nickname"),
                "头像": cus.get("avatarUrl"),
                "性别": cus.get("gender"),
                "默认收货地址ID": cus.get("defaultAddressId"),
            })
        return {
            "总条数": data.get("total", len(cus_list)),
            "当前页": page,
            "每页条数": size,
            "顾客列表": cus_list,
        }
    return {"错误": result.get("message")}


async def get_merchant(token: str, arguments: dict) -> dict:
    """根据商户的用户ID查询商户详细信息"""
    result = await call_java_api(
        token=token,
        method="GET",
        path=f"/admin/merchants/by-user/{arguments.get('userId')}",
    )
    if result.get("code") == 200 and result.get("data"):
        m = result["data"]
        return {"商户信息": {
            "商户的用户ID": m.get("userId"),
            "商户id": m.get("id"),
            "店铺名称": m.get("shopName"),
            "店铺logo": m.get("logoUrl"),
            "联系人": m.get("contactName"),
            "联系电话": m.get("contactPhone"),
            "店铺简介": m.get("shopDescription"),
            "评分": m.get("rating"),
            "是否认证": m.get("isVerified"),
        }}
    return {"错误": result.get("message")}


# ────────────────────────────────────────────
# 商家审核相关
# ────────────────────────────────────────────

async def get_pending_merchants(token: str, arguments: dict) -> dict:
    """查询待审核商家列表（含实名信息）"""
    page = arguments.get("page", 1)
    size = arguments.get("size", 10)
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/merchants/pending",
        params={"page": page, "size": size},
    )
    if result.get("code") == 200:
        data = result.get("data") or {}
        records = data.get("records", [])
        m_list = []
        for m in records:
            rn = m.get("realName") or {}
            m_list.append({
                "商户的用户ID": m.get("userId"),
                "商户id": m.get("id"),
                "店铺名称": m.get("shopName"),
                "店铺logo": m.get("logoUrl"),
                "联系人": m.get("contactName"),
                "联系电话": m.get("contactPhone"),
                "店铺简介": m.get("shopDescription"),
                "评分": m.get("rating"),
                "实名姓名": rn.get("realName"),
                "身份证号": rn.get("cardId"),
                "身份证照片": rn.get("cardUrl"),
            })
        return {
            "总条数": data.get("total", len(m_list)),
            "当前页": page,
            "每页条数": size,
            "待审核商家列表": m_list,
        }
    return {"错误": result.get("message")}


async def get_modifications(token: str, arguments: dict) -> dict:
    """查询店铺信息修改申请"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/merchants/modifications",
        params={"status": arguments.get("status")},
    )
    if result.get("code") == 200 and result.get("data"):
        data = result.get("data")
        mod = []
        for d in data:
            mod.append({
                "申请id": d.get("id"),
                "商户的用户ID": d.get("merchantId"),
                "商家名称": d.get("shopName"),
                "修改类型": d.get("type"),
                "申请内容": d.get("changeDetail"),
                "申请状态": d.get("status"),
                "申请时间": d.get("createdAt"),
            })
        return {
            "总条数": len(mod),
            "店铺修改申请列表": mod,
        }
    return {"错误": result.get("message")}


async def get_pending_withdraws(token: str, arguments: dict) -> dict:
    """查询待审核的提现申请列表"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/merchants/withdraws/pending",
    )
    if result.get("code") == 200 and result.get("data"):
        data = result.get("data")
        withdraws = []
        for d in data:
            withdraws.append({
                "提现id": d.get("id"),
                "商户ID": d.get("merchantId"),
                "提现金额": d.get("amount"),
                "备注": d.get("remark"),
                "申请时间": d.get("createdAt"),
            })
        return {
            "总条数": len(withdraws),
            "待审核提现列表": withdraws,
        }
    return {"错误": result.get("message")}


# async def verify_merchant(token: str, arguments: dict) -> dict:
#     """审核商家认证（管理员本职，调用方需先确认后执行）"""
#     merchant_id = arguments.get("merchantId")
#     result = await call_java_api(
#         token=token,
#         method="PUT",
#         path=f"/admin/merchants/{merchant_id}/verify",
#         params={
#             "verified": arguments.get("verified"),
#         },
#     )
#     if result.get("code") == 200:
#         return {"成功": result.get("message", "商家审核完成")}
#     return {"错误": result.get("message")}

async def verify_merchant(token: str, arguments: dict) -> dict:
    merchant_id = arguments.get("merchantId")
    verified = arguments.get("verified")
    code = arguments.get("confirmCode")

    # 第一阶段：只预览，绝不执行
    if not code:
        code = secrets.token_hex(3).upper()
        _PENDING_CONFIRM[code] = {
            "fp": _fp(token),
            "tool": "verify_merchant",
            "args": {"merchantId": merchant_id, "verified": verified},
            "expire": time.time() + _CONFIRM_TTL,
        }
        return {
            "需要确认": True,
            "确认码": code,
            "待执行操作": f"{'审核通过' if verified == 1 else '审核拒绝'}商家 {merchant_id}",
            "提示": "请向管理员复述以上内容；管理员明确同意后，带上 confirmCode 再调用一次才会真正执行",
        }

    # 第二阶段：校验确认码
    rec = _PENDING_CONFIRM.pop(code, None)
    if not rec:
        return {"错误": "确认码无效或已使用，请重新发起确认"}
    if rec["expire"] < time.time():
        return {"错误": "确认码已过期，请重新发起确认"}
    if rec["fp"] != _fp(token):
        return {"错误": "确认码不属于当前操作人"}
    if rec["tool"] != "verify_merchant" or rec["args"] != {"merchantId": merchant_id, "verified": verified}:
        return {"错误": "确认码与本次操作不匹配"}

    result = await call_java_api(
        token=token, method="PUT",
        path=f"/admin/merchants/{merchant_id}/verify",
        params={"verified": verified},
    )
    if result.get("code") == 200:
        return {"成功": result.get("message", "商家审核完成")}
    return {"错误": result.get("message")}


# ────────────────────────────────────────────
# 订单监管
# ────────────────────────────────────────────

async def get_all_orders(token: str, arguments: dict) -> dict:
    """分页查询全平台订单，可按状态筛选"""
    try:
        page = max(1, int(arguments.get("page") or 1))
    except (TypeError, ValueError):
        page = 1
    try:
        size = int(arguments.get("size") or 20)
    except (TypeError, ValueError):
        size = 20
    size = max(1, min(size, 50))

    params = {"page": page, "size": size}
    status = arguments.get("status")
    if status:
        params["status"] = status

    result = await call_java_api(
        token=token,
        method="GET",
        path="/order/admin/orders",
        params=params,
    )
    if result.get("code") != 200:
        return {"错误": result.get("message", "查询全平台订单失败")}

    # 注意：该接口返回 List<Order> 而非 MyBatis-Plus 的 Page；
    # 且带 status 筛选时后端会忽略 page/size 返回全量，所以这里统一按 list 处理再本地截断。
    data = result.get("data")
    records = data if isinstance(data, list) else (data or {}).get("records", [])

    order_list = [
        {
            "订单号": o.get("orderNo"),
            "金额": o.get("payAmount") or o.get("totalAmount"),
            "状态": o.get("status"),
            "下单时间": o.get("createTime"),
            "顾客ID": o.get("customerId"),
            "商家ID": o.get("merchantId"),
        }
        for o in records[:size]
    ]
    return {
        "返回条数": len(order_list),
        "订单列表": order_list,
        "说明": f"最多返回 {size} 条；如结果被截断，请用 status 筛选或翻页缩小范围",
    }


async def get_all_order_history(token: str, arguments: dict) -> dict:
    """查询全平台订单历史（已完成+已取消）"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/order/admin/history",
    )
    if result.get("code") == 200:
        data = result.get("data") or []
        records = data if isinstance(data, list) else data.get("records", [])
        total = len(records)
        return {
            "历史订单总数": total,
            "已返回条数": min(total, 50),
            "历史订单列表": records[:50],
        }
    return {"错误": result.get("message", "查询全平台订单历史失败")}


# ────────────────────────────────────────────
# 用户管理
# ────────────────────────────────────────────

async def get_user(token: str, arguments: dict) -> dict:
    """按用户ID查询用户详情（通用）"""
    result = await call_java_api(
        token=token,
        method="GET",
        path=f"/admin/users/{arguments.get('id')}",
    )
    if result.get("code") == 200 and result.get("data"):
        u = result["data"]
        return {"用户信息": {
            "用户ID": u.get("id"),
            "用户名": u.get("username"),
            "手机号": u.get("phone"),
            "邮箱": u.get("email"),
            "角色": u.get("role"),
            "状态": "正常" if u.get("status") == 1 else "禁用",
        }}
    return {"错误": result.get("message", "查询用户详情失败")}


async def list_users(token: str, arguments: dict) -> dict:
    """分页查询用户列表，可按角色、状态、关键词筛选"""
    params = {
        "page": arguments.get("page", 1),
        "size": arguments.get("size", 10),
    }
    if arguments.get("keyword"):
        params["keyword"] = arguments["keyword"]
    if arguments.get("role"):
        params["role"] = arguments["role"]
    if arguments.get("status") is not None:
        params["status"] = arguments["status"]
    result = await call_java_api(
        token=token,
        method="GET",
        path="/admin/users",
        params=params,
    )
    if result.get("code") == 200 and result.get("data"):
        data = result["data"]
        records = data.get("records", [])
        user_list = []
        for u in records:
            user_list.append({
                "用户ID": u.get("id"),
                "用户名": u.get("username"),
                "手机号": u.get("phone"),
                "角色": u.get("role"),
                "状态": "正常" if u.get("status") == 1 else "禁用",
            })
        return {"总条数": data.get("total", len(user_list)), "用户列表": user_list}
    return {"错误": result.get("message", "查询用户列表失败")}


# ────────────────────────────────────────────
# 通用
# ────────────────────────────────────────────

async def get_categories(token: str, arguments: dict) -> dict:
    """获取全部分类列表"""
    result = await call_java_api(
        token=token,
        method="GET",
        path="/category/all",
    )
    if result.get("code") == 200:
        return {"全部分类": result["data"]}
    return {"错误": result.get("message", "查询分类失败")}


# ────────────────────────────────────────────
# 工具名 → 函数 映射表
# AdminAgent._execute_tool 靠这个表做分发，不再写一长串 if/elif
# ────────────────────────────────────────────

ADMIN_TOOL_HANDLERS = {
    "get_dashboard": get_dashboard,
    "get_dashboard_stats": get_dashboard_stats,
    "get_customer": get_customer,
    "list_customer": list_customer,
    "get_merchant": get_merchant,
    "get_pending_merchants": get_pending_merchants,
    "get_modifications": get_modifications,
    "get_pending_withdraws": get_pending_withdraws,
    "verify_merchant": verify_merchant,
    "get_all_orders": get_all_orders,
    "get_all_order_history": get_all_order_history,
    "get_user": get_user,
    "list_users": list_users,
    "get_categories": get_categories,
}

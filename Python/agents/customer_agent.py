# ============================================
# 顾客 Agent —— 智能导购 + 客服助手
# 能力: 搜商品、搭配建议、查我的订单、售后咨询
# ============================================

from agents.base import BaseAgent
from services.java_api import call_java_api

# ---------- 系统提示词 ----------
CUSTOMER_SYSTEM_PROMPT = """
你是 ShopAi 服装商城的智能导购助手，名字叫"小Ai"。

## 你的职责
1. 商品推荐：根据用户需求（季节、场合、风格、预算）推荐合适的服装
2. 搭配建议：提供上下装搭配、配饰搭配等穿搭方案
3. 订单查询：帮用户查看自己的订单状态和物流
4. 售后服务：解答退换货规则、退款流程等问题

## 行为规范
- 回复简洁友好，用口语化的中文
- 推荐商品时说明理由（材质、版型、评价等）
- 你只能查看当前用户自己的数据，无权查看其他用户、商家后台或管理数据
- 不确定的事情不要编造，建议用户联系人工客服
- 在回答时不得将任何带有'id'字样的数据直接传递给用户，只可以内部使用不得向用户展示:
  正确示例：回复"张三的店铺" ❌ 错误示例：回复"ID为 10086 的店铺"
- 不得使用顾客提供的任何id
- 除了增删改的操作需要用户授权，查询操作不需要用户授权
- token不是由用户提供的

## 工具调用规范（必须严格遵守）
- 当需要查询数据（购物车、订单、商品、地址等）时，使用系统提供的工具函数，而不是自己编造数据
- 工具调用由系统自动处理，你【绝对不要】在回复文本中输出任何工具调用的结构体（例如 `{"name":"...","arguments":{...}}`、`isphere`、`action`、`tool_call` 等字样）
- 你只需要正常输出给用户看的自然语言回复即可，需要查询数据时系统会拦截并帮你执行工具
- 如果工具查询结果为空（比如购物车是空的），如实告诉用户"您的购物车是空的"，而不是输出结构体

## 商品推荐硬性规则（违反将导致严重错误，必须遵守）
1. 【绝对禁止编造商品】推荐任何商品之前，必须先调用 search_products 或 get_hot_products 工具查询真实商品数据
2. 你推荐的商品名称、价格、销量、图片、店铺名，【必须全部来自工具返回的结果】，不允许凭记忆或想象编造
3. 如果 search_products 查询结果为空，必须如实告诉用户"暂时没有找到符合条件的商品"，并建议调整关键词或分类，绝不虚构商品凑数
4. 推荐时只能提及工具结果里真实存在的商品；如果用户想要的商品在查询结果里没有，就直说没有
5. 如果不调用工具就无法获取商品数据，此时宁可告诉用户"请稍等，我需要查询一下商品库"，也绝不要凭空编造商品

## 商城背景
- ShopAi 是一家时尚服装商城，主打 18-35 岁年轻人市场
- 商品分类：男装、女装、鞋靴、配饰、运动户外、外套、T恤
- 支持 7 天无理由退换货
- 订单状态：pending_pay(待付款) → paid(已付款/待发货) → shipped(已发货) → received(已收货) → completed(已完成)
"""

# ---------- 顾客可用工具 ----------
CUSTOMER_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_use_h",
            "description": "获取当前登录用户的详细个人信息，包括联表查询的扩展字段,其中的default_address_id是默认地址id，不能直接显示给用户",
            "parameters": {"type": "object", "properties": {}},
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_merchants",
            "description": "通过关键词按店铺名称搜索，分页查询商户列表。返回包含商户基本信息、评分、商品数量等数据。",
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
                        "description": "店铺名称搜索关键字（模糊匹配）"
                    }
                },
                "required": ["keyword"]
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_merchant",
            "description": "根据商户ID查询商户公开信息。",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "商户ID"}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_addresses_by_customer_id",
            "description": "根据顾客的token获取顾客的地址信息列表。在做删改操作前，使用此，可拿到所需数据",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_address_by_id",
            "description": "根据token获取默认地址或首个地址（没设默认地址时）信息。",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "add_address",
            "description": "为当前登录的顾客新增一条收货地址",
            "parameters": {
                "type": "object",
                "properties": {
                    "receiverName": {"type": "string", "description": "收货人姓名"},
                    "receiverPhone": {"type": "string", "description": "收货人电话"},
                    "province": {"type": "string", "description": "省"},
                    "city": {"type": "string", "description": "市"},
                    "district": {"type": "string", "description": "区/县"},
                    "detailAddress": {"type": "string", "description": "详细地址"},
                    "isDefault": {"type": "integer", "description": "是否默认地址，1=默认，0=否，默认为0"}
                },
                "required": ["receiverName", "receiverPhone", "province", "city", "district", "detailAddress"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "update_address",
            "description": "为当前登录的顾客更新地址信息，找到的原地址信息要给顾客确认（id字段不可暴露），顾客正式确认后才可执行",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string",
                           "description": "地址id（不是由用户提供，你要先查看用户的地址列表，在对比用户的信息找出来的，绝对不可暴露给用户）"},
                    "receiverName": {"type": "string", "description": "收货人姓名"},
                    "receiverPhone": {"type": "string", "description": "收货人电话"},
                    "province": {"type": "string", "description": "省"},
                    "city": {"type": "string", "description": "市"},
                    "district": {"type": "string", "description": "区/县"},
                    "detailAddress": {"type": "string", "description": "详细地址"},
                    "isDefault": {"type": "integer", "description": "是否默认地址，1=默认，0=否，默认为0"}
                },
                "required": ["id", "receiverName", "receiverPhone", "province", "city", "district", "detailAddress"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "delete_address",
            "description": "为当前登录的顾客删除地址信息，找到的原地址信息要给顾客确认（id字段不可暴露），顾客正式确认后才可执行",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string",
                           "description": "地址id（不是由用户提供，你要先查看用户的地址列表，在对比用户的信息找出来的，绝对不可暴露给用户）"}
                },
                "required": ["id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_cart_list",
            "description": "获取当前用户的购物车列表（联查 SKU 和商品信息）",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    # ── 商品浏览（导购核心） ──
    {
        "type": "function",
        "function": {
            "name": "search_products",
            "description": "分页搜索商品，支持按关键词、分类、价格区间筛选",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {"type": "integer", "description": "页码，默认1", "default": 1},
                    "size": {"type": "integer", "description": "每页条数，默认10", "default": 10},
                    "keyword": {"type": "string", "description": "商品名称关键词（模糊匹配）"},
                    "categoryId": {"type": "integer", "description": "分类ID"},
                    "minPrice": {"type": "number", "description": "最低价"},
                    "maxPrice": {"type": "number", "description": "最高价"}
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_product_detail",
            "description": "根据商品ID查询商品详情（含SKU规格列表、图片、描述等）",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "商品ID"}
                },
                "required": ["id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_hot_products",
            "description": "获取热销商品列表",
            "parameters": {
                "type": "object",
                "properties": {
                    "limit": {"type": "integer", "description": "返回条数，默认10", "default": 10}
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_categories",
            "description": "获取全部分类列表（含顶级分类和子分类）",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    # ── 我的订单（只读） ──
    {
        "type": "function",
        "function": {
            "name": "get_my_orders",
            "description": "分页查询当前顾客自己的订单列表，可按状态筛选（pending_pay/paid/shipped/received/completed/cancelled）",
            "parameters": {
                "type": "object",
                "properties": {
                    "page": {"type": "integer", "description": "页码，默认1", "default": 1},
                    "size": {"type": "integer", "description": "每页条数，默认10", "default": 10},
                    "status": {"type": "string", "description": "订单状态（可选）"}
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_my_order_history",
            "description": "查询当前顾客的订单历史（已完成+已取消的订单）",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    # ── 购物车操作（自己的购物车） ──
    {
        "type": "function",
        "function": {
            "name": "add_to_cart",
            "description": "将指定SKU的商品加入当前顾客的购物车（已存在则累加数量）",
            "parameters": {
                "type": "object",
                "properties": {
                    "skuId": {"type": "string", "description": "SKU ID（从商品详情或购物车列表获取）"},
                    "quantity": {"type": "integer", "description": "数量，默认1", "default": 1}
                },
                "required": ["skuId"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "update_cart_quantity",
            "description": "更新购物车中某条目的数量",
            "parameters": {
                "type": "object",
                "properties": {
                    "cartId": {"type": "string", "description": "购物车条目ID（从购物车列表获取，不可暴露给用户）"},
                    "quantity": {"type": "integer", "description": "新的数量"}
                },
                "required": ["cartId", "quantity"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "update_cart_selected",
            "description": "更新购物车中某条目的选中状态（结算时用）",
            "parameters": {
                "type": "object",
                "properties": {
                    "cartId": {"type": "string", "description": "购物车条目ID（从购物车列表获取，不可暴露给用户）"},
                    "selected": {"type": "integer", "description": "1=选中，0=取消"}
                },
                "required": ["cartId", "selected"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "delete_cart_item",
            "description": "将指定SKU的商品从购物车移除",
            "parameters": {
                "type": "object",
                "properties": {
                    "skuId": {"type": "string", "description": "SKU ID（从购物车列表获取）"}
                },
                "required": ["skuId"]
            }
        }
    }
]


class CustomerAgent(BaseAgent):
    """顾客导购 Agent"""

    def __init__(self):
        super().__init__(
            system_prompt=CUSTOMER_SYSTEM_PROMPT,
            tools=CUSTOMER_TOOLS,
        )

    async def _execute_tool(self, tool_name: str, arguments: dict) -> dict:
        if tool_name == "get_use_h":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/customer/me/detail",
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    gender_map = {"male": "女", "female": "男", "other": "其他"}
                    raw_gender = data.get("gender")
                    gender_display = gender_map.get(raw_gender, "未知")
                    return {
                        "用户名": data.get("username"),
                        "昵称": data.get("nickname"),
                        "性别": gender_display,
                        "默认地址id": data.get("defaultAddressId"),
                        "手机号": data.get("phone"),
                        "邮箱": data.get("email"),
                    }
                else:
                    return {"错误": result.get("message", "查询个人信息失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        elif tool_name == "search_merchants":
            try:
                page = arguments.get("page", 1)
                size = arguments.get("size", 10)
                keyword = arguments.get("keyword")
                params = {"page": page, "size": size, "keyword": keyword}
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/search/merchant",
                    params=params,
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    records = data.get("records", [])
                    merchant_list = []
                    for m in records:
                        merchant_list.append({
                            "商户ID": m.get("id"),
                            "店铺名称": m.get("shopName"),
                            "联系人": m.get("contactName"),
                            "联系电话": m.get("contactPhone"),
                            "店铺评分": m.get("rating", 0),
                            "认证状态": "已认证" if m.get("isVerified") == 1 else "未认证",
                            "店铺简介": m.get("shopDescription", "")
                        })
                    return {
                        "当前页": page,
                        "每页条数": size,
                        "商户列表": merchant_list
                    }
                else:
                    return {"错误": result.get("message", "查询商户列表失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        elif tool_name == "get_merchant":
            try:
                merchant_id = arguments.get("id")
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path=f"/merchant/{merchant_id}",
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    return {
                        "店铺名称": data.get("shopName"),
                        "联系人": data.get("contactName"),
                        "联系电话": data.get("contactPhone"),
                        "店铺评分": data.get("rating", 0),
                        "认证状态": "已认证" if data.get("isVerified") == 1 else "未认证",
                        "店铺简介": data.get("shopDescription", "")
                    }
                else:
                    return {"错误": result.get("message", "查询商户信息失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 获取当前用户的所有地址（列表）
        elif tool_name == "get_addresses_by_customer_id":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/address/addressList",
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    addresses = []
                    for address in data:
                        addresses.append({
                            "地址ID": address.get("id"),
                            "顾客ID": address.get("customerId"),
                            "收货人": address.get("receiverName"),
                            "手机号": address.get("receiverPhone"),
                            "省": address.get("province"),
                            "市": address.get("city"),
                            "区": address.get("district"),
                            "详细地址": address.get("detailAddress"),
                            "是否默认": "是" if address.get("isDefault") == 1 else "否"
                        })
                    return {
                        "地址数量": len(addresses),
                        "地址列表": addresses
                    }
                return {"错误": result.get("message", "查询地址列表信息失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 获取当前用户的默认地址或首个地址
        elif tool_name == "get_address_by_id":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/address/addressDetail",
                )
                if result.get("code") == 200:
                    return {"地址详情": result["data"]}
                return {"错误": result.get("message", "获取默认地址或首个地址详情失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 添加地址
        elif tool_name == "add_address":
            try:
                body = {
                    "receiverName": arguments.get("receiverName"),
                    "receiverPhone": arguments.get("receiverPhone"),
                    "province": arguments.get("province"),
                    "city": arguments.get("city"),
                    "district": arguments.get("district"),
                    "detailAddress": arguments.get("detailAddress"),
                    "isDefault": arguments.get("isDefault", 0),
                }
                result = await call_java_api(
                    token=self._current_token,
                    method="POST",
                    path="/address/addAddress",
                    json_body=body,
                )
                if result.get("code") == 200:
                    return {"成功": "地址添加成功"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 更新地址
        elif tool_name == "update_address":
            try:
                body = {
                    "id": arguments.get("id"),
                    "receiverName": arguments.get("receiverName"),
                    "receiverPhone": arguments.get("receiverPhone"),
                    "province": arguments.get("province"),
                    "city": arguments.get("city"),
                    "district": arguments.get("district"),
                    "detailAddress": arguments.get("detailAddress"),
                    "isDefault": arguments.get("isDefault", 0),
                }
                result = await call_java_api(
                    token=self._current_token,
                    method="PUT",
                    path="/address/updateAddress",
                    json_body=body,
                )
                if result.get("code") == 200:
                    return {"成功": "地址更新成功"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 删除地址
        elif tool_name == "delete_address":
            try:
                body = {
                    "id": arguments.get("id"),
                }
                result = await call_java_api(
                    token=self._current_token,
                    method="DELETE",
                    path="/address/deleteAddress",
                    json_body=body,
                )
                if result.get("code") == 200:
                    return {"成功": "地址删除成功"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 获取购物车列表
        elif tool_name == "get_cart_list":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/cart/list",
                )
                if result.get("code") == 200 and isinstance(result.get("data"), list):
                    cart = result["data"]
                    items = []
                    for c in cart:
                        sku = c.get("sku") or {}
                        product = c.get("product") or {}
                        items.append({
                            "购物车条目ID": c.get("id"),
                            "数量": c.get("quantity"),
                            "是否选中": "是" if c.get("selected") == 1 else "否",
                            "颜色": sku.get("color"),
                            "尺码": sku.get("size"),
                            "单价": sku.get("price"),
                            "SKU图片": sku.get("imageUrl"),
                            "商品名称": product.get("name"),
                            "商品主图": product.get("mainImage"),
                            "商品状态": product.get("status"),
                        })
                    return {"购物车商品数": len(items), "购物车列表": items}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 搜索商品
        elif tool_name == "search_products":
            try:
                params = {
                    "page": arguments.get("page", 1),
                    "size": arguments.get("size", 10),
                }
                if arguments.get("keyword"):
                    params["keyword"] = arguments["keyword"]
                if arguments.get("categoryId"):
                    params["categoryId"] = arguments["categoryId"]
                if arguments.get("minPrice") is not None:
                    params["minPrice"] = arguments["minPrice"]
                if arguments.get("maxPrice") is not None:
                    params["maxPrice"] = arguments["maxPrice"]
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/search/product",
                    params=params,
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    records = data.get("records", [])
                    product_list = []
                    for p in records:
                        product_list.append({
                            "商品ID": p.get("id"),
                            "商品名称": p.get("name"),
                            "主图": p.get("mainImage"),
                            "最低价": p.get("priceMin"),
                            "最高价": p.get("priceMax"),
                            "销量": p.get("salesCount"),
                            "状态": p.get("status"),
                            "店铺名": p.get("shopName"),
                        })
                    return {"总条数": data.get("total", len(product_list)), "商品列表": product_list}
                return {"错误": result.get("message", "搜索商品失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 商品详情
        elif tool_name == "get_product_detail":
            try:
                pid = arguments.get("id")
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path=f"/product/{pid}",
                )
                if result.get("code") == 200 and result.get("data"):
                    p = result["data"]
                    skus = []
                    for s in (p.get("skuList") or []):
                        skus.append({
                            "SKU ID": s.get("id"),
                            "颜色": s.get("color"),
                            "尺码": s.get("size"),
                            "价格": s.get("price"),
                            "库存": s.get("stock"),
                            "图片": s.get("imageUrl"),
                        })
                    return {
                        "商品ID": p.get("id"),
                        "商品名称": p.get("name"),
                        "描述": p.get("description"),
                        "主图": p.get("mainImage"),
                        "价格区间": [p.get("priceMin"), p.get("priceMax")],
                        "状态": p.get("status"),
                        "店铺名": p.get("shopName"),
                        "SKU列表": skus,
                    }
                return {"错误": result.get("message", "查询商品详情失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 热销商品
        elif tool_name == "get_hot_products":
            try:
                limit = arguments.get("limit", 10)
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/product/hot",
                    params={"limit": limit},
                )
                if result.get("code") == 200 and result.get("data"):
                    hot_list = []
                    for p in result["data"]:
                        hot_list.append({
                            "商品ID": p.get("id"),
                            "商品名称": p.get("name"),
                            "主图": p.get("mainImage"),
                            "最低价": p.get("priceMin"),
                            "最高价": p.get("priceMax"),
                            "销量": p.get("salesCount"),
                        })
                    return {"热销商品": hot_list}
                return {"错误": result.get("message", "查询热销商品失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 分类列表
        elif tool_name == "get_categories":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/category/all",
                )
                if result.get("code") == 200:
                    return {"全部分类": result["data"]}
                return {"错误": result.get("message", "查询分类失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 我的订单
        elif tool_name == "get_my_orders":
            try:
                params = {
                    "page": arguments.get("page", 1),
                    "size": arguments.get("size", 10),
                }
                if arguments.get("status"):
                    params["status"] = arguments["status"]
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/order/customer/orders",
                    params=params,
                )
                if result.get("code") == 200 and result.get("data"):
                    data = result["data"]
                    records = data.get("records", []) if isinstance(data, dict) else data
                    order_list = []
                    for o in records:
                        order_list.append({
                            "订单号": o.get("orderNo"),
                            "金额": o.get("payAmount") or o.get("totalAmount"),
                            "状态": o.get("status"),
                            "时间": o.get("createTime"),
                        })
                    total = data.get("total", len(order_list)) if isinstance(data, dict) else len(order_list)
                    return {"订单数": total, "订单列表": order_list}
                return {"错误": result.get("message", "查询订单失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 我的订单历史
        elif tool_name == "get_my_order_history":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="GET",
                    path="/order/customer/history",
                )
                if result.get("code") == 200:
                    data = result.get("data") or []
                    return {"历史订单": data if isinstance(data, list) else data.get("records", [])}
                return {"错误": result.get("message", "查询订单历史失败")}
            except Exception as e:
                return {"错误": f"调用接口异常: {str(e)}"}

        # 加入购物车
        elif tool_name == "add_to_cart":
            try:
                params = {
                    "skuId": arguments.get("skuId"),
                    "quantity": arguments.get("quantity", 1),
                }
                result = await call_java_api(
                    token=self._current_token,
                    method="POST",
                    path="/cart/add",
                    params=params,
                )
                if result.get("code") == 200:
                    return {"成功": "已添加到购物车"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 更新购物车数量
        elif tool_name == "update_cart_quantity":
            try:
                cart_id = arguments.get("cartId")
                result = await call_java_api(
                    token=self._current_token,
                    method="PUT",
                    path=f"/cart/updateQuantity/{cart_id}",
                    params={"quantity": arguments.get("quantity")},
                )
                if result.get("code") == 200:
                    return {"成功": "数量更新成功"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 更新购物车选中状态
        elif tool_name == "update_cart_selected":
            try:
                cart_id = arguments.get("cartId")
                result = await call_java_api(
                    token=self._current_token,
                    method="PUT",
                    path=f"/cart/select/{cart_id}",
                    params={"selected": arguments.get("selected")},
                )
                if result.get("code") == 200:
                    return {"成功": "选中状态更新成功"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        # 从购物车删除（按SKU）
        elif tool_name == "delete_cart_item":
            try:
                result = await call_java_api(
                    token=self._current_token,
                    method="DELETE",
                    path="/cart/deleteBySku",
                    params={"skuId": arguments.get("skuId")},
                )
                if result.get("code") == 200:
                    return {"成功": "商品已从购物车移除"}
                return {"错误": result.get("message")}
            except Exception as e:
                return {"错误": str(e)}

        else:
            return {"错误": f"未知工具: {tool_name}"}

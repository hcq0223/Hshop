# ============================================
# Agent 基类
# 所有角色 Agent 的公共父类，封装 LLM 调用、工具分发、流式输出
# ============================================

import json
import re
from abc import ABC, abstractmethod
from typing import AsyncGenerator, Optional, List
from openai import AsyncOpenAI
from config import settings

import time


# ============================================
# 会话级工具结果记忆（跨请求保留）
# 结构: {user_id: [{"time": ts, "tool": name, "args": {...}, "result": {...}}, ...]}
# 目的: 上一轮工具返回的结构化数据（cartId/skuId/orderId 等）在本轮仍可被
#       模型引用，以便"删除那件商品"这类后续操作能拿到标识。
# 注意: 这些数据只注入给模型内部使用，不得展示给用户。
# ============================================
_AGENT_SESSIONS: dict = {}
_MAX_SESSION_TOOLS = 10   # 每用户最多保留最近 N 条工具结果
_MAX_SESSION_AGE = 1800   # 超过 30 分钟的工具结果视为过期


class BaseAgent(ABC):
    """
    Agent 基类

    每个子类只需要做两件事：
    1. __init__ 里指定 system_prompt（告诉 AI 它是谁、能做什么）
    2. _execute_tool 里写 if/elif 把工具名映射到实际函数

    对话流程（以 "查待审核商家" 为例）：
    用户说: "有哪些待审核的商家"
      → LLM 回复: 我需要调用 get_pending_merchants 工具
      → base.py 自动调用 self._execute_tool("get_pending_merchants", {})
      → 你的函数返回 {"pending": [...]}
      → base.py 把结果发回 LLM
      → LLM 用自然语言回复: "目前有3家商家待审核：张三的店、李四的店..."
    """

    # 商品查询类工具：结果易被模型编造，需要强制注入 + 回复校验
    PRODUCT_TOOLS = {
        "search_products",
        "get_hot_products",
        "get_product_detail",
    }

    def __init__(self, system_prompt: str, tools: list):
        self.client = AsyncOpenAI(
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_BASE_URL,
        )
        self.system_prompt = system_prompt
        self.tools = tools
        self.model = settings.LLM_MODEL
        # token 在每次对话时注入，方便工具函数里调 Java API
        self._current_token: str = ""

    # ============================================
    # 会话级工具结果记忆
    # ============================================
    @staticmethod
    def _remember_tool_result(user_id, tool_name, arguments, result):
        """记录一次工具调用结果到会话记忆"""
        if user_id is None:
            return
        now = time.time()
        entry = {
            "time": now,
            "tool": tool_name,
            "args": arguments or {},
            "result": result or {},
        }
        sess = _AGENT_SESSIONS.setdefault(user_id, [])
        sess.append(entry)
        # 裁剪：只保留最近 N 条
        if len(sess) > _MAX_SESSION_TOOLS:
            del sess[: len(sess) - _MAX_SESSION_TOOLS]

    @staticmethod
    def _recent_tool_results(user_id, max_age=_MAX_SESSION_AGE, limit=_MAX_SESSION_TOOLS):
        """获取用户近期工具结果（按时间倒序，最多 limit 条）"""
        if user_id is None:
            return []
        sess = _AGENT_SESSIONS.get(user_id) or []
        now = time.time()
        recent = [e for e in sess if now - e["time"] <= max_age]
        return recent[-limit:] if recent else []

    @staticmethod
    def _build_session_context(user_id) -> str:
        """把近期工具结果拼成一段注入上下文的摘要（仅供模型内部引用，不含展示指令）"""
        recent = BaseAgent._recent_tool_results(user_id)
        if not recent:
            return ""
        lines = ["以下是本会话之前查询到的数据，你可以在后续操作中引用其中的 ID（但不要向用户展示 ID）："]
        for e in recent:
            tool = e.get("tool", "")
            result = e.get("result", {})
            lines.append(f"- 工具 {tool} 返回: {json.dumps(result, ensure_ascii=False)[:300]}")
        return "\n".join(lines)

    def _build_messages(
        self, user_id: int, message: str, history: Optional[list] = None
    ) -> list:
        """构建发给 LLM 的完整消息列表：[system, ...历史, user]"""
        messages = [{"role": "system", "content": self.system_prompt}]
        if history:
            for msg in history:
                messages.append({
                    "role": msg.get("role", "user"),
                    "content": msg.get("content", ""),
                })
        messages.append({"role": "user", "content": message})
        return messages

    # ============================================
    # 普通对话（非流式）
    # ============================================
    async def chat(
        self, user_id: int, message: str, token: str = "",
        history: Optional[list] = None,
    ) -> str:
        self._current_token = token
        messages = self._build_messages(user_id, message, history)
        kw = {
            "model": self.model,
            "messages": messages,
        }
        if self.tools:
            kw["tools"] = self.tools
            kw["tool_choice"] = "auto"
        response = await self.client.chat.completions.create(**kw)
        return await self._process_response(response, messages)

    # ============================================
    # 工具调用文本解析（兼容 Ollama/qwen 等本地模型）
    # ============================================
    @staticmethod
    def _parse_text_tool_calls(text: str):
        """
        从一段文本中解析出工具调用结构体。

        返回:
            clean_text  剥离工具调用后的剩余正文
            tool_calls  [{"name": str, "arguments": dict}, ...] 解析出的工具调用
        """
        if not text:
            return text, []

        tool_calls = []
        pattern = re.compile(
            r"(?:isphere|action|tool_call|invoke|\[|\()?"
            r'(\{"name"\s*:\s*"[^"]+"\s*,\s*"arguments"\s*:\s*\{.*?\}\})',
            re.DOTALL,
        )

        def repl(m):
            raw = m.group(1)
            try:
                data = json.loads(raw)
                if isinstance(data, dict) and data.get("name"):
                    tool_calls.append(data)
                    return ""
            except (json.JSONDecodeError, TypeError):
                pass
            return raw

        clean_text = pattern.sub(repl, text)
        return clean_text, tool_calls

    # ============================================
    # 强制检索注入：从工具结果中提取真实商品清单
    # ============================================
    @staticmethod
    def _extract_product_names(result) -> List[str]:
        """
        从商品查询工具的结果中提取所有真实商品名，用于后续校验。

        result 可能的结构：
            {"商品列表": [{"商品名称": "xx", ...}, ...]}
            {"热销商品": [{"商品名称": "xx", ...}, ...]}
            [{"name": "xx", ...}]  (部分接口直接返回数组)
        """
        names = []

        def collect(items):
            for it in items or []:
                if not isinstance(it, dict):
                    continue
                # 中文键
                n = it.get("商品名称") or it.get("名称") or it.get("name")
                if n:
                    names.append(str(n))

        if isinstance(result, dict):
            # 兼容不同返回键
            for key in ("商品列表", "热销商品", "records", "list", "data"):
                val = result.get(key)
                if isinstance(val, list):
                    collect(val)
            # 也尝试直接取 "商品名称" 等
        elif isinstance(result, list):
            collect(result)

        return names

    # ============================================
    # 回复校验：检测模型回复中出现的商品名是否都在清单内
    # ============================================
    @staticmethod
    def _verify_product_names(reply: str, real_names: List[str]) -> Optional[str]:
        """
        检查回复中提到的商品名是否都在真实清单内。
        返回需要追加的纠错提示（无则返回 None）。
        """
        if not reply or not real_names:
            return None

        # 从回复中提取可能的商品名（引号内、书名号内、特定模式）
        # 简化处理：检查清单中每个真实商品名是否出现；不反向提取所有实体，
        # 因为中文分词复杂。这里只做正向兜底：如果回复提到明显价格/商品模式但
        # 没有任何一个真实商品名，提示模型核对。
        mentioned = [n for n in real_names if n and n in reply]
        if mentioned:
            return None  # 提到了真实商品，通过

        # 回复看起来在推荐商品（含价格/件/款等词）但没提到任何真实商品名
        looks_like_recommendation = any(k in reply for k in ("推荐", "这款", "这件", "¥", "元"))
        if looks_like_recommendation:
            return (
                "【系统提示】你刚才推荐的商品不在商城真实在售清单中。"
                f"商城目前可推荐的商品只有：{', '.join(real_names[:10])}。"
                "请重新基于这些真实商品回答，不要编造不存在的商品。"
            )
        return None

    # ============================================
    # 流式对话（SSE，前端逐字显示）
    # 支持工具调用 + 商品强制注入校验
    # ============================================
    async def chat_stream(
        self, user_id: int, message: str, token: str = "",
        history: Optional[list] = None,
    ) -> AsyncGenerator[str, None]:
        self._current_token = token
        messages = self._build_messages(user_id, message, history)

        # 注入会话级工具结果记忆（上一轮查询到的数据，本轮可引用其中的 ID）
        session_ctx = self._build_session_context(user_id)
        if session_ctx:
            messages.append({
                "role": "system",
                "content": session_ctx,
            })
            print(f"[chat_stream] 已注入会话记忆: {session_ctx[:120]}...")

        # 记录本对话中出现过的真实商品名（跨轮累积）
        real_product_names: List[str] = []

        # 最多循环 5 轮工具调用，防止死循环
        for round_no in range(5):
            print(f"[chat_stream] ==== 第 {round_no + 1} 轮 ====")
            try:
                kwargs = {
                    "model": self.model,
                    "messages": messages,
                    "stream": True,
                }
                if self.tools:
                    kwargs["tools"] = self.tools
                    kwargs["tool_choice"] = "auto"

                stream = await self.client.chat.completions.create(**kwargs)

                collected_content = ""
                collected_tool_calls = {}

                async for chunk in stream:
                    if not chunk.choices:
                        continue
                    delta = chunk.choices[0].delta

                    if delta.tool_calls:
                        for tc in delta.tool_calls:
                            idx = tc.index
                            if idx not in collected_tool_calls:
                                collected_tool_calls[idx] = {
                                    "id": tc.id or "", "name": "", "arguments": "",
                                }
                            if tc.id:
                                collected_tool_calls[idx]["id"] = tc.id
                            # 函数名在流式分块中可能每个 chunk 都完整发送，只取第一次，绝不累加
                            if tc.function and tc.function.name:
                                if not collected_tool_calls[idx]["name"]:
                                    collected_tool_calls[idx]["name"] = tc.function.name
                            # arguments 是增量片段，需要累加
                            if tc.function and tc.function.arguments:
                                collected_tool_calls[idx]["arguments"] += tc.function.arguments

                    if delta.content:
                        collected_content += delta.content

            except Exception as e:
                yield f"data: [LLM调用出错: {str(e)}]\n\n"
                yield "data: [DONE]\n\n"
                return

            # 文本形式工具调用解析
            clean_content, text_tool_calls = self._parse_text_tool_calls(collected_content)
            standard_calls = list(collected_tool_calls.values())
            print(f"[chat_stream]   轮{round_no + 1} 收集到文本: {collected_content[:100]!r}")
            print(f"[chat_stream]   轮{round_no + 1} 标准tool_calls: {[(c['name'], c['arguments']) for c in standard_calls]}")
            print(f"[chat_stream]   轮{round_no + 1} 文本tool_calls: {[(t.get('name'), t.get('arguments')) for t in text_tool_calls]}")

            # 没有工具调用 → 纯净正文（含回复校验）流出，结束
            if not standard_calls and not text_tool_calls:
                print(f"[chat_stream]   轮{round_no + 1} 无工具调用 → 输出最终回答: {clean_content[:100]!r}")
                # 商品回复校验：若模型没调工具却直接推荐，可能是编造
                if clean_content and real_product_names:
                    hint = self._verify_product_names(clean_content, real_product_names)
                    if hint:
                        clean_content = clean_content + "\n\n" + hint
                if clean_content:
                    # JSON 编码：把内容里的换行转成 \n，避免破坏 SSE 帧结构
                    #（SSE 不允许 payload 里有裸换行，否则前端按行解析会截断）
                    yield f"data: {json.dumps(clean_content, ensure_ascii=False)}\n\n"
                yield "data: [DONE]\n\n"
                return

            print(f"[chat_stream]   轮{round_no + 1} 有工具调用 → 丢弃过渡话语: {clean_content[:80]!r}")
            # 有工具调用 → 不流出本轮的过渡话语（如"我来帮您查一下"）
            # 原因：模型可能连续多轮调用工具，提前流出会导致碎片拼接
            #（"我来帮您查一下~稍等~有1笔订单：..."），而最终回答还没生成。
            # 过渡话语统一丢弃，只保留最终无工具调用那一轮的完整回答。

            # ---- 执行所有工具 ----
            standard_results = []
            text_results = []

            for tc in standard_calls:
                tool_name = tc["name"]
                try:
                    arguments = json.loads(tc["arguments"]) if tc["arguments"] else {}
                except json.JSONDecodeError:
                    arguments = {}
                try:
                    result = await self._execute_tool(tool_name, arguments)
                except Exception as e:
                    result = {"错误": f"工具执行异常: {str(e)}"}
                standard_results.append((tc["id"], tool_name, result))

            for tc in text_tool_calls:
                tool_name = tc.get("name", "")
                arguments = tc.get("arguments", {}) if isinstance(tc.get("arguments"), dict) else {}
                try:
                    result = await self._execute_tool(tool_name, arguments)
                except Exception as e:
                    result = {"错误": f"工具执行异常: {str(e)}"}
                text_results.append((tool_name, result))

            # ---- 商品强制注入：收集真实商品名，构造注入指令 ----
            is_product_query = any(
                name in self.PRODUCT_TOOLS for _, name, _ in standard_results
            ) or any(
                name in self.PRODUCT_TOOLS for name, _ in text_results
            )

            injected = False
            if is_product_query:
                # 从所有结果里提取真实商品名
                for _id, _n, result in standard_results:
                    real_product_names.extend(self._extract_product_names(result))
                for _n, result in text_results:
                    real_product_names.extend(self._extract_product_names(result))
                # 去重
                real_product_names = list(dict.fromkeys(real_product_names))
                if real_product_names:
                    injected = True

            # ---- 把结果喂回模型 ----
            if standard_calls:
                assistant_msg = {
                    "role": "assistant",
                    "content": clean_content or None,
                    "tool_calls": [
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {
                                "name": tc["name"],
                                "arguments": tc["arguments"],
                            },
                        }
                        for tc in standard_calls
                    ],
                }
                messages.append(assistant_msg)
                for tc_id, _name, result in standard_results:
                    tool_msg_content = json.dumps(result or {}, ensure_ascii=False)
                    # 商品工具 → 强注入真实清单
                    if injected:
                        tool_msg_content += (
                            "\n\n【重要】以上是商城真实在售商品。你只能从这些商品中向用户推荐，"
                            "绝对禁止编造任何不在这份清单里的商品名称、价格或图片。"
                            "如果清单为空或没有合适商品，就如实告诉用户暂时没有符合的商品。"
                        )
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc_id,
                        "content": tool_msg_content,
                    })
                    # 记录到会话记忆，供后续轮次/请求引用
                    self._remember_tool_result(user_id, _name, {}, result)
            else:
                messages.append({
                    "role": "assistant",
                    "content": collected_content or "",
                })
                results_text = "\n".join(
                    f"工具 {name} 返回结果: {json.dumps(result or {}, ensure_ascii=False)}"
                    for name, result in text_results
                )
                if injected:
                    results_text += (
                        "\n\n【重要】以上是商城真实在售商品清单。你只能从这些商品中向用户推荐，"
                        "绝对禁止编造任何不在这份清单里的商品名称、价格或图片。"
                        "如果清单为空或没有合适商品，就如实告诉用户暂时没有符合的商品。"
                    )
                messages.append({
                    "role": "user",
                    "content": (
                        "以下是工具执行结果，请直接根据这些结果用自然语言回答用户的问题。"
                        "如果结果为空，就如实说明（例如购物车是空的、暂无数据等）。"
                        "绝对不要再输出任何工具调用或JSON结构。\n\n"
                        f"{results_text}"
                    ),
                })
                # 记录到会话记忆
                for _n, _r in text_results:
                    self._remember_tool_result(user_id, _n, {}, _r)

            # 继续循环：让 LLM 基于工具结果生成最终回复

        yield "data: [已达工具调用上限，请稍后重试]\n\n"
        yield "data: [DONE]\n\n"

    # ============================================
    # 工具调用处理（自动循环，子类不需要管）
    # ============================================
    async def _process_response(self, response, messages: list) -> str:
        choice = response.choices[0]

        if choice.finish_reason != "tool_calls" or not choice.message.tool_calls:
            return choice.message.content or ""

        messages.append(choice.message)

        for tool_call in choice.message.tool_calls:
            tool_name = tool_call.function.name
            try:
                arguments = json.loads(tool_call.function.arguments)
            except json.JSONDecodeError:
                arguments = {}

            result = await self._execute_tool(tool_name, arguments)
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "content": json.dumps(result or {}, ensure_ascii=False),
            })

        final_response = await self.client.chat.completions.create(
            model=self.model, messages=messages,
        )
        return final_response.choices[0].message.content or ""

    @abstractmethod
    async def _execute_tool(self, tool_name: str, arguments: dict) -> dict:
        """子类必须实现：根据工具名执行具体操作，把结果写成 dict 返回"""
        ...

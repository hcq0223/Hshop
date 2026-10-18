# ============================================
# LLM 调用封装
# 提供统一的 LLM 调用接口，支持 OpenAI 和兼容 API
# ============================================

from openai import AsyncOpenAI
from config import settings


# ============================================
# 全局 LLM 客户端（懒加载）
# ============================================
_llm_client: AsyncOpenAI = None


def get_llm_client() -> AsyncOpenAI:
    """
    获取 LLM 客户端单例

    支持所有 OpenAI 兼容的 API（包括 DeepSeek、通义千问、智谱等）
    只需在 .env 中配置对应的 BASE_URL 和 API_KEY 即可
    """
    global _llm_client
    if _llm_client is None:
        _llm_client = AsyncOpenAI(
            api_key=settings.OPENAI_API_KEY,
            base_url=settings.OPENAI_BASE_URL,
        )
    return _llm_client

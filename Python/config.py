# ============================================
# 项目配置管理
# 从 .env 文件和环境变量中读取所有配置项
# ============================================

import os
from dotenv import load_dotenv

# 加载 .env 文件（如果存在）
load_dotenv()


class Settings:
    """全局配置单例，所有模块通过 from config import settings 引用"""

    # ========== 大模型 ==========
    OPENAI_API_KEY: str = os.getenv("OPENAI_API_KEY", "")
    OPENAI_BASE_URL: str = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "gpt-4o")

    # 仅本地模型（Ollama/qwen）需要；云端模型用原生 function calling【默认关闭文本工具调用】
    TEXT_TOOL_CALL_ENABLED: bool = os.getenv("TEXT_TOOL_CALL_ENABLED", "false").lower() == "true"

    # ========== JWT ==========
    JWT_SECRET: str = os.getenv("JWT_SECRET", "")
    JWT_ALGORITHM: str = "HS256"

    # ========== Java 后端 ==========
    JAVA_API_BASE: str = os.getenv("JAVA_API_BASE", "http://localhost:8080/api")

    # ========== Agent 服务 ==========
    AGENT_HOST: str = os.getenv("AGENT_HOST", "0.0.0.0")
    AGENT_PORT: int = int(os.getenv("AGENT_PORT", "8090"))


# 全局唯一实例
settings = Settings()

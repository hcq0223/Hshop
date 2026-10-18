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

    # ========== JWT ==========
    JWT_SECRET: str = os.getenv("JWT_SECRET", "")
    JWT_ALGORITHM: str = "HS256"

    # ========== MySQL ==========
    # ⚠️ 微服务拆分后已废弃：Java 侧各服务只访问自己的库，
    #    Agent 不再直连数据库，全部经 JAVA_API_BASE（网关）走 HTTP。
    #    见 core/database.py 顶部说明。保留仅兼容旧脚本。
    MYSQL_HOST: str = os.getenv("MYSQL_HOST", "localhost")
    MYSQL_PORT: int = int(os.getenv("MYSQL_PORT", "3306"))
    MYSQL_USER: str = os.getenv("MYSQL_USER", "root")
    MYSQL_PASSWORD: str = os.getenv("MYSQL_PASSWORD", "")
    MYSQL_DATABASE: str = os.getenv("MYSQL_DATABASE", "hcq_shop")

    # ========== Java 后端 ==========
    JAVA_API_BASE: str = os.getenv("JAVA_API_BASE", "http://localhost:8080/api")

    # ========== Agent 服务 ==========
    AGENT_HOST: str = os.getenv("AGENT_HOST", "0.0.0.0")
    AGENT_PORT: int = int(os.getenv("AGENT_PORT", "8090"))


# 全局唯一实例
settings = Settings()

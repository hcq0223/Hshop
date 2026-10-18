# ============================================
# ShopAi Python Agent 启动说明
# ============================================
#
# 【首次启动】
# 1. 安装 Python 3.10+
# 2. 安装依赖:
#    pip install -r requirements.txt --break-system-packages
# 3. 配置 API Key:
#    复制 .env.example 为 .env，填入真实的 OPENAI_API_KEY
#    cp .env.example .env
#    然后编辑 .env 文件
# 4. 启动:
#    uvicorn main:app --reload --port 8090 --host 0.0.0.0
#    或直接: python main.py
# 5. 验证:
#    浏览器访问 http://localhost:8090/docs 查看 Swagger API 文档
#    访问 http://localhost:8090/health 检查服务状态
#
# 【目录结构】
# main.py           - FastAPI 入口
# config.py         - 全局配置（从 .env 加载）
# .env.example      - 环境变量模板
# requirements.txt  - 依赖清单
# models/           - 请求/响应数据模型
#   schemas.py      - Pydantic 模型定义
# agents/           - Agent 实现
#   base.py         - Agent 基类（LLM调用、工具分发）
#   customer_agent.py - 顾客导购助手
#   merchant_agent.py - 商家运营助手
#   admin_agent.py  - 平台管理助手
# tools/            - 工具函数模块（Agent 调用的具体功能）
# core/             - 核心基础设施
#   auth.py         - JWT Token 解析
#   database.py     - MySQL 连接池
#   llm.py          - LLM 客户端封装
# services/         - 外部服务调用
#   java_api.py     - Java 后端 HTTP 调用
# routers/          - API 路由
#   chat.py         - 对话端点
#
# 【开发步骤】
# 1. ✅ 项目骨架搭建完成
# 2. ⬜ 配置 .env 填入真实 API Key
# 3. ⬜ 启动服务，测试 /health 端点
# 4. ⬜ 测试 /api/agent/chat 端点（需要有效 JWT token）
# 5. ⬜ 前端接入聊天窗口组件
# 6. ⬜ 逐个实现 tools 中的工具函数（接真实数据源）
# 7. ⬜ 优化各 Agent 的系统提示词

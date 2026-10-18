# ShopAi 部署说明（Nginx + 网关 + 微服务 + 前端）

- 日期：2026-10-05
- 适用：把 ShopAi 部署到一台服务器（Linux 为例），用 Nginx 作为前端静态托管 + 反向代理入口。
- 配套文件：`deploy/nginx/shopai.conf`（可直接放入 Nginx，改 3 处即可用）

---

## 0. 架构总览

```
浏览器 ──▶ Nginx(80/443)
             ├─ /            → 前端静态资源（Vue dist）
             ├─ /api/        → Spring Cloud Gateway (127.0.0.1:8080) → 各微服务
             ├─ /uploads/    → Spring Cloud Gateway → shopai-user 静态图片
             └─ /agent/      → Python AI 服务 (127.0.0.1:8090, SSE 流式)
```

原则：**Nginx 只做静态资源 + 反向代理；业务鉴权/路由分流仍归 Spring Cloud Gateway。**
不要把鉴权逻辑写进 Nginx。

> 本地开发**不需要** Nginx：Vite dev server 已把 `/api`、`/uploads`、`/agent` 代理到对应端口。

---

## 1. 服务器前置依赖

| 组件 | 版本要求 | 备注 |
|---|---|---|
| JDK | **21** | 微服务是 Java 21 构建 |
| MySQL | 8.x | 库名 `hcq_shop`，导入既有 SQL |
| Redis | 6/7.x | 127.0.0.1:6379 |
| RabbitMQ | 3.x | 127.0.0.1:5672，guest/guest |
| Nacos | **2.3.2** | 与 SCA 2023.0.1.0 客户端对齐；**不要用 3.0** |
| Seata Server | **2.0.0** | 事务分组 `shopai_tx_group`；相关库建 `undo_log` 表 |
| Elasticsearch | **8.11.x + IK 8.11.x** | 见 `es-index-setup.md`；**7.12.1 不兼容** |
| Nginx | **1.24+**（1.18 也可，建议升级） | 见下文 |

---

## 2. 构建后端

在项目根 `D:\ShopAi\JAVA`（或服务器上的对应目录）执行：

```bash
# 用项目自带的 Maven Wrapper，无需单独装 Maven
# Linux/macOS
./mvnw clean package -DskipTests
# Windows(PowerShell)
.\mvnw.cmd clean package -DskipTests
```

产物：各服务模块 `target/*.jar`（可执行 jar），例如：

```
shopai-gateway/target/shopai-gateway-1.0.0-SNAPSHOT.jar
shopai-user/target/shopai-user-1.0.0-SNAPSHOT.jar
shopai-product/target/shopai-product-1.0.0-SNAPSHOT.jar
shopai-order/target/shopai-order-1.0.0-SNAPSHOT.jar
shopai-merchant/target/shopai-merchant-1.0.0-SNAPSHOT.jar
shopai-search/target/shopai-search-1.0.0-SNAPSHOT.jar
shopai-inventory/target/shopai-inventory-1.0.0-SNAPSHOT.jar
```

> `shopai-common` 是公共库，不产出可执行 jar，构建时会被各服务依赖。

启动单个服务（示例）：

```bash
java -jar shopai-gateway/target/shopai-gateway-1.0.0-SNAPSHOT.jar
```

生产建议用 `nohup` 或 systemd 常驻，例如：

```bash
nohup java -jar shopai-gateway/target/shopai-gateway-1.0.0-SNAPSHOT.jar > logs/gateway.log 2>&1 &
```

---

## 3. 构建前端

在 `D:\ShopAi\Vue` 执行：

```bash
npm install
npm run build        # 产物输出到 Vue/dist
```

把 `dist` 目录上传到服务器，例如 `/var/www/shopai/dist`。

---

## 4. 安装并配置 Nginx

### 4.1 安装

```bash
# Ubuntu/Debian
sudo apt update && sudo apt install -y nginx

# CentOS/RHEL
sudo yum install -y nginx
```

> **关于 1.18.0**：功能上够用（静态托管 + 反代 + gzip + HTTPS 全支持）。它是 Ubuntu 20.04 内置版，安全补丁由系统回补，个人/学习项目没问题；长期对公网建议升到 **1.24 / 1.26 stable**。配置写法两者一致。

### 4.2 放入配置

把 `deploy/nginx/shopai.conf` 拷贝到 Nginx 配置目录：

```bash
sudo cp deploy/nginx/shopai.conf /etc/nginx/conf.d/shopai.conf
```

**只需改 3 处**（文件中已用 `← 改成...` 标注）：

1. `server_name` → 你的域名或服务器公网 IP
2. `root` → 前端 dist 实际路径（如 `/var/www/shopai/dist`）
3. 上游地址 → 若网关/Python 不在本机，改 `upstream` 里的 IP

### 4.3 校验并生效

```bash
sudo nginx -t            # 语法校验，必须通过
sudo systemctl reload nginx   # 或 nginx -s reload
```

---

## 5. 启动顺序

1. MySQL、Redis、RabbitMQ
2. **Nacos 2.3.2**（`startup.cmd -m standalone` / `sh startup.sh -m standalone`）
3. **Seata Server 2.0.0**
4. **Elasticsearch 8.11.x**（已装 IK）
5. 业务服务（顺序不严格，但建议）：`shopai-user → shopai-product → shopai-merchant → shopai-order → shopai-search → shopai-inventory`
6. **shopai-gateway**（最后，作为统一入口）
7. Python AI 服务（8090，如需要 `/agent`）
8. Nginx

---

## 6. 验证清单

```bash
# 网关是否存活
curl -i http://127.0.0.1:8080/api/category/all

# 经 Nginx 访问 API（应返回同样的 JSON）
curl -i http://<你的域名或IP>/api/category/all

# 前端页面（应返回 index.html）
curl -i http://<你的域名或IP>/

# 上传的图片（放一张图后）
curl -I http://<你的域名或IP>/uploads/xxx.jpg

# ES 索引是否建好（首次同步/rebuild 后）
curl "http://127.0.0.1:9200/_cat/indices?v"
```

浏览器核对：打开首页 → 登录 → 商品列表/详情 → 下单 → 商家后台 → 搜索（含分类分面）。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 刷新子路由页面 404 | Nginx 缺 `try_files ... /index.html;`（本配置已含，Vue history 模式必需） |
| `/uploads/xxx.jpg` 404 | 后端 `upload.path` 指向的目录不存在或没写权限；确认网关路由含 `/uploads/**` |
| 上传大图报 413 | 调大 `client_max_body_size`（本配置给了 20m） |
| `/agent` 对话没有实时输出 | SSE 被缓冲：确认 `/agent/` 块里 `proxy_buffering off;`（本配置已含） |
| 前端发版后仍是旧页面 | `index.html` 被缓存：本配置对 `index.html` 设了 `no-cache`，强刷一次即可 |
| 502 Bad Gateway | 网关/后端未启动，或 `upstream` 地址不对 |
| ES 建索引报 `analyzer [ik_max_word] not found` | 没装 IK 插件，或版本与 ES 不一致，见 `es-index-setup.md` |
| 连不上 Nacos | 客户端 2.3.2 连了 Nacos 3.0 服务端，降级 Nacos Server 到 2.3.2 |

---

## 8. HTTPS（可选）

有域名后，用 certbot 一条命令即可（会自动改 Nginx 配置并加跳转）：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d shopai.example.com
```

或手动启用 `shopai.conf` 末尾注释掉的 443 段（把 80 端口 server 里的 location 复制过去），
并把 80 端口改为 `return 301 https://$host$request_uri;`。

---

## 附：文件位置

- Nginx 配置：`deploy/nginx/shopai.conf`
- ES 索引说明：`docs/superpowers/plans/es-index-setup.md`
- 中间件部署：`docs/superpowers/plans/infra-config.md`

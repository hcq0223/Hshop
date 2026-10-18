# ShopAi 微服务基础设施配置

- 日期：2026-10-04
- 说明：记录拆分所需的四个中间件的部署信息、账号与启动命令，供本地联调参考。

---

## 1. Nacos（注册中心 + 配置中心）

| 项 | 值 |
|---|---|
| 版本 | 2.x |
| 地址 | http://localhost:8848 |
| 控制台 | http://localhost:8848/nacos |
| 默认账号 | nacos / nacos |
| 命名空间 | `shopai` |
| 分组 | `DEFAULT_GROUP` |

**启动命令（单机模式）**：

```bash
# Linux/Mac
sh startup.sh -m standalone
# Windows
startup.cmd -m standalone
```

**使用要点**：
- 各服务 `application.yml` 配置 `spring.cloud.nacos.discovery.server-addr=localhost:8848` 与 `spring.cloud.nacos.config.server-addr=localhost:8848`。
- 服务名：`shopai-gateway`、`shopai-user`、`shopai-product`、`shopai-order`、`shopai-merchant`、`shopai-inventory`、`shopai-search`。

---

## 2. RabbitMQ（消息队列）

| 项 | 值 |
|---|---|
| 地址 | amqp://localhost:5672 |
| 管理台 | http://localhost:15672 |
| 默认账号 | guest / guest |

**资源清单（topic 交换机）**：

| 类型 | 名称 |
|---|---|
| 交换机 | `shopai.data.exchange`（type=topic） |
| 队列 | `search.product.changed`（routingKey=`product.changed`） |
| 队列 | `search.merchant.changed`（routingKey=`merchant.changed`） |
| 死信交换机 | `shopai.data.dlx` |
| 死信队列 | `search.data.dlq` |

**启动命令（Docker）**：

```bash
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

---

## 3. Elasticsearch（搜索引擎）

| 项 | 值 |
|---|---|
| 版本 | 7.x 或 8.x |
| 地址 | http://localhost:9200 |

**索引**：

| 索引名 | 说明 |
|---|---|
| `product` | 商品搜索索引 |
| `merchant` | 商家搜索索引 |

**启动命令（Docker）**：

```bash
# ES 7.x
docker run -d --name es -p 9200:9200 -e "discovery.type=single-node" elasticsearch:7.17.0
# ES 8.x（若使用，需处理安全认证）
docker run -d --name es -p 9200:9200 -e "discovery.type=single-node" -e "xpack.security.enabled=false" elasticsearch:8.11.0
```

**⚠️ 必须安装 IK 分词器插件**：商品/商家索引字段使用 `ik_max_word` / `ik_smart` 分词，
未安装插件时建索引会报 `analyzer [ik_max_word] not found`，`shopai-search` 无法启动。
插件版本需与 ES 版本一致，装法见 `docs/superpowers/plans/es-index-setup.md`。

**索引是否会手动建**：**不需要手动建**。`shopai-search` 首次同步（MQ）或调用 `POST /api/search/rebuild` 时，
Spring Data ES 会按 `@Document`/`@Field` 注解**自动创建 `product` / `merchant` 索引及 mapping**。
如需显式控制 mapping，见 `es-index-setup.md` 的手动建索引 DSL。

---

## 4. Seata Server（分布式事务 TC）

| 项 | 值 |
|---|---|
| 版本 | 1.x（推荐 1.6+） |
| TC 地址 | http://localhost:7091 |
| 事务分组 | `shopai_tx_group` |

**启动要点**：
- 下载 Seata Server，使用 `nacos` 作为注册中心与配置中心（`application.yml` 中 `seata.registry.type=nacos`、`seata.config.type=nacos`）。
- 各业务服务配置：
  ```yaml
  seata:
    tx-service-group: shopai_tx_group
    registry:
      type: nacos
      nacos:
        server-addr: localhost:8848
        namespace: shopai
  ```
- 参与全局事务的库需建 `undo_log` 表（见 `sql/seata_undo_log.sql`）。

---

## 5. 本地联调启动顺序

1. 启动 Nacos、RabbitMQ、Elasticsearch、Seata Server。
2. 执行 SQL：`inventory.sql`、`seata_undo_log.sql`，并用应用层 `InventoryService.initStock()` 完成库存初始化。
3. 启动顺序：`shopai-common` 先 `mvn install` → 各业务服务 → `shopai-gateway`。
4. 前端：`cd Vue && npm run dev`（代理指向 `localhost:8080` 网关）。

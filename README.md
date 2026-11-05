# Nova AI

RAG 简历分析 + 模拟面试项目的 **AI 内核 / 中间件底座**。

本目录的代码来自 GymMind（AI 健身 RAG 系统）中可复用的部分，已统一改包名为 `com.nova`、配置前缀为 `nova.*`、品牌为 `Nova`。健身业务代码（会员、课程、预约、会籍、训练、器材、分析）全部剔除。

> 生成方式：`scripts/migrate-from-gymmind.ps1`（幂等，可重复执行以同步上游改动）

---

## 1. 当前状态

| 项 | 结果 |
|---|---|
| Java 源文件 | 422 个（`backend/src/main/java/com/nova`） |
| 编译（JDK 17） | ✅ `mvn -DskipTests compile` BUILD SUCCESS |
| 单元测试 | ❌ 未迁移（GymMind 的 236 个测试依赖健身夹具，需按新域重写） |
| 启动验证 | ⏳ 未验证（需 MySQL + Redis + ES + Milvus 就绪） |
| 前端 | 仅 3 个可复用工具文件，非完整工程 |

---

## 2. 目录结构

```
Nova AI/
├── backend/
│   ├── pom.xml                      Spring Boot 3.4.12 / Java 17（依赖已裁剪）
│   └── src/main/
│       ├── java/com/nova/
│       │   ├── NovaApplication.java  启动类
│       │   ├── ai/                   模型网关 + 对话编排 + 工具调用（120）
│       │   ├── knowledge/            解析入库 + 混合检索 + 索引（76）
│       │   ├── iam/                  认证 / 角色 / 权限（67）
│       │   ├── tenancy/              租户 / 配额 / 设置（44）
│       │   ├── shared/               鉴权 / 异常 / 响应 / 任务队列 / 配置（41）
│       │   ├── agent/                Agent 人格 + 提示词版本历史（22）
│       │   ├── ocr/                  DashScope OCR 识别（21）
│       │   ├── audit/                操作审计 + AI 用量查询（20）
│       │   └── platform/             RBAC 目录与启动引导（10）
│       └── resources/
│           ├── application.yml       主配置（nova.* 前缀）
│           ├── application-dev.yml   本地开发配置
│           └── META-INF/spring/…     .env 自动加载
├── docker-compose.yml                Redis / ES / etcd / MinIO / Milvus（+可选 MySQL）
├── docker/elasticsearch/Dockerfile   ES 中文分词自定义镜像
├── frontend/src/                     SSE 消费 / 执行图 / 对话 API 封装
├── scripts/migrate-from-gymmind.ps1  迁移脚本（幂等）
├── start-infrastructure.bat          拉起中间件
├── start-backend-dev.bat             启动后端
└── .env.example                      环境变量模板
```

---

## 3. 复用映射表

### 3.1 直接可用（改包名即可）

| 能力 | 位置 | 说明 |
|---|---|---|
| 模型网关 | `ai/application/ChatModelGateway` | 同步 / 流式 / 结构化 JSON / 原生工具调用四种形态 |
| 百炼适配 | `ai/infrastructure/BailianChatAdapter` | OpenAI 兼容协议、SSE 解析、3 次重试、用量解析、错误脱敏 |
| 向量与精排 | `ai/infrastructure/bailian/BailianEmbeddingAdapter`、`BailianRerankAdapter` | 1024 维，带维度校验与重试 |
| 模型配置 | `ai/infrastructure/bailian/DashScopeProperties` | 模型名全部外置 |
| 用量与计费 | `ai/application/DefaultAiUsageService` + `AiModelPricing` | token 计量、成本折算、按 feature 打点 |
| 混合检索 | `ai/domain/HybridRetriever` + `RrfFusion` | 关键词 + 向量并行召回，单路失败自动降级 |
| 检索编排 | `knowledge/application/DefaultKnowledgeSearchService` | 融合 → 精排 → 词面兜底 → 权限过滤 → 上下文预算 |
| 索引端口 | `knowledge/infrastructure/search/{KeywordIndexPort,VectorIndexPort}` | ES / Milvus / 内存三种实现可切 |
| 文档解析 | `knowledge/infrastructure/parser/DocumentParserRegistry` | PDF / DOCX / HTML / MD / TXT |
| 分块 | `knowledge/infrastructure/parser/StructuredChunker` + `ChunkStrategySelector` | 6 种策略自动选型（需新增 RESUME 策略） |
| 入库流水线 | `knowledge/application/KnowledgeIngestWorker` | 异步、重试、状态机 |
| 索引代发布 | `knowledge/infrastructure/search/IndexGenerationService` | 先建新代再原子切换 |
| OCR | `ocr/**` | 上传 → 识别 → Markdown，含每日配额 |
| 工具调用 | `ai/harness/capability/{AiCapability,ToolGateway}` + `AiToolOrchestrator` | 模型决策 + 受控执行 |
| 提示词分层 | `ai/chat/application/AiPromptSkeleton` | 平台 → 角色 → 租户 → 安全红线 |
| 注入防护 | `ai/domain/PromptInjectionGuard` | 简历藏白字提示词是本项目真实风险 |
| 流式对话 | `ai/harness/api/AiHarnessController` | SSE：token / progress / done / error |
| 鉴权 | `shared/security/**` + `iam/**` | JWT 签发校验、token 版本失效、RBAC |
| 配额限流 | `tenancy/**` + `shared/security/TenantRateLimitFilter` | 租户配额、每分钟限流 |
| 审计 | `audit/**` | 操作审计 + AI 用量查询/趋势 |
| 任务队列 | `shared/async/{RedisStream,MySql,InMemory}PersistentJobQueue` | at-least-once |
| 对象存储 | `knowledge/infrastructure/storage/*` | Local / MinIO |

### 3.2 需要改造后使用

| 文件 | 改造点 |
|---|---|
| `ai/harness/application/DefaultAiHarnessService` | 主流程可留，`buildAnswerRules()` 里的健身话术换成面试话术 |
| `ai/domain/AnswerPathRouter` + `AnswerPathRuleEngine` | `RoleCode` 枚举换成 `CANDIDATE / INTERVIEWER / ADMIN`，路由规则换成简历类/岗位类/行为类 |
| `ai/domain/ContextBudgeter` | 硬编码 `MAX_SEGMENTS=8`、`MAX_PER_DOCUMENT=2`，简历是单文档多片段，必须改成按 section 配额 |
| `ai/infrastructure/BailianChatAdapter` | `max_tokens` 写死 256（第 40/128/180 行附近），面试长回答会被截断，需参数化 |
| `knowledge/infrastructure/parser/ChunkStrategy` | 新增 `RESUME`：按章节标题 + 时间线切，一段经历一个 chunk |
| `knowledge/application/DefaultKnowledgeSearchService` | 删掉价格/促销结构化段（`addStructuredPriceContext` 一带） |
| `platform/bootstrap/PermissionCatalog` | 仍含健身权限码，需按新角色裁剪 |
| `shared/security/CurrentActor` + `iam/domain/model/RoleCode` | 角色维度替换 |
| `ai/harness/plugins/*`（保留的 5 个） | 仅作插件写法示例，业务能力需重写 |

### 3.3 已剔除（未复制）

```
member/ booking/ course/ membership/ coach/ nutrition/ workout/ equipment/ exercise/ analytics/ profile/ search/ mcp/
ai/api/{AiAnalysis,AiWeeklyReport,AiWorkoutPlan,CoachAssistant}Controller
ai/application/{DefaultAiAnalysisService, DefaultAiWorkoutPlanDraftService, DefaultCoachAssistantService, WorkoutPlanPromptComposer, ...}
ai/harness/plugins/{门店运营、会员流失、课程预约、会员训练、训练日历、平台AI管理、文章检索、文章导入}
ai/harness/terminal/**            （终端读写工具，高权限，无必要）
knowledge/bootstrap/FitnessKnowledgeDemoInitializer   （健身演示数据）
platform/bootstrap/MemberWorkoutScheduleSchemaInitializer
iam/application/Default{UserAdministration,UserStatistics}Service  （耦合会籍域）
```

> 保留 `UserAdministrationService` 接口（纯接口，含 `UserSummary` 记录），因为 `UserResponse` / `RoleResponse` 依赖它。后续做用户管理时补一个不依赖会籍的实现即可。

---

## 4. 与 GymMind 的差异

| 维度 | GymMind | Nova AI |
|---|---|---|
| 包名 | `com.gymmind` | `com.nova` |
| 配置前缀 | `gymmind.*` | `nova.*` |
| 环境变量 | `GYMMIND_ES_URL` 等 | `NOVA_ES_URL` 等 |
| JWT issuer | `GymMind` | `Nova` |
| 品牌文案 | GymMind 智能健身助手 | Nova AI 面试助手 |
| 默认插件 | 12 个（含门店运营） | 3 个（`basic_chat`、`knowledge_search`、`ai_memory`） |
| 数据库 | `gymmind` | `nova_ai` |
| OnlyOffice | 启用 | 默认关闭 |
| 联网搜索 | 默认 tavily | 默认 `none` |

---

## 5. 快速开始

### 5.1 准备

```bat
copy .env.example .env
:: 编辑 .env：填 MYSQL_PASSWORD 与 DASHSCOPE_API_KEY
```

### 5.2 起中间件

```bat
start-infrastructure.bat
```

首次启动 ES / Milvus 约需 1～2 分钟。`docker compose ps` 全部 healthy 即可。

### 5.3 起后端

```bat
start-backend-dev.bat
```

或手动：

```powershell
$env:JAVA_HOME='D:\java\jdk-17.0.14'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd backend
mvn spring-boot:run
```

默认端口 `8080`，健康检查 `GET /actuator/health`。

### 5.4 编译

```powershell
$env:JAVA_HOME='D:\java\jdk-17.0.14'
mvn -f backend/pom.xml -DskipTests compile
```

---

## 6. 下一步（对应复用方案的 P0）

- [ ] 建库：`CREATE DATABASE nova_ai DEFAULT CHARACTER SET utf8mb4;`（JPA `ddl-auto=update` 自动建表）
- [ ] 首次启动后用 `admin / 123456` 登录，确认鉴权链路
- [ ] 调通 `POST /api/v1/ai/harness/chat/stream`，确认 SSE 四类事件
- [ ] 上传一份 DOCX 简历走通入库 → 检索 → 引用
- [ ] 改造 `ContextBudgeter` 与 `max_tokens`（见 §3.2，**越早越好**）
- [ ] 新增 `resume/` 包：`ResumeProfile` 抽取 + PII 脱敏 + 溯源

> 完整分期计划见 GymMind 仓库：`docs/superpowers/plans/2026-11-05-resume-interview-reuse-plan.md`

---

## 7. 注意事项

1. **`frontend/src` 不是可运行工程**，只是 3 个可复用文件：`sseStream.ts`（SSE 解析）、`aiExecutionGraph.ts`（执行过程图）、`api/harness.ts`（对话 API 封装）。它们依赖新前端工程提供 `./client`、`@/types/api`。
2. **重新同步上游**：改完 GymMind 的 AI 层后，重跑 `scripts/migrate-from-gymmind.ps1` 即可覆盖刷新（脚本会清空 `com/nova` 后重建）。
3. **测试未迁移**：新增业务代码请配套单测，不要沿用 GymMind 的健身夹具。
4. **多租户维度已保留**：`tenantId` 贯穿表结构与鉴权。个人版建议固定单租户使用，不要手工拆字段——改动面远大于收益。
5. **`target/` 为编译产物**，已在 `.gitignore` 中。

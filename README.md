# Panel

RAG 简历分析 + 模拟面试项目的 **AI 内核 / 中间件底座**。

本目录的代码来自 GymMind（AI 健身 RAG 系统）中可复用的部分，已统一改包名为 `com.panel`、配置前缀为 `panel.*`、品牌为 `Panel`。健身业务代码（会员、课程、预约、会籍、训练、器材、分析）全部剔除。

> 生成方式：`scripts/migrate-from-gymmind.ps1`（幂等，可重复执行以同步上游改动）

---

## 1. 当前状态

| 项 | 结果 |
|---|---|
| Java 源文件 | 422 个（`backend/src/main/java/com/panel`） |
| 编译（JDK 21） | ✅ `mvn -DskipTests compile` BUILD SUCCESS |
| 单元测试 | ❌ 未迁移（GymMind 的 236 个测试依赖健身夹具，需按新域重写） |
| 启动验证 | ⏳ 未验证（需 MySQL + Redis + ES + Milvus 就绪） |
| 前端 | 仅 3 个可复用工具文件，非完整工程 |

---

## 2. 目录结构

```
Panel/
├── backend/
│   ├── pom.xml                      Spring Boot 3.4.12 / Java 17（依赖已裁剪）
│   └── src/main/
│       ├── java/com/panel/
│       │   ├── PanelApplication.java  启动类
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
│           ├── application.yml       主配置（panel.* 前缀）
│           ├── application-dev.yml   本地开发配置
│           └── META-INF/spring/…     .env 自动加载
├── docker-compose.yml                Kafka（+可选 MySQL）；Redis/ES/MinIO/Milvus 复用共享实例
├── docker/elasticsearch/Dockerfile   ES 中文分词镜像（独立部署时手工加回 compose）
├── frontend/src/                     SSE 消费 / 执行图 / 对话 API 封装
├── scripts/migrate-from-gymmind.ps1  迁移脚本（幂等）
├── start-infrastructure.bat          拉起 Kafka（其余中间件复用本机实例）
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

| 维度 | GymMind | Panel |
|---|---|---|
| 包名 | `com.gymmind` | `com.panel` |
| 配置前缀 | `gymmind.*` | `panel.*` |
| 环境变量 | `GYMMIND_ES_URL` 等 | `PANEL_ES_URL` 等 |
| JWT issuer | `GymMind` | `Panel` |
| 品牌文案 | GymMind 智能健身助手 | Panel 面试助手 |
| 默认插件 | 12 个（含门店运营） | 3 个（`basic_chat`、`knowledge_search`、`ai_memory`） |
| 数据库 | `gymmind` | `panel_ai` |
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

Panel **不重复起** Redis / ES / etcd / MinIO / Milvus，直接用本机已在运行的那套（本机就是 GymMind 的 `gymmind-*` 容器），自己只起独有的 Kafka：

```bat
start-infrastructure.bat
```

等价于 `docker compose up -d kafka`。Kafka 约 30 秒 healthy，`docker compose ps` 出现 `panel-kafka ... (healthy)` 即可。

若那套共享中间件没在跑，先去 GymMind 目录执行 `docker compose up -d`，或者改 `.env` 里的 `REDIS_HOST_PORT` / `ES_HOST_PORT` / `MINIO_API_PORT` / `MILVUS_PORT` 指向别处。

共享同一套实例靠命名空间隔离，不会互相污染：

| 资源 | GymMind | Panel |
|---|---|---|
| MySQL 库 | `gymmind` | `panel_ai` |
| ES 索引 | `gymmind-knowledge` | `panel-knowledge`（`PANEL_ES_INDEX`） |
| Milvus collection | `gymmind_knowledge` | `panel_knowledge`（`PANEL_MILVUS_COLLECTION`） |
| MinIO bucket | `gymmind` | `panel-ai`（首次上传自动创建） |
| Redis key | `gymmind:…` | `panel:<env>:v1:…` |

前提是 `application-dev.yml` 里的端口指向那套正在跑的服务（本机默认就是 `6379 / 9000 / 9202 / 19530`）。

### 5.3 起后端

```bat
start-backend-dev.bat
```

或手动：

```powershell
$env:JAVA_HOME='D:\java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd backend
mvn spring-boot:run
```

默认端口 `8080`，健康检查 `GET /actuator/health`。

#### 5.3.1 数据库 schema（Flyway）

- `spring.jpa.hibernate.ddl-auto` 已改为 `validate`：Hibernate 只校验表结构，**不再建表/改表**。
- 表结构由 `backend/src/main/resources/db/migration/` 下的 Flyway 脚本管理：
  - `V1__baseline.sql`：当前完整基线（32 张表）。
  - `V2__async_job.sql`：`panel.job-queue=mysql|kafka` 时才用到的队列表。
  - `V3__kb_chat.sql` / `V4__kb_questions_and_graph.sql` / `V5__kb_catalog.sql` / `V6__kb_catalog_type_align.sql`：知识库问答、题目与图谱、知识目录及类型对齐。
  - `V7__resume_soft_delete.sql`：简历软删除。
  - `V8__kb_quiz.sql`：刷题闭环（`kb_quiz_session` / `kb_quiz_attempt` / `kb_category_relation`）。
  - `V9__kb_skill_state.sql`：知识点能力值（`kb_skill_state`，用户 × 知识点一条，Elo 口径）。
  - `V10__kb_skill_interview_import.sql`：面试信号回填台账（`kb_skill_interview_import`，`turn_id` 唯一保幂等）。
  - `V11__account_and_data_isolation.sql`：用户级数据隔离（`resume` / `interview_session` / `kb_base` 加 `owner_user_id`）+ 邮箱验证字段与 `sys_email_verification`；只加可空列/索引/表，旧代码仍可启动。设计见 [`docs/上线方案-注册与数据隔离.md`](docs/上线方案-注册与数据隔离.md)。
- **已有 `panel_ai` 库**：首次用新版本启动时，Flyway 会创建 `flyway_schema_history` 并把现有库标记为 V1（`baseline-on-migrate`），只补执行 V2；不会重建 V1 里的表，也不会删除任何数据。
- **全新库**：先执行 `CREATE DATABASE panel_ai DEFAULT CHARACTER SET utf8mb4;`，再启动后端，V1 + V2 按顺序建表。
- 以后改表：新增 `V3__xxx.sql`，不要修改已执行过的 V1/V2（Flyway 会校验 checksum）。旧开发库如需临时兼容脚本，可设 `panel.dev.legacy-schema-shim=true`。
- 若确实要退回“完全不碰 schema”，把 `spring.flyway.enabled` 设为 `false`、`ddl-auto` 设为 `none`；但新库就需要手工导入 SQL。

### 5.4 编译

```powershell
$env:JAVA_HOME='D:\java\jdk-21'
mvn -f backend/pom.xml -DskipTests compile
```

---

## 6. 当前进度（RAG 知识库）

- [x] 建库：`CREATE DATABASE panel_ai DEFAULT CHARACTER SET utf8mb4;`（Flyway `V1__baseline.sql` 建表，`ddl-auto=validate` 只校验；旧库 `nova_ai` 保留不动）
- [x] 8080 已切到新构建 + `panel_ai`：`admin / 123456` 登录成功（`admin@panel.local`，56 个权限）
- [x] K0：`kb_base` + 3 个内置库（`question-bank` / `tech-knowledge` / `job-knowledge`），`/api/v1/kb` 支持 CRUD
- [x] 前端 `/kb` 知识库列表（`/kb/chat` 仍禁用）
- [x] K1：文档上传 / 解析 / 切片 / 落库 / ES+Milvus 索引；`/kb/:id` 文档管理；legacy `/knowledge/*` 已关闭；ES 索引 `panel-knowledge` 已建
- [x] K2：检索测试面板（混合/关键词/向量 + rerank + 阈值）、`kbId` 双端过滤、重建索引；`/kb/:id?tab=search`
- [x] 刷题闭环：抽题（分层 + 加权）→ 只看题面 → 展开答案 → 自评 → 错题落知识点；`/kb/quiz`；覆盖率看板接口；知识点关系 CRUD
- [x] 能力值模型：`kb_skill_state` + Elo 口径更新（难度/学习率衰减/遗忘回落/面试 2 倍权重）+ 树形聚合；`/kb/quiz/skills`；抽题权重已切到 `wrongRate^1.5`（`panel.kb.quiz.skill-weight` 可关闭做 A/B）
- [x] 面试信号接入：`interview_turn.skill` 名字对齐知识点 + 得分折算观测 + `turn_id` 台账幂等回填（`/quiz/skills/interview-backfill`，目录页有「面试信号回填」按钮）
- [x] 按文档一键归类：`/catalog/documents/{documentId}/classify`（`redoLowConfidence` 清低置信重跑），刷题页「按文档看缺口」每行一个按钮
- [x] 知识树 v1：全栈+Agent 方向 5 领域 / 30 主题 / 179 考点（214 节点），建在题库库并继承到技术知识库，`tmp/kb-tree/sync.js` 幂等可复跑；见 [`docs/知识树-v1.md`](docs/知识树-v1.md)
- [x] 外部索引降级：ES/Milvus 未启动时上传仍能 `READY`（`generation=local-*`）、文档/知识库删除不再 500，检索返回 503 并说明原因；详情页新增「删除知识库」入口；回归用例 `KbIndexerDegradeTest`
- [x] 目录树就地编辑：左侧树上双击改名、悬停 `＋` 建子级、拖拽换层级/调顺序、行内确认删除（`cascade=true` 删子树，题目/切片只摘挂载回到「未分类」）；`move` 补 `beforeId` 排序与 `parent_id` 修正，改名同步重写 `path_key`
- [x] 未归类可见 + 规则兜底增强：目录页头「还有 N 个切片/题目没挂目录」黄条（一键归类 / 看未分类）；`KbAutoClassifyService.fragmentScore` 支持带括号节点名的片段匹配（英文整词 + 中文 2-gram，命中 ≥2 才挂，0.72~0.78），模型不可用时不再全军覆没；用例 `KbAutoClassifyFragmentTest`
- [x] 任务队列兼容老 Redis：`RedisStreamPersistentJobQueue` 探测到 `XADD/XGROUP unknown command`（本机 Redis 3.2）就永久降级为 ZSET 索引 + 定时轮询，不再把异常抛到调度线程顶层崩掉进程；用例 `RedisStreamPersistentJobQueueDegradeTest`
- [x] 归属状态可见：条目卡片标 `✓ 待确认 92%`（可点）/ `✓ 已确认`，页头 `确认归属` 一键锁全库（`POST /catalog/items/confirm-all`）；条目 DTO 补 `categoryId / locked / confidence / linkSource`
- [x] 题库解析以整篇为准：Markdown 保留 `1.` 题号，`KbQuestionParser.parse(正文, chunks)` 先拆题再反查切片——8 题的文档切成 4 片仍出 8 题，不再把文档标题当题干；标签只取该题段落
- [x] 异步调度不再被单个坏任务拖垮：事务只包「领取」，handler 在事务外执行并兜住异常；`ResumeParseWorker` 的重试入队自兜（此前会把 `UnexpectedRollbackException` 甩到调度线程顶层）
- [x] 「做过」只算题库里还在的题：`countDistinctQuestions` 加 `in` 子句，重传文档后不会再出现「做过 13 / 总题数 8」；空题库覆盖率报 0 而不是 100%
- [x] 空题库不再假装能刷：`开始刷题` 置灰，空态直接说「先去知识库管理上传题库文档」（此前点下去只得到 `CONFLICT`）；`stats.recentSessions` 跳过「一道活题都没有」的僵尸会话
- [x] 界面不再对用户撒谎：归类报告改成"跑前跑后差集"计数（`KbClassifyResult.summary`，连点两次不会报两遍，也不会出现库里 8 条报告 12 条）；首屏加载出骨架而不显示 0；0 次作答时正确率显示 `—`。设计与验收见 [知识库体验改造方案](docs/知识库体验改造方案.md)
- [x] 导航按"人要做什么"收敛：侧栏只剩 **我的资料 / 练习 / 问答**（`面试题库` 不再是第四个平级入口）；进库后一条页签切 **资料 · 目录 · 练习 · 问答**，`/kb/:id/quiz`、`/kb/:id/chat` 为新增路由，老链接 `/kb/quiz?kbId=`、`/kb/chat?kbId=`、`/kb/questions` 继续可用
- [x] 自动归类不再"181 选 1"：先把内容定到 30 个主题之一（主题词命中就不问模型），再在该主题的 6~10 个叶子里选；222 个目录节点全部补上"放什么、不放什么"的说明；选串了按域锚词打折留空。手法与实测见 [知识库归类准确率](docs/知识库归类准确率.md)
- [x] 带代码块的 Markdown 从"传不上去"修到"能读能抄"：围栏行曾被当正文洗成空串（整篇卡在「失败 / 切片 0」）；随后发现代码被 `strip()` 掉缩进、被切片器从中间劈开、`idx_name_age` 这类标识符被 markdown 清洗成 `idxnameage`。现在围栏内缩进原样保留、一段代码是不可切的原子块（88 个含代码切片，0 个被切断）、清洗只去真装饰，知识点和题目详情都渲染成深色 `<pre>`
- [x] 抽题边界靠标题而不是猜：`plainText()` 保留 `##` 前缀并把代码块连围栏一起带下去，题目只认"像题目的标题"，答案里从 1 重来的有序列表不再被当成新题——「MyBatis 中 # 和 $ 的区别」的答案从 1554 字符（吞了 AOP、数组、List 三节）回到 275 字符
- [x] 切片标题带上所属题目（「4. MyBatis 中 # 和 $ 的区别 · 简短回答」）并用「章节 → 主题」表跨批继承，归类不再靠猜
- [ ] K3 问答、K4 Agent 接入、K5 评测

完整设计见 [`docs/RAG知识库-设计方案.md`](docs/RAG知识库-设计方案.md)；刷题链路见 [`docs/刷题与知识点联动.md`](docs/刷题与知识点联动.md)；业务视角与截图见 [`docs/知识库与刷题业务设计.md`](docs/知识库与刷题业务设计.md)；知识树见 [`docs/知识树-v1.md`](docs/知识树-v1.md)。

### 6.1 知识库接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/kb` | `knowledge:read` | 库列表（内置库在前，带文档/切片数） |
| POST | `/api/v1/kb` | `knowledge:write` | 新建库，返回 201 |
| GET | `/api/v1/kb/{id}` | `knowledge:read` | 库详情 |
| PATCH | `/api/v1/kb/{id}` | `knowledge:write` | 改名称/描述/可见性/切片参数 |
| DELETE | `/api/v1/kb/{id}` | `knowledge:write` | 删除自定义库，204；内置库 409；有文档需 `?force=true` |
| POST | `/api/v1/kb/{id}/documents` | `knowledge:write` | multipart 单文件，202；同库同内容 409 |
| GET | `/api/v1/kb/{id}/documents` | `knowledge:read` | 分页 + `status` / `keyword` || GET | `/api/v1/kb/documents/{docId}` | `knowledge:read` | 文档详情（状态/错误/切片数） |
| GET | `/api/v1/kb/documents/{docId}/chunks` | `knowledge:read` | 切片分页 |
| POST | `/api/v1/kb/documents/{docId}/retry` | `knowledge:write` | 失败重试，202 |
| PATCH | `/api/v1/kb/documents/{docId}/publication` | `knowledge:write` | 发布 / 下线 |
| DELETE | `/api/v1/kb/documents/{docId}` | `knowledge:write` | 204，同时清索引与对象 |
| GET | `/api/v1/kb/documents/{docId}/download` | `knowledge:read` | 原文件下载 |
| POST | `/api/v1/kb/documents/{docId}/reindex` | `knowledge:write` | 从已落库切片重建索引，202 |
| POST | `/api/v1/kb/{id}/search` | `knowledge:read` | `{query, topK?, mode?, rerank?, publishedOnly?}`，返回 `hits + debug` |

> 入库是异步的：上传返回 202，前端每 2~3 秒轮询 `GET /kb/documents/{id}`，状态机 `UPLOADING → PARSING → CHUNKED → INDEXING → READY | FAILED`。
> 开发环境可用 `/dev-login?token=<JWT>&actor=admin&next=/kb` 直接进页面截图；`actor=admin` 仅用于本地冒烟。
> 旧接口 `/api/v1/knowledge/*` 已通过 `@Profile("legacy-knowledge")` 关闭，默认 404。

### 6.2 刷题接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/kb/{kbId}/quiz` | `knowledge:read` | `{size?}`（默认 5，最大 20），201；**返回体不含答案** |
| GET | `/api/v1/kb/quiz/sessions/{sessionId}` | `knowledge:read` | 会话详情，刷新可恢复 |
| POST | `/api/v1/kb/quiz/attempts/{attemptId}/reveal` | `knowledge:read` | 展开答案 + 知识点 + 相关知识点 |
| POST | `/api/v1/kb/quiz/attempts/{attemptId}/grade` | `knowledge:read` | `{result: CORRECT\|WRONG\|UNSURE\|SKIPPED}`；最后一题答完自动收尾并返回 summary |
| GET | `/api/v1/kb/{kbId}/quiz/stats` | `knowledge:read` | 题量/挂载/做过/正确率/薄弱知识点/最近 5 组 |
| GET | `/api/v1/kb/{kbId}/quiz/wrong-book?limit=50` | `knowledge:read` | 错题本（带知识点路径） |
| GET | `/api/v1/kb/{kbId}/quiz/skills` | `knowledge:read` | 能力值：叶子按弱到强排、父节点按题量加权聚合；含 `wrongRate`（下次答错概率）；不限题库 |
| POST | `/api/v1/kb/{kbId}/quiz/skills/interview-backfill` | `knowledge:write` | 面试记录回填能力值：`?userId=&dryRun=&createMissing=&rootName=`，按 `turn_id` 幂等 |
| GET | `/api/v1/kb/{kbId}/catalog/coverage` | `knowledge:read` | 目录覆盖率 + 缺口队列（低置信在前、未分类在后，各 ≤20 条） |
| POST | `/api/v1/kb/{kbId}/catalog/items/{itemType}/{itemId}/confirm` | `knowledge:write` | 确认归属：锁定 + 置信度 1.0，自动归类不再覆盖 |
| POST | `/api/v1/kb/{kbId}/catalog/items/confirm-batch` | `knowledge:write` | `{itemType,itemIds[]}` 批量确认，没挂载的跳过，返回 `{confirmed,skipped}` |
| POST | `/api/v1/kb/{kbId}/catalog/import?sourceKbId=` | `knowledge:write` | 继承另一个库的目录树（按 `path_key` 去重、幂等） |
| POST | `/api/v1/kb/{kbId}/catalog/categories` | `knowledge:write` | `{parentId,name,description?,pathKey?}`，父节点为 0/null 即一级 |
| PATCH | `/api/v1/kb/catalog/categories/{id}` | `knowledge:write` | 改名（`description` 传 null = 不动），`path_key` 与子树同步重写 |
| POST | `/api/v1/kb/catalog/categories/{id}/move` | `knowledge:write` | `{parentId,beforeId}`：换父 + 同级排序，重算子树 `path/path_key/depth` |
| DELETE | `/api/v1/kb/catalog/categories/{id}` | `knowledge:write` | 空节点才能删；`?cascade=true` 删整棵子树，题目/切片只摘挂载回「未分类」 |
| POST | `/api/v1/kb/{kbId}/catalog/documents/{documentId}/classify` | `knowledge:write` | 按文档一键归类；`?redoLowConfidence=true` 先清未锁定的低置信挂载再重跑 |
| GET/POST | `/api/v1/kb/{kbId}/catalog/relations` | `knowledge:read` / `write` | 知识点横向关系（RELATED / PREREQUISITE / CONTRAST） |
| DELETE | `/api/v1/kb/{kbId}/catalog/relations/{relationId}` | `knowledge:write` | 204 |

> 只有 `kind=QUESTION_BANK` 的库能抽题。抽题**不调用 ES/Milvus**：题目是抽样问题、知识点是目录问题，相似度在这里没有用武之地；向量只服务于 `/kb/chat` 和"题 → 知识点候选"这类需要语义的地方。
> `/api/v1/kb/{kbId}/questions`（题库浏览）列表**不返回答案**，答案只在 `/api/v1/kb/questions/{id}` 详情里，避免在浏览列表时提前看到答案。
> **可见性已强制**：`kb_base.visibility=INTERNAL` 只对 `PLATFORM_ADMIN` / `ADMIN` / `INTERVIEWER` 开放。`GET /kb` 会过滤，其余读接口（目录、文档、检索、问答、刷题、覆盖率）走 `KbAccessGuard.ensureReadable`，直连 id 返回 403。
> 登录依赖 Redis（`/auth/login` 走会话存储），本地没起 Redis 时会被 `DEPENDENCY_UNAVAILABLE` 挡住。

---

## 7. 注意事项

1. **`frontend/src` 已是可运行的 Panel 前端**（`pnpm dev`，5173，`/api` 代理到 8080）；其中 `sseStream.ts`、`aiExecutionGraph.ts`、`api/harness.ts` 三份仍复用自 GymMind。
2. **重新同步上游**：改完 GymMind 的 AI 层后，重跑 `scripts/migrate-from-gymmind.ps1` 即可覆盖刷新（脚本会清空 `com/panel` 后重建）。
3. **测试未迁移**：新增业务代码请配套单测，不要沿用 GymMind 的健身夹具。
4. **旧业务表保留 `tenantId`**（个人版固定单租户）；知识库新表 `kb_*` 不再带 `tenant_id`。
5. **`target/` 为编译产物**，已在 `.gitignore` 中。

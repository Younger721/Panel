# NexusAI 改造方案：从「AI 底座」到「智能面试与职业评估平台」

> 基线：Panel（422 个 Java 源文件，`clean compile` BUILD SUCCESS，JDK 17 / Spring Boot 3.4.12）
> 目标：简历中描述的 NexusAI —— 多 Agent 协作 + 简历解析 + 岗位匹配 + 模拟面试 + 评估复核 + 混合 RAG + 异步任务 + SSE
> 原则：**新增业务上下文，复用 AI 内核**。不改造成本高的部分不动，靠端口/接口扩展。

---

## 0. 一句话结论

现底座把「模型调用、工具编排、混合检索、文档入库、异步任务、鉴权配额、用量审计、SSE」都做成了可插拔端口，
**多 Agent 只是这些端口之上的编排层**。所以缺的不是能力，而是：①一个面试业务上下文；②5 个 Agent 的运行时与状态机；③简历/岗位两张业务表；④Kafka（简历写了但代码里完全没有）。

---

## 1. 复用盘点（先明确什么不用动）

| 简历中的能力 | 现有实现（直接复用） | 位置 | 复用方式 |
|---|---|---|---|
| 4 种模型调用形态 | `ChatModelGateway`（同步/流式/结构化 JSON/原生工具调用） | `ai/application/ChatModelGateway.java` | 原样用，Agent 运行时只依赖这个接口 |
| 百炼/OpenAI 兼容、SSE 解析、重试、用量脱敏 | `BailianChatAdapter` + `DashScopeProperties` | `ai/infrastructure/` | 原样用 |
| Milvus + ES 混合检索 + RRF | `HybridRetriever` + `RrfFusion`（单路失败自动降级，带进度事件） | `ai/domain/` | 原样用 |
| Rerank 精排 | `BailianRerankAdapter` + `DefaultKnowledgeSearchService` | `ai/infrastructure/bailian/`、`knowledge/application/` | 原样用 |
| 检索编排（融合→精排→词面兜底→权限过滤→预算） | `DefaultKnowledgeSearchService` | `knowledge/application/` | 复用，改预算策略 |
| 文档解析 PDF/DOCX/HTML/MD/TXT | `DocumentParserRegistry` + `StructuredChunker` | `knowledge/infrastructure/parser/` | 简历解析直接复用 |
| 异步入库流水线（重试/状态机） | `KnowledgeIngestWorker` + `PersistentJobQueue` | `knowledge/application/`、`shared/async/` | 简历→入库→打标签走同一条 |
| 索引代发布（原子切换） | `IndexGenerationService` | `knowledge/infrastructure/search/` | 原样用 |
| 对象存储 | `KnowledgeObjectStore`（MinIO / Local） | `knowledge/application/` | 存简历原件 |
| OCR（扫描件简历） | `ocr/**`（DashScope，含每日配额） | `ocr/` | 原样用 |
| 工具调用循环 | `AiToolOrchestrator` + `ToolGateway` + `AiCapability` | `ai/harness/` | Evaluator/Reviewer 查题库、查评分规则时用 |
| 提示词分层 | `AiPromptSkeleton` + `SystemPromptResolver` + `PromptComposer` | `ai/chat/application/`、`ai/harness/application/` | Agent 复用同一套分层，不另起炉灶 |
| 注入防护 | `PromptInjectionGuard` | `ai/domain/` | **简历藏白字提示词是真实攻击面，必须挂在简历文本入口** |
| 流式响应 | `AiHarnessController.chatStream`（SSE：token/progress/done/error） | `ai/harness/api/` | 抄这个模式，扩事件类型 |
| 鉴权 / RBAC / 配额限流 | `shared/security/**` + `tenancy/**` | — | 原样用 |
| 用量与成本 | `DefaultAiUsageService` + `AiModelPricing` | `ai/application/` | Agent 每次调用都打点，否则面试成本不可控 |
| Agent 人格 + 提示词版本历史 | `TenantAgent` + `TenantAgentPromptHistory` | `agent/` | 存 5 个 Agent 的提示词版本，直接复用 |

**统计口径**：422 个文件中约 **340 个（≈81%）零改动**；约 40 个涉及角色枚举/权限需替换（见 §2.1）；约 30 个属 README §3.2 列的「改造后使用」。

---

## 2. 必须先做的三处「纠偏」（否则简历站不住）

### 2.1 角色域：`GYM_ADMIN / COACH / MEMBER` → `CANDIDATE / INTERVIEWER / ADMIN`

现实：`RoleCode` 枚举仍是健身域 —— **16 个文件**出现 `GYM_ADMIN`（其中 13 处是 `RoleCode.GYM_ADMIN` 引用，含 `DefaultTenantProvisioningService` 开通租户时默认建 GYM_ADMIN），`PermissionCatalog` 里是健身权限码。

改法（一次性、机械但必须做完）：

| 文件 | 改动 |
|---|---|
| `iam/domain/model/RoleCode.java` | `PLATFORM_ADMIN, ADMIN, CANDIDATE`（INTERVIEWER 若不做 B 端可省） |
| `platform/bootstrap/PermissionCatalog.java` | 权限码重写：`resume:read/write`、`job:read/write`、`interview:start`、`report:read`、`platform:ai:config` |
| `platform/bootstrap/SystemCatalogInitializer.java` | 角色显示名与默认权限映射 |
| `tenancy/application/DefaultTenantProvisioningService.java` | 默认管理员角色改 `ADMIN` |
| 其余 12 个文件 | `RoleCode.GYM_ADMIN` → `RoleCode.ADMIN` 等机械替换（`ActorAccess`、`HarnessToolCatalog`、`KnowledgeDocument` 受众集合等） |
| `knowledge/domain/model/KnowledgeCategory.java` | `GYM_RULES` → `JOB_RULES`；`ChunkStrategySelector` 关键词同步 |

> 预估 0.5～1 人日。**先做这个**，否则后面每写一个业务类都要绕开旧角色。
> ✅ **已完成**：`RoleCode` = `PLATFORM_ADMIN / ADMIN / INTERVIEWER / CANDIDATE`；21 个文件 76 处标识符替换（`GYM_ADMIN→ADMIN`、`COACH→INTERVIEWER`、`MEMBER→CANDIDATE`、`GYM_RULES→JOB_RULES`）；`PermissionCatalog` 整体重写为面试域（42 条权限 / 14 个菜单，resume、jobs、interviews、reports 四个新菜单），并已通过其内置一致性断言（ENTRIES 与菜单映射 42=42，无缺无多）；`AiRoleProfileResolver` 四个角色画像改写，且清掉了指向已删除插件的死 id。
> ⏳ 待办：仍有 35 个文件里的中文业务话术（如「门店运营助手」提示词、`ContextBudgeter` 注释）属健身域，随 P1/P3 写业务时一并替换。

### 2.2 Java 17 → 21（✅ 已完成）

`backend/pom.xml` 已改 `<java.version>21</java.version>`，`mvn -o clean compile` 输出 `javac [debug parameters release 21]`、BUILD SUCCESS（8.1s）。
本机 JDK：`D:\java\jdk-21`（构建时需把 `JAVA_HOME` 指向它）。
收益不只是对齐简历：**Agent 并发（Planner 之后 Resume/JobMatch 可并行）+ 虚拟线程**能显著简化编排代码（`Executors.newVirtualThreadPerTaskExecutor()`）。

### 2.3 Kafka（简历写了，代码里 0 处）

现状：`PersistentJobQueue` 有三个实现（Redis Stream / MySQL / 内存），**没有 Kafka**。
`PersistentJobQueue` 接口只有 8 个方法，加一个实现很轻：

```java
// shared/async/KafkaPersistentJobQueue.java  （约 200 行）
// enqueue  -> kafkaTemplate.send(topic, jobId)  + 落库（复用 MySQL 表做状态权威）
// claimNext-> 先查库拿租约，再由 @KafkaListener 消费触发执行（库为准，Kafka 只做唤醒）
// retry/complete/advance -> 与 RedisStreamPersistentJobQueue 同语义
```

要点（面试常追问，必须真的这么做）：**Kafka 用 at-least-once + 业务侧幂等键（job id）**，状态以 DB 为准，Kafka 只做「新任务唤醒」，不做唯一真相。这样重复消费不会重复扣费/重复生成报告。
配套：`docker-compose.yml` 加 `kafka`（KRaft 单节点即可）+ `application-dev.yml` 加 `panel.job-queue=kafka` 开关 + `.env.example` 加端口。

> 📄 详细设计（类结构、topic、故障演练、验收）见 [Kafka 开发计划](./Kafka开发计划.md)。compose 服务与 `.env.example` 变量已就位，等实现与联调。

> 决策点：如果不想维护两套队列，也可以**只保留 Kafka 作为「事件总线」**（面试进度、评估完成事件），任务状态仍走 Redis Stream。二选一，但简历里必须能自圆其说。

### 2.4 Spring AI：**已决定不引入**（用自己的框架）

原简历写的是「基于 Spring AI」，但本仓库已有自研 `ChatModelGateway` + `BailianChatAdapter`（重试、SSE 解析、用量解析、错误脱敏、原生工具调用、结构化 JSON 全都实现了）。再叠一层 Spring AI 只会让调用链变长、排障变难。

**决定**：多 Agent 直接构建在 `ChatModelGateway` 之上，不引入 `spring-ai` 依赖。

面试时的口径（这样讲反而加分）：
> 我把模型访问收敛成一个 `ChatModelGateway` 端口，上层 Agent 只依赖接口；底层实现可替换（当前是百炼 OpenAI 兼容实现）。多 Agent 的价值在编排与评估一致性，不在换一个 SDK，所以没有引入 Spring AI。

> ⚠️ 简历里「基于 Spring AI」这一句与实际代码不一致，建议改写为「基于自研模型网关抽象（ChatModelGateway）构建多 Agent 协作体系」，其余描述保持不变。

---

## 3. 目标结构（新增 `com.panel.interview` 业务上下文）

```
backend/src/main/java/com/panel/interview/
├── api/
│   ├── ResumeController.java            POST /api/v1/resumes          上传
│   │                                    GET  /api/v1/resumes/{id}     解析结果
│   ├── JobController.java               GET  /api/v1/jobs              岗位库
│   │                                    GET  /api/v1/jobs/{id}/match   匹配+差距
│   ├── InterviewController.java         POST /api/v1/interviews        创建会话
│   │                                    POST /api/v1/interviews/{id}/turns   答题（SSE）
│   │                                    GET  /api/v1/interviews/{id}   会话详情
│   └── ReportController.java            GET  /api/v1/interviews/{id}/report  报告
├── application/
│   ├── ResumeAnalysisService.java       上传→解析→结构化抽取→标签→PII 脱敏
│   ├── JobMatchingService.java          向量召回 + 规则打分 + 差距分析
│   ├── InterviewSessionService.java     会话生命周期（状态机驱动）
│   └── AssessmentReportService.java     评估汇总→复核→报告
├── agent/
│   ├── AgentSpec.java                   record：id/role/promptKey/tools/outputSchema/maxTokens
│   ├── AgentRuntime.java                单 Agent 执行（工具循环 or 结构化 JSON）
│   ├── AgentTrace.java                  每步输入输出→落库→转 SSE 进度
│   ├── InterviewGraph.java              编排状态机（见 §4）
│   ├── AgentRegistry.java               5 个 Agent 的装配
│   └── roles/
│       ├── PlannerAgent.java
│       ├── ResumeAgent.java
│       ├── InterviewerAgent.java
│       ├── EvaluatorAgent.java
│       └── ReviewerAgent.java
├── domain/
│   ├── ResumeProfile.java               record：技术栈/项目经历/能力标签/年限
│   ├── JobPosting.java + JobMatch.java
│   ├── InterviewSession.java + InterviewTurn.java
│   ├── Evaluation.java                  rubric 维度分 + 证据
│   └── InterviewReport.java
└── infrastructure/
    ├── persistence/                     JPA 实现（沿用现有 Repository 分层）
    └── prompt/PromptTemplateLoader.java  从 classpath:prompts/*.md 载入
```

> 为什么新建包而不是塞进 `ai/`：`ai/` 是**能力层**（模型、检索、工具），`interview/` 是**业务层**。混在一起后，以后加第二个业务（比如「求职辅导」）会重演 GymMind 的业务耦合。

---

## 4. 多 Agent 怎么编排（简历核心，也是最容易被追问的部分）

### 4.1 不要做「让模型自由决定流程」

`AiToolOrchestrator` 已验证「模型决策 + 受控执行」可行，但**流程编排不能交给模型**：面试一旦跑偏（反复追问同一题、评估时跳过题）就不可复现。

**做法**：Planner 只产出**受限的 JSON 计划**（题型序列、难度阶、覆盖维度），真正执行由 `InterviewGraph` 状态机推进，每一步都是 `AgentSpec` 的一次调用，输入输出都过 JSON Schema 校验。

```
CREATED
  └─(Planner)→ PLANNED            产出 plan_json：{topics[], difficultyCurve, turnBudget, focusGaps[]}
       └─(Resume)→ ANALYZED      结构化简历 + PII 脱敏后的文本 + tags
            └─(JobMatch)         向量召回岗位 + 差距清单（不调 LLM 也能算，LLM 只做解释）
                 └─(Interviewer)→ IN_PROGRESS  逐轮：提问 → 等回答 → 决策下一题
                      └─(Evaluator)→ EVALUATED  逐题 rubric 打分（结构化 JSON）
                           └─(Reviewer)→ REVIEWED  复核：反例检索 + 分数校准 + 申诉点
                                └─(Report)→ COMPLETED
```

状态机落库（`interview_session.status`），**每一步可重入**：模型超时/进程崩溃后由 `PersistentJobQueue` 重投，从最后成功的 step 继续，不重跑已完成步骤（费用可控）。

### 4.2 Agent 运行时复用现有端口

```java
public final class AgentRuntime {
    private final ChatModelGateway model;          // 现有
    private final AiToolOrchestrator tools;        // 现有（需要工具的 Agent 才注入）
    private final PromptInjectionGuard guard;      // 现有
    private final DefaultAiUsageService usage;     // 现有（每次调用打点）

    // 需要工具的 Agent（Interviewer 查题库、Reviewer 查原题）
    // → model.completeWithTools(...) + AiToolOrchestrator
    // 结构化输出的 Agent（Planner/Resume/Evaluator）
    // → model.completeStructuredJson(...) + Jackson 校验 + 失败重试一次
}
```

提示词复用 `AiPromptSkeleton` 的分层（平台 → 角色 → 租户 → 安全红线），5 个 Agent 各一份 `prompts/{planner,resume,interviewer,evaluator,reviewer}.md`，版本由 `TenantAgentPromptHistory` 落库（已存在，不用新写）。

### 4.3 每个 Agent 的职责边界（写进代码注释，面试可直接讲）

| Agent | 输入 | 输出（JSON Schema 约束） | 关键约束 |
|---|---|---|---|
| Planner | 简历摘要 + 岗位 JD + 历史薄弱点 | `{focus[], difficultyCurve[], turnBudget, rationale}` | turnBudget 上限 12；禁止产出知识库外主题 |
| Resume | 简历原始文本（已过注入防护） | `{skills[], years, projects[], tags[], gaps[]}` | 只抽取不推断；抽取不到的字段置 null，不许编造 |
| Interviewer | plan + 最近 N 轮对话 + 检索到的技术知识 | 下一题 `{question, type, difficulty, expect[]}` | 连续 2 轮不得同 type；每题必须带 `expect`（评分锚点） |
| Evaluator | 单题问答 + rubric | `{scores{technical,logic,expression}, evidence[], deductions[]}` | 每条扣分必须引用回答原文片段（可核查） |
| Reviewer | 全部题目评估 + 原题 | `{adjustedScores, disputes[], finalReport}` | 只在证据冲突时调分，改动需写理由（否则视为越权） |

**「evidence 必须引用原文」是这套设计可辩护的关键**——否则就是"模型随手打分"，面试官一问就崩。

---

## 5. 数据模型（新增表，`ddl-auto=update` 自动建）

```sql
-- 简历结构化结果（原件存 MinIO，解析文本入库）
CREATE TABLE resume_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT, user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,           -- 复用 knowledge_document
  structured_json JSON NOT NULL,          -- ResumeProfile 序列化
  masked_text LONGTEXT,                   -- 已脱敏，供检索与提示词使用
  tags JSON, status VARCHAR(32), created_at DATETIME
);

CREATE TABLE job_posting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, tenant_id BIGINT,
  title VARCHAR(128), company VARCHAR(128),
  requirements LONGTEXT, tags JSON,
  embedding_id VARCHAR(64),               -- Milvus 主键
  created_at DATETIME
);

CREATE TABLE job_match (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT, job_id BIGINT,
  score DECIMAL(5,2), gaps JSON, rationale LONGTEXT,
  created_at DATETIME
);

CREATE TABLE interview_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT, user_id BIGINT,
  resume_id BIGINT, job_id BIGINT,
  status VARCHAR(32) NOT NULL,            -- CREATED/PLANNED/ANALYZED/IN_PROGRESS/EVALUATED/REVIEWED/COMPLETED/FAILED
  plan_json JSON, current_turn INT, turn_budget INT,
  created_at DATETIME, updated_at DATETIME
);

CREATE TABLE interview_turn (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL, seq INT NOT NULL,
  question LONGTEXT, question_type VARCHAR(32), difficulty VARCHAR(16),
  expect_json JSON, answer LONGTEXT,
  asked_at DATETIME, answered_at DATETIME,
  UNIQUE KEY uk_session_seq (session_id, seq)
);

CREATE TABLE interview_evaluation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL, turn_id BIGINT,
  rubric_version VARCHAR(32),
  scores JSON, evidence JSON, deductions JSON,
  reviewer_notes LONGTEXT, adjusted BOOLEAN DEFAULT FALSE,
  created_at DATETIME
);

CREATE TABLE interview_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL UNIQUE,
  report_json JSON NOT NULL, summary LONGTEXT,
  created_at DATETIME
);
```

---

## 6. SSE 事件协议（在现有四种事件上扩展）

现有：`token` / `progress` / `done` / `error`（`AiHarnessController`）。面试场景**一轮时长可能超过 5 分钟**（现有 `SseEmitter(300_000L)`），需要：

1. **心跳**：每 15s 发 `event: ping`，防网关/浏览器断连；
2. **按轮粒度**推送，不在一个 SSE 里跑完整场面试；
3. **可续订**：`GET /interviews/{id}/events?lastEventId=`，服务端从 `interview_turn` 重放（事件带自增 id）。

新增事件（复用 `AiProgressEvent` 结构，`pluginId` 填 agent 名）：

| event | 时机 | data |
|---|---|---|
| `agent.started` / `agent.finished` | 每个 Agent 起止 | `{agent, step, durationMs}` |
| `question` | Interviewer 产出下一题 | `{seq, question, type, difficulty}` |
| `evaluation` | 单题评估完成 | `{seq, scores, evidence}`（**不流式吐分**，避免边答边变的错觉） |
| `report.ready` | 报告生成 | `{reportId}` |

---

## 7. 分阶段实施（含验收，按可交付切）

| 阶段 | 内容 | 改动点 | 人日 | 验收 |
|---|---|---|---|---|
| **P0 纠偏** ✅ | 角色域替换、Java 21、`.env`、启动验证 | `RoleCode`+`PermissionCatalog`+21 处引用；pom | 1.5 | 应用能起来，`admin` 登录，`/actuator/health` UP，现有 chat/stream 仍可用 |
| **P1 简历** 🟡 60% | 上传→解析→结构化（标签/脱敏待补） | 已建 `interview/**`（实体/仓储/服务/worker/控制器）+ `AsyncJobDispatcher`；复用 `DocumentParserRegistry`、`completeStructuredJson`、`KnowledgeObjectStore`、`PersistentJobQueue` | 3 | 传一份简历异步解析出 `{skills, years, projects, education, highlights, summary}`；**已验证**；缺 `PromptInjectionGuard` 与 tags |
| **P2 岗位匹配** | 岗位库 + 向量召回 + 差距分析 | `JobPosting` 入库（走同一 RAG 链路）、`JobMatchingService` | 3 | 给定简历+JD，返回 score 与 3 条以上可解释差距 |
| **P3 多 Agent 面试** 🟢 85% | 5 Agent + 状态机 + SSE 已跑通；断线续订待做 | `interview/agent/**`（5 Agent）、`InterviewService`、`InterviewController`（同步 + SSE 双接口）、`interview_session`/`interview_turn` | 6 | ✅ 跑完 3 轮真实面试（87/85/10 → 总分 61 由代码重算）；✅ 考察点不重复；✅「不知道」判 0 分；✅ SSE 逐 Agent 上报；缺 `Last-Event-ID` 重放 |
| **P4 评估复核** | rubric 打分 + Reviewer 调分 + 报告 | `EvaluatorAgent`、`ReviewerAgent`、`AssessmentReportService` | 4 | 每个扣分点都能定位到回答原文片段；Reviewer 调分有理由记录 |
| **P5 Kafka** | 队列/事件总线 + compose | `KafkaPersistentJobQueue`（或事件监听）、compose kafka、dev yml 开关 | 2 | 杀进程后任务不丢；重复消费不重复生成报告 |
| **P6 可选** | 虚拟线程并发、成本报表 | `Executors.newVirtualThreadPerTaskExecutor()`、用量聚合 | 1.5 | 并发路段（Resume/JobMatch）耗时下降，单场面试成本可见 |

合计 **≈19 人日**（单人）。P1/P2 可并行于 P3 的 Agent 骨架开发。（原 21.5 人日含 Spring AI 适配层，已取消。）

### 7.1 P1 实现记录（已完成部分）

| 项 | 内容 |
|---|---|
| 新增包 | `com.panel.interview`（`api` / `application` / `domain` / `infrastructure`） |
| 表 | `resume`、`resume_profile`（JPA 自动建表，**均无 `tenant_id`**） |
| 接口 | `POST /api/v1/interview/resumes`（202）、`GET /api/v1/interview/resumes`、`GET /{id}`、`GET /{id}/profile` |
| 异步 | `RESUME_PARSE:<id>` 入队 → Kafka 唤醒 → `AsyncJobDispatcher` 按类型路由 → `ResumeParseWorker` |
| 路由 | 新增 `AsyncJobHandler` 接口：知识入库与简历解析共用一张 `async_job` 表，靠 `type` 前缀分发，避免互相误领 |
| 幂等 | 已 `PARSED` 直接完成；内容 SHA-256 去重；重试 3 次后置 `FAILED` 并保留 `last_error` |
| 已验证 | 上传→建任务→领取→失败回落全链路；`PARSED` 详情与 profile 读取；中文 JSON 往返 |
| 模型 | `deepseek-v4.1-flash-1017`（`.env` 的 `DASHSCOPE_CHAT_MODEL`）；一份 DOCX 简历 5 秒内解析出 10 项技能/4 条亮点 |
| 配置修复 | `.env` 原本**从未被加载**（`META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor` 不是 Spring Boot 认的注册格式）。改为正确的 `META-INF/spring.factories` + `spring.config.import` 双保险 |
| 未做 | `PromptInjectionGuard` 接入、`tags`、SSE 进度推送、PDF 实文件回归 |

> 顺带修掉一个真实缺陷：`JdbcJobStateStore.enqueue` 原来用 `Instant.EPOCH` 写 `next_attempt_at`，在 UTC 会话下被 MySQL 判为非法时间（`ERROR 1292`），导致入队直接 500。改为数据库 `now(6)`。

### 7.2 P3 实现记录（5 Agent 已跑通）

| Agent | 输入 → 输出 | 代码级约束（不是提示词约束） |
|---|---|---|
| Planner | 简历画像 + 轮次 → 考察点矩阵 | 点数必须等于 `turnBudget`，否则抛异常 |
| Interviewer | 矩阵 + 已问题目 → 下一题 | `pointId` 必须存在且未被覆盖，重复即拒绝 |
| Evaluator | 题目 + 回答 → 四维分数 + 证据 | 无 `evidence.quote` 视为无效评分 |
| Reviewer | 题目 + 回答 + 评分 → 调分/确认 | 只在「无证据 / 有内容却判 0 分」时触发，不重复打分 |
| Report | 逐题记录 → 报告正文 | **不采信模型总分**，由代码按逐题分数重算 |

接口：`POST /api/v1/interview/sessions`（201）、`POST /sessions/{id}/answers`、`GET /sessions/{id}`、`GET /sessions/{id}/report`。

实测（简历=张伟，`turnBudget=3`，模型 `deepseek-v4.1-flash-1017`）：

| 轮次 | 考察点 | 耗时 | 得分 | 证据条数 |
|---|---|---|---|---|
| Q1 | Redis / ADVANCED | 5s（建会话含 Planner） | 87 | 2 |
| Q2 | Kafka / ADVANCED | 4s | 85 | 2 |
| Q3 | Spring Cloud / EXPERT | 8s | 10 | 2 |
| 报告 | — | 1 次调用 | 总分 61 = ⌊(87+85+10)/3⌋ | — |

- 三轮考察点无重复，题目全部来自简历真实技术栈；
- 故意敷衍的 Spring Cloud 回答被判 10 分，报告准确指出「完全未触及任何组件」；
- 「不知道，没做过」类回答 → 0 分、`answered=false`、feedback 给出该补的知识点。

未做：SSE 流式与断线续订（当前为同步接口）、Reviewer 触发路径的构造用例、每步 trace 落库。

### 7.3 SSE 流式（P3 收尾）

同一套编排代码通过 `InterviewProgressListener` 输出进度：同步接口传 `NOOP`，流式接口传"写 `SseEmitter`"的实现，**不为流式复制第二套流程**。

| 接口 | 事件序列 |
|---|---|
| `POST /api/v1/interview/sessions/stream` | `agent`(Planner STARTED/COMPLETED) → `session` → `agent`(Interviewer) → `question` → `done` |
| `POST /api/v1/interview/sessions/{id}/answers/stream` | `agent`(Evaluator) → `evaluation` → `agent`(Interviewer) → `question` → `done`；最后一轮把 `question` 换成 `report` |

- 执行线程用 `Executors.newVirtualThreadPerTaskExecutor()`：模型调用是阻塞 IO，不占 Tomcat 线程；
- 每个 `agent` 事件带 `durationMs`，前端可直接画"Planner 2.7s / Interviewer 1.4s"；
- 客户端断开时抛 `IllegalStateException`，事务回滚，不会出现"界面断了但后台继续扣 token"。

实测事件（`turnBudget=3`）：Planner 2749ms → Interviewer 1369ms → Evaluator 2827ms（含 2 条原文证据 + 4 条扣分理由）→ Interviewer 1610ms → … → `report`。

未做：`Last-Event-ID` 断线重放（当前重连续需重新拉一次 `GET /sessions/{id}`）、Reviewer 触发路径的构造用例、每步 trace 落库。

---

## 8. 风险与对冲（面试会追问，先想清楚）

1. **成本失控**：8 轮面试 × (检索 + 生成 + 评估 + 复核) 至少 30 次模型调用。必须：每步落 token 用量（复用 `DefaultAiUsageService`）、`turnBudget` 硬上限、Evaluator 用便宜模型、Reviewer 只对有争议题调一次。
2. **评估不可信**：rubric 必须版本化（`rubric_version`），分数变化可归因；否则同一份回答两次跑分不同，用户会投诉。
3. **简历注入**：PDF 白字/隐藏文本是真实攻击面（README 已列），入口必须 `PromptInjectionGuard` + 只把**脱敏后文本**喂模型。
4. ~~**多租户**~~ **单租户**：多租户是上一系统（GymMind）的底座遗留，本产品不使用。新表（`resume`、`resume_profile`、`interview_*`）一律不带 `tenant_id`，接口不做租户隔离，`tenancy` 代码保留但不再扩展。
5. **Kafka 语义**：别声称 exactly-once，说清 at-least-once + 幂等键，这是更可信的工程表述。
6. **Milvus/ES 一致性问题**：现有 `IndexGenerationService` 是"先建新代再原子切换"，简历入库要沿用（否则删改简历后向量残留）。

---

## 9. 最小可跑通路径（如果时间紧）

只做 P0 → P1 → P3（跳过岗位匹配、Kafka、Spring AI，评估用单轮 rubric）：
**「上传简历 → 5 轮模拟面试（SSE 实时）→ 生成报告」**，约 8～9 人日，已经能支撑简历里 4 条职责的描述（除去 Milvus/ES 之外的 Kafka 与 Spring AI 需删或改口径）。

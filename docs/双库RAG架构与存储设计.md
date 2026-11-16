# 双库 RAG 架构与存储设计

> 对象：技术文档库（TECH_DOC）+ 面试题库（QUESTION_BANK），当前约 30 个 md 文件，后续持续扩张。
> 要回答的问题：数据放哪、要不要上 Neo4j、两个库怎么打通。

## 1. 结论先行

1. **现在不需要 Neo4j。** 图数据用 MySQL 8 的邻接表 + 递归 CTE 就够，30 篇文档的规模离图的瓶颈还很远。
2. **两个库共用一套检索底座**，按知识库类型开不同通道：题库走「元数据过滤 + BM25 + 向量」，文档走「BM25 + 向量 + 图谱扩展」。
3. **加一层共享概念表**，让题库和技术文档通过同一个概念词表连接，这是产品差异化的关键。
4. 存储分工：**MySQL 存业务主数据和图，ES 存全文与过滤字段，Milvus 存向量，MinIO 存原始文件**。

## 2. 总体架构

```
                         ┌──────────────────────────────┐
                         │ 前端：问答助手 / 刷题 / 文档    │
                         └──────────────┬───────────────┘
                                        │ HTTP / SSE
                         ┌──────────────▼───────────────┐
                         │ Spring Boot API               │
                         │  ├─ 问答编排（K3）             │
                         │  ├─ 检索编排 RetrievalOrchestrator │
                         │  ├─ 图谱构建 Worker            │
                         │  └─ 题库解析 / 标签 Worker      │
                         └───┬────────┬────────┬────────┘
                             │        │        │
        ┌────────────────────▼──┐  ┌──▼─────────────┐  ┌▼─────────────────┐
        │ MySQL 8               │  │ Elasticsearch  │  │ Milvus            │
        │ 业务主数据             │  │ 全文 + 过滤字段 │  │ 向量集合           │
        │ 概念/关系/证据         │  │ chunk / 题目   │  │ chunk / 题目 / 概念│
        │ 题库结构化数据         │  │                │  │                   │
        └───────────────────────┘  └────────────────┘  └───────────────────┘
                             │
                    ┌────────▼────────┐
                    │ MinIO：原始 md   │
                    └─────────────────┘
```

请求链路（问答）：

```
问题
 ├─ 意图/知识库路由
 ├─ 题库通道：过滤(公司/难度/标签) → BM25(题干加权) → 向量 → RRF
 ├─ 文档通道：BM25 + 向量 + 图谱扩展(概念→1~2跳→证据块) → RRF
 ├─ rerank → 阈值 → 上下文组装（含关系路径）
 └─ 流式生成 → 引用（题目/文档/关系）
```

## 3. 存储选型

| 数据 | 存储 | 理由 |
| --- | --- | --- |
| 原始 md 文件 | MinIO | 已有对象存储；原文不可变，索引可重建 |
| 知识库 / 文档 / chunk 主数据 | MySQL | 已有 `kb_base` / `kb_document` / `kb_chunk` |
| 概念词表（跨库共享） | MySQL `kb_concept` | 需要事务、唯一约束、联表 |
| 图谱关系 | MySQL `kb_graph_edge` | 1~2 跳查询用递归 CTE；与业务同库好维护 |
| 概念出现证据 | MySQL `kb_graph_mention` | 精确到 chunk + offset，支撑引用 |
| 题库结构化数据 | MySQL `kb_question` | 题目是独立对象，需要过滤和排序 |
| 全文检索 / 过滤 | Elasticsearch | 题干 BM25、tag/公司/难度过滤、高亮 |
| chunk 向量 | Milvus | 已有；按 kb_id 分区 |
| 题目问法向量 | Milvus `kb_question_vec` | 相似题、同义问法合并 |
| 概念向量 | Milvus `kb_concept_vec` | 实体链接（别名之外的第二道匹配） |
| 会话/消息 | MySQL | 已有 `kb_chat_session` / `kb_chat_message` |
| 全局问答缓存（可选） | Redis | 社区摘要/主题概览结果缓存，后置 |

**一个原则**：MySQL 是事实来源，ES 和 Milvus 都是可重建的索引。任何时候索引坏了，能从 MySQL + MinIO 重建。

## 4. 数据模型

### 4.1 复用现有

`kb_base`、`kb_document`、`kb_chunk`、`kb_chat_session`、`kb_chat_message` 保持不变。

### 4.2 新增：共享概念层

```sql
CREATE TABLE `kb_concept` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NULL,
  `canonical_name` varchar(128) NOT NULL,   -- Redis 持久化
  `normalized_name` varchar(128) NOT NULL,  -- redis持久化
  `type` varchar(24) NOT NULL,              -- CONCEPT/COMPONENT/COMMAND/ERROR/PATTERN/METRIC
  `description` varchar(512) NULL,
  `aliases_json` text NULL,                 -- ["RDB","Redis Database"]
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_concept_name` (`tenant_id`,`normalized_name`),
  KEY `idx_kb_concept_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 概念是**全局**的：题库和技术文档引用同一行。这样“这道题考的是 RDB/AOF”和“文档里有 RDB/AOF 的关系”天然能对上。

### 4.3 新增：图谱关系与证据

```sql
CREATE TABLE `kb_graph_edge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,                  -- 关系属于哪个库（证据来自该库）
  `source_concept_id` bigint NOT NULL,
  `target_concept_id` bigint NOT NULL,
  `type` varchar(24) NOT NULL,              -- CONTRASTS_WITH / CAUSES / DEPENDS_ON ...
  `weight` decimal(4,3) NOT NULL,
  `evidence_chunk_id` bigint NULL,
  `document_id` bigint NULL,
  `extractor_version` varchar(32) NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_edge` (`kb_id`,`source_concept_id`,`target_concept_id`,`type`),
  KEY `idx_kb_graph_edge_source` (`source_concept_id`),
  KEY `idx_kb_graph_edge_target` (`target_concept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `kb_graph_mention` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `concept_id` bigint NOT NULL,
  `chunk_id` bigint NOT NULL,
  `kb_id` bigint NOT NULL,
  `start_offset` int NULL,
  `end_offset` int NULL,
  `quote` varchar(255) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_mention` (`concept_id`,`chunk_id`,`start_offset`),
  KEY `idx_kb_graph_mention_chunk` (`chunk_id`),
  KEY `idx_kb_graph_mention_kb` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.4 新增：题库结构化

```sql
CREATE TABLE `kb_question` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,
  `document_id` bigint NOT NULL,
  `chunk_id` bigint NULL,                   -- 对应的分块（原文/答案）
  `stem` text NOT NULL,                     -- 题干（检索主字段）
  `answer` longtext NULL,                   -- 参考答案
  `difficulty` varchar(16) NULL,            -- EASY/MEDIUM/HARD
  `company` varchar(64) NULL,
  `role` varchar(64) NULL,                  -- 后端/前端/算法
  `year` int NULL,
  `tags_json` text NULL,                    -- ["Redis","持久化"]
  `fingerprint` varchar(64) NULL,           -- 题干归一化指纹，用于去重
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_question_kb` (`kb_id`,`difficulty`),
  KEY `idx_kb_question_fingerprint` (`kb_id`,`fingerprint`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `kb_question_concept` (
  `question_id` bigint NOT NULL,
  `concept_id` bigint NOT NULL,
  `weight` decimal(4,3) NOT NULL DEFAULT 1.000,
  PRIMARY KEY (`question_id`,`concept_id`),
  KEY `idx_kb_question_concept_concept` (`concept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`kb_question_concept` 是跨库联动的桥：题目 → 概念 → 文档证据。

## 5. 索引设计

### 5.1 ES 索引

`kb_chunk_v1`（两个库共用，字段按类型填充）：

| 字段 | 说明 |
| --- | --- |
| `kb_id` / `document_id` / `chunk_key` | 过滤与回表 |
| `kind` | `TECH_DOC` / `QUESTION_BANK` |
| `text` | 正文（ik_max_word） |
| `stem` | 仅题库：题干，查询时权重 ×3 |
| `heading` | 标题 |
| `tags` / `company` / `difficulty` / `year` | 题库过滤字段 |
| `concept_ids` | 概念 ID 数组，term 过滤 |
| `published` | 与现有 `publishedOnly` 对齐 |

用别名 `kb_chunk` 指向 `kb_chunk_v1`，重建时切换别名，避免停机。

### 5.2 Milvus 集合

| 集合 | 内容 | 主要字段 |
| --- | --- | --- |
| `kb_chunk_vec` | chunk 文本向量 | `kb_id`, `chunk_key`, `document_id`, `kind` |
| `kb_question_vec` | 题干向量 | `kb_id`, `question_id` |
| `kb_concept_vec` | 概念名 + 描述向量 | `tenant_id`, `concept_id` |
| `kb_entity_name_vec` | 别名向量（可选） | `concept_id`, `alias` |

分区按 `kb_id`，过滤用标量字段。

## 6. 图查询：MySQL 够不够

单库规模估算（30 篇文档）：

- chunk：1000~2000
- 概念：3000~6000
- 边：10000~30000
- 提及：10000~30000

这个量级下，MySQL 的唯一索引 + 递归 CTE 完全够。两跳邻域查询：

```sql
WITH RECURSIVE reach (concept_id, depth, weight) AS (
  SELECT id, 0, 1.0 FROM kb_concept WHERE id IN (:seedIds)
  UNION ALL
  SELECT CASE WHEN e.source_concept_id = r.concept_id
              THEN e.target_concept_id ELSE e.source_concept_id END,
         r.depth + 1,
         r.weight * e.weight * 0.7
  FROM reach r
  JOIN kb_graph_edge e
    ON e.source_concept_id = r.concept_id OR e.target_concept_id = r.concept_id
  WHERE e.kb_id = :kbId AND r.depth < 2
)
SELECT concept_id, MAX(weight) AS weight
FROM reach
GROUP BY concept_id;
```

要点：

- `kb_graph_edge` 的两端都要建索引；
- 限制每层邻居数（按 weight 截断），防止图爆炸；
- 结果去重后取 Top N，再查 `kb_graph_mention` 拿 chunk。

## 7. 要不要 Neo4j

### 7.1 现在不上的理由

| 维度 | MySQL 8 | Neo4j |
| --- | --- | --- |
| 1~2 跳邻域 | 递归 CTE，毫秒级 | 原生遍历，稍快 |
| 路径查询 | 写起来麻烦 | 强项 |
| 社区检测/PageRank | 不支持，需离线算 | 图算法库内置 |
| 事务一致性 | 与业务同库，天然一致 | 跨库，需要同步 |
| 运维 | 已有 | 多一个进程/备份/权限/监控 |
| 规模 | 百万边以内轻松 | 千万边以上更从容 |
| 团队成本 | 都会 SQL | 要学 Cypher 和建模 |

你现在是 30 篇文档、万级边，**上 Neo4j 属于为 1% 的场景付 10 倍的运维成本**。

### 7.2 什么时候换

同时满足两条以上再考虑：

- 常规查询需要 **3 跳以上**，或者要查「两个概念之间的路径」；
- 要做**在线**社区检测、PageRank、相似度传播；
- 图规模到**千万级边**，或者多个知识库共享一张大图；
- 需要多租户隔离的图权限；
- 团队已经有 Neo4j 运维经验。

### 7.3 换的时候怎么不痛

把图访问收在一个接口后面：

```java
public interface GraphRepository {
    List<GraphNeighbor> neighbors(Long kbId, Collection<Long> conceptIds, int hops, Set<String> types, int limit);
    List<GraphPath> paths(Long kbId, Long fromConceptId, Long toConceptId, int maxHops);
    List<Long> conceptsByChunk(Long chunkId);
    List<Long> chunksByConcept(Long conceptId, Long kbId, int limit);
}
```

- 现在：`JdbcGraphRepository`（MySQL）。
- 将来：`Neo4jGraphRepository`，只换实现，检索层和前端不动。
- 离线社区检测可以直接把边导出成 CSV，用 Python（networkx/igraph）跑，**也不需要 Neo4j 在线**。

## 8. 两个库怎么联动

```
题库命中题目
   └─ kb_question_concept → 概念
        └─ kb_graph_mention → 文档 chunk（参考答案/延伸阅读）

文档命中段落
   └─ kb_graph_mention → 概念
        └─ kb_question_concept → 相关面试题（练一练）
```

对外两个接口：

- `GET /api/v1/kb/questions/{id}/references`：题目 → 文档证据；
- `GET /api/v1/kb/concepts/{id}/related`：概念 → 题目 + 文档。

这样“刷题 → 看不懂 → 看文档 → 再练同类题”的闭环就有了。

## 9. 构建与更新流程

```
上传 md
  → MinIO
  → 解析（按题目边界 / 按标题）→ kb_document + kb_chunk + kb_question
  → 索引：ES（chunk/题目）+ Milvus（chunk/题目向量）
  → 抽取 Worker：
       文档：实体/关系/证据 → kb_concept + kb_graph_edge + kb_graph_mention
       题库：概念标签 → kb_question_concept
  → 概念归一化：别名 + 向量聚类 + 人工复核
```

更新时按 `document_id` 删除旧数据重建；`extractor_version` 变化时支持重跑对比。

## 10. 容量与运维

- MySQL：30 篇文档阶段无压力；单表千万行前不用分库。
- ES：单索引 + 别名切换；分片数 1~2 即可。
- Milvus：按 `kb_id` 分区，collection 数量控制在个位数。
- 备份：MySQL 每日全量 + binlog；ES/Milvus 可从 MySQL + MinIO 重建，不要求强备份。
- 监控：检索 P95、图谱扩展耗时、LLM 调用失败率、索引重建耗时。

## 11. 落地顺序

| 阶段 | 内容 | 预估 |
| --- | --- | --- |
| P0 | 题库结构化（题干/答案/标签/难度）+ ES 字段 + 元数据过滤 | 2–3 天 |
| P1 | 共享概念表 + 题目概念标签 + 文档对比/因果/前置边 + 图谱扩展 | 1 周 |
| P2 | 跨库联动接口 + 前端「看文档 / 练同类题」 | 3–5 天 |
| P3 | 主题摘要/知识地图（可选，先文档级摘要，不上社区检测） | 1 周 |
| P4 | 触发条件满足后再评估 Neo4j | 视情况 |

## 12. 风险

| 风险 | 对策 |
| --- | --- |
| 概念词表漂移 | 建 canonical + alias 表，定期人工复核 |
| 题库标签不一致 | 抽取用固定标签集 + 置信度，低置信人工确认 |
| 近似重复题 | 题干指纹 + 向量聚类去重 |
| 图过期 | 按 document 重建 + extractor_version |
| 索引与主数据不一致 | ES/Milvus 全量重建脚本 + 对账任务 |
| 过早优化 | 图谱通道按库开关，随时可关 |

---

**一句话**：MySQL 存事实与图，ES 存检索与过滤，Milvus 存向量，MinIO 存原文；Neo4j 现在不上，等「多跳/路径/在线图算法」成为常态再说。

## 13. 附录：规模演进测算（100–200 篇 × 每篇 20 个切片）

### 13.1 数据量

| 资源 | 30 篇（现状） | 200 篇 | 判断 |
| --- | --- | --- | --- |
| chunk | 600 | 4,000 | 小 |
| 概念（去重后） | 3,000–6,000 | 8,000–15,000 | 需要认真做消解 |
| 图谱边 | 1–3 万 | 5–10 万 | MySQL 无压力 |
| 提及 | 1–3 万 | 5–8 万 | 无压力 |
| ES 文档 | 600 | 4,000 | 单索引 2 分片 |
| Milvus 向量 | 600 | 4,000 | 单 collection |
| 全量抽取 | 十几分钟 | 1–2 小时（并发 8） | 需要断点续跑 |

结论：**架构不用变，Neo4j 依然不需要。** 变的是下面四件运维层面的事。

### 13.2 实体消解从“可做”变成“必须做”

1.5 万个概念里，同一个东西会有多种写法（`RDB` / `Redis Database` / `快照持久化`）。不做消解，图上会出现大量重复节点，关系被稀释，扩展出来的邻居全是噪声。

- 别名表 + 向量聚类（cosine > 0.92）先自动合并；
- 低置信合并进复核队列，做一个最简单的审核页（列出候选、点合并/拒绝）；
- 给概念加 `tenant_id` 和领域前缀（`redis:事务` vs `spring:事务`）。

### 13.3 图查询要加“刹车”

200 篇文档后，实体平均度数可能到 8–15。两跳从 5 个种子出发，最坏会摸到 5 × 15 × 15 ≈ 1,100 个概念，再转成 chunk 就是几千个，重排直接被打爆。

默认参数建议：

- 种子概念：≤ 5 个；
- 默认 **1 跳**，只有指定关系类型（`CONTRASTS_WITH` / `CAUSES` / `PREREQUISITE`）才开 2 跳；
- 每层邻居按 `weight` 截断到 20；
- 扩展出的 chunk 总数 ≤ 30，再和 BM25/向量一起 RRF。

### 13.4 抽取流水线要能重跑

200 篇至少要全量跑两三次（提示词升级、概念表调整）。必须有的能力：

- 按 `document_id` 幂等重建；
- 批量（一次 4–8 个 chunk）+ 并发 4–8 + 失败重试；
- 断点续跑（记录已处理的 chunk）；
- `extractor_version`，支持新旧版本 A/B；
- 抽取结果先落“草稿表”，审核后再进正式图（可选）。

粗算成本：4,000 chunk × 约 1,000 token ≈ 400 万 token，用便宜模型是几十元量级；时间上并发 8 大约 1–2 小时。**成本不是问题，重跑和消解才是。**

### 13.5 全局问答：这时才有讨论价值

4,000 个 chunk 大约 300–400 万 token，确实放不进上下文。但也不需要社区检测：

- 200 篇文档摘要 × 约 300 token ≈ 6 万 token，一次就能喂给模型；
- 或者聚成 30–50 个主题摘要，约 1–2 万 token，更适合 map-reduce。

所以“100–200 篇”这个量级，**文档摘要 + 主题摘要**足够覆盖“这个库讲了什么”，社区摘要仍然是 P3 之后的事。

### 13.6 这个规模下可以顺手加的

| 项 | 是否必要 | 说明 |
| --- | --- | --- |
| Redis 缓存 | 可选 | 概念链接、query embedding、高频检索结果 |
| ES 别名切换 | 建议 | 重建索引不停机 |
| Milvus HNSW | 建议 | 默认即可，4k 向量很轻 |
| 异步重建任务 | 建议 | 文档更新触发增量抽取 |
| 概念审核页 | 建议 | 消解质量的生命线 |
| Neo4j | **不需要** | 等百万级边或 3+ 跳再说 |

### 13.7 参数建议（这个规模）

| 参数 | 建议值 |
| --- | --- |
| chunk 大小 | 600–900 字，overlap 80–120 |
| 抽取窗口 | 当前 chunk + 前后各 1 块 |
| topK 召回 | 每路 20，融合后 20 |
| rerank 输入 | 20–30 |
| 图谱 1 跳邻居上限 | 20 / 概念 |
| 图谱 2 跳 | 仅白名单关系类型 |
| 扩展 chunk 上限 | 30 |
| 最终上下文 | 6–10 个 chunk |

## 14. 实施进度

### P0 已完成（本次）

- [x] V4 迁移：`kb_question` / `kb_concept` / `kb_question_concept` / `kb_graph_edge` / `kb_graph_mention`
- [x] 题库解析：`KbQuestionParser`（题号、标题、兜底三种策略；公司/年份/难度/角色/标签识别），随文档入库自动执行
- [x] 题库浏览：`GET /api/v1/kb/{kbId}/questions`、`GET /api/v1/kb/questions/{id}`，前端「面试题库」页
- [x] ES 字段：`kind` / `questionId` / `stem` / `difficulty` / `company` / `year` / `tags`，`stem` 查询权重 ×3
- [x] 元数据过滤：BM25 filter + 融合后按 chunkKey 回查 ES 兜底（向量通道同样生效）
- [x] 单测：`KbQuestionParserTest`（编号拆题、标题兜底、答案内有序列表不误拆）

### P1 已完成

- [x] 概念抽取 Worker：`KbGraphExtractionService`，LLM 结构化 JSON → `kb_concept` / `kb_graph_edge` / `kb_graph_mention`，按文档幂等，默认关闭
- [x] 图检索：`KbGraphRetriever` 实体链接 + 邻域扩展 + 证据块，`graph-link` / `graph-expand` 两阶段，参与 RRF
- [x] 双库联动（读侧）：`GET /api/v1/kb/questions/{id}/references`，题目 → 概念 → 文档片段
- [x] 管理接口：`POST /api/v1/kb/{kbId}/graph/rebuild`、`GET /api/v1/kb/{kbId}/graph/stats`、`GET /api/v1/kb/{kbId}/concepts`
- [x] 前端：题库详情展示「关联文档片段」；思考链路显示图谱命中徽标
- [x] 单测：`KbConceptTest`

启用方式：`PANEL_KB_GRAPH_ENABLED=true`（或 `panel.kb.graph.enabled`），然后对已有文档调用 rebuild；
题目→文档联动依赖图谱已抽取，未抽取时返回空列表。

### P2 待做

- [ ] 概念审核页（别名/合并/拒绝）
- [ ] 知识库概览（文档级摘要 + 主题索引，不上社区检测）
- [ ] 题目概念表 `kb_question_concept` 的自动标注（当前题干实体链接是即时计算，不落表）




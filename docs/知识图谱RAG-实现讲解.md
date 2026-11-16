# 知识图谱 RAG：怎么做，以及它会不会让现有 RAG 作废

> 面向当前这个「技术知识库」：文档已经分块（`kb_chunk`），有 BM25（ES）+ 向量（Milvus）+ RRF + rerank 的检索链路，问答助手刚接上。本文讲两件事：图谱怎么建、怎么用；以及它和现有 RAG 是什么关系。

## 0. 两个结论

1. **图谱是给 RAG 增加的一路「关系索引」，不是把文档 RAG 推倒重来。**
2. **现有链路继续用**：召回 → 融合 → 重排 → 生成 → 引用都不变。图谱只在两个位置加动作：
   - 建库时：把文档里的实体和关系抽出来；
   - 查询时：按关系去找更多相关段落，并把关系路径交给模型。

## 1. 先看一个例子：图谱补的是什么

问题：**「RDB 和 AOF 怎么选？」**

| 检索方式 | 可能的结果 |
| --- | --- |
| BM25 | 命中含「RDB」「AOF」字样的段落，可能只命中 RDB 定义 |
| 向量 | 命中语义相近的段落，可能只命中 AOF 说明 |
| 图谱 | 识别出 `RDB`、`AOF` 两个实体 → 找到 `RDB ──CONTRASTS_WITH── AOF` → 把两边的定义、对比证据、适用场景一起召回 |
| 生成 | 输出结构化对比，每个结论都能点回原文 |

注意：图谱**不生产答案**，它改变的是「哪些段落被召回」和「上下文里多一条关系说明」。答案的依据仍然是 `kb_chunk` 里的原文。

## 2. 图谱的三层结构

```
本体（Schema）      ：有哪些实体类型、关系类型、约束
   ↓
实例（Nodes/Edges） ：RDB ──CONTRASTS_WITH── AOF，weight=0.9
   ↓
证据（Mentions）    ：这条边来自 chunk#42 的某句话
```

一条硬规则：**没有证据的边不要入库**。因为答案必须能引用原文；抽出来但找不到出处的「关系」，等于让模型自由发挥。

## 3. 建模：技术知识库该抽什么

### 3.1 实体类型

| 类型 | 例子 | 说明 |
| --- | --- | --- |
| `CONCEPT` | RDB、AOF、MVCC、CAP | 可被单独提问的技术概念 |
| `COMPONENT` | InnoDB、Sentinel、Kafka Broker | 具体组件/模块 |
| `COMMAND` | `BGSAVE`、`appendfsync` | 命令、配置项 |
| `ERROR` | `OOM command not allowed` | 报错、异常 |
| `PATTERN` | 缓存穿透、幂等、熔断 | 模式/方案 |
| `METRIC` | QPS、P99、RTO | 指标 |

粒度判断：**能单独回答「X 是什么/X 和 Y 什么关系」的名词**才抽。不要抽「使用」「配置」「优化」这类动词短语，否则图会变成一团噪声。

### 3.2 关系类型

| 关系 | 语义 | 典型问题 |
| --- | --- | --- |
| `DEPENDS_ON` | A 依赖 B | 「学 A 要先会什么」 |
| `PART_OF` | A 属于 B | 「B 由哪些部分组成」 |
| `CONTRASTS_WITH` | A 与 B 是对比/替代关系 | 「A 和 B 怎么选」 |
| `CAUSES` | A 导致 B | 「为什么会 B」 |
| `PREREQUISITE` | A 是 B 的前置知识 | 学习路径 |
| `SOLVES` | A 解决 B | 「B 怎么排查」 |
| `CONFIGURES` | A 配置/影响 B | 参数类问题 |
| `MENTIONS` | 弱关联，兜底 | 相关推荐 |

关系要**有方向**（`CONTRASTS_WITH` 是对称的，其余大多有向），并允许同一对实体存在多条不同类型边。

### 3.3 属性

实体：`canonical_name`、`aliases`（别名）、`type`、`description`、`embedding`。
边：`type`、`weight`（置信度）、`evidence_chunk_id`、`document_id`、`extractor_version`。

## 4. 构建流水线（7 步）

### 第 1 步：切块（复用现有 `kb_chunk`）

- 直接复用当前 400–800 字的分块，但抽取时**带上 heading 和前后各 1 块**作为窗口，避免「A 和 B 的区别」被切到两块里。
- 代码块、表格单独成块，抽取时保留原文。

### 第 2 步：LLM 抽取（结构化 JSON）

一个 chunk 一次调用，温度 0，只允许 schema 内的类型，要求给出 evidence 原文：

```text
你是技术文档的图谱抽取器。只输出 JSON，不要解释。

允许的实体类型：CONCEPT, COMPONENT, COMMAND, ERROR, PATTERN, METRIC
允许的关系类型：DEPENDS_ON, PART_OF, CONTRASTS_WITH, CAUSES,
               PREREQUISITE, SOLVES, CONFIGURES, MENTIONS

规则：
1. 只抽文档里明确出现的技术名词，不要补常识。
2. 每条关系必须给出原文证据（20~80 字），找不到证据就不要输出。
3. 同一实体只出现一次，别名放进 aliases。
4. confidence 取 0~1，模棱两可给 0.5 以下。

输出格式：
{"entities":[{"name":"RDB","type":"CONCEPT","aliases":["Redis Database"],
  "description":"Redis 的全量快照持久化方式"}],
 "relations":[{"source":"RDB","target":"AOF","type":"CONTRASTS_WITH",
  "evidence":"RDB 恢复快但可能丢数据，AOF 更完整但恢复慢","confidence":0.92}]}

文档：<heading>
<chunk 原文>
```

### 第 3 步：过滤与合并

- `confidence < 0.6` 丢弃；无 evidence 丢弃；自环丢弃。
- 同一 `(source, target, type)` 合并，`weight = max(conf)`，证据保留多条。
- 实体名做基础清洗：全半角、大小写、首尾空白、中英文括号。

### 第 4 步：实体消解（最难的一步）

图谱质量 80% 取决于这里，而不是 LLM 抽取。

1. **别名表**：人工维护高频别名（`RDB` / `Redis Database` / `快照持久化`）。
2. **规则归一**：小写、去空格、去版本号（`MySQL 8` → `mysql`）。
3. **向量聚类**：实体名 + 描述做 embedding，cosine > 0.92 视为同一实体候选。
4. **人工复核**：低置信合并、同名不同义（如「事务」在 MySQL 和 Spring 里不是一回事）进队列。

最终每个实体有一个 `canonical_id`，查询时任何别名都映射到它。

### 第 5 步：落库

见第 5 节 DDL。三张表：实体、边、提及（证据）。

### 第 6 步：证据挂接

`kb_graph_mention` 记录 `entity_id`、`chunk_id`、`start_offset`、`end_offset`、`quote`。
这样回答引用可以精确到「哪个文档的哪一段落」，图谱视图也能高亮原文。

### 第 7 步：增量与回收

- 文档重索引时，按 `document_id` 删除旧边和提及，重新抽取，避免脏数据。
- 没有任何证据的实体软删除。
- 边权重随证据数增加、随时间轻微衰减。
- 记录 `extractor_version`，提示词升级后可批量重跑对比。

## 5. 存储：先用 MySQL 8

现有栈就是 MySQL 8，**先不要引入 Neo4j**。三张表足够支撑几千文档、几十万条边。

```sql
CREATE TABLE `kb_graph_entity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `normalized_name` varchar(128) NOT NULL,
  `type` varchar(24) NOT NULL,
  `aliases_json` text NULL,
  `description` varchar(512) NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_entity` (`kb_id`,`normalized_name`),
  KEY `idx_kb_graph_entity_type` (`kb_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `kb_graph_edge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kb_id` bigint NOT NULL,
  `source_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `type` varchar(24) NOT NULL,
  `weight` decimal(4,3) NOT NULL,
  `evidence_chunk_id` bigint NULL,
  `document_id` bigint NULL,
  `extractor_version` varchar(32) NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_graph_edge` (`kb_id`,`source_id`,`target_id`,`type`),
  KEY `idx_kb_graph_edge_source` (`source_id`),
  KEY `idx_kb_graph_edge_target` (`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `kb_graph_mention` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entity_id` bigint NOT NULL,
  `chunk_id` bigint NOT NULL,
  `start_offset` int NULL,
  `end_offset` int NULL,
  `quote` varchar(255) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kb_graph_mention_entity` (`entity_id`),
  KEY `idx_kb_graph_mention_chunk` (`chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**两跳查询**用 MySQL 8 的递归 CTE（双向）：

```sql
WITH RECURSIVE reach (entity_id, depth, weight) AS (
  SELECT id, 0, 1.0 FROM kb_graph_entity WHERE id IN (:seedIds)
  UNION ALL
  SELECT CASE WHEN g.source_id = r.entity_id THEN g.target_id ELSE g.source_id END,
         r.depth + 1,
         r.weight * g.weight * 0.7
  FROM reach r
  JOIN kb_graph_edge g
    ON g.source_id = r.entity_id OR g.target_id = r.entity_id
  WHERE r.depth < 2
)
SELECT entity_id, MAX(weight) AS weight
FROM reach
GROUP BY entity_id;
```

什么时候换 Neo4j：需要 3 跳以上、路径查询、社区发现/PageRank、图规模到千万级，或者多个知识库共享实体。迁移成本主要在查询重写，不在数据导出。

实体链接的向量：实体总量小（几千）时，直接在 MySQL 里取出来做内存 cosine 即可；上万再进 Milvus 单独 collection。

## 6. 检索：三种模式

### 6.1 局部（实体为中心）

「RDB 是什么」→ 链接到 `RDB` → 1 跳邻居 + 提及段落。

### 6.2 关系（实体对）

「RDB 和 AOF 怎么选」→ 链接到两个实体 → 优先找 `CONTRASTS_WITH` 边和两端描述 → 召回证据块。

问题意图可以路由关系类型：

| 意图 | 优先关系 |
| --- | --- |
| 对比/选择 | `CONTRASTS_WITH` |
| 为什么/报错 | `CAUSES`、`SOLVES` |
| 学习路径 | `PREREQUISITE`、`DEPENDS_ON` |
| 组成/架构 | `PART_OF` |

### 6.3 全局（语料级）

「帮我梳理 Redis 的知识体系」→ 社区检测（Leiden）+ 每个社区的摘要 → 汇总回答。这是 P2，成本最高。

### 6.4 混合召回（推荐先做）

```
entities   = 别名精确匹配 ∪ 实体向量 Top5
seeds      = mentions(entities)
neighbors  = edges(seeds, hops ≤ 2, 按关系类型加权)
graphHits  = chunks(mentions(neighbors))
vectorHits = 现有 K2 向量召回
bm25Hits   = 现有 K2 BM25 召回

fused = RRF(bm25Hits, vectorHits, graphHits)   # 三路等权起步
final = rerank(fused) → 阈值/每文档限制 → 上下文
```

不要直接把「图分数」和「余弦相似度」相加，量纲不同；**用排名做 RRF** 最稳。

上下文里额外拼一段关系说明：

```
[RDB] 与 [AOF] 的关系：CONTRASTS_WITH（证据：chunk#42）
```

生成时要求引用编号，引用可以同时指向 chunk 和关系边。

## 7. 接进现有系统（不改 K2 主干）

1. `KbSearchService` 增加可选 `graph` 通道，由知识库开关控制；关闭时行为与现在完全一致。
2. 图谱通道内部再做 1~2 跳扩展，产出 `graphHits` 参与 RRF。
3. 链路新增两个节点（前端已有位置）：
   - `graph-link`：实体链接（识别到 RDB、AOF）
   - `graph-expand`：图谱扩展（2 个实体、5 条关系）
4. 引用 DTO 增加 `path` 字段，前端显示 `RDB ──CONTRASTS_WITH── AOF`。
5. 灰度：按 KB 开关，先在一个技术库上跑；A/B 100 道题对比。

## 8. 质量与评估

| 指标 | 怎么测 | 目标 |
| --- | --- | --- |
| 关系抽取准确率 | 抽 100 条边人工判断 | > 0.85 |
| 实体链接准确率 | 100 个问题里的实体识别 | > 0.9 |
| 别名覆盖率 | 高频词是否都有别名 | > 0.8 |
| 端到端 Recall@5 | 100 道标注题 | 相比现状 +10% |
| 引用支持率 | 引用段落是否支持结论 | > 0.9 |
| 无依据断言率 | 人工抽查 | < 5% |

常见坑与对策：

| 坑 | 表现 | 对策 |
| --- | --- | --- |
| 抽取幻觉 | 文档没写的关系也抽 | 强制 evidence + 置信度阈值 |
| 实体歧义 | 「事务」混在一起 | 按知识库/领域加命名空间 |
| 图谱爆炸 | 1 跳几百个邻居 | 限制每实体邻居数、按权重截断 |
| 过期边 | 文档改了边还在 | 按 document 重建 + 版本号 |
| 长尾缺失 | 新概念没抽到 | 保留向量/BM25 兜底，不要只靠图 |

## 9. 成本与工期（按当前 3 篇文档、128 个 chunk）

- 抽取：128 次调用（可 4 块批量），约十几万 token；一次全量 + 每次文档更新增量。
- 存储：3 张表，百万级边 MySQL 无压力。
- 开发：P1 约 3–5 天（迁移 + 抽取任务 + 链接 + 检索插件 + 两个链路节点 + 灰度开关 + 评测脚本）。

建议顺序：

1. **P0（1 天）**：不调用模型，用「同文档共现 + 标题层级」生成一张粗图，先做相关概念/查询扩展。
2. **P1（3–5 天）**：LLM 三元组抽取 + 实体消解 + 1 跳扩展，**先只做 `CONTRASTS_WITH` 对比关系**（面试最高频，收益最好验证）。
3. **P2**：全量关系 + 社区摘要 + 全局问答 + 图谱视图。

## 10. 那现在的 RAG 会作废吗？

**不会。** 两者是分工关系，不是替代关系。

| 能力 | 谁来干 |
| --- | --- |
| 精确词、命令、错误码 | BM25 |
| 同义改写、口语化提问 | 向量 |
| 概念关系、多跳、全局结构 | 图谱 |
| 候选排序 | rerank |
| 组织答案、引用 | LLM + 原文 chunk |

三个理由：

1. **答案的证据仍然是原文段落。** 图谱只告诉你去哪找，不能替代引用；没有 chunk，回答就不可追溯。
2. **图谱有覆盖盲区。** 没抽到的关系就是没有；向量/BM25 是兜底。只上图谱，长尾问题会明显变差。
3. **成本与维护。** 抽取、消解、增量更新都要持续投入；混合方案可以在收益不明确的库上直接关掉图谱通道。

会变的是这四件事：

- 检索多一路 `graphHits`，参与 RRF；
- 链路多两个节点；
- 引用多一个「关系路径」；
- 评估多一组图谱指标。

不会变的是：分块、embedding、rerank、生成提示词、引用展示、会话存储。

演进路线可以这么看：

```
v1   文档 RAG（现状）
      BM25 + 向量 + RRF + rerank

v1.5 图谱增强（建议下一步）
      + 实体/关系 + graphHits 参与融合
      + 链路两节点 + 引用路径

v2   图谱原生（P2）
      + 社区摘要 + 全局问答 + 图谱视图
      但文本块仍然是最终证据
```

所以正确的心态是：**图谱是给 RAG 加的一路召回和一个可解释层**，不是换掉 RAG。

## 11. P1 最小落地清单

- [ ] V4 迁移：`kb_graph_entity` / `kb_graph_edge` / `kb_graph_mention`
- [ ] 抽取任务：按文档触发、批量、失败重试、`extractor_version`
- [ ] 实体消解：别名表 + 向量候选 + 低置信复核接口
- [ ] 实体链接：别名精确匹配 + 实体向量 TopK
- [ ] 检索插件：1 跳扩展 → `graphHits` → 与 BM25/向量 RRF
- [ ] 前端：`graph-link` / `graph-expand` 两个节点，引用显示关系路径
- [ ] 灰度开关 + 100 题评测脚本

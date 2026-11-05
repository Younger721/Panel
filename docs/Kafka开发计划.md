# Kafka 开发计划（异步任务与事件分发）

> 目标：让简历里「结合 Redis、Kafka 实现任务状态管理及异步处理」这一条，在代码里有真实落点。
> 前提：**不推翻现有任务模型**，Kafka 只作为「唤醒/分发层」接入，DB 仍是状态权威。

---

## 1. 现状（改造前，已核查）

| 事实 | 位置 |
|---|---|
| 队列抽象 8 个方法：`enqueue / claimNext / advance / retry / complete / recoverExpired / find` | `shared/async/PersistentJobQueue.java` |
| 任务记录：`id, type, tenantId, status, attempts, nextAttemptAt, leaseUntil, lastError` | `shared/async/PersistentJob.java` |
| 状态机：`PENDING → RUNNING → COMPLETED / FAILED` | `JobStatus.java` |
| 三个实现，靠 `nova.job-queue` 切换 | `mysql` / `redis` / `memory`（默认） |
| 消费方式：**5 秒轮询**，每轮 `recoverExpired` + `claimNext` + 处理 1 个任务 | `KnowledgeIngestWorker:98`（`@Scheduled(fixedDelayString="${nova.knowledge.ingest-delay-ms:5000}")`） |
| 重试：`30s << (attempts-1)`，上限 5 次 | `MySqlPersistentJobQueue.retry` / `RedisStreamPersistentJobQueue.retry` |
| `enqueue` 幂等：同 `(type, tenantId)` 已有 PENDING/RUNNING 则复用；终态则重置重放 | 两个实现里的 slot / lock 逻辑 |
| 表 `async_job`：`type, tenant_id, status, attempts, next_attempt_at, lease_until, last_error` | `MySqlPersistentJobQueue` 的 SQL |
| **Kafka：0 处**（依赖、compose、代码都没有） | 全仓搜索确认 |

关键结论：**现有轮询已经能跑通「至少一次 + 租约重投」**，Kafka 的增量价值是「任务一产生就立刻被消费」，把 5 秒延迟降到毫秒级，并给多实例消费提供分发能力。

---

## 2. 设计决策

### 2.1 DB 权威 + Kafka 唤醒（不是"消息即任务"）

```
生产者                      Kafka                       消费者
enqueue() ──insert async_job(PENDING)──┐
    └── send(jobId, key=tenantId) ──► topic: nova.async.job.ready ──► @KafkaListener
                                                                          │
                                                              claimNext(now, lease)  ← 仍走 DB 的
                                                                          │   FOR UPDATE SKIP LOCKED
                                                          处理成功 → complete() / 失败 → retry()
```

三条硬约束，决定了这个结构：

1. **消息可以重复**：`claimNext` 只在 `status=PENDING` 时把租约写进 DB，重复消息抢不到任务 → 天然幂等，不需要消息级 exactly-once。
2. **消息可以丢**：Kafka 挂了，`@Scheduled` 轮询仍然 5 秒兜底，业务不中断（降级而不是故障）。
3. **状态要可查**：用户轮询任务进度时读 DB，不依赖 Kafka 的 consumer offset。

> 面试口径（这样讲最稳）：
> 「Kafka 做任务分发，MySQL 做状态权威，消费端按租约抢占；语义是 at-least-once，靠业务幂等键（jobId）去重，不承诺 exactly-once——因为跨 DB 和 MQ 的 exactly-once 成本远高于收益。」

### 2.2 不做的部分

- 不用 Kafka 存任务状态（不做 log-compacted topic 当 KV）。
- 不引入 `KafkaTemplate` 事务消息（`@Transactional` + `send` 的边界问题会拖长事务）。
- 不替换 Redis 的用途：Redis 仍是缓存/会话/限流（`shared/security`、`tenancy` 已在用）。

---

## 3. 代码改动清单

### 3.1 重构：把「状态存储」从「队列实现」里抽出来

现状 `MySqlPersistentJobQueue` 把 SQL 和接口实现写在一起，Kafka 实现需要复用同一套 SQL。抽出 `JdbcJobStateStore`（包内可见，无 `@Component`）：

| 文件 | 动作 | 说明 |
|---|---|---|
| `shared/async/JdbcJobStateStore.java` | **新增** ~140 行 | 8 个方法对应的纯 SQL：`insertPending / claimOne / markRunning / markRetry / markCompleted / resetForReplay / findById / recoverExpiredJobs` |
| `shared/async/MySqlPersistentJobQueue.java` | 改 | 保留 `@ConditionalOnProperty(nova.job-queue=mysql)`，内部改为委托 `JdbcJobStateStore`（行为不变，`nova.job-queue=mysql` 的回归必须一致） |
| `shared/async/KafkaPersistentJobQueue.java` | **新增** ~180 行 | 实现接口：状态委托 `JdbcJobStateStore`，`enqueue`/`retry` 额外发唤醒消息 |

> 只抽一层、不重写，是为了保证 `mysql` 与 `kafka` 两个模式的语义完全一致（幂等 slot、租约、退避都只有一份实现）。

### 3.2 新增：Kafka 配置与 topic

| 文件 | 内容 |
|---|---|
| `shared/async/kafka/KafkaJobQueueProperties.java` | `bootstrapServers / jobTopic / dlqTopic / consumerGroup / enabled` |
| `shared/async/kafka/KafkaTopicConfiguration.java` | `NewTopic` bean：`jobTopic` 3 分区、`dlqTopic` 1 分区（`replicas=1`，本地单节点） |
| `shared/async/kafka/KafkaJobListener.java` | `@KafkaListener(topics="${nova.kafka.job-topic}")`，收到消息后调用 `worker.drainOnce()`；手动 ack，异常进 DLQ |
| `shared/async/kafka/KafkaJobPublisher.java` | 封装 `send`，key=`tenantId`（保证同租户顺序），header `type`，失败只记 WARN（不阻塞 enqueue） |

### 3.3 改造：消费端

`KnowledgeIngestWorker` 现在只有一个 `@Scheduled` 方法。把「领一个任务并处理」抽成 `public void drainOnce()`，被两处调用：

- `@Scheduled(fixedDelayString=...)` —— 兜底轮询（**保留**，Kafka 不可用时靠它）；
- `KafkaJobListener` —— 实时触发（新增）。

注意：`drainOnce` 必须线程安全（`claimNext` 已是原子的，`SKIP LOCKED` 保证多线程/多实例不会重复领同一任务），并加 `AtomicBoolean` 防同实例重入，避免 3 个分区同时唤醒把 CPU 打满。

---

## 4. Topic 与消息契约

| Topic | 分区 | 保留 | Key | Value | 用途 |
|---|---|---|---|---|---|
| `nova.async.job.ready` | 3 | 1h | `tenantId` | `{"jobId":123}` | 有新任务可领 |
| `nova.async.job.dlq` | 1 | 7d | `tenantId` | 原消息 + 异常栈 | 消费连续失败（≥5 次）后转人工 |

- `ack-mode: manual_immediate`（或 `record`），**处理完再 ack**；`drainOnce` 内部已是幂等，重复投递最坏情况是空转一次。
- `auto.offset.reset=earliest`：新实例启动能补到积压（但真正兜底靠 DB 轮询，不依赖它）。

---

## 5. 配置

`application-dev.yml` 增加：

```yaml
nova:
  job-queue: kafka            # memory | mysql | redis | kafka
  kafka:
    bootstrap-servers: ${NOVA_KAFKA_BOOTSTRAP:localhost:9092}
    job-topic: nova.async.job.ready
    dlq-topic: nova.async.job.dlq
    consumer-group: nova-ingest-workers

spring:
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true          # 生产端不重复
    consumer:
      auto-offset-reset: earliest
      max-poll-records: 20
      enable-auto-commit: false
```

`.env.example` 增加：

```
# ---------- Kafka（nova.job-queue=kafka 时需要） ----------
KAFKA_HOST_PORT=9092
NOVA_KAFKA_BOOTSTRAP=localhost:9092
```

**端口注意**：`advertised.listeners` 用的是 `localhost:9092`，所以宿主机端口不能被改掉（否则 broker 返回的地址客户端连不上）。要改端口就得加双 listener（`INTERNAL://kafka:9092` + `EXTERNAL://localhost:<port>`），本期不做，保持 9092。

---

## 6. Docker 环境

`docker-compose.yml` 增加 **KRaft 单节点**（无需 ZooKeeper）：

```yaml
  kafka:
    image: apache/kafka:3.9.1
    container_name: nova-kafka
    restart: unless-stopped
    ports:
      - "${KAFKA_HOST_PORT:-9092}:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    volumes:
      - kafka_data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      retries: 20
      start_period: 30s
```

镜像约 **350~400MB**，首次拉取需要网络。

**实测（本机，2026-11-05）**：直接 `docker pull apache/kafka:3.9.1` 会失败，报
`failed to fetch anonymous token: Get "https://auth.docker.io/token...": EOF` —— Docker Hub 的鉴权端点被阻断（`registry-1.docker.io` 从宿主机 curl 也是 000）。
可用的路径是镜像站：

```powershell
docker pull docker.m.daocloud.io/apache/kafka:3.9.1
docker tag  docker.m.daocloud.io/apache/kafka:3.9.1 apache/kafka:3.9.1
```

打完 tag 后，compose 里的 `image: apache/kafka:3.9.1` 直接命中本地镜像，无需改文件。
（`docker.1panel.live`、`dockerproxy.com` 实测均不可达，只有 `docker.m.daocloud.io` 返回 401 = 可达。）

**环境已验证（本机）**：`apache/kafka:3.9.1`（563MB）已落盘；`docker compose up -d kafka` → `nova-kafka` **healthy**，监听 `0.0.0.0:9092`；两个 topic 已创建（`ready` 3 分区 / `dlq` 1 分区，ISR 正常）；produce→consume 往返成功。

> 另：本仓库原先缺 `.env`，已按 `.env.example` 生成一份（gitignore 内），compose 才能插值 `MINIO_ROOT_*` / `MYSQL_PASSWORD`。

---

## 7. 依赖

`backend/pom.xml` 增加（版本由 Spring Boot 3.4.12 BOM 管理，不写死）：

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

`spring-kafka` 需要联网下载（首次编译时）。当前环境代理抖动，若下载失败重试或改用镜像仓库。

**实测**：Maven Central（`repo.maven.apache.org`）在本机 TLS 握手被中断，已新建
`%USERPROFILE%\.m2\settings.xml` 把 central 指到 `https://maven.aliyun.com/repository/public`（`mirrorOf=central`，
不影响其它仓库）。之后 `spring-kafka:3.3.11` + `kafka-clients:3.8.1` 正常解析，`mvn clean compile` 通过。

---

## 8. 实施顺序与排期（2 人日）

| # | 任务 | 产出 | 估时 | 验收 | 状态 |
|---|---|---|---|---|---|
| T1 | 抽 `JdbcJobStateStore` | 重构 `MySqlPersistentJobQueue` 为委托 | 0.3d | `nova.job-queue=mysql` 编译通过 + 行为不变 | ✅ 完成 |
| T2 | `KafkaPersistentJobQueue` | 实现 8 个方法 + 唤醒消息 | 0.5d | 单测：同 `(type,tenant)` 二次 enqueue 返回同 id | ✅ 完成 |
| T3 | 配置与 topic | Properties + NewTopic + Publisher | 0.3d | 启动后 `kafka-topics.sh --list` 有两个 topic | ✅ 完成 |
| T4 | 消费端接入 | 监听器调 `KnowledgeIngestWorker.processNext()` | 0.3d | 入队后 <1s 被消费（轮询模式是 ≤5s） | ✅ 完成，已真机验证 |
| T5 | Docker + 文档 | compose 服务、`.env.example`、README | 0.3d | `docker compose up -d kafka` healthy | ✅ 完成 |
| T6 | 验证与回归 | 四条故障演练 | 0.3d | 见 §9 | ⏳ 唤醒链路已通，故障演练待做 |

### 真机验证记录（2026-11-05）

环境：`nova_ai` 库 + 复用 GymMind 的 Redis/MinIO/ES/Milvus + 本项目 `nova-kafka`，`NOVA_JOB_QUEUE=kafka` 启动后端。

```
Started NovaApplication in 11.469 seconds
Tomcat started on port 8080
async_job 表自动创建（0 行）
```

插入 3 条 PENDING 任务并各发 1 条唤醒消息后：

```
id  type         status     attempts
1   smoke:test   COMPLETED  0
2   smoke:test   COMPLETED  0
3   smoke:test   COMPLETED  0

GROUP                TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
nova-ingest-workers  nova.async.job.ready   0          1               1               0
nova-ingest-workers  nova.async.job.ready   1          2               2               0
nova-ingest-workers  nova.async.job.ready   2          1               1               0
```

关键证据：`nova-ingest-workers` 组只有本监听器在消费，位点已提交且 LAG=0 —— 说明消息确实走了
`Kafka → 监听器 → claimNext → COMPLETED`，而不是被 5 秒轮询捡走的。

**过程中修掉两个迁移遗漏**（都属于"GymMind 有实现、Nova 丢了"）：

1. `RecommendationCandidateSource` 没有实现豆子（真正实现是健身域的 `ExerciseRecommendationCandidateSource`，
   兜底类 `InMemoryRecommendationCandidateSource` 漏了 `@Component`）→ 应用直接启动失败。已补注解，返回空列表。
2. `AsyncJobSchemaInitializer` 的 `@ConditionalOnProperty(havingValue="mysql")` 把建表锁死在 MySQL 队列上，
   但 Kafka 队列同用这张表 → `Table 'nova_ai.async_job' doesn't exist` 每 5 秒刷屏。已改为 mysql/kafka 都建表。

---

## 9. 测试与验收（重点在故障路径）

| # | 场景 | 操作 | 期望 |
|---|---|---|---|
| 1 | 正常投递 | 上传一份简历触发入库 | 任务在 1 秒内从 PENDING→RUNNING→COMPLETED |
| 2 | 消费者中途被杀 | 处理中 `Ctrl+C` 停应用 | 重启后 `recoverExpired` 把过期租约改回 PENDING 并重跑，**不产生重复文档** |
| 3 | 重复消息 | 手工向 topic 再发一次同 jobId | 消费端空转一次，`attempts` 不增加，无副作用 |
| 4 | Kafka 不可用 | `docker compose stop kafka` 后上传简历 | enqueue 仍成功（DB 落 PENDING），5 秒内被轮询消费；日志有 WARN，无异常抛出 |
| 5 | 连续失败 | 构造一直抛异常的任务 | 重试 5 次后 FAILED + 进 DLQ，`last_error` 可查 |
| 6 | 多实例 | 起两个进程 | 同一任务只被一个实例领走（`SKIP LOCKED`），`attempts` 不重复累加 |

> 场景 2/3/6 是这套设计能讲清楚的三个点：**租约重投、幂等消费、跨实例不重复**。

### 演练结果（2026-11-05 实测，`NOVA_JOB_QUEUE=kafka`）

| # | 场景 | 实测结果 |
|---|---|---|
| 3 | 同一 jobId 连发 10 条消息 | **68ms** 内 `COMPLETED`，`attempts=0` —— 重复消息只抢到一次租约，其余空转 |
| 5 | 构造一直失败的任务（MinIO 对象缺失） | 首次 `attempts=1`、`last_error=knowledge object is unavailable`；快进 4 轮后 `attempts=5` → **FAILED**，文档同步置 FAILED；**死信已投递**：`nova.async.job.dlq` 收到 `{"jobId":8,"type":"KNOWLEDGE_INGEST:2","error":"knowledge object is unavailable"}` |
| 4 | `docker stop nova-kafka` 后插入任务 | **3661ms** 内 `COMPLETED`（轮询兜底），应用无异常退出；Kafka 恢复后消费者自动重新入组（LAG=0） |
| 2 | 手工造 `RUNNING` + 过期 60s 租约 | **4483ms** 内被 `recoverExpired` 回收并执行完毕，`attempts` 仍为 0（回收不计重试） |

演练中顺带修掉一个真实缺陷：`KafkaTemplate.send()` 的失败是**异步**的（异常只出现在 `CompletableFuture` 里），
原来的 `try/catch` 只能接住序列化这类同步异常，broker 挂掉时根本不会记录。已改为 `whenComplete` 里记 WARN。

仍待补：场景 6（多实例 `SKIP LOCKED` 验证，需要起第二个进程）。

---

## 10. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| Docker Hub 拉不到镜像 | 无法验证 | 配 Docker Desktop 代理 / 换镜像源；代码层面不依赖 Kafka 也能跑（`memory`/`redis`） |
| `spring-kafka` 依赖下载失败 | 编译失败 | 重试；Maven 配镜像仓库 |
| advertised listener 配错 | 客户端连不上（典型 `localhost:9092` 超时） | 固定 9092，不做端口重映射 |
| 把 Kafka 当唯一真相 | 消息丢失=任务丢失，面试会被问穿 | 明确 DB 权威 + 轮询兜底（本方案的核心） |

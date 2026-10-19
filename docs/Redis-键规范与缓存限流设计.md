# 问卷系统 · Redis 键规范与缓存/限流设计

> 更新时间：**2026-10-13**
> 定位：**Redis 相关约定的唯一出处**（key 规范、失效策略、降级方向、运维红线）。
> 配套：`docs/ARCHITECTURE.md`（怎么跑 / 铁律）、`docs/AUDIT.md`（端点口径）。
> 代码入口：`common/RedisKeys`（key 唯一出处）、`service/StatsCacheService`（缓存）、
> `config/RedisRateLimiter`（限流计数）、`common/RedisCircuitBreaker`（熔断）。

---

## 一、为什么引入 Redis（先看数据）

本机实测，2 万答卷 / 12 万明细，32 并发、每接口 2000 次请求，
同一份代码用 `--app.cache.stats-enabled=false` 与默认配置各起一个实例对比：

| 接口 | 缓存关闭 | 缓存开启 | 结论 |
|---|---|---|---|
| `GET /api/answer/statistics/{qid}`（`GROUP BY content`） | QPS 165、P50 **190ms**、P99 256ms | QPS **2333**、P50 **11ms**、P99 41ms | **QPS ×14、P50 降 94%** |
| `GET /api/answer/count/{sid}`（`COUNT(*)`） | QPS 1371、P50 21ms | QPS 3082、P50 9ms | QPS ×2.2 |
| `GET /api/answer/texts/{qid}`（2000 条文本） | QPS 566、P50 26ms | QPS 1120、P50 13ms | QPS ×2.0 |
| `GET /api/survey/my-statistics`（**未接缓存**） | QPS 1231、P50 11.5ms | QPS 1286、P50 11.4ms | 基本不变（对照组） |
| `GET /api/answer/list/{sid}`（**未接缓存**；当时未分页） | QPS 33、P50 239ms | QPS 34、P50 237ms | 基本不变（对照组） |

两条**未接缓存**的接口数字完全不变，说明上面的差异来自缓存本身，而不是机器状态波动。

限流侧的实测（同一台机器、两个实例 8081/8082 共享一个 Redis）：

```
把 30 次配额全部打到 8081 → 30×200，Redis 计数=30
第 31 次打到【另一个实例】8082 → 429
```
进程内计数在这里必然放行（每个实例各算一份配额），这正是要换 Redis 的原因。

---

## 二、★ key 规范（动 Redis 之前先读这里）

### 2.1 两个命名空间必须物理隔离

| 命名空间 | 前缀 | 数据性质 | 丢了会怎样 |
|---|---|---|---|
| 缓存 | `survey:cache:` | 预聚合的统计结果 | **回源重算**，不影响正确性 |
| 限流 | `survey:rate:` | 窗口配额计数器 | **等于配额被重置**（防爆破失效） |

★ 绝不允许两条链路共用前缀。典型事故：清缓存时顺手清掉了限流计数，
表现为"清一次缓存，登录防爆破同时失效"——排查时看到的是"限流偶尔不起作用"，
根因却在缓存那侧的清理代码里，属于极难定位的一类问题。
`common/RedisKeysTest` 钉住了"两条前缀互不包含、也不互为前缀"。

### 2.2 key 清单

| 用途 | key 模板 | 值 | TTL |
|---|---|---|---|
| 答卷份数 | `survey:cache:answer-count:<surveyId>` | 十进制整数（字符串） | **60s（固定）** |
| 单题选项分布 | `survey:cache:qstats:<questionId>` | JSON `[{content,count}]` | 600s + 0~60s 随机抖动 |
| 单题填空文本 | `survey:cache:qtexts:<questionId>` | JSON `[string]` | 600s + 0~60s 随机抖动 |

★ 两大类 key 的 TTL 差 10 倍：单题统计回源要扫明细表（2 万答卷下 P50 约 190ms），
答卷数回源只是一条 `COUNT(*)`（约 21ms）—— 短 TTL 的代价可以忽略，而它能把
"增量更新失败导致缓存比库少 1"的窗口压到 1 分钟（详见 3.4）。
★ 抖动只加在单题统计上：同一批 key 若同时写入，就会在同一刻集体过期，那个瞬间的并发请求
会一起回源（缓存击穿）；答卷数的 TTL 本来就要短而稳定，不加抖动。
| 限流计数 | `survey:rate:<rule>:<subject>` | 窗口内累计次数 | = 窗口长度（`PEXPIRE`） |

* `<rule>` 取 `RateLimitInterceptor.RULES` 里的 name（login / ai / view / submit / submit-share…）。
* `<subject>` 是按 IP 计数时的**真实连接地址**，或按用户计数时的 `X-User-Id`。
  ★ IPv6 客户端的 IP 形如 `0:0:0:0:0:0:0:1`，与 key 的分隔符 `:` 相同 —— 功能无影响，
  但**不要**按冒号去解析 subject（需要拆解时按"前缀 + rule + 剩余部分"处理）。

### 2.3 不要在业务代码里拼字符串

所有 key 都由 `common/RedisKeys` 生成（`answerCount` / `questionStats` / `questionTexts` / `rateLimit`）。
散落的字面量没有任何机制保证隔离性 —— 只要有人把限流 key 写成 `survey:cache:rate:...`，2.1 的隔离就没了，
而 `RedisKeysTest` 会立刻变红。

### 2.4 运维红线

**禁止** `KEYS *`、`FLUSHDB`、`FLUSHALL`：

* 它们会把限流计数一起清空（等于短暂解除全部限流）；
* `FLUSHALL` 还会清掉同一实例上别的应用的库。

要清缓存就按**具体的 key** 删（`DEL survey:cache:answer-count:861`）。
排查时可以用 `KEYS 'survey:rate:*'` 这类**只读**命令看现状，但不要用它做删除。
★ 本项目的删除路径（提交答卷失效、级联删除问卷）全部按具体 key 删，没有一处前缀扫描。

---

## 三、缓存设计

### 3.1 缓存了什么、**故意不缓存**什么

| 接口 | 是否缓存 | 理由 |
|---|---|---|
| 单题选项分布 | ✅ | `GROUP BY content` 扫明细表，收益最大（P50 190ms → 11ms） |
| 单题填空文本 | ✅ | 每次读 2000 条文本，重复计算明显 |
| 单问卷答卷数 | ✅ | 统计页每次刷新都打，`COUNT(*)` 结果只随答卷变化 |
| `survey/my-statistics`、`system-overview` | ❌ | 已经是"一条 `GROUP BY` 批量取数"（历史上专门消除过 N+1），基线 12ms，缓存收益与维护成本不成比例 |
| `answer/list/{sid}` | ❌ | **已分页**（第二十八轮，见《待解决问题》C1）：每页 ≤100 条、`(survey_id, submit_time)` 索引；仍不缓存 —— 它是"最新答卷"的实时视图，缓存会让"刚提交却看不到"变成常态 |
| `answer/detail/{aid}` | ❌ | 单份答卷详情，key 空间随答卷数线性增长，命中率低 |

### 3.2 一致性策略：**失效优先，增量只用于计数**

| 写操作 | 对缓存做什么 | 为什么 |
|---|---|---|
| 提交答卷 | 答卷数 **`INCR`（仅当 key 存在）** | 计数是单调的，增量安全且省一次回源 |
| 提交答卷 | 该问卷各题的分布/文本 **`DEL`** | 多选题"一选项一行"，分布会以难以穷举的方式变化；删掉重算只花一条 `GROUP BY` |
| 彻底删除问卷（级联） | 删本份问卷的答卷数 + 各题 key | 题目行即将消失，残值只会白占内存 |
| 移入/移出回收站 | **不动缓存** | 答卷没变，统计口径没变 |

★ **失效动作在事务提交之后执行**（`AnswerService.afterCommitCacheRefresh`，用 `TransactionSynchronization`）：
事务回滚时缓存不能先动，否则会出现"库里没有这份答卷、答卷数却多 1"的脏值。
方向差异要记住：**`DEL` 放在事务里也安全**（幂等 + 下次回源正确），**`INCR` 不行**，所以两者统一放到提交后。

### 3.3 ★ 为什么答卷数的增量更新是"仅当 key 存在"

直接 `INCR survey:cache:answer-count:9` 在 key **不存在**时会凭空创建一个值为 1 的键，
而库里真实答卷数是 20000 —— 下一次读缓存就会把 **1** 当成答卷数返回，
并且这个错值会一直"看起来是有效缓存"直到 TTL 到期。

所以增量更新走一段 Lua（`EXISTS` 判定 + `INCR` 原子完成，见 `StatsCacheService.BUMP_IF_EXISTS`）：
key 不存在就什么都不做（下次读会回源并写回正确值）。
★ 另外 `INCR` **不会清除原有 TTL**（Redis 语义），所以这里也不续期 ——
活跃问卷的计数缓存会周期性回源重建，等于自带一道自愈。

### 3.4 TTL 的作用边界（两类 key 的 TTL **故意不同**）

TTL 是**兜底**，不是主要失效手段：正常的失效靠上表的写操作主动删除；TTL 只负责
"万一将来新增了一条漏掉的写入路径，脏值也会在 TTL 后自愈"。

| key | TTL | 为什么是这个值 |
|---|---|---|
| `answer-count` | **60 秒，固定** | 回源只要 `COUNT(*)`（约 21ms）。★ 它还承担一个额外职责：提交后走的是 `INCR` 增量，万一增量失败（Redis 抖动但还没触发熔断），缓存会比库里**少 1** —— 短 TTL 把这个错误窗口从 10 分钟压到 1 分钟。 |
| `qstats` / `qtexts` | 600 秒 + **0~60 秒随机抖动** | 回源要扫明细表（约 190ms），所以 TTL 长；抖动用于打散"同一批 key 同时过期 → 并发一起回源"的击穿。 |

★ 还有第二道保险：`INCR` 失败时会**顺手静默删掉该 key**（让下次读直接回源），
  见 `StatsCacheService.bumpAnswerCount` 的失败分支。
  两者叠加后，"答卷数少 1"只会出现在"增量失败 + 1 分钟内"这个极窄的窗口里。

---

## 四、限流设计

### 4.1 为什么必须从进程内计数换成 Redis

进程内的 `ConcurrentHashMap<String, Counter>` 只活在**单个 JVM** 里。
单实例部署时它工作得很好，但一旦多实例（水平扩容、灰度、容器滚动更新），
配额就被放大 N 倍：每个实例各拦 10 次/分，实际允许 10×N 次/分 ——
"防暴力破解"这条规则在被真正需要的时候（多实例）恰好失效。

### 4.2 为什么用 Lua 而不是 `INCR` 之后再 `EXPIRE`

两条命令之间有窗口：`INCR` 成功、`EXPIRE` 之前进程被杀死（或被 Redis 拒绝执行），
这个 key 就变成**永不过期**的计数 —— 该主体从此被永久锁死，比"没限流"更糟。
放进 Lua 后，"自增 + 首次设置过期"是一个原子步骤，不存在中间态。

### 4.3 固定窗口（有意保留，没做滑动窗口）

窗口边界最多放行 2 倍配额（10 次/分的规则跨窗口瞬间最多 20 次）。
对"挡脚本爆破、挡刷接口"这个目标完全够用，而滑动窗口要么用 ZSET 存每次请求
（内存与 CPU 随配额线性增长），要么用令牌桶（需额外维护填充时间）。
★ 两条路径（Redis / 进程内降级）的窗口语义**完全一致**，所以降级不会改变限流行为。

---

## 五、★ 降级与熔断（两条链路的方向**相反**）

| | 缓存（`StatsCacheService`） | 限流（`RedisRateLimiter`） |
|---|---|---|
| Redis 读失败 | 当作**未命中** → 回源直查库 | 返回 null → **回退进程内计数** |
| 安全方向 | "当作没有缓存"（安全，只是变慢） | "当作仍然受限"（保守，宁可多拦） |
| **绝不允许** | 让接口 500 | **Redis 一挂就放行**（那会把一次依赖故障放大成安全事件） |

两条链路共用 `common/RedisCircuitBreaker`：**失败一次就短路 10 秒**，期间不再尝试 Redis；
窗口过后放行一次探测，成功即立刻关闭（不必等窗口走完）。

★ 熔断为什么必须存在（实测，不是理论担忧）：把 Redis 停掉后，接口虽然仍是 **200**，
但每次请求都要等一次命令超时（`spring.data.redis.timeout`）。加熔断前后：

| 场景 | 加熔断前 | 加熔断后 |
|---|---|---|
| Redis 停机，第 1 次请求 | ~4000ms | 525ms（等一次 500ms 超时） |
| Redis 停机，后续请求 | ~4000ms **每次都等** | **12ms**（熔断短路，直接回源） |
| Redis 恢复后 | — | 自动关闭熔断：第 1 次 152ms、第 2 次 8ms |

"只是变慢"的降级在真实流量下会占满 Tomcat 线程池，效果等同宕机 —— 这是熔断存在的全部理由。

---

## 六、配置项

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 500ms          # 决定"第一次失败要等多久"（配合熔断）
      connect-timeout: 500ms
      repositories:
        enabled: false        # 不用 Spring Data Repository，关掉启动时的扫描

app:
  cache:
    stats-enabled: ${APP_CACHE_STATS_ENABLED:true}         # false = 完全绕过 Redis（对比压测 / CI）
    stats-ttl-seconds: ${APP_CACHE_STATS_TTL_SECONDS:600}  # 单题分布/文本：回源贵，TTL 长
    count-ttl-seconds: ${APP_CACHE_COUNT_TTL_SECONDS:60}   # 答卷数：回源便宜，短 TTL 缩小脏读窗口
    ttl-jitter-seconds: ${APP_CACHE_TTL_JITTER_SECONDS:60} # 单题统计的随机抖动（打散击穿）
  rate-limit:
    redis-enabled: ${APP_RATE_LIMIT_REDIS_ENABLED:true}    # false = 完全走进程内计数
```

* 连不上 Redis **不会**导致启动失败（Lettuce 懒连接），也不会让接口 500。
* 压测对比法：`SERVER_PORT=8082 APP_CACHE_STATS_ENABLED=false` 起第二个实例，
  与默认实例跑同一组用例（本轮就是这么得到第一节那张表的）。

---

## 七、本地怎么起 Redis（Windows，不依赖 Docker）

本机没有 Docker，用的是 GitHub 上 `tporadowski/redis` 的 Windows 构建：

```
tools/redis-5.0.14.1/
├── redis-server.exe
├── redis-cli.exe
├── redis.survey.conf     # 端口 6379、无持久化、256MB + allkeys-lru
├── start-redis.bat       # 双击即可（保持窗口开着）
└── stop-redis.bat        # redis-cli shutdown nosave
```

* 配置要点：`save ""` + `appendonly no`（缓存与限流计数都是可丢数据，不需要持久化）、
  `maxmemory 256mb` + `maxmemory-policy allkeys-lru`。
* 生产/演示用 Docker 时见仓库根 `docker-compose.yml` 的 `redis` 服务
  ★ **本机未验证过**（本机没装 Docker），只做了配置层面的对齐。
* 从零复现这套二进制：`https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip`
  （国内网络可换镜像前缀，例如 `https://ghfast.top/<上面的完整地址>`）。

---

## 八、改动清单（代码入口）

| 文件 | 作用 |
|---|---|
| `common/RedisKeys.java` | **key 唯一出处**：两条命名空间 + 4 类 key 生成方法 |
| `common/RedisCircuitBreaker.java` | 熔断器：失败即短路 10 秒，成功即恢复 |
| `service/StatsCacheService.java` | 缓存的读写/失效/降级（`enabled` 开关、TTL、JSON 编解码） |
| `config/RedisRateLimiter.java` | 限流计数的 Redis 实现（Lua：`INCR` + `PEXPIRE`） |
| `config/RateLimitInterceptor.java` | 配额判定：先 Redis、失败回退进程内计数 |
| `service/AnswerService.java` | 三条统计查询走缓存；提交成功后 `afterCommitCacheRefresh` |
| `service/SurveyService.java` | 级联删除问卷时清掉本份问卷的缓存 |
| `application.yaml` | `spring.data.redis.*` 与 `app.cache.*` / `app.rate-limit.*` |

新增测试（`mvnw -o test` 默认全跑，134 例）：
`common/RedisKeysTest`（4）、`common/RedisCircuitBreakerTest`（4）、
`config/RedisRateLimiterTest`（8）、`config/RateLimitInterceptorTest`（5）、
`service/StatsCacheServiceTest`（14）。

---

## 九、已知局限（如实列出）

| 项 | 说明 |
|---|---|
| 滑动窗口未做 | 固定窗口在边界最多放行 2 倍配额（与改造前一致，见 4.3） |
| 双栈 IP 会算成两个桶 | 同一客户端走 IPv4 与 IPv6 时，`getConnectionIp()` 拿到的是两个不同字符串（`127.0.0.1` / `::1`），配额因此翻倍。这是**改造前就存在**的行为，与 Redis 无关；要修得在计数前把 IP 归一化 |
| 批量统计未缓存 | `my-statistics` / `system-overview` 仍是每次一条 `GROUP BY`（基线 12ms，收益小） |
| 答卷列表有分页、无缓存 | 分页已在第二十八轮补上（每页 ≤100）；仍不缓存，理由见上表 |
| 缓存与数据库之间无版本号 | 靠"写入即失效 + TTL 兜底"，没有版本向量；对当前的单写入口（`AnswerService.submit` + 级联删除）足够 |
| Docker 里的 Redis 未实测 | 本机没有 Docker，只对齐了配置 |
| **写放大** | 每提交一份答卷，就把该问卷**所有题**的缓存删掉（8 题 = 16 个 key）。高频提交的问卷缓存几乎永远不命中 —— 这是"失效优先"策略的固有代价；要根治得改成"版本号/时间戳"方案（key 里带版本，提交时只改版本号），复杂度明显更高。当前规模下不值得做 |
| **`afterCommit` 在请求线程里执行** | 提交答卷的应答要等 2 次 Redis 往返（约 2ms）。正常可忽略；Redis 变慢时会拖慢提交。规模上来后改成异步（代价是失效晚几毫秒） |
| **缓存击穿只做了概率缓解** | 用随机 TTL 打散集体过期，没有做严格单飞（single-flight）。极端情况下（同一 key 刚被删、又来了 N 个并发）仍会有 N 次回源 |

---

## 十、怎么复现第一、五节的那组数据

压测夹具与客户端都在工作区 `tools/bench/`（**不进仓库**）：

| 文件 | 作用 |
|---|---|
| `bench-fill.sql` | 造夹具：1 份 8 题问卷 + 2 万匿名答卷 + 约 12 万明细（实测 1.9 秒灌完） |
| `bench-cleanup.sql` | 清理：先打印"清理前"确认，再按 `survey_id` / `username` 整段删（**不按条数**） |
| `gen-bench-user.js` | 生成压测账号的建号 SQL（PBKDF2 口径与 `UserService.hashPassword` 一致；★ 用户名不能带下划线，`strict-username-check` 会拦） |
| `Bench.java` | 压测客户端（只用 JDK，无第三方依赖） |
| `run-baseline.ps1` | 串行跑 6 组用例；`-Base <url>` 指定实例，`-SkipLimit` 跳过限流观测组 |
| `result-*.txt` | 三次实测的原始输出（改造前基线 / 关缓存 / 开缓存） |

```powershell
# 1) 起 Redis（本机）
tools\redis-5.0.14.1\start-redis.bat

# 2) 灌夹具（口令见 application-local.yml）
mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <项目>/tools/bench/bench-fill.sql"

# 3) 造压测账号并拿 token（统计接口需要登录态）
node tools/bench/gen-bench-user.js 'Bench@12345' 'benchadmin' '<tmp>/bench-user.sql'
mysql.exe -uroot -p<口令> survey_db -e "source <tmp>/bench-user.sql"
#    然后 POST /api/user/login {"username":"benchadmin","password":"Bench@12345"} 取 token

# 4) 起两个实例做 A/B：8081 开缓存、8082 关缓存
$env:SERVER_PORT='8082'; $env:APP_CACHE_STATS_ENABLED='false'; .\mvnw.cmd -o spring-boot:run

# 5) 同一组用例分别打两个实例
pwsh -File tools/bench/run-baseline.ps1 -Tag cache-on  -Base http://localhost:8081 -SkipLimit
pwsh -File tools/bench/run-baseline.ps1 -Tag cache-off -Base http://localhost:8082 -SkipLimit

# 6) 收工：清夹具 + 按具体 key 清缓存（不要 FLUSHDB）
mysql.exe -uroot -p<口令> survey_db -e "source <项目>/tools/bench/bench-cleanup.sql"
redis-cli -p 6379 DEL survey:cache:answer-count:<sid>
```

★ 压测**必须串行跑两组**（两个实例共用同一个 MySQL，并行会互相干扰），
且两组都带 `--warmup 100`（把 JIT 与连接池预热掉，否则第一组会吃亏）。
★ 想要"缓存前"的数字，也可以用单实例 + `--app.cache.stats-enabled=false` 重启 —— 但那样
MySQL 的缓冲池状态与 A/B 两实例不完全一致，**两组各起一个实例、同一时间窗内跑**才是干净的做法。

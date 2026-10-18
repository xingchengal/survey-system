# 压测工具（Profile only · 不入 CI）

复现 README「技术亮点 8. 性能基线」那组数字的最小工具集。**只依赖 JDK 17，零第三方依赖。**

> ⚠️ 夹具会**真的往库里写 2 万答卷 + 12 万明细**（灌入约 2.5 秒）。
> 只应该在**演示库 / 独立库**上跑，别对着生产库跑；跑完务必执行 `bench-cleanup.sql`。

## 文件

| 文件 | 作用 |
|---|---|
| `Bench.java` | 单接口压测器（JDK 自带 `HttpClient`）：并发数、预热、QPS、P50/P90/P95/P99 |
| `bench-fill.sql` | 造夹具：1 份问卷 / 8 题（3 单选 + 3 多选 + 2 填空）/ 2 万答卷 / 12 万明细 |
| `bench-cleanup.sql` | 按 `share_code='bench0001'` 整段删除（可重复执行，`@sid` 为 NULL 时不会误删） |
| `run-bench.ps1` | 编排：登录 → 自动取题目 id → 跑 6 个用例 → 输出到文件 |

## 三步

```powershell
# 1) 造夹具（返回 survey_id / 答卷数 / 明细数）
mysql -uroot -p --default-character-set=utf8mb4 survey_db < tools/bench/bench-fill.sql

# 2) 起一个实例（想测"加索引/缓存前后"，再起一个关缓存的对照实例）
$env:SERVER_PORT='8081'; ./mvnw -o spring-boot:run
$env:SERVER_PORT='8082'; $env:APP_CACHE_STATS_ENABLED='false'; ./mvnw -o spring-boot:run   # 对照

# 3) 压测（--SurveyId 用第 1 步打印出来的那个）
pwsh -File tools/bench/run-bench.ps1 -Base http://localhost:8081 -SurveyId 909 -Username <账号> -Password <口令>
pwsh -File tools/bench/run-bench.ps1 -Base http://localhost:8082 -SurveyId 909 -Username <账号> -Password <口令> -Out result-nocache.txt

# 4) 清理（★ 别省）
mysql -uroot -p --default-character-set=utf8mb4 survey_db < tools/bench/bench-cleanup.sql
```

`run-bench.ps1` 也可以直接传 `-Token <jwt>`（省一次登录；注意登录接口有 10 次/分/IP 限流）。

## 2026-10-18 的实测结果（2 万答卷）

| 接口 | 无缓存 QPS | 有缓存 QPS | 提升 | P95（无 → 有） |
|---|---|---|---|---|
| 单题选项分布 `statistics/{qid}` | 140.3 | **2353.8** | **×16.8** | 284ms → 30ms |
| 整份问卷统计 `statistics/survey/{sid}`（8 题一次取回） | 27.4 | **984.3** | **×35.9** | 328ms → 10ms |
| 答卷数 `count/{sid}` | 1266.3 | 2912.9 | ×2.3 | 41ms → 22ms |
| 填空文本 `texts/{qid}`（2 万行取前 2000） | 598.9 | 1135.5 | ×1.9 | 39ms → 21ms |
| 答卷列表分页 `list/{sid}`（**未接缓存**，对照组） | 904.5 | 834.6 | ×0.9 | 10ms → 12ms |
| 我的统计 `my-statistics`（**未接缓存**，对照组） | 462.0 | 464.9 | ×1.0 | 41ms → 40ms |

**最后两行是实验设计的关键**：它们没接缓存，两轮数字几乎一样 —— 说明差异只能来自缓存，
不是"有缓存那轮恰好机器更闲"这类环境噪声。

> ⚠️ 两行对照里 **`my-statistics` 的数字取决于"当前账号名下有多少数据"**：
> 上面 462 QPS 是"该账号名下有那份 2 万答卷问卷"时测的；如果用一个名下没有问卷的账号跑，
> 它会变成 2000+ QPS（几乎没有工作量）。所以这一行只能**同一个账号前后对比**，不能跨账号横比。

> ★ **必须让 `JAVA_HOME` 指向 JDK 17+**。`Bench.java` 用单文件源码模式（`java Bench.java`），
> JDK 11+ 才有；如果 PATH 上落到了 JDK 8，脚本会"跑完但没有任何 QPS"——
> 现在 `run-bench.ps1` 会先校验 java 版本并直接报错。

## 环境

- 本机 Windows、JDK 17、MySQL 8 单实例、Redis 5 本机
- **单实例、无反向代理** —— 数字是"应用自身"的，不含网关/负载均衡开销
- 客户端与服务器在**同一台机器**上：`Bench` 自己的 CPU 开销会分走一部分服务端能力，
  所以绝对 QPS 会略低于"客户端独立机器"的结果；但**同一台机器上做的两组对照仍然有效**

## 为什么不进 CI

压测需要"真实数据量 + 稳定的独占环境"，而 CI 的 runner 有噪声、也没法保证数据量；
把压测放进 CI 只会得到一个每次都不一样的数字。它的定位是**手动跑、记录一次、写进文档**。

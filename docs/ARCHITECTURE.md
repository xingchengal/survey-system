# ARCHITECTURE · 架构铁律与唯一口径

> **定位**：四份主文档之一。本文件只写**当前有效**的架构铁律、唯一口径、关键不变量与环境坑。
> **读取方式**：默认读；**只在铁律变化时改**（权限模型 / 事务边界 / 缓存方向 / 状态机 / 上限口径）。
> **数字口径**：端点、表、测试、页面的具体数量以 [`AUDIT.md`](AUDIT.md) 为唯一出处，本文件不重复。
> **不写过程叙事**：轮次、变更缘由、复盘与回滚只进 `HISTORY.md`；待办与未决项只进 `SPRINT.md`。
> **专题文档**：
> [`前端架构与后端契约.md`](前端架构与后端契约.md)（前端分层与前后端硬契约）、
> [`Redis-键规范与缓存限流设计.md`](Redis-键规范与缓存限流设计.md)（key / 失效 / 降级 / 熔断的完整出处）、
> [`AUDIT.md`](AUDIT.md)（端点四档与事实矩阵）、[`BUG-滑块验证码.md`](BUG-滑块验证码.md)（归档原件，不改）、
> [`README.md`](README.md)（本目录索引）。
>
> ★ 本文件里的每条规则都是**现状**。写代码前先确认你要动的是哪一条；动到权限 / 事务 / 缓存 / 状态机 / 上限，
> 就说明本文件对应段落要跟着改。

---

## 一、系统形态

一个前后端同源部署的问卷系统：`survey-backend`（Spring Boot 4 + MyBatis 注解式、无 XML + MySQL 8）承载全部业务，
`survey-frontend`（Vue 3 + Vite + Element Plus + ECharts）构建后拷进后端 `static/` 做单包发布；
MySQL 是唯一事实来源，Redis **只**承担统计预聚合缓存与跨实例限流计数，缺失时两条链路各自降级、不影响启动；
身份是一枚短效 JWT access token 加一枚可吊销的 refresh token，
授权由「能力（角色 / 权限点，切面拦）× 对象（归属 / 协作者，Service 判）」两半组成，缺一半就是横向越权。

一行请求链路：

```
浏览器 → RequestSizeFilter(@Order 0，请求体 >4MB 直接 413)
       → JwtAuthFilter(@Order 1：白名单 / 验签 / 查库判账号有效 / 覆盖写 X-User-Id、X-User-Role)
       → DocAccessFilter(@Order 2，只拦文档路径)
       → RateLimitInterceptor(/api/**，Redis 失败回退进程内计数)
       → AdminCheckAspect / PermissionAspect（能力）
       → Controller → Service（事务 + 业务不变量）→ Mapper → MySQL / Redis
```

★ 链路顺序本身就是口径：`X-User-Id` / `X-User-Role` 之所以可信，是因为**一定**经过了 `JwtAuthFilter` 的覆盖写入；
限流之所以可信，是因为它取的是容器给的真实连接 IP，而不是请求头。

---

## 二、分层与依赖方向

| 层 | 只负责 | 禁止 |
|---|---|---|
| `filter/` | 认证、请求体大小、文档路径、身份注入 | 写业务判断、查业务表 |
| `config/` | 异常翻译、限流拦截器、MVC / 文档配置 | 直连 Mapper 做业务（异常处理器取用户也走 Service） |
| `aspect/` | 声明式能力校验（`@RequireAdmin` / `@RequirePermission`） | 判「这份数据是不是你的」（那要查库，属 Service） |
| `controller/` | 协议层：路径、参数、身份头、转调 Service | 注入 Mapper、写 `@Transactional`、写业务规则 |
| `service/` | 业务规则、事务边界、对象级授权、缓存失效 | 返回 `ResponseEntity`、读写 Servlet 请求头 |
| `mapper/` | SQL 与行映射 | 承载业务判断 |
| `common/` | 纯规则与常量（可单测、不依赖 Spring Web） | 拿 Mapper、开启事务 |

**依赖方向单向**：`controller → service → mapper`；`aspect → common`；`common` 不反向依赖任何一层。

- **Controller 不注入 Mapper、不写 `@Transactional`。** 否则事务边界散到 Web 层，这段逻辑也没法在 Service 层复用或单测。
  验：`grep -n "Mapper" src/main/java/com/survey/controller/*.java`，除下面登记的例外外只应命中注释。
  ★ **现存例外**：`UserController`（登录时读角色码与权限点）、`SurveyController` / `SystemStatisticsController`
  （系统总览与统计按模块权限裁剪字段）直接注入 `RoleMapper`。三处都是**只读、无事务**；
  新增代码不要照抄，优先把权限查询收在 Service / 已有的 `SurveyAccessService`。
- **对象级不变量必须在 Service 层。** `SurveyAccessService.requirePermission`、`AnswerService.submit` 的守卫、
  `UserService.deleteUser` 的删除保护都在 Service；新增入口先找「有没有现成的统一入口」。
- **静态工具类不拿 Mapper。** 协作者判定规则留在 `OwnershipGuard` 的纯函数里，查库交给 `SurveyAccessService`，
  这样规则可单测、无 Spring 上下文。
- **切面只管能力，不管对象。** 只判注解 → 有 `survey:edit` 的人能改**所有人**的问卷；只判归属 → 被邀请的 analyst 也能改题。
- **`config/SpaController` 是 SPA 兜底，不属于业务端点。** 它不在 `GlobalExceptionHandler` 的增强范围内
  （`basePackages = "com.survey.controller"`），抛出的 `ResponseStatusException` 由容器按自带状态码处理；
  统计端点时不要把它算进来。

---

## 三、必须遵守的铁律

每条格式：**规则 → 为什么 → 违反后的后果 / 怎么验**。同主题已合并，不按条号罗列。

### 3.1 异常与响应

- **业务失败一律 `throw new BizException(原因)`，不 `return` 文案、不 `return null`。**
  为什么：前端只看 HTTP 状态码，2xx 一律当成功；返回字符串会被当成正常数据。
  后果：`api/http.js` 里没有任何中文字符串比对，返回 `"无权限"` 只会让页面拿到一段"成功"的文本。
- **`BizException` 翻译成 400 且不写 `t_error_log`。** 为什么："输错密码""无权限"是可预期的用户操作，
  不是程序故障；写日志会把真正的异常淹掉。验：连续输错密码后 `t_error_log` 行数不变。
- **系统级故障抛 `RuntimeException`（或让它冒泡）→ 500 + 固定文案，写 `t_error_log`，不回显 `message`。**
  为什么：MySQL 与 AI 的异常文本会带表名、SQL 片段与网络细节。验：500 响应体固定是「系统繁忙，请稍后重试」。
- **权限拒绝用 `ForbiddenException` → 403，不用 `BizException`。** `@RequireAdmin` / `@RequirePermission`
  切面抛的就是它；`OwnershipGuard` / `SurveyAccessService` 的归属失败仍是 400。
  为什么：400 是"这次请求不对"，403 是"你的身份做不了"，前端引导不同（改输入 vs 找管理员授权）。
  验：`EndpointAuthMatrixTest` 的管理档逐条断言 403。
- **错误响应形状固定** `{success:false, code, message, timestamp}`，只由 `GlobalExceptionHandler` 产出。
  页面不要自己拼错误对象。
- **`ResponseStatusException` 必须由专门 handler 接。** 为什么：它继承 `RuntimeException`，
  没 handler 就会被 500 兜底吃掉，干净的 404 变成 500 且写三条日志。4xx 一律不写异常日志。
- **参数绑定失败 → 400 且不写日志**（请求体非法 JSON / 类型不匹配 / 缺必填参数或请求头 / Content-Type 不支持），
  文案由 `describeClientError` 翻成人话，**不回显框架原文**（会泄露类型名与请求体片段）。
- **`IllegalArgumentException` 仍然是 400 但会写日志**，与上面几条 400 不同；不要拿它表达业务拒绝。

### 3.2 权限与归属

- **完整授权 = 能力 × 对象，两半都要写。** 能力用 `@RequirePermission`（或 `@RequireAdmin`），
  对象用 `SurveyAccessService.requirePermission`（内部走 `OwnershipGuard.canAccess`）。
  只写一半 = 横向越权；新增端点先问"这两半分别在哪"。
- **`@RequireAdmin` 只认超级管理员（`Role.isAdmin`）。** 次级管理员 `subadmin` 不进它，只能走权限点。
  `Role.isStaff` 仅用于数据范围放行，**不能**替代权限点判断。
- **系统总览与系统统计对 staff 开放，字段严格按模块权限裁剪。** `/survey/system-overview` 与
  `/system/statistics` 都在方法内判 `Role.isStaff`（没有权限点注解）；问卷 / 用户 / 模板 / 举报 / 钱包 / 日志
  分别只认 `survey:manage` / `user:manage` / `template:manage` / `review:handle` / `wallet:manage` /
  `log:view`。`system:view` **只解锁统计页的资源模块**（数据库大小 / 表行数 / 快照占用），
  不再等于全站业务数据。
  为什么：次级管理员没有某模块权限就不该看到该模块数字；**没权限的字段必须不返回**，
  只靠前端隐藏等于把数据照样发给了他。
- **协作者只有 ACCEPTED 生效。** `t_survey_collaborator.status` 的 PENDING / REJECTED / REVOKED
  都不参与权限判定；`findCollaboratorRole` 是唯一权限入口，名单、共享列表与名额计数也一律只认
  ACCEPTED。名额费在**接受时**扣，邀请不扣、拒绝/撤销因此不需要退款。
- **作者对自己问卷全权，不查权限点表。** 为什么：作者对自己数据的支配权不该由一张配置表决定，
  配置漏了会变成"作者删不了自己的问卷"。
- **身份只从 `X-User-Id` / `X-User-Role` 取。** 所有"我的"类接口绝不允许由请求体指定归属。
  为什么：这两个头由过滤器覆盖写入，客户端伪造的值会被真实值覆盖。
- **管理端点的 `@RequestParam` 一律 `required = false` + 方法体判空。**
  为什么：参数绑定发生在切面**之前**，缺参直接 400，普通用户拿到的就不是 403，`EndpointAuthMatrixTest` 会红。
- **权限点字符串固定 `资源:动作`、全小写，常量只在 `Permission`。** 上线后不改名；
  废弃就新增一个，旧值在迁移脚本里删，不要原地改（它同时存在于数据库、注解、审计表与接口文档）。
- **超管保护：不可删除 / 禁用 / 降级 / 注销，且任何人都不能删自己。** 次级管理员只能由超管操作。
  为什么：这是"系统一定还有人能进后台"的最强保证，不依赖"最后一个管理员"的时机判断。
- **失败状态码不要互换**：能力不足 403、对象不属于你 400、未登录 401。前端按这三种走不同分支。
- **反馈 / 客服只认 `feedback:handle`，用户侧按工单归属。** 管理侧 4 个端点全部挂
  `@RequirePermission(feedback:handle)`，不复用 `review:handle` / `system:view`；用户侧不挂权限点，
  但每条读写都校验 `feedback.user_id == 当前用户`。工单只关闭、不物理删除。
- **伪实时轮询的固定口径**：详情打开 3s（管理端 5s）、`afterId` 增量、单次最多 50 条 + `hasMore`、
  页面隐藏暂停、失败 3/5/10/20 秒退避；发送带 `clientMsgId`（UUID）幂等。不要改成 WebSocket 或
  无上限全量拉取。
- **角色 / 权限变更必须作废对方已签发的凭证。** 升级、降级、改权限后同时写
  `t_user.token_valid_after` 并吊销该用户全部 refresh token。只做一半会出现
  “旧 access token 还能用”或“用 refresh token 续出一张新票”，被降级的人仍留在管理端、
  前端菜单与后端权限也对不上。前端拿到 401 后续期失败会清登录态并跳登录。

### 3.3 事务

- **`@Transactional` 只标在 Service 的公开入口，Controller 一律不写。**
  为什么：事务是"一次业务要写哪几张表"的一致性边界，只有 Service 知道这件事；
  写在 Controller 会让边界散到 Web 层，也无法被其他 Service 复用。
- **同类内部调用不过 Spring 代理。** `this.xxx()` 上的 `@Transactional` 等于没写；
  薄包装方法自己也要标，或在编排方法里用 `TransactionTemplate` 显式开事务。
  后果：多表删/插各自 autocommit → 半删、半成品数据。
- **包含外部 HTTP 调用的方法绝不加 `@Transactional`。** AI 生成最长 120 秒，
  事务只包住落库的几条 `INSERT`；包住整段就是让一个请求占着数据库连接几分钟。
- **缓存失效与站内通知放在 `afterCommit` 回调里**（`TransactionSynchronization`）。
  为什么：事务回滚时缓存不能先动，否则会出现"库里没有这份答卷、答卷数却多 1"的脏值；
  `DEL` 放事务里虽然幂等，但 `INCR` 不行，两者统一到提交后。
- **级联删除顺序自底向上，且必须复用统一入口。** `SurveyService.cascadeDeleteSurvey` 的顺序是
  答卷明细 → 答卷 → 选项 → 题目 → 版本 / 协作者 / IP / 举报 → 指向该问卷的通知 → 问卷本体；
  `UserService.deleteUser` 复用同一入口，不要手写第二套顺序。新增"从属于问卷"的表必须加进这份清单。
- **删用户必须有保护且整体回滚。** 不能删自己、不能删超管、次级管理员只能超管删；
  中途失败不能留下"用户没了、问卷还在"的孤儿数据。

### 3.4 状态与枚举

- **状态 / 题型 / 生命周期 / 角色 / 协作者角色 / 计费项一律用枚举或常量。**
  不写 `status == 1`、`type == 1 || type == 2`、`"admin".equals(...)`。
- **`t_survey.status` 的 0/1/2 数字语义冻结，不新增 `status = 3`。** 业务态进 `SurveyLifecycle`
  （`SUSPENDED` 时 `status` 仍是 1）。为什么：数字被定时任务、前端路由与大量 SQL 依赖，改语义等于全站回归。
- **每个改 `status` / `deleted` 的入口同时写 `lifecycle`**：发布、自动发布、自动关闭、移入回收站、恢复、举报下架。
  只写 `status` 会让两套口径打架；对账 SQL 写在 `SurveyLifecycle` 的类注释里。
- **题型编号只追加不复用。** 是否有选项用 `QuestionType.hasOptions(x)`；前端 `utils/questionTypes.js`
  必须与后端 `QuestionType` 一一对应。
- **`status` 为 NULL 视为草稿**（`SurveyStatus`），别在业务代码里各处补判空。
- **回收站优先级最高**（`ARCHIVED`）：回收站问卷不能编辑 / 发布 / 收答卷 / 设模板；
  已结束问卷要先延长截止时间才能重新发布。`SurveyMapper.update` 的 SQL **不含** `status` / `share_code`，
  这是把不变量搬进了 SQL，不要"顺手"补上。
- **创建问卷时 `status` / `shareCode` / `isTemplate` / `deleted` 由服务端强制归零。**
  分享码服务端独占：`/survey/update` 传 `shareCode` 会被忽略，只有发布端点才生成。

### 3.5 上限与契约

- **任何长度 / 条数上限都要与落库处对账。** 文本上限集中在 `TextLimits`，其值必须**等于**
  `information_schema` 里的列宽；要放宽就先 `ALTER TABLE` 再改 Java，别只改一侧。
  后果：Java 上限 > 列宽时，超长会通过校验再被 MySQL 拒绝，用户看到 500 而不是 400。
- **分页一律过 `PageParams`**（`pageSize ≤ 100`、`pageNum ≤ 10000`，`offset` 用 long 算）。
  为什么：`pageSize=100000` 会真的去 `LIMIT 100000`；天文数字页码会在 int 上溢出成负数，SQL 报错变 500。
- **导出上限是防护口径，不是可随手调的参数**：答卷 5000 份 / 明细 50000 行是真正的闸门，
  文本答案单次读 2000 条。★ 不要给 `findBySurveyId` 加 `LIMIT` —— 那会让导出**静默截断**。
- **头像真实上限是解码后 2MB**（`AvatarValidator.MAX_BYTES`）；字符串 3MB 只是第一道廉价闸；
  请求体 >4MB 由 `RequestSizeFilter` 直接 413。前端按 2MB 拦并与后端一致。
- **模糊搜索参数先过 `SqlLikeEscaper.escape()`。** 否则用户输入一个 `%` 就等于"匹配所有行"。
  ★ 只转义用户输入；演示数据那种固定字面前缀不要转。
- **多选题口径：一选项一行**（每题最多 50 行），单选 / 填空严格一行。
  改这个口径必须同时改统计的 `GROUP BY content`、演示数据与历史数据，否则图表失真。
- **跳转 `nextQuestionId` 存真 `question.id`**（不是 1 基序号）；保存时校验目标属于本问卷；
  复制问卷要做跨问卷映射。
- **新入参用 DTO + `@Valid`，失败由 `GlobalExceptionHandler` 翻成 400。**
  老的 `if (...) throw new BizException(...)` 仍有效，但迁移时**迁一个删一处**，别让两套规则并存。

### 3.6 缓存与限流

- **Redis key 只能由 `RedisKeys` 生成。** 缓存前缀 `survey:cache:`、限流前缀 `survey:rate:` 不得共用、
  不得互为前缀。为什么：共用前缀会出现"清一次缓存，防爆破限流同时失效"这种极难排查的问题。
  `RedisKeysTest` 钉住了隔离性。
- **禁止 `KEYS *` / `FLUSHDB` / `FLUSHALL`。** 它们会把限流计数一起清空（等于短暂解除全部限流），
  `FLUSHALL` 还会清掉同实例上别的应用。要清就按具体 key `DEL`；只读排查可用 `KEYS 'survey:rate:*'` 看现状，
  但**不要**用它做删除。项目里所有删除路径都按具体 key 删，没有一处前缀扫描。
- **两条链路的降级方向相反，且必须保持相反。** 缓存失败当"未命中"回源直查库；
  限流失败必须当"仍然受限"（回退进程内计数）。改成"Redis 挂了就放行"等于把一次依赖故障放大成安全事件。
- **Redis 熔断是硬要求。** 失败一次短路 10 秒，成功即立刻恢复；否则每个请求都要等一次命令超时
  （实测 4000ms/请求，会占满 Tomcat 线程池，效果等同宕机）。
- **缓存失效必须在事务提交后；答卷数 `INCR` 仅在 key 存在时做。** 两套统计口径（含 / 不含无效样本）
  是两套 key（`qstats` / `qstats-valid`），失效时必须一起删。
- **限流按真实连接 IP 计数，不看 `X-Forwarded-For`。** 日志里的 IP 优先信任该头、可伪造，
  只能用于展示，不能用于任何安全判决。
- **限流规则可带可选 `method`。** `RateLimitInterceptor.Rule` 默认只按路径前缀匹配；反馈这类
  “只限写操作”的规则必须带 `POST`，否则 3 秒一次的 GET 轮询会把 20 次/分的配额吃光。
  新增轮询型接口时先检查它会不会撞上已有前缀规则。
- **缓存 / 限流各有开关**（`app.cache.stats-enabled`、`app.rate-limit.redis-enabled`）：
  false = 完全绕过 Redis，用于对比压测与 CI。新增缓存前先回答三件事：回源代价多大、TTL 多少、失效点在哪；
  没有明确失效点的数据不要缓存。

### 3.7 端点生命周期

- **删端点前先问"有没有替代者"，不是"有没有调用者"。** "前端零调用"是必要不充分条件。
  反例：某个提交端点前端零调用，却是唯一能把答题人身份写进 `t_answer.user_id` 的路径，删掉整列变死列。
- **删字面量端点必须登记 `SurveyController.REMOVED_LITERAL_PATHS`。** 路径变量声明成 `String`，
  先查这张表给 404 +「该端点已下线」，非数字再给 400。为什么：`@PathVariable Integer` 遇到非数字
  **在进入方法体之前**就抛错，客户端会拿到文案指向不存在参数的 400。
- **删除的验收口径是 HTTP 实测**：已删 URL 必须 404/405 且文案不误导、保留端点全部 200、
  `t_error_log` 不增长、`SurveyControllerRoutingTest` 变绿。
- **白名单唯一出处 `JwtAuthFilter.isWhiteList`。** 它只放匿名答题 / 登录注册 / 验证码 / 公告 / 健康检查这类
  功能必需路径；增删要同步审计表与前端匿名页的 `silent` 调用（否则未登录时会被"登录过期"打断）。
- **端点变更同步三处**：`AUDIT.md`（标题 / 汇总 / 明细 / 注解计数四处一致）、
  `EndpointAuthMatrixTest` 的清单与断言、前端 `api/` 封装。测试先红是它该做的事。
- **改表必须同时更新 `sql/init.sql` 与一份独立的 `sql/migration-*.sql`**，脚本头写清撤销方式与能否重复执行。
  否则"从零建库"和"老库升级"会走出两个不同的结构。
- **删端点不要只删映射、把下游留成死代码。** 确认没有其他调用方后，`Service` / `Mapper` 一起清；
  反过来，恢复端点也不能只加注解——整条调用链要补齐。

---

## 四、唯一口径类

同一件事只允许有一个出处。下表是"该去哪找"，不是"可以参考"。

| 类 / 文件 | 管什么 | 不要绕过它做什么 |
|---|---|---|
| `common/BizException` | 业务失败 → 400 + 文案，且不写异常日志 | 不要 `return "错误文案"`、不要改用 `RuntimeException` |
| `common/ForbiddenException` | 权限不足 → 403，不写异常日志 | 不要用 400 表达"身份不够" |
| `config/GlobalExceptionHandler` | 全部异常 → 统一 JSON，4xx 不写日志 / 5xx 写 | 不要在各 Controller 里自己 `try-catch` 拼响应 |
| `common/Role` | 角色常量（`admin` / `subadmin` / `user`）与 `isAdmin` / `isStaff` | 不要写 `"admin".equals(role)` |
| `common/Permission` | 权限点字符串（`资源:动作`）唯一出处 | Java 代码里不要写权限点字面量（`migration-rbac.sql` 的种子除外） |
| `common/OwnershipGuard` | 归属 / 协作者纯判定（作者、管理员、协作者三选一） | 不要手写 `survey.getUserId().equals(userId)` |
| `service/SurveyAccessService` | 「这个人能不能动**这份**问卷」的唯一入口（负责查协作者与权限点表） | 不要跳过它直接查 `t_survey_collaborator` |
| `common/CollaboratorRole` | 协作者三种角色 → 权限点映射；脏数据解析失败按无权限 | 不要给协作者开删除 / 发布 |
| `common/SurveyStatus` | 收答卷状态 0/1/2，NULL 视为草稿 | 不要写 `status == 1` |
| `common/SurveyLifecycle` | 业务生命周期（含被举报下架），附对账 SQL | 不要新增 `status=3` |
| `common/QuestionType` | 题型编号 + `hasOptions()` | 不要用 `type == 1 || type == 2` 判选项 |
| `common/TextLimits` | 文本上限，值 = 数据库列宽 | 不要在 DTO 里另写一套数字 |
| `common/PageParams` | 页码 / 每页条数夹紧 + `offset`（long） | 不要把 `pageNum` 直接丢给 `LIMIT` |
| `common/SqlLikeEscaper` | LIKE 通配符转义（只转用户输入） | 不要拼接未转义的 `%keyword%` |
| `common/AvatarValidator` | 头像格式、魔数、解码后 2MB 上限 | 不要只校验字符串长度 |
| `common/Grader` | 考试判分唯一口径（单选 / 下拉 / 多选，默认方案） | 不要在 Controller 或前端各算一份分 |
| `common/AnswerDisplay` | 多行明细 → 一行可读文本（导出与"我的答案"共用） | 不要为 Excel 另写渲染 |
| `common/AnswerQuality` | 无效样本四条规则与原因码 | 不要把"可疑"直接删掉 |
| `common/MatrixConfig` | 矩阵行列 JSON 的解析与校验 | 不要手写 `JSON.parse` 后直接落库 |
| `common/DateRanges` | 管理端时间筛选口径（结束取 23:59:59，空 = 不限） | 不要自己解析日期字符串 |
| `common/WalletFeature` | 计费项、单价、计费模式（一次性 / 按次） | 不要在 Service 里写价格数字 |
| `common/RedisKeys` | Redis key 唯一生成器（缓存 / 限流两个命名空间） | 不要在业务代码里拼 key 字符串 |
| `common/RedisCircuitBreaker` | Redis 熔断（失败短路 10 秒，成功即恢复） | 不要绕过它直接 `redisTemplate` 调命令 |
| `config/RedisRateLimiter` | 限流计数 Redis 实现（Lua 原子完成 INCR + PEXPIRE）；返回 null = 不可用 | 调用方拿到 null **必须**回退进程内计数 |
| `service/StatsCacheService` | 统计缓存读写 / 失效 / 降级 | 不要在 Service 里直接操作缓存 key |
| `config/RateLimitInterceptor` | 限流规则清单与执行（429） | 不要新增"只在前端防"的限制 |
| `util/JwtUtil` | 签发 / 校验；有效期只有一个配置来源；`isIssuedAtOrAfter` 是 **iat 严格晚于** cutoff | 不要自己解析 token 或另设有效期 |
| `util/WebUtils` | 客户端 IP（日志用）/ 连接 IP（限流用）/ 统一错误输出 | 不要拿日志 IP 做安全判决 |
| `filter/JwtAuthFilter` | 第一道门：白名单、身份注入、查库判账号有效、校验 `token_valid_after` | 不要在 Controller 里重复解析 token |
| `filter/DocAccessFilter` | 文档路径单独限管理员，与 API 拦截职责分离 | 不要把文档路径塞进 API 白名单 |
| `styles/theme.css`（前端） | 视觉唯一口径：`--sky-*` / `--brand-gradient` / `--shadow-*` / `--r-*` + 组件覆盖 + 动效 | 页面里不要写死颜色与阴影 |
| `api/http.js`（前端） | 唯一请求客户端：token、状态码判定、401 续期、`silent` | 页面里不要裸 `fetch` |
| `api/index.js`（前端） | 接口汇总出口（`api.survey.xxx`） | 页面不要直接 import 具体模块文件 |
| `utils/auth.js`（前端） | 登录态唯一出口：`token` / `user` / `refreshToken` / `permissions` 读写 | 不要直接碰 `localStorage` |
| `utils/questionTypes.js`（前端） | 题型展示与判定，与后端 `QuestionType` 对齐 | 不要在页面里写题型数字 |

---

## 五、关键业务不变量

| 项 | 要点 |
|---|---|
| **用户软删除口径** | `UserMapper` 里每个查用户的 SQL 都带 `deleted_at IS NULL`；只有三个故意不过滤的方法（`findByIdIncludeDeleted` / `findByUsernameIncludeDeleted` / `findByUsernamePrefix`），各自 javadoc 写了原因。新增 `FROM t_user` 查询前先读类注释——漏一处 = 已注销账号还能用 |
| **账号注销与恢复** | 注销只写 `deleted_at`，不级联删任何东西；用户名继续被占用（防抢注）；需密码；`restoreUser` 同时写 `token_valid_after` ⇒ 恢复后必须重新登录 |
| **`token_valid_after`** | 写入点只有改密码与恢复账号（都截到秒）；比较口径是 **iat 严格晚于** cutoff，同秒一律拒 |
| **答卷防重** | 登录用户按 `survey_id + user_id` 查重 → 400；匿名侧有意不查（NAT 会误伤） |
| **答卷质控** | 提交时按四条规则打标：过快（< 题数 × 1.5s）、直线作答、答案不在选项里、同题干答案不一致。★ 只打标不删除；统计接口 `excludeInvalid=true` 才剔除；耗时来自前端上报、可伪造，只作提示 |
| **版本快照** | 只在题目结构真的变了时生成（改标题、重复保存不生成）；每份问卷最多 50 版，超出删最旧；回滚 = 全量覆盖写回 + 生成新版本（历史线性、可再回滚）；跳转规则指向"会被重建的题目"时拒绝回滚 |
| **发布 / 更新状态机** | 回收站不能编辑 / 发布 / 收答卷 / 设模板；已结束要先延长截止时间；`SurveyMapper.update` 的 SET 列表不含 `status` / `share_code` |
| **分享码** | 服务端独占：更新接口传了就忽略，只有发布才生成 |
| **跳转关系** | `nextQuestionId` 存真 `question.id`，保存时校验目标属于本问卷，复制问卷时做跨问卷映射 |
| **删除问卷** | 移入回收站 ≠ 彻底删除；彻底删除按 `cascadeDeleteSurvey` 的固定顺序清到底，包括版本、协作者、IP 记录、举报、以及指向该问卷的通知 |
| **删除用户** | 不可逆且级联：先删该用户全部问卷（复用问卷级联）、再删通知、最后删用户；不能删自己、不能删超管、次级管理员只能超管删 |
| **禁用 / 解禁** | 除写 `disabled` 外同时写 `token_valid_after`，此前签发的 token 立刻作废；不能禁用自己；不能禁用最后一个**可用**管理员；已注销账号要先恢复 |
| **协作者边界** | `viewer` 只能看问卷结构；`editor` 可改内容且可看数据、不能删；`analyst` 只能看数据；作者与管理员全权，作者不依赖权限点表 |
| **钱包幂等** | 每一笔业务带 `idempotencyKey`（如 `publish:{surveyId}`），靠唯一键保证只扣一次；发布扣点、移入回收站退款；进阶题型包按问卷只扣一次，删掉不退款 |
| **考试判分** | 提交时算分并连同总分一起写进 `t_answer`（快照），事后改分值不影响历史答卷；多选全对才得分、不做部分得分；统计只算 `earned_score IS NOT NULL` 的行 |
| **Excel 导出** | 单元格渲染走 `AnswerDisplay`（多选 `A、B、C`、矩阵 `行：列`、排序带名次）；导出上限见 3.5；文件名同时给 `filename` 与 `filename*`，前端优先解析后者 |
| **个人中心两页** | `UserProfile.vue` 与 `AdminProfile.vue` 是同一份复制品，改一边必须同步另一边 |
| **无效样本两套缓存** | `qstats` 与 `qstats-valid` 是两套 key，失效时必须一起删 |

---

## 六、前端铁律

- **分层不要越层**：`views/*.vue` → `api/*.js` → `api/http.js` → `fetch`。页面只管"拿数据 / 交给谁"。
- **`api/` 是唯一 HTTP 出口**：页面里**不写裸 `fetch`、不拼 URL、不判断 `data.success`、不比对中文文案**。
- **`http.js` 只看 `response.ok`**；失败时取响应体 `message` 展示。401 先尝试用 refresh token 续期，
  续不动才清登录态并跳登录；`silent: true` 的调用不弹提示、不跳转（轮询与匿名页必须保留它）。
- **成功响应不统一**（裸字符串 / `{success,message}` / 实体 / `PageResult`），按各接口实际返回类型处理；
  分页一律 `data.list || []` + `data.total || 0`。
- **全部 Options API，不引入 `<script setup>`。** `views/` 下现状没有任何 `<script setup>`，保持一致。
- **反馈 / 客服线程只走 `components/FeedbackThread.vue`。** 固定 header、消息区独立滚动、composer 常驻、
  5 分钟时间分组、Enter 发送（中文输入法组合态不发送）都在组件内；页面只传 `loader` / `sender` /
  `closer` / `reader`，不要各写一套轮询与排版。`pollInterval` 传 0 即不轮询（反馈是异步意见箱），
  `readonly` 隐藏输入框与关闭按钮——轮询只用于客服工单。
- **视觉令牌只认 `styles/theme.css`**：颜色用 `--sky-*`、渐变用 `--brand-gradient`、阴影用 `--shadow-*`、
  圆角用 `--r-*`。页面里不要写死 `#409eff` 之类的色值。
- **路由守卫三层**：`meta.public`（免登录）→ 登录态 → `meta.roles` + `meta.permission`。
  三者都只是**体验层**：localStorage 可篡改，真防线在后端；拿不到数据是前端越权的唯一结果。
- **菜单与按钮显隐按 `permissions` / `myPermissions` 分开**：前者是全局权限点（决定菜单），
  后者是"对这份问卷"的权限（决定按钮），两者不要混。
- **列表页必须带请求序号**（`loadSeq` 模式）：日期控件 `@change` 与"重置"本身都会发请求，
  没有序号就会出现"慢响应覆盖新结果"。
- **图标与 ECharts 都是按需注册**：新图标要在 `main.js` 的 import 与 `icons` 对象各补一行；
  `:icon="X"` 这类属性变量形式还要该页面自己 import。新图表类型 / 组件要补进 `echarts.use([...])`。
- **个人中心两个页面同步改**，并优先用 `el-form` 的 `rules` 在页内展示错误，不要回到手写 `if` + `ElMessage.warning`。
- **前端校验只是体验**：后端对同一规则必须二次实现；`maxlength` 应当**等于或大于**后端上限。

---

## 七、环境与工具坑

| 坑 | 正确做法 |
|---|---|
| PATH 上的 `java` 是 JDK 8 | 每次先 `$env:JAVA_HOME='C:\Users\1\.jdks\ms-17.0.20.1'`（项目要 JDK 17），否则编译静默失败或落到旧 JDK |
| 只跑 `compile` 会"假成功" | 有 8080 在跑时：先 `(Get-Item $f).LastWriteTime=Get-Date` 再 `mvnw -o compile`；8080 已停时才用 `mvnw -o clean compile`。IDEA 的容错编译会留下"能生成但运行时报 `Unresolved compilation problem`"的 class |
| `clean` 会删掉 8080 正在用的静态文件 | **不要删 `target\classes`**：IDEA 正用它跑 8080，里面还有前端静态文件；必须 clean 前先确认 8080 已停 |
| "实例活着" ≠ "跑的是新 class" | 涉及路由 / 端点的改动，先用一个"只有新代码才有"的探针验收（如已删端点应 404），别只看进程 |
| `job_kill` 杀不掉 maven fork 出的 java | 在**同一条命令**里按端口 `netstat -ano` 找 PID 再 `Stop-Process`；沙箱里 `Get-NetTCPConnection` 返回空；别用 `-Name java` 误杀 8080 |
| 沙箱起不了无头浏览器 | 页面视觉与浏览器交互**无法验证**；只能靠 HTTP 实测 + ESLint + 构建，并如实告诉用户这部分没验 |
| `npm run dev` / `vite build` 报 spawn EPERM | 与 `package.json` 无关；用 `npx vite --configLoader native`（dev 与 build 都有效） |
| Node 里 `child_process` 取管道输出 EPERM | 验证脚本只做 HTTP；造临时账号 / 查库取证放到 pwsh |
| MyBatis 字段名不匹配时静默不赋值 | 项目开了 `map-underscore-to-camel-case`，写错就是该属性恒为 null。新实体 / 映射后用真实数据打一次接口，逐字段核值 |
| `@Valid` 在进入方法体之前执行 | 不能在 Controller 里先 `trim` 再校验；改 DTO 正则或写自定义约束注解 |
| Spring Boot 4 不再注册 Jackson 2 的 `ObjectMapper` bean | 自己持有 `private static final ObjectMapper MAPPER`（与 `QwenClient` / `WebUtils` 一致），`@Autowired ObjectMapper` 会让启动失败 |
| Boot 4 的 MockMvc 注解换了包 | `@AutoConfigureMockMvc` 在 `org.springframework.boot.webmvc.test.autoconfigure`，`@MockitoBean` 在 `org.springframework.test.context.bean.override.mockito`；按 Boot 3 的 import 写编译不过 |
| 新增 `@SpringBootTest` 会连上活库 | MockMvc 用例把**全部 Mapper + `DataSource`** 都 `@MockitoBean`；漏一个就会真查库；别把 `datasource.url` 指向 `localhost:3306` |
| 真库集成测试有独立库 | 只认 `survey_db_test`，口令走环境变量；绝不指向活库 `survey_db`；跑完自清数据 |
| 本机无外网 | 别删 `node_modules`（装不回来）；Maven 用 `-o` 离线；新增依赖需联网一次，可用工作区临时本地仓库避免写 `~/.m2` |
| `.java` 是 CRLF，且不能带 BOM | 脚本替换前先 `replace(/\r\n/g,'\n')` 归一；写 Java 用 `UTF8Encoding($false)`，不要用会加 BOM 的 `Set-Content -Encoding UTF8` |
| PowerShell 重定向会改编码 | 生成 `.sql` 等给别的程序吃的文件用 `cmd /c "... > file"` 或 Node 的 `writeFileSync`；BOM 会让 `mysql < init.sql` 语法报错 |
| MySQL 中文乱码 | `mysql.exe` 加 `--default-character-set=utf8mb4`，pwsh 里设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` |
| 造临时账号时用户名带下划线 | 账号规则是字母数字 4~20 位；带下划线会被登录的严格校验直接判为"账号或密码错误" |
| 按条数 / id 区间清测试数据 | 按 `username` / `share_code` 定位；压测夹具用 `tools/bench/bench-cleanup.sql`（可重复执行） |
| 后端要手动重启 | 项目**没有** `spring-boot-devtools`；前端 Vite dev 会热更新，后端改完必须重启 |
| Redis 是可选依赖 | 本机在 `tools\redis-5.0.14.1`（6379）；没起也能跑，只是缓存与限流各自降级 |
| 浏览器是用户界面 | 不要杀 `msedge`；`.dsh-tmp` 里的 edge-profile 残留等用户关浏览器后再清 |
| 多行中文 commit message 被引号搞坏 | 用 `git commit -F <文件>` |
| `edit` 偶发 `ReplaceFileW EIO` | 重试一次通常就好；不行就用 Node 脚本写 |

---

## 八、改代码前的检查清单

1. 读 `SPRINT.md`，确认这件事没被拍成"明确不做"；新发现的未决项回写 `SPRINT.md`。
2. 动端点前查 `AUDIT.md` 的档位（要不要登录 / 管理 / 归属 / 业务前置条件），改完跑表末的复核命令。
3. 新增或修改权限判断：能力（`@RequirePermission` / `@RequireAdmin`）+ 对象（`SurveyAccessService`）两半都写，
   并确认失败状态码是 403 / 400 / 401 中的正确那一个。
4. 新增写操作：先定事务边界，确认事务里没有外部 HTTP；缓存失效与通知是否已放 `afterCommit`。
5. 新增表 / 列：同时改 `sql/init.sql` 与独立 `sql/migration-*.sql` 并写撤销说明；核对 `TextLimits` 是否等于列宽。
6. 新增查询：`FROM t_user` 必带 `deleted_at IS NULL`；模糊参数过 `SqlLikeEscaper`；分页过 `PageParams`；
   并检查 MyBatis 的下划线映射是否每个字段都有值。
7. 新增缓存 / 限流：先在 `RedisKeys` 加生成器，写清 TTL 与失效点，确认两个命名空间隔离、降级方向正确、有熔断。
8. 新增 / 删除端点后同步：`EndpointAuthMatrixTest`、`SurveyControllerRoutingTest`、`AUDIT.md`、前端 `api/` 封装。
9. 验证命令：后端 `mvnw -o clean compile` + `mvnw -o test`；前端 `npx eslint .` + `npx vite --configLoader native build`。
10. 收工三件事：HTTP 实测（不是只看测试绿）、确认 `t_error_log` 不增长、把无法验证的部分（视觉 / 浏览器交互）如实说明。

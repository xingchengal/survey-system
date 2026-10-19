# AUDIT · 端点权限与事实矩阵（全量 **126** 个方法级端点）

> **定位**：四份主文档之一，**项目数字的唯一出处**（端点 / 表 / 测试 / 页面）。
> **用途**：动任何 Controller 之前，先在下面四张明细表里找到那个端点，
> 看它「应当」是哪一档，再决定要不要补校验；新增端点时也照着这个标准归一次类。
> 判断维度是**四列**：① 要不要登录 ② 要不要限管理员 ③ 要不要校归属 ④ **业务前置条件是否成立**
> （问卷是否已发布 / 是否在回收站 / 是否已结束……）。
> **端点变更历史**（哪一轮加了哪几条、分档怎么订正）见 `HISTORY.md`；本文件只写**现状**。
>
> **当前口径（2026-10-19 按代码重核，四处一致）**：**126** 端点 = 公开 **13** + 需登录 **42** + 归属 **31** + 管理 **40**；
> Controller **18**；复核命令见 §八，**改动端点后必须重跑**。
>
> ★ 改这张表时必须守的规矩：
> ① **标题 = §二汇总 = 四张明细表的活行之和 = 注解计数**，四处必须一致；对不上就说明**有一处是编的**。
> ② **总数对不代表分档对** —— 改完明细表**顺手核一遍总数与分档**（§八有现成命令）。
> ③ **带删除线的行是已删端点的历史记录，不计入活行数** —— 数的时候别把它们算进去。

---

## 一、判定标准

| 列 | 含义 |
|---|---|
| **登录** | 只要求带有效 token。`JwtAuthFilter` 白名单之外默认都要登录，所以判断依据是「有没有进白名单」 |
| **admin** | 必须 `X-User-Role == "admin"`，否则拒绝 |
| **归属** | 除登录外，还要确认「这条数据是不是你的」——非管理员不能读/改/删别人的 |

三条判定基线（按本项目实际业务定）：

1. **匿名答题链路必须公开**：`/survey/share/`、`/survey/view/`、`/answer/submit-share/`、`/question/list/`、`/announcement/active`、`/health`、`/user/login`、`/user/register` —— 这是**功能需要，不是漏洞**（访客凭分享码答题就靠它）。
2. **系统级数据必须限管理员**：全站统计、日志、用户列表、模板播种、演示数据。
3. **「我的」类接口一律从 `X-User-Id` 取身份**，**绝不允许**由请求体指定归属。

标记说明：🔧 = 本轮审计新修 ｜ 🛠 = 前几轮修的 ｜ ✅ = 本来就写对了

---

## 二、事实快照与端点汇总

### 2.1 事实快照（2026-10-19 收工基线）

| 项 | 值 | 复核方式 |
|---|---|---|
| 方法级端点 | **126** | §八 注解计数 |
| 分档 | 公开 **13** / 需登录 **42** / 归属 **31** / 管理 **40** | §三~§六 活行 |
| Controller | **20** | `controller/*.java` |
| 数据库表 | **25** | `sql/init.sql` 的 `CREATE TABLE` |
| 后端测试 | **470 例 / 51 类** | `mvnw -o test` |
| 前端页面 | **27** | `src/views/**/*.vue` |
| 前端 API 模块 | **18** | `src/api/*.js` |
| 具名路由 | **29** | `router/index.js` |

### 2.2 端点分档

| 应当的权限 | 端点数 |
|---|---|
| 公开（白名单，不需登录） | **13** |
| 需要登录（只能碰自己的数据） | **42** |
| 需要登录 **+ 归属校验** | **31** |
| 需要**管理员** | **40** |
| **合计** | **126** |

> ★ 历史分档订正（“总数对、分档错”、明细表漏登记 12 条等）见 `HISTORY.md` 的「审计」条目；
>   本表只写当前口径。

---

## 三、公开端点（**13 个**，`JwtAuthFilter` 白名单）

> ★ 第十九轮订正：表头原写"8 个"是上一时点的数；第十六轮之后白名单加过条目，
>   第十九轮又新增 `/api/captcha/`（滑块取图与判定）与 `/api/user/refresh`（双 token 续期）。
> ★ 第三十三轮新增 `GET /api/survey/result/{shareCode}`（公开结果页），**公开档 11 → 12**。

| 端点 | 说明 | 状态 |
|---|---|---|
| `POST /api/user/register` | 注册 | ✅ |
| `POST /api/user/login` | 登录（顺手给管理员种 `doc_token` Cookie） | ✅ |
| `GET /api/survey/share/{shareCode}` | 凭分享码打开问卷（要求 status=1）。★ 第三十三轮起：返回前把 `accessPassword` 置空，只暴露 `hasPassword` | ✅ |
| `GET /api/survey/result/{shareCode}` | ✅ **第三十三轮新增**：公开结果页。问卷作者开启 `public_result=1` 才可读；仅返回**脱敏**汇总（选项分布/评分分布），填空题原文一律置 null | ✅ |
| `POST /api/survey/view/{shareCode}` | 匿名访客记访问量 | ✅ |
| `GET /api/question/list/{surveyId}` | 加载题目+选项。**半公开**：已发布任何人可读，未发布仅作者/管理员 | 🛠 前几轮修 |
| `POST /api/answer/submit-share/{shareCode}` | 凭分享码匿名提交答卷。★ **业务前置条件（缺一不可）**：问卷存在 / 不在回收站 / **已发布** / **未过截止时间** / 非空答卷 / 题数·长度·归属合法 / **该问卷未开启「仅限登录用户」**（`require_login=1` 时直接 400，因为这条路径的 userId 恒为 null）/ **匿名侧不做防重**（按 IP 去重会误伤 NAT 后的同网用户） | ✅ |
| `GET /api/answer/participants/{shareCode}` | 公开报名名单（前 50 位，按提交时间正序）。★ 只返回**称呼与时间**，不返回手机号等其它答案；称呼取第一道单行文本题，没有则"匿名"。★ 作者未开启「公开报名名单」（`show_participants=1`）时 **400** —— 默认不公开 | ✅ 第四十五轮新增（白名单） |
| `GET /api/announcement/active` | 有效公告（未过期） | ✅ |
| `GET /api/health` | 健康检查（启动器的就绪探针） | ✅ |
| `GET /api/captcha/slider` | ✅ **第十九轮新增**：注册页取滑块验证码。注册页此时必然没有 token；只读、不含用户数据 |
| `POST /api/captcha/verify` | ✅ **第十九轮新增**：拖动结束后由后端判定。★ 只**标记**凭证 verified、不消费，真正的消费在注册那一步 |
| `POST /api/user/refresh` | ✅ **第十九轮新增**：用 refresh token 换 access token。★ 必须公开 —— 调用它的时刻 access token 已失效，要求带有效 token 会让续期永远走不通；安全性由 refresh token 自身承担（只存 SHA-256、7 天过期、一次性轮换、可吊销）|

---

## 四、需要管理员（**40 个**）

> ★ 第四十二轮（G3）：+4 条管理端点 —— `PUT /user/{id}/disabled`、`GET /user/export`、
>   `GET /survey/export`、`GET /log/export`（详见变更摘要）。
>   三条导出端点都是**只读 + 全量数据**，必须限管理员；`disabled` 那条会改变账号能否登录，更是管理动作。

> ★ 第二十轮·续订正：18 → **20**（`GET /user/page-deleted`、`PUT /user/restore/{id}` 两条**同时**加入；
>   旧文写的 19 是只算了一条）。
> ★ 第十九轮订正：表头原写 21（更早时点）。按表体活行 + 本次补登记的 1 行 = **18**；
>   带删除线的行（`/survey/statistics`、`/survey/recycle`）是已删端点的历史记录，**不计入**。
> ★ 第三十四轮：补登记 3 条举报审核端点（此前只在 Controller 里、明细表漏登记）⇒ 20 → **23**。

| 端点 | 权限点 | 状态 |
|---|---|---|
| `GET /api/user/page` | `@RequirePermission("user:manage")` | ✅ ★ **第四十二轮（G3）改口径**：查询条件从写死的 `role='user'` 改为**含管理员**，并新增可选筛选 `role`（admin/user）与 `disabled`（0/1）。★ 为什么必须含管理员：否则页面上的「角色」列永远显示"普通用户"、"角色筛选"无从谈起，"禁用某个管理员"也没有入口。★ `disabled` 是**筛选**不是默认过滤 —— 默认必须能看到已禁用的账号，否则解禁没有入口 |
| `GET /api/user/page-deleted` | `@RequirePermission("user:manage")` | ✅ **第二十轮·续新增**：已注销用户列表（供恢复）。★ 必须与 `/page` 并存 —— `/page` 按设计过滤 `deleted_at`，看不到已注销的人，恢复就没有入口。★ 第四十二轮起同样支持 `role` 筛选（已注销的管理员也要能被恢复） |
| `DELETE /api/user/delete/{id}` | `@RequirePermission("user:manage")` | ✅（物理删除 + 级联；与用户自助注销是两件事，见开头说明） |
| `PUT /api/user/restore/{id}` | `@RequirePermission("user:manage")` | ✅ **第二十轮·续新增**：恢复已注销账号。★ 只清 `deleted_at` 一列，密码与资料都还在。★ **第四十二轮**给前端的批量恢复传入 `{ silent: true }`（逐条调用，由页面汇总提示） |
| `PUT /api/user/{id}/disabled` | `@RequirePermission("user:manage")` | ✅ **第四十二轮（G3）新增**：禁用/解禁（`disabled=1/0`）。★ **立刻生效**：除写 `disabled` 外同时写 `token_valid_after`，该账号此前签发的 token 全部作废（JWT 无法吊销，这是唯一手段）。★ 三道保护：不能禁用自己 / 不能禁用**最后一个可用管理员**（`countActiveAdmins()`，不是 `countAdmins()`）/ 已注销账号要先恢复。★ 参数声明为**非必填**再自己判：必填 `@RequestParam` 会在切面之前抛 400，导致普通用户拿到 400 而不是 403（`EndpointAuthMatrixTest` 会红） |
| `GET /api/user/export` | `@RequirePermission("user:manage")` | ✅ **第四十二轮（G3）新增**：用户列表导出 Excel（`deleted=true` 导「已注销」视图；筛选与 `/page` 同一套）。★ 上限 10000 行，**超出明确拒绝**而不是静默截断（静默截断会让管理员以为导出全了）。★ 不导头像（mediumtext，单张可达 2MB） |
| `GET /api/survey/page` | `@RequirePermission("survey:manage")` | ✅ ★ **第四十二轮（G3）**新增可选筛选 `startDate`/`endDate`（`yyyy-MM-dd`，闭区间，结束补到 `23:59:59`；格式错 → 400 而不是 500）。★ 用 `created_time >= / <=` 直接比列，不包 `DATE()`（列上有索引） |
| `GET /api/survey/export` | `@RequirePermission("survey:manage")` | ✅ **第四十二轮（G3）新增**：问卷列表导出 Excel，筛选条件与 `/survey/page` **完全一致**，口径是「全部问卷」（**不含模板**，与 page 的 SQL 一致）。★ 答卷数用一次 `GROUP BY` 补齐，不逐份 count |
| `GET /api/survey/list-with-response-count` | `@RequirePermission("survey:manage")` | ✅ ★ 第十九轮复核时**补登记**：这个方法本来就有（第十二轮加的），但此前只在本表正文里被"提了一嘴"，行始终没进表 —— 四张明细表因此少了 1 条，正是"合计对不上"的一半原因。★ 口径是问卷数据，权限点跟随 `survey:manage`（不是 `system:view`） |
| ~~`GET /api/survey/statistics`~~ | 🛠 前几轮修过；**第十六轮已删除**（前端零调用，是 `/system-overview` 的真子集） |
| `PUT /api/survey/set-template/{id}` | `@RequirePermission("template:manage")` | ✅ |
| `GET /api/survey/template-categories` | `@RequirePermission("template:manage")` | ✅ **第四十六轮新增**：模板分类清单。★ 分类可以独立于模板存在（模板管理页「+ 新增分类」），所以不能从模板列表反推；管理档，因为只有管理端需要。 |
| `POST /api/survey/template-categories` | `@RequirePermission("template:manage")` | ✅ **第四十六轮新增**：新增分类（`name` 参数）。★ 非必填声明 + Service 判空：必填 `@RequestParam` 会在 `@RequireAdmin` 切面之前抛 400，普通用户拿到的就不是 403（同 `PUT /user/{id}/disabled` 的坑）。★ 返回新增后的完整清单，前端不用再 GET 一次。 |
| `GET /api/admin/permissions` | `@RequireAdmin` | ✅ **第四十七轮新增**：可分配权限目录（9 个模块级权限点）。★ 超管专属（`@RequireAdmin`），次级管理员不能给自己加权限。 |
| `GET /api/admin/sub-admins` | `@RequireAdmin` | ✅ **第四十七轮新增**：次级管理员列表（含逐人权限）。★ 超管专属。 |
| `POST /api/admin/sub-admins` | `@RequireAdmin` | ✅ **第四十七轮新增**：把普通用户提升为 `subadmin` 并授权。★ 超管专属；权限只认 `ADMIN_GRANTABLE`，角色与权限同事务。 |
| `PUT /api/admin/sub-admins/{userId}/permissions` | `@RequireAdmin` | ✅ **第四十七轮新增**：修改次级管理员权限（不改角色）。★ 超管专属。 |
| `DELETE /api/admin/sub-admins/{userId}` | `@RequireAdmin` | ✅ **第四十七轮新增**：降为普通用户（保留账号与数据）。★ 超管专属。 |
| `GET /api/log/page` | `@RequirePermission("log:view")` | ✅ ★ **第四十二轮（G3）**新增可选筛选 `role`（→「级别筛选」的落地口径）与 `startDate`/`endDate`。★ `t_operation_log` **没有"级别"列**，本页的"级别"= **操作人角色**（admin/user）；不新增 level 列（那要改表 + 回填 + 改 `LogAspect` 写入契约），"高危操作"用 `operation` 关键字筛即可 |
| `GET /api/log/export` | `@RequirePermission("log:view")` | ✅ **第四十二轮（G3）新增**：操作日志导出 Excel（筛选同上）。★ 导的是**日志原文**（含 IP 与方法名），只能管理员下载；上限 10000 行，超出明确拒绝 |
| `GET /api/error-log/page` | `@RequirePermission("log:view")` | ✅ |
| `GET /api/error-log/count` | `@RequirePermission("log:view")` | ✅ |
| `DELETE /api/error-log/{id}` | `@RequireAdmin` | ✅ |
| `DELETE /api/error-log/clear` | `@RequireAdmin` | ✅ |
| `POST /api/announcement/add` | `@RequirePermission("announcement:manage")` | ✅ |
| `GET /api/announcement/list` | `@RequirePermission("announcement:manage")` | ✅ |
| `PUT /api/announcement/status/{id}` | `@RequirePermission("announcement:manage")` | ✅ |
| `DELETE /api/announcement/delete/{id}` | `@RequirePermission("announcement:manage")` | ✅ |
| `POST /api/demo/init` | `@RequireAdmin` | ✅ |
| `DELETE /api/demo/reset` | `@RequireAdmin` | ✅ |
| `POST /api/template-seeder/init` | `@RequireAdmin` | ✅（前几轮还补了幂等） |
| `GET /api/review/list` | `@RequirePermission("review:handle")` | ✅ **第三十四轮新增**：举报审核列表（`status` 可选筛选）。★ 与 `/review/my` 的分界：这条是**全站**举报，含举报人 id 与理由原文，必须限管理员 |
| `POST /api/review/{id}/handle` | `@RequirePermission("review:handle")` | ✅ **第三十四轮新增**：处理举报（`action=SUSPEND` 通过并下架 / `IGNORE` 驳回）。★ 下架只改 `lifecycle`（`status` 不变）；SQL 幂等，只有 `PENDING` 能处理 |
| `POST /api/survey/unsuspend/{id}` | `@RequirePermission("review:handle")` | ✅ **第三十四轮新增**：取消下架（只允许 `SUSPENDED → PUBLISHED`）。★ 与第三十五轮作者侧的 `/request-restore` 分开：这条是管理员**执行**，那条只是作者**申请** |
| `GET /api/wallet/admin/page` | `@RequirePermission("wallet:manage")` | ✅ **第三十六轮新增**：用户钱包列表（分页、按用户名/昵称搜）。★ 只读；余额单位是「点」 |
| `POST /api/wallet/admin/adjust` | `@RequirePermission("wallet:manage")` | ✅ **第三十六轮新增**：调整用户额度（`amount` 正加负扣，必须带 `idempotencyKey`）。★ 流水类型 `ADMIN_ADJUST`，与用户自助充值是两类账 |
| `GET /api/wallet/admin/reconcile` | `@RequirePermission("wallet:manage")` | ✅ **第三十六轮新增**：对账（余额 ≠ 流水汇总 的钱包）。★ 只报告不自动修，确认原因后用 `adjust` 补账 |
| `GET /api/feedback/admin/page` | `@RequirePermission("feedback:handle")` | ✅ 反馈 / 工单列表（分页，`type` / `status` 可选）。★ 有 `feedback:handle` 即可看全部工单，不复用 `review:handle` |
| `GET /api/feedback/admin/{id}` | `@RequirePermission("feedback:handle")` | ✅ 工单详情（`afterId` 增量，单次最多 50 条 + `hasMore`）；进入即推进管理端已读水位 |
| `POST /api/feedback/admin/{id}/reply` | `@RequirePermission("feedback:handle")` | ✅ 回复工单：写入 admin 消息、记 `handled_by`、通知用户；POST 限 20 次/分/用户 |
| `POST /api/feedback/admin/{id}/close` | `@RequirePermission("feedback:handle")` | ✅ 关闭工单：只改 `status`（不删除），通知用户 |

---

## 五、需要登录 + 归属校验（**31 个**）

> ★ 第十九轮订正：表头原写 16，表体活行是 **15** —— 表头与表体不一致时**以表体为准**（同样要复核）。
> ★ 第二十四轮：新增 `GET /api/answer/statistics/survey/{surveyId}`（整份问卷统计，一次取回）⇒ 15 → **16**。
> ★ 第二十五轮：新增 `SurveyVersionController` 的 4 条（版本列表 / 版本详情 / 版本对比 / 回滚）⇒ 16 → **20**。
>   快照里是**草稿题目的全文**，所以不能只校验"已登录" —— 否则改一下 URL 就能读到别人草稿的题目结构。
> ★ 第二十六轮：新增 `GET /api/answer/quality/{surveyId}`（质控摘要）⇒ 20 → **21**。
> ★ 第三十四轮：新增 4 条协作者管理端点（邀请/改角色/移除/名单）⇒ 21 → **25**。

> 判定方式统一是：`if (!"admin".equals(role) && !数据.getUserId().equals(userId)) 拒绝`

| 端点 | 说明 | 状态 |
|---|---|---|
| `POST /api/survey/add` | 新建问卷 | 🔧 **本轮修**（原来不取 `X-User-Id`，`userId` 由请求体决定 → **可以把问卷伪造到别人名下**；现在强制用登录身份覆盖） |
| `GET /api/survey/{id}` | 按 id 查问卷 | 🔧 **本轮修**（原来完全没校验 → 遍历 id 就能读全站问卷**含别人的草稿**；现在：管理员全可读、本人可读、**已发布的可读**，其余拒绝） |
| `PUT /api/survey/update` | 更新问卷 | ✅（还额外 `setUserId(old.getUserId())` 防止改归属，写得好） |
| `POST /api/survey/publish/{id}` | 发布 | ✅ |
| `POST /api/survey/copy/{id}` | 复制 | ✅ |
| `PUT /api/survey/recycle/{id}` | 移入回收站 | ✅ |
| `PUT /api/survey/restore/{id}` | 从回收站恢复 | ✅ |
| `DELETE /api/survey/permanent/{id}` | 彻底删除（要求先移入回收站） | ✅ |
| `POST /api/question/save/{surveyId}` | 保存题目（全量覆盖）。★ **返回值是"按下标一一对应的题目 id 列表"** `[{id, sortOrder}]`（**不是**以前的字符串"保存成功"）：新建的题在保存前没有数据库 id，而编辑器的跳转目标必须存真 id，前端要靠这份回显把它草稿阶段的**负数临时 id** 换成真 id —— 详见 `dto/QuestionIdItem`。<br>★ **跳转目标不属于本问卷 → 400 拒绝保存、不落库**（绝不静默置空 —— 那会让用户以为规则还在）。"新建的题还没有 id"这个矛盾由**前端预存时先摘掉这类目标**解决（`SurveyEdit.ensureRealQuestionIds`），后端只认最终形态 | ✅ |
| `GET /api/answer/count/{surveyId}` | 答卷数 | 🛠 前几轮修 |
| `GET /api/answer/statistics/{questionId}` | 单题统计 | 🛠 前几轮修 |
| `GET /api/answer/texts/{questionId}` | 填空题文本 | 🛠 前几轮修 |
| `GET /api/answer/list/{surveyId}` | 答卷列表（**分页**：`pageNum`/`pageSize`，默认 1/10、每页上限 100、页码上限 10000，返回 `PageResult`） | 🛠 前几轮修；第二十八轮补分页 |
| `GET /api/answer/detail/{answerId}` | 单份答卷详情 | 🛠 前几轮修 |
| `GET /api/answer/export/{surveyId}` | 导出 Excel | 🛠 前几轮修 |
| `GET /api/answer/statistics/survey/{surveyId}` | 整份问卷统计（一次取回：题目 + 每题分布/文本 + 答卷数） | ✅ 第二十四轮新增；`requireSurveyOwnership` 与上面几条同一口径（admin 放行） |
| `GET /api/answer/quality/{surveyId}` | 答卷质控摘要（可疑份数 / 原因分布 / 最近 50 条样本） | ✅ 第二十六轮新增；`requireSurveyOwnership` 同一口径。★ 只打标、不删除 |
| `PUT /api/answer/checkin/{answerId}` | 签到 / 取消签到（`?checkedIn=true|false`）。★ `requireSurveyOwnership`（admin 放行）；问卷未启用签到（`checkin_enabled=1`）→ 400；只改签到状态与时间，不动答卷内容与分数 | ✅ 第四十五轮新增 |
| `GET /api/survey/version/list/{surveyId}` | 版本列表（新的在前，**不含**题目快照 JSON） | ✅ 第二十五轮新增；`OwnershipGuard.requireOwnerOrAdmin`（admin 放行） |
| `GET /api/survey/version/{versionId}` | 单个版本详情（**含**题目快照 JSON） | ✅ 同上（先按版本反查 surveyId，再校归属） |
| `GET /api/survey/version/diff/{fromId}/{toId}` | 两个版本的差异 | ✅ 同上；service 还会再校验"两个版本属于同一份问卷" |
| `POST /api/survey/version/rollback/{versionId}` | 回滚到某个版本（生成新版本） | ✅ 同上；回滚走"全量覆盖式保存"，所以额外 `requireNotInRecycleBin` |
| `GET /api/survey/{surveyId}/collaborators` | **第三十四轮新增**：协作者名单（作者/管理员） | ✅ `SurveyAccessService.requirePermission(…, SURVEY_MANAGE_COLLAB, …)`，协作者本人看不到名单以外的权限 |
| `POST /api/survey/{surveyId}/collaborators` | **第三十四轮新增**：按用户名邀请协作者（viewer/editor/analyst）。★ 用户名不存在 / 已是协作者 / 邀请自己 → 400；邀请成功给对方发站内通知 | ✅ 同上；被邀请人**不会**因此获得 `t_user.role` 的任何变化 |
| `PUT /api/survey/{surveyId}/collaborators/{targetUserId}` | **第三十四轮新增**：改协作者角色。★ 只改 `t_survey_collaborator.role`，与系统角色 `t_user.role` 是两套东西 | ✅ 同上 |
| `DELETE /api/survey/{surveyId}/collaborators/{targetUserId}` | **第三十四轮新增**：移除协作者（作者/管理员）。★ 与下面「本人退出」是两条权限不同的路径 | ✅ 同上 |
| `POST /api/survey/{surveyId}/collaborators/{targetUserId}/revoke` | 撤销未接受的邀请（作者/管理员）：`PENDING → REVOKED`。★ 只改状态、不删行；撤销后对方再点接受一律 400 | ✅ |
| `GET /api/feedback/{id}` | 用户侧工单详情（`afterId` 增量）。★ 归属按 `feedback.user_id` 校验；不存在与不属于自己用同一句文案，不暴露别人的工单 id | ✅ |
| `POST /api/feedback/{id}/messages` | 追加工单消息（`clientMsgId` 幂等）。★ 归属校验 + 关闭后 400；内容 ≤2000 字 | ✅ |
| `POST /api/feedback/{id}/read` | 标记用户已读水位（只进不退，`GREATEST`） | ✅ |
| `POST /api/feedback/{id}/close` | 关闭自己的工单（只关闭、不删除） | ✅ |

**这几处原来是这么越权的**：`AnswerController` 的这 7 个端点方法签名里**只有 id、没有当前用户**，结构上就没法校验 —— 所以前几轮给它们加了 `requireSurveyOwnership()` / 反查 surveyId 的逻辑（admin 放行，避免弄坏管理员的系统统计页）。

---

## 六、需要登录（**42 个**，其中 2 条限管理端人员）

> ★ 第二十轮·续（第二轮）：`PUT /api/user/profile-public` **已删除**（需求撤销）⇒ 本档 **22 → 21**。
> ★ 第二十轮·续：21 → **22**（新增 `DELETE /api/user/account`）。
> ★ 第二十轮：19 → **21**（新增 `GET /api/user/login-logs` 与 `PUT /api/user/profile-public`）。
> ★ 第十九轮：表头原写 20，表体活行 19（另有 1 行带删除线的 `/survey/recycle` 历史记录，不计入）。
> ★ 第三十四/三十五轮：新增 6 条（`/collaborator/shared`、协作者申请与退出、举报、我的举报、作者申请恢复）⇒ 21 → **27**。

| 端点 | 说明 | 状态 |
|---|---|---|
| `POST /api/user/logout` | 登出（清 `doc_token` Cookie） | ✅ |
| `GET /api/survey/system-overview` | 系统总览。★ **管理端人员**（admin / subadmin）均可访问，方法内判 `Role.isStaff`；普通用户 403。★ 字段按模块权限裁剪：`user:manage` → users、`survey:manage` → 问卷/答卷/访客/回收站/今日、`template:manage` → templates、`review:handle` → pendingReviews、`wallet:manage` → 钱包、`log:view` → recentActivities；`system:view` 只解锁统计页资源模块，不再给业务字段。没有任何模块权限时返回空对象，前端显示「联系超管授权」 | ✅ |
| `GET /api/system/statistics` | 系统统计（按模块）。★ **管理端人员**（admin / subadmin）均可访问，方法内判 `Role.isStaff`；普通用户 403。★ 模块字段按权限裁剪：`survey:manage` → survey、`user:manage` → user、`template:manage` → template、`review:handle` → review、`wallet:manage` → wallet、`log:view` → log；`system:view` → 只解锁 resource（数据库/表行数/版本快照占用）。没权限的模块不查库也不返回 | ✅ |
| `POST /api/feedback` | 提交反馈 / 发起客服工单。★ 所有登录用户可用；同一用户最多一个 OPEN SERVICE（生成列唯一索引兜底，已有则续开同一条）。★ 用户侧不挂权限点，权限边界是 `feedback.user_id == 当前用户` | ✅ |
| `GET /api/feedback/my` | 我的工单列表（最多 100 条；未读由 SQL 按已读水位算出） | ✅ |
| `GET /api/feedback/unread-count` | 我的未读工单数；当前用户有 `feedback:handle` 时附带管理端未读数（菜单红点） | ✅ |
| `GET /api/collaborator/invitations` | 我的待接受邀请。★ 只按 `X-User-Id` 查，不接受 userId 参数；带问卷标题与作者昵称 | ✅ |
| `POST /api/collaborator/invitations/{id}/accept` | 接受邀请（`PENDING → ACCEPTED`）。★ 归属按 `invitation.user_id` 校验；名额费在这一步扣，余额不足则保持 PENDING | ✅ |
| `POST /api/collaborator/invitations/{id}/reject` | 拒绝邀请（`PENDING → REJECTED`）。★ 只作用于自己的邀请；不占名额、不扣费 | ✅ |
| `GET /api/user/profile` | 我的资料（★ 会带出 `deletedAt`/`tokenValidAfter` 两个第二十轮新增字段） | ✅ |
| `DELETE /api/user/account` | ✅ **第二十轮·续新增**：注销**自己**的账号（需求 三.5，**软删除**）。★ 必须带密码确认；★ 身份只取 `X-User-Id`，没有任何可传的 id（否则就是"能注销别人"的接口）；★ 与管理员那条物理删除**刻意分成两个路径** |
| ~~`PUT /api/user/profile-public`~~ | ❌ **第二十轮·续第二轮已删除**：原「资料公开开关」（需求 三.4）。需求方明确"不需要这个需求了，对应代码也剔除" —— 端点、Service、Mapper、实体字段、前端开关与 api 封装一并删除；库里那一列保留未删 |
| `GET /api/user/login-logs` | ✅ **第二十轮新增**：我的最近登录记录（需求 三.3）。★ 身份只取 `X-User-Id`，**不接受 userId/账号名参数**（没有查别人登录记录的口子）；返回条数上限 10 写死在 `LoginLogService`，不受请求参数影响 |
| `PUT /api/user/nickname` | 改昵称 | ✅ |
| `PUT /api/user/password` | 改密码（`oldPassword`/`newPassword`） | ✅（前几轮换成 PBKDF2） |
| `PUT /api/user/avatar` | 改头像 | ✅（★ 第二十轮加强：不再只看长度，见 §八） |
| `PUT /api/user/contact` | 改手机/邮箱 | ✅ |
| `GET /api/survey/my` | 我的问卷 | ✅ |
| `GET /api/survey/my-statistics` | 我的统计 | ✅ |
| ~~`GET /api/survey/recycle`~~ | 不分页旧接口；**第十六轮已删除**（被 `recycle/page` 覆盖） |
| `GET /api/survey/recycle/page` | 回收站分页 | ✅ |
| `GET /api/survey/templates` | 模板广场列表 | ✅（对所有登录用户开放是合理的） |
| `POST /api/survey/from-template/{templateId}` | 从模板创建 | ✅ |
| `POST /api/answer/submit/{surveyId}` | 登录态提交。★ **「仅限登录用户填写」的问卷只接受这条路径**（`require_login=1`）：`submit-share` 的 userId 恒为 null，会被 400 拒收。它同时是唯一能把答题人身份写进 `t_answer.user_id` 的路径，实名答卷因此受 `uk_survey_user`（一人一份）约束。第十六轮曾按"前端零调用"删除、随即恢复，理由见 `AnswerController.submit` 的 javadoc |
| `GET /api/answer/score/{surveyId}` | 查**自己**在某份问卷的成绩（`{totalScore,earnedScore,passScore,passed,submittedAt}`）。★ 只按 `surveyId + X-User-Id` 定位，不接受 answerId / 不带 userId 参数；匿名答卷（`user_id IS NULL`）查不到 → 400「你还没有提交过这份问卷」；未开判分的问卷 → 400。第四十五轮新增 |
| `GET /api/answer/my` | 我答过的问卷（**分页**：`pageNum`/`pageSize`，默认 1/10、上限 100）。★ 只按 `X-User-Id` 查，不接受 userId 参数；匿名答卷没有身份可回溯、不会出现；已删除/已结束的问卷仍保留记录（前端按 `deleted`/`status` 禁用入口）。第四十五轮新增 |
| `GET /api/answer/my/{answerId}` | 我的某份答卷详情（逐题回放：题目、我的答案、对错、正确答案）。★ 权限按**答卷归属**校验（`answer.userId == 当前用户`），不是问卷归属 —— 答题人不是作者，不能复用 `/answer/detail/{answerId}`；别人的 answerId 一律 400「无权查看该答卷」。★ `correctAnswers` 只在作者开启「提交后显示答案」时返回。第四十五轮新增 |
| `POST /api/ai/generate` | AI 生成问卷 | ✅（正确用 `X-User-Id`，是前几轮修归属时的现成模板） |
| `GET /api/notification/list` | 我的通知 | ✅ |
| `GET /api/notification/unread` | 未读数（顶栏 30 秒轮询） | ✅ |
| `PUT /api/notification/read/{id}` | 标记单条已读 | ✅（service 层带 userId） |
| `PUT /api/notification/read-all` | 全部已读 | ✅ |
| `DELETE /api/notification/{id}` | 删单条 | ✅（service 层带 userId） |
| `DELETE /api/notification/clear` | 清空 | ✅ |
| `GET /api/collaborator/shared` | 「共享给我」的问卷列表。★ 只按 `X-User-Id` 查，没有任何可传的用户 id | ✅ 第三十四轮新增 |
| `POST /api/collaborator/self/{surveyId}/apply` | 协作者主动申请变更权限（给作者发通知，**不**直接改角色） | ✅ 第三十四轮新增；报名角色只接受 viewer/editor/analyst |
| `DELETE /api/collaborator/self/{surveyId}` | 协作者自己退出（只删自己那一行，不能借它移除他人） | ✅ 第三十四轮新增 |
| `POST /api/survey/{surveyId}/report` | 举报问卷（只能举报已发布、不能举报自己、同一人同问卷只能有一条 `PENDING`） | ✅ 第三十四轮新增；理由 1~500 字 |
| `GET /api/review/my` | 我的举报（只按 `X-User-Id` 查，不接受 userId 参数） | ✅ 第三十四轮新增 |
| `POST /api/survey/{id}/request-restore` | 作者申请恢复上架。★ 只把申请变成给**全部有效管理员**（`UserMapper.findAdminIds()`）的通知，`lifecycle` 保持 `SUSPENDED`；只有「作者本人 + 确实被下架」能申请，归属判断在 Service 里 | ✅ 第三十五轮新增 |
| `GET /api/wallet/pricing` | ✅ **第三十七轮新增**：计费项目录（发布/AI/导出/高级设置包/协作者名额的价格与说明）。★ 后端 `WalletFeature` 是价格唯一出处，前端动态渲染、不硬编码 |
| `GET /api/wallet` | 我的钱包余额（没有钱包时自动建 0 余额包）。★ 只取 `X-User-Id`，没有任何查别人的参数 | ✅ 第三十六轮新增 |
| `GET /api/wallet/transactions` | 我的额度流水（分页）。金额**有符号**：入账正、出账负 | ✅ 第三十六轮新增 |
| `POST /api/wallet/recharge` | 模拟充值（演示用，不接真实支付；1~1000 点，必须带幂等键，重复提交只到账一次） | ✅ 第三十六轮新增 |

---

## 七、被审计确认「写得对」的地方（答辩可讲）

- `SurveyController.update` 在校验通过后还执行 `survey.setUserId(old.getUserId())` —— **主动阻止改归属**，这个细节很多人会漏。
- `publish` / `copy` / `recycle` / `restore` / `permanent` / `question/save` **六个端点的硬校验都齐**，注释里还标了「★ 硬校验」。
- `NotificationController` 的已读/删除**在 service 层也带 userId**（`markReadByUser` / `deleteByUser`），不只是 Controller 层过一下 —— 双保险，防的是"拿别人的通知 id 来标记"。
- `ErrorLogController` / `OperationLogController` 全部端点都限了管理员。
- `JwtAuthFilter` 的「只拦 `/api/`」+ 文档路径交给专门的 `DocAccessFilter`，职责分离干净。

## 八、审计方法（可复用）

### 0. ★ 先核"总数"（一条命令）

改完端点后**必须**复核总数 —— 只改明细、不核总数，就会出现“明细对、合计错”或相反的情况。

```powershell
# 按注解计数（方法级映射数），与下面四张明细表的活行数比对
cd C:\Users\1\Desktop\dsh\IdeaProjects\survey-backend
node -e "const fs=require('fs'),p=require('path');const d='src/main/java/com/survey/controller';let t=0;for(const f of fs.readdirSync(d).filter(x=>x.endsWith('.java'))){const s=fs.readFileSync(p.join(d,f),'utf8');const n=(s.match(/@(Get|Post|Put|Delete|Patch)Mapping/g)||[]).length;t+=n;console.log(f.padEnd(32)+n)}console.log('合计 = '+t)"
```

**判定规则**：注解计数 = 四表活行之和 = 标题数字 = 汇总合计，**四处必须一致**；
对不上就说明有一处是编的。

### 0.5 ★ 复核"分档"（管理员 / 需登录），别只核总数

**总数对不代表分档对** —— 分档错过一次，合计仍可能吻合（例如 22+19 与 21+20 都等于 41）。
数"`@RequireAdmin` 覆盖了几个端点"时，**先确认注解与方法的相对位置**：
本项目写在 **`@XxxMapping` 之后、方法签名之前**（例：`SurveyController.java:155-156`）。
所以窗口必须是"本行映射 → 本方法的 `public` 签名（遇 public 即停）"，
**既不能只往上找**（找不到），**也不能固定往后 N 行**（会跨到下一个方法，把邻居算进来）。

```powershell
# 管理员档逐条列出（判定窗口 = 映射 → 本方法 public 签名）
cd C:\Users\1\Desktop\dsh
node -e "const fs=require('fs'),p=require('path');const d='IdeaProjects/survey-backend/src/main/java/com/survey/controller';let t=0;for(const f of fs.readdirSync(d).filter(x=>x.endsWith('.java'))){const L=fs.readFileSync(p.join(d,f),'utf8').replace(/\r\n/g,'\n').split('\n');const cl=L.findIndex(x=>/public\s+class\s+/.test(x));const ca=cl>0&&/@RequireAdmin/.test(L.slice(0,cl).join('\n'));for(let i=0;i<L.length;i++){const m=L[i].match(/@(Get|Post|Put|Delete|Patch)Mapping/);if(!m)continue;let ma=false;for(let k=i;k<Math.min(L.length,i+20);k++){if(/@RequireAdmin/.test(L[k]))ma=true;if(/^\s*(public|protected)\s/.test(L[k]))break}if(ca||ma){t++;console.log('  '+f+':'+(i+1))}}}console.log('管理员档 = '+t)"
```

### 1. 一次性提取所有端点签名

**一次性提取所有端点签名**（含方法参数，才能看出「取没取用户身份」）——这条 PowerShell 很有用，新增 Controller 后重跑一遍即可：

```powershell
$dir='C:\Users\1\Desktop\dsh\IdeaProjects\survey-backend\src\main\java\com\survey\controller'
foreach($f in (Get-ChildItem $dir -Filter *.java | Sort-Object Name)){
  $lines=[System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
  $out=@()
  for($i=0;$i -lt $lines.Count;$i++){
    if($lines[$i] -match '@(Get|Post|Put|Delete)Mapping'){
      $buf=$lines[$i].Trim()
      for($j=$i+1;$j -lt [Math]::Min($i+6,$lines.Count);$j++){
        $buf += ' ' + $lines[$j].Trim()
        if($lines[$j] -match '\{\s*$'){ break }
      }
      $out += ('  L' + ($i+1) + '  ' + ($buf -replace '\s+',' '))
    }
  }
  Write-Output ('=== ' + $f.Name + '  (' + $lines.Count + ' 行, ' + $out.Count + ' 个端点) ===')
  $out | ForEach-Object { Write-Output $_ }
}
```

**判读规则**：
1. 签名里**没有** `@RequestHeader("X-User-Id")` 又没有 `@RequestHeader("X-User-Role")` → **高危**，要么它是纯公开接口，要么就是漏了校验（本轮 4 处里有 3 处是这样被找出来的）。
2. 有 `X-User-Id` 但方法体里没出现 `equals(userId)` / `requireAdmin` → 要读方法体确认。
3. 有 `X-User-Role` 但没看到 `"admin".equals(role)` → 多半是收了参数没用（`/survey/list` 就是这样）。

---

## 九、审计的第四个维度：**业务前置条件**

**起因**：IDEA 内置 AI 又报出三条，我核实**全部属实**。其中一条暴露了这份审计表的盲区 ——
前四张表我只核了「**身份 / 角色 / 归属**」，**没核「这个操作在业务上允不允许」**。

典型例子：`POST /api/answer/submit/{surveyId}` 当初在第五节（需登录 + 归属校验）里
**只核了身份与归属就通过了**，但它真正的问题是 —— **没校验问卷是否已发布**，
于是任何登录用户遍历 `surveyId` 就能往**草稿**或**已关闭**的问卷里塞答卷
（而旁边并排的 `submit-share` 反而老老实实校验了 `status==1`）。
★ 该端点第十六轮曾按"前端零调用"删掉、**随即恢复并保留至今**（它是 `t_answer.user_id` 的唯一写入路径，见 `HISTORY.md` 的「端点下线」）；
这条教训正是"业务前置条件"那一列的由来。

### 新修的 3 处（都是这类"业务前置条件"缺失）

| # | 文件 | 问题 | 修法 |
|---|---|---|---|
| 1 | `AnswerController.submit()` | **没校验问卷是否已发布** → 可向草稿/已关闭问卷提交答卷 | 对齐 `submitByShareCode`：先 `getById`，`status != 1` 就返回「问卷未发布」 |
| 2 | `SurveyController.update()` | **允许客户端篡改 `shareCode`**。原来只在 `shareCode == null` 时才沿用旧值，传了就会被采纳 → 可改成别人已有的码（撞唯一索引）或抢注 | 改为**无条件** `survey.setShareCode(old.getShareCode())`，分享码由服务端独占管理 |
| 3 | `WebMvcConfig.corsFilter()` | **CORS 过宽**：`addAllowedOriginPattern("*")` + `setAllowCredentials(true)` → 任何网站都能带凭证跨域调接口（CSRF 风险） | 改成白名单 `localhost/127.0.0.1` 的 `5173`（Vite dev）与 `8080`；生产是同源单包部署，本就不需要 CORS |

**实测验证（临时实例 8081）**：

| 用例 | 结果 |
|---|---|
| 向【草稿】问卷提交答卷 | ✅ 返回「问卷未发布」，且**答卷数仍为 0**（原来会塞进一条空答卷） |
| 向【已发布】问卷提交答卷 | ✅ 「提交成功」（确认没弄坏正常路径） |
| `update` 时提交 `shareCode=hacked1` | ✅ 库里 `share_code` 仍是服务端生成的 `ir16dy`，未被篡改 |
| 请求带 `Origin: http://evil.com` | ✅ **HTTP 403 被拒** |
| 请求带 `Origin: http://localhost:5173` | ✅ HTTP 200 且返回 `Access-Control-Allow-Origin: http://localhost:5173` |

**结论：以后审计端点要核四列** —— ① 要不要登录 ② 要不要限管理员 ③ 要不要校归属 ④ **业务前置条件是否成立**（问卷是否已发布、是否在回收站、是否已结束……）。

---

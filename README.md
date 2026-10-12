<div align="center">

# 📊 问卷调查系统

**基于 Spring Boot 4 + Vue 3 的前后端分离问卷调查系统**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.8-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D.svg)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1.svg)](https://www.mysql.com/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

一个功能完整的问卷调查平台：问卷设计与逻辑跳转、匿名/登录双通道填写、数据统计与 Excel 导出、
AI 生成问卷、账号与治理后台。

[功能特性](#-功能特性) · [技术栈](#-技术栈) · [系统架构](#-系统架构) · [快速开始](#-快速开始) · [项目结构](#-项目结构) · [接口文档](#-接口文档) · [界面截图](#-界面截图)

</div>

---

## 📊 项目规模

| 维度 | 数量 |
|---|---|
| 后端主源文件 | **84** 个 Java 文件 |
| REST 端点 | **67** 个方法级端点（公开 11 / 需登录 21 / 需登录+归属 15 / 管理员 20） |
| 数据库 | **12** 张表（MySQL 8，库名 `survey_db`） |
| 纯单元测试 | **11** 个测试类 / **99** 个用例 |
| 前端 | `views/` **17** 个页面 · `api/` **12** 个模块 · **18** 条具名路由 |

---

## ✨ 功能特性

### 📋 问卷管理
- ✅ 问卷增删改查、一键复制、题目与选项动态增删调整
- ✅ **逻辑跳转**：条件（选项 → 目标题号）存真 `question.id`，删题或调序都不会错位
- ✅ 三种题型：单选、多选、单行填空
- ✅ 状态机：草稿 → 已发布 → 已结束，配合 **定时发布** 与 **自动截止**（Spring `@Scheduled` 定时任务）
- ✅ **回收站**：软删除 → 移入回收站 → 恢复 / 彻底删除
- ✅ **模板广场**：31 个预置模板，7 大分类；可套用模板创建，也可把已有问卷标记为模板

### 🤖 AI 生成
- ✅ 输入一句话描述，由 **Qwen2.5-7B-Instruct**（硅基流动）生成完整问卷（题干 + 选项 + 题型）
- ✅ OkHttp 调用与数据库写入分离：外部 HTTP 调用在事务之外，DB 连接只在落库瞬间占用

### 🔗 发布与分享
- ✅ 一键发布并生成 **6 位分享码**，可复制分享链接、生成二维码
- ✅ 匿名答题页 `/s/:shareCode` 无需登录即可访问

### 📱 填写
- ✅ **匿名凭分享码作答**，**登录态作答**（同一套题目渲染，两条提交入口）
- ✅ 向导式填写，动态进度条
- ✅ **多选题"一选项一行"** 布局，长文案不挤在同一行
- ✅ **登录用户对同一问卷只能提交一次**：应用层查重 + `t_answer` 唯一索引 `uk_survey_user` 兜底
- ✅ 匿名侧有意不做防重（避免误伤同一出口 IP 的 NAT 用户），改用 IP 限流控制刷量

### 📊 数据统计
- ✅ 单题分布（饼图 / 柱状图）、填空题文本、答卷列表与详情
- ✅ **Excel 导出**（EasyExcel，中文文件名按 RFC 5987 编码，浏览器下载不乱码）
- ✅ 系统看板（管理员）、个人统计（问卷数 / 答卷数概览）

### 👤 账号
- ✅ 注册 / 登录：**滑块验证码**（注册侧强制）、**双 token（access 12h + refresh 7d）**、「记住我」
- ✅ **401 自动静默续期并重放**：多请求并发过期只续期一次，续期失败才清登录态跳登录页
- ✅ 个人中心：**账号与昵称概念分离**、昵称 / 手机 / 邮箱修改、**自研头像裁剪（固定输出 320×320 PNG）** + 后端文件校验（格式 + 解码后 ≤ 2MB）
- ✅ 改密码、**登录日志与"最近登录"**、**账号注销（软删除，可被管理员恢复）**

### 🛡️ 治理
- ✅ 系统公告、站内通知、操作日志（AOP 自动记录）、异常日志（默认保留 90 天）
- ✅ 用户管理：**在用 / 已注销两视图**，已注销账号可恢复
- ✅ **演示数据**：一键生成 / 重置，方便演示与联调
- ✅ **限流 9 条规则**（内存固定窗口，命中返回 429）：登录 10/分/IP · AI 生成 1/30s/用户 · 匿名提交 30/分/IP · 登录提交 60/分/用户 · health 60/分/IP · 滑块取图 20/分/IP · 滑块判定 120/分/IP · refresh 30/分/IP · view 30/分/IP

### 🔐 安全
- ✅ **PBKDF2 加盐哈希**存密码，兼容历史 MD5 记录并在登录时自动升级
- ✅ **自研 JWT**（jjwt 0.12.6）+ 4 个 Filter，未引入 Spring Security
- ✅ **RBAC + `@RequireAdmin` 注解 + AOP 切面**，前端路由守卫只做体验层，权限防线全在后端
- ✅ **归属校验**：`OwnershipGuard` 统一判断资源是否属于当前用户
- ✅ **软删除**：每个查用户的 SQL 都带 `deleted_at IS NULL`，已注销账号不留任何后门
- ✅ **`token_valid_after`**：改密码 / 恢复账号即作废此前签发的全部票据
- ✅ 接口文档用独立 `doc_token` 保护（HttpOnly + SameSite=Strict，`scope=doc` + `role=admin` 双校验，有效期 2 小时）
- ✅ 请求体上限 4MB，超限直接 413

---

## 🛠️ 技术栈

### 后端
| 技术 | 版本 | 说明 |
|---|---|---|
| **Spring Boot** | 4.0.8 | 核心框架（`spring-boot-starter-webmvc`） |
| **Java** | 17 | 语言版本 |
| **MyBatis** | 4.0.1 | ORM：**注解式 Mapper，无 XML** |
| **PageHelper** | 2.1.0 | 分页插件 |
| **MySQL** | 8.x | 数据库 `survey_db` |
| **jjwt** | 0.12.6 | 自研 JWT 认证 |
| **Knife4j** | 5.6.1 | 接口文档（OpenAPI 3） |
| **EasyExcel** | 3.3.4 | Excel 导出 |
| **OkHttp** | 4.12.0 | HTTP 客户端（调用 AI） |
| **AspectJ** | — | AOP：操作日志、`@RequireAdmin` 鉴权 |
| **Maven Wrapper** | 3.9.16 | 构建工具（`mvnw` / `mvnw.cmd` 已入库） |

### 前端
| 技术 | 版本 | 说明 |
|---|---|---|
| **Vue** | 3.5 | 核心框架，**全项目 Option API，零 `<script setup>`**、无 Pinia / 无 TypeScript |
| **Vue Router** | 4.6 | 18 条具名路由 + 路由守卫 |
| **Element Plus** | 2.14 | UI 组件库 |
| **ECharts** | 6.1 | 数据可视化 |
| **qrcode** | 1.5 | 二维码生成 |
| **Vite** | 8 | 构建工具（dev 端口 5173，`/api` 代理到 8080） |
| **Node.js** | `^22.18.0` 或 `>=24.12.0` | 运行时要求（`package.json` 的 `engines`） |

---

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        浏览器（用户端）                        │
│        管理员 · 普通用户 · 匿名访客                            │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP / HTTPS
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                 前端 · Vue 3 + Vite (5173)                   │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────┐  │
│  │ 登录注册  │ 问卷管理  │ 问卷填写  │ 数据统计  │ 系统管理  │  │
│  └──────────┴──────────┴──────────┴──────────┴──────────┘  │
│              Vue Router（18 条路由） · Element Plus           │
│         开发期：Vite dev server + /api → 8080 反向代理         │
└──────────────────────┬──────────────────────────────────────┘
                       │ RESTful API（携带 JWT）
                       ▼
┌─────────────────────────────────────────────────────────────┐
│               后端 · Spring Boot 4 (8080)                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Filter 层：JwtAuthFilter · DocAccessFilter           │   │
│  │             RequestSizeFilter · HeaderRewrite         │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  拦截器：RateLimitInterceptor（9 条限流规则）           │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  AOP 层：LogAspect（操作日志）· AdminGuard（@RequireAdmin）│
│  ├──────────────────────────────────────────────────────┤   │
│  │  Controller 层：13 个控制器 / 67 个端点                │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Service 层：业务逻辑 + @Scheduled 定时任务             │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Mapper 层：MyBatis 注解式 Mapper（无 XML）            │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    MySQL 8（survey_db）                      │
│  用户·登录日志·令牌·问卷·题目·选项·答卷·明细·公告·日志·通知     │
└─────────────────────────────────────────────────────────────┘
```

**部署形态**

| 场景 | 形态 |
|---|---|
| 开发 | IDEA 启动后端 **8080** + Vite 启动前端 **5173**，前端 `/api` 反向代理到后端 |
| 生产 | **单包部署**：前端 `npm run build` 产物 `dist` 拷进后端 `static/`，前后端同源，只需一个进程与一个端口 |

**配置分层**

| 文件 | 入库 | 说明 |
|---|---|---|
| `application.yaml` | ✅ | 主配置：端口、数据源占位符、MyBatis、PageHelper、Knife4j、AI、JWT 默认值；`spring.profiles.default=local` |
| `application-local.yml` | ❌ **已 gitignore** | 本地开发配置：数据库口令、JWT 密钥、AI Key 都放这里，**不入库** |
| `application-prod.yml` | ✅ | 生产配置：HikariCP 连接池、`@Async` 线程池、错误页信息脱敏、文件日志滚动 |

---

## 🚀 快速开始

### 环境要求

| 工具 | 版本 |
|---|---|
| JDK | 17+ |
| Node.js | `^22.18.0` 或 `>=24.12.0` |
| MySQL | 8.0+ |
| Maven | 无需安装，用仓库自带的 Maven Wrapper（3.9.16） |

### 1️⃣ 初始化数据库

```sql
CREATE DATABASE survey_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

然后导入建表脚本（位于**仓库根目录的 `sql/`**，不是后端模块下）：

```bash
mysql -u root -p --default-character-set=utf8mb4 survey_db < sql/init.sql
```

脚本一次性建好 **12 张表**。它只含表结构、**不含业务数据**，所以建完库还有两步：

1. **创建管理员**：系统没有管理员种子数据 —— 先在前端注册一个账号，再执行
   ```sql
   UPDATE t_user SET role = 'admin' WHERE username = '你的账号';
   ```
2. **补内置模板**（可选）：模板广场初始为空，用管理员账号调用一次幂等接口补回 31 个模板：
   ```bash
   curl -X POST http://localhost:8080/api/template-seeder/init
   ```
   该接口在 `prod` profile 下不注册，请在本地调用。

### 2️⃣ 配置后端

在 `survey-backend/src/main/resources/` 下新建本地配置文件 `application-local.yml`（该文件已被 `.gitignore` 排除，不会入库），至少覆盖这三项：

| 配置项 | 说明 |
|---|---|
| `spring.datasource.username` / `password` | 本地 MySQL 账号口令 |
| `jwt.secret` | JWT 密钥，**至少 32 个字符**；缺失时后端启动即失败（有意为之） |
| `ai.qwen.api-key` | 硅基流动 API Key，不用 AI 功能可留空 |

主配置 `application.yaml` 已设 `spring.profiles.default=local`，所以在 IDEA 里直接 Run 即可生效；
生产部署请显式加 `--spring.profiles.active=prod`，否则会读本地配置。

### 3️⃣ 启动后端

```powershell
cd survey-backend
.\mvnw.cmd -o clean compile
.\mvnw.cmd spring-boot:run
```

后端启动在 **`http://localhost:8080`**。

### 4️⃣ 启动前端

```bash
cd survey-frontend
npm install
npm run dev
```

前端启动在 **`http://localhost:5173`**（`/api` 请求由 Vite 代理到 8080）。

### 5️⃣ 登录系统

- **管理员**：由上方"初始化数据库"的 `UPDATE t_user SET role='admin'` 指定，生产环境请立即改密码
- **普通用户**：在注册页自行注册（注册需通过滑块验证码）

---

## 🐳 Docker 一键启动（演示 / 答辩推荐）

数据库、后端、前端一起起；前端 `dist` 已打进 jar，所以**只有一个应用容器**：

```bash
copy .env.example .env      # Git Bash: cp .env.example .env
# 编辑 .env：DB_PASSWORD、JWT_SECRET（≥32 字符）、QWEN_API_KEY（可留空）
docker compose up --build
```

首次构建要几分钟（下载 Node 与 Maven 依赖并完整编译一次），之后 `docker compose up` 秒起。

| 项 | 说明 |
|---|---|
| 应用地址 | <http://localhost:8080> —— 前端与接口同源，不需要再跑 Vite |
| 数据库 | 容器内 3306，宿主机映射 **3307**（避开本机已装的 MySQL） |
| 建表 | 首次启动自动执行 `sql/init.sql`（12 张表 + 唯一索引） |
| 数据持久化 | 存在 `survey-mysql-data` 卷里：`docker compose down` 不丢，`down -v` 才清空 |
| 建管理员 | 先注册账号，再 `UPDATE t_user SET role='admin' WHERE username='你的账号';` |
| 改了代码 | 重新 `docker compose up --build` |

> 只想要"一个进程"的本地形态也可以不用 Docker：把 `npm run build` 的 `dist/*` 拷进
> `survey-backend/src/main/resources/static/`，然后只跑后端 `8080`。

## 📁 项目结构

```
IdeaProjects/
├── README.md                          # 本文件
├── sql/                               # 数据库脚本
│   └── init.sql                       # 建库建表（12 张表，仅结构）
├── docs/                              # 随仓库入库的文档（见下方"文档索引"）
│   ├── README.md
│   ├── 问卷系统-端点权限审计表.md
│   └── ...
│
├── survey-backend/                    # 后端服务
│   ├── mvnw / mvnw.cmd                # Maven Wrapper 3.9.16
│   ├── src/main/java/com/survey/
│   │   ├── annotation/                # 自定义注解（@OperationLog、@RequireAdmin）
│   │   ├── aspect/                    # AOP 切面（操作日志、管理员鉴权）
│   │   ├── common/                    # 通用类（PageResult、OwnershipGuard、枚举、校验器）
│   │   ├── config/                    # 配置类（Knife4j、CORS、限流、全局异常、SPA 转发）
│   │   ├── controller/                # 控制器（13 个）
│   │   ├── dto/                       # 请求 DTO（@Valid 校验）
│   │   ├── entity/                    # 实体类（12 个）
│   │   ├── filter/                    # 过滤器（JWT、文档保护、请求体上限）
│   │   ├── mapper/                    # 数据访问层（注解式 Mapper，12 个）
│   │   ├── service/                   # 业务逻辑层（15 个）
│   │   └── util/                      # 工具类（JwtUtil、WebUtils）
│   ├── src/main/resources/
│   │   ├── application.yaml           # 主配置
│   │   ├── application-local.yml      # 本地配置（已 gitignore，不入库）
│   │   └── application-prod.yml       # 生产配置
│   ├── src/test/java/com/survey/      # 纯单元测试（11 个测试类 / 99 个用例）
│   └── pom.xml
│
└── survey-frontend/                   # 前端应用
    ├── src/
    │   ├── api/                       # 接口模块（12 个，http.js 统一封装 401 续期）
    │   ├── components/                # 公共组件（头像裁剪等）
    │   ├── layouts/                   # 布局组件
    │   ├── router/                    # 路由配置（18 条具名路由 + 守卫）
    │   ├── styles/                    # 全局样式
    │   ├── utils/                     # 工具（登录态读写、请求工具）
    │   ├── views/                     # 页面（17 个）
    │   ├── App.vue
    │   └── main.js
    ├── public/
    ├── package.json
    └── vite.config.js
```

---

## 📚 接口文档

启动后端后访问 Knife4j（OpenAPI 3）文档：

- **开发环境**：<http://localhost:8080/doc.html>
- 文档路径由 `DocAccessFilter` 保护：仅 **管理员** 可访问，后端在管理员登录时下发独立的 `doc_token` Cookie
  （HttpOnly + SameSite=Strict，`scope=doc` + `role=admin` 双校验，有效期 2 小时）；非管理员访问返回 403
- **生产环境**：默认关闭

### 核心接口

| 模块 | 接口 | 说明 |
|---|---|---|
| **认证** | `POST /api/user/register` | 注册（需滑块验证码凭证） |
| **认证** | `POST /api/user/login` | 登录，返回 access token（勾选"记住我"再返回 refresh token） |
| **认证** | `POST /api/user/refresh` | 用 refresh token 换取新的 access token（一次性轮换） |
| **问卷** | `GET /api/survey/my` | 我的问卷列表 |
| **问卷** | `POST /api/survey/publish/{id}` | 发布问卷并生成分享码 |
| **问卷** | `GET /api/survey/templates` | 模板广场列表 |
| **填写** | `GET /api/survey/share/{shareCode}` | 通过分享码查询问卷（匿名可访问） |
| **提交** | `POST /api/answer/submit-share/{shareCode}` | 匿名提交答卷 |
| **提交** | `POST /api/answer/submit/{surveyId}` | 登录态提交答卷（同一用户同一问卷仅一次） |
| **统计** | `GET /api/answer/statistics/{questionId}` | 单题选项分布统计 |
| **统计** | `GET /api/answer/texts/{questionId}` | 填空题文本列表 |
| **导出** | `GET /api/answer/export/{surveyId}` | 导出 Excel（中文文件名） |
| **AI** | `POST /api/ai/generate` | AI 生成问卷 |

完整接口请查看 Knife4j 文档，或查阅 [`docs/问卷系统-端点权限审计表.md`](docs/问卷系统-端点权限审计表.md)
（**67** 个方法级端点的权限分档与业务前置条件的权威口径）。

---

## 🎯 技术亮点

### 1. 逻辑跳转（有向无环图）
- 跳转条件存**真实的 `question.id`**（不是题号下标），删题、调序后依然指向同一道题
- 单选 / 多选条件跳转，OR 逻辑 + 顺序优先
- 前端按跳转条件动态计算作答路径，避免死循环

### 2. 双 token + 401 静默续期
- `access token` 12 小时（JWT，无状态）+ `refresh token` 7 天（入库、可吊销、一次性轮换）
- 前端 `api/http.js` 收到 401 时自动调 `/api/user/refresh` 换票并**重放**原请求，用户无感
- 并发过期只发一次续期请求，避免"一次性轮换"互相踩踏
- 改密码 / 恢复账号时写入 `token_valid_after`，一次性作废此前签发的全部票据

### 3. 软删除与账号可恢复
- 问卷回收站用 `t_survey.deleted` 标记（并记录 `deleted_by`，页面上能看到"谁删的"），**彻底删除**才级联清理题目 / 选项 / 答卷 / 明细
- 用户注销用 `t_user.deleted_at` 时间戳标记，每个查用户的 SQL 都带 `deleted_at IS NULL`，注销账号在鉴权链路里等价于不存在
- 管理员可在"已注销"视图中恢复账号，恢复后旧票据依旧无效（令牌生效起点被推后）

### 4. 多层鉴权
- JWT 过滤器（认证）→ `@RequireAdmin` + AOP（角色）→ `OwnershipGuard`（归属）→ MyBatis 查询条件（软删除）
- 前端路由守卫只负责不显示越权菜单，**真正的权限防线全在后端**

### 5. 内存限流 9 条规则
- `RateLimitInterceptor` 固定窗口计数，按 IP 或按用户维度区分
- 公开接口（登录、AI、匿名提交、滑块、health、view）逐一配额，命中直接 429
- 登录接口按**真实连接地址**计数，不用可伪造的 `X-Forwarded-For`

### 6. Spring AOP 操作日志
- 自定义 `@OperationLog` 注解 + 切面自动落库，异步写入不阻塞主业务
- 全局异常处理统一响应格式，异常异步写入 `t_error_log`，默认保留 90 天，前端页面可直接查看堆栈

### 7. 定时任务
- `@Scheduled` 每分钟扫描：到点自动发布草稿问卷、超期自动结束已发布问卷

---

## 📸 界面截图

### 登录页
![登录页](docs/screenshots/login.PNG)

账号 + 密码登录，配合"记住我"下发 7 天 refresh token；登录失败只给统一文案（不区分账号不存在与密码错误），避免账号探测。

### 注册页（含滑块验证码）
![注册页](docs/screenshots/register.PNG)

注册需填写账号、密码、昵称与手机号，并**先通过滑块验证码**才能提交；账号限定 4~20 位字母数字，密码 6~20 位。滑块由后端出题与判定（凭证 60 秒过期、一次性使用），注册页因此不需要登录态。

### 管理员看板
![管理员看板](docs/screenshots/admin-dashboard.PNG)

系统总览：用户数、问卷数、答卷量等关键指标一屏汇总，是进入治理后台（用户管理、公告、操作日志、异常日志、限流状态）的入口。

### 问卷编辑器（含逻辑跳转）
![问卷编辑器](docs/screenshots/survey-edit.PNG)

题目与选项动态增删调整，支持单选 / 多选 / 单行填空三种题型；每道题可配置**逻辑跳转**——某选项跳到指定题号，条件存真实 `question.id`，删题或调序都不会错位。

### 数据统计
![数据统计](docs/screenshots/statistics.PNG)

单题分布用饼图 / 柱状图呈现，填空题以文本列表展示原文，另有答卷列表与详情；数据可一键导出 Excel（中文文件名按 RFC 5987 编码，下载不乱码）。

### AI 生成问卷
![AI生成](docs/screenshots/ai-generate.PNG)

输入一句话描述，由 Qwen2.5-7B-Instruct（硅基流动）生成完整问卷，题干、选项、题型一并给出，生成后可直接进入编辑器继续调整；接口按"1 次/30 秒/用户"限流，防止真实计费的调用被脚本刷爆。

### 接口文档
![接口文档](docs/screenshots/api-doc.PNG)

Knife4j（OpenAPI 3）在线文档，覆盖全部 67 个方法级端点；文档路径由独立 `doc_token` Cookie 保护（HttpOnly + SameSite=Strict，`scope=doc` 与 `role=admin` 双校验），非管理员访问返回 403。

---

## 📝 开发说明

### 📖 文档索引

| 文档 | 说明 |
|---|---|
| [`docs/README.md`](docs/README.md) | 文档目录索引与同步规则 |
| [`docs/问卷系统-端点权限审计表.md`](docs/问卷系统-端点权限审计表.md) | ★ **端点与权限的权威口径**：67 个方法级端点的权限分档、业务前置条件、可复现的审计命令 |

### 数据库表结构

| 表名 | 说明 |
|---|---|
| `t_user` | 用户表（含 `deleted_at` 软删除、`token_valid_after` 令牌生效起点） |
| `t_login_log` | 登录日志表 |
| `t_refresh_token` | 刷新令牌表（一次性轮换，`uk_token_hash` 唯一索引） |
| `t_survey` | 问卷表（含分享码、发布/截止时间、访问量；`deleted` + `deleted_by` 标记回收站） |
| `t_question` | 题目表 |
| `t_option` | 选项表 |
| `t_answer` | 答卷表（`uk_survey_user` 唯一索引兜底防重） |
| `t_answer_detail` | 答题明细表 |
| `t_announcement` | 系统公告表 |
| `t_notification` | 站内通知表 |
| `t_operation_log` | 操作日志表 |
| `t_error_log` | 异常日志表（默认保留 90 天，可用 `log.retention-days` 覆盖） |

### 关键配置

| 配置项 | 位置 | 说明 |
|---|---|---|
| 数据库账号口令 | `application-local.yml` | 本地配置，不入库 |
| JWT 密钥（`jwt.secret`） | `application-local.yml` | 至少 32 字符，生产环境务必更换；缺失时启动失败 |
| Qwen API Key（`ai.qwen.api-key`） | `application-local.yml` | 硅基流动申请，不用 AI 可留空 |
| 连接池 / 异步线程池 / 错误页脱敏 | `application-prod.yml` | 生产专用 |
| 异常日志保留天数 | `log.retention-days` | 默认 90 |

### 单元测试

```powershell
cd survey-backend
.\mvnw.cmd -o test
```

覆盖 11 个测试类、99 个用例，全部为**纯单元测试**（不依赖数据库）；
唯一需要真实 MySQL 的 `@SpringBootTest` 已在 Surefire 中排除，需要时可单独点名运行：

```powershell
.\mvnw.cmd -o "-Dtest=SurveyBackendApplicationTests" test
```

---

## 🤝 贡献指南

欢迎提 Issue 和 PR！

1. Fork 本项目
2. 新建分支 `git checkout -b feature/xxx`
3. 提交更改 `git commit -m "feat: xxx"`
4. 推送 `git push origin feature/xxx`
5. 提交 Pull Request

---

## 📄 License

[MIT](LICENSE) © 2026

---

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue.js](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [Knife4j](https://doc.xiaominfo.com/)
- [SiliconFlow](https://siliconflow.cn/)

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star ⭐**

</div>

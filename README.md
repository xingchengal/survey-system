<div align="center">

# 📊 问卷调查系统

**基于 Spring Boot 4 + Vue 3 的前后端分离问卷调查系统**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.8-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.x-4FC08D.svg)](https://vuejs.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1.svg)](https://www.mysql.com/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

一个功能完整的问卷调查平台，支持问卷创建、逻辑跳转、匿名填写、数据统计、AI 生成等功能。

[功能特性](#-功能特性) · [技术栈](#-技术栈) · [快速开始](#-快速开始) · [项目结构](#-项目结构) · [接口文档](#-接口文档)

</div>

---

## ✨ 功能特性

### 📋 问卷管理
- ✅ 问卷创建、编辑、发布、复制、删除
- ✅ **逻辑跳转**：单选/多选条件跳转（OR 逻辑 + 顺序优先）
- ✅ **定时发布** / **截止时间**（Spring @Scheduled 定时任务）
- ✅ 三种题型：单选、多选、单行输入
- ✅ 软删除回收站机制

### 🎨 模板与 AI
- ✅ **模板广场**：31 个预置模板，7 大分类
- ✅ **AI 生成问卷**：集成 Qwen2.5-7B-Instruct（硅基流动）

### 📱 填写与分享
- ✅ 匿名填写（无需登录）
- ✅ 分享链接 + 二维码生成
- ✅ 向导式填写，动态进度条
- ✅ 答卷防重复校验

### 📊 数据统计
- ✅ ECharts 可视化（饼图、柱状图、趋势图、仪表盘）
- ✅ 答卷详情查看
- ✅ **Excel 导出**（EasyExcel）

### 🔐 安全与权限
- ✅ **JWT 认证**（无状态、防伪造）
- ✅ **RBAC 权限**：管理员 / 普通用户 / 匿名访客
- ✅ MD5 密码加密
- ✅ 路由守卫 + 接口层双重校验
- ✅ HttpOnly Cookie 保护接口文档

### 🔧 系统能力
- ✅ **操作日志**（Spring AOP 切面自动记录）
- ✅ **异常日志**（全局异常捕获 + 异步写入）
- ✅ **站内通知**（答卷提醒、公告推送）
- ✅ **系统公告**
- ✅ **演示数据**（一键生成 + 重置）
- ✅ **Knife4j 接口文档**（Spring Boot 4 兼容版）

---

## 🛠️ 技术栈

### 后端
| 技术 | 说明 |
|---|---|
| **Spring Boot 4** | 核心框架 |
| **MyBatis** | ORM 框架 |
| **PageHelper** | 分页插件 |
| **MySQL 8** | 数据库 |
| **JJWT** | JWT 认证 |
| **Spring AOP** | 操作日志切面 |
| **Spring @Scheduled** | 定时任务 |
| **EasyExcel** | Excel 导出 |
| **Knife4j** | 接口文档（OpenAPI 3） |
| **OkHttp** | HTTP 客户端（调用 AI） |

### 前端
| 技术 | 说明 |
|---|---|
| **Vue 3** | 核心框架 |
| **Vite** | 构建工具 |
| **Element Plus** | UI 组件库 |
| **Vue Router** | 路由管理 |
| **ECharts** | 数据可视化 |
| **qrcode.js** | 二维码生成 |

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
│                  Vue Router · Element Plus                    │
└──────────────────────┬──────────────────────────────────────┘
                       │ RESTful API（携带 JWT）
                       ▼
┌─────────────────────────────────────────────────────────────┐
│               后端 · Spring Boot 4 (8080)                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Filter 层：JwtAuthFilter · DocAccessFilter · CORS   │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  AOP 层：LogAspect（操作日志）                         │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Controller 层：8 个控制器                             │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Service 层：业务逻辑 + 定时任务                        │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Mapper 层：MyBatis 数据访问                           │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    MySQL 8（survey_db）                      │
│  用户 · 问卷 · 题目 · 选项 · 答卷 · 明细 · 公告 · 日志 · 通知   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 环境要求

| 工具 | 版本 |
|---|---|
| JDK | 17+ |
| Node.js | 16+ |
| MySQL | 8.0+ |
| Maven | 3.6+ |

### 1️⃣ 初始化数据库

```sql
CREATE DATABASE survey_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

执行建表 SQL（详见 `survey-backend/sql/init.sql`，如无则手动创建表）。

### 2️⃣ 配置后端

```bash
cd survey-backend/src/main/resources
cp application.yml application-local.yml
```

编辑 `application-local.yml`，填入你自己的：
- MySQL 密码
- JWT 密钥
- Qwen API Key（可选）

### 3️⃣ 启动后端

```bash
cd survey-backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

后端启动在 **`http://localhost:8080`**

### 4️⃣ 启动前端

```bash
cd survey-frontend
npm install
npm run dev
```

前端启动在 **`http://localhost:5173`**

### 5️⃣ 登录系统

- **管理员**：`admin / 123456`
- **普通用户**：自行注册

---

## 📁 项目结构

```
survey-system/
├── survey-backend/                    # 后端服务
│   ├── src/main/java/com/survey/
│   │   ├── annotation/                # 自定义注解（@OperationLog）
│   │   ├── aspect/                    # AOP 切面（日志）
│   │   ├── common/                    # 通用类（PageResult）
│   │   ├── config/                    # 配置类
│   │   ├── controller/                # 控制器（8 个）
│   │   ├── entity/                    # 实体类（10 个）
│   │   ├── filter/                    # 过滤器（JWT、文档保护）
│   │   ├── mapper/                    # 数据访问层
│   │   ├── service/                   # 业务逻辑层
│   │   └── util/                      # 工具类（JwtUtil）
│   ├── src/main/resources/
│   │   ├── application.yml            # 主配置（占位符）
│   │   ├── application-local.yml      # 本地配置（不上传）
│   │   └── application-prod.yml       # 生产配置
│   └── pom.xml
│
└── survey-frontend/                   # 前端应用
    ├── src/
    │   ├── layouts/                   # 布局组件
    │   ├── router/                    # 路由配置
    │   ├── utils/                     # 工具（请求拦截器）
    │   ├── views/                     # 页面（16 个）
    │   ├── App.vue
    │   └── main.js
    ├── public/
    ├── package.json
    └── vite.config.js
```

---

## 📚 接口文档

启动后端后，访问：

- **开发环境**：http://localhost:8080/doc.html（需管理员登录）
- **生产环境**：默认关闭

---

## 🔑 核心接口

| 模块 | 接口 | 说明 |
|---|---|---|
| **认证** | `POST /api/user/login` | 登录，返回 JWT |
| **问卷** | `GET /api/survey/my` | 我的问卷列表 |
| **填写** | `GET /api/survey/share/{code}` | 通过分享码查询问卷 |
| **提交** | `POST /api/answer/submit-share/{code}` | 匿名提交答卷 |
| **统计** | `GET /api/answer/statistics/{qid}` | 选项分布统计 |
| **导出** | `GET /api/answer/export/{id}` | 导出 Excel |
| **AI** | `POST /api/ai/generate` | AI 生成问卷 |

完整接口请查看 Knife4j 文档。

---

## 🎯 技术亮点

### 1. 逻辑跳转（有向无环图）
- 单选/多选条件跳转
- OR 逻辑 + 顺序优先
- 前端动态计算路径，避免死循环

### 2. Spring AOP 操作日志
- 自定义 `@OperationLog` 注解
- 切面自动记录到数据库
- 异步写入，不阻塞主业务

### 3. JWT 认证 + HttpOnly Cookie
- 无状态认证，防请求头伪造
- 接口文档用 Cookie 保护，非管理员 403

### 4. Spring @Scheduled 定时任务
- 定时发布问卷
- 自动关闭到期问卷
- cron 表达式每分钟执行

### 5. 全局异常处理
- 统一异常响应格式
- 异常异步写入 `t_error_log`
- 支持前端"异常日志"页面查看堆栈

---

## 📸 界面截图

> 📌 把你的截图放到 `docs/screenshots/` 目录，替换下面的占位符

### 登录页
![登录页](docs/screenshots/login.PNG)

### 管理员看板
![管理员看板](docs/screenshots/admin-dashboard.PNG)

### 问卷编辑器（含逻辑跳转）
![问卷编辑器](docs/screenshots/survey-edit.PNG)

### 数据统计
![数据统计](docs/screenshots/statistics.PNG)

### AI 生成问卷
![AI生成](docs/screenshots/ai-generate.PNG)

### 接口文档
![接口文档](docs/screenshots/api-doc.PNG)

---

## 📝 开发说明

### 数据库表结构

| 表名 | 说明 |
|---|---|
| `t_user` | 用户表 |
| `t_survey` | 问卷表 |
| `t_question` | 题目表 |
| `t_option` | 选项表 |
| `t_answer` | 答卷表 |
| `t_answer_detail` | 答题明细表 |
| `t_announcement` | 系统公告表 |
| `t_operation_log` | 操作日志表 |
| `t_error_log` | 异常日志表 |
| `t_notification` | 站内通知表 |

### 关键配置

| 配置项 | 位置 | 说明 |
|---|---|---|
| 数据库密码 | `application-local.yml` | 本地配置，不上传 |
| JWT 密钥 | `application-local.yml` | 生产环境务必更换 |
| Qwen API Key | `application-local.yml` | 硅基流动申请 |

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
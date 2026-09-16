# 问卷调查系统

基于 Spring Boot 4 + Vue 3 的前后端分离问卷调查系统。

## 📁 项目结构

- `survey-backend/` 后端服务（Spring Boot 4 + MyBatis + MySQL）
- `survey-frontend/` 前端应用（Vue 3 + Vite + Element Plus）

## ✨ 功能特性

- 📋 问卷管理（CRUD + 逻辑跳转）
- 📱 匿名填写 + 二维码分享
- 🤖 AI 生成问卷（Qwen2.5-7B）
- 📊 数据统计（ECharts）
- 📤 Excel 导出
- 🔔 站内通知
- 📜 操作日志 + 异常日志
- 🔐 JWT 认证 + RBAC 权限

## 🚀 快速开始

### 后端

```bash
cd survey-backend
mvn spring-boot:run
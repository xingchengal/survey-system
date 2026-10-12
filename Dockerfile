# syntax=docker/dockerfile:1
# ============================================================
# 问卷系统 · 单包镜像
#   前端 dist 打进 jar 的 static/，最终只有一个 JRE 容器跑整个应用。
#   用法：docker compose up --build      （见 docker-compose.yml）
# ============================================================

# ---------- 阶段 1：构建前端 ----------
FROM node:22-alpine AS frontend
WORKDIR /build
COPY survey-frontend/package.json survey-frontend/package-lock.json ./
RUN npm ci
COPY survey-frontend/ ./
RUN npm run build

# ---------- 阶段 2：构建后端（把前端产物放进 static，形成单包） ----------
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY survey-backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY survey-backend/src ./src
# 先清掉构建上下文里可能残留的旧 static，再放入本次前端产物
RUN rm -rf src/main/resources/static
COPY --from=frontend /build/dist ./src/main/resources/static
RUN mvn -B -q clean package -DskipTests

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
# ★ 时区必须是 Asia/Shanghai：token_valid_after 的时间比较依赖"数据库本地时间"口径
ENV TZ=Asia/Shanghai
COPY --from=backend /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

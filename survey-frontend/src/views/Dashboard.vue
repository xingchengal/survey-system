<template>
  <div>
    <!-- 顶部 6 个数据卡片 -->
    <el-row :gutter="16">
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#ecf5ff;color:#409eff;">
            <el-icon :size="26"><UserFilled /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总用户</div>
            <div class="stat-value color-primary">{{ stats.users }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f0f9eb;color:#67c23a;">
            <el-icon :size="26"><Document /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总问卷</div>
            <div class="stat-value color-success">{{ stats.total }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fdf6ec;color:#e6a23c;">
            <el-icon :size="26"><Promotion /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已发布</div>
            <div class="stat-value color-warning">{{ stats.published }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fef0f0;color:#f56c6c;">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总答卷</div>
            <div class="stat-value color-danger">{{ stats.responses }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f4f0ff;color:#8e44ad;">
            <el-icon :size="26"><View /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">访客次数</div>
            <div class="stat-value color-purple">{{ stats.views }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#e8f6ff;color:#12b0e8;">
            <el-icon :size="26"><PieChart /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已结束</div>
            <div class="stat-value color-cyan">{{ stats.ended }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 中间：状态分布图 + 系统信息 -->
    <el-row class="mt-20" :gutter="20">
      <el-col :span="10">
        <el-card style="height: 500px;">
          <template #header>
            <span class="text-bold">📊 问卷状态分布</span>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" style="width: 100%; height: 400px;"></div>
          <el-empty v-else description="暂无问卷数据" :image-size="80" />
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card style="height: 500px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📌 系统信息</span>
              <el-tag :type="healthTagType" size="small">
                <span :style="{
                  display: 'inline-block',
                  width: '6px',
                  height: '6px',
                  borderRadius: '50%',
                  background: healthDotColor,
                  marginRight: '4px',
                  verticalAlign: 'middle'
                }"></span>
                {{ healthText }}
              </el-tag>
            </div>
          </template>

          <!-- 基础信息 -->
          <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
            <el-descriptions-item label="系统名称">问卷调查系统</el-descriptions-item>
            <el-descriptions-item label="系统版本">v2.1.0</el-descriptions-item>
            <el-descriptions-item label="当前用户">{{ currentUser.nickname || currentUser.username }}</el-descriptions-item>
            <el-descriptions-item label="登录时间">{{ loginTime }}</el-descriptions-item>
          </el-descriptions>

          <!-- 技术栈 -->
          <div style="font-weight: bold; font-size: 13px; color: #606266; margin-bottom: 8px;">
            🔧 技术栈
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="前端">
              Vue 3 · Vite · Element Plus · ECharts · qrcode · Vue Router
            </el-descriptions-item>
            <el-descriptions-item label="后端">
              Spring Boot 4 · MyBatis · PageHelper · OkHttp · JJWT
            </el-descriptions-item>
            <el-descriptions-item label="数据库">
              MySQL 8.x（survey_db · 12 张表）
            </el-descriptions-item>
            <el-descriptions-item label="安全机制">
              <!-- 注意：这里是"系统信息"卡片的技术栈展示，改动实现时要同步改这里，
                   否则会出现"代码用 PBKDF2、界面还写 MD5"这类不一致 -->
              JWT 双 token（access 12h + refresh 7d）· RBAC 权限 · PBKDF2 加盐哈希 · HttpOnly + SameSite Cookie · 路由守卫
            </el-descriptions-item>
            <el-descriptions-item label="进阶技术">
              Spring AOP · @Scheduled 定时任务 · @Async 异步处理 · 全局异常 · 内存固定窗口限流
            </el-descriptions-item>
            <el-descriptions-item label="AI 能力">
              Qwen2.5-7B-Instruct（SiliconFlow 硅基流动）
            </el-descriptions-item>
            <el-descriptions-item label="工具库">
              Knife4j（OpenAPI 3）· EasyExcel
            </el-descriptions-item>
          </el-descriptions>

          <!-- 核心功能 -->
          <div style="font-weight: bold; font-size: 13px; color: #606266; margin-top: 12px; margin-bottom: 8px;">
            ✨ 核心功能
          </div>
          <div style="display: flex; flex-wrap: wrap; gap: 6px;">
            <el-tag size="small" effect="plain">问卷管理</el-tag>
            <el-tag size="small" effect="plain">逻辑跳转</el-tag>
            <el-tag size="small" effect="plain">模板广场</el-tag>
            <el-tag size="small" effect="plain">AI 生成</el-tag>
            <el-tag size="small" effect="plain">匿名填写</el-tag>
            <el-tag size="small" effect="plain">二维码分享</el-tag>
            <el-tag size="small" effect="plain">定时发布</el-tag>
            <el-tag size="small" effect="plain">数据可视化</el-tag>
            <el-tag size="small" effect="plain">Excel 导出</el-tag>
            <el-tag size="small" effect="plain">回收站</el-tag>
            <el-tag size="small" effect="plain">站内通知</el-tag>
            <el-tag size="small" effect="plain">操作日志</el-tag>
            <el-tag size="small" effect="plain">异常日志</el-tag>
            <el-tag size="small" effect="plain">系统公告</el-tag>
            <el-tag size="small" effect="plain">演示数据</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 底部：快捷操作 -->
    <el-card class="mt-20">
      <template #header>
        <span class="text-bold">⚡ 快捷操作</span>
      </template>
      <el-row :gutter="16">
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/users')">
            <el-icon :size="32" color="#409eff"><UserFilled /></el-icon>
            <div class="text-subtitle">用户管理</div>
            <div class="text-hint">查看 / 删除用户</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/admin/surveys')">
            <el-icon :size="32" color="#67c23a"><Document /></el-icon>
            <div class="text-subtitle">全部问卷</div>
            <div class="text-hint">查看 / 删除违规</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/statistics')">
            <el-icon :size="32" color="#e6a23c"><PieChart /></el-icon>
            <div class="text-subtitle">系统统计</div>
            <div class="text-hint">图表 / 排行</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/admin/logs')">
            <el-icon :size="32" color="#f56c6c"><Memo /></el-icon>
            <div class="text-subtitle">系统监控</div>
            <div class="text-hint">操作 / 异常日志</div>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script>
// ★ 按需引入 ECharts（只打包用得到的图表）：整库 import 会把全部图表打进产物。
//   注册清单与维护提示见 utils/echarts.js。
import echarts from '../utils/echarts'
// ★ 统一接口层：本页的请求一律走 src/api/（token、401 跳转、错误提示、query 拼串都由它处理）。
//   api.misc.health() 与 api.demo.misc.health() 两种写法都能用：src/api/index.js 把 demo.js 里的
//   `misc` 命名导出提升到了顶层（与 operationLog/errorLog 同款），本项目的约定是扁平写法。
//   （写法约定见 src/api/index.js 顶部"命名约定"那段。）
import api from '../api'
// ★ 失败提示由 src/api/http.js 统一弹出，所以本文件不需要 import ElMessage
//   （不需要的 import 会触发 no-unused-vars 警告）。
//   ⚠️ 判断依据（别只看注释）：模板标签 <Xxx /> 或 <component :is="'Xxx'"> 走全局注册；
//      只有 :icon="Xxx" / :prefix-icon="Xxx" 这类**属性变量**才需要本文件 import + data 里 markRaw 暴露。
import { getUser } from '../utils/auth'

// 图标已在 main.js 全局注册，不再重复注册（View 是 HTML 保留标签名，重复注册会被 lint 拦）
export default {
  data() {
    return {
      // ★ 这里在 data() 里，必须用 utils/auth.getUser()：裸 parse 遇到脏值
      //   会让**组件渲染直接失败**（auth.js 的存在就是为了消灭这类白屏）
      currentUser: getUser() || {},
      loginTime: '',
      stats: {
        users: 0, total: 0, published: 0, draft: 0, ended: 0, responses: 0, views: 0
      },
      pieChart: null,
      serverHealthy: true,
      healthTimer: null
    }
  },
  computed: {
    healthTagType() {
      return this.serverHealthy ? 'success' : 'danger'
    },
    healthDotColor() {
      return this.serverHealthy ? '#67c23a' : '#f56c6c'
    },
    healthText() {
      return this.serverHealthy ? '服务运行正常' : '服务已离线'
    }
  },
  mounted() {
    this.loginTime = this.formatNow()
    this.loadStats()
    this.checkHealth()
    this.healthTimer = setInterval(this.checkHealth, 5000)
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
    // ★ resize 监听必须成对移除（否则每进一次本页就永久多一个监听，
    //   还会对已 dispose 的实例调 resize）。写法与 SystemStatistics.vue 一致。
    window.removeEventListener('resize', this.handleResize)
    if (this.healthTimer) clearInterval(this.healthTimer)
  },
  methods: {
    /**
     * ★ 窗口尺寸变化时让饼图重绘：没有监听的话窗口变化后饼图不跟着变。
     *   写法与 SystemStatistics.vue 的 handleResize 一致。
     */
    handleResize() {
      if (this.pieChart) this.pieChart.resize()
    },

    formatNow() {
      const d = new Date()
      const pad = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },

    // ===== 后端健康检查（顶部「系统信息」卡片的绿点/红点）=====
    // 这段干什么：每 5 秒探一次 /api/health，决定 serverHealthy（进而决定 healthTagType / healthText）。
    // 为什么这么写：
//   ★ api.misc.health() 内部已经带了 silent —— 后端没启动时正是靠它显示
//     「服务已离线」，绝不能因此弹一堆错误提示；
//   ★ 成败标准是 HTTP 状态码：能 await 到（2xx）就是健康，非 2xx / 网络异常会 throw。
    async checkHealth() {
      try {
        await api.misc.health()
        this.serverHealthy = true
      } catch {
        // 探测失败是预期内的状态展示，不是错误：只把圆点变红，不弹提示、不打断页面
        this.serverHealthy = false
      }
    },

    // ===== 顶部 6 个统计卡片 + 饼图 =====
    // 关联：stats 同时喂给模板的卡片和 initPie() 的饼图数据，所以拿不到数据时不画图。
    async loadStats() {
      try {
        this.stats = await api.survey.systemOverview()
        this.$nextTick(() => this.initPie())
      } catch {
        // ★ 失败提示（ElMessage.error）已经在 src/api/http.js 里统一弹过了，这里不再重复弹，
        //   只做流程控制：失败就保持 stats 默认值（模板显示 0 / 空状态），不画饼图。
      }
    },

    initPie() {
      if (this.stats.total === 0 || !this.$refs.pieRef) return
      if (this.pieChart) this.pieChart.dispose()

      this.pieChart = echarts.init(this.$refs.pieRef)

      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: '#67c23a' } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: '#e6a23c' } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: '#f56c6c' } })

      // ★ 只在首次初始化时注册一次监听（initPie 会被重复调用，重复注册会叠加）
      window.removeEventListener('resize', this.handleResize)
      window.addEventListener('resize', this.handleResize)

      this.pieChart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0 },
        series: [
          {
            type: 'pie',
            radius: ['45%', '70%'],
            center: ['50%', '45%'],
            label: { formatter: '{b}\n{c}份' },
            data
          }
        ]
      })
    }
  }
}
</script>

<style scoped>
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}
.stat-content { flex: 1; min-width: 0; }
.stat-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 22px;
  font-weight: bold;
  line-height: 1;
}

.quick-action {
  text-align: center;
  padding: 24px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid #f0f2f5;
}
.quick-action:hover {
  background: #f8fafc;
  border-color: #409eff;
  transform: translateY(-2px);
}
</style>
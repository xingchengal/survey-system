<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">系统总览</h2>
        <p class="page-subtitle">{{ pageSubtitle }}</p>
      </div>
      <div class="head-actions">
        <span class="health-pill" :class="{ offline: !serverHealthy }">
          <span class="health-dot"></span>
          {{ healthText }}
        </span>
        <el-button type="primary" plain @click="$router.push('/statistics')">
          <el-icon class="btn-icon"><PieChart /></el-icon>系统统计
        </el-button>
      </div>
    </div>

    <el-empty v-if="failed" description="总览加载失败，请刷新重试" />
    <el-empty
      v-else-if="loaded && !hasAnyModule"
      :description="canViewStatistics
        ? '还没有被分配业务模块；系统资源可在「系统统计」查看'
        : '还没有被分配任何模块，请联系超级管理员授权'"
    />
    <el-skeleton v-else-if="!loaded" :rows="4" animated />

    <template v-else>
      <!-- 指标卡：只显示后端按模块权限返回的字段，没有权限的模块整张卡不出现 -->
      <el-row :gutter="16">
        <el-col
          v-for="card in statCards"
          :key="card.key"
          :span="6"
          :xs="12"
          :sm="8"
          :md="6"
        >
          <el-card
            shadow="hover"
            class="stat-card"
            :class="{ clickable: !!card.path }"
            @click="openCard(card)"
          >
            <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
              <el-icon :size="24"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-label">{{ card.label }}</div>
              <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
              <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row class="mt-20 equal-height-row" :gutter="20">
        <el-col :span="hasActivities ? 15 : 24" :xs="24" :sm="24" :md="hasActivities ? 15 : 24">
          <el-card class="ops-card">
            <template #header>
              <div class="flex-between">
                <span class="text-bold">🧭 运营待办</span>
                <span class="panel-hint">点击条目直达对应页面</span>
              </div>
            </template>

            <div v-if="todayItems.length" class="ops-summary">
              <div v-for="item in todayItems" :key="item.label" class="ops-summary-item">
                <span class="ops-summary-icon" :class="item.tone">
                  <el-icon :size="18"><component :is="item.icon" /></el-icon>
                </span>
                <div class="ops-summary-text">
                  <span class="ops-summary-label">{{ item.label }}</span>
                  <b>{{ item.value }}</b>
                </div>
              </div>
            </div>

            <div v-if="opsItems.length" class="ops-list">
              <div
                v-for="item in opsItems"
                :key="item.label"
                class="ops-item"
                @click="$router.push(item.path)"
              >
                <span class="ops-icon" :class="item.tone">
                  <el-icon :size="15"><component :is="item.icon" /></el-icon>
                </span>
                <span class="ops-label">{{ item.label }}</span>
                <span class="ops-value" :class="{ warn: item.warn }">{{ item.value }}{{ item.suffix }}</span>
                <el-icon class="ops-arrow"><TopRight /></el-icon>
              </div>
            </div>
            <div v-else class="ops-empty">当前没有需要处理的待办</div>

            <div class="ops-hint" :class="{ warn: opsHintWarn }">
              <span class="ops-hint-icon">{{ opsHintWarn ? '⚠️' : '💡' }}</span>
              <span>{{ opsHint }}</span>
            </div>
          </el-card>
        </el-col>

        <el-col v-if="hasActivities" :span="9" :xs="24" :sm="24" :md="9">
          <el-card>
            <template #header>
              <div class="flex-between">
                <span class="text-bold">🕒 最近动态</span>
                <el-button v-if="canViewLogs" link type="primary" @click="$router.push('/admin/logs')">
                  全部日志 →
                </el-button>
              </div>
            </template>

            <div v-if="activities.length" class="activity-list">
              <div v-for="a in activities" :key="a.id" class="activity-item">
                <div class="activity-avatar">{{ (a.username || '匿').charAt(0) }}</div>
                <div class="activity-body">
                  <div class="activity-main">
                    <b>{{ a.username || '匿名' }}</b>
                    <span class="activity-op">{{ a.operation }}</span>
                  </div>
                  <div class="activity-time">{{ a.createTime }}</div>
                </div>
              </div>
            </div>
            <el-empty v-else description="暂无操作记录" :image-size="60" />
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="quickActions.length" class="mt-20">
        <template #header>
          <div class="flex-between">
            <span class="text-bold">⚡ 快捷操作</span>
            <span class="panel-hint">只显示已授权的模块</span>
          </div>
        </template>
        <el-row :gutter="16">
          <el-col
            v-for="action in quickActions"
            :key="action.path"
            :span="6"
            :xs="12"
            :sm="8"
            :md="6"
          >
            <div class="quick-action" @click="$router.push(action.path)">
              <span class="quick-icon" :style="{ background: action.bg, color: action.color }">
                <el-icon :size="22"><component :is="action.icon" /></el-icon>
              </span>
              <div class="quick-title">{{ action.title }}</div>
              <div class="quick-desc">{{ action.desc }}</div>
            </div>
          </el-col>
        </el-row>
      </el-card>

      <el-card class="mt-20 sys-card" shadow="never">
        <el-collapse v-model="sysInfoActive" class="sys-collapse">
          <el-collapse-item name="info">
            <template #title>
              <span class="text-bold">🧩 技术栈与系统信息</span>
              <span class="panel-hint sys-version">v2.4.0 · 128 端点 · 505 测试（55 类）· 26 张表</span>
            </template>

            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="前端">
                Vue 3.5（全 Options API）· Vite 8 · Element Plus 2.14 · ECharts 6 · qrcode · Vue Router 4 · ESLint 10
              </el-descriptions-item>
              <el-descriptions-item label="后端">
                Spring Boot 4.0.8 · Java 17 · MyBatis 4（注解式，无 XML）· PageHelper 2.1 ·
                OkHttp 4.12 · JJWT 0.12 · Redis（Lettuce）· Spring AOP（@Scheduled / @Async）
              </el-descriptions-item>
              <el-descriptions-item label="数据库">
                MySQL 8.x（survey_db · 26 张表 · utf8mb4）· HikariCP
              </el-descriptions-item>
              <el-descriptions-item label="缓存 / 限流">
                Redis 5：统计预聚合缓存 + 分布式限流 + 熔断降级（不可用时回退进程内计数）
              </el-descriptions-item>
              <el-descriptions-item label="安全机制" :span="2">
                JWT 双 token（access 12h + refresh 7d）· RBAC 权限点 + AOP 切面 · PBKDF2 加盐哈希 ·
                滑块验证码 · token_valid_after 吊销 · HttpOnly + SameSite Cookie · 请求体上限 / CORS 白名单
              </el-descriptions-item>
              <el-descriptions-item label="进阶能力" :span="2">
                Spring AOP · @Scheduled 定时发布/截止/GC · @Async 站内通知 · 版本快照/回滚 · 答卷质控 ·
                矩阵/排序/NPS 统计 · 条件跳转 · 额度钱包 · 作答乱序 / 随机抽题 · 选项配图上传 ·
                考试判分 · 签到核销 / 公开名单 · 反馈 / 客服轮询 · 协作邀请确认
              </el-descriptions-item>
              <el-descriptions-item label="测试 / CI" :span="2">
                JUnit 5 · Mockito · MockMvc 全栈权限矩阵 · Maven Surefire · JaCoCo（行覆盖 67.15%）·
                GitHub Actions 4 个作业（backend / backend-integration / frontend / docs）
              </el-descriptions-item>
              <el-descriptions-item label="AI / 工具" :span="2">
                Qwen2.5-7B-Instruct（SiliconFlow）· Knife4j（OpenAPI 3）· EasyExcel · Docker Compose · Maven Wrapper
              </el-descriptions-item>
            </el-descriptions>

            <div class="feature-tags">
              <el-tag v-for="tag in featureTags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
  </div>
</template>

<script>
// ★ 总览只做「系统主体 + 待办 + 健康 + 最近动态」：分布 / 趋势 / TOP5 归系统统计页。
//   字段由后端按模块权限裁剪，前端只按字段存在性显隐，不做二次口径判断。
import api from '../api'
import { hasPermission, getRole } from '../utils/auth'
// 失败提示由 src/api/http.js 统一弹出，本文件不 import ElMessage

// 图标已在 main.js 全局注册，模板里用 <component :is="'Xxx'" /> 即可
export default {
  data() {
    return {
      stats: {},
      loaded: false,
      failed: false,
      serverHealthy: true,
      healthTimer: null,
      /** 技术栈信息默认收起：总览首屏优先展示待办与动态 */
      sysInfoActive: [],
      /** 项目当前能力标签（与 README 功能特性同步；只作展示，不参与逻辑） */
      featureTags: [
        '十种题型',
        '逻辑跳转',
        '矩阵/排序/NPS',
        '考试判分',
        '实名问卷',
        '签到核销',
        '作答乱序',
        '随机抽题',
        '选项配图',
        'RBAC 权限',
        '次级管理员',
        '协作者',
        '反馈 / 客服',
        '举报审核',
        '额度钱包',
        '模板热度',
        '版本历史',
        '答卷质控',
        'Excel 导出',
        'AI 生成',
        'Redis 限流'
      ]
    }
  },
  computed: {
    role() {
      return getRole()
    },
    isSubAdmin() {
      return this.role === 'subadmin'
    },
    isSuper() {
      return this.role === 'admin'
    },
    pageSubtitle() {
      return this.isSuper
        ? '全站运营概览：待办、服务健康与最近动态'
        : '只显示超级管理员分配给你的模块'
    },
    hasAnyModule() {
      return Object.keys(this.stats).length > 0
    },
    canViewLogs() {
      return this.isSuper || hasPermission('log:view')
    },
    /** 只用于空态文案：有 system:view 的人可以去统计页看资源 */
    canViewStatistics() {
      return this.isSuper || hasPermission('system:view')
    },
    hasActivities() {
      return this.has('recentActivities') && Array.isArray(this.stats.recentActivities)
    },
    activities() {
      return this.hasActivities ? this.stats.recentActivities : []
    },
    /** 指标卡：每个字段对应一个模块权限，后端没返回就不渲染 */
    statCards() {
      const s = this.stats
      const cards = []
      if (this.has('users')) {
        cards.push({
          key: 'users', label: '总用户', value: s.users, icon: 'UserFilled',
          bg: '#ecf5ff', color: '#409eff', sub: '注册账号'
        })
      }
      if (this.has('total')) {
        cards.push({
          key: 'surveys', label: '总问卷', value: s.total, icon: 'Document',
          bg: '#f0f9eb', color: '#67c23a', sub: `已发布 ${s.published}`
        })
      }
      if (this.has('responses')) {
        cards.push({
          key: 'answers', label: '总答卷', value: s.responses, icon: 'ChatDotRound',
          bg: '#fef0f0', color: '#f56c6c', sub: `今日 +${s.todayAnswers}`
        })
      }
      if (this.has('views')) {
        cards.push({
          key: 'views', label: '访客次数', value: s.views, icon: 'View',
          bg: '#f4f0ff', color: '#8e44ad', sub: `回收站 ${s.recycled}`
        })
      }
      if (this.has('templates')) {
        cards.push({
          key: 'templates', label: '在架模板', value: s.templates, icon: 'DocumentCopy',
          bg: '#e8f6ff', color: '#12b0e8', sub: '模板广场'
        })
      }
      if (this.has('pendingReviews')) {
        cards.push({
          key: 'reviews', label: '待处理举报', value: s.pendingReviews, icon: 'Warning',
          bg: '#fff7e6', color: '#d97706',
          sub: s.pendingReviews > 0 ? '点击处理' : '暂无待办',
          path: '/admin/reviews'
        })
      }
      if (this.has('walletBalance')) {
        cards.push({
          key: 'wallet', label: '全站额度', value: s.walletBalance, icon: 'Wallet',
          bg: '#eef2ff', color: '#4338ca', sub: '点', path: '/admin/wallets'
        })
      }
      if (this.has('todaySurveys')) {
        cards.push({
          key: 'today', label: '今日新增问卷', value: s.todaySurveys, icon: 'Timer',
          bg: '#e7fbff', color: '#0e7490', sub: '今日新建'
        })
      }
      return cards
    },
    todayItems() {
      const s = this.stats
      const items = []
      if (this.has('todaySurveys')) {
        items.push({ label: '今日新增问卷', value: s.todaySurveys, icon: 'Document', tone: 'blue' })
      }
      if (this.has('todayAnswers')) {
        items.push({ label: '今日收到答卷', value: s.todayAnswers, icon: 'ChatDotRound', tone: 'green' })
      }
      return items
    },
    opsItems() {
      const s = this.stats
      const items = []
      if (this.has('pendingReviews')) {
        items.push({
          label: '待处理举报', value: s.pendingReviews, suffix: '',
          path: '/admin/reviews', warn: s.pendingReviews > 0, icon: 'Warning', tone: 'amber'
        })
      }
      if (this.has('walletMismatches')) {
        items.push({
          label: '钱包对账异常', value: s.walletMismatches, suffix: '',
          path: '/admin/wallets', warn: s.walletMismatches > 0, icon: 'Wallet', tone: 'violet'
        })
      }
      if (this.has('recycled')) {
        items.push({
          label: '回收站问卷', value: s.recycled, suffix: '',
          path: '/admin/recycle', warn: false, icon: 'Delete', tone: 'rose'
        })
      }
      if (this.has('templates')) {
        items.push({
          label: '在架模板', value: s.templates, suffix: '',
          path: '/admin/templates', warn: false, icon: 'DocumentCopy', tone: 'blue'
        })
      }
      if (this.has('walletBalance')) {
        items.push({
          label: '全站额度总量', value: s.walletBalance, suffix: ' 点',
          path: '/admin/wallets', warn: false, icon: 'DataLine', tone: 'indigo'
        })
      }
      return items
    },
    quickActions() {
      const actions = [
        { permission: 'user:manage', title: '用户管理', desc: '账号 / 注销恢复', path: '/users', icon: 'UserFilled', color: '#409eff', bg: '#ecf5ff' },
        { permission: 'survey:manage', title: '全部问卷', desc: '查看 / 模板 / 删除', path: '/admin/surveys', icon: 'Document', color: '#67c23a', bg: '#f0f9eb' },
        { permission: 'template:manage', title: '模板管理', desc: '分类 / 热度 / 内容', path: '/admin/templates', icon: 'DocumentCopy', color: '#12b0e8', bg: '#e8f6ff' },
        { permission: 'review:handle', title: '举报审核', desc: '下架 / 恢复上架', path: '/admin/reviews', icon: 'Warning', color: '#e6a23c', bg: '#fff7e6' },
        { permission: 'wallet:manage', title: '钱包管理', desc: '余额 / 调整 / 对账', path: '/admin/wallets', icon: 'Wallet', color: '#8e44ad', bg: '#f4f0ff' },
        { permission: 'announcement:manage', title: '公告管理', desc: '发布 / 上下架', path: '/admin/announcements', icon: 'Bell', color: '#b45309', bg: '#fff6e8' },
        { permission: 'log:view', title: '系统监控', desc: '操作 / 异常日志', path: '/admin/logs', icon: 'Memo', color: '#f56c6c', bg: '#fef0f0' }
      ]
      return actions.filter(a => this.isSuper || hasPermission(a.permission))
    },
    healthText() {
      return this.serverHealthy ? '服务运行正常' : '服务已离线'
    },
    /** 运营提示：按"举报 > 对账 > 回收站 > 今日冷清"的优先级给一句行动建议（无字段的模块自动跳过） */
    opsHint() {
      const s = this.stats
      if (s.pendingReviews > 0) {
        return `有 ${s.pendingReviews} 条举报待处理，先清审核台，相关问卷会一直保持下架状态`
      }
      if (s.walletMismatches > 0) {
        return `有 ${s.walletMismatches} 个钱包对账不一致，建议到钱包管理里人工核对流水`
      }
      if (s.recycled > 0) {
        return `回收站有 ${s.recycled} 份问卷，可恢复或彻底删除`
      }
      if (s.todayAnswers === 0) {
        return '今天还没有收到答卷，可以检查热门问卷的分享入口是否已发出'
      }
      return '暂无高优先级待办，系统运行平稳'
    },
    opsHintWarn() {
      return this.stats.pendingReviews > 0 || this.stats.walletMismatches > 0
    }
  },
  mounted() {
    this.loadStats()
    this.checkHealth()
    this.healthTimer = setInterval(this.checkHealth, 5000)
  },
  beforeUnmount() {
    if (this.healthTimer) clearInterval(this.healthTimer)
  },
  methods: {
    /** 后端没返回的字段 = 没有该模块权限；用 hasOwnProperty 区分 0 与缺失 */
    has(key) {
      return Object.prototype.hasOwnProperty.call(this.stats, key)
    },
    openCard(card) {
      if (card && card.path) this.$router.push(card.path)
    },
    // 后端健康检查：右上角状态点的唯一数据源
    async checkHealth() {
      try {
        await api.misc.health()
        this.serverHealthy = true
      } catch {
        this.serverHealthy = false
      }
    },
    async loadStats() {
      try {
        const data = await api.survey.systemOverview()
        this.stats = data && typeof data === 'object' ? data : {}
        this.loaded = true
      } catch {
        // 失败提示由 src/api/http.js 统一弹出；页面显示重试空态，不伪造数字
        this.failed = true
      }
    }
  }
}
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  color: var(--ink);
}
.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ink-3);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.health-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: #15803d;
  background: #ecfdf3;
  border: 1px solid #bbf7d0;
}
.health-pill.offline {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}
.health-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.16);
}
.health-pill.offline .health-dot {
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.16);
}
.btn-icon {
  margin-right: 5px;
}

/* 两栏等高：el-col 变成 flex 容器后，卡片铺满整行并让 body 也能纵向伸展 */
.equal-height-row :deep(.el-col) {
  display: flex;
}
/* 卡片是纯展示容器：内部滚动条会抢走页面滚轮，统一裁掉 */
:deep(.el-card) {
  overflow: hidden;
}
.equal-height-row :deep(.el-card) {
  width: 100%;
  display: flex;
  flex-direction: column;
}
.equal-height-row :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ---------- 指标卡 ---------- */
.stat-card {
  background: linear-gradient(180deg, #ffffff 0%, #fbfdff 100%);
  transition: transform 0.18s var(--ease), box-shadow 0.18s var(--ease);
}
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-card.clickable {
  cursor: pointer;
}
.stat-card.clickable:hover {
  transform: translateY(-2px);
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}
.stat-content {
  flex: 1;
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 4px;
}
.stat-value {
  font-size: 23px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.stat-sub {
  margin-top: 4px;
  font-size: 11px;
  color: #a8abb2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.panel-hint {
  font-size: 12px;
  color: var(--ink-3);
}

/* ---------- 运营待办 ---------- */
.ops-summary {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.ops-summary-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 13px;
  border-radius: 12px;
  background: var(--surface-2);
  border: 1px solid var(--stroke);
}
.ops-summary-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.ops-summary-icon.blue {
  background: #e8f3ff;
  color: #2f80ed;
}
.ops-summary-icon.green {
  background: #e9f9ef;
  color: #16a34a;
}
.ops-summary-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.ops-summary-label {
  font-size: 11px;
  color: var(--ink-3);
}
.ops-summary-text b {
  font-size: 20px;
  line-height: 1.05;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.ops-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ops-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.16s var(--ease), transform 0.16s var(--ease);
}
.ops-item:hover {
  background: var(--sky-50, #eff6ff);
  transform: translateX(2px);
}
.ops-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--stroke);
  color: #64748b;
}
.ops-icon.amber {
  background: #fff7e6;
  color: #d97706;
}
.ops-icon.violet {
  background: #f3ecff;
  color: #7c3aed;
}
.ops-icon.rose {
  background: #fef0f0;
  color: #dc2626;
}
.ops-icon.blue {
  background: #e8f3ff;
  color: #2f80ed;
}
.ops-icon.indigo {
  background: #eef2ff;
  color: #4338ca;
}
.ops-label {
  flex: 1;
  font-size: 13px;
  color: var(--ink-2);
}
.ops-value {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.ops-value.warn {
  color: #d97706;
}
.ops-arrow {
  font-size: 13px;
  color: #c0c4cc;
}
.ops-empty {
  padding: 18px 8px;
  font-size: 13px;
  color: var(--ink-3);
}

/* 运营提示：蓝=一般提醒，琥珀=需要马上处理 */
.ops-hint {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: 10px;
  background: #f0f7ff;
  color: #1d4ed8;
  font-size: 12px;
  line-height: 1.55;
}
.ops-hint.warn {
  background: #fff7e6;
  color: #b45309;
}
.ops-hint-icon {
  flex-shrink: 0;
}

/* ---------- 最近动态 ---------- */
.activity-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-around;
}
.activity-item {
  display: flex;
  gap: 10px;
  padding: 7px 0;
}
.activity-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #2f80ed, #56ccf2);
}
.activity-body {
  flex: 1;
  min-width: 0;
}
.activity-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.45;
  overflow: hidden;
}
.activity-main b {
  flex-shrink: 0;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
  font-weight: 600;
}
.activity-op {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.activity-time {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-3);
}

/* ---------- 快捷操作 ---------- */
.quick-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 18px 10px;
  border-radius: 12px;
  cursor: pointer;
  border: 1px solid var(--stroke);
  transition: all 0.2s ease;
}
.quick-action:hover {
  background: var(--surface-2);
  border-color: #409eff;
  transform: translateY(-2px);
}
.quick-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.quick-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.quick-desc {
  font-size: 11px;
  color: var(--ink-3);
}

/* ---------- 系统信息（折叠） ---------- */
.sys-card :deep(.el-card__body) {
  padding-top: 4px;
  padding-bottom: 4px;
}
.sys-collapse :deep(.el-collapse-item__header),
.sys-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.sys-version {
  margin-left: 10px;
}
.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

/* ---------- 深色主题 ---------- */
html[data-theme="dark"] .stat-card {
  background: linear-gradient(180deg, #13233b 0%, #101d31 100%);
}
html[data-theme="dark"] .health-pill {
  background: #0f2a1a;
  border-color: #14532d;
  color: #86efac;
}
html[data-theme="dark"] .health-pill.offline {
  background: #2a1215;
  border-color: #7f1d1d;
  color: #fca5a5;
}
html[data-theme="dark"] .ops-summary-item {
  background: #16263f;
  border-color: #1e3a5f;
}
html[data-theme="dark"] .ops-hint {
  background: #16263f;
  color: #93c5fd;
}
html[data-theme="dark"] .ops-hint.warn {
  background: #3a2a12;
  color: #fbbf24;
}
html[data-theme="dark"] .ops-icon {
  background: #1e293b;
  color: #94a3b8;
}
html[data-theme="dark"] .quick-action {
  border-color: #1e293b;
}
html[data-theme="dark"] .quick-action:hover {
  background: #16263f;
}

@media (max-width: 900px) {
  .page-head {
    margin-bottom: 14px;
  }
}
</style>

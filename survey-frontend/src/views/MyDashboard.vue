<template>
  <div>
    <!-- 欢迎语 -->
    <el-card style="margin-bottom: 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border: none;">
      <div style="color: #fff; display: flex; align-items: center; justify-content: space-between;">
        <div>
          <h2 style="margin: 0 0 8px 0; color: #fff;">👋 你好，{{ userName }}</h2>
          <p style="margin: 0; opacity: 0.9;">欢迎回来，这里是你的问卷数据总览</p>
        </div>
        <div style="font-size: 60px; opacity: 0.3;">📊</div>
      </div>
    </el-card>

    <!-- 数据卡片 -->
    <el-row :gutter="20">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #ecf5ff; color: #409eff;">
            <el-icon :size="28"><Document /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">我的问卷</div>
            <div class="stat-value" style="color: #409eff;">{{ stats.total }}</div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #f0f9eb; color: #67c23a;">
            <el-icon :size="28"><Promotion /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已发布</div>
            <div class="stat-value" style="color: #67c23a;">{{ stats.published }}</div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #fdf6ec; color: #e6a23c;">
            <el-icon :size="28"><EditPen /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">草稿</div>
            <div class="stat-value" style="color: #e6a23c;">{{ stats.draft }}</div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #fef0f0; color: #f56c6c;">
            <el-icon :size="28"><ChatDotRound /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总答卷数</div>
            <div class="stat-value" style="color: #f56c6c;">{{ stats.responses }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 下方两块 -->
    <el-row :gutter="20" style="margin-top: 20px;">
      <!-- 左：状态分布饼图 -->
      <el-col :span="10">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">📊 问卷状态分布</span>
            </div>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" style="width: 100%; height: 280px;"></div>
          <el-empty v-else description="暂无问卷" :image-size="80" />
        </el-card>
      </el-col>

      <!-- 右：最近问卷 -->
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">📝 最近创建的问卷</span>
              <el-button type="primary" link size="small" @click="$router.push('/my/surveys')">查看全部 →</el-button>
            </div>
          </template>
          <el-table :data="stats.recent" :show-header="false" style="width: 100%;">
            <el-table-column min-width="180">
              <template #default="{ row }">
                <div style="display: flex; align-items: center;">
                  <el-icon style="margin-right: 8px; color: #409eff;"><Document /></el-icon>
                  <span style="font-weight: 500;">{{ row.title }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
                <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
                <el-tag v-else type="info" size="small">草稿</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdTime" width="160" align="right">
              <template #default="{ row }">
                <span style="color: #999; font-size: 13px;">{{ row.createdTime }}</span>
              </template>
            </el-table-column>
            <el-table-column width="70" align="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="goEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!stats.recent || stats.recent.length === 0" description="还没有创建过问卷" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Document, Promotion, EditPen, ChatDotRound } from '@element-plus/icons-vue'

export default {
  components: { Document, Promotion, EditPen, ChatDotRound },
  data() {
    return {
      userName: '',
      stats: {
        total: 0,
        published: 0,
        draft: 0,
        ended: 0,
        responses: 0,
        recent: []
      },
      pieChart: null
    }
  },
  mounted() {
    const user = JSON.parse(localStorage.getItem('user') || '{}')
    this.userName = user.nickname || user.username || '用户'
    this.loadStats()
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
  },
  methods: {
    async loadStats() {
      try {
        const res = await fetch('/api/survey/my-statistics')
        const data = await res.json()
        this.stats = data
        this.$nextTick(() => this.initPie())
      } catch {
        ElMessage.error('加载统计数据失败')
      }
    },

    initPie() {
      if (this.stats.total === 0) return
      if (!this.$refs.pieRef) return
      if (this.pieChart) this.pieChart.dispose()

      this.pieChart = echarts.init(this.$refs.pieRef)

      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: '#67c23a' } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: '#e6a23c' } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: '#f56c6c' } })

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
    },

    goEdit(row) {
      this.$router.push(`/my/surveys/${row.id}/edit`)
    }
  }
}
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  padding: 8px;
}
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 16px;
}
.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
}
.stat-content {
  flex: 1;
}
.stat-label {
  font-size: 14px;
  color: #999;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 28px;
  font-weight: bold;
  line-height: 1;
}
</style>
<template>
  <div class="page-state">
    <!-- 加载中：骨架块（行数可配）。列表页用它代替整块 v-loading 转圈 -->
    <div v-if="loading" class="ps-skeleton">
      <div
        v-for="i in skeletonRows"
        :key="i"
        class="sk-block ps-sk-row"
      />
    </div>

    <!-- 失败：图标 + 文案 + 重试。断网/停后端时就靠它，而不是停在旧数据上 -->
    <div v-else-if="error" class="ps-error">
      <el-icon class="ps-error-icon"><WarningFilled /></el-icon>
      <p class="ps-error-text">{{ errorText }}</p>
      <el-button type="primary" plain @click="$emit('retry')">重试</el-button>
    </div>

    <!-- 空：插画 + 说明 + 可选主行动（默认插槽） -->
    <el-empty v-else-if="empty" :description="emptyText" :image-size="96">
      <slot name="action" />
    </el-empty>

    <!-- 正常内容 -->
    <slot v-else />
  </div>
</template>

<script>
/**
 * 页面三态容器（骨架 / 空 / 错误）。
 *
 * 为什么要统一：改造前各列表页的空态文案各写各的、加载是 v-loading 转圈、
 * 接口失败只有一条 toast（页面还停在旧数据上，看着像"加载成功但没数据"）。
 *
 * 用法：
 *   <PageState :loading="loading" :error="error" :empty="rows.length === 0"
 *              empty-text="暂无问卷" @retry="loadRows">
 *     <el-table ... />
 *   </PageState>
 *
 * 注意：error 优先于 empty —— 失败时列表通常为空，不该显示"暂无数据"。
 */
export default {
  name: 'PageState',
  props: {
    loading: { type: Boolean, default: false },
    empty: { type: Boolean, default: false },
    error: { type: Boolean, default: false },
    emptyText: { type: String, default: '暂无数据' },
    errorText: { type: String, default: '加载失败，请检查网络或稍后重试' },
    skeletonRows: { type: Number, default: 6 }
  },
  emits: ['retry']
}
</script>

<style scoped>
.page-state {
  width: 100%;
}

.ps-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 6px 14px;
}

.ps-sk-row {
  height: 44px;
}

.ps-sk-row:first-child {
  height: 52px;
}

.ps-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 54px 20px;
  text-align: center;
}

.ps-error-icon {
  font-size: 46px;
  color: #f59e0b;
}

.ps-error-text {
  margin: 0;
  font-size: 14px;
  color: var(--ink-2);
}
</style>

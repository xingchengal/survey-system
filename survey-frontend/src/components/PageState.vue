<template>
  <div class="page-state">
    <!-- 加载中：骨架块（行数可配），不用整块 v-loading -->
    <div v-if="loading" class="ps-skeleton">
      <div
        v-for="i in skeletonRows"
        :key="i"
        class="sk-block ps-sk-row"
      />
    </div>

    <!-- 失败：图标 + 文案 + 重试 -->
    <div v-else-if="error" class="ps-error">
      <el-icon class="ps-error-icon"><WarningFilled /></el-icon>
      <p class="ps-error-text">{{ errorText }}</p>
      <el-button type="primary" plain @click="$emit('retry')">重试</el-button>
    </div>

    <!-- 空：插画 + 说明 + 可选主行动（action 插槽） -->
    <el-empty v-else-if="empty" :description="emptyText" :image-size="96">
      <slot name="action" />
    </el-empty>

    <slot v-else />
  </div>
</template>

<script>
/**
 * 列表页三态统一容器：加载骨架 / 空态 / 错误重试。
 * ★ 新列表页一律用它，别每页各写一套 empty + v-loading + toast；error 优先于 empty。
 * 契约：props loading / error / empty / emptyText / errorText / skeletonRows；emits retry。
 * 用法：<PageState :loading="loading" :error="error" :empty="!rows.length" @retry="loadRows" />
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

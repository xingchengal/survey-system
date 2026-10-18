<template>
  <el-radio-group
    :model-value="density"
    size="small"
    class="table-density"
    @change="setDensity"
  >
    <el-radio-button value="default">默认</el-radio-button>
    <el-radio-button value="compact">紧凑</el-radio-button>
  </el-radio-group>
</template>

<script>
import { ref } from 'vue'

/**
 * 表格行密度切换（默认 / 紧凑）。
 *
 * 实现方式：只切 <html data-density>，行高由 theme.css 统一收紧 ——
 * 这样任何一个 el-table 都不用改模板，也不需要在页面里传 :size。
 * 选择存 localStorage，刷新后保持。
 */
const KEY = 'survey_table_density'

function load() {
  try {
    return localStorage.getItem(KEY) === 'compact' ? 'compact' : 'default'
  } catch {
    return 'default'
  }
}

function apply(value) {
  document.documentElement.dataset.density = value
}

const density = ref(load())
// 模块加载即生效：刷新后沿用上次选择（不用等任何一个表格挂载）
apply(density.value)

export default {
  name: 'TableDensity',
  setup() {
    function setDensity(value) {
      density.value = value
      try {
        localStorage.setItem(KEY, value)
      } catch {
        // 存不进去就只在本次会话生效
      }
      apply(value)
    }
    return { density, setDensity }
  }
}
</script>

<style scoped>
.table-density {
  flex-shrink: 0;
}
</style>

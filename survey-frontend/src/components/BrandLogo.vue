<template>
  <svg
    class="brand-logo"
    :width="size"
    :height="size"
    viewBox="0 0 48 48"
    role="img"
    aria-label="问卷系统"
  >
    <!--
      品牌标识：天空蓝渐变圆角方块 + 白色问卷线条。
      ★ 与 AdminLayout 侧栏 logo、登录/注册页标题、public/favicon.svg 是同一套图形，
        改这里记得同步改 favicon（两处图形保持一致即可，不要求逐像素相同）。
      ★ 渐变 id 固定：同页出现多个实例时共用同一份 <defs>，视觉无误。
    -->
    <defs>
      <linearGradient id="brand-logo-gradient" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#56ccf2" />
        <stop offset="55%" stop-color="#2f80ed" />
        <stop offset="100%" stop-color="#2563eb" />
      </linearGradient>
    </defs>
    <rect x="2" y="2" width="44" height="44" rx="13" fill="url(#brand-logo-gradient)" />
    <!-- 问卷纸（描边） -->
    <rect
      x="14"
      y="11.5"
      width="20"
      height="25"
      rx="4.5"
      fill="none"
      stroke="#ffffff"
      stroke-width="2.2"
      opacity="0.95"
    />
    <!-- 作答勾选 + 题目行 -->
    <path
      d="M18 18.6 l1.8 1.9 3.2-3.6"
      fill="none"
      stroke="#ffffff"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
    <rect x="25.4" y="16.8" width="5.6" height="2.2" rx="1.1" fill="#ffffff" opacity="0.95" />
    <rect x="18" y="24.4" width="13" height="2.2" rx="1.1" fill="#ffffff" opacity="0.9" />
    <rect x="18" y="29.4" width="9" height="2.2" rx="1.1" fill="#ffffff" opacity="0.7" />
  </svg>
</template>

<script>
export default {
  name: 'BrandLogo',
  props: {
    /** 图形边长（px） */
    size: { type: Number, default: 32 }
  }
}
</script>

<style scoped>
.brand-logo {
  display: inline-block;
  flex-shrink: 0;
  vertical-align: middle;
}
</style>

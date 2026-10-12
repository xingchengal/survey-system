import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 构建 / 开发服务器配置。
 *
 * ── 开发环境（npm run dev）──────────────────────────────
 *   端口 5173，并把 /api 请求代理到后端 8080。
 *   这就是为什么前端代码里可以直接写 fetch('/api/survey/my') 而不带域名 ——
 *   开发时由这里的 proxy 转发，生产环境则是「同源单包部署」（dist 拷进后端 static）。
 *
 * ── 生产构建（npm run build）────────────────────────────
 *   路由级懒加载已经在 router/index.js 里做了（每个页面一个 chunk），
 *   这里再补一层「公共依赖分包」：把 vue / element-plus / echarts 这些体积大、
 *   更新频率低的库拆成独立 chunk，好处是：
 *     1. 单个 chunk 体积变小，减少构建体积告警；
 *     2. 业务代码更新时，这些库的 chunk 哈希不变 → 用户浏览器可以用缓存。
 *
 *   ⚠️ 说明：element-plus 现在是**全量引入**（见 main.js），
 *   它的 CSS 约 360KB、JS 也比较大，所以即使分包，那个 chunk 仍可能超过
 *   默认的 500KB 告警线。要进一步瘦身需要改成按需引入（unplugin-vue-components），
 *   那会改动所有页面的写法，属于后续可选项，这里先不做。
 */
// ★ 后端地址可配置：不要把它写死在 proxy 里 —— 后端换端口 / 跑在别的机器 /
//   要连临时实例（比如 8081）时，写死就只能改代码。
//   ★ 为什么用 loadEnv 而不是直接读 process.env：Vite **不会**把 .env / .env.local 里的
//     `VITE_xxx` 注入到 config 执行时的 process.env（只有客户端代码里的 import.meta.env 才拿得到）。
//     用 loadEnv(mode, cwd, '') 一次把「.env 文件」与「shell 环境变量」都收进来，两种方式都能生效：
//       ① 临时： $env:VITE_API_TARGET='http://localhost:8081'; npm run dev
//       ② 长期： 在 survey-frontend/.env.local 写一行 VITE_API_TARGET=http://localhost:8081
//     不设置任何东西时就是默认的 8080，正常开发完全无感。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'
  return {
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  },
  build: {
    // 关掉 sourcemap（默认就是 false，这里显式写出来表明态度：产物不带源码）
    sourcemap: false,
    rollupOptions: {
      output: {
        // ★ 公共依赖分包：把体积大、更新频率低的库拆成独立 chunk。
        //
        // ⚠️ 必须写成**函数**形式。Vite 8 底层换成了 rolldown，
        //    如果写成对象（{ echarts: ['echarts'] } 那种传统 rollup 写法），
        //    会报 "Expected Function but received Object" 并直接构建失败。
        //
        // 参数 id 是模块的完整路径，返回 chunk 名（返回 undefined 表示"不特殊分组"）。
        manualChunks(id) {
          // 只处理第三方库；业务代码交给 router 的懒加载自然分包
          if (!id.includes('node_modules')) return

          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('qrcode')) return 'qrcode'
          if (id.includes('vue-router') || id.includes('@vue/') || /[\\/]vue[\\/]/.test(id)) {
            return 'vue-vendor'
          }
          return 'vendor'
        }
      }
    }
  }
  }
})

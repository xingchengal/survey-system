// ESLint 扁平配置（ESLint 9+ 的写法，旧的 .eslintrc 已废弃）
//
// ★ 使用前需要先装依赖（在 survey-frontend 目录下执行）：
//     npm i -D eslint @eslint/js eslint-plugin-vue globals
//   装完就能用：npm run lint（自动修）/ npm run lint:check（只检查）
//
// ★ VSCode 里想让它实时标红：安装 "ESLint" 扩展即可，会自动读这个文件。

import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  // 1. JS 官方推荐规则
  js.configs.recommended,

  // 2. Vue 3 官方推荐规则（会检查模板里的常见问题）
  ...pluginVue.configs['flat/recommended'],

  // 3. 本项目自己的调整
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        // 浏览器环境
        ...globals.browser
      }
    },
    rules: {
      // 组件名允许单词（Login.vue、Dashboard.vue…）是本项目既有风格，不强制改成多词
      'vue/multi-word-component-names': 'off',
      // 未使用变量只警告不报错（开发期常留临时变量）
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      // 允许 console（项目里用它做少量输出）
      'no-console': 'off',
      // 禁止 v-html（防 XSS；本项目当前 v-html 使用数为 0，保持这条约束）
      'vue/no-v-html': 'error',

      // ===== 以下全是「排版类」规则：交给 Prettier 管，ESLint 不重复管 =====
      // 原因：这些规则和 Prettier 会互相打架（ESLint 要求一种换行方式，Prettier 改成另一种），
      // 而且它们在首次检查里贡献了 1400 多条 warning，却没有一条是真 bug。
      // 标准做法就是"格式归 Prettier、正确性归 ESLint"，两边各管一摊。
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/first-attribute-linebreak': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/html-indent': 'off',
      'vue/attributes-order': 'off'
    }
  },

  // 4. 构建/配置文件跑在 Node 里，需要 Node 全局变量（process 等）
  //    ★ vite.config.js 里用 `loadEnv(mode, process.cwd(), '')` + `env.VITE_API_TARGET`
  //      让后端地址可配，其中 process.cwd() 用到 Node 全局，
  //      而上面的 globals 只给了浏览器环境 → `no-undef: process is not defined`。
  //      只对这两个文件开 Node 全局，避免把 Node API 泄漏进浏览器代码。
  {
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: {
      globals: {
        ...globals.node
      }
    }
  },

  // 5. 不检查这些目录
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**']
  }
]

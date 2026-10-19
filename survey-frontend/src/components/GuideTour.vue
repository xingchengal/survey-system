<template>
  <teleport to="body">
    <div v-if="visible && currentStep" class="tour-root">
      <!-- 聚光灯：用超大 box-shadow 压暗四周，不挖洞、不改目标元素层级 -->
      <div class="tour-spotlight" :style="spotlightStyle"></div>
      <div class="tour-card" :style="cardStyle" role="dialog" :aria-label="currentStep.title">
        <div class="tour-progress">第 {{ stepIndex + 1 }} / {{ steps.length }} 步</div>
        <div class="tour-title">{{ currentStep.title }}</div>
        <div class="tour-text">{{ currentStep.text }}</div>
        <div class="tour-actions">
          <el-button text size="small" @click="finish">跳过</el-button>
          <div class="tour-nav">
            <el-button size="small" :disabled="stepIndex === 0" @click="goTo(stepIndex - 1)">上一步</el-button>
            <el-button size="small" type="primary" @click="next">
              {{ stepIndex === steps.length - 1 ? '完成' : '下一步' }}
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script>
/**
 * 新手引导浮层（不引第三方依赖）。
 * ★ 每一步用 CSS 选择器定位目标；目标不存在（角色差异 / 页面未渲染）就自动跳下一步，绝不卡住。
 * ★ 只在使用者手动开启时播放（个人中心由「新手引导」按钮触发，不自动弹）。
 * ★ 传了 storageKey 才在完成/跳过时写 localStorage；不传就完全不碰存储。
 */
const CARD_WIDTH = 320
const CARD_HEIGHT = 190
const MARGIN = 14

export default {
  name: 'GuideTour',
  props: {
    modelValue: { type: Boolean, default: false },
    /** [{ target: '.css-selector', title: '标题', text: '说明' }] */
    steps: { type: Array, default: () => [] },
    /** localStorage 完成标记的 key；为空则不记 */
    storageKey: { type: String, default: '' }
  },
  emits: ['update:modelValue'],
  data() {
    return {
      stepIndex: 0,
      targetEl: null,
      spot: null,
      card: null
    }
  },
  computed: {
    visible() {
      return this.modelValue
    },
    currentStep() {
      return this.steps[this.stepIndex] || null
    },
    spotlightStyle() {
      if (!this.spot) return { display: 'none' }
      return {
        top: `${this.spot.top}px`,
        left: `${this.spot.left}px`,
        width: `${this.spot.width}px`,
        height: `${this.spot.height}px`
      }
    },
    cardStyle() {
      if (!this.card) return { display: 'none' }
      return {
        top: `${this.card.top}px`,
        left: `${this.card.left}px`
      }
    }
  },
  watch: {
    modelValue: {
      immediate: true,
      handler(val) {
        if (val) this.open()
        else this.teardown()
      }
    }
  },
  mounted() {
    this.reposition = () => this.updatePosition()
    window.addEventListener('resize', this.reposition)
    window.addEventListener('scroll', this.reposition, true)
    this.escHandler = (e) => {
      if (e.key === 'Escape' && this.visible) this.finish()
    }
    window.addEventListener('keydown', this.escHandler)
  },
  beforeUnmount() {
    this.teardown()
    window.removeEventListener('resize', this.reposition)
    window.removeEventListener('scroll', this.reposition, true)
    window.removeEventListener('keydown', this.escHandler)
  },
  methods: {
    async open() {
      if (!this.steps.length) return
      await this.$nextTick()
      this.goTo(0)
    },

    async goTo(index) {
      if (index < 0 || index >= this.steps.length) {
        this.finish()
        return
      }
      this.stepIndex = index
      const step = this.steps[index]
      const el = await this.waitFor(step.target, 1200)
      if (!el) {
        // 目标不存在（权限不同 / 该卡片未渲染）就跳过，别停在空白页
        this.goTo(index + 1)
        return
      }
      this.targetEl = el
      this.$nextTick(this.updatePosition)
    },

    next() {
      if (this.stepIndex >= this.steps.length - 1) this.finish()
      else this.goTo(this.stepIndex + 1)
    },

    /** 轮询直到元素出现或超时（切页/骨架屏时目标可能晚一拍才渲染） */
    waitFor(selector, timeout) {
      return new Promise((resolve) => {
        const start = Date.now()
        const tick = () => {
          const el = document.querySelector(selector)
          if (el) return resolve(el)
          if (Date.now() - start > timeout) return resolve(null)
          requestAnimationFrame(tick)
        }
        tick()
      })
    },

    updatePosition() {
      const el = this.targetEl
      if (!el || !el.isConnected) return
      const rect = el.getBoundingClientRect()
      this.spot = {
        top: rect.top - 6,
        left: rect.left - 6,
        width: rect.width + 12,
        height: rect.height + 12
      }
      const below = rect.bottom + 12
      const top = below + CARD_HEIGHT > window.innerHeight
        ? Math.max(MARGIN, rect.top - CARD_HEIGHT - 12)
        : below
      const left = Math.min(
        Math.max(MARGIN, rect.left),
        window.innerWidth - CARD_WIDTH - MARGIN
      )
      this.card = { top, left }
    },

    finish() {
      if (this.storageKey) {
        try {
          localStorage.setItem(this.storageKey, '1')
        } catch {
          // 隐私模式下写不了也不影响关闭
        }
      }
      this.$emit('update:modelValue', false)
    },

    teardown() {
      this.targetEl = null
      this.spot = null
      this.card = null
    }
  }
}
</script>

<style scoped>
.tour-root {
  position: fixed;
  inset: 0;
  z-index: 3000;
}
.tour-spotlight {
  position: fixed;
  border: 2px solid var(--sky-500);
  border-radius: 10px;
  box-shadow: 0 0 0 9999px rgba(3, 10, 24, 0.62);
  pointer-events: none;
  transition:
    top 0.22s var(--ease),
    left 0.22s var(--ease),
    width 0.22s var(--ease),
    height 0.22s var(--ease);
  z-index: 3000;
}
.tour-card {
  position: fixed;
  width: 320px;
  padding: 16px 18px 14px;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  background: var(--surface);
  box-shadow: var(--shadow-lg);
  z-index: 3001;
}
.tour-progress {
  font-size: 11px;
  color: var(--sky-500);
  margin-bottom: 6px;
}
.tour-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.tour-text {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
}
.tour-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
}
.tour-nav {
  display: flex;
  gap: 8px;
}
</style>

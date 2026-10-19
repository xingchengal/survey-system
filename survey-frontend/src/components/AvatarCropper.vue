<template>
  <el-dialog
    :model-value="modelValue"
    title="裁剪头像"
    width="460px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @opened="onOpened"
    @closed="onClosed"
  >
    <!-- ★ 不要换成裁剪库：本机离线仓库里没有 cropperjs / vue-cropper。
         拖动只改 CSS 的 left/top/width，导出时才用离屏 canvas（见 crop()）。 -->
    <div
      ref="stage"
      class="crop-stage"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerUp"
    >
      <img
        v-if="src"
        ref="imgEl"
        :src="src"
        class="crop-img"
        :style="imgStyle"
        alt="待裁剪的图片"
        draggable="false"
        @load="onImageLoad"
      />
      <!-- 圆形遮罩 + 四角变暗：用一个超大 spread 的 box-shadow 画，圆外不留缝 -->
      <div class="crop-mask"></div>
    </div>

    <div class="crop-tip">拖动图片调整位置，用滑块缩放。<span class="crop-tip-dim">圆内区域即头像</span></div>

    <el-slider v-model="scalePercent" :min="30" :max="100" :show-tooltip="false" />

    <template #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button type="primary" :loading="busy" @click="crop">使用该头像</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ElMessage } from 'element-plus'

/**
 * 头像裁剪组件（自研，零依赖）：圆形取景框内所见即最终头像。
 * 用法：<avatar-cropper v-model="cropVisible" :src="rawImage" @confirm="onAvatarCropped" />
 * 契约：props.src 原始图 data URL（FileReader 读取）/ props.modelValue 显隐（v-model）；
 *       emits.confirm 返回 PNG data URL / emits.update:modelValue。
 * 输出固定 320×320 方形 PNG（展示时裁圆）；原图需 ≤2MB（后端按 base64 解码后大小校验）。
 * ★ crop() 必须把预览坐标逆变换回原图，与预览共用同一套 scale/offset，否则画面错位。
 */

/** 输出边长（px） */
const OUTPUT_SIZE = 320

/** 缩放下限（再小圆外会露白）；上限固定为 1，即原图尺寸 */
const MIN_SCALE = 0.3

export default {
  name: 'AvatarCropper',
  props: {
    modelValue: { type: Boolean, default: false },
    src: { type: String, default: '' }
  },
  emits: ['update:modelValue', 'confirm'],
  data() {
    return {
      /** 舞台（裁剪框）的边长，与下方 CSS 里的 .crop-stage 宽高必须一致 */
      stageSize: 320,
      scale: 0.5,
      offsetX: 0,
      offsetY: 0,
      naturalWidth: 0,
      naturalHeight: 0,
      dragStart: null,
      busy: false
    }
  },
  computed: {
    /** 滑块用百分比，避免 scale 浮点在滑块上抖动 */
    scalePercent: {
      get() {
        return Math.round(this.scale * 100)
      },
      set(v) {
        const next = Math.max(MIN_SCALE, Math.min(1, v / 100))
        // 缩放后必须重新夹位置：缩小时贴边的一侧会露出白边
        const centerX = this.offsetX + (this.naturalWidth * this.scale) / 2
        const centerY = this.offsetY + (this.naturalHeight * this.scale) / 2
        this.scale = next
        this.offsetX = centerX - (this.naturalWidth * next) / 2
        this.offsetY = centerY - (this.naturalHeight * next) / 2
        this.clampOffset()
      }
    },
    imgStyle() {
      return {
        left: this.offsetX + 'px',
        top: this.offsetY + 'px',
        width: this.naturalWidth * this.scale + 'px',
        height: this.naturalHeight * this.scale + 'px'
      }
    }
  },
  watch: {
    // 每次打开都重置：否则第二次打开会沿用上一次的缩放/位置，看起来像"图没换"
    modelValue(open) {
      if (open) this.resetForNewImage()
    }
  },
  methods: {
    onOpened() {
      this.resetForNewImage()
      // 图片可能已被浏览器缓存，load 事件不会再触发 —— 主动兜一次
      this.$nextTick(() => {
        const el = this.$refs.imgEl
        if (el && el.complete && el.naturalWidth > 0) this.onImageLoad()
      })
    },
    onClosed() {
      this.dragStart = null
    },
    /** 把缩放与位置重置成"图片铺满裁剪框"的初始态（保证任何方向都不露白） */
    resetForNewImage() {
      if (!this.src) return
      this.naturalWidth = 0
      this.naturalHeight = 0
      this.scale = 0.5
      this.offsetX = 0
      this.offsetY = 0
    },
    onImageLoad() {
      const el = this.$refs.imgEl
      if (!el || !el.naturalWidth || !el.naturalHeight) return
      this.naturalWidth = el.naturalWidth
      this.naturalHeight = el.naturalHeight
      // 初始按 cover 铺满裁剪框（两个方向都不露白）；极小图按 1:1 居中，放大糊不如保持原样
      const coverScale = this.stageSize / Math.min(this.naturalWidth, this.naturalHeight)
      this.scale = Math.min(1, coverScale)
      this.offsetX = (this.stageSize - this.naturalWidth * this.scale) / 2
      this.offsetY = (this.stageSize - this.naturalHeight * this.scale) / 2
      this.clampOffset()
    },
    /** 图片必须始终完全盖住裁剪框：边界要显式夹住，不能指望用户不会拖远 */
    clampOffset() {
      const w = this.naturalWidth * this.scale
      const h = this.naturalHeight * this.scale
      // 图片比框小时（极小图放大受限）居中，不让它漂在角落
      this.offsetX = w <= this.stageSize
        ? (this.stageSize - w) / 2
        : Math.min(0, Math.max(this.stageSize - w, this.offsetX))
      this.offsetY = h <= this.stageSize
        ? (this.stageSize - h) / 2
        : Math.min(0, Math.max(this.stageSize - h, this.offsetY))
    },
    onPointerDown(e) {
      if (!this.naturalWidth) return
      this.dragStart = { x: e.clientX, y: e.clientY, offsetX: this.offsetX, offsetY: this.offsetY }
      // setPointerCapture：拖出组件或窗口后再松手，事件仍回到这里，图片不会卡住不跟手
      if (e.target.setPointerCapture) {
        try { e.target.setPointerCapture(e.pointerId) } catch { /* 某些浏览器/元素不支持，忽略 */ }
      }
    },
    onPointerMove(e) {
      if (!this.dragStart) return
      this.offsetX = this.dragStart.offsetX + (e.clientX - this.dragStart.x)
      this.offsetY = this.dragStart.offsetY + (e.clientY - this.dragStart.y)
      this.clampOffset()
    },
    onPointerUp() {
      this.dragStart = null
    },
    /**
     * 导出：把裁剪框对应的原图画到 320×320 离屏 canvas。
     * 逆变换：sx = -offsetX / scale，sy = -offsetY / scale，sSize = stageSize / scale。
     * 必须与预览共用同一套 scale/offset，否则导出画面与用户看到的错位。
     */
    crop() {
      const img = this.$refs.imgEl
      if (!img || !this.naturalWidth) {
        return ElMessage.warning('图片还没加载完成，请稍候')
      }
      this.busy = true
      try {
        const sSize = this.stageSize / this.scale
        const sx = -this.offsetX / this.scale
        const sy = -this.offsetY / this.scale
        const canvas = document.createElement('canvas')
        canvas.width = OUTPUT_SIZE
        canvas.height = OUTPUT_SIZE
        const ctx = canvas.getContext('2d')
        // 白底：原图若是带透明通道的 PNG，透明区域在深色顶栏上会变成黑块
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
        ctx.drawImage(img, sx, sy, sSize, sSize, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
        const result = canvas.toDataURL('image/png')
        this.$emit('confirm', result)
        this.close()
      } catch (e) {
        // 同源 data URL 本不会污染画布；真出错也给用户一句人话，而不是只留 SecurityError
        console.error(e)
        ElMessage.error('裁剪失败，请重新选择图片')
      } finally {
        this.busy = false
      }
    },
    close() {
      this.$emit('update:modelValue', false)
    }
  }
}
</script>

<style scoped>
/* 裁剪舞台：正方形 + 超出隐藏；尺寸必须与 data.stageSize 一致（320） */
.crop-stage {
  position: relative;
  width: 320px;
  height: 320px;
  margin: 0 auto;
  overflow: hidden;
  border-radius: 8px;
  background: #f5f7fa;
  cursor: grab;
  touch-action: none;   /* 触屏上禁止滚动手势抢走拖动 */
  user-select: none;
}
.crop-stage:active { cursor: grabbing; }

.crop-img {
  position: absolute;
  /* 只用 left/top/width/height，不用 transform：导出时要按同一套数值逆变换 */
  image-rendering: auto;
  pointer-events: none;   /* 拖动统一由 stage 接管，图片不单独收事件 */
}

/* 圆形遮罩：中间透明、四周变暗。用超大 spread 的 box-shadow 一次画出圆外区域 */
.crop-mask {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 260px;
  height: 260px;
  margin: -130px 0 0 -130px;
  border-radius: 50%;
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.45);
  border: 1px solid rgba(255, 255, 255, 0.85);
  pointer-events: none;   /* 必须：否则遮罩会吃掉 pointerdown，图片拖不动 */
}

.crop-tip {
  margin: 12px 0 4px 0;
  text-align: center;
  font-size: 13px;
  color: #606266;
}
.crop-tip-dim { color: #909399; }
</style>

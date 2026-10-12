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
    <!-- ★ 裁剪交互完全自研，只用 DOM + CSS + Canvas 这些浏览器原生能力：
         **不要换成裁剪库** —— 本机离线仓库里没有 cropperjs / vue-cropper。

         ★ 为什么用 DOM/CSS 而不是把画布画在 <canvas> 里：
           拖动只需要改 CSS 的 left/top/scale，浏览器自己合成，不重绘位图；
           而"导出"那一步才用 canvas（见 crop()）—— 一次性的、离屏的。
           两件事分开做，代码量与性能都更好。 -->
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
      <!-- 圆形遮罩 + 四角变暗：用"超大 spread 的 box-shadow"实现，
           比四个方向的半透明 div 更简单，也不会在圆外留缝 -->
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
 * 头像裁剪组件（自研，零新增依赖）—— 需求 二.2「图片选择后前端预览，支持图片裁剪」。
 *
 * ── 用法（两个个人中心页面都是同一份调用）──────────────
 *   <avatar-cropper v-model="cropVisible" :src="rawImage" @confirm="onAvatarCropped" />
 *
 * ── 契约 ──────────────────────────────────────────────
 *   props.src       : 原始图片的 data URL（页面用 FileReader.readAsDataURL 得到）
 *   props.modelValue: 是否显示（v-model）
 *   emits.confirm   : 裁剪结果 data URL（**PNG**，见 crop()）
 *   emits.update:modelValue
 *
 * ── 为什么输出固定尺寸的正方形 PNG ────────────────────
 *   · 头像在所有地方都是圆形小图（顶栏 32px、卡片 110px），方形可裁即可；
 *   · 输出固定 320×320 会**顺带压缩**：一张 1MB 的原图裁完通常只有几十 KB，
 *     够不着后端的上限（base64 字符串 3MB / **解码后 2MB** 才是真正生效的那道闸），
 *     也不会让 /api/user/profile 每次返回一大坨 base64
 *     （那个接口会连 avatar 一起返回，见 UserMapper 的说明）。
 *   · 用 PNG 而不是沿用原图类型：PNG 无损、且后端白名单里有它，
 *     省掉"jpeg 质量参数该给多少"这种又要对齐一处口径的决定。
 */

/** 输出边长（px）。改这个数要连同下方 crop() 的 canvas 尺寸一起改。 */
const OUTPUT_SIZE = 320

/** 缩放下限：图片的最小显示比例（再小就会在圆外露白）。上限固定为 1（原图尺寸）。 */
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
      /** 图片的显示比例（相对原图） */
      scale: 0.5,
      /** 图片左上角相对舞台左上角的偏移（px） */
      offsetX: 0,
      offsetY: 0,
      /** 原图尺寸（onImageLoad 里读到） */
      naturalWidth: 0,
      naturalHeight: 0,
      /** 拖动起点，仅在按下到抬起之间有效 */
      dragStart: null,
      busy: false
    }
  },
  computed: {
    /** 滑块绑的是"百分比"，与 scale 分开，避免浮点数在滑块上跳来跳去 */
    scalePercent: {
      get() {
        return Math.round(this.scale * 100)
      },
      set(v) {
        const next = Math.max(MIN_SCALE, Math.min(1, v / 100))
        // ★ 缩放后必须重新夹住位置：缩小时原来贴边的那一侧会露出白边
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
      // 初始比例 = "较短边正好盖住裁剪框"，这样两个方向都不会露白；
      // 若原图比框还小（极小图），则直接按 1:1 显示并居中（放大到糊不如保持原样）
      const coverScale = this.stageSize / Math.min(this.naturalWidth, this.naturalHeight)
      this.scale = Math.min(1, coverScale)
      this.offsetX = (this.stageSize - this.naturalWidth * this.scale) / 2
      this.offsetY = (this.stageSize - this.naturalHeight * this.scale) / 2
      this.clampOffset()
    },
    /**
     * 夹住偏移：图片必须始终**完全盖住**裁剪框（任一边不能露白）。
     * ★ 这是本组件唯一的"硬校验"：边界条件必须显式算出来，
     *   不能靠"用户一般不会拖那么远"。
     */
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
      // ★ setPointerCapture：鼠标拖出组件范围（甚至拖出窗口）时事件仍然回到这里，
      //   否则"拖太快松手在外面"会让图片卡住不跟手。
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
     * 导出：把"裁剪框对应的那块原图"画到离屏 canvas 上，再 toDataURL。
     * ★ 逆变换（显示坐标 → 原图坐标）：
     *     原图坐标 = (显示坐标 - offset) / scale
     *   裁剪框在显示坐标里就是 [0,0,stageSize,stageSize]，所以
     *     sx = -offsetX / scale,  sy = -offsetY / scale,  sSize = stageSize / scale
     *   —— 这里**必须**用同一套 scale/offset，否则导出的画面与用户看到的错位
     *   （显示坐标与导出坐标必须同源，不能当成两套）。
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
        // 理论上到不了这里（同源 data URL 不会污染画布），但真出问题时
        // 让用户看到一句人话，而不是控制台里一个 SecurityError
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
/* 裁剪舞台：正方形 + 超出部分隐藏。★ 尺寸与 data 里的 stageSize 必须一致（320） */
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
  /* 只用 left/top/width/height 定位，不用 transform:
     一是导出时要按同一套数值做逆变换，二者必须完全一致；
     二是 transform 的百分比基准会引入额外的心算负担 */
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
  pointer-events: none;   /* ★ 必须：否则遮罩会吃掉 pointerdown，图片拖不动 */
}

.crop-tip {
  margin: 12px 0 4px 0;
  text-align: center;
  font-size: 13px;
  color: #606266;
}
.crop-tip-dim { color: #909399; }
</style>

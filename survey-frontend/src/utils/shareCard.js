/**
 * 答卷统计「分享图」渲染器（纯 Canvas 2D，不引第三方截图库）。
 *
 * 为什么手绘而不是 html2canvas：截图库要多打 100KB+ 且对 ECharts canvas 支持不稳；
 * 这里只画"标题 + 四个数字 + 一个环 + 图例"，手绘反而更可控、配色也能严格跟随主题。
 *
 * 用法：
 *   const dataUrl = renderStatsCard({
 *     title: '员工满意度调查',
 *     tiles: [{ label: '回收答卷', value: 42, color: '#2f80ed' }, ...],
 *     segments: [{ name: '计入统计', value: 40, color: '#22c55e' }, ...],
 *     generatedAt: '2026-10-18 01:30'
 *   })
 *   // dataUrl 可直接塞进 <img src>，也可用 <a download> 下载
 */

const W = 1080
const H = 1320
const FONT = '"PingFang SC", "Microsoft YaHei", "Helvetica Neue", Arial, sans-serif'
const INK = '#0f172a'
const INK_2 = '#475569'
const INK_3 = '#94a3b8'
const SURFACE = '#f5f9ff'

/** 手绘圆角矩形（Canvas 的 roundRect 在旧内核里不一定有，这里自己画） */
function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2)
  ctx.beginPath()
  ctx.moveTo(x + rr, y)
  ctx.arcTo(x + w, y, x + w, y + h, rr)
  ctx.arcTo(x + w, y + h, x, y + h, rr)
  ctx.arcTo(x, y + h, x, y, rr)
  ctx.arcTo(x, y, x + w, y, rr)
  ctx.closePath()
}

/** 按可用宽度折行（中英混排都按字符折，够用且不会把单词切得难看） */
function wrapText(ctx, text, maxWidth) {
  const chars = String(text || '').split('')
  const lines = []
  let line = ''
  for (const ch of chars) {
    const next = line + ch
    if (ctx.measureText(next).width > maxWidth && line) {
      lines.push(line)
      line = ch
    } else {
      line = next
    }
  }
  if (line) lines.push(line)
  return lines
}

function drawBrandBand(ctx) {
  const bandH = 470
  const g = ctx.createLinearGradient(0, 0, W, bandH)
  g.addColorStop(0, '#56ccf2')
  g.addColorStop(0.55, '#2f80ed')
  g.addColorStop(1, '#2563eb')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, W, bandH)

  // 右上角柔光：让大面积渐变不那么"平"
  const glow = ctx.createRadialGradient(W * 0.92, -80, 0, W * 0.92, -80, 520)
  glow.addColorStop(0, 'rgba(255,255,255,0.38)')
  glow.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, W, bandH)
}

function drawHeader(ctx, title, generatedAt) {
  ctx.textAlign = 'left'
  ctx.fillStyle = 'rgba(255,255,255,0.82)'
  ctx.font = `600 22px ${FONT}`
  ctx.fillText('SURVEY REPORT · 答卷统计', 72, 96)

  ctx.fillStyle = '#ffffff'
  ctx.font = `800 54px ${FONT}`
  const lines = wrapText(ctx, title || '未命名问卷', W - 144).slice(0, 2)
  lines.forEach((line, i) => ctx.fillText(line, 72, 190 + i * 68))

  ctx.fillStyle = 'rgba(255,255,255,0.78)'
  ctx.font = `400 24px ${FONT}`
  // 标题占两行时副标题往下挪一点，避免压在一起
  ctx.fillText(`生成于 ${generatedAt}`, 72, 190 + (lines.length - 1) * 68 + 58)
}

function drawTiles(ctx, tiles) {
  // 左右各留 72、中间 32 间距：(1080 - 72*2 - 32) / 2 = 452，保证两边留白对称
  const cardW = 452
  const cardH = 166
  const gap = 32
  const x0 = 72
  const y0 = 392

  tiles.slice(0, 4).forEach((tile, i) => {
    const col = i % 2
    const row = Math.floor(i / 2)
    const x = x0 + col * (cardW + gap)
    const y = y0 + row * (cardH + gap)

    ctx.save()
    ctx.shadowColor = 'rgba(15, 61, 145, 0.12)'
    ctx.shadowBlur = 28
    ctx.shadowOffsetY = 10
    ctx.fillStyle = '#ffffff'
    roundRect(ctx, x, y, cardW, cardH, 26)
    ctx.fill()
    ctx.restore()

    // 左侧色条
    ctx.fillStyle = tile.color || '#2f80ed'
    roundRect(ctx, x + 28, y + 38, 8, 56, 4)
    ctx.fill()

    ctx.textAlign = 'left'
    ctx.fillStyle = INK_3
    ctx.font = `500 24px ${FONT}`
    ctx.fillText(tile.label || '', x + 56, y + 62)

    ctx.fillStyle = tile.color || INK
    ctx.font = `800 56px ${FONT}`
    ctx.fillText(String(tile.value ?? 0), x + 56, y + 128)
  })
}

function drawDonut(ctx, segments, total) {
  // 环 + 图例作为一组居中：环左 232、图例右约 840，左右留白接近
  const cx = 400
  const cy = 972
  const radius = 148
  const lineWidth = 40

  // 底环
  ctx.beginPath()
  ctx.arc(cx, cy, radius, 0, Math.PI * 2)
  ctx.strokeStyle = '#e8f0fb'
  ctx.lineWidth = lineWidth
  ctx.stroke()

  const sum = segments.reduce((s, x) => s + Number(x.value || 0), 0)
  if (sum > 0) {
    let start = -Math.PI / 2
    segments.forEach(seg => {
      const angle = (Number(seg.value) / sum) * Math.PI * 2
      ctx.beginPath()
      ctx.arc(cx, cy, radius, start, start + angle)
      ctx.strokeStyle = seg.color || '#2f80ed'
      ctx.lineWidth = lineWidth
      ctx.lineCap = 'butt'
      ctx.stroke()
      start += angle
    })
  }

  ctx.textAlign = 'center'
  ctx.fillStyle = INK
  ctx.font = `800 62px ${FONT}`
  ctx.fillText(String(total ?? sum), cx, cy + 12)
  ctx.fillStyle = INK_3
  ctx.font = `400 22px ${FONT}`
  ctx.fillText('回收答卷', cx, cy + 50)
  ctx.textAlign = 'left'
}

function drawLegend(ctx, segments) {
  const x = 640
  let y = 892
  const sum = segments.reduce((s, v) => s + Number(v.value || 0), 0)

  ctx.fillStyle = INK_2
  ctx.font = `600 26px ${FONT}`
  ctx.fillText('回收构成', x, y - 34)

  segments.forEach(seg => {
    ctx.beginPath()
    ctx.arc(x + 11, y + 9, 11, 0, Math.PI * 2)
    ctx.fillStyle = seg.color || '#2f80ed'
    ctx.fill()

    ctx.fillStyle = INK
    ctx.font = `500 26px ${FONT}`
    ctx.fillText(seg.name, x + 36, y + 18)

    const pct = sum > 0 ? Math.round((Number(seg.value) / sum) * 100) : 0
    ctx.fillStyle = INK_3
    ctx.font = `400 24px ${FONT}`
    ctx.fillText(`${seg.value} 份 · ${pct}%`, x + 36, y + 52)

    y += 96
  })

  if (segments.length === 0) {
    ctx.fillStyle = INK_3
    ctx.font = `400 24px ${FONT}`
    ctx.fillText('暂无答卷', x + 36, y + 18)
  }
}

function drawFooter(ctx, generatedAt) {
  ctx.strokeStyle = '#e6eefb'
  ctx.lineWidth = 2
  ctx.beginPath()
  ctx.moveTo(72, H - 132)
  ctx.lineTo(W - 72, H - 132)
  ctx.stroke()

  ctx.fillStyle = INK_3
  ctx.font = `400 22px ${FONT}`
  ctx.fillText(`数据生成于 ${generatedAt}`, 72, H - 86)

  ctx.textAlign = 'right'
  ctx.fillStyle = '#2f80ed'
  ctx.font = `600 22px ${FONT}`
  ctx.fillText('问卷系统 · Survey', W - 72, H - 86)
  ctx.textAlign = 'left'
}

/**
 * 生成分享图。
 * @param {{ title: string, tiles: Array<{label:string,value:number,color:string}>,
 *           segments: Array<{name:string,value:number,color:string}>,
 *           generatedAt: string }} data
 * @returns {string} PNG 的 dataURL
 */
export function renderStatsCard({ title, tiles = [], segments = [], generatedAt = '' }) {
  const canvas = document.createElement('canvas')
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d')

  ctx.fillStyle = SURFACE
  ctx.fillRect(0, 0, W, H)

  drawBrandBand(ctx)
  drawHeader(ctx, title, generatedAt)
  drawTiles(ctx, tiles)

  const total = segments.reduce((s, x) => s + Number(x.value || 0), 0)
  drawDonut(ctx, segments, total)
  drawLegend(ctx, segments)
  drawFooter(ctx, generatedAt)

  return canvas.toDataURL('image/png')
}

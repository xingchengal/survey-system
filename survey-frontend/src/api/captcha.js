/**
 * 滑块验证码接口（后端 CaptchaController，基础路径 /api/captcha）。仅注册页使用。
 *
 * 坐标契约：组件（vue3-slide-verify）回调的 left == blockLeft，成功时 blockLeft ≈ offset，比例是 1；
 * 组件对方块位置的硬校验区间是 [L+10, w−(L+10)]，L = l + 2r + 3（r 默认 10），
 * 后端生成 offset 必须用同一个 L（CaptchaService.createChallenge），否则组件不画缺口。
 * ★ 必须把组件实际用的 w / l 传上来（Register.vue 传 320 / 46），否则判定口径与组件不一致。
 * 两步：slider 取图（返回一次性 token）→ verify 由后端判定；token 在注册时被消费一次。
 * 免登录；后端限流：slider 20 次/分/IP、verify 120 次/分/IP。
 */
import http from './http'

export default {
  /**
   * 取一张滑块验证码。
   * @param {{w?:number, l?:number}} size 组件实际宽度与缺口边长
   * @returns {Promise<{token:string, bgImage:string, sliderImage:string,
   *   canvasWidth:number, canvasHeight:number, blockSize:number,
   *   offset:number, tolerance:number, expiresIn:number, expectedLeft:number}>}
   *   offset 就是缺口左边界，expectedLeft 与它相等（比例 1），判定以后端为准。
   *   ★ bgImage 必须塞进组件的 imgs，否则组件会去 picsum.photos 联网取图，断网即卡在"加载中"。
   *   sliderImage 是服务端挖洞后的留档图，前端不使用。
   */
  slider: (size) => http.get('/api/captcha/slider', size),

  /**
   * 校验一次拖动结果（由后端判定）。
   * @param {{token:string, left:number|null}} data token 为一次性凭证；left 为组件 success 回调的值
   * @returns {Promise<{passed:boolean, expectedLeft:number, actualLeft:number|null,
   *   diff:number|null, invalidLeft?:boolean, fails?:number, maxFails?:number, expired?:boolean}>}
   *   ★ 该接口"HTTP 200 但 passed=false"是正常业务结果（没对准），前端提示重试而不是弹错误。
   *   invalidLeft=true 表示上报的 left 不是有效数值（组件时序不巧会给 NaN），应换图重来。
   */
  verify: (data) => http.post('/api/captcha/verify', null, { params: data, silent: true })
}

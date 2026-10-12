/**
 * 滑块验证码接口（后端 CaptchaController，基础路径 /api/captcha）。仅注册页使用。
 *
 * ══ 坐标契约（★ 结论有源码级依据，改之前先读完这一节）══
 *   · 组件（vue3-slide-verify v1.1.8）内部：
 *       block.value.style.left = blockLeft + "px";      // 拖动过程中持续赋值
 *       const left = block.value.style.left;             // endCb 读它
 *       verify(left, blockX.value, accuracy);            // 成功条件 |blockLeft − offset| ≤ accuracy
 *       emit("success", { left: parseFloat(left) });
 *     ⇒ **回调的 left == blockLeft，而成功时 blockLeft ≈ offset —— 比例是 1**。
 *   · 组件对方块位置有**硬校验区间** [L+10, w−(L+10)]，其中 L = l + 2r + 3。
 *     所以后端生成 offset 必须用**同一个 L**（见 CaptchaService.createChallenge），
 *     否则越界时组件会在 img.onload 里直接 throw、缺口根本不画。
 *
 * ══ 两步式协作 ══
 *   ① slider()  取图：后端画好纯底图（**未挖洞**）并给定 offset，返回一次性 token；
 *      ★ 必须把前端组件实际用的 **w / l** 传上去（r 用默认 10，两端有同名常量），
 *        否则 offset 的生成区间与判定口径会与组件不一致。
 *      ★ 返回的 bgImage 会被 Register.vue 塞进组件的 imgs —— 若不塞（传空数组），
 *        组件会去 picsum.photos **联网取图**，断网即卡在"加载中"。
 *   ② verify()  判定：拖动结束后把组件的 left 上报，由**后端**回答对不对。
 *      ★ 该接口是**唯一一个"HTTP 200 但 passed 为 false"**的接口：
 *        "没对准"是正常业务结果，前端据此提示重试，而不是弹红色错误。
 *   ③ 凭证的消费在注册那一步（后端 user.register 内部 consumeVerifiedOrThrow），
 *      所以"验证通过后改账号/手机号"不需要重拖。
 *
 * ★ 免登录（注册页没有 token）；后端限流：slider 20 次/分/IP、verify 120 次/分/IP。
 */
import http from './http'

export default {
  /**
   * 取一张滑块验证码。
   * @param {{w?:number, l?:number}} size 组件实际宽度与缺口边长（Register.vue 传 320 / 46）
   * @returns {Promise<{token:string, bgImage:string, sliderImage:string,
   *   canvasWidth:number, canvasHeight:number, blockSize:number,
   *   offset:number, tolerance:number, expiresIn:number, expectedLeft:number}>}
   *   ★ offset 就是缺口左边界；expectedLeft 与它**相等**（比例 1），仅供排查与展示，
   *     判定仍以后端为准（前端改这个值没有任何用）。
   *   ★ sliderImage 是服务端"挖洞后"的留档图，**前端不使用**（组件自己按 offset 切图）。
   */
  slider: (size) => http.get('/api/captcha/slider', size),

  /**
   * 校验一次拖动结果（由后端判定）。
   * @param {{token:string, left:number|null}} data token 为一次性凭证；left 为组件 success 回调的值
   * @returns {Promise<{passed:boolean, expectedLeft:number, actualLeft:number|null,
   *   diff:number|null, invalidLeft?:boolean, fails?:number, maxFails?:number, expired?:boolean}>}
   *   ★ invalidLeft 为 true 表示"上报的 left 不是有效数值"（组件在渲染时序不巧时会给出 NaN）——
   *     前端应据此换图重来，而不是当成"用户拖错了"。
   */
  verify: (data) => http.post('/api/captcha/verify', null, { params: data, silent: true })
}

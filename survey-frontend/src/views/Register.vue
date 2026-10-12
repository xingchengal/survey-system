<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <h2 class="auth-title">📝 问卷系统</h2>
      <p class="auth-subtitle">注册新账号</p>

      <!-- ★ 接口返回的错误信息在表单区域展示，不使用弹窗 alert。
           所以服务端失败一律落到这个 alert 里，**不弹 ElMessage.error**。 -->
      <el-alert
        v-if="serverError"
        :title="serverError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input
            v-model="form.username"
            placeholder="账号仅支持字母和数字，4~20个字符"
            size="large"
            maxlength="20"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <!-- show-password 就是"切换显示/隐藏密码"（Element Plus 自带，无需自己写眼睛图标） -->
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6~20 位，建议字母+数字+符号"
            size="large"
            maxlength="20"
            show-password
          />
          <!-- 密码强度提示（弱/中/强） -->
          <div v-if="form.password" class="strength">
            <span class="strength-label">密码强度：</span>
            <div class="strength-bar">
              <i
                v-for="i in 3"
                :key="i"
                class="strength-seg"
                :class="{ on: i <= strength.level }"
                :style="i <= strength.level ? { background: strength.color } : {}"
              />
            </div>
            <span class="strength-text" :style="{ color: strength.color }">{{ strength.text }}</span>
          </div>
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            maxlength="20"
            show-password
          />
        </el-form-item>

        <el-form-item label="手机号（选填）" prop="phone">
          <el-input
            v-model="form.phone"
            placeholder="仅用于信息采集，暂不支持手机号登录"
            size="large"
            maxlength="11"
            clearable
          />
        </el-form-item>

        <!-- ★ 滑块验证（仅注册页启用） -->
        <el-form-item>
          <div class="slider-wrap">
            <!--
              ★★ props 名必须用 w / h：组件源码里的 props 是 w / h / l / r，
                写成 :width/:height 等于没传，组件会按**默认 310×155** 渲染而后端画布是 320×160，
                两套尺寸不一致会让 offset 的语义整体偏移。
              ★ :key="sliderToken"：每次取到新凭证就**整体重建组件**。
                组件的底图/缺口位置是异步 onload 后画的，重建能彻底规避
                "新 offset 配旧底图"这类竞态（换图后拖了却判不过，多半就是它）。
            -->
            <slide-verify
              ref="slideRef"
              :key="sliderToken || 'captcha'"
              :w="sliderW"
              :h="sliderH"
              :l="sliderL"
              :r="sliderR"
              :imgs="sliderImgs"
              :offset="sliderOffset"
              :accuracy="accuracy"
              slider-text="向右滑动完成验证"
              @success="onSliderSuccess"
              @fail="onSliderFail"
              @again="onSliderAgain"
            />
            <p class="slider-tip">
              <span v-if="sliderPassed" class="ok">✅ 验证通过</span>
              <span v-else-if="sliderLoading" class="text-gray">验证码加载中…</span>
              <span v-else-if="sliderChecking" class="text-gray">正在校验…</span>
              <span v-else class="text-gray">拖动滑块完成验证</span>
            </p>
          </div>
        </el-form-item>

        <el-form-item>
          <!-- 提交按钮的置灰条件：表单校验不通过 / 滑块未验证通过；提交过程中显示加载状态防重复提交 -->
          <el-button
            class="w-full"
            type="primary"
            size="large"
            :disabled="!canSubmit"
            :loading="submitting"
            @click="handleRegister"
          >
            {{ submitting ? '注册中…' : '注 册' }}
          </el-button>
        </el-form-item>

        <div class="auth-footer">
          <span class="text-gray">已有账号？</span>
          <el-button type="primary" link @click="$router.push('/login')">去登录</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import SlideVerify from 'vue3-slide-verify'
// ★ 组件样式必须单独引（该库"只提供局部引用的方式，不再采用插件形式，方便按需加载"）
import 'vue3-slide-verify/dist/style.css'
import api from '../api'

/** 账号规则：字母+数字，4~20 位（与后端 RegisterRequest 的 @Pattern **必须**一致） */
const ACCOUNT_RE = /^[A-Za-z0-9]{4,20}$/
/** 国内手机号：11 位、1 开头、第二位 3~9（与后端 @Pattern 一致；空值合法） */
const PHONE_RE = /^1[3-9]\d{9}$/

export default {
  name: 'RegisterView',
  components: { SlideVerify },
  data() {
    // 确认密码的校验依赖 form.password，所以写成函数形式（每次校验都读最新值）
    const validateConfirm = (rule, value, callback) => {
      if (!value) return callback(new Error('请再次输入密码'))
      if (value !== this.form.password) return callback(new Error('两次输入的密码不一致'))
      callback()
    }
    const validatePhone = (rule, raw, callback) => {
      // 选填：空了就放过
      // ★ 先 trim 再判：手机号是纯数字框，"粘贴后又删一半"很容易留下空格。
      //   不 trim 的话，用户只打了个空格就会被判成"请输入 11 位国内手机号"，
      //   而那个空格其实等于"没填"（选填项）—— 与后端 ContactRequest 的 `^\s*$` 放行口径对齐。
      const value = String(raw == null ? '' : raw).trim()
      if (!value) return callback()
      if (!PHONE_RE.test(value)) return callback(new Error('请输入 11 位国内手机号'))
      callback()
    }

    return {
      form: { username: '', password: '', confirmPassword: '', phone: '' },
      rules: {
        username: [
          { required: true, message: '请输入账号', trigger: 'blur' },
          { pattern: ACCOUNT_RE, message: '账号仅支持字母和数字，4~20个字符', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '密码长度需在 6~20 位之间', trigger: 'blur' }
        ],
        confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
        phone: [{ validator: validatePhone, trigger: 'blur' }]      },

      submitting: false,
      /** 服务端返回的错误文案（页内展示，不弹窗） */
      serverError: '',

      // ===== 滑块验证状态 =====
      /**
       * 组件尺寸。★ 必须与后端**同一个口径**：
       * 组件的 success 回调返回的 left 就是方块位置（成功时 ≈ 后端下发的 offset，比例 1），
       * 而组件对方块位置有硬校验区间 [L+10, w−(L+10)]，L = l + 2r + 3 ——
       * 所以后端必须知道前端实际用的 w/l/r，才能生成合法 offset、并判得准。
       * 因此取图时把这几个值带上去（见 loadCaptcha），其中 r 显式传 10。
       */
      sliderW: 320,
      sliderH: 160,
      sliderL: 46,
      /**
       * ★ 组件方块圆角半径（与后端 COMPONENT_R 必须一致）：
       *   组件内部方块真实边长 L = l + 2r + 3，后端生成 offset 的合法区间
        *   [L+10, w−(L+10)] 就是按同一个 L 算的 —— 两边不一致就会越界。
       */
      sliderR: 10,
      /** 后端下发的缺口位置：交给组件的 offset 属性（组件据此摆好缺口，用户再拖） */
      sliderOffset: 0,
      /** 一次性凭证（后端签发，60 秒有效、注册时消费） */
      sliderToken: '',
      /** ★ 只有**后端**判定通过才置 true（组件只按它自己的缺口判定，不能代表后端认定了"对准"） */
      sliderPassed: false,
      sliderLoading: false,
      /** 正在调后端判定（避免用户在这一瞬间重复点） */
      sliderChecking: false,
      /** 组件自带的"非人为操作"检测精度（默认 5；用 3 更灵敏，触发 again 后自动换图） */
      accuracy: 3,
      /**
       * ★★ 背景图数组：必须填入**后端下发的纯底图**（data URL）。
       *   为什么不能留空：组件源码里 `imgs.length > 0 ? imgs[…] : "https://picsum.photos/…"`
       *   —— 空数组等于让它去**第三方图床联网取图**：断网时 img.onload 一直不触发，
       *      组件就会停在"加载中"（据源码分析；本机未做断网实验），
       *   而且把验证码图托管到外网本身就不该发生。
       *   loadCaptcha 会把 bgImage 填进来，组件按后端给的 offset 在这张图上切拼图，
       *   **页面看到的图 = 后端生成的图**，与服务端判定用的是同一张。
       */
      sliderImgs: []
    }
  },
  computed: {
    /**
     * 提交按钮是否可用。
     * ★ 两条叠加：① 表单校验不通过 → 置灰；② 滑块没过 → 置灰。
     *   表单是否"校验通过"没法直接用 computed 拿到，所以这里用**字段自身的规则**做一次轻量判断
     *   （与 rules 里的规则同源，改规则时两处一起改会有遗漏风险 —— 所以下面刻意写成同一个正则）。
     */
    canSubmit() {
      if (this.submitting) return false
      if (!this.sliderPassed) return false
      const f = this.form
      if (!ACCOUNT_RE.test(f.username)) return false
      if (!f.password || f.password.length < 6 || f.password.length > 20) return false
      if (f.password !== f.confirmPassword) return false
      if (f.phone && !PHONE_RE.test(f.phone)) return false
      return true
    },
    /**
     * 密码强度（弱/中/强）。
     * 判定：先按"字符种类数"（小写/大写/数字/符号）与长度打分，再归到三档。
     * ★ 这只是**给用户的提示**，不是安全门槛 —— 真正的门槛是后端的 6~20 位长度校验。
     */
    strength() {
      const p = this.form.password || ''
      if (!p) return { level: 0, text: '', color: '#909399' }

      let kinds = 0
      if (/[a-z]/.test(p)) kinds++
      if (/[A-Z]/.test(p)) kinds++
      if (/\d/.test(p)) kinds++
      if (/[^A-Za-z0-9]/.test(p)) kinds++

      let level = 1
      if (p.length >= 8 && kinds >= 2) level = 2
      if (p.length >= 10 && kinds >= 3) level = 3

      const map = {
        1: { level: 1, text: '弱', color: '#f56c6c' },
        2: { level: 2, text: '中', color: '#e6a23c' },
        3: { level: 3, text: '强', color: '#67c23a' }
      }
      return map[level]
    }
  },
  mounted() {
    this.loadCaptcha()
  },
  methods: {
    /**
     * 取一张新的滑块验证码。
     *
     * ★ 调用时机（四条都有理由，不要随意增减）：
     *   ① mounted（页面加载/刷新）；
     *   ② 拖动失败（onSliderFail）/ 判定未通过 / left 非法；
     *   ③ 判定接口报错（网络/429 除外：429 时不换图，否则换图又消耗取图额度）；
     *   ④ 提交返回业务报错后（滑块验证状态失效，必须重新验证）。
     *   ★ 不做"连续失败 N 次才刷新"的计数：界面上不出现失败次数，而且每次失败都已换图，
     *     计数规则等价于把 N 次并成 1 次。
     *   ★ 组件自带的刷新图标（@refresh）**没接**：组件源码里 refresh() 只做
     *     `{ reset(); emit("refresh") }` —— 它**只把滑块复位，并不重新取图**。
     *     所以"点组件的刷新图标 → 换一张图"实际是靠本方法（:key 重建 + 新 bgImage）实现的；
     *     若同时监听 @refresh 再调本方法，会白多打一次 /slider（多消耗一次限流额度）。
     */
    async loadCaptcha() {
      this.sliderLoading = true
      this.sliderPassed = false
      this.sliderToken = ''
      try {
        // ★ 把组件实际用的 w / l 带上：后端要用同一套坐标算"拖到缺口时 left 应该是多少"。
        //   不带也能跑（后端有默认值），但那样两套渲染尺寸不一致时判定会偏。
        const data = await api.captcha.slider({ w: this.sliderW, l: this.sliderL })
        // ★ 顺序有讲究：先把底图与 offset 都准备好，最后再改 sliderToken ——
        //   因为 :key="sliderToken" 会**重建组件**，重建时它读到的必须已经是新图 + 新 offset。
        this.sliderImgs = [data.bgImage]
        this.sliderOffset = data.offset
        this.sliderToken = data.token
        // 让组件按新的 offset 重新摆位（refresh() 会重置滑块与卡片位置）
        this.$nextTick(() => {
          if (this.$refs.slideRef) this.$refs.slideRef.refresh()
        })
      } catch {
        // 取图失败：http.js 已弹过红字（该接口没传 silent）。
        // 这里只把状态摆在"验证未通过"，于是提交按钮保持置灰 —— 不会让用户提交一个必然被拒的表单。
        this.serverError = '验证码加载失败，请点击刷新或稍后重试'
      } finally {
        this.sliderLoading = false
      }
    },

    /**
     * 拖动结束（组件回调）—— ★ 但"是否真的对齐"由**后端**说了算。
     *
     * @param {{timestamp:number, left:number}} detail left 就是方块位置（成功时 ≈ 后端下发的 offset）
     *
     * ★★ 为什么"过没过"必须由后端说了算：组件只比较**它自己的**缺口与拖动位置，
     *   完全不知道后端下发的是哪个 offset。否则会出现两种坏情况：
     *     ① offset 越界时组件连缺口都不画（它在 img.onload 里直接 throw），用户怎么拖都过不了；
     *     ② 前端点亮了按钮、后端却判"没对准" → 报「验证未通过 / 参数格式不正确」。
     *   所以：前端只上报 left，**判定在后端**（与下发 offset 同一口径），前端按结果点亮按钮。
     */
    async onSliderSuccess(detail) {
      // ★★ 必须用 Number.isFinite，不能用 typeof === 'number'：
      //   vue3-slide-verify 的回调给的是 parseFloat(block.style.left)，
      //   在渲染时序不巧时 style.left 为空 → parseFloat("") = **NaN**，
      //   而 `typeof NaN === 'number'` **为真** → 会把 NaN 原样放行 →
      //   被 http.js 序列化成字符串 "NaN" → 后端报「参数「left」格式不正确」。
      //   所以 NaN 必须挡在这里，走"重新加载验证码"的正常路径。
      const rawLeft = detail && detail.left
      const left = Number.isFinite(rawLeft) ? rawLeft : null
      if (left === null) {
        // ★ 这一支不是"理论上不会发生"：组件读 block.style.left，
        //   该值只在拖动回调里被赋值，拿不到时 parseFloat("") 就是 NaN（据源码定位）。
        //   必须重新加载验证码，否则用户会卡在"拖了但按钮一直是灰的"且毫无提示。
        console.warn('[滑块] 组件回调未给出有效 left，已重新加载验证码。detail =', detail)
        this.serverError = '这次拖动没被正确识别，已刷新验证码，请重新拖动'
        this.sliderPassed = false
        await this.loadCaptcha()
        return
      }
      this.sliderChecking = true
      try {
        const r = await api.captcha.verify({ token: this.sliderToken, left })
        if (r && r.passed) {
          this.sliderPassed = true
          this.serverError = ''
          // 通过后立刻校验一遍表单，让"哪一项不合法"的红字马上显出来
          if (this.$refs.formRef) this.$refs.formRef.validate().catch(() => {})
        } else if (r && r.invalidLeft) {
          // 后端明确说"收到的 left 不合法"（NaN/缺失这类）→ 换题重来
          this.serverError = '这次拖动没被正确识别，已刷新验证码，请重新拖动'
          this.sliderPassed = false
          await this.loadCaptcha()
        } else {
          // 后端说没对上：立刻换一张图让用户重试
          this.sliderPassed = false
          await this.loadCaptcha()
        }
      } catch (e) {
        // 判定接口失败：保持"未通过"并换图。
        // ★★ 必须把**真实原因**说出来：把 429（拖太快/限流）与其它失败吞成同一句话，
        //   用户与排查者都无从下手。按状态码分流：
        //     · 429 → 明确告诉用户"太频繁"，等一会儿再试（且**不要**立刻自动换图，
        //             否则换图本身又是一次请求，只会把窗口继续撑满）；
        //     · 其它 → 保留后端 message（如"滑块验证已失效，请重新拖动"）。
        //   （api.captcha.verify 传了 silent，http.js 不会弹红字，文案只能由这里给。）
        const status = e && e.status
        if (status === 429) {
          this.serverError = '操作过于频繁，请稍等几秒再拖动滑块'
        } else {
          this.serverError = (e && e.message) || '校验失败，已重新加载验证码，请再拖一次'
          await this.loadCaptcha()   // 凭证这类问题（失效/过期）→ 换题重来才有意义
        }
        this.sliderPassed = false
      } finally {
        this.sliderChecking = false
      }
    },

    /**
     * 拖动失败（组件自己判定没对上）。
     * ★ 不做"连续拖拽失败 3 次才刷新"的计数：界面上不显示失败次数，
     *   而且**每次失败都会换图**（见下），计数规则等价于把 3 次并成 1 次。
     */
    onSliderFail() {
      this.sliderPassed = false
      this.loadCaptcha()
    },

    /** 组件判定为"非人为操作"（脚本/匀速拖动）→ 换题重来。 */
    onSliderAgain() {
      this.sliderPassed = false
      this.loadCaptcha()
    },

    async handleRegister() {
      this.serverError = ''
      // 1) 表单校验（按钮虽然置灰了，但键盘回车等路径仍可能进来，所以再挡一次）
      try {
        await this.$refs.formRef.validate()
      } catch {
        return
      }
      // 2) 滑块：这里只判断"后端是否已判定通过"（sliderPassed **只由后端结果**设置）
      if (!this.sliderPassed || !this.sliderToken) {
        this.serverError = '请先完成滑块验证'
        return
      }

      this.submitting = true
      try {
        // ★ 只带凭证 token：判定已在 /api/captcha/verify 做完，
        //   注册接口只认"这个凭证已被标记为通过"，然后消费它。
        await api.user.register(
          {
            username: this.form.username,
            password: this.form.password,
            nickname: this.form.username, // 昵称留空时后端会用账号兜底；这里显式传显得更明确
            // ★ trim 后再发：与上面的 validatePhone 同一口径，也与后端 UserService.register
            //   的 `phone.trim()` 一致；只打空格 = 不填（后端会把空串归一成 null）
            phone: this.form.phone.trim()
          },
          { token: this.sliderToken }
        )
        ElMessage.success('注册成功，即将跳转到登录页')
        setTimeout(() => this.$router.push('/login'), 900)
      } catch (e) {
        // ★ 错误信息在表单区域展示：api.user.register 传了 silent:true → http.js 不弹 ElMessage，
        //   但**错误信息照常挂在 ApiError.message 上**（silent 只抑制弹窗，不吞信息），
        //   所以下面直接用 e.message 渲染到页内 el-alert。
        this.serverError = (e && e.message) || '注册失败，请稍后重试'
        // 提交返回业务报错后，滑块验证状态失效，需要重新完成验证
        await this.loadCaptcha()
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 24px 0;
}
.auth-card {
  width: 440px;
  padding: 8px 12px;
}
.auth-title {
  text-align: center;
  margin: 0 0 4px 0;
}
.auth-subtitle {
  text-align: center;
  margin: 0 0 20px 0;
  color: #909399;
  font-size: 14px;
}
.auth-footer {
  text-align: center;
  font-size: 14px;
}
.mb-16 {
  margin-bottom: 16px;
}
.strength {
  display: flex;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
}
.strength-label {
  color: #909399;
}
.strength-bar {
  display: flex;
  gap: 4px;
  margin: 0 8px;
}
.strength-seg {
  display: block;
  width: 28px;
  height: 6px;
  border-radius: 3px;
  background: #e4e7ed;
}
.strength-text {
  font-weight: 600;
}
.slider-wrap {
  width: 100%;
}
.slider-tip {
  margin: 8px 0 0 0;
  font-size: 12px;
  color: #909399;
}
.slider-tip .ok {
  color: #67c23a;
  font-weight: 600;
}
</style>

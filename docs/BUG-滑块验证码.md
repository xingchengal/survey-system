# 滑块验证码 BUG 排查与完整修复记录

> 范围：注册页「滑动组块」= 滑块人机验证（**不是题型**，本系统题型仅单选/多选/填空）。
> 涉及文件：
> - 后端：`service/CaptchaService.java`、`controller/CaptchaController.java`
> - 前端：`views/Register.vue`、`api/captcha.js`
> - 组件源码：`node_modules/vue3-slide-verify/dist/vue3-slide-verify.es.js`（v1.1.8）

---

## 一、结论速览

整条链路的核心问题是：**后端与 `vue3-slide-verify` 组件之间的「坐标 / 取值契约」对不上**。
表现为「拖对了却验证不通过、反复换图、断网时滑块卡死」，且每次重取都是新随机值，时好时坏，
调容差公式无法根治。共三个 Bug：

| # | 现象 | 根因 | 影响 | 修复 |
|---|------|------|------|------|
| 1 | 约 22% 概率怎么拖都过不了、反复刷新 | 后端 offset 取值 `[56,264]` 超出组件合法区间 `[79,241]`，越界时组件 `throw` | 必现·高危 | offset 改用组件 `L = l + 2r + 3` 计算合法区间 |
| 2 | 断网/网络抖动时滑块卡在「加载中」不可用 | 前端 `sliderImgs=[]` → 组件去 `picsum.photos` 联网取图；后端画的图从未显示 | 必现·高危 | 前端喂后端纯底图 data URL；后端返回未画洞的背景 |
| 3 | 判定偏差方向不可控 | `expectedFrontendLeft` 用了 `offset×w/(w−20)≈×1.067`，方向错误 | 次要 | 成功即 `left≈offset`，期望值直接用 offset，容差固定 12px |

---

## 二、组件源码的真实行为（决定性证据）

摘自 `vue3-slide-verify.es.js`（v1.1.8）：

### ① `offset` 有硬性取值校验（第 292–301 行）
```js
const L = l + r * 2 + 3;                       // 组件方块真实边长
if (offset > 0) {
  if (offset >= L + 10 && offset <= w - (L + 10)) {
    blockX.value = offset;                     // 合法才摆缺口
  } else {
    throw new Error(`offset must be ...`);     // ★ 越界直接抛异常，缺口根本不画
  }
} else {
  blockX.value = getRandomNumberByRange(L + 10, w - (L + 10));
}
```

### ② `success` 回调返回的 `left` = 方块位置，成功即 `left ≈ offset`（第 243、249–256 行）
```js
let blockLeft = (w - 40 - 20) / (w - 40) * moveX;
block.value.style.left = blockLeft + "px";
// ...
const left = block.value.style.left;            // ★ 返回 blockLeft，不是 moveX
verify(left, blockX.value, accuracy);            // 成功条件 |blockLeft - offset| ≤ accuracy
emit("success", { timestamp, left: parseFloat(left) });   // left ≈ offset
```

### ③ `imgs` 为空 → 联网取第三方图（第 39–42 行）
```js
const len = imgs.length;
return len > 0 ? imgs[...]
  : "https://picsum.photos/300/150?image=" + random;   // ★ 空数组 = 走远程
```

---

## 三、逐 Bug 分析

### BUG #1 — offset 取值范围不兼容（必现·高危）
- 前端 props：`w=320, l=46, r=10(默认)` → 组件 `L = 46 + 10×2 + 3 = 69`，
  合法 offset 区间 `[L+10, w-(L+10)] = [79, 241]`。
- 后端生成（`MIN_OFFSET/MAX_OFFSET`）：`offset ∈ [56, 264]`。
- 落在 `[56,78] ∪ [242,264]`（约占 22%）时组件在 `img.onload` 里 `throw` → 缺口不画、`blockX` 停在 0
  → 用户怎么拖都过不了 → 自动换图 → 再随机 → 可能再越界 → 死循环。
- 根因：后端画自己的图用「方块=46、无 r 凸起」坐标，组件方块是 `L = l + 2r + 3 = 69`，两套边长不一致。

### BUG #2 — 前端联网取图 + 后端图成死代码（必现·高危）
- `sliderImgs: []` 实际让组件去 `picsum.photos` 联网取图（违背「离线可用」）。
- 断网：`img.onerror` 不断换远程地址重试 → `onload` 永不触发 → `blockX` 停在 0、`loadBlock` 转圈 → 不可用。
- 后端 `drawBackground()/cutPuzzle()/bgImage/sliderImage` 及 `java.awt.headless` 前端从未显示，全为死重量。

### BUG #3 — `expectedFrontendLeft` 换算方向错（次要）
- 组件 emit 的 `left = blockLeft`，成功条件就是 `|blockLeft - offset| ≤ accuracy`，故 `left ≈ offset`（比例应为 1）。
- 后端却用 `offset × w/(w−20) ≈ ×1.067`；代码里「实测 left≈200」与本版本源码矛盾（疑旧版本/误测）。

---

## 四、后端完整补丁（`CaptchaService.java`）

### 4.1 删除旧的两个 offset 常量
```java
// 删除以下两行（原第 102-103 行，区间错误导致 BUG #1）
// private static final int MIN_OFFSET = BLOCK_SIZE + 10;
// private static final int MAX_OFFSET = CANVAS_W - BLOCK_SIZE - 10;
```

> `COMPONENT_R` 在类中**已定义**（`private static final int COMPONENT_R = 10;`，第 96 行），直接复用，勿重复声明。

### 4.2 `createChallenge` —— 完整替换版
```java
/**
 * 生成一张新的滑块验证码。
 * ★ 修复（BUG #1）：offset 必须落在组件真实合法区间 [L+10, w-(L+10)]，L = l + 2r + 3。
 * ★ 修复（BUG #2）：返回的是【未画洞】的纯背景，交给组件按 offset 自己切拼图（前端喂本地图，离线可用）。
 *
 * @return {token, bgImage, sliderImage, canvasWidth, canvasHeight, blockSize, offset, tolerance, expiresIn, expectedLeft}
 */
public Map<String, Object> createChallenge(Integer frontendW, Integer frontendL) {
    boolean prevHeadless = GraphicsEnvironment.isHeadless();
    // 保险：即使 yaml 忘了配 headless，这里也保证不因为"没有显示器"而崩
    System.setProperty("java.awt.headless", "true");

    // 前端可以把"它实际用的组件宽/缺口边长"带上来（0/负数/超范围一律按默认值处理）。
    int cw = (frontendW != null && frontendW >= 200 && frontendW <= 500) ? frontendW : COMPONENT_W;
    int cl = (frontendL != null && frontendL >= 20 && frontendL <= 100) ? frontendL : COMPONENT_L;

    // ★★ 关键修复（BUG #1）：与组件源码对齐 —— 方块真实边长 L = l + 2r + 3，
    //   offset 合法区间 = [L+10, w-(L+10)]，越界组件会在 img.onload 里 throw，缺口不画。
    int L = cl + COMPONENT_R * 2 + 3;
    int minOffset = L + 10;
    int maxOffset = cw - (L + 10);
    if (maxOffset < minOffset) {
        throw new IllegalStateException(
                "滑块画布尺寸不合法：w=" + cw + " l=" + cl + " 需要 w > 2*(l+2r+13)");
    }
    int offset = minOffset + random.nextInt(maxOffset - minOffset + 1);
    int y = MIN_Y + random.nextInt(Math.max(1, CANVAS_H - BLOCK_SIZE - MIN_Y * 2));
    int pieceType = random.nextInt(3);   // 0=方形 1=圆形 2=圆角

    String token = randomToken();
    Challenge challenge = new Challenge(offset, y, System.currentTimeMillis() + TTL_MS, pieceType, cw, cl);
    challenges.put(token, challenge);

    Map<String, Object> result = new LinkedHashMap<>();
    try {
        BufferedImage full = drawBackground();
        // ★ 修复（BUG #2）：这张 full 此刻还没画洞，是【纯背景】→ 交给组件按 offset 自己切拼图。
        //   组件用 drawImage(img,0,0,w,h) 渲染，且后端画布 cw×ch 已对齐组件 w×h（320×160），坐标一致。
        result.put("bgImage", toDataUrl(full));

        BufferedImage slider = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
        cutPuzzle(full, slider, offset, y, pieceType);
        // sliderImage / 画了洞的 full 现在仅服务端留档，前端不再使用
        result.put("sliderImage", toDataUrl(slider));
    } catch (Exception e) {
        // AWT 不可用属于系统级故障：抛出去让全局处理器记异常日志
        challenges.remove(token);
        throw new IllegalStateException("验证码生成失败（AWT 不可用）", e);
    } finally {
        if (!prevHeadless) {
            LOG.debug("滑块验证码：headless 属性由 false 被临时置为 true");
        }
    }

    result.put("token", token);
    result.put("canvasWidth", CANVAS_W);
    result.put("canvasHeight", CANVAS_H);
    result.put("blockSize", BLOCK_SIZE);
    result.put("offset", offset);
    result.put("tolerance", TOLERANCE_MIN);
    result.put("expiresIn", TTL_MS / 1000);
    // ★ 修复（BUG #3）：期望 left 直接等于 offset（成功即 blockLeft≈offset）
    result.put("expectedLeft", expectedFrontendLeft(challenge));
    return result;
}
```

### 4.3 `verify` —— 容差改为固定下限（完整替换版）
```java
public Map<String, Object> verify(String token, String rawLeft, Integer userId, String ip) {
    Challenge c = requireAlive(token);

    Map<String, Object> out = new LinkedHashMap<>();
    Integer left = parseLeft(rawLeft);
    if (left == null) {
        out.put("passed", false);
        out.put("expectedLeft", expectedFrontendLeft(c));
        out.put("actualLeft", null);
        out.put("diff", null);
        out.put("invalidLeft", true);
        LOG.info("滑块判定收到非法 left：raw={} token={}", rawLeft, mask(token));
        return out;
    }

    int expected = expectedFrontendLeft(c);       // == c.offset（BUG #3 修正后）
    int diff = Math.abs(left - expected);
    // ★ 修复（BUG #3）：容差改为固定下限，不再随 offset 缩放。
    //   组件自身 accuracy(前端设 3) 已保证 |left-offset| ≤ 3，这里留 TOLERANCE_MIN=12 兜渲染取整误差。
    int tolerance = TOLERANCE_MIN;
    boolean passed = diff <= tolerance;

    out.put("passed", passed);
    out.put("expectedLeft", expected);
    out.put("actualLeft", left);
    out.put("diff", diff);

    if (passed) {
        c.verified = true;
        writeLog("滑块验证", "滑块验证成功（偏差 " + diff + "px）", userId, ip);
    } else {
        int fails = ++c.fails;
        out.put("fails", fails);
        out.put("maxFails", MAX_FAILS);
        LOG.info("滑块验证未通过：上报 left={} 期望 left={} 偏差={}px 容差={}px 第{}次",
                left, expected, diff, tolerance, fails);
        writeLog("滑块验证", "滑块验证失败（偏差 " + diff + "px，第 " + fails + " 次）", userId, ip);
        if (fails >= MAX_FAILS) {
            challenges.remove(token);
            out.put("expired", true);
        }
    }
    return out;
}
```

### 4.4 `expectedFrontendLeft` —— 完整替换版
```java
/**
 * 组件 success 回调返回的期望 left。
 * ★ 修复（BUG #3）：本版本组件 emit 的 left = block.style.left = blockLeft，
 *   而它内部成功条件就是 |blockLeft - offset| ≤ accuracy，所以成功时 left ≈ offset（比例应为 1）。
 *   旧实现 offset × w/(w-20)（≈×1.067）方向搞反，是靠大容差才没爆；现在直接用 offset。
 */
private int expectedFrontendLeft(Challenge c) {
    return c.offset;
}
```

---

## 五、前端完整补丁（`Register.vue`）

### 5.1 模板：给组件加 `:key`、`r` 与底图绑定
```html
<el-form-item>
  <div class="slider-wrap">
    <slide-verify
      ref="slideRef"
      :key="sliderToken || 'captcha'"
      :l="sliderL"
      :r="sliderR"
      :w="sliderW"
      :h="sliderH"
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
```
> 注意：原模板写的是 `:width` / `:height`，而组件真实 props 是 `w` / `h`（见源码 props 定义）。
> 这里一并改成 `:w` / `:h`，并新增 `:r="sliderR"`（默认 10，与后端 COMPONENT_R 对齐）。
> `:key="sliderToken"` 让每次取到新凭证整体重建组件，彻底规避「底图/offset 未同步」的竞态。

### 5.2 data()：新增 `sliderR`，`sliderImgs` 改为承载后端底图
```javascript
      // ===== 滑块验证状态 =====
      sliderW: 320,
      sliderH: 160,
      sliderL: 46,
      sliderR: 10,                       // ★ 新增：与组件默认 r、后端 COMPONENT_R 一致
      /** 后端下发的缺口位置：交给组件的 offset 属性（组件据此摆好缺口，用户再拖） */
      sliderOffset: 0,
      /** 一次性凭证（后端签发，60 秒有效、注册时消费） */
      sliderToken: '',
      sliderPassed: false,
      sliderLoading: false,
      sliderChecking: false,
      accuracy: 3,
      /**
       * ★ 修复（BUG #2）：背景图数组。必须放入后端下发的纯底图（data URL）。
       *   传空数组会让组件去 picsum.photos 联网取图（断网即卡死），因此禁止留空。
       */
      sliderImgs: []
```

### 5.3 `loadCaptcha`：喂后端底图 + 重建组件
```javascript
    async loadCaptcha() {
      this.sliderLoading = true
      this.sliderPassed = false
      this.sliderToken = ''
      try {
        const data = await api.captcha.slider({ w: this.sliderW, l: this.sliderL })
        this.sliderToken = data.token
        // ★ 先设底图、再设 offset：组件 onload 时读最新 offset 摆缺口，两者必须同批就绪
        this.sliderImgs = [data.bgImage]
        this.sliderOffset = data.offset
        // sliderToken 变化会通过 :key 触发组件重建；此处保留 refresh 作为无 key 场景的兜底
        this.$nextTick(() => {
          if (this.$refs.slideRef) this.$refs.slideRef.refresh()
        })
      } catch {
        this.serverError = '验证码加载失败，请点击刷新或稍后重试'
      } finally {
        this.sliderLoading = false
      }
    },
```

### 5.4 `onSliderSuccess`（保持原逻辑，确认 left 直接可比）
> 修复后组件 emit 的 `left ≈ offset`，`api.captcha.verify({ token, left })` 原样上报即可，
> 后端 `expectedFrontendLeft` 已按 `offset` 判定，无需在前端做任何换算。**前端不要自己加缩放。**

---

## 六、`api/captcha.js` 注释勘误（可选，纯文档）
原文注释写「left ≈ offset × (w−40)/(w−60)」的换算，已不成立；修复后应记为「success 回调的 left ≈ 后端 offset（比例 1）」。代码逻辑本身不用改（仍是 `slider()` 取图、`verify()` 上报）。

---

## 七、建议实施顺序

1. **先上 #1 + #3（纯后端）** → 立刻消掉约 22% 崩溃的图，最快见效。
2. 再上 **#2（前端喂后端纯底图 + `:key` 重建 + `:w/:h/:r` 修正）** → 解决离线卡死与视觉/判定一致性。
3. 回归验证：
   - 连取 20+ 张验证码，均应正常显示缺口且能拖过；
   - 断网/弱网下不再卡在「加载中」；
   - `t_operation_log` 中「滑块验证成功」偏差普遍 ≤ 12px。

---

## 八、附：坐标契约对照表（防回归参考）

| 量 | 组件 (vue3-slide-verify v1.1.8) | 后端/前端应遵循 |
|----|--------------------------------|----------------|
| 方块边长 | `L = l + 2r + 3`（本项目 `46+20+3=69`） | 用同一 `L` 计算 offset 边界 |
| 合法 offset | `[L+10, w-(L+10)]` → 本项目 `[79, 241]` | 后端生成区间必须等于该区间 |
| success 返回 | `left = blockLeft ≈ offset` | 期望值取 `offset`（比例 1），容差 ≥ accuracy |
| props 名称 | `l / r / w / h / offset / imgs / accuracy` | 前端用 `:w/:h`（非 width/height），并显式传 `:r` |
| 背景图 | `imgs` 非空用本地；空则联网 picsum | 前端必须喂后端纯底图 data URL，禁用空数组 |
| 画布尺寸 | `w×h`（320×160） | 后端 `CANVAS_W×CANVAS_H=320×160` 必须一致 |
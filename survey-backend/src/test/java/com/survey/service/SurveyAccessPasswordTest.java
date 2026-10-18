package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Survey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答题密码的**两条口径**：
 *   · 编辑器（作者/管理员读自己的问卷）→ 免口令；
 *   · 填写页（含作者自己打开分享链接、匿名访客、其他登录用户）→ 一律比口令。
 *
 * ── 为什么这两条必须分开 ─────────────────────────────
 *   2026-10-18 事故链：
 *   ① 一开始两条不分 → 作者设了密码，编辑器读不到题目，一保存把 7 题全删了；
 *   ② 改成"按身份一刀切免口令" → 填写页判断"口令对不对"的方式本来就是
 *      "能不能读到题目"，于是作者随手输个错口令也能过门，
 *      到提交答卷（匿名入口、没有身份）才 400 —— 用户看到的是"密码失效了，但提交要密码"。
 *   所以豁免必须**只挂在编辑器这条路径**上，判据是 X-Survey-Editor 请求头。
 *
 * ── 关于 mock ─────────────────────────────────────────
 *   被测方法是纯逻辑（只看 Survey 实体 + 身份参数），不碰 Mapper，
 *   所以直接 new 出 Service、不启动 Spring 容器。
 */
class SurveyAccessPasswordTest {

    private final SurveyService surveyService = new SurveyService();

    private static final Integer OWNER_ID = 466;

    private static Survey locked() {
        Survey s = new Survey();
        s.setId(874);
        s.setUserId(OWNER_ID);
        s.setAccessPassword("111");
        return s;
    }

    // ==================== 填写页口径（不带编辑器头）====================

    @Test
    @DisplayName("填写页：没设密码 → 任何人放行")
    void noPasswordPassesEveryone() {
        Survey s = new Survey();
        s.setUserId(OWNER_ID);
        assertDoesNotThrow(() -> surveyService.requireAccessPassword(s, null));
        assertDoesNotThrow(() -> surveyService.requireAccessPassword(s, "随便"));
    }

    @Test
    @DisplayName("★ 填写页：作者自己也要口令（否则密码门形同虚设）")
    void ownerStillNeedsPasswordOnFillPage() {
        assertThrows(BizException.class, () -> surveyService.requireAccessPassword(locked(), null));
        assertThrows(BizException.class, () -> surveyService.requireAccessPassword(locked(), ""));
    }

    @Test
    @DisplayName("填写页：匿名访客 → 400")
    void anonymousIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> surveyService.requireAccessPassword(locked(), null));
        assertTrue(ex.getMessage().contains("答题密码"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("填写页：其他人带对口令 → 放行，且容忍首尾空格")
    void othersWithCorrectPasswordPass() {
        assertDoesNotThrow(() -> surveyService.requireAccessPassword(locked(), "111"));
        assertDoesNotThrow(() -> surveyService.requireAccessPassword(locked(), "  111  "));
    }

    @Test
    @DisplayName("填写页：口令错误 → 400")
    void wrongPasswordIsRejected() {
        assertThrows(BizException.class, () -> surveyService.requireAccessPassword(locked(), "222"));
    }

    // ==================== 编辑器口径（带编辑器头）====================

    @Test
    @DisplayName("★ 编辑器：作者本人免口令（否则作者一设密码就把自己锁死）")
    void ownerBypassesPasswordInEditor() {
        assertDoesNotThrow(() ->
                surveyService.requireAccessPasswordForEditor(locked(), OWNER_ID, "user", null));
    }

    @Test
    @DisplayName("★ 编辑器：管理员免口令")
    void adminBypassesPasswordInEditor() {
        assertDoesNotThrow(() ->
                surveyService.requireAccessPasswordForEditor(locked(), 1, "admin", null));
    }

    @Test
    @DisplayName("编辑器：**不是**作者也不是管理员 → 仍然要口令（头不能当通行证）")
    void editorHeaderDoesNotHelpOthers() {
        assertThrows(BizException.class, () ->
                surveyService.requireAccessPasswordForEditor(locked(), 999, "user", null));
        assertDoesNotThrow(() ->
                surveyService.requireAccessPasswordForEditor(locked(), 999, "user", "111"));
    }

    @Test
    @DisplayName("编辑器：作者带错口令也放行（先判身份，再比口令）")
    void ownerWithWrongPasswordStillPassesInEditor() {
        assertDoesNotThrow(() ->
                surveyService.requireAccessPasswordForEditor(locked(), OWNER_ID, "user", "错误的密码"));
    }
}

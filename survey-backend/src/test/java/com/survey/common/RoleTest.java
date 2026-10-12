package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角色常量的纯单元测试（零依赖）。
 *
 * ── 为什么值得为两个字符串写测试 ─────────────────────────
 *   {@code Role.ADMIN} = "admin" 是**全项目权限判断的锚点**：
 *   JWT 里签发的 role、数据库 t_user.role 的取值、切面与过滤器里的比较，
 *   靠的都是同一个字面量。一旦有人把它改成 "Admin" 或 "administrator"，
 *   编译不会报错、测试也不会红 —— 但**所有管理员会瞬间变成普通用户**（或反之）。
 *   这里把取值与判定行为钉死，改名就必须显式改测试、被迫面对影响面。
 *
 * ── null 的行为尤其重要 ──────────────────────────────────
 *   匿名请求（白名单路径）经过 JwtAuthFilter 后 X-User-Role 是空串，
 *   Spring 转成 null 传入 Controller。isAdmin(null) 必须**稳定返回 false**，
 *   不能 NPE、也不能"宽容地放行"。
 */
class RoleTest {

    @Test
    @DisplayName("角色字面量：ADMIN=admin、USER=user（与 JWT / t_user.role 的既有取值一致）")
    void constants_shouldKeepTheirWireValues() {
        // ★ 这两个值是**对外契约**：库里已有数据的 role 列、已签发的 JWT 里都写着它们。
        //   改动等于让所有存量 token 与用户记录失效，所以在这里钉死。
        assertEquals("admin", Role.ADMIN, "ADMIN 的字面量不能改（存量 JWT 与 t_user.role 里存的就是它）");
        assertEquals("user", Role.USER, "USER 的字面量不能改（注册与演示数据都会写入它）");
    }

    @Test
    @DisplayName("isAdmin：只有精确的 \"admin\" 返回 true")
    void isAdmin_shouldBeStrict() {
        assertTrue(Role.isAdmin("admin"));
        assertFalse(Role.isAdmin("user"), "普通用户不是管理员");
        assertFalse(Role.isAdmin("ADMIN"), "大小写敏感 —— 大小写不同在库里就是两个不同的角色");
        assertFalse(Role.isAdmin("admin "), "带空格不算（用户名/角色两侧都要靠 trim 或精确匹配，不能靠这里的宽容）");
        assertFalse(Role.isAdmin("administrator"));
    }

    @Test
    @DisplayName("isAdmin：null / 空串稳定返回 false，不抛 NPE（匿名请求走的就是这条路）")
    void isAdmin_shouldTolerateNullAndEmpty() {
        assertDoesNotThrow(() -> Role.isAdmin(null));
        assertFalse(Role.isAdmin(null), "未登录（白名单路径）role 为 null，必须判为『不是管理员』");
        assertFalse(Role.isAdmin(""), "空串同样不是管理员");
    }
}

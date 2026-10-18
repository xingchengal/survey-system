package com.survey.common;

/**
 * 权限不足 —— 由 {@code @RequirePermission} 切面抛出，全局异常处理器翻译成 **403**。
 *
 * ── 为什么不复用 BizException（400）────────────────────
 *   "已登录、但这项操作不给你做"与"你填的参数不对"是两类问题：
 *     · 400 表示**这次请求本身**有问题（可改参数重试）；
 *     · 403 表示**身份/权限**不够（改参数也没用）。
 *   前端据此可以做不同处理（例如 403 时提示"联系管理员授权"，而不是"检查输入"）。
 *   ★ 历史包袱：{@code @RequireAdmin} 目前仍抛 BizException（400），
 *     两套口径会在 H2-6 统一 —— 那一步要同步 EndpointAuthMatrixTest 的 20 条断言
 *     与《端点权限审计表》，所以单独放一个提交，不混在权限矩阵的改造里。
 *
 * ── 与 RuntimeException 的关系 ────────────────────────
 *   继承 RuntimeException 而不是 BizException：GlobalExceptionHandler 里
 *   {@code @ExceptionHandler(RuntimeException.class)} 是 500 兜底，
 *   所以**必须**给本类单独写一个 {@code @ExceptionHandler}，
 *   否则会变成 500「系统繁忙」并写进 t_error_log（权限拒绝不是系统故障）。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

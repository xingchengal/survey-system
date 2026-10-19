package com.survey.common;

/**
 * 权限不足（403）—— 由 {@code @RequireAdmin} / {@code @RequirePermission} 切面抛出，全局异常处理器翻译。
 *
 * 不复用 {@link BizException}：400 表示"参数可改"，403 表示"权限不够、改参数也没用"，前端处理不同。
 * ★ 必须为它单独写 {@code @ExceptionHandler}：否则会落到 500 兜底并写 t_error_log（权限拒绝不是系统故障）。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}

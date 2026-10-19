package com.survey.common;

/**
 * 业务失败统一异常 → 400 + {success:false, code, message}，且**不写 t_error_log**。
 *
 * ★ 不要 return 一句中文（前端会比对文案），也不要抛 RuntimeException（正常拒绝会刷满异常日志）。
 * ★ 只用于调用方可纠正的错误；空指针、SQL、第三方故障继续抛 RuntimeException，好进异常日志。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}

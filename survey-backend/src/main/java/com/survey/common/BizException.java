package com.survey.common;

/**
 * 业务异常 —— 表示「用户这次操作不合法」，而不是「程序出错了」。
 *
 * ── 为什么统一走它 ────────────────────────────────────
 *   表达"业务失败"的两种错误做法，各自会带来一类难查的问题：
 *
 *   ① `throw new RuntimeException("无权限")`
 *      → 响应形状是对的（400 + {success:false,message}），
 *        但它**会把这次失败写进 t_error_log**：于是"输错密码""无权限""删不掉"
 *        这些正常的业务拒绝会把「异常日志」页面刷满，真异常反而被淹没。
 *
 *   ② `return "原密码错误"`（HTTP 200 + 一句中文）
 *      → 前端只能比对中文文案，后端措辞改一个字前端就静默失效
 *        （症状：改密码输错原密码毫无提示、删除失败却提示成功）。
 *
 *   ⇒ 规则：**业务失败一律 throw new BizException(原因)**，于是：
 *     · GlobalExceptionHandler 翻译成 **400 + {success:false, code, message, timestamp}**
 *     · **不写 t_error_log**（异常日志只留真异常：空指针、SQL 错误、外部接口故障…）
 *   前端因此可以只依赖 HTTP 状态码：2xx 成功、非 2xx 失败并读 message，
 *   全项目再没有一处中文文案比对。
 *
 * ── 什么时候该用它 ────────────────────────────────────
 *   用户/调用方能自行纠正的错误：参数不合法、密码错误、无权限、状态不允许（如"问卷未发布"）、
 *   目标不存在（问卷/答卷/公告已被删）。
 *   什么时候**不要**用它：确实的程序错误（空指针、类型转换失败、第三方服务挂了）——
 *   那些继续抛 RuntimeException / 其它异常，好让它们照常进异常日志。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}

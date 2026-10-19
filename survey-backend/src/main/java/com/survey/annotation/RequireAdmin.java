package com.survey.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「必须是管理员才能访问」的 Controller 类或方法，由 {@link com.survey.aspect.AdminCheckAspect} 统一实施。
 *
 * ★ 声明式取代手写 role 判断：漏一行 throw 就是横向越权，而且没有任何编译期或运行期提示。
 * 只负责「角色」这一半；「作者或管理员」的归属校验要按资源 id 查库，统一走 common/OwnershipGuard。
 * 整个类都是管理接口就标在类上；只有部分方法是管理接口就标在方法上。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}

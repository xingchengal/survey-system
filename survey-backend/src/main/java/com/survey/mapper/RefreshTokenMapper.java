package com.survey.mapper;

import com.survey.entity.RefreshToken;
import org.apache.ibatis.annotations.*;

/**
 * 刷新令牌表（t_refresh_token）的数据访问层。
 *
 * ── 表结构要点────────────────────────────────────────
 *   token_hash : 令牌的 SHA-256 十六进制，**唯一索引**（uk_token_hash）—— 所以查它是索引命中；
 *                ★ 绝不存明文（见 entity/RefreshToken 的说明）。
 *   expires_at : 过期时间，有 idx_expires_at 索引供清理任务范围扫描。
 *   revoked    : 0=有效 1=已吊销。实际置 1 的场景（可 grep 核对）：
 *                · 一次性轮换（RefreshTokenService.rotate）
 *                · 用户已被删除（rotate 里发现用户不存在）
 *                · 登出（UserController.logout）
 *                ★ **改密码不在其中**（未接入），详见 RefreshTokenService 类注释里的核查记录。
 *
 * ── 本接口的设计取舍──────────────────────────────────
 *   · 只提供"按 hash 查 / 插入 / 吊销 / 按用户吊销全部 / 清理过期"五种操作，
 *     **不提供"把 token 查出来看"**（没必要，也不该有）。
 *   · 更新语句都带 `revoked = 0` 条件 —— 重复吊销不会报错，且不会把已吊销的又改回去。
 */
@Mapper
public interface RefreshTokenMapper {

    /** 写入一条新令牌（tokenHash 已是 SHA-256 十六进制）。时间交给数据库默认值。 */
    @Insert("INSERT INTO t_refresh_token(user_id, token_hash, expires_at, revoked) " +
            "VALUES(#{userId}, #{tokenHash}, #{expiresAt}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RefreshToken token);

    /**
     * 按 hash 查一条令牌。
     * ★ 调用方（RefreshTokenService）必须自己再判：是否过期、是否已吊销、用户是否还在。
     *   这里返回原始行，不做过期过滤 —— 因为"过期的 token"与"没这个 token"要有不同日志。
     */
    @Select("SELECT * FROM t_refresh_token WHERE token_hash = #{tokenHash}")
    RefreshToken findByHash(@Param("tokenHash") String tokenHash);

    /** 吊销单条（轮换时用）。带 revoked = 0 条件，幂等。 */
    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE id = #{id} AND revoked = 0")
    int revokeById(@Param("id") Integer id);

    /**
     * 吊销某用户的**全部**有效令牌。
     * ★ 三个调用点，语义都是"让该用户所有会话立刻失效"：logout / **改密码** / **注销账号**
     *   （见 {@link com.survey.service.RefreshTokenService} 类注释）。
     * ★ 它只管 refresh token；已签发的 access token 要靠 \`token_valid_after\` 作废。
     */
    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE user_id = #{userId} AND revoked = 0")
    int revokeAllByUser(@Param("userId") Integer userId);

    /** 清理已过期或已吊销的旧记录（定时任务用，走 idx_expires_at）。 */
    @Delete("DELETE FROM t_refresh_token WHERE expires_at < NOW() OR (revoked = 1 AND update_time < DATE_SUB(NOW(), INTERVAL 7 DAY))")
    int deleteExpiredOrStale();
}

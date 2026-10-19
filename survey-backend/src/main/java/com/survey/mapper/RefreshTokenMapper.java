package com.survey.mapper;

import com.survey.entity.RefreshToken;
import org.apache.ibatis.annotations.*;

/**
 * 刷新令牌表（t_refresh_token）的数据访问层。
 * token_hash 是令牌的 SHA-256 十六进制（唯一索引 uk_token_hash）；★ 库里只存哈希、绝不存明文。
 * revoked：0=有效 / 1=已吊销，轮换用掉即置 1（一次性使用）；expires_at 有索引供清理任务扫描。
 * 更新语句一律带 revoked = 0：重复吊销不报错，也不会把已吊销的改回去。
 */
@Mapper
public interface RefreshTokenMapper {

    /** 写入一条新令牌（tokenHash 已是 SHA-256 十六进制），时间交给数据库默认值。 */
    @Insert("INSERT INTO t_refresh_token(user_id, token_hash, expires_at, revoked) " +
            "VALUES(#{userId}, #{tokenHash}, #{expiresAt}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RefreshToken token);

    /**
     * 按 hash 查一条令牌。不做过期/吊销过滤，返回原始行：调用方要自己判，
     * 且"已过期"与"不存在"需要不同的日志。
     */
    @Select("SELECT * FROM t_refresh_token WHERE token_hash = #{tokenHash}")
    RefreshToken findByHash(@Param("tokenHash") String tokenHash);

    /** 吊销单条（轮换时用）。带 revoked = 0 条件，幂等。 */
    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE id = #{id} AND revoked = 0")
    int revokeById(@Param("id") Integer id);

    /**
     * 吊销某用户的全部有效令牌（登出 / 改密码 / 注销账号）。
     * ★ 只吊销 revoked = 0 的未使用行；已过期的行顺手置 1 无副作用（清理任务会删）。
     *   已签发的 access token 不在此列，只能靠 token_valid_after 作废。
     */
    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE user_id = #{userId} AND revoked = 0")
    int revokeAllByUser(@Param("userId") Integer userId);

    /** 清理已过期、或已吊销满 7 天的旧记录（定时任务，走 idx_expires_at）。 */
    @Delete("DELETE FROM t_refresh_token WHERE expires_at < NOW() OR (revoked = 1 AND update_time < DATE_SUB(NOW(), INTERVAL 7 DAY))")
    int deleteExpiredOrStale();
}

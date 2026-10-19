package com.survey.mapper;

import com.survey.entity.WalletTransaction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 额度流水（t_wallet_transaction）—— 只追加的账本。
 * ★ idempotency_key 全表唯一，是防重复扣款 / 重复到账的最终闸门：
 *   insertIgnore 首次插 1 行、重复插 0 行，调用方据此决定是否再动余额，冲突不变成异常。
 * 不用"先 SELECT 再 INSERT"：并发下两边都查不到、都会插，一个撞唯一索引抛异常会把事务标成回滚。
 */
@Mapper
public interface WalletTransactionMapper {

    /**
     * 插入流水；幂等键已存在时返回 0（不抛异常、不改任何数据）。
     * balance_after 先写 0，扣/加成功后再用 updateBalanceAfter 回填真实值。
     */
    @Insert("INSERT IGNORE INTO t_wallet_transaction "
            + "(user_id, type, amount, balance_after, biz_id, idempotency_key, remark) "
            + "VALUES (#{userId}, #{type}, #{amount}, #{balanceAfter}, #{bizId}, #{idempotencyKey}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(WalletTransaction tx);

    /** 按幂等键查已有流水（重复请求直接返回它，不再动余额）。 */
    @Select("SELECT * FROM t_wallet_transaction WHERE idempotency_key = #{key}")
    WalletTransaction findByIdem(@Param("key") String key);

    /** 回填"本笔之后余额"快照（只更新这一列，账本本身不可变）。 */
    @Update("UPDATE t_wallet_transaction SET balance_after = #{balanceAfter} WHERE id = #{id}")
    int updateBalanceAfter(@Param("id") Long id, @Param("balanceAfter") int balanceAfter);

    /** 我的流水（按 user_id 分页，分页交给 PageHelper，这里不带 LIMIT）。 */
    @Select("SELECT * FROM t_wallet_transaction WHERE user_id = #{userId} ORDER BY id DESC")
    List<WalletTransaction> findByUser(@Param("userId") Integer userId);

    /** 今日全站入账 / 出账 / 笔数（amount 有符号：入账正、出账负）。 */
    @Select("SELECT COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0) AS income, "
            + "COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0) AS expense, "
            + "COUNT(*) AS cnt FROM t_wallet_transaction WHERE created_time >= CURDATE()")
    Map<String, Object> sumToday();

    /** 全站流水按类型汇总（统计页「流水构成」）。 */
    @Select("SELECT type, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS amount "
            + "FROM t_wallet_transaction GROUP BY type ORDER BY cnt DESC")
    List<Map<String, Object>> sumByType();
}

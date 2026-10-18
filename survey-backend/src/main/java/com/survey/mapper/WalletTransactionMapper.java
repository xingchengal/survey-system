package com.survey.mapper;

import com.survey.entity.WalletTransaction;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 额度流水（t_wallet_transaction）—— 只追加的账本。
 *
 * ── 幂等的实现方式 ────────────────────────────────────
 *   {@link #insertIgnore} 用 MySQL 的 INSERT IGNORE + 全表唯一的 idempotency_key：
 *     · 首次请求插入 1 行 → 继续扣/加余额；
 *     · 重复请求插入 0 行 → 调用方直接返回已有流水，**不再动余额**。
 *   ★ 为什么不用"先 SELECT 再 INSERT"：并发下两个请求都查不到，都会插，
 *     然后一个撞唯一索引抛异常 —— 事务被标记回滚，反而要写补偿逻辑。
 *   ★ 为什么不用 catch DuplicateKeyException：Spring 的 @Transactional 在异常穿过
 *     方法边界时会把事务标成 rollback-only，即使你 catch 住了，提交时仍抛
 *     UnexpectedRollbackException。INSERT IGNORE 把"冲突"变成正常返回值，干净得多。
 */
@Mapper
public interface WalletTransactionMapper {

    /**
     * 插入流水；幂等键已存在时返回 0（不抛异常、不改任何数据）。
     * ★ balance_after 先写 0，扣/加成功后再用 updateBalanceAfter 回填真实值。
     */
    @Insert("INSERT IGNORE INTO t_wallet_transaction "
            + "(user_id, type, amount, balance_after, biz_id, idempotency_key, remark) "
            + "VALUES (#{userId}, #{type}, #{amount}, #{balanceAfter}, #{bizId}, #{idempotencyKey}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(WalletTransaction tx);

    @Select("SELECT * FROM t_wallet_transaction WHERE idempotency_key = #{key}")
    WalletTransaction findByIdem(@Param("key") String key);

    /** 回填"本笔之后余额"快照（只更新这一列，账本本身不可变） */
    @Update("UPDATE t_wallet_transaction SET balance_after = #{balanceAfter} WHERE id = #{id}")
    int updateBalanceAfter(@Param("id") Long id, @Param("balanceAfter") int balanceAfter);

    /** 我的流水（分页交给 PageHelper，这里不带 LIMIT） */
    @Select("SELECT * FROM t_wallet_transaction WHERE user_id = #{userId} ORDER BY id DESC")
    List<WalletTransaction> findByUser(@Param("userId") Integer userId);
}

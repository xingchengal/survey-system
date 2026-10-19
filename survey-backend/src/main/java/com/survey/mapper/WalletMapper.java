package com.survey.mapper;

import com.survey.entity.Wallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 额度钱包（t_wallet）的数据访问层。
 * ★ 扣减只有 debit 一条 SQL，判断与扣减在同一条语句里完成；返回 0 = 余额不足，不能先 SELECT 再 UPDATE。
 * 入账走 credit（无余额条件）；管理端列表/对账都是只读，分页交给 PageHelper。
 */
@Mapper
public interface WalletMapper {

    /** 没有钱包时建一个 0 余额的（INSERT IGNORE，幂等）。注册赠送/首充/发布扣费都先调它，避免"钱包不存在"分支散落。 */
    @Insert("INSERT IGNORE INTO t_wallet (user_id, balance, frozen, version) "
            + "VALUES (#{userId}, 0, 0, 0)")
    int insertIgnore(Integer userId);

    @Select("SELECT * FROM t_wallet WHERE user_id = #{userId}")
    Wallet findByUserId(Integer userId);

    @Select("SELECT balance FROM t_wallet WHERE user_id = #{userId}")
    Integer findBalance(Integer userId);

    /**
     * 锁定读钱包行（FOR UPDATE）：同一用户的并发资金操作在这一行上串行。
     * 必须先锁钱包、再动流水：反过来会让"流水唯一键锁"与"钱包行锁"交叉等待死锁。
     */
    @Select("SELECT balance FROM t_wallet WHERE user_id = #{userId} FOR UPDATE")
    Integer findBalanceForUpdate(Integer userId);

    /**
     * 原子扣减。
     * 必须保留 AND balance >= #{amount}：它是"不超扣"的唯一保证，去掉后先查再扣，并发就能把余额扣成负数。
     * @return 1 = 成功；0 = 余额不足或钱包不存在
     */
    @Update("UPDATE t_wallet SET balance = balance - #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int debit(@Param("userId") Integer userId, @Param("amount") int amount);

    /** 入账（退款/充值/赠送）。没有余额条件；钱包不存在时影响 0 行，由调用方先 ensure。 */
    @Update("UPDATE t_wallet SET balance = balance + #{amount}, version = version + 1 "
            + "WHERE user_id = #{userId}")
    int credit(@Param("userId") Integer userId, @Param("amount") int amount);

    /**
     * 管理端钱包列表（余额从高到低），keyword 同时匹配用户名与昵称；分页由 PageHelper 承担。
     * updated_time 用 DATE_FORMAT 返回字符串：列表走 Map，Jackson 会给 LocalDateTime 输出带 T 的 ISO 格式。
     */
    @Select("<script>"
            + "SELECT w.user_id AS userId, u.username, u.nickname, w.balance, w.frozen, "
            + "DATE_FORMAT(w.updated_time, '%Y-%m-%d %H:%i:%s') AS updatedTime "
            + "FROM t_wallet w LEFT JOIN t_user u ON u.id = w.user_id "
            + "<where><if test='keyword != null and keyword != \"\"'>"
            + "AND (u.username LIKE CONCAT('%', #{keyword}, '%') "
            + "OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if></where> "
            + "ORDER BY w.balance DESC, w.user_id ASC"
            + "</script>")
    List<Map<String, Object>> findPage(@Param("keyword") String keyword);

    @Select("SELECT COUNT(*) FROM t_wallet")
    int countWallets();

    /** 全站余额合计。只读展示，不参与对账；对账走 findMismatches（SUM(amount) 对比 balance）。 */
    @Select("SELECT IFNULL(SUM(balance), 0) FROM t_wallet")
    long sumBalance();

    /**
     * 对账：余额与流水汇总不一致的钱包。口径唯一 —— SUM(amount)（有符号）必须等于 balance。
     * 返回 ledger（流水汇总）与 balance 两列，不等就是账错。
     */
    @Select("SELECT w.user_id AS userId, u.username, w.balance, COALESCE(t.ledger, 0) AS ledger "
            + "FROM t_wallet w "
            + "LEFT JOIN t_user u ON u.id = w.user_id "
            + "LEFT JOIN (SELECT user_id, SUM(amount) AS ledger FROM t_wallet_transaction GROUP BY user_id) t "
            + "  ON t.user_id = w.user_id "
            + "WHERE w.balance <> COALESCE(t.ledger, 0) "
            + "ORDER BY w.user_id LIMIT 200")
    List<Map<String, Object>> findMismatches();

    @Select("SELECT COUNT(*) FROM t_wallet w "
            + "LEFT JOIN (SELECT user_id, SUM(amount) AS ledger FROM t_wallet_transaction GROUP BY user_id) t "
            + "  ON t.user_id = w.user_id "
            + "WHERE w.balance <> COALESCE(t.ledger, 0)")
    int countMismatches();
}

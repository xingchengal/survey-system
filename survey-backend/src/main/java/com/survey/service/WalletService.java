package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.BizException;
import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.WalletFeature;
import com.survey.common.WalletTxType;
import com.survey.entity.Wallet;
import com.survey.entity.WalletTransaction;
import com.survey.mapper.WalletMapper;
import com.survey.mapper.WalletTransactionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 额度钱包（§H-H5，模拟付费，不接真实支付）。
 *
 * ── 三条铁律（改代码前先看）──────────────────────────────
 *   1. **扣减只有一条 SQL**：{@code UPDATE ... WHERE balance >= ?}。判断与扣减在
 *      同一条语句里完成，没有"读-改-写"窗口，50 并发也不会超扣。
 *   2. **幂等靠 UNIQUE 键**：每一笔业务都带 idempotencyKey（publish:{surveyId}、
 *      refund:{surveyId}、signup:{userId}、recharge:{uuid}）。
 *      重复请求插入 0 行 → 直接返回已有流水，不再动余额。
 *   3. **流水不可变**：只 INSERT、只回填 balance_after，永不 UPDATE amount。
 *      对账等式因此只有一条：SUM(amount) = balance。
 *
 * ── 事务边界 ──────────────────────────────────────────
 *   charge / credit / refundForSurvey / recharge / adjust 都是 @Transactional 的
 *   **public** 方法（跨 Bean 调用才走代理）；真正干活的 doCharge/doCredit 是私有方法，
 *   运行时加入外层事务 —— 千万不要在同类内部直接调用 public 方法（事务会失效）。
 *
 * ── 业务规则 ──────────────────────────────────────────
 *   · 注册赠送 10 点（幂等键 signup:{userId}；迁移脚本给老用户补发过，不重复）
 *   · 发布问卷扣 1 点（幂等键 publish:{surveyId}，一份问卷终身只扣一次）
 *   · 移入回收站退 1 点（幂等键 refund:{surveyId}，只退一次；恢复后不重复扣）
 *   · 管理员手动调整（补偿/修正）
 */
@Service
public class WalletService {

    private static final Logger LOG = LoggerFactory.getLogger(WalletService.class);

    /** 发布一份问卷消耗的点数（价格唯一出处：WalletFeature） */
    public static final int PUBLISH_COST = WalletFeature.PUBLISH.getCost();

    /** AI 生成问卷消耗的点数（进阶功能；失败会原路退回） */
    public static final int AI_COST = WalletFeature.AI_SURVEY.getCost();

    /** 导出答卷消耗的点数（进阶功能；每次导出都扣，导出是"取走数据"的动作） */
    public static final int EXPORT_COST = WalletFeature.EXPORT.getCost();

    /** 高级设置包（答题密码 / 限收份数 / 公开结果），每份问卷一次性 */
    public static final int ADVANCED_SETTINGS_COST = WalletFeature.ADVANCED_SETTINGS.getCost();

    /** 协作者名额（第 3 名起，按人一次性） */
    public static final int COLLABORATOR_COST = WalletFeature.COLLABORATOR.getCost();

    /** 注册赠送点数 */
    public static final int SIGNUP_BONUS_AMOUNT = 10;

    /** 单次模拟充值上限（防止误点/脚本刷出天文数字） */
    public static final int RECHARGE_MAX = 1000;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper txMapper;

    // ==================== 查询 ====================

    /** 我的钱包（没有就建一个 0 余额的，保证前端拿到的是同一个结构） */
    public Wallet getOrCreate(Integer userId) {
        if (userId == null) {
            throw new BizException("用户不存在");
        }
        walletMapper.insertIgnore(userId);
        Wallet wallet = walletMapper.findByUserId(userId);
        if (wallet == null) {
            throw new BizException("钱包初始化失败，请稍后重试");
        }
        return wallet;
    }

    /** 我的流水（分页，新的在前） */
    public PageResult<WalletTransaction> listTransactions(Integer userId, int pageNum, int pageSize) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        List<WalletTransaction> list = txMapper.findByUser(userId);
        PageInfo<WalletTransaction> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 管理端：用户钱包列表（按余额倒序，可按用户名/昵称搜） */
    public PageResult<Map<String, Object>> pageWallets(String keyword, int pageNum, int pageSize) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        List<Map<String, Object>> list = walletMapper.findPage(SqlLikeEscaper.escape(keyword));
        PageInfo<Map<String, Object>> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 对账：余额 ≠ 流水汇总 的钱包。
     * ★ 不修数据、只报告 —— 自动"修"会把错误藏起来，应该由人确认后再调账。
     */
    public Map<String, Object> reconcile() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("walletCount", walletMapper.countWallets());
        result.put("mismatchCount", walletMapper.countMismatches());
        result.put("mismatches", walletMapper.findMismatches());
        return result;
    }

    // ==================== 扣减 / 入账 ====================

    /**
     * 扣额度（发布问卷等）。
     *
     * @return 本次流水；**幂等命中时返回已有流水**（不再扣）
     * @throws BizException 余额不足（事务回滚，占位流水一并回滚）
     */
    @Transactional
    public WalletTransaction charge(Integer userId, int amount, String type, String bizId,
                                    String idempotencyKey, String remark) {
        return doCharge(userId, amount, type, bizId, idempotencyKey, remark);
    }

    /**
     * 入账（退款/充值/赠送/正调整）。
     *
     * @return 本次流水；幂等命中时返回已有流水（不再加）
     */
    @Transactional
    public WalletTransaction credit(Integer userId, int amount, String type, String bizId,
                                    String idempotencyKey, String remark) {
        return doCredit(userId, amount, type, bizId, idempotencyKey, remark);
    }

    /**
     * 发布扣费：一份问卷只扣一次（幂等键 publish:{surveyId}）。
     * ★ userId 为 null / 0 时直接跳过：模板属于"公共资产"（userId=0），不向任何人收费。
     * ★ 由 SurveyService.publish 在**同一事务**内调用：余额不足会连同"改状态"一起回滚。
     */
    @Transactional
    public void chargeForPublish(Integer surveyId, Integer ownerId) {
        if (surveyId == null || ownerId == null || ownerId == 0) {
            return;
        }
        doCharge(ownerId, PUBLISH_COST, WalletTxType.PUBLISH, String.valueOf(surveyId),
                "publish:" + surveyId, "发布问卷扣 " + PUBLISH_COST + " 点");
    }

    /**
     * 移入回收站退款：只有"确实扣过发布费"的问卷才退（幂等键 refund:{surveyId}）。
     * ★ 恢复回收站后再删除不会再退第二次；一次收费终身有效。
     */
    @Transactional
    public WalletTransaction refundForSurvey(Integer surveyId, Integer ownerId) {
        if (surveyId == null || ownerId == null || ownerId == 0) {
            return null;
        }
        if (txMapper.findByIdem("publish:" + surveyId) == null) {
            return null; // 没扣过（草稿直接删除）→ 不退款
        }
        return doCredit(ownerId, PUBLISH_COST, WalletTxType.REFUND, String.valueOf(surveyId),
                "refund:" + surveyId, "移入回收站退回 " + PUBLISH_COST + " 点");
    }

    /**
     * AI 生成问卷：**先预扣**再调用模型。
     *
     * ★ 为什么预扣而不是"成功后再扣"：
     *   AI 调用耗时 10~120 秒，如果先调用再扣费，并发下余额可能在中间被别处花光，
     *   生成完却扣不到钱（或者问卷已入库、只能回滚）。预扣把"扣不到"挡在 AI 调用之前，
     *   失败时由调用方 {@link #refundAi} 原路退回，用户不会白付。
     *
     * @return 本次扣费的幂等键（退款时原样传回）
     */
    @Transactional
    public String chargeForAi(Integer userId) {
        String key = "ai:" + java.util.UUID.randomUUID();
        doCharge(userId, AI_COST, WalletTxType.AI_GENERATE, null, key,
                "AI 生成问卷（" + AI_COST + " 点）");
        return key;
    }

    /**
     * AI 生成失败：把预扣的点数原路退回（幂等键 refund:{chargeKey}）。
     * ★ 由 Controller 在 catch 里调用；独立事务，即使原请求的异常继续抛出也能退成功。
     */
    @Transactional
    public void refundAi(Integer userId, String chargeKey) {
        if (userId == null || chargeKey == null || chargeKey.isBlank()) {
            return;
        }
        doCredit(userId, AI_COST, WalletTxType.REFUND, null, "refund:" + chargeKey,
                "AI 生成失败退回 " + AI_COST + " 点");
    }

    /**
     * 导出答卷：每次扣 1 点（进阶功能）。
     * ★ 管理员不扣（由调用方判断角色）；幂等键带 UUID —— 导出本身就是"每次取一份数据"，
     *   不做跨请求去重（去重会让用户以为第二次导出没花钱）。
     */
    @Transactional
    public void chargeForExport(Integer surveyId, Integer userId) {
        if (surveyId == null || userId == null || userId == 0) {
            return;
        }
        String key = "export:" + surveyId + ":" + userId + ":" + java.util.UUID.randomUUID();
        doCharge(userId, EXPORT_COST, WalletTxType.EXPORT, String.valueOf(surveyId), key,
                "导出答卷（" + EXPORT_COST + " 点）");
    }

    /**
     * 高级设置包：解锁答题密码 / 限收份数 / 公开结果，**每份问卷一次性**。
     * ★ 幂等键 advanced:{surveyId}：先开密码、后开公开结果，只会扣一次；
     *   关掉再开会命中同一幂等键，不再扣（买断语义）。
     * ★ 只扣"首次启用"那一次，由调用方（SurveyController.update）判断 old/new。
     */
    @Transactional
    public void chargeAdvancedSettings(Integer surveyId, Integer ownerId) {
        if (surveyId == null || ownerId == null || ownerId == 0) {
            return;
        }
        doCharge(ownerId, ADVANCED_SETTINGS_COST, WalletTxType.ADVANCED_SETTINGS,
                String.valueOf(surveyId), "advanced:" + surveyId,
                "高级设置包（密码/限量/公开结果）");
    }

    /**
     * 协作者名额：每份问卷前 {@link WalletFeature#FREE_COLLABORATORS} 名免费，之后按人扣。
     * ★ 幂等键 collab:{surveyId}:{targetUserId}：同一人重复邀请/退出后再邀请都不会重复扣。
     * ★ 扣的是**问卷作者**的额度（名额属于这份问卷）；作者是模板（userId=0）时免费。
     */
    @Transactional
    public void chargeCollaborator(Integer surveyId, Integer ownerId, Integer targetUserId) {
        if (surveyId == null || ownerId == null || ownerId == 0 || targetUserId == null) {
            return;
        }
        doCharge(ownerId, COLLABORATOR_COST, WalletTxType.COLLABORATOR,
                String.valueOf(surveyId), "collab:" + surveyId + ":" + targetUserId,
                "协作者名额（第 " + (WalletFeature.FREE_COLLABORATORS + 1) + " 名起）");
    }

    /**
     * 计费项目录（前端「计费规则」动态渲染用）。
     * ★ 价格只在这里定义（WalletFeature），前端不再硬编码数字；
     *   以后新增计费项（例如新的进阶题型包）只要加一个枚举，前端自动显示。
     */
    public List<Map<String, Object>> pricing() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (WalletFeature feature : WalletFeature.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", feature.getCode());
            row.put("label", feature.getLabel());
            row.put("cost", feature.getCost());
            row.put("description", feature.getDescription());
            row.put("mode", feature.getMode().name());
            list.add(row);
        }
        return list;
    }

    /**
     * 注册赠送（幂等键 signup:{userId}；迁移已补发的老用户不会重复得到）。
     *
     * ★ 用 REQUIRES_NEW 独立事务：它由 UserService.register（本身 @Transactional）在
     *   用户插入之后调用，赠送失败**不能**把注册一起回滚。独立事务下即使这里抛异常，
     *   外层 catch 也不会踩到 "rollback-only → UnexpectedRollbackException"。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletTransaction grantSignupBonus(Integer userId) {
        return doCredit(userId, SIGNUP_BONUS_AMOUNT, WalletTxType.SIGNUP_BONUS, null,
                "signup:" + userId, "新用户注册赠送 " + SIGNUP_BONUS_AMOUNT + " 点");
    }

    /**
     * 模拟充值（用户自助，演示用）。
     * ★ 幂等键由前端生成，重复提交同一键只到账一次。
     */
    @Transactional
    public WalletTransaction recharge(Integer userId, int amount, String idempotencyKey) {
        if (amount < 1 || amount > RECHARGE_MAX) {
            throw new BizException("单次充值金额必须在 1 ~ " + RECHARGE_MAX + " 点之间");
        }
        return doCredit(userId, amount, WalletTxType.RECHARGE, null, idempotencyKey,
                "模拟充值 " + amount + " 点（演示用，未接真实支付）");
    }

    /**
     * 管理员调整额度：amount 为正加、为负扣（扣到负数会因余额不足被拒）。
     * ★ 与充值分开一个类型（ADMIN_ADJUST），流水中能一眼区分"用户自己充的"与"管理员调的"。
     */
    @Transactional
    public WalletTransaction adjust(Integer userId, int amount, String reason, String idempotencyKey) {
        if (amount == 0) {
            throw new BizException("调整金额不能为 0");
        }
        if (amount > 100000 || amount < -100000) {
            throw new BizException("单次调整金额不能超过 100000 点");
        }
        if (amount > 0) {
            return doCredit(userId, amount, WalletTxType.ADMIN_ADJUST, null, idempotencyKey,
                    "管理员调整 " + (reason == null || reason.isBlank() ? "" : "：" + reason).trim());
        }
        return doCharge(userId, -amount, WalletTxType.ADMIN_ADJUST, null, idempotencyKey,
                "管理员调整 " + (reason == null || reason.isBlank() ? "" : "：" + reason).trim());
    }

    // ==================== 内部实现（运行在外层事务里） ====================

    private WalletTransaction doCharge(Integer userId, int amount, String type, String bizId,
                                       String idempotencyKey, String remark) {
        requireKey(idempotencyKey);
        if (amount <= 0) {
            throw new BizException("扣减额度必须大于 0");
        }
        // ★ 锁顺序：先锁钱包行，再动流水。所有资金操作都遵守这个顺序，锁不会成环。
        lockWallet(userId);

        // 锁内再查幂等键：同一 key 的并发请求会在钱包行锁上排队，后来的直接返回原流水
        WalletTransaction existing = txMapper.findByIdem(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        WalletTransaction tx = newTransaction(userId, type, -amount, bizId, idempotencyKey, remark);
        int inserted = txMapper.insertIgnore(tx);
        if (inserted == 0) {
            // 理论上不会走到（锁内已查重）；留作兜底：绝不再扣
            return txMapper.findByIdem(idempotencyKey);
        }

        int rows = walletMapper.debit(userId, amount);
        if (rows == 0) {
            Integer balance = walletMapper.findBalance(userId);
            throw new BizException("额度不足：本次需要 " + amount + " 点，当前只有 "
                    + (balance == null ? 0 : balance) + " 点；请到「我的钱包」充值后再试");
        }
        int balanceAfter = walletMapper.findBalance(userId);
        txMapper.updateBalanceAfter(tx.getId(), balanceAfter);
        tx.setBalanceAfter(balanceAfter);
        return tx;
    }

    private WalletTransaction doCredit(Integer userId, int amount, String type, String bizId,
                                       String idempotencyKey, String remark) {
        requireKey(idempotencyKey);
        if (amount <= 0) {
            throw new BizException("入账额度必须大于 0");
        }
        lockWallet(userId);

        WalletTransaction existing = txMapper.findByIdem(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        WalletTransaction tx = newTransaction(userId, type, amount, bizId, idempotencyKey, remark);
        int inserted = txMapper.insertIgnore(tx);
        if (inserted == 0) {
            return txMapper.findByIdem(idempotencyKey);
        }

        walletMapper.credit(userId, amount);
        int balanceAfter = walletMapper.findBalance(userId);
        txMapper.updateBalanceAfter(tx.getId(), balanceAfter);
        tx.setBalanceAfter(balanceAfter);
        return tx;
    }

    /**
     * 锁定钱包行（不存在则先建包再锁）。
     *
     * ★ 为什么不用"无脑 INSERT IGNORE 建包"：钱包已存在时，INSERT IGNORE 会对该行
     *   加共享锁；随后再升级成 FOR UPDATE 的排他锁，两个并发事务就可能互相等待。
     *   先 FOR UPDATE 查、只有真的不存在时才插入，正常路径就只有一次锁定读。
     */
    private void lockWallet(Integer userId) {
        if (userId == null) {
            throw new BizException("用户不存在");
        }
        Integer balance = walletMapper.findBalanceForUpdate(userId);
        if (balance == null) {
            walletMapper.insertIgnore(userId);
            balance = walletMapper.findBalanceForUpdate(userId);
        }
        if (balance == null) {
            throw new BizException("钱包初始化失败，请稍后重试");
        }
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException("缺少幂等键（idempotencyKey）");
        }
        if (idempotencyKey.length() > 128) {
            throw new BizException("幂等键过长（最多 128 字符）");
        }
    }

    private static WalletTransaction newTransaction(Integer userId, String type, int signedAmount,
                                                   String bizId, String idempotencyKey, String remark) {
        WalletTransaction tx = new WalletTransaction();
        tx.setUserId(userId);
        tx.setType(type);
        tx.setAmount(signedAmount);
        tx.setBalanceAfter(0); // 占位，扣/加成功后再回填真实余额
        tx.setBizId(bizId);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRemark(remark);
        return tx;
    }
}

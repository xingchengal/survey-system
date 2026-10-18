package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.WalletTxType;
import com.survey.entity.WalletTransaction;
import com.survey.mapper.WalletMapper;
import com.survey.mapper.WalletTransactionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 额度钱包的**规则**测试（不连库）。
 *
 * 覆盖：正常扣减、幂等命中不再扣、余额不足、入账、注册赠送、退款前置条件、
 *       公共资产的发布不收费、对账口径、调整 0 点被拒。
 *
 * ★ 真正的并发不超扣不在这里测 —— Mockito 测不出"两个事务同时 UPDATE"，
 *   必须用真库（见 WalletConcurrencyTest，默认被 surefire 排除，显式点名才跑）。
 * ★ 实现细节：资金操作先 `SELECT ... FOR UPDATE` 锁钱包行（见 WalletService.lockWallet），
 *   所以这里的每个用例都要给 findBalanceForUpdate 一个返回值，否则会走"建包失败"分支。
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletMapper walletMapper;

    @Mock
    private WalletTransactionMapper txMapper;

    @InjectMocks
    private WalletService walletService;

    /** 让 insertIgnore 像真实 INSERT 一样回填自增 id */
    private void stubInserted(long id) {
        when(txMapper.insertIgnore(any())).thenAnswer(inv -> {
            WalletTransaction tx = inv.getArgument(0);
            tx.setId(id);
            return 1;
        });
    }

    @Test
    @DisplayName("发布扣费：扣 1 点、写负金额流水、回填扣后余额")
    void charge_shouldDebitOnceAndFillBalanceAfter() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(10);
        when(txMapper.findByIdem("publish:5")).thenReturn(null);
        stubInserted(100L);
        when(walletMapper.debit(7, 1)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(9);

        WalletTransaction tx = walletService.charge(7, 1, WalletTxType.PUBLISH, "5", "publish:5", "发布问卷");

        assertEquals(-1, tx.getAmount(), "出账必须记负数，对账 SUM(amount)=balance 才成立");
        assertEquals(9, tx.getBalanceAfter());
        assertEquals("publish:5", tx.getIdempotencyKey());
        verify(txMapper).updateBalanceAfter(100L, 9);
    }

    @Test
    @DisplayName("★ 幂等：同一幂等键重复请求只返回原流水，绝不再扣一次")
    void charge_sameIdempotencyKey_shouldNotDebitAgain() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(9);
        WalletTransaction existing = new WalletTransaction();
        existing.setId(99L);
        existing.setAmount(-1);
        existing.setBalanceAfter(9);
        when(txMapper.findByIdem("publish:5")).thenReturn(existing);

        WalletTransaction tx = walletService.charge(7, 1, WalletTxType.PUBLISH, "5", "publish:5", "发布问卷");

        assertSame(existing, tx);
        verify(walletMapper, never()).debit(anyInt(), anyInt());
        verify(txMapper, never()).insertIgnore(any());
        verify(txMapper, never()).updateBalanceAfter(anyLong(), anyInt());
    }

    @Test
    @DisplayName("★ 余额不足：抛 BizException 且文案带当前余额（前端可直接提示去充值）")
    void charge_insufficientBalance_shouldThrowWithCurrentBalance() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(0);
        when(txMapper.findByIdem("publish:5")).thenReturn(null);
        stubInserted(100L);
        when(walletMapper.debit(7, 1)).thenReturn(0); // 条件不满足 = 余额不够
        when(walletMapper.findBalance(7)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> walletService.charge(7, 1, WalletTxType.PUBLISH, "5", "publish:5", "发布问卷"));

        assertTrue(ex.getMessage().contains("额度不足"), ex.getMessage());
        assertTrue(ex.getMessage().contains("当前只有 0 点"), ex.getMessage());
        verify(txMapper, never()).updateBalanceAfter(anyLong(), anyInt());
    }

    @Test
    @DisplayName("充值：入账记正数，流水类型 RECHARGE")
    void recharge_shouldCreditPositiveAmount() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(10);
        when(txMapper.findByIdem("recharge:uuid-1")).thenReturn(null);
        stubInserted(101L);
        when(walletMapper.credit(7, 30)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(40);

        WalletTransaction tx = walletService.recharge(7, 30, "recharge:uuid-1");

        assertEquals(30, tx.getAmount());
        assertEquals(WalletTxType.RECHARGE, tx.getType());
        assertEquals(40, tx.getBalanceAfter());
    }

    @Test
    @DisplayName("注册赠送：10 点、幂等键 signup:{userId}")
    void grantSignupBonus_shouldCreditTenPoints() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(0);
        when(txMapper.findByIdem("signup:7")).thenReturn(null);
        stubInserted(102L);
        when(walletMapper.credit(7, 10)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(10);

        WalletTransaction tx = walletService.grantSignupBonus(7);

        assertEquals(10, tx.getAmount());
        assertEquals(WalletTxType.SIGNUP_BONUS, tx.getType());
        assertEquals("signup:7", tx.getIdempotencyKey());
    }

    @Test
    @DisplayName("退款前置：没扣过发布费（无 publish 流水）就不退")
    void refundForSurvey_withoutPublishCharge_shouldDoNothing() {
        when(txMapper.findByIdem("publish:5")).thenReturn(null);

        assertNull(walletService.refundForSurvey(5, 7));
        verify(walletMapper, never()).credit(anyInt(), anyInt());
        verify(walletMapper, never()).findBalanceForUpdate(anyInt());
    }

    @Test
    @DisplayName("退款：扣过才退，幂等键 refund:{surveyId}")
    void refundForSurvey_withPublishCharge_shouldCreditOnce() {
        when(txMapper.findByIdem("publish:5")).thenReturn(new WalletTransaction());
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(10);
        when(txMapper.findByIdem("refund:5")).thenReturn(null);
        stubInserted(103L);
        when(walletMapper.credit(7, 1)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(11);

        WalletTransaction tx = walletService.refundForSurvey(5, 7);

        assertNotNull(tx);
        assertEquals(WalletTxType.REFUND, tx.getType());
        assertEquals(1, tx.getAmount());
        assertEquals("refund:5", tx.getIdempotencyKey());
    }

    @Test
    @DisplayName("公共资产（userId=0 的模板）发布不收费")
    void chargeForPublish_ownerZero_shouldSkip() {
        walletService.chargeForPublish(5, 0);
        verifyNoInteractions(walletMapper, txMapper);
    }

    @Test
    @DisplayName("对账：返回钱包数、不一致数、明细")
    void reconcile_shouldReportCountsAndRows() {
        when(walletMapper.countWallets()).thenReturn(25);
        when(walletMapper.countMismatches()).thenReturn(1);
        when(walletMapper.findMismatches()).thenReturn(List.of(Map.of("userId", 9, "balance", 3, "ledger", 2)));

        Map<String, Object> result = walletService.reconcile();

        assertEquals(25, result.get("walletCount"));
        assertEquals(1, result.get("mismatchCount"));
        assertEquals(1, ((List<?>) result.get("mismatches")).size());
    }

    @Test
    @DisplayName("管理员调整 0 点：直接拒绝（没有意义的流水不许落库）")
    void adjust_zeroAmount_shouldThrow() {
        assertThrows(BizException.class, () -> walletService.adjust(7, 0, "手滑", "adjust:1"));
        verifyNoInteractions(walletMapper, txMapper);
    }

    @Test
    @DisplayName("缺少幂等键：拒绝（否则重复扣款无从去重）")
    void charge_blankIdempotencyKey_shouldThrow() {
        assertThrows(BizException.class,
                () -> walletService.charge(7, 1, WalletTxType.PUBLISH, "5", "  ", "发布问卷"));
        verifyNoInteractions(walletMapper, txMapper);
    }

    @Test
    @DisplayName("高级设置包：扣 1 点、幂等键 advanced:{surveyId}（先开密码后开公开结果也只扣一次）")
    void chargeAdvancedSettings_shouldChargeOncePerSurvey() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(10);
        when(txMapper.findByIdem("advanced:5")).thenReturn(null);
        stubInserted(200L);
        when(walletMapper.debit(7, 1)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(9);

        walletService.chargeAdvancedSettings(5, 7);

        verify(txMapper).insertIgnore(argThat(tx -> "advanced:5".equals(tx.getIdempotencyKey())
                && tx.getAmount() == -1
                && WalletTxType.ADVANCED_SETTINGS.equals(tx.getType())));
        verify(txMapper).updateBalanceAfter(200L, 9);
    }

    @Test
    @DisplayName("高级设置包：公共资产（ownerId=0）不收费")
    void chargeAdvancedSettings_ownerZero_shouldSkip() {
        walletService.chargeAdvancedSettings(5, 0);
        verifyNoInteractions(walletMapper, txMapper);
    }

    @Test
    @DisplayName("协作者名额：按「问卷 + 被邀请人」幂等（collab:{surveyId}:{targetUserId}）")
    void chargeCollaborator_shouldUseCompositeIdempotencyKey() {
        when(walletMapper.findBalanceForUpdate(7)).thenReturn(10);
        when(txMapper.findByIdem("collab:5:99")).thenReturn(null);
        stubInserted(201L);
        when(walletMapper.debit(7, 1)).thenReturn(1);
        when(walletMapper.findBalance(7)).thenReturn(9);

        walletService.chargeCollaborator(5, 7, 99);

        verify(txMapper).insertIgnore(argThat(tx -> "collab:5:99".equals(tx.getIdempotencyKey())
                && WalletTxType.COLLABORATOR.equals(tx.getType())));
    }

    @Test
    @DisplayName("计费项目录：价格与说明来自 WalletFeature（前端不再硬编码）")
    void pricing_shouldExposeFeatureCatalog() {
        var list = walletService.pricing();

        assertEquals(5, list.size(), "发布/AI/导出/高级设置/协作者共 5 项");
        var advanced = list.stream()
                .filter(m -> "advanced".equals(m.get("code")))
                .findFirst()
                .orElseThrow();
        assertEquals(1, advanced.get("cost"));
        assertTrue(String.valueOf(advanced.get("description")).contains("每份问卷只扣一次"));
    }
}

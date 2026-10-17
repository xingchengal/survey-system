package com.survey.service;

import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.entity.Answer;
import com.survey.mapper.AnswerMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 答卷列表分页的纯单测 —— 钉住"偏移量算法"与"参数夹紧"，不连库。
 *
 * ── 为什么值得单独测 ────────────────────────────────────
 *   分页的三个经典错误都不会报错、只会"看起来怪"：
 *     · 偏移量算成 {@code pageNum * pageSize}（第 1 页跳过前 10 条，**永远看不到最新答卷**）；
 *     · 每页条数没夹紧，前端传 {@code pageSize=100000} 就真去 LIMIT 100000；
 *     · 页码没上限，{@code (pageNum-1)*pageSize} 在 int 上**溢出成负数** → SQL 报错 → 500。
 *   这类问题在页面上表现为"数据少了一条"或"偶尔很慢/偶尔 500"，靠肉眼很难发现。
 *
 * ── 与 HTTP 测试的分工 ──────────────────────────────────
 *   {@code EndpointAuthMatrixTest} 管"这个端点要登录 + 校归属"；
 *   本类管"拿到数据之后，分页参数换算对不对"。两者互不替代。
 */
@ExtendWith(MockitoExtension.class)
class AnswerPaginationTest {

    @Mock
    private AnswerMapper answerMapper;

    @InjectMocks
    private AnswerService answerService;

    @Test
    @DisplayName("第 1 页：offset = 0，LIMIT = pageSize（不能把第一页也偏移掉）")
    void firstPage_offsetShouldBeZero() {
        when(answerMapper.countBySurveyId(7)).thenReturn(3);
        when(answerMapper.findPageBySurveyId(eq(7), anyLong(), anyInt())).thenReturn(List.of(new Answer()));

        PageResult<Answer> page = answerService.listAnswersPage(7, 1, 10);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(answerMapper).findPageBySurveyId(eq(7), offset.capture(), size.capture());
        assertEquals(0L, offset.getValue(), "第 1 页的 offset 必须是 0");
        assertEquals(10, size.getValue());
        assertEquals(3, page.getTotal());
        assertEquals(1, page.getPageNum());
        assertEquals(10, page.getPageSize());
        assertEquals(1, page.getList().size());
    }

    @Test
    @DisplayName("第 3 页、每页 20 条：offset = (3-1)×20 = 40")
    void thirdPage_offsetShouldSkipTwoPages() {
        when(answerMapper.countBySurveyId(7)).thenReturn(100);
        when(answerMapper.findPageBySurveyId(eq(7), anyLong(), anyInt())).thenReturn(List.of());

        answerService.listAnswersPage(7, 3, 20);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        verify(answerMapper).findPageBySurveyId(eq(7), offset.capture(), anyInt());
        assertEquals(40L, offset.getValue());
    }

    @Test
    @DisplayName("★ 参数夹紧：pageNum=0 → 1；pageSize=100000 → 100（不是真去 LIMIT 100000）")
    void invalidParams_shouldBeClamped() {
        when(answerMapper.countBySurveyId(7)).thenReturn(1);
        when(answerMapper.findPageBySurveyId(eq(7), anyLong(), anyInt())).thenReturn(List.of());

        PageResult<Answer> page = answerService.listAnswersPage(7, 0, 100_000);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(answerMapper).findPageBySurveyId(eq(7), offset.capture(), size.capture());
        assertEquals(0L, offset.getValue(), "页码 0 会被夹到 1，offset 仍应是 0");
        assertEquals(PageParams.MAX_PAGE_SIZE, size.getValue());
        assertEquals(1, page.getPageNum(), "响应里回显的应是夹紧后的页码");
        assertEquals(PageParams.MAX_PAGE_SIZE, page.getPageSize(), "响应里回显的应是夹紧后的每页条数");
    }

    @Test
    @DisplayName("★ 页码天文数字：夹到 10000，且 offset 用 long 算、不会溢出成负数")
    void hugePageNum_shouldClampAndNeverOverflow() {
        when(answerMapper.countBySurveyId(7)).thenReturn(1);
        when(answerMapper.findPageBySurveyId(eq(7), anyLong(), anyInt())).thenReturn(List.of());

        PageResult<Answer> page = answerService.listAnswersPage(7, Integer.MAX_VALUE, 100);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        verify(answerMapper).findPageBySurveyId(eq(7), offset.capture(), anyInt());
        assertEquals(PageParams.MAX_PAGE_NUM, page.getPageNum(), "页码应被夹到上限");
        assertEquals((long) (PageParams.MAX_PAGE_NUM - 1) * 100, offset.getValue(),
                "offset 必须是 (10000-1)×100，而不是溢出后的负数");
        assertTrue(offset.getValue() >= 0, "MySQL 的 OFFSET 不接受负数");
    }

    @Test
    @DisplayName("没有答卷（total=0）：不查列表，直接返回空页（少一次无用 SQL）")
    void emptySurvey_shouldNotQueryPage() {
        when(answerMapper.countBySurveyId(7)).thenReturn(0);

        PageResult<Answer> page = answerService.listAnswersPage(7, 1, 10);

        verify(answerMapper, never()).findPageBySurveyId(anyInt(), anyLong(), anyInt());
        assertEquals(0, page.getTotal());
        assertTrue(page.getList().isEmpty());
    }
}

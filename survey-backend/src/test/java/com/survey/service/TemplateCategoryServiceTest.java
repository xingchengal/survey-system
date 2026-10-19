package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.TemplateCategory;
import com.survey.mapper.TemplateCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板分类清单的规则（Mockito，不连库）。
 *
 * ★ 为什么值得测：
 *   分类名会写进两张表（清单 + t_survey.template_category），
 *   长度/空白/重复任何一条漏了，要么 500（列宽），要么清单里出现两个"其他"。
 */
@ExtendWith(MockitoExtension.class)
class TemplateCategoryServiceTest {

    @Mock
    private TemplateCategoryMapper templateCategoryMapper;

    @InjectMocks
    private TemplateCategoryService templateCategoryService;

    private static TemplateCategory category(String name, int sort) {
        TemplateCategory c = new TemplateCategory();
        c.setName(name);
        c.setSortOrder(sort);
        return c;
    }

    @Test
    @DisplayName("listNames：按 mapper 顺序返回，过滤空白名")
    void listNames_shouldKeepMapperOrder() {
        when(templateCategoryMapper.findAll()).thenReturn(List.of(
                category("满意度", 1), category("报名", 2), category(null, 3), category("  ", 4)));

        assertEquals(List.of("满意度", "报名"), templateCategoryService.listNames());
    }

    @Test
    @DisplayName("add：名称 trim 后落库，且展示顺序取当前最大值 + 1")
    void add_shouldTrimAndAppend() {
        when(templateCategoryMapper.countByName("培训反馈")).thenReturn(0);
        when(templateCategoryMapper.countAll()).thenReturn(7);
        when(templateCategoryMapper.maxSortOrder()).thenReturn(7);
        when(templateCategoryMapper.findAll()).thenReturn(List.of(category("满意度", 1), category("培训反馈", 8)));

        List<String> names = templateCategoryService.add("  培训反馈  ");

        assertEquals(List.of("满意度", "培训反馈"), names);
        ArgumentCaptor<TemplateCategory> captor = ArgumentCaptor.forClass(TemplateCategory.class);
        verify(templateCategoryMapper).insertIgnore(captor.capture());
        assertEquals("培训反馈", captor.getValue().getName());
        assertEquals(8, captor.getValue().getSortOrder());
    }

    @Test
    @DisplayName("add：空白名称 / 超 50 字都 400，且不写库")
    void add_shouldRejectBlankAndTooLong() {
        BizException blank = assertThrows(BizException.class, () -> templateCategoryService.add("   "));
        assertTrue(blank.getMessage().contains("不能为空"));

        String tooLong = "分".repeat(51);
        BizException longEx = assertThrows(BizException.class, () -> templateCategoryService.add(tooLong));
        assertTrue(longEx.getMessage().contains("不能超过 50"));

        verify(templateCategoryMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("add：同名分类 400（不覆盖已有分类）")
    void add_shouldRejectDuplicate() {
        when(templateCategoryMapper.countByName("满意度")).thenReturn(1);

        BizException ex = assertThrows(BizException.class,
                () -> templateCategoryService.add("满意度"));
        assertTrue(ex.getMessage().contains("已存在"));
        verify(templateCategoryMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("add：分类总数到上限 100 时 400")
    void add_shouldRejectWhenFull() {
        when(templateCategoryMapper.countByName("新分类")).thenReturn(0);
        when(templateCategoryMapper.countAll()).thenReturn(100);

        BizException ex = assertThrows(BizException.class,
                () -> templateCategoryService.add("新分类"));
        assertTrue(ex.getMessage().contains("最多 100"));
        verify(templateCategoryMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("ensure：已有分类不重复插入；空白名直接返回")
    void ensure_existingOrBlank_shouldNotInsert() {
        when(templateCategoryMapper.countByName("满意度")).thenReturn(1);
        templateCategoryService.ensure("满意度");
        templateCategoryService.ensure(null);
        templateCategoryService.ensure("  ");

        verify(templateCategoryMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("ensure：新分类自动登记（设为模板时的 allow-create 路径）")
    void ensure_newCategory_shouldInsert() {
        when(templateCategoryMapper.countByName("培训反馈")).thenReturn(0);
        when(templateCategoryMapper.countAll()).thenReturn(7);
        when(templateCategoryMapper.maxSortOrder()).thenReturn(7);

        templateCategoryService.ensure(" 培训反馈 ");

        ArgumentCaptor<TemplateCategory> captor = ArgumentCaptor.forClass(TemplateCategory.class);
        verify(templateCategoryMapper).insertIgnore(captor.capture());
        assertEquals("培训反馈", captor.getValue().getName());
    }
}

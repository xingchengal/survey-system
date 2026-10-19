package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.TextLimits;
import com.survey.entity.TemplateCategory;
import com.survey.mapper.TemplateCategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模板分类清单的业务口径。
 *
 * ── 为什么单独一个 Service ──────────────────────────────
 *   分类名要 trim、要限长、要去重、要在"设为模板时自动登记"与"管理员手动新增"两条路径上
 *   走同一套规则。把这些规则写在 Controller 里，第二条入口（SurveyService.setTemplate）就会漏。
 *
 * ── 什么不在这里 ────────────────────────────────────────
 *   · 不判断"分类下有没有模板"：分类允许为空（这正是新增分类按钮存在的原因）；
 *   · 不做权限判断：两个 HTTP 入口都限管理员（@RequireAdmin）；setTemplate 内部调用不需要。
 */
@Service
public class TemplateCategoryService {

    /** 分类总数上限：远大于真实需要，只挡"脚本灌几十万条" */
    private static final int MAX_CATEGORIES = 100;

    @Autowired
    private TemplateCategoryMapper templateCategoryMapper;

    /** 分类名列表（前端下拉 / chips 直接用）。 */
    public List<String> listNames() {
        return templateCategoryMapper.findAll().stream()
                .map(TemplateCategory::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /**
     * 管理员新增分类。
     *
     * @return 新增后的完整分类名列表（前端一次请求即可刷新下拉，不用再 GET 一次）
     * @throws BizException 名称为空 / 超长 / 已存在 / 超过总数上限
     */
    public List<String> add(String name) {
        String normalized = normalize(name);
        if (templateCategoryMapper.countByName(normalized) > 0) {
            throw new BizException("模板分类「" + normalized + "」已存在");
        }
        if (templateCategoryMapper.countAll() >= MAX_CATEGORIES) {
            throw new BizException("模板分类最多 " + MAX_CATEGORIES + " 个");
        }
        insert(normalized);
        return listNames();
    }

    /**
     * 确保分类存在（设为模板时调用）。
     *
     * ★ 为什么用"自动登记"而不是"拒绝未知分类"：前端两个入口都允许现场输入新分类
     *   （allow-create），拒绝会让"设为模板"这条主流程多一步"先去模板管理建分类"，
     *   而用户当时的意图就是建这个分类。自动登记既保留旧行为，又让清单自动长全。
     * ★ INSERT IGNORE：并发登记同一个新分类时不会因为唯一键抛异常。
     */
    public void ensure(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String normalized = normalize(name);
        if (templateCategoryMapper.countByName(normalized) > 0) {
            return;
        }
        if (templateCategoryMapper.countAll() >= MAX_CATEGORIES) {
            throw new BizException("模板分类最多 " + MAX_CATEGORIES + " 个");
        }
        insert(normalized);
    }

    /** 分类名归一化：trim + 非空 + 与列宽一致的长度上限。 */
    private String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("模板分类名称不能为空");
        }
        String normalized = name.trim();
        TextLimits.requireWithin(normalized, TextLimits.TEMPLATE_CATEGORY, "模板分类名称");
        return normalized;
    }

    private void insert(String name) {
        TemplateCategory category = new TemplateCategory();
        category.setName(name);
        category.setSortOrder(templateCategoryMapper.maxSortOrder() + 1);
        templateCategoryMapper.insertIgnore(category);
    }
}

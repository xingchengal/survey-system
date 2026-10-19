package com.survey.mapper;

import com.survey.entity.TemplateCategory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 模板分类清单（t_template_category）。
 *
 * ★ 它只存"有哪些分类"，不存分类下有多少模板 —— 数量永远由 t_survey 现算，
 *   否则模板改分类/取消模板时要同步维护计数，漏一处就长期对不上。
 */
@Mapper
public interface TemplateCategoryMapper {

    /** 全部分类，按展示顺序（同序按 id，保证稳定）。 */
    @Select("SELECT * FROM t_template_category ORDER BY sort_order ASC, id ASC")
    List<TemplateCategory> findAll();

    /** 同名分类数量（唯一键是最终防线，这里是给用户友好 400 的预检）。 */
    @Select("SELECT COUNT(*) FROM t_template_category WHERE name = #{name}")
    int countByName(@Param("name") String name);

    /** 当前最大展示顺序；空表返回 0。 */
    @Select("SELECT IFNULL(MAX(sort_order), 0) FROM t_template_category")
    int maxSortOrder();

    /** 分类总数（上限校验用）。 */
    @Select("SELECT COUNT(*) FROM t_template_category")
    int countAll();

    /**
     * 新增分类。
     * ★ INSERT IGNORE 供"设为模板时自动登记分类"使用：两个请求同时登记同一个新分类时，
     *   后到的会被唯一键忽略，不抛异常、也不影响这次设为模板。
     */
    @Insert("INSERT IGNORE INTO t_template_category(name, sort_order) VALUES(#{name}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(TemplateCategory category);
}

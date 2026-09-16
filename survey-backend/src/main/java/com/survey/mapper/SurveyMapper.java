package com.survey.mapper;

import com.survey.entity.Survey;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SurveyMapper {

    // ★ 修复问题 1：加上 view_count
    @Insert("INSERT INTO t_survey(user_id, title, description, status, share_code, deleted, " +
            "start_time, end_time, is_template, template_category, view_count) " +
            "VALUES(#{userId}, #{title}, #{description}, #{status}, #{shareCode}, 0, " +
            "#{startTime}, #{endTime}, #{isTemplate}, #{templateCategory}, " +
            "IFNULL(#{viewCount}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Survey survey);

    @Update("UPDATE t_survey SET deleted = 1, deleted_by = #{userId} WHERE id = #{id}")
    int moveToRecycle(@Param("id") Integer id, @Param("userId") Integer userId);

    @Update("UPDATE t_survey SET deleted = 0, deleted_by = NULL WHERE id = #{id}")
    int restoreFromRecycle(Integer id);

    @Delete("DELETE FROM t_survey WHERE id = #{id}")
    int deleteById(Integer id);

    @Update("UPDATE t_survey SET title=#{title}, description=#{description}, " +
            "status=#{status}, share_code=#{shareCode}, " +
            "start_time=#{startTime}, end_time=#{endTime} WHERE id=#{id}")
    int update(Survey survey);

    @Select("SELECT * FROM t_survey WHERE deleted = 0 ORDER BY created_time DESC")
    List<Survey> findAll();

    @Select("SELECT * FROM t_survey WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_time DESC")
    List<Survey> findByUserId(Integer userId);

    @Select("SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.user_id = #{userId} AND s.deleted = 1 ORDER BY s.created_time DESC")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findRecycleByUserId(Integer userId);

    @Select("SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.deleted = 1 ORDER BY s.created_time DESC")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findAllRecycle();

    @Select("SELECT * FROM t_survey WHERE id = #{id}")
    Survey findById(Integer id);

    @Select("SELECT * FROM t_survey WHERE share_code = #{shareCode} AND deleted = 0")
    Survey findByShareCode(String shareCode);

    // 分页查询（排除模板，问题 8 已在此修复）
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR description LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='status != null'>" +
            "AND status = #{status} " +
            "</if>" +
            "ORDER BY created_time DESC" +
            "</script>")
    List<Survey> findPageByCondition(@Param("keyword") String keyword,
                                     @Param("status") Integer status);

    @Select("SELECT * FROM t_survey WHERE user_id = #{userId}")
    List<Survey> findByUserIdIncludeDeleted(@Param("userId") Integer userId);

    @Select("SELECT * FROM t_survey WHERE deleted = 0 ORDER BY created_time DESC")
    List<Survey> findAllNormal();

    @Select("SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) ORDER BY created_time DESC")
    List<Survey> findAllNonTemplate();

    @Select("SELECT * FROM t_survey WHERE id = #{id}")
    Survey findByIdSimple(Integer id);

    // 模板相关
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE is_template = 1 AND deleted = 0 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR template_category LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY id ASC" +
            "</script>")
    List<Survey> findTemplates(@Param("keyword") String keyword);

    @Update("UPDATE t_survey SET is_template = #{isTemplate}, template_category = #{category} WHERE id = #{id}")
    int setTemplate(@Param("id") Integer id,
                    @Param("isTemplate") Integer isTemplate,
                    @Param("category") String category);

    // 访问次数
    @Update("UPDATE t_survey SET view_count = view_count + 1 WHERE share_code = #{shareCode}")
    int incrementViewCount(String shareCode);

    @Select("SELECT IFNULL(SUM(view_count), 0) FROM t_survey WHERE deleted = 0")
    int sumViewCount();

    // 定时任务
    @Select("SELECT * FROM t_survey WHERE status = 0 AND deleted = 0 " +
            "AND start_time IS NOT NULL AND start_time <= NOW()")
    List<Survey> findPendingPublish();

    @Select("SELECT * FROM t_survey WHERE status = 1 AND deleted = 0 " +
            "AND end_time IS NOT NULL AND end_time <= NOW()")
    List<Survey> findPendingClose();

    // 回收站分页
    @Select("<script>" +
            "SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.deleted = 1 AND s.user_id = #{userId} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND s.title LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY s.created_time DESC" +
            "</script>")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findRecyclePageByUser(@Param("userId") Integer userId,
                                       @Param("keyword") String keyword);

    @Select("<script>" +
            "SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.deleted = 1 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND s.title LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY s.created_time DESC" +
            "</script>")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findRecyclePageAll(@Param("keyword") String keyword);
}
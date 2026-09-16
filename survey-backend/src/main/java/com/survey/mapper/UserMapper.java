package com.survey.mapper;

import com.survey.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM t_user WHERE username = #{username}")
    User findByUsername(String username);

    @Insert("INSERT INTO t_user(username, password, nickname, role) VALUES(#{username}, #{password}, #{nickname}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    // 查询所有普通用户（支持按用户名/昵称搜索）
    @Select("<script>" +
            "SELECT * FROM t_user WHERE role = 'user' " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY created_time DESC" +
            "</script>")
    List<User> findAllNormalUsers(@Param("keyword") String keyword);

    // 删除用户
    @Delete("DELETE FROM t_user WHERE id = #{id}")
    int deleteById(Integer id);

    // 统计普通用户数量
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'user'")
    int countNormalUsers();

    @Select("SELECT * FROM t_user WHERE id = #{id}")
    User findById(Integer id);

    @Update("UPDATE t_user SET nickname = #{nickname} WHERE id = #{id}")
    int updateNickname(@Param("id") Integer id, @Param("nickname") String nickname);

    @Update("UPDATE t_user SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Integer id, @Param("password") String password);

    @Update("UPDATE t_user SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Integer id, @Param("avatar") String avatar);

    // 查询所有演示用户
    @Select("SELECT * FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    List<User> findByUsernamePrefix(@Param("prefix") String prefix);

    // 统计演示用户数量
    @Select("SELECT COUNT(*) FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    int countByUsernamePrefix(@Param("prefix") String prefix);

    // 更新联系方式
    @Update("UPDATE t_user SET phone = #{phone}, email = #{email} WHERE id = #{id}")
    int updateContact(@Param("id") Integer id,
                      @Param("phone") String phone,
                      @Param("email") String email);
}
package cn.campus.errand.student;

import org.apache.ibatis.annotations.*;

@Mapper
public interface StudentMapper {
    @Select("SELECT id, app_id, wechat_openid, nickname, contact, enabled FROM student_user WHERE app_id=#{appId} AND wechat_openid=#{openId}")
    StudentAccount findByIdentity(@Param("appId") String appId, @Param("openId") String openId);

    @Select("SELECT id, app_id, wechat_openid, nickname, contact, enabled FROM student_user WHERE id=#{id}")
    StudentAccount findById(long id);

    @Insert("INSERT INTO student_user (app_id, wechat_openid, nickname) VALUES (#{appId}, #{openId}, '微信用户')")
    int insert(@Param("appId") String appId, @Param("openId") String openId);
}

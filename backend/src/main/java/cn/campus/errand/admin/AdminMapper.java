package cn.campus.errand.admin;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminMapper {
    @Select("SELECT id, username, password_hash, display_name, enabled FROM admin_account WHERE username = #{username}")
    AdminAccount findByUsername(String username);

    @Select("SELECT id, username, password_hash, display_name, enabled FROM admin_account WHERE id = #{id}")
    AdminAccount findById(long id);

    @Insert("INSERT INTO admin_account (username, password_hash, display_name) VALUES (#{username}, #{passwordHash}, #{displayName})")
    int insert(@Param("username") String username, @Param("passwordHash") String passwordHash,
               @Param("displayName") String displayName);
}

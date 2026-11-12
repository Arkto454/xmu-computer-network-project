package cn.campus.errand.admin;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.admin.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final AdminMapper mapper;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrap(AdminMapper mapper, PasswordEncoder encoder,
                          @Value("${app.admin.bootstrap.username}") String username,
                          @Value("${app.admin.bootstrap.password}") String password,
                          @Value("${app.admin.bootstrap.display-name}") String displayName) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.username = username.trim();
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || username.length() > 64 || password.isBlank() || password.length() < 8
                || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || displayName.isBlank() || displayName.length() > 64) {
            throw new IllegalStateException("设置有效的初始化账号、8至64字符且不超过72字节的密码，以及显示名称");
        }
        if (mapper.findByUsername(username) == null) {
            mapper.insert(username, encoder.encode(password), displayName);
        }
        // 已存在时不重置密码；初始化完成后应关闭 bootstrap。
    }
}

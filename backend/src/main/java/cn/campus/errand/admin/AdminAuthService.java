package cn.campus.errand.admin;

import cn.campus.errand.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import static cn.campus.errand.admin.AuthContracts.*;

@Service
public class AdminAuthService {
    private final AdminMapper mapper;
    private final PasswordEncoder encoder;
    private final AdminTokenStore tokens;
    private final String dummyHash;

    public AdminAuthService(AdminMapper mapper, PasswordEncoder encoder, AdminTokenStore tokens) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.tokens = tokens;
        this.dummyHash = encoder.encode("unused-account-password");
    }

    public LoginResult login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "密码 UTF-8 长度不能超过 72 字节");
        }
        AdminAccount account = mapper.findByUsername(request.username().trim());
        boolean matches = encoder.matches(request.password(), account == null ? dummyHash : account.passwordHash());
        if (account == null || !matches || !account.enabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "账号或密码错误，或账号不可用");
        }
        var token = tokens.issue(account.id());
        return new LoginResult(token.value(), "Bearer", token.expiresAt(), AdminProfile.from(account));
    }

    public AdminProfile authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw unauthorized();
        }
        Long id = tokens.resolve(authorization.substring(7).trim());
        AdminAccount account = id == null ? null : mapper.findById(id);
        if (account == null || !account.enabled()) throw unauthorized();
        return AdminProfile.from(account);
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请登录，或重新获取有效登录凭据");
    }
}

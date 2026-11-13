package cn.campus.errand.student;

import cn.campus.errand.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static cn.campus.errand.student.StudentContracts.*;

@Service
public class StudentAuthService {
    private final WeChatGateway wechat;
    private final StudentMapper mapper;
    private final StudentTokenStore tokens;

    public StudentAuthService(WeChatGateway wechat, StudentMapper mapper, StudentTokenStore tokens) {
        this.wechat = wechat;
        this.mapper = mapper;
        this.tokens = tokens;
    }

    public LoginResult login(LoginRequest request) {
        var identity = wechat.exchange(request.code());
        StudentAccount student = mapper.findByIdentity(identity.appId(), identity.openId());
        if (student == null) {
            try { mapper.insert(identity.appId(), identity.openId()); }
            catch (DuplicateKeyException e) {
                // 并发首次登录时，另一请求已创建该身份，下面重新读取同一账号。
                if (mapper.findByIdentity(identity.appId(), identity.openId()) == null) throw e;
            }
            student = mapper.findByIdentity(identity.appId(), identity.openId());
        }
        if (student == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "登录暂时不可用");
        if (!student.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已被禁用");
        var token = tokens.issue(student.id());
        return new LoginResult(token.value(), "Bearer", token.expiresAt(), StudentProfile.from(student));
    }

    public StudentProfile authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw unauthorized();
        Long id = tokens.resolve(authorization.substring(7).trim());
        StudentAccount student = id == null ? null : mapper.findById(id);
        if (student == null || !student.enabled()) throw unauthorized();
        return StudentProfile.from(student);
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请通过小程序登录或重新获取登录凭据");
    }
}

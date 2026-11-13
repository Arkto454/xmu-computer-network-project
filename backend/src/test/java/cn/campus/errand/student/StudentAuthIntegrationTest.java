package cn.campus.errand.student;

import cn.campus.errand.admin.AdminTokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static cn.campus.errand.student.StudentContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudentMapper mapper;
    @Autowired StudentAuthService service;
    @Autowired AdminTokenStore adminTokens;
    @MockitoBean WeChatGateway wechat;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM student_user");
        when(wechat.exchange(anyString())).thenReturn(new WeChatGateway.Identity("test-app", "private-openid"));
    }

    private LoginResult login(String code) { return service.login(new LoginRequest(code)); }

    @Test
    void firstLoginCreatesStudentAndProfileDoesNotExposeWechatIdentity() throws Exception {
        String body = json.writeValueAsString(Map.of("code", "valid-code", "openid", "forged", "appId", "forged"));
        var result = mvc.perform(post("/api/student/auth/wechat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.student.nickname").value("微信用户"))
                .andExpect(content().string(not(containsString("private-openid"))))
                .andExpect(jsonPath("$.data.student.wechatOpenid").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(result).path("data").path("accessToken").asText();
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("微信用户"));
        assertThat(mapper.findByIdentity("test-app", "private-openid")).isNotNull();
        assertThat(mapper.findByIdentity("forged", "forged")).isNull();
        verify(wechat).exchange("valid-code");
    }

    @Test
    void returningStudentKeepsIdentityAndProfileAndReplacesToken() throws Exception {
        var first = login("first");
        jdbc.update("UPDATE student_user SET nickname=?, contact=? WHERE id=?", "校园同学", "demo-contact", first.student().id());
        var second = login("fresh-code");
        assertThat(second.student().id()).isEqualTo(first.student().id());
        assertThat(second.student().nickname()).isEqualTo("校园同学");
        assertThat(second.student().contact()).isEqualTo("demo-contact");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_user", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer " + first.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer " + second.accessToken())).andExpect(status().isOk());
    }

    @Test
    void missingAndInvalidTokensAreRejected() throws Exception {
        mvc.perform(get("/api/student/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer unknown")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/me").header("Authorization", "Basic anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void studentAndAdminCredentialsAreNotInterchangeable() throws Exception {
        var student = login("valid-code");
        var admin = adminTokens.issue(student.student().id());
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + student.accessToken())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer " + admin.value())).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledStudentCannotLoginOrAccessProfile() throws Exception {
        var student = login("first");
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", student.student().id());
        mvc.perform(post("/api/student/auth/wechat").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"fresh-code\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(get("/api/student/me").header("Authorization", "Bearer " + student.accessToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void blankMissingMalformedAndOverlongCodesFailBeforeWechatCall() throws Exception {
        for (String body : List.of("{}", "{", "{\"code\":\" \"}", json.writeValueAsString(Map.of("code", "a".repeat(513))))) {
            mvc.perform(post("/api/student/auth/wechat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(wechat);
    }

    @Test
    void wechatFailureDoesNotCreateUser() throws Exception {
        when(wechat.exchange("invalid")).thenThrow(new cn.campus.errand.common.ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "WECHAT_LOGIN_FAILED", "微信凭证无效"));
        mvc.perform(post("/api/student/auth/wechat").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"invalid\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WECHAT_LOGIN_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_user", Integer.class)).isZero();
    }

    @Test
    void wechatIdentityIsScopedByAppAndCaseSensitive() {
        when(wechat.exchange("other-app")).thenReturn(new WeChatGateway.Identity("other-app", "private-openid"));
        when(wechat.exchange("other-case")).thenReturn(new WeChatGateway.Identity("test-app", "PRIVATE-OPENID"));
        long first = login("first").student().id();
        assertThat(login("other-app").student().id()).isNotEqualTo(first);
        assertThat(login("other-case").student().id()).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_user", Integer.class)).isEqualTo(3);
    }

    @Test
    void concurrentFirstLoginsCreateOneStudent() throws Exception {
        var ready = new CountDownLatch(4);
        when(wechat.exchange(startsWith("race-"))).thenAnswer(invocation -> {
            ready.countDown();
            if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Login threads did not rendezvous");
            return new WeChatGateway.Identity("test-app", "concurrent-openid");
        });
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String freshCode = "race-" + i;
                results.add(executor.submit(() -> login(freshCode).student().id()));
            }
            long id = results.get(0).get(10, TimeUnit.SECONDS);
            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM student_user", Integer.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
}

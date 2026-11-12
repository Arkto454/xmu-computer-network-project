package cn.campus.errand.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthIntegrationTest {
    private static final String PASSWORD = "IntegrationOnly123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminMapper mapper;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM admin_account");
        mapper.insert("test_admin", encoder.encode(PASSWORD), "测试管理员");
    }

    private String body(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String login() throws Exception {
        var response = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("test_admin", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.admin.username").value("test_admin"))
                .andExpect(jsonPath("$.data.admin.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("accessToken").asText();
    }

    @Test
    void loginAndCurrentAdminUseRealMapperAndHashedPassword() throws Exception {
        assertThat(mapper.findByUsername("test_admin").passwordHash()).isNotEqualTo(PASSWORD);
        String token = login();
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("测试管理员"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void incorrectPasswordAndUnknownAccountAreRejected() throws Exception {
        for (String request : new String[]{body("test_admin", "wrong"), body("unknown", PASSWORD), body("' OR 1=1 --", PASSWORD)}) {
            mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
        }
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        jdbc.update("UPDATE admin_account SET enabled = FALSE WHERE username = ?", "test_admin");
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("test_admin", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountCannotReuseOldToken() throws Exception {
        String token = login();
        jdbc.update("UPDATE admin_account SET enabled = FALSE WHERE username = ?", "test_admin");
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingInvalidAndWrongSchemeCredentialsAreRejected() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        for (String header : new String[]{"Bearer invalid", "Basic abc", "Bearer "}) {
            mvc.perform(get("/api/admin/me").header("Authorization", header)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void malformedBlankAndOverlongUtf8InputsAreRejected() throws Exception {
        for (String request : new String[]{"{", "{}", body(" ", PASSWORD), body("test_admin", "密".repeat(30))}) {
            mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void newLoginReplacesOldCredential() throws Exception {
        String oldToken = login();
        String newToken = login();
        assertThat(newToken).isNotEqualTo(oldToken);
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + newToken)).andExpect(status().isOk());
    }

    @Test
    void healthActuallyQueriesDatabase() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.database").value("UP"));
    }
}

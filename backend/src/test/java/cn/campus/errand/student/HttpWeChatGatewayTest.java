package cn.campus.errand.student;

import cn.campus.errand.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpWeChatGatewayTest {
    MockRestServiceServer server;
    HttpWeChatGateway gateway;

    @BeforeEach
    void prepare() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpWeChatGateway(builder.build(), new WeChatProperties("test-app", "test-secret"), new ObjectMapper());
    }

    @Test
    void exchangesCodeAtOfficialEndpointAndIgnoresSessionKey() {
        server.expect(requestTo(startsWith("https://api.weixin.qq.com/sns/jscode2session?")))
                .andExpect(queryParam("appid", "test-app")).andExpect(queryParam("secret", "test-secret"))
                .andExpect(queryParam("js_code", "login-code")).andExpect(queryParam("grant_type", "authorization_code"))
                .andRespond(withSuccess("{\"openid\":\"server-openid\",\"session_key\":\"not-a-login-token\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.exchange("login-code")).isEqualTo(new WeChatGateway.Identity("test-app", "server-openid"));
        server.verify();
    }

    @Test
    void missingConfigurationFailsWithoutNetworkCall() {
        var builder = RestClient.builder();
        var unconfigured = new HttpWeChatGateway(builder.build(), new WeChatProperties("", ""), new ObjectMapper());
        assertThatThrownBy(() -> unconfigured.exchange("code")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("WECHAT_NOT_CONFIGURED"));
    }

    @ParameterizedTest
    @ValueSource(ints = {40029, 40163})
    void invalidOrUsedCodeBecomesAuthenticationFailure(int errorCode) {
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":" + errorCode + "}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.exchange("invalid")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("WECHAT_LOGIN_FAILED"));
    }

    @Test
    void upstreamHttpFailureDoesNotExposeSecretOrCode() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.exchange("private-code"))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("test-secret").hasMessageNotContaining("private-code");
    }

    @Test
    void busyOrMisconfiguredWechatDoesNotLookLikeSuccessfulLogin() {
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":45011}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.exchange("code")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("WECHAT_UNAVAILABLE"));
    }

    @Test
    void missingOpenidFailsClosed() {
        server.expect(anything()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.exchange("code")).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidJsonFailsClosed() {
        server.expect(anything()).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.exchange("code")).isInstanceOf(ApiException.class);
    }

    @Test
    void codeCannotInjectAdditionalQueryParameters() {
        server.expect(requestTo(containsString("js_code=code%2Bwith%26extra%3D1")))
                .andRespond(withSuccess("{\"openid\":\"server-openid\"}", MediaType.APPLICATION_JSON));
        assertThat(gateway.exchange("code+with&extra=1").openId()).isEqualTo("server-openid");
        server.verify();
    }

    @Test
    void parsesJsonEvenWhenUpstreamLabelsItAsPlainText() {
        server.expect(anything()).andRespond(withSuccess("{\"openid\":\"server-openid\"}", MediaType.TEXT_PLAIN));
        assertThat(gateway.exchange("code").openId()).isEqualTo("server-openid");
        server.verify();
    }
}

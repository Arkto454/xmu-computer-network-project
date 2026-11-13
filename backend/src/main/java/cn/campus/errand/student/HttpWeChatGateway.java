package cn.campus.errand.student;

import cn.campus.errand.common.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpWeChatGateway implements WeChatGateway {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionResponse(String openid, Integer errcode) {}
    private final RestClient client;
    private final WeChatProperties properties;
    private final ObjectMapper json;

    public HttpWeChatGateway(RestClient client, WeChatProperties properties, ObjectMapper json) {
        this.client = client;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Identity exchange(String code) {
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_NOT_CONFIGURED", "微信登录尚未配置");
        }
        SessionResponse response;
        try {
            String body = client.get().uri(builder -> builder.scheme("https").host("api.weixin.qq.com")
                    .path("/sns/jscode2session").queryParam("appid", properties.appId())
                    .queryParam("secret", properties.appSecret()).queryParam("js_code", "{code}")
                    .queryParam("grant_type", "authorization_code").build(code))
                    .retrieve().body(String.class);
            response = body == null ? null : json.readValue(body, SessionResponse.class);
        } catch (RestClientException | JsonProcessingException e) {
            // 原异常可能含带有 AppSecret 的 URL，不把它传入通用日志或响应。
            throw unavailable();
        }
        if (response == null) throw unavailable();
        if (response.errcode() != null && response.errcode() != 0) {
            if (response.errcode() == 40029 || response.errcode() == 40163) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "WECHAT_LOGIN_FAILED", "微信登录凭证无效或已使用，请重新登录");
            }
            throw unavailable();
        }
        if (!StringUtils.hasText(response.openid()) || response.openid().length() > 128 || properties.appId().length() > 64) {
            throw unavailable();
        }
        return new Identity(properties.appId(), response.openid());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE", "微信登录暂时不可用，请稍后重试");
    }
}

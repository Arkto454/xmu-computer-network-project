package cn.campus.errand.student;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.wechat")
public record WeChatProperties(String appId, String appSecret) {
    @Override
    public String toString() { return "WeChatProperties[appId=" + appId + ", appSecret=***]"; }
}

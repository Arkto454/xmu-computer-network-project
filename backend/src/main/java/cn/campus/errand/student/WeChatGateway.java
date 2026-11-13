package cn.campus.errand.student;

public interface WeChatGateway {
    record Identity(String appId, String openId) {}
    Identity exchange(String code);
}

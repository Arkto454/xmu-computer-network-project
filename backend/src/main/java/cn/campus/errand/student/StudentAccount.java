package cn.campus.errand.student;

public record StudentAccount(Long id, String appId, String wechatOpenid, String nickname, String contact, boolean enabled) {}

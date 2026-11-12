package cn.campus.errand.admin;

// 持久化对象只在后端内部使用，不直接作为接口响应。
public record AdminAccount(Long id, String username, String passwordHash, String displayName, boolean enabled) {}

package cn.campus.errand.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthContracts {
    private AuthContracts() {}

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 64) String password) {}
    public record AdminProfile(long id, String username, String displayName) {
        static AdminProfile from(AdminAccount account) {
            return new AdminProfile(account.id(), account.username(), account.displayName());
        }
    }
    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, AdminProfile admin) {}
}

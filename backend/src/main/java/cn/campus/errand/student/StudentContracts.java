package cn.campus.errand.student;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class StudentContracts {
    private StudentContracts() {}
    public record LoginRequest(@NotBlank @Size(max = 512) String code) {}
    public record StudentProfile(long id, String nickname, String contact) {
        static StudentProfile from(StudentAccount account) {
            return new StudentProfile(account.id(), account.nickname(), account.contact());
        }
    }
    public record LoginResult(String accessToken, String tokenType, Instant expiresAt, StudentProfile student) {}
}

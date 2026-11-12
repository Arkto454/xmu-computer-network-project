package cn.campus.errand.admin;

import cn.campus.errand.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.admin.AuthContracts.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminAuthService auth;
    public AdminController(AdminAuthService auth) { this.auth = auth; }

    @PostMapping("/auth/login")
    ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(auth.login(request));
    }

    @GetMapping("/me")
    ApiResponse<AdminProfile> me(@RequestAttribute("currentAdmin") AdminProfile admin) {
        return ApiResponse.success(admin);
    }
}

package cn.campus.errand.student;

import cn.campus.errand.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.campus.errand.student.StudentContracts.*;

@RestController
@RequestMapping("/api/student")
public class StudentController {
    private final StudentAuthService auth;
    public StudentController(StudentAuthService auth) { this.auth = auth; }

    @PostMapping("/auth/wechat")
    ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(auth.login(request));
    }

    @GetMapping("/me")
    ApiResponse<StudentProfile> me(@RequestAttribute("currentStudent") StudentProfile student) {
        return ApiResponse.success(student);
    }
}

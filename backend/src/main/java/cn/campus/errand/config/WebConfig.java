package cn.campus.errand.config;

import cn.campus.errand.admin.AdminAuthService;
import cn.campus.errand.student.StudentAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminAuthService auth;
    private final StudentAuthService studentAuth;
    public WebConfig(AdminAuthService auth, StudentAuthService studentAuth) {
        this.auth = auth;
        this.studentAuth = studentAuth;
    }

    @Bean
    static PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute("currentAdmin", auth.authenticate(request.getHeader("Authorization")));
                return true;
            }
        }).addPathPatterns("/api/admin/**").excludePathPatterns("/api/admin/auth/login");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute("currentStudent", studentAuth.authenticate(request.getHeader("Authorization")));
                return true;
            }
        }).addPathPatterns("/api/student/**").excludePathPatterns("/api/student/auth/wechat");
    }
}

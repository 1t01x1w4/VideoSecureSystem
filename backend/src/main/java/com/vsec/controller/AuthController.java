package com.vsec.controller;

import com.vsec.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // ==================== DTO ====================

    public record SendCodeRequest(
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "register|login") String purpose
    ) {}

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    // ==================== 接口 ====================

    /**
     * 发送验证码
     * POST /api/auth/code
     */
    @PostMapping("/code")
    public ResponseEntity<Map<String, String>> sendCode(
            @RequestBody SendCodeRequest req,
            HttpServletRequest request) {
        authService.sendVerificationCode(req.email, req.purpose, request);
        return ResponseEntity.ok(Map.of("message", "验证码已发送"));
    }

    /**
     * 注册
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @RequestBody RegisterRequest req,
            HttpServletRequest request) {
        Map<String, String> result = authService.register(
                req.email, req.password, req.code, request);
        return ResponseEntity.ok(result);
    }

    /**
     * 登录
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest req,
            HttpServletRequest request) {
        Map<String, Object> result = authService.login(
                req.email, req.password, req.code, request);
        return ResponseEntity.ok(result);
    }

    /**
     * 登出
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }
}

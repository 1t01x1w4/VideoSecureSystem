package com.vsec.service;

import com.vsec.entity.User;
import com.vsec.repository.UserRepository;
import com.vsec.crypto.CryptoUtil;
import com.vsec.exception.VsecException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String SESSION_ENC_KEY = "enc_key";
    private static final String SESSION_UUID = "user_uuid";

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;

    @Value("${app.security.argon2.memory-kb}")
    private int argonMemoryKb;

    @Value("${app.security.argon2.iterations}")
    private int argonIterations;

    @Value("${app.security.argon2.parallelism}")
    private int argonParallelism;

    @Value("${app.security.code.ttl-minutes}")
    private int codeTtlMinutes;

    @Value("${app.security.code.length}")
    private int codeLength;

    @Value("${app.security.login.max-attempts}")
    private int maxLoginAttempts;

    @Value("${app.security.login.lock-minutes}")
    private int lockMinutes;

    @Value("${spring.mail.username}")
    private String mailFrom;

    @Value("${spring.mail.dev-mode:true}")
    private boolean mailDevMode;

    @Value("${app.security.server-secret:vsec-server-secret-dev}")
    private String serverSecret;

    public AuthService(UserRepository userRepository,
                       AuditLogService auditLogService,
                       StringRedisTemplate redis,
                       JavaMailSender mailSender) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.redis = redis;
        this.mailSender = mailSender;
    }

    // ==================== 验证码 ====================

    /**
     * 生成并"发送"验证码（开发环境打印到控制台）
     * 存入 Redis，key: code:{purpose}:{email}，TTL: 5 分钟
     */
    public void sendVerificationCode(String email, String purpose, HttpServletRequest request) {
        // 检查发送频率（60 秒内只允许发一次）
        // L4: 移除 purpose 防止交替绕过
        String rateKey = "rate:code:" + email;
        if (Boolean.TRUE.equals(redis.hasKey(rateKey))) {
            throw new VsecException("验证码发送过于频繁，请 60 秒后再试");
        }

        String code = generateCode(codeLength);
        String codeKey = "code:" + purpose + ":" + email;
        redis.opsForValue().set(codeKey, code, codeTtlMinutes, TimeUnit.MINUTES);
        redis.opsForValue().set(rateKey, "1", 60, TimeUnit.SECONDS);

        if (mailDevMode) {
            log.info("============================================");
            log.info("  [DEV] 验证码发送到: {}", email);
            log.info("  [DEV] 用途: {}", purpose);
            log.info("  [DEV] 验证码: {}", code);
            log.info("  有效期: {} 分钟", codeTtlMinutes);
            log.info("============================================");
        } else {
            // TODO: 生产环境通过 SMTP 发送邮件
            sendEmail(email, code, purpose);
        }
    }

    // ==================== 注册 ====================

    public Map<String, String> register(String email, String password, String code,
                                         HttpServletRequest request) {
        // M10: 注册频率限制（每 IP 每小时最多 3 次）
        String regRateKey = "rate:register:" + request.getRemoteAddr();
        String regCount = redis.opsForValue().get(regRateKey);
        if (regCount != null && Integer.parseInt(regCount) >= 3) {
            throw new VsecException("注册过于频繁，请稍后再试");
        }

        // 校验验证码
        try {
            verifyCode(email, "register", code);
        } catch (RuntimeException e) {
            auditLogService.record(null, "REGISTER", null, request, "FAIL", "验证码校验失败");
            throw e;
        }

        // 检查邮箱是否已注册
        if (userRepository.existsByEmail(email)) {
            auditLogService.record(null, "REGISTER", null, request, "FAIL", "邮箱已注册");
            throw new VsecException("该邮箱已注册");
        }

        // 校验密码强度
        if (!isPasswordStrong(password)) {
            auditLogService.record(null, "REGISTER", null, request, "FAIL", "弱密码");
            throw new VsecException("口令至少8位，需包含大小写字母和数字");
        }

        // 生成用户 UUID
        String uuid = UUID.randomUUID().toString();

        // 生成盐 + 派生密钥
        byte[] salt = CryptoUtil.generateSalt();
        byte[] authKey = CryptoUtil.deriveAuthKey(password, salt,
                argonMemoryKb, argonIterations, argonParallelism);

        // 存储用户
        User user = new User(uuid, email, salt, authKey);
        userRepository.save(user);

        // 记录注册速率
        Long newRegCount = redis.opsForValue().increment(regRateKey);
        if (newRegCount != null && newRegCount == 1) {
            redis.expire(regRateKey, 1, TimeUnit.HOURS);
        }

        // 记录审计日志
        auditLogService.record(uuid, "REGISTER", null, request, "SUCCESS", null);

        log.info("新用户注册: uuid={}, email={}", uuid, email);
        return Map.of("uuid", uuid, "email", email);
    }

    // ==================== 登录 ====================

    public Map<String, Object> login(String email, String password, String code,
                                      HttpServletRequest request) {
        // 检查锁定状态
        checkLockStatus(email);

        // 校验验证码
        verifyCode(email, "login", code);

        // 查找用户
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    recordLoginFailure(email, request);
                    return new RuntimeException("邮箱或口令错误");
                });

        if (!"active".equals(user.getStatus())) {
            throw new VsecException("邮箱或口令错误");
        }

        // 派生密钥并比对 auth_key
        CryptoUtil.DerivedKeys keys = CryptoUtil.deriveKeys(password, user.getSalt(),
                argonMemoryKb, argonIterations, argonParallelism);

        boolean matched = CryptoUtil.constantTimeEquals(keys.authKey(), user.getAuthKey());
        if (!matched) {
            CryptoUtil.wipe(keys.encKey());
            CryptoUtil.wipe(keys.authKey());
            recordLoginFailure(email, request);
            throw new VsecException("邮箱或口令错误");
        }

        // 登录成功：作废旧会话、创建新会话，防 Session 固定攻击
        HttpSession session = request.getSession(true);
        request.changeSessionId();

        // enc_key 用服务端密钥加密后再存 Session，防 Redis 明文泄露
        byte[] wrappedEncKey = CryptoUtil.encryptWithServerKey(keys.encKey(), serverSecret);
        session.setAttribute(SESSION_ENC_KEY,
                Base64.getEncoder().encodeToString(wrappedEncKey));
        session.setAttribute(SESSION_UUID, user.getUuid());
        session.setMaxInactiveInterval(30 * 60); // 30 分钟

        // 作废旧 Session（单设备登录）
        String sessionKey = "user:session:" + user.getUuid();
        String oldSessionId = redis.opsForValue().get(sessionKey);
        if (oldSessionId != null) {
            redis.delete("vsec:session:sessions:" + oldSessionId);
        }
        redis.opsForValue().set(sessionKey, session.getId(), 30, TimeUnit.MINUTES);

        // 设置 Spring Security 认证上下文，使后续请求通过认证检查
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                email, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authToken);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());

        // 清除失败计数
        redis.delete("login:fail:" + email);

        // 记录审计日志
        auditLogService.record(user.getUuid(), "LOGIN", null, request, "SUCCESS", null);

        // 擦除 auth_key 和 enc_key（不需要保存）
        CryptoUtil.wipe(keys.authKey());
        CryptoUtil.wipe(keys.encKey());

        log.info("用户登录成功: uuid={}, email={}", user.getUuid(), email);
        return Map.of("message", "登录成功", "uuid", user.getUuid(), "email", user.getEmail());
    }

    // ==================== 登出 ====================

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String uuid = (String) session.getAttribute(SESSION_UUID);
            // L5: 登出审计日志
            auditLogService.record(uuid, "LOGOUT", null, request, "SUCCESS", null);
            // 显式擦除 enc_key
            session.removeAttribute(SESSION_ENC_KEY);
            session.invalidate();
            if (uuid != null) {
                log.info("用户登出: uuid={}", uuid);
            }
        }
    }

    // ==================== 内部方法 ====================

    private void verifyCode(String email, String purpose, String code) {
        String codeKey = "code:" + purpose + ":" + email;
        String failKey = "code:fail:" + purpose + ":" + email;

        // 验证码爆破防护：连续 10 次错误即作废
        String failCount = redis.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount) >= 10) {
            redis.delete(codeKey);
            redis.delete(failKey);
            throw new VsecException("验证码已失效，请重新获取");
        }

        String storedCode = redis.opsForValue().get(codeKey);
        if (storedCode == null) {
            throw new VsecException("验证码已过期或未发送");
        }
        if (!storedCode.equals(code)) {
            Long newCount = redis.opsForValue().increment(failKey);
            if (newCount != null && newCount == 1) {
                redis.expire(failKey, codeTtlMinutes, TimeUnit.MINUTES);
            }
            throw new VsecException("验证码错误");
        }
        // 验证通过，删除验证码和失败计数
        redis.delete(codeKey);
        redis.delete(failKey);
    }

    // M4: 锁定账户返回与密码错误相同的消息，防止邮箱枚举
    private void checkLockStatus(String email) {
        String failKey = "login:fail:" + email;
        String failCount = redis.opsForValue().get(failKey);
        if (failCount != null) {
            int count = Integer.parseInt(failCount);
            if (count >= maxLoginAttempts) {
                Long ttl = redis.getExpire(failKey, TimeUnit.SECONDS);
                log.warn("锁定账户登录尝试: email={}, 剩余锁定={}秒", email, ttl);
                throw new VsecException("邮箱或口令错误");
            }
        }
    }

    private void recordLoginFailure(String email, HttpServletRequest request) {
        String failKey = "login:fail:" + email;
        Long newCount = redis.opsForValue().increment(failKey);
        if (newCount != null && newCount == 1) {
            redis.expire(failKey, lockMinutes, TimeUnit.MINUTES);
        }
        if (newCount != null && newCount >= maxLoginAttempts) {
            redis.expire(failKey, lockMinutes, TimeUnit.MINUTES);
            log.warn("账户锁定: email={}, 失败次数={}, 锁定{}分钟", maskEmail(email), newCount, lockMinutes);
        }
        auditLogService.record(null, "LOGIN", null, request, "FAIL", "邮箱=" + maskEmail(email));
    }

    private String generateCode(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasUpper = false, hasLower = false, hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasUpper && hasLower && hasDigit;
    }


    private void sendEmail(String to, String code, String purpose) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(mailFrom);
        msg.setTo(to);
        msg.setSubject("register".equals(purpose) ? "VSec 注册验证码" : "VSec 登录验证码");
        msg.setText("您的验证码：" + code + "，有效期 " + codeTtlMinutes + " 分钟。");
        mailSender.send(msg);
    }

    /**
     * 获取当前会话的 enc_key (MK)，自动解密服务端保护层
     */
    public byte[] getEncKey(HttpSession session) {
        if (session == null) return null;
        String b64 = (String) session.getAttribute(SESSION_ENC_KEY);
        if (b64 == null) return null;
        byte[] wrapped = Base64.getDecoder().decode(b64);
        return CryptoUtil.decryptWithServerKey(wrapped, serverSecret);
    }

    // M5: 邮箱脱敏，仅保留首字符和域名
    private static String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 1) return "*" + domain;
        return name.charAt(0) + "***" + domain;
    }

    /**
     * 获取当前会话的用户 UUID
     */
    public static String getUserUuid(HttpSession session) {
        if (session == null) return null;
        return (String) session.getAttribute(SESSION_UUID);
    }
}

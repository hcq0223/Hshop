package com.shopai.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类 - 生成/解析/校验Token
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.security.JwtUtil 迁移至公共库。
 * 在网关统一认证模式下，本类主要被"网关"用于验签，
 * 以及"用户服务(登录签发)"使用；其余业务服务读 X-User-* 头，不再各自验签。
 */
@Component
public class JwtUtil {

    // 注意：加默认值，避免"网关统一认证后不再验签的业务服务"因缺少 jwt 配置而启动失败。
    @Value("${jwt.secret:}")
    private String secret;
    @Value("${jwt.expiration:86400000}")
    private Long expiration;
    @Value("${jwt.token-prefix:Bearer }")
    private String tokenPrefix;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成JWT Token
     */
    public String generateToken(Long userId, String phone, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("phone", phone);
        claims.put("role", role);
        return tokenPrefix + Jwts.builder().claims(claims).subject(phone).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration)).signWith(getSigningKey()).compact();
    }

    /**
     * 从Token中获取用户ID
     */
    public Long getUserId(String token) {
        return parseToken(cleanToken(token)).get("userId", Long.class);
    }

    /**
     * 从Token中获取手机号
     */
    public String getPhone(String token) {
        return parseToken(cleanToken(token)).getSubject();
    }

    /**
     * 从Token中获取角色
     */
    public String getRole(String token) {
        return parseToken(cleanToken(token)).get("role", String.class);
    }

    /**
     * 验证Token有效性
     */
    public boolean validateToken(String token) {
        try {
            parseToken(cleanToken(token));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseToken(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }

    private String cleanToken(String token) {
        return token != null && token.startsWith(tokenPrefix) ? token.substring(tokenPrefix.length()) : token;
    }
}

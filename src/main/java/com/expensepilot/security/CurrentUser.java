package com.expensepilot.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 当前登录人上下文。
 *
 * <p>只读取 Spring Security 已验证过签名、issuer、exp 等声明后的 JwtAuthenticationToken。</p>
 */
@Component
public class CurrentUser {

    public String userId() {
        Authentication authentication = authentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String subject = jwt.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }

        throw new AccessDeniedException(
                "JWT 缺少有效 subject，无法确定当前用户");
    }

    public void requireAnyAuthority(String... authorities) {
        Authentication authentication = authentication();

        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(granted -> Arrays.asList(authorities)
                        .contains(granted.getAuthority()));

        if (!allowed) {
            throw new AccessDeniedException(
                    "当前用户缺少审批权限");
        }
    }

    private Authentication authentication() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("当前请求未认证");
        }

        return authentication;
    }
}

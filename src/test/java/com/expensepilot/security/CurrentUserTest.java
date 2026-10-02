package com.expensepilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsUserIdFromVerifiedJwtSubject() {
        authenticate(
                "employee-1001",
                List.of(new SimpleGrantedAuthority("SCOPE_expense.create"))
        );

        assertEquals(
                "employee-1001",
                currentUser.userId()
        );
    }

    @Test
    void acceptsExpenseApproveScope() {
        authenticate(
                "approver-1",
                List.of(new SimpleGrantedAuthority("SCOPE_expense.approve"))
        );

        assertDoesNotThrow(() ->
                currentUser.requireAnyAuthority(
                        "SCOPE_expense.approve"
                )
        );
    }

    @Test
    void rejectsUserWithoutApprovalScope() {
        authenticate(
                "employee-1001",
                List.of(new SimpleGrantedAuthority("SCOPE_expense.create"))
        );

        assertThrows(
                AccessDeniedException.class,
                () -> currentUser.requireAnyAuthority(
                        "SCOPE_expense.approve"
                )
        );
    }

    private void authenticate(
            String subject,
            List<SimpleGrantedAuthority> authorities) {

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        authorities
                )
        );
    }
}

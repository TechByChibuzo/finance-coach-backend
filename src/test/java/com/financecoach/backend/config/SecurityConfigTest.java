package com.financecoach.backend.config;

import com.financecoach.backend.controller.UserController;
import com.financecoach.backend.dto.UserResponse;
import com.financecoach.backend.repository.UserRepository;
import com.financecoach.backend.security.JwtTokenProvider;
import com.financecoach.backend.security.MetricsAccessManager;
import com.financecoach.backend.service.AccountDeletionService;
import com.financecoach.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real Spring Security filter chain (SecurityConfig + JWT filter) in front of a
 * controller, to check how unauthenticated and revoked requests are answered.
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, MetricsAccessManager.class})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean private UserService userService;
    @MockBean private AccountDeletionService accountDeletionService;

    @Test
    void noToken_is401_soClientsSendTheUserToLogIn() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidOrExpiredToken_is401() throws Exception {
        when(jwtTokenProvider.validateToken("expired")).thenReturn(false);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenRevokedByPasswordReset_is401() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtTokenProvider.validateToken("old")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("old")).thenReturn(userId);
        when(jwtTokenProvider.getTokenVersionFromToken("old")).thenReturn(1);
        when(userRepository.findTokenVersionById(userId)).thenReturn(Optional.of(2));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer old"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validToken_isAllowed() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtTokenProvider.validateToken("good")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("good")).thenReturn(userId);
        when(jwtTokenProvider.getTokenVersionFromToken("good")).thenReturn(0);
        when(userRepository.findTokenVersionById(userId)).thenReturn(Optional.of(0));
        when(userService.getUserById(userId))
                .thenReturn(new UserResponse(userId, "jane@example.com", "Jane", LocalDateTime.now()));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer good"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpoints_needNoToken() throws Exception {
        mockMvc.perform(get("/api/users/health")).andExpect(status().isOk());
    }
}

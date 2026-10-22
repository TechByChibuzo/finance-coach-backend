package com.financecoach.backend.service;

import com.financecoach.backend.dto.UserRegistrationRequest;
import com.financecoach.backend.exception.EmailAlreadyExistsException;
import com.financecoach.backend.model.User;
import com.financecoach.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MetricsService metricsService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
        ReflectionTestUtils.setField(userService, "metricsService", metricsService);
    }

    // --- email normalization ---

    @Test
    void normalizeEmail_trimsAndLowercases() {
        assertThat(UserService.normalizeEmail("  Jane.Doe@Example.COM ")).isEqualTo("jane.doe@example.com");
        assertThat(UserService.normalizeEmail(null)).isNull();
    }

    @Test
    void registerUser_storesTheNormalizedEmail() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        userService.registerUser(registration("Jane@Example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void registerUser_withSameEmailInDifferentCase_isRejected() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(registration("JANE@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void authenticate_matchesEmailRegardlessOfCase() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setPasswordHash("hash");
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        User result = userService.authenticate(" Jane@Example.com", "password123");

        assertThat(result).isSameAs(user);
    }

    @Test
    void registerUser_rejectsMissingEmailOrShortPassword() {
        UserRegistrationRequest noEmail = registration("");
        UserRegistrationRequest shortPassword = registration("jane@example.com");
        shortPassword.setPassword("short");

        assertThatThrownBy(() -> userService.registerUser(noEmail)).hasMessageContaining("Email is required");
        assertThatThrownBy(() -> userService.registerUser(shortPassword)).hasMessageContaining("at least 8");
        verify(userRepository, never()).save(any());
    }

    @Test
    void authenticate_withAWrongPasswordOrUnknownEmail_isRejected_andRecordsLogins() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setPasswordHash("hash");
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(userRepository.lookupByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.authenticate("jane@example.com", "wrong"))
                .isInstanceOf(com.financecoach.backend.exception.InvalidCredentialsException.class);
        assertThatThrownBy(() -> userService.authenticate("nobody@example.com", "right"))
                .isInstanceOf(com.financecoach.backend.exception.InvalidCredentialsException.class);
        userService.authenticate("jane@example.com", "right");

        assertThat(user.getLastLogin()).isNotNull();
        verify(metricsService).recordUserLogin();
    }

    @Test
    void authenticate_updatesOnlyLastLogin_neverSavesTheWholeUser() {
        // Saving the loaded user would write back its password hash and token version,
        // undoing a password reset that committed while the password was being checked
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setPasswordHash("old-hash");
        user.setTokenVersion(3);
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("right", "old-hash")).thenReturn(true);

        userService.authenticate("jane@example.com", "right");

        verify(userRepository).updateLastLogin(org.mockito.ArgumentMatchers.eq(user.getId()), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void getUserById_andByEmail_returnTheUser_orThrowNotFound() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findById(missing)).thenReturn(Optional.empty());
        when(userRepository.lookupByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(userRepository.lookupByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(userService.getUserById(user.getId()).getEmail()).isEqualTo("jane@example.com");
        assertThat(userService.getUserByEmail("JANE@example.com").getId()).isEqualTo(user.getId());
        assertThatThrownBy(() -> userService.getUserById(missing))
                .isInstanceOf(com.financecoach.backend.exception.UserNotFoundException.class);
        assertThatThrownBy(() -> userService.getUserByEmail("nobody@example.com"))
                .isInstanceOf(com.financecoach.backend.exception.UserNotFoundException.class);
    }

    @Test
    void weeklySummaryEmails_areOnUnlessTurnedOff() {
        User user = new User();
        user.setId(UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(userService.getUserById(user.getId()).isWeeklySummaryEmails()).isTrue();

        user.setWeeklySummaryEmails(false);
        assertThat(userService.updateWeeklySummaryEmails(user.getId(), false).isWeeklySummaryEmails()).isFalse();
        verify(userRepository).updateWeeklySummaryEmails(user.getId(), false);
    }

    private UserRegistrationRequest registration(String email) {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail(email);
        request.setPassword("password123");
        request.setFullName("Jane Doe");
        return request;
    }
}

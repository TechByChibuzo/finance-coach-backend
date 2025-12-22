// src/main/java/com/financecoach/backend/controller/AuthController.java
package com.financecoach.backend.controller;

import com.financecoach.backend.dto.*;
import com.financecoach.backend.model.User;
import com.financecoach.backend.security.JwtTokenProvider;
import com.financecoach.backend.service.PasswordResetService;
import com.financecoach.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "User authentication and registration endpoints")
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetService passwordResetService;

    @Autowired
    public AuthController(UserService userService,
                          JwtTokenProvider jwtTokenProvider,
                          PasswordResetService passwordResetService) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordResetService = passwordResetService;
    }

    @Operation(
            summary = "Register new user",
            description = "Create a new user account. Returns JWT token for immediate authentication."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "User registered successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LoginResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                      "type": "Bearer",
                                      "user": {
                                        "id": "123e4567-e89b-12d3-a456-426614174000",
                                        "email": "user@example.com",
                                        "fullName": "John Doe",
                                        "createdAt": "2024-12-21T10:30:00"
                                      }
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Email already exists",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "User registration details",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = UserRegistrationRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "email": "user@example.com",
                                      "password": "SecurePass123!",
                                      "fullName": "John Doe"
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody UserRegistrationRequest request) {

        UserResponse user = userService.registerUser(request);
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        LoginResponse response = new LoginResponse(token, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Login user",
            description = "Authenticate with email and password. Returns JWT token valid for 24 hours."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Login successful",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LoginResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid credentials",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Login credentials",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = LoginRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "email": "user@example.com",
                                      "password": "SecurePass123!"
                                    }
                                    """)
                    )
            )
            @Valid @RequestBody LoginRequest request) {

        User user = userService.authenticate(request.getEmail(), request.getPassword());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());

        UserResponse userResponse = new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt()
        );

        LoginResponse response = new LoginResponse(token, userResponse);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Request password reset",
            description = "Send password reset email to user. Returns success even if email doesn't exist (security)."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Reset email sent (if account exists)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MessageResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Too many requests - rate limited",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        passwordResetService.initiatePasswordReset(request.getEmail());

        return ResponseEntity.ok(new MessageResponse(
                "If an account exists with that email, you will receive password reset instructions."));
    }

    @Operation(
            summary = "Reset password",
            description = "Reset password using token from email"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Password reset successful"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid or expired token"
            )
    })
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());

        return ResponseEntity.ok(new MessageResponse("Password has been reset successfully!"));
    }

    @Operation(
            summary = "Validate reset token",
            description = "Check if password reset token is valid and not expired"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token is valid"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token")
    })
    @GetMapping("/validate-reset-token/{token}")
    public ResponseEntity<MessageResponse> validateResetToken(
            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Password reset token from email",
                    example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            )
            @PathVariable String token) {

        boolean isValid = passwordResetService.validateToken(token);

        if (isValid) {
            return ResponseEntity.ok(new MessageResponse("Token is valid"));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponse("Invalid or expired token"));
    }
}
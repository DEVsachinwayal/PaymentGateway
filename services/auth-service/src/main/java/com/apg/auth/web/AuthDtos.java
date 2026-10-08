package com.apg.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72, message = "password must be 10-72 characters") String password,
            @NotBlank @Size(max = 200) String fullName) {}

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record RegisterResponse(UUID userId, String email) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}
}

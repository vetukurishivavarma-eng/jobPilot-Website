package com.jobpilot.auth;

import com.jobpilot.shared.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(@NotBlank @Email @Size(max = 255) String email,
                                  @NotBlank @Size(min = 8, max = 72) String password, // bcrypt ignores bytes past 72
                                  @NotBlank @Size(max = 120) String fullName) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    public record UserDto(UUID id, String email, String fullName, Instant createdAt) {
        static UserDto of(User u) { return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getCreatedAt()); }
    }
    public record AuthResponse(String token, long expiresInSeconds, UserDto user) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AuthResponse register(@Valid @RequestBody RegisterRequest r) {
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email))
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
        User u = users.save(new User(email, encoder.encode(r.password()), r.fullName().trim()));
        return new AuthResponse(jwt.issue(u.getId()), jwt.ttlSeconds(), UserDto.of(u));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest r) {
        User u = users.findByEmail(r.email().trim().toLowerCase(Locale.ROOT))
                .filter(x -> encoder.matches(r.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Wrong email or password"));
        return new AuthResponse(jwt.issue(u.getId()), jwt.ttlSeconds(), UserDto.of(u));
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal UUID userId) {
        return users.findById(userId).map(UserDto::of).orElseThrow(() -> ApiException.notFound("User"));
    }
}

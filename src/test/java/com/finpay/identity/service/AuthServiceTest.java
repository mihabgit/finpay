package com.finpay.identity.service;

import com.finpay.common.security.JwtService;
import com.finpay.identity.dto.AuthResponse;
import com.finpay.identity.dto.LoginRequest;
import com.finpay.identity.dto.RegisterRequest;
import com.finpay.identity.entity.Role;
import com.finpay.identity.entity.User;
import com.finpay.identity.entity.UserStatus;
import com.finpay.identity.repository.RoleRepository;
import com.finpay.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private Authentication authentication;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
            userRepository,
            roleRepository,
            passwordEncoder,
            authenticationManager,
            jwtService,
            900000,
            refreshTokenService
        );
    }

    @Test
    void register_shouldCreateActiveUserWithUserRole() {

        RegisterRequest request =
            new RegisterRequest(
                "test@example.com",
                "password123",
                "Test",
                "User"
            );

        Role userRole = new Role("USER");

        when(userRepository.existsByEmailIgnoreCase(
            "test@example.com"
        )).thenReturn(false);

        when(roleRepository.findByName("USER"))
            .thenReturn(Optional.of(userRole));

        when(passwordEncoder.encode("password123"))
            .thenReturn("hashed-password");

        authService.register(request);

        verify(passwordEncoder)
            .encode("password123");

        verify(userRepository)
            .save(any(User.class));
    }

    @Test
    void register_shouldRejectDuplicateEmail() {

        RegisterRequest request =
            new RegisterRequest(
                "test@example.com",
                "password123",
                "Test",
                "User"
            );

        when(userRepository.existsByEmailIgnoreCase(
            "test@example.com"
        )).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> authService.register(request)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Email is already registered");

        verify(userRepository, never())
            .save(any(User.class));
    }

    @Test
    void login_shouldGenerateAccessAndRefreshTokens() {

        LoginRequest request =
            new LoginRequest(
                "test@example.com",
                "password123"
            );

        User user = new User(
            "test@example.com",
            "hashed-password",
            "Test",
            "User",
            UserStatus.ACTIVE
        );

        UserDetails userDetails =
            org.springframework.security.core.userdetails.User
                .withUsername("test@example.com")
                .password("hashed-password")
                .authorities("ROLE_USER")
                .build();

        when(userRepository.findByEmailIgnoreCase(
            "test@example.com"
        )).thenReturn(Optional.of(user));

        when(authenticationManager.authenticate(any(
            UsernamePasswordAuthenticationToken.class
        ))).thenReturn(authentication);

        when(authentication.getPrincipal())
            .thenReturn(userDetails);

        when(jwtService.generateAccessToken(userDetails))
            .thenReturn("access-token");

        when(refreshTokenService.createRefreshToken(user))
            .thenReturn("refresh-token");

        AuthResponse response =
            authService.login(request);

        assertThat(response.accessToken())
            .isEqualTo("access-token");

        assertThat(response.refreshToken())
            .isEqualTo("refresh-token");

        assertThat(response.tokenType())
            .isEqualTo("Bearer");

        assertThat(response.expiresIn())
            .isEqualTo(900);

        verify(jwtService)
            .generateAccessToken(userDetails);

        verify(refreshTokenService)
            .createRefreshToken(user);
    }
}
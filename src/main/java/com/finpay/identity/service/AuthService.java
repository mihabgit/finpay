package com.finpay.identity.service;

import com.finpay.common.security.JwtService;
import com.finpay.identity.dto.AuthResponse;
import com.finpay.identity.dto.LoginRequest;
import com.finpay.identity.dto.RegisterRequest;
import com.finpay.identity.entity.RefreshToken;
import com.finpay.identity.entity.Role;
import com.finpay.identity.entity.User;
import com.finpay.identity.entity.UserStatus;
import com.finpay.identity.repository.RoleRepository;
import com.finpay.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final long accessTokenExpirationSeconds;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
        UserRepository userRepository,
        RoleRepository roleRepository,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager,
        JwtService jwtService,
        @Value("${security.jwt.access-token-expiration}")
        long accessTokenExpiration,
        RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.accessTokenExpirationSeconds = accessTokenExpiration / 1000;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public void register(RegisterRequest request) {

        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email is already registered");
        }

        Role userRole = roleRepository.findByName("USER")
            .orElseThrow(() -> new IllegalStateException("Default USER role is missing"));

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
            email,
            passwordHash,
            request.firstName().trim(),
            request.lastName().trim(),
            UserStatus.ACTIVE
        );

        user.addRole(userRole);

        userRepository.save(user);

        log.info("User registered successfully: userId={}, email={}", user.getId(), user.getEmail());

    }

    @Transactional
    public AuthResponse login(LoginRequest request) {

        String email = request.email().trim().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));

        Authentication authentication =
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password())
            );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(user);

        log.info("User login successful: userId={}, email={}", user.getId(), user.getEmail());

        return new AuthResponse(accessToken, refreshToken, "Bearer", accessTokenExpirationSeconds);

    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {

        RefreshToken oldToken = refreshTokenService.validate(rawRefreshToken);

        User user = oldToken.getUser();

        UserDetails userDetails =
                org.springframework.security.core.userdetails.User
                        .withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities(
                                user.getRoles()
                                        .stream()
                                        .flatMap(role ->
                                                java.util.stream.Stream.concat(
                                                        java.util.stream.Stream.of(
                                                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                                        "ROLE_" + role.getName()
                                                                )
                                                        ),
                                                        role.getPermissions()
                                                                .stream()
                                                                .map(permission ->
                                                                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                                                permission.getName()
                                                                        )
                                                                )
                                                )
                                        )
                                        .toList()
                        )
                        .build();

        String accessToken = jwtService.generateAccessToken(userDetails);

        /*
         * Refresh-token rotation:
         *
         * Old token becomes unusable.
         * A completely new refresh token is issued.
         */
        refreshTokenService.revoke(oldToken);

        String newRefreshToken = refreshTokenService.createRefreshToken(user);

        log.info("Access token refreshed: userId={}", user.getId());

        return new AuthResponse(
                accessToken,
                newRefreshToken,
                "Bearer",
                accessTokenExpirationSeconds
        );
    }

    @Transactional
    public void logout(String rawRefreshToken) {

        RefreshToken refreshToken = refreshTokenService.validate(rawRefreshToken);

        refreshTokenService.revoke(refreshToken);

        log.info("User logged out: userId={}", refreshToken.getUser().getId());

    }

}
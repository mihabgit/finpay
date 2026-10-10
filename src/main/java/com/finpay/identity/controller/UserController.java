package com.finpay.identity.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('USER_READ')")
    public String me(Authentication authentication) {

        return "Authenticated as: " + authentication.getName();
    }

    @GetMapping("/admin-test")
    @PreAuthorize("hasAuthority('ADMIN_WRITE')")
    public String adminTest() {
        return "Admin access granted";
    }

}
package com.finpay.identity.service;

import com.finpay.identity.entity.Role;
import com.finpay.identity.entity.User;
import com.finpay.identity.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class FinPayUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public FinPayUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() ->
                new UsernameNotFoundException(
                    "User not found"
                )
            );

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();

        for (Role role : user.getRoles()) {

            // Role authority
            authorities.add(
                new SimpleGrantedAuthority(
                    "ROLE_" + role.getName()
                )
            );

            // Permission authorities
            role.getPermissions().forEach(permission ->
                authorities.add(
                    new SimpleGrantedAuthority(
                        permission.getName()
                    )
                )
            );
        }

        return org.springframework.security.core.userdetails.User
            .withUsername(user.getEmail())
            .password(user.getPasswordHash())
            .authorities(authorities)
            .accountLocked(user.getStatus().name().equals("LOCKED"))
            .disabled(user.getStatus().name().equals("SUSPENDED"))
            .build();
    }
}
package com.collabcase.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.collabcase.auth.dto.AuthResponse;
import com.collabcase.auth.dto.LoginRequest;
import com.collabcase.auth.dto.RegisterRequest;
import com.collabcase.auth.dto.UserResponse;
import com.collabcase.user.User;
import com.collabcase.user.UserService;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        User user = userService.create(request.name(), request.email(), request.password());
        return createResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userService.findByEmail(request.email())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPassword()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return createResponse(user);
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }

    private AuthResponse createResponse(User user) {
        return new AuthResponse(jwtService.generateToken(user), toResponse(user));
    }
}

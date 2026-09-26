package com.app.userservice.auth;

import com.app.userservice.user.User;
import com.app.userservice.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(Long userId, String username, String token) {
    }

    public record UserInfo(Long userId, String username) {
    }

    /** Client gọi để đăng nhập, nhận về token. */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(u -> u.getPassword().equals(request.password()))
                .orElseThrow(() -> {
                    log.warn("Login failed for username={}", request.username());
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
                });

        user.setToken(UUID.randomUUID().toString());
        userRepository.save(user);
        log.info("Login success v2 userId={} username={}", user.getId(), user.getUsername());
        return new LoginResponse(user.getId(), user.getUsername(), user.getToken());
    }

    /** Service khác (OrderService) gọi để kiểm tra token hợp lệ và lấy ra user. */
    @GetMapping("/validate")
    public UserInfo validate(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length())
                : null;
        User user = (token == null ? null : userRepository.findByToken(token).orElse(null));
        if (user == null) {
            log.warn("Token validation failed");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
        log.info("Token valid userId={}", user.getId());
        return new UserInfo(user.getId(), user.getUsername());
    }
}

package com.app.userservice.auth;

import com.app.userservice.user.User;
import com.app.userservice.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UserRepository userRepository;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        controller = new AuthController(userRepository);
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User("alice", "123456")));

        AuthController.LoginResponse response = controller.login(new AuthController.LoginRequest("alice", "123456"));

        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User("alice", "123456")));

        assertThatThrownBy(() -> controller.login(new AuthController.LoginRequest("alice", "wrong")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validateWithUnknownTokenIsUnauthorized() {
        when(userRepository.findByToken("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.validate("Bearer bad"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validateWithoutBearerPrefixIsUnauthorized() {
        assertThatThrownBy(() -> controller.validate("some-token"))
                .isInstanceOf(ResponseStatusException.class);
    }
}

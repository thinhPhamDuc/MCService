package com.app.userservice.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Tạo sẵn user mẫu khi service khởi động (chỉ tạo nếu chưa có).
 */
@Component
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;

    public UserSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) {
        for (String username : new String[]{"alice", "bob"}) {
            if (userRepository.findByUsername(username).isEmpty()) {
                userRepository.save(new User(username, "123456"));
                log.info("Seeded user {}", username);
            }
        }
    }
}

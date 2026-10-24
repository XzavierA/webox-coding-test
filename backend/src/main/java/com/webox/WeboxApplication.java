package com.webox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WeboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(WeboxApplication.class, args);
    }
}

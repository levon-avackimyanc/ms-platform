package com.logistics.planner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LogisticsPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogisticsPlannerApplication.class, args);
    }
}

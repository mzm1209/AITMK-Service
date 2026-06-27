package com.example.aitmk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
@EnableAsync
public class AitmkApplication {

    public static void main(String[] args) {
        SpringApplication.run(AitmkApplication.class, args);
    }

}

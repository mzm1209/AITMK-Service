package com.example.aitmk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class AitmkApplication {

    public static void main(String[] args) {
        SpringApplication.run(AitmkApplication.class, args);
    }

}

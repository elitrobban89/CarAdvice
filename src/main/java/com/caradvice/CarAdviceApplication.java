package com.caradvice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CarAdviceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CarAdviceApplication.class, args);
    }
}

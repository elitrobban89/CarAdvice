package com.caradvice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @author Robert Andersson Kopler */
@SpringBootApplication
@EnableScheduling
public class CarAdviceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CarAdviceApplication.class, args);
    }
}

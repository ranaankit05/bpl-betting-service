package com.bally.bpl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BplApplication {

    public static void main(String[] args) {
        SpringApplication.run(BplApplication.class, args);
    }
}

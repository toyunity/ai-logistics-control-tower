package com.example.controltower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ControlTowerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlTowerApplication.class, args);
    }
}

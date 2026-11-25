package com.memecreator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MemeCreatorApplication {

    public static void main(String[] args) {
        // Java2D rendering must not need a display
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(MemeCreatorApplication.class, args);
    }
}

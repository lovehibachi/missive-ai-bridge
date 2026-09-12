package com.lovehibachi.aichatbot;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(BridgeProperties.class)
public class FinMissiveBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinMissiveBridgeApplication.class, args);
    }
}

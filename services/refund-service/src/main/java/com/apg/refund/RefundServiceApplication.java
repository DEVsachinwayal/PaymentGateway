package com.apg.refund;

import com.apg.refund.service.RefundProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RefundProperties.class)
public class RefundServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RefundServiceApplication.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}

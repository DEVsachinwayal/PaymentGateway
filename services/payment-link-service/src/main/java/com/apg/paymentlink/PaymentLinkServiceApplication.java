package com.apg.paymentlink;

import com.apg.paymentlink.service.LinkProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(LinkProperties.class)
public class PaymentLinkServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentLinkServiceApplication.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}

package com.agrosphere.operations.config;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RuntimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}

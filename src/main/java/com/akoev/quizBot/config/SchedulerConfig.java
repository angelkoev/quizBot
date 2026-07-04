package com.akoev.quizBot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class SchedulerConfig {

    @Bean
    public ScheduledExecutorService scheduler() {
        return Executors.newScheduledThreadPool(2);
    }
}
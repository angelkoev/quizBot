package com.akoev.quizBot.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exists only so Render (a "Web Service" must respond over HTTP) considers the app
 * healthy, and so an external uptime pinger has something to hit to stop the free-tier
 * instance from spinning down after 15 minutes of no inbound HTTP traffic. The Telegram
 * bot itself runs on its own long-polling thread and doesn't use this endpoint.
 */
@RestController
public class HealthController {

    @GetMapping("/")
    public String health() {
        return "OK";
    }
}

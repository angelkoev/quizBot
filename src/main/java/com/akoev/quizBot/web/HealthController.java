package com.akoev.quizBot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /**
     * Hit by Render's own platform health check every few seconds - not logged,
     * since that traffic is constant and not useful signal.
     */
    @GetMapping("/")
    public String health() {
        return "OK";
    }

    /**
     * Hit only by our GitHub Actions keep-alive cron (see .github/workflows/keep-alive.yml),
     * so this is the one endpoint worth logging to confirm the cron is actually reaching us.
     */
    @GetMapping("/keepalive")
    public String keepAlive() {
        log.info("Keep-alive ping received");
        return "OK";
    }
}

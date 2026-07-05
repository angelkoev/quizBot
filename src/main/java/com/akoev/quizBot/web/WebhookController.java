package com.akoev.quizBot.web;

import com.akoev.quizBot.bot.QuizBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives updates pushed by Telegram when the bot runs in webhook mode (see
 * QuizBot#init - only active when telegram.webhook.url is set). Any inbound call here
 * counts as HTTP traffic, so it also wakes a spun-down Render free-tier instance on the
 * next message a user sends, without needing the long-polling thread to already be alive.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final QuizBot quizBot;

    @Value("${telegram.webhook.secret:}")
    private String webhookSecret;

    public WebhookController(QuizBot quizBot) {
        this.quizBot = quizBot;
    }

    @PostMapping("/telegram/webhook")
    public ResponseEntity<Void> onUpdate(
            @RequestBody String body,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretHeader) {

        if (!webhookSecret.isBlank() && !webhookSecret.equals(secretHeader)) {
            log.warn("Rejected webhook call with missing/invalid secret token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Update update = BotUtils.parseUpdate(body);
        quizBot.onUpdate(update);

        return ResponseEntity.ok().build();
    }
}

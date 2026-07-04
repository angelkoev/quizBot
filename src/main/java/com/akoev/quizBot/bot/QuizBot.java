package com.akoev.quizBot.bot;

import com.akoev.quizBot.model.GameState;
import com.akoev.quizBot.model.Player;
import com.akoev.quizBot.model.Question;
import com.akoev.quizBot.service.GameService;
import com.pengrad.telegrambot.*;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.SendMessage;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QuizBot {

    private final TelegramBot bot;
    private final GameService gameService;
    private long chatIdanswer;

    public QuizBot(GameService gameService,
                   @Value("${telegram.bot.token}") String token) {

        this.gameService = gameService;
        this.bot = new TelegramBot(token);
    }

    @PostConstruct
    public void init() {

        bot.setUpdatesListener(updates -> {

            for (Update update : updates) {

                // =========================
                // CALLBACK (INLINE BUTTONS)
                // =========================
                if (update.callbackQuery() != null) {

                    long chatId = update.callbackQuery().message().chat().id();
                    long userId = update.callbackQuery().from().id();
                    String answer = update.callbackQuery().data();
                    String room = String.valueOf(chatId);

                    // 🔥 ВАЖНО – ACK
                    bot.execute(new AnswerCallbackQuery(update.callbackQuery().id()));

                    handleAnswer(room, userId, answer);
                    continue;
                }

                // =========================
                // MESSAGE
                // =========================
                if (update.message() == null || update.message().text() == null)
                    continue;

                long chatId = update.message().chat().id();
                long userId = update.message().from().id();
                String text = normalizeCommand(update.message().text());
                String room = String.valueOf(chatId);

                if (text.startsWith("/")) {
                    handleCommand(text, chatId, userId, room, update);

                }
            }

            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    // =========================
    // COMMANDS
    // =========================
    private void handleCommand(String text, long chatId, long userId, String room, Update update) {

        GameState g = gameService.get(room);

        if (text.equals("/start")) {
            bot.execute(new SendMessage(chatId,
                    "🎮 Quiz Bot Ready!\n\n" +
                            "/categories\n/newQuiz\n/join\n/ready\n/list\n/help"));
            return;
        }

        if (text.equals("/help")) {
            bot.execute(new SendMessage(chatId,
                    """
                    🎮 Quiz Bot Commands:
    
                    /categories - show categories
                    /newQuiz <category|all> <count>
                    /join - join game
                    /ready - start game
                    /list - players
                    """));
            return;
        }

        if (text.equals("/categories")) {

            StringBuilder sb = new StringBuilder("📂 Categories:\n\n");
            gameService.getCategories().forEach(c -> sb.append("• ").append(c).append("\n"));

            bot.execute(new SendMessage(chatId, sb.toString()));
            return;
        }

        if (text.startsWith("/newQuiz")) {

            String[] p = text.split(" ");
            if (p.length < 3) {
                bot.execute(new SendMessage(chatId, "Usage: /newQuiz <category|all> <count>"));
                return;
            }

            String category = p[1];
            int count = Integer.parseInt(p[2]);

            gameService.createGame(room, category, count);

            GameState state = gameService.get(room);
            state.setPhase(GameState.GamePhase.WAITING);

            bot.execute(new SendMessage(chatId,
                    "🎮 Game created!\n" +
                            "Category: " + category + "\n" +
                            "Questions: " + count + "\n\n" +
                            "👉 /join\n👉 /ready\n👉 /list"));

            return;
        }

        if (text.equals("/join")) {

            if (g == null) {
                bot.execute(new SendMessage(chatId, "❌ No game. Use /newQuiz"));
                return;
            }

            String name = update.message().from().firstName(); // или username

            boolean joined = gameService.join(room, userId, name);

            if (joined) {
                bot.execute(new SendMessage(chatId, "👤 " + name + " joined"));
            } else {
                bot.execute(new SendMessage(chatId, "⚠️ Already joined"));
            }

            return;
        }

        if (text.equals("/list")) {

            if (g == null || g.players.isEmpty()) {
                bot.execute(new SendMessage(chatId, "No players"));
                return;
            }

            StringBuilder sb = new StringBuilder("👥 Players:\n\n");
            g.players.values().forEach(p ->
                    sb.append("• ").append(p.getName()).append("\n")
            );

            bot.execute(new SendMessage(chatId, sb.toString()));
            return;
        }

        if (text.equals("/ready")) {

            if (g == null || g.players.isEmpty()) {
                bot.execute(new SendMessage(chatId, "❌ No players"));
                return;
            }

            g.setPhase(GameState.GamePhase.IN_GAME);

            startRound(chatId, room);
        }
    }

    // =========================
    // ANSWERS (INLINE / TEXT)
    // =========================
    private void handleAnswer(String room, long userId, String answer) {

        long chatId = Long.parseLong(room);

        GameState g = gameService.get(room);
        if (g == null || !g.roundActive) return;

        gameService.answer(room, userId, answer);

        if (!gameService.allAnswered(room)) {
            return;
        }

        // only FIRST time we reach this
        g.roundActive = false;

        gameService.cancelTimer(room);

        sendResults(chatId, room);

        gameService.next(room);

        if (gameService.isGameOver(room)) {
            sendFinal(chatId, room);
        } else {
            startRound(chatId, room);
        }
    }
    // =========================
    // GAME FLOW
    // =========================
    private void startRound(long chatId, String room) {

        GameState g = gameService.get(room);
        if (g == null) return;

        // 🔥 reset round state
        g.roundActive = true;
        g.answeredThisRound.clear();
        g.roundCorrect.clear();
        g.setFinishedRound(false);


        sendQuestion(chatId, room);

        gameService.startTimer(room, () -> {

            GameState state = gameService.get(room);
            if (state == null || !state.roundActive) return;

            state.roundActive = false;

            sendResults(chatId, room);

            gameService.next(room);

            if (!gameService.isGameOver(room)) {
                startRound(chatId, room);
            } else {
                sendFinal(chatId, room);
            }
        });
    }

    // =========================
    // QUESTION
    // =========================
    private void sendQuestion(long chatId, String room) {

        GameState g = gameService.get(room);

        if (g == null) return;
        g.roundActive = true;

        Question q = gameService.current(room);

        int current = g.getIndex() + 1;
        int total = g.getQuestions().size();

        StringBuilder sb = new StringBuilder();

        sb.append("[Question ")
                .append(current)
                .append(" / ")
                .append(total)
                .append("] ")
                .append("❓ ")
                .append(q.getQuestion());

        // INLINE BUTTONS (A, B, C, D)
        List<InlineKeyboardButton> buttons = q.getOptions().entrySet()
                .stream()
                .map(e ->
                        new InlineKeyboardButton(e.getKey() + ") " + e.getValue())
                                .callbackData(e.getKey())
                )
                .toList();

        InlineKeyboardButton[][] keyboardArray = new InlineKeyboardButton[buttons.size()][1];

        for (int i = 0; i < buttons.size(); i++) {
            keyboardArray[i][0] = buttons.get(i);
        }

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(keyboardArray);

        bot.execute(new SendMessage(chatId, sb.toString())
                .replyMarkup(keyboard));
    }

    // =========================
    // RESULTS
    // =========================
    private void sendResults(long chatId, String room) {

        GameState g = gameService.get(room);

        StringBuilder sb = new StringBuilder("📊 Round results:\n\n");

        for (Long userId : g.players.keySet()) {

            String name = g.players.get(userId).getName();

            boolean answered = g.answeredThisRound.contains(userId);
            boolean correct = g.roundCorrect.getOrDefault(userId, false);

            if (correct) {
                sb.append("🟢 ").append(name).append(" - Correct\n");
            } else if (answered) {
                sb.append("🔴 ").append(name).append(" - Wrong\n");
            } else {
                sb.append("⚪ ").append(name).append(" - No answer\n");
            }
        }

        bot.execute(new SendMessage(chatId, sb.toString()));
    }

    private void sendFinal(long chatId, String room) {

        GameState g = gameService.get(room);

        StringBuilder sb = new StringBuilder("🏆 FINAL RESULTS:\n\n");

        g.scores.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {

                    Player p = g.players.get(e.getKey());

                    String name = (p != null)
                            ? p.getName()
                            : String.valueOf(e.getKey());

                    sb.append(name)
                            .append(": ")
                            .append(e.getValue())
                            .append("\n");
                });

        bot.execute(new SendMessage(chatId, sb.toString()));
    }

    private String normalizeCommand(String text) {
        if (text == null) return null;
        return text.split("@")[0].trim();
    }

//    private String getUserName(com.pengrad.telegrambot.model.User user) {
//
//        if (user.username() != null && !user.username().isBlank()) {
//            return "@" + user.username();
//        }
//
//        String name = user.firstName();
//
//        if (user.lastName() != null) {
//            name += " " + user.lastName();
//        }
//
//        return name;
//    }
}
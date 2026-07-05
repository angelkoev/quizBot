package com.akoev.quizBot.bot;

import com.akoev.quizBot.model.GameState;
import com.akoev.quizBot.model.Player;
import com.akoev.quizBot.model.Question;
import com.akoev.quizBot.service.GameService;
import com.akoev.quizBot.service.GameService.AnswerResult;
import com.pengrad.telegrambot.*;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.SendMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QuizBot {

    private static final Logger log = LoggerFactory.getLogger(QuizBot.class);

    private final TelegramBot bot;
    private final GameService gameService;

    public QuizBot(GameService gameService, TelegramBot bot) {
        this.gameService = gameService;
        this.bot = bot;
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
                    String data = update.callbackQuery().data();
                    String callbackId = update.callbackQuery().id();
                    String room = String.valueOf(chatId);

                    handleCallback(room, chatId, userId, data, callbackId);
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
                    handleCommand(text, chatId, userId, room, update.message().from().firstName());
                }
            }

            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    // =========================
    // COMMANDS
    // =========================
    /**
     * Package-private (rather than private) so tests can drive command handling directly
     * with plain values, without needing to construct a real pengrad {@link Update}.
     */
    void handleCommand(String text, long chatId, long userId, String room, String firstName) {

        GameState g = gameService.get(room);

        if (text.equals("/start")) {
            bot.execute(new SendMessage(chatId,
                    "🎮 Quiz Bot Ready!\n\n" +
                            "/categories\n/newGame\n/join\n/ready\n/startGame\n/pauseGame\n/resumeGame\n/endGame\n/list\n/help"));
            return;
        }

        if (text.equals("/help")) {
            bot.execute(new SendMessage(chatId,
                    """
                    🎮 Quiz Bot Commands:

                    /categories - show categories
                    /newGame <category|all> <count> [timeout] - create a game (timeout in seconds per question, default %d)
                    /join - join game
                    /ready - mark yourself as ready
                    /startGame - start once everyone is ready
                    /pauseGame - pause after the current question
                    /resumeGame - resume a paused game
                    /endGame - end the game early
                    /list - players
                    """.formatted(GameService.DEFAULT_TIMEOUT_SECONDS)));
            return;
        }

        if (text.equals("/categories")) {

            StringBuilder sb = new StringBuilder("📂 Categories:\n\n");
            gameService.getCategories().forEach(c -> sb.append("• ").append(c).append("\n"));

            bot.execute(new SendMessage(chatId, sb.toString()));
            return;
        }

        if (text.startsWith("/newGame")) {

            if (g != null && g.getPhase() == GameState.GamePhase.IN_GAME) {
                bot.execute(new SendMessage(chatId,
                        "⚠️ A game is already in progress in this chat. Wait for it to finish."));
                return;
            }

            String usage = "Usage: /newGame <category|all> <count> [timeout]";

            String[] p = text.split(" ");
            if (p.length < 3) {
                bot.execute(new SendMessage(chatId, usage));
                return;
            }

            String category = p[1];

            if (!gameService.isValidCategory(category)) {
                bot.execute(new SendMessage(chatId, "❌ Unknown category. Use /categories"));
                return;
            }

            int count;
            try {
                count = Integer.parseInt(p[2]);
            } catch (NumberFormatException e) {
                bot.execute(new SendMessage(chatId, usage));
                return;
            }

            if (count <= 0) {
                bot.execute(new SendMessage(chatId, "❌ Count must be positive"));
                return;
            }

            int timeoutSeconds = GameService.DEFAULT_TIMEOUT_SECONDS;
            if (p.length >= 4) {
                try {
                    timeoutSeconds = Integer.parseInt(p[3]);
                } catch (NumberFormatException e) {
                    bot.execute(new SendMessage(chatId, usage));
                    return;
                }

                if (timeoutSeconds <= 0) {
                    bot.execute(new SendMessage(chatId, "❌ Timeout must be positive"));
                    return;
                }
            }

            gameService.createGame(room, category, count, timeoutSeconds);

            log.info("Game created in room {} category={} count={} timeout={}s", room, category, count, timeoutSeconds);

            bot.execute(new SendMessage(chatId,
                    "🎮 Game created!\n" +
                            "Category: " + category + "\n" +
                            "Questions: " + count + "\n" +
                            "Timeout: " + timeoutSeconds + "s\n\n" +
                            "👉 /join\n👉 /ready\n👉 /startGame\n👉 /list"));

            return;
        }

        if (text.equals("/join")) {

            if (g == null) {
                bot.execute(new SendMessage(chatId, "❌ No game. Use /newGame"));
                return;
            }

            if (rejectIfNotWaiting(chatId, g, "⚠️ Game already started. Wait for the next one.")) {
                return;
            }

            boolean joined = gameService.join(room, userId, firstName);

            if (joined) {
                log.info("Player {} ({}) joined room {}", firstName, userId, room);
                bot.execute(new SendMessage(chatId, "👤 " + firstName + " joined"));
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
            g.players.values().forEach(p -> {
                boolean ready = g.readyPlayers.contains(p.getId());
                sb.append(ready ? "✅ " : "❌ ").append(p.getName()).append("\n");
            });

            bot.execute(new SendMessage(chatId, sb.toString()));
            return;
        }

        if (text.equals("/ready")) {

            if (g == null) {
                bot.execute(new SendMessage(chatId, "❌ No game. Use /newGame first"));
                return;
            }

            if (rejectIfNotWaiting(chatId, g, "⚠️ Game already started")) {
                return;
            }

            if (!g.players.containsKey(userId)) {
                bot.execute(new SendMessage(chatId, "❌ Join first with /join"));
                return;
            }

            boolean nowReady = g.readyPlayers.add(userId);
            String name = g.players.get(userId).getName();

            bot.execute(new SendMessage(chatId,
                    (nowReady ? "✅ " + name + " is ready" : "⚠️ " + name + " is already ready")
                            + " (" + g.readyPlayers.size() + "/" + g.players.size() + ")\n"
                            + "👉 /startGame when everyone is ready"));
            return;
        }

        if (text.equals("/startGame")) {

            if (g == null) {
                bot.execute(new SendMessage(chatId, "❌ No game. Use /newGame first"));
                return;
            }

            if (rejectIfNotWaiting(chatId, g, "⚠️ Game already started")) {
                return;
            }

            if (g.players.isEmpty()) {
                bot.execute(new SendMessage(chatId, "❌ No players. Use /join first"));
                return;
            }

            if (!g.allPlayersReady()) {
                StringBuilder sb = new StringBuilder("⏳ Waiting for players to /ready:\n\n");
                g.players.values().stream()
                        .filter(p -> !g.readyPlayers.contains(p.getId()))
                        .forEach(p -> sb.append("• ").append(p.getName()).append("\n"));
                bot.execute(new SendMessage(chatId, sb.toString()));
                return;
            }

            g.setPhase(GameState.GamePhase.IN_GAME);

            log.info("Game started in room {} with {} players", room, g.players.size());

            startRound(chatId, room);
            return;
        }

        if (text.equals("/pauseGame")) {

            if (g == null || g.getPhase() != GameState.GamePhase.IN_GAME) {
                bot.execute(new SendMessage(chatId, "❌ No game in progress to pause."));
                return;
            }

            if (!g.players.containsKey(userId)) {
                bot.execute(new SendMessage(chatId, "❌ Only joined players can pause the game."));
                return;
            }

            if (g.isPaused()) {
                bot.execute(new SendMessage(chatId, "⚠️ Game is already paused."));
                return;
            }

            g.setPaused(true);
            log.info("Game paused in room {}", room);
            bot.execute(new SendMessage(chatId,
                    "⏸ Game will pause after this question. Use /resumeGame to continue."));
            return;
        }

        if (text.equals("/resumeGame")) {

            if (g == null || g.getPhase() != GameState.GamePhase.IN_GAME) {
                bot.execute(new SendMessage(chatId, "❌ No game in progress to resume."));
                return;
            }

            if (!g.players.containsKey(userId)) {
                bot.execute(new SendMessage(chatId, "❌ Only joined players can resume the game."));
                return;
            }

            if (!g.isPaused()) {
                bot.execute(new SendMessage(chatId, "⚠️ Game is not paused."));
                return;
            }

            g.setPaused(false);
            log.info("Game resumed in room {}", room);

            if (g.isAwaitingResume()) {
                g.setAwaitingResume(false);
                bot.execute(new SendMessage(chatId, "▶️ Resuming game..."));
                startRound(chatId, room);
            } else {
                bot.execute(new SendMessage(chatId, "▶️ Game will no longer pause after this question."));
            }
            return;
        }

        if (text.equals("/endGame")) {

            if (g == null || g.getPhase() == GameState.GamePhase.FINISHED) {
                bot.execute(new SendMessage(chatId, "❌ No active game to end."));
                return;
            }

            if (!g.players.containsKey(userId)) {
                bot.execute(new SendMessage(chatId, "❌ Only joined players can end the game."));
                return;
            }

            boolean wasInGame = g.getPhase() == GameState.GamePhase.IN_GAME;

            gameService.cancelTimer(room);
            g.tryCloseRound();
            g.setPhase(GameState.GamePhase.FINISHED);

            log.info("Game ended in room {} (wasInGame={})", room, wasInGame);

            if (wasInGame) {
                bot.execute(new SendMessage(chatId, "🛑 Game ended early."));
                sendFinal(chatId, room);
            } else {
                bot.execute(new SendMessage(chatId, "🛑 Game cancelled before it started."));
            }
        }
    }

    /**
     * Blocks a WAITING-only command when the game has moved past that phase, sending a
     * message that reflects the actual phase (in progress vs. already finished) rather
     * than a generic "already started" that misleads once the game is FINISHED.
     *
     * @return true if the command was rejected (caller should return immediately)
     */
    private boolean rejectIfNotWaiting(long chatId, GameState g, String inGameMessage) {

        if (g.getPhase() == GameState.GamePhase.IN_GAME) {
            bot.execute(new SendMessage(chatId, inGameMessage));
            return true;
        }

        if (g.getPhase() == GameState.GamePhase.FINISHED) {
            bot.execute(new SendMessage(chatId, "🏁 That game finished. Start a new one with /newGame"));
            return true;
        }

        return false;
    }

    // =========================
    // CALLBACK QUERIES (INLINE BUTTONS)
    // =========================
    /**
     * Package-private for the same reason as {@link #handleCommand}: lets tests drive
     * callback handling with plain values instead of a real pengrad callback query.
     */
    void handleCallback(String room, long chatId, long userId, String data, String callbackId) {

        if (data.equals("REPLAY")) {
            handleReplay(room, chatId, callbackId);
        } else {
            handleAnswer(room, chatId, userId, data, callbackId);
        }
    }

    // =========================
    // ANSWERS (INLINE BUTTONS ONLY)
    // =========================
    private void handleAnswer(String room, long chatId, long userId, String data, String callbackId) {

        int roundId;
        String answer;
        try {
            String[] parts = data.split(":");
            answer = parts[0];
            roundId = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            bot.execute(new AnswerCallbackQuery(callbackId));
            return;
        }

        AnswerResult result = gameService.submitAnswer(room, userId, roundId, answer);

        switch (result) {
            case NO_GAME, STALE_ROUND -> bot.execute(new AnswerCallbackQuery(callbackId)
                    .text("⏱ That round is over.").showAlert(false));
            case ALREADY_ANSWERED -> bot.execute(new AnswerCallbackQuery(callbackId)
                    .text("✅ You already answered this round.").showAlert(false));
            case RECORDED -> bot.execute(new AnswerCallbackQuery(callbackId)
                    .text("Answer received!"));
            case ROUND_COMPLETE -> {
                bot.execute(new AnswerCallbackQuery(callbackId).text("Answer received!"));
                finishRound(chatId, room);
            }
        }
    }

    // =========================
    // GAME FLOW
    // =========================
    private void startRound(long chatId, String room) {

        GameState g = gameService.get(room);
        if (g == null) return;

        int roundId = gameService.openRound(room);
        if (roundId < 0) return;

        sendQuestion(chatId, room, g);

        gameService.startTimer(room, roundId, () -> finishRound(chatId, room));
    }

    /**
     * Called exactly once per round - either from the last player's answer or from
     * timer expiry, whichever wins the CAS in GameState#tryCloseRound. Never both.
     */
    private void finishRound(long chatId, String room) {

        gameService.cancelTimer(room);

        sendResults(chatId, room);

        gameService.advance(room);

        GameState g = gameService.get(room);

        if (gameService.isGameOver(room)) {
            if (g != null) g.setPhase(GameState.GamePhase.FINISHED);
            log.info("Game finished in room {}", room);
            sendFinal(chatId, room);
            return;
        }

        if (g != null && g.isPaused()) {
            g.setAwaitingResume(true);
            bot.execute(new SendMessage(chatId,
                    "⏸ Game paused. Use /resumeGame to continue with the next question."));
            return;
        }

        startRound(chatId, room);
    }

    // =========================
    // QUESTION
    // =========================
    private void sendQuestion(long chatId, String room, GameState g) {

        Question q = gameService.current(room);

        int current = g.getIndex() + 1;
        int total = g.getQuestions().size();
        int roundId = g.getRoundId();

        StringBuilder sb = new StringBuilder();

        sb.append("[Question ")
                .append(current)
                .append(" / ")
                .append(total)
                .append("] ")
                .append("❓ ")
                .append(q.getQuestion());

        // INLINE BUTTONS (A, B, C, D) - callback data is tagged with the round id so
        // a press on this exact keyboard can never be mistaken for a later round.
        List<InlineKeyboardButton> buttons = q.getOptions().entrySet()
                .stream()
                .map(e ->
                        new InlineKeyboardButton(e.getKey() + ") " + e.getValue())
                                .callbackData(e.getKey() + ":" + roundId)
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
        if (g == null) return;

        StringBuilder sb = new StringBuilder("📊 Round results:\n\n");

        for (Long userId : g.players.keySet()) {

            String name = g.players.get(userId).getName();

            boolean answered = g.getAnsweredThisRound().contains(userId);
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
        if (g == null) return;

        StringBuilder sb = new StringBuilder("🏆 FINAL RESULTS:\n\n");
        StringBuilder resultLog = new StringBuilder();

        g.players.keySet().stream()
                .sorted((a, b) -> g.scores.getOrDefault(b, 0) - g.scores.getOrDefault(a, 0))
                .forEach(userId -> {

                    Player p = g.players.get(userId);
                    String name = (p != null) ? p.getName() : String.valueOf(userId);
                    int score = g.scores.getOrDefault(userId, 0);

                    sb.append(name)
                            .append(": ")
                            .append(score)
                            .append("\n");

                    if (!resultLog.isEmpty()) resultLog.append(", ");
                    resultLog.append(name).append("=").append(score);
                });

        log.info("Final results in room {}: {}", room, resultLog);

        sb.append("\n👉 /newGame to start a new game\n👉 /help for all commands");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(
                new InlineKeyboardButton[][]{
                        {new InlineKeyboardButton("🔁 Play Again").callbackData("REPLAY")}
                });

        bot.execute(new SendMessage(chatId, sb.toString()).replyMarkup(keyboard));
    }

    /**
     * Recreates a game in this room with the same category/count/timeout as the game
     * that just finished, so players can rematch without retyping /newGame's arguments.
     */
    private void handleReplay(String room, long chatId, String callbackId) {

        GameState g = gameService.get(room);

        if (g == null || g.getPhase() != GameState.GamePhase.FINISHED) {
            bot.execute(new AnswerCallbackQuery(callbackId)
                    .text("⚠️ Can't replay right now.").showAlert(false));
            return;
        }

        String category = g.category;
        int count = g.getQuestions().size();
        int timeoutSeconds = g.getTimeoutSeconds();

        gameService.createGame(room, category, count, timeoutSeconds);

        log.info("Replay: game recreated in room {} category={} count={} timeout={}s", room, category, count, timeoutSeconds);

        bot.execute(new AnswerCallbackQuery(callbackId).text("🔁 New game created!"));

        bot.execute(new SendMessage(chatId,
                "🎮 Game created!\n" +
                        "Category: " + category + "\n" +
                        "Questions: " + count + "\n" +
                        "Timeout: " + timeoutSeconds + "s\n\n" +
                        "👉 /join\n👉 /ready\n👉 /startGame\n👉 /list"));
    }

    private String normalizeCommand(String text) {
        if (text == null) return null;
        return text.split("@")[0].trim();
    }
}
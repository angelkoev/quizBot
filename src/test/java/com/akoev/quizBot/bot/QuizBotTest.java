package com.akoev.quizBot.bot;

import com.akoev.quizBot.model.GameState;
import com.akoev.quizBot.service.GameService;
import com.akoev.quizBot.service.QuestionLoader;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.SendMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Drives {@link QuizBot}'s command/callback handling directly with plain values (see
 * {@link QuizBot#handleCommand} and {@link QuizBot#handleCallback}), so these tests never
 * need to construct a real pengrad {@code Update}. {@link TelegramBot#execute} is stubbed
 * to record every outgoing request instead of hitting the Telegram API.
 */
class QuizBotTest {

    private static final long CHAT_ID = 555L;
    private static final String ROOM = String.valueOf(CHAT_ID);
    private static final long ALICE_ID = 1L;
    private static final long BOB_ID = 2L;

    private ScheduledExecutorService scheduler;
    private GameService gameService;
    private QuizBot quizBot;
    private List<BaseRequest<?, ?>> sent;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = Executors.newScheduledThreadPool(2);

        QuestionLoader loader = new QuestionLoader();
        loader.init();
        gameService = new GameService(scheduler, loader);

        TelegramBot bot = mock(TelegramBot.class);
        sent = new ArrayList<>();
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(bot).execute(any());

        quizBot = new QuizBot(gameService, bot);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private List<String> messageTexts() {
        return sent.stream()
                .filter(SendMessage.class::isInstance)
                .map(r -> ((SendMessage) r).getText())
                .toList();
    }

    private String lastMessageText() {
        List<String> texts = messageTexts();
        return texts.get(texts.size() - 1);
    }

    private List<String> callbackAlertTexts() {
        return sent.stream()
                .filter(AnswerCallbackQuery.class::isInstance)
                .map(r -> String.valueOf(((AnswerCallbackQuery) r).getParameters().get("text")))
                .toList();
    }

    // ---- /newGame ----

    @Test
    void newGame_missingArgs_sendsUsage() {
        quizBot.handleCommand("/newGame", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("Usage: /newGame"));
    }

    @Test
    void newGame_invalidCategory_sendsError() {
        quizBot.handleCommand("/newGame not-a-category 5", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("Unknown category"));
    }

    @Test
    void newGame_nonPositiveCount_sendsError() {
        quizBot.handleCommand("/newGame all 0", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("Count must be positive"));
    }

    @Test
    void newGame_validArgs_createsGameAndSendsConfirmation() {
        quizBot.handleCommand("/newGame all 3", CHAT_ID, ALICE_ID, ROOM, "Alice");

        String text = lastMessageText();
        assertTrue(text.contains("Category: all"));
        assertTrue(text.contains("Questions: 3"));

        GameState g = gameService.get(ROOM);
        assertNotNull(g);
        assertEquals(GameState.GamePhase.WAITING, g.getPhase());
    }

    @Test
    void newGame_blockedWhileGameInProgress() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/startGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");

        assertTrue(lastMessageText().contains("already in progress"));
    }

    @Test
    void newGame_allowedAgainWhileWaiting_replacesExistingGame() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/newGame all 2", CHAT_ID, ALICE_ID, ROOM, "Alice");

        GameState g = gameService.get(ROOM);
        assertEquals(2, g.getQuestions().size());
        assertTrue(g.players.isEmpty());
    }

    // ---- /join ----

    @Test
    void join_withoutGame_sendsError() {
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("No game"));
    }

    @Test
    void join_addsPlayerThenRejectsDuplicateJoin() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("joined"));

        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("Already joined"));
    }

    // ---- /list ----

    @Test
    void list_withNoPlayers_sendsNoPlayersMessage() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/list", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("No players"));
    }

    @Test
    void list_showsCheckmarkForReadyAndCrossForNotReady() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, BOB_ID, ROOM, "Bob");
        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/list", CHAT_ID, ALICE_ID, ROOM, "Alice");

        String text = lastMessageText();
        assertTrue(text.contains("✅ Alice"));
        assertTrue(text.contains("❌ Bob"));
    }

    // ---- /ready ----

    @Test
    void ready_withoutJoining_sendsError() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("Join first"));
    }

    @Test
    void ready_marksPlayerReady_thenAlreadyReadyOnSecondCall() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("is ready"));

        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");
        assertTrue(lastMessageText().contains("already ready"));
    }

    // ---- /startGame ----

    @Test
    void startGame_blockedUntilAllPlayersReady() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, BOB_ID, ROOM, "Bob");
        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/startGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        assertTrue(lastMessageText().contains("Waiting for players"));
        assertEquals(GameState.GamePhase.WAITING, gameService.get(ROOM).getPhase());
    }

    // ---- /pauseGame & /resumeGame ----

    @Test
    void pauseGame_rejectsNonJoinedPlayer() {
        startSinglePlayerGame(1);

        quizBot.handleCommand("/pauseGame", CHAT_ID, 999L, ROOM, "Eve");

        assertTrue(lastMessageText().contains("Only joined players can pause"));
    }

    @Test
    void pauseGame_rejectsSecondPauseWhileAlreadyPaused() {
        startSinglePlayerGame(1);

        quizBot.handleCommand("/pauseGame", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/pauseGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        assertTrue(lastMessageText().contains("already paused"));
    }

    @Test
    void pauseGame_thenResume_startsNextRoundOnceCurrentQuestionFinishes() {
        startSinglePlayerGame(2);

        quizBot.handleCommand("/pauseGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        GameState g = gameService.get(ROOM);
        int roundId = g.getRoundId();
        String key = gameService.current(ROOM).getOptions().keySet().iterator().next();

        // Alice answers the (only) question of round 1 - completes the round.
        quizBot.handleCallback(ROOM, CHAT_ID, ALICE_ID, key + ":" + roundId, "cb-answer");

        assertTrue(lastMessageText().contains("Game paused"));
        assertTrue(g.isAwaitingResume());

        quizBot.handleCommand("/resumeGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        assertFalse(g.isPaused());
        assertFalse(g.isAwaitingResume());
        assertTrue(lastMessageText().contains("[Question 2 / 2]"));
    }

    // ---- /endGame ----

    @Test
    void endGame_rejectsNonJoinedPlayer() {
        startSinglePlayerGame(1);

        quizBot.handleCommand("/endGame", CHAT_ID, 999L, ROOM, "Eve");

        assertTrue(lastMessageText().contains("Only joined players can end"));
    }

    @Test
    void endGame_beforeStart_cancelsWithoutShowingResults() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCommand("/endGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        assertTrue(lastMessageText().contains("cancelled before it started"));
        assertEquals(GameState.GamePhase.FINISHED, gameService.get(ROOM).getPhase());
    }

    @Test
    void endGame_duringGame_showsFinalResults() {
        startSinglePlayerGame(1);

        quizBot.handleCommand("/endGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        List<String> texts = messageTexts();
        assertTrue(texts.get(texts.size() - 2).contains("ended early"));
        assertTrue(texts.get(texts.size() - 1).contains("FINAL RESULTS"));
        assertEquals(GameState.GamePhase.FINISHED, gameService.get(ROOM).getPhase());
    }

    // ---- REPLAY ----

    @Test
    void replay_recreatesGameWithSameSettings() {
        quizBot.handleCommand("/newGame all 1 45", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/endGame", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCallback(ROOM, CHAT_ID, ALICE_ID, "REPLAY", "cb-replay");

        GameState g = gameService.get(ROOM);
        assertEquals("all", g.category);
        assertEquals(1, g.getQuestions().size());
        assertEquals(45, g.getTimeoutSeconds());
        assertEquals(GameState.GamePhase.WAITING, g.getPhase());
        assertTrue(lastMessageText().contains("Game created!"));
    }

    @Test
    void replay_rejectsWhenGameNotFinished() {
        quizBot.handleCommand("/newGame all 1", CHAT_ID, ALICE_ID, ROOM, "Alice");

        quizBot.handleCallback(ROOM, CHAT_ID, ALICE_ID, "REPLAY", "cb-replay");

        assertTrue(callbackAlertTexts().stream().anyMatch(t -> t.contains("Can't replay")));
    }

    /**
     * Creates a game with {@code questionCount} questions, joins and readies a single
     * player (Alice), and starts it - leaving the game {@code IN_GAME} with round 1 open.
     */
    private void startSinglePlayerGame(int questionCount) {
        quizBot.handleCommand("/newGame all " + questionCount, CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/join", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/ready", CHAT_ID, ALICE_ID, ROOM, "Alice");
        quizBot.handleCommand("/startGame", CHAT_ID, ALICE_ID, ROOM, "Alice");
    }
}

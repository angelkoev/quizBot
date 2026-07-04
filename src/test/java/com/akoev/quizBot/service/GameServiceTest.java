package com.akoev.quizBot.service;

import com.akoev.quizBot.model.GameState;
import com.akoev.quizBot.model.Question;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServiceTest {

    private static final String ROOM = "test-room";

    private ScheduledExecutorService scheduler;
    private GameService gameService;
    private int totalQuestionCount;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = Executors.newScheduledThreadPool(2);

        QuestionLoader loader = new QuestionLoader();
        loader.init();

        totalQuestionCount = loader.loadAll().values().stream().mapToInt(List::size).sum();

        gameService = new GameService(scheduler, loader);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // ---- categories ----

    @Test
    void getCategories_returnsLoadedCategories() {
        assertFalse(gameService.getCategories().isEmpty());
    }

    @Test
    void isValidCategory_acceptsAllAndKnownCategories() {
        assertTrue(gameService.isValidCategory("all"));
        assertTrue(gameService.isValidCategory("ALL"));

        String realCategory = gameService.getCategories().iterator().next();
        assertTrue(gameService.isValidCategory(realCategory));
    }

    @Test
    void isValidCategory_rejectsUnknownCategory() {
        assertFalse(gameService.isValidCategory("not-a-real-category"));
    }

    // ---- createGame ----

    @Test
    void createGame_picksRequestedQuestionCountFromAllCategories() {
        gameService.createGame(ROOM, "all", 2, 30);

        GameState g = gameService.get(ROOM);

        assertNotNull(g);
        assertEquals("all", g.category);
        assertEquals(30, g.getTimeoutSeconds());
        assertEquals(2, g.getQuestions().size());
    }

    @Test
    void createGame_capsQuestionCountAtWhatsAvailable() {
        gameService.createGame(ROOM, "all", totalQuestionCount + 1000, 30);

        GameState g = gameService.get(ROOM);

        assertEquals(totalQuestionCount, g.getQuestions().size());
    }

    // ---- join ----

    @Test
    void join_addsNewPlayerAndRejectsDuplicateJoin() {
        gameService.createGame(ROOM, "all", 2, 30);

        assertTrue(gameService.join(ROOM, 1L, "Alice"));
        assertFalse(gameService.join(ROOM, 1L, "Alice"));

        assertTrue(gameService.get(ROOM).players.containsKey(1L));
    }

    @Test
    void join_returnsFalse_whenNoGameExistsForRoom() {
        assertFalse(gameService.join("no-such-room", 1L, "Alice"));
    }

    // ---- rounds & answers ----

    @Test
    void openRound_incrementsRoundIdEachCall() {
        gameService.createGame(ROOM, "all", 2, 30);

        assertEquals(1, gameService.openRound(ROOM));
        assertEquals(2, gameService.openRound(ROOM));
    }

    @Test
    void openRound_returnsNegativeOne_whenNoGame() {
        assertEquals(-1, gameService.openRound("no-such-room"));
    }

    @Test
    void submitAnswer_returnsNoGame_whenRoomHasNoGame() {
        assertEquals(GameService.AnswerResult.NO_GAME,
                gameService.submitAnswer("no-such-room", 1L, 1, "A"));
    }

    @Test
    void submitAnswer_returnsStaleRound_forWrongOrUnopenedRound() {
        gameService.createGame(ROOM, "all", 2, 30);
        gameService.join(ROOM, 1L, "Alice");

        // round hasn't been opened yet
        assertEquals(GameService.AnswerResult.STALE_ROUND,
                gameService.submitAnswer(ROOM, 1L, 1, "A"));

        int roundId = gameService.openRound(ROOM);

        // wrong round id
        assertEquals(GameService.AnswerResult.STALE_ROUND,
                gameService.submitAnswer(ROOM, 1L, roundId + 1, "A"));
    }

    @Test
    void submitAnswer_recordsCorrectAnswerAndScoresPoint() {
        gameService.createGame(ROOM, "all", 2, 30);
        gameService.join(ROOM, 1L, "Alice");
        gameService.join(ROOM, 2L, "Bob");

        int roundId = gameService.openRound(ROOM);
        String correctKey = gameService.current(ROOM).getCorrectKey();

        GameService.AnswerResult result = gameService.submitAnswer(ROOM, 1L, roundId, correctKey);

        assertEquals(GameService.AnswerResult.RECORDED, result);

        GameState g = gameService.get(ROOM);
        assertEquals(1, g.scores.get(1L));
        assertTrue(g.roundCorrect.get(1L));
    }

    @Test
    void submitAnswer_doesNotScore_whenAnswerIsWrong() {
        gameService.createGame(ROOM, "all", 2, 30);
        gameService.join(ROOM, 1L, "Alice");
        gameService.join(ROOM, 2L, "Bob");

        int roundId = gameService.openRound(ROOM);
        Question q = gameService.current(ROOM);
        String wrongKey = q.getOptions().keySet().stream()
                .filter(k -> !k.equals(q.getCorrectKey()))
                .findFirst()
                .orElseThrow();

        gameService.submitAnswer(ROOM, 1L, roundId, wrongKey);

        GameState g = gameService.get(ROOM);
        assertNull(g.scores.get(1L));
        assertFalse(g.roundCorrect.get(1L));
    }

    @Test
    void submitAnswer_returnsAlreadyAnswered_onSecondSubmissionSameRound() {
        gameService.createGame(ROOM, "all", 2, 30);
        gameService.join(ROOM, 1L, "Alice");
        gameService.join(ROOM, 2L, "Bob");

        int roundId = gameService.openRound(ROOM);
        String key = gameService.current(ROOM).getOptions().keySet().iterator().next();

        gameService.submitAnswer(ROOM, 1L, roundId, key);
        GameService.AnswerResult second = gameService.submitAnswer(ROOM, 1L, roundId, key);

        assertEquals(GameService.AnswerResult.ALREADY_ANSWERED, second);
    }

    @Test
    void submitAnswer_returnsRoundComplete_onlyOnce_whenLastPlayerAnswers() {
        gameService.createGame(ROOM, "all", 2, 30);
        gameService.join(ROOM, 1L, "Alice");
        gameService.join(ROOM, 2L, "Bob");

        int roundId = gameService.openRound(ROOM);
        String key = gameService.current(ROOM).getOptions().keySet().iterator().next();

        assertEquals(GameService.AnswerResult.RECORDED,
                gameService.submitAnswer(ROOM, 1L, roundId, key));
        assertEquals(GameService.AnswerResult.ROUND_COMPLETE,
                gameService.submitAnswer(ROOM, 2L, roundId, key));

        // round is already closed - a stale duplicate delivery must not double-fire
        assertEquals(GameService.AnswerResult.STALE_ROUND,
                gameService.submitAnswer(ROOM, 2L, roundId, key));
    }

    // ---- advance / isGameOver ----

    @Test
    void isGameOver_becomesTrueOnlyAfterAllQuestionsAdvanced() {
        gameService.createGame(ROOM, "all", 2, 30);

        assertFalse(gameService.isGameOver(ROOM));

        gameService.advance(ROOM);
        assertFalse(gameService.isGameOver(ROOM));

        gameService.advance(ROOM);
        assertTrue(gameService.isGameOver(ROOM));
    }

    @Test
    void isGameOver_isTrue_whenNoGameExists() {
        assertTrue(gameService.isGameOver("no-such-room"));
    }

    // ---- timers ----

    @Test
    void startTimer_firesOnTimeout_whenNotCancelled() throws InterruptedException {
        gameService.createGame(ROOM, "all", 1, 0);
        int roundId = gameService.openRound(ROOM);

        CountDownLatch fired = new CountDownLatch(1);
        gameService.startTimer(ROOM, roundId, fired::countDown);

        assertTrue(fired.await(2, TimeUnit.SECONDS));
    }

    @Test
    void cancelTimer_preventsOnTimeoutFromFiring() throws InterruptedException {
        gameService.createGame(ROOM, "all", 1, 1);
        int roundId = gameService.openRound(ROOM);

        CountDownLatch fired = new CountDownLatch(1);
        gameService.startTimer(ROOM, roundId, fired::countDown);
        gameService.cancelTimer(ROOM);

        assertFalse(fired.await(1500, TimeUnit.MILLISECONDS));
    }

    @Test
    void startTimer_doesNotFire_whenRoundAlreadyClosed() throws InterruptedException {
        gameService.createGame(ROOM, "all", 1, 0);
        int roundId = gameService.openRound(ROOM);

        GameState g = gameService.get(ROOM);
        assertTrue(g.tryCloseRound()); // simulate round already closed by a winning answer

        CountDownLatch fired = new CountDownLatch(1);
        gameService.startTimer(ROOM, roundId, fired::countDown);

        assertFalse(fired.await(500, TimeUnit.MILLISECONDS));
    }

    // ---- shuffling ----

    @Test
    void currentShuffled_preservesCorrectAnswerValueAndOptionSet() {
        gameService.createGame(ROOM, "all", 1, 30);

        Question original = gameService.current(ROOM);
        String originalCorrectValue = original.getOptions().get(original.getCorrectKey());

        Question shuffled = gameService.currentShuffled(ROOM);
        String shuffledCorrectValue = shuffled.getOptions().get(shuffled.getCorrectKey());

        assertEquals(originalCorrectValue, shuffledCorrectValue);
        assertEquals(new HashSet<>(original.getOptions().values()), new HashSet<>(shuffled.getOptions().values()));
    }
}

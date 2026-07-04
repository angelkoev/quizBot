package com.akoev.quizBot.service;

import com.akoev.quizBot.model.GameState;
import com.akoev.quizBot.model.Player;
import com.akoev.quizBot.model.Question;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class GameService {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * Result of an answer submission, telling the caller exactly what to do next.
     * ROUND_COMPLETE is returned to at most one caller per round - it is the signal
     * to send results and advance, and it can never be returned twice for the same
     * round because it is gated by GameState#tryCloseRound (a single CAS).
     */
    public enum AnswerResult {
        NO_GAME,
        STALE_ROUND,
        ALREADY_ANSWERED,
        RECORDED,
        ROUND_COMPLETE
    }

    private final Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private final Map<String, List<Question>> categories;

    public GameService(ScheduledExecutorService scheduler, QuestionLoader loader) {
        this.scheduler = scheduler;
        this.categories = loader.loadAll();
    }

    public Set<String> getCategories() {
        return categories.keySet();
    }

    // CREATE ROOM
    public void createGame(String chatId, String category, int count, int timeoutSeconds) {

        cancelTimer(chatId);

        List<Question> opts;

        if (category.equalsIgnoreCase("all")) {
            opts = new ArrayList<>(
                    categories.values()
                            .stream()
                            .flatMap(List::stream)
                            .toList()
            );
        } else {
            opts = new ArrayList<>(categories.getOrDefault(category, List.of()));
        }

        Collections.shuffle(opts);

        GameState g = new GameState();
        g.category = category;
        g.setQuestions(opts.subList(0, Math.min(count, opts.size())));
        g.setTimeoutSeconds(timeoutSeconds);

        games.put(chatId, g);
    }

    public boolean isValidCategory(String category) {

        return category.equalsIgnoreCase("all")
                || categories.containsKey(category);
    }

    public GameState get(String chatId) {
        return games.get(chatId);
    }

    // JOIN ROOM
    public boolean join(String chatId, long userId, String name) {

        GameState g = games.get(chatId);
        if (g == null) return false;

        if (g.players.containsKey(userId)) return false;

        g.players.put(userId, new Player(userId, name));

        return true;
    }

    // CURRENT QUESTION
    public Question current(String chatId) {
        GameState g = games.get(chatId);
        return g.getQuestions().get(g.getIndex());
    }

    /**
     * Opens the next round (bumps round id, resets round-scoped state) and returns the
     * round id that must be embedded in that round's inline keyboard callback data, so
     * late presses on an earlier round's keyboard can be recognized and rejected.
     */
    public int openRound(String chatId) {
        GameState g = games.get(chatId);
        if (g == null) return -1;
        return g.openRound();
    }

    // ANSWER
    public AnswerResult submitAnswer(String chatId, long userId, int roundId, String answer) {

        GameState g = games.get(chatId);
        if (g == null) return AnswerResult.NO_GAME;

        // Reject presses on a keyboard from a round that has already moved on.
        if (g.getRoundId() != roundId || !g.isRoundOpen()) {
            return AnswerResult.STALE_ROUND;
        }

        // Set.add() on a ConcurrentHashMap-backed set is atomic: this is the single
        // dedup point, so a user can never be scored twice for the same round even
        // under concurrent/duplicate callback delivery.
        if (!g.getAnsweredThisRound().add(userId)) {
            return AnswerResult.ALREADY_ANSWERED;
        }

        g.getAnswers().put(userId, answer);

        Question q = current(chatId);
        boolean correct = answer.equalsIgnoreCase(q.getCorrectKey());

        if (correct) {
            g.scores.merge(userId, 1, Integer::sum);
        }
        g.roundCorrect.put(userId, correct);

        if (g.getAnsweredThisRound().size() >= g.players.size() && g.tryCloseRound()) {
            return AnswerResult.ROUND_COMPLETE;
        }

        return AnswerResult.RECORDED;
    }

    public boolean isGameOver(String chatId) {
        GameState g = games.get(chatId);
        return g == null || g.getIndex() >= g.getQuestions().size();
    }

    public void advance(String chatId) {
        GameState g = games.get(chatId);
        if (g != null) g.advanceIndex();
    }

    /**
     * Schedules the round timeout. If it fires, it attempts to close the round itself
     * (guarded by the same CAS as answer submission) and only invokes onTimeout if it
     * actually won that race - so onTimeout and a concurrent ROUND_COMPLETE from
     * submitAnswer can never both run for the same round.
     */
    public void startTimer(String chatId, int roundId, Runnable onTimeout) {

        cancelTimer(chatId);

        GameState g = games.get(chatId);
        int timeoutSeconds = (g != null) ? g.getTimeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        ScheduledFuture<?> task = scheduler.schedule(() -> {

            GameState current = games.get(chatId);
            if (current == null || current.getRoundId() != roundId) return;

            if (current.tryCloseRound()) {
                onTimeout.run();
            }

        }, timeoutSeconds, TimeUnit.SECONDS);

        timers.put(chatId, task);
    }

    public void cancelTimer(String chatId) {

        ScheduledFuture<?> task = timers.remove(chatId);

        if (task != null) {
            task.cancel(false);
        }
    }

    public Question currentShuffled(String chatId) {

        Question original = current(chatId);

        Question q = original.copy();

        List<Map.Entry<String, String>> entries =
                new ArrayList<>(q.getOptions().entrySet());

        Collections.shuffle(entries);

        Map<String, String> shuffled = new LinkedHashMap<>();
        String[] labels = {"A", "B", "C", "D"};

        String newCorrect = null;

        for (int i = 0; i < entries.size(); i++) {

            String label = labels[i];
            String value = entries.get(i).getValue();

            shuffled.put(label, value);

            if (entries.get(i).getKey().equals(original.getCorrectKey())) {
                newCorrect = label;
            }
        }

        return new Question(
                q.getQuestion(),
                shuffled,
                newCorrect
        );
    }
}
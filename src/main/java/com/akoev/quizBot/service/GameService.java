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
    public void createGame(String chatId, String category, int count) {

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
        g.questions = opts.subList(0, Math.min(count, opts.size()));

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
        return g.questions.get(g.index);
    }

    // ANSWER
    public synchronized void answer(String chatId, long userId, String answer) {

        GameState g = games.get(chatId);
        if (g == null) return;

        if (g.getAnsweredThisRound().contains(userId)) return;

        g.getAnsweredThisRound().add(userId);
        g.getAnswers().put(userId, answer);

        Question q = current(chatId);

        boolean correct = answer.equalsIgnoreCase(q.getCorrectKey());

        if (correct) {
            g.scores.merge(userId, 1, Integer::sum);
        }

        g.roundCorrect.put(userId, correct);
    }

    // ROUND DONE?
    public boolean allAnswered(String chatId) {

        GameState g = games.get(chatId);
        return g != null && g.answeredThisRound.size() == g.players.size();
    }

    // NEXT ROUND
    public void next(String chatId) {

        GameState g = games.get(chatId);

        g.index++;
        g.answers.clear();
        g.answeredThisRound.clear();
        g.roundCorrect.clear();

        g.setFinishedRound(false); // 🔥 reset за нов рунд
    }

    public boolean isGameOver(String chatId) {
        GameState g = games.get(chatId);
        return g.index >= g.questions.size();
    }

    public void startTimer(String chatId, Runnable onTimeout) {

        cancelTimer(chatId);

        ScheduledFuture<?> task = scheduler.schedule(() -> {

            GameState g = games.get(chatId);

            if (g == null || g.isFinishedRound()) return;

            g.setFinishedRound(true);

            onTimeout.run();

        }, 60, TimeUnit.SECONDS);

        timers.put(chatId, task);
    }

    public void cancelTimer(String chatId) {

        ScheduledFuture<?> task = timers.remove(chatId);

        if (task != null) {
            task.cancel(false);
        }
    }

    public void forceNext(String chatId) {

        GameState g = games.get(chatId);

        if (g == null || g.isFinishedRound()) return;

        g.setFinishedRound(true);

        next(chatId);
    }

    public Question currentShuffled(String chatId) {

        Question original = current(chatId);

        // правим копие (ВАЖНО!)
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
package com.akoev.quizBot.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GameState {

    public String category;

    private List<Question> questions;

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }

    private volatile int index = 0;

    public int getIndex() {
        return index;
    }

    public final Map<Long, Player> players = new ConcurrentHashMap<>();
    public final Map<Long, Integer> scores = new ConcurrentHashMap<>();

    /**
     * Players who have signalled /ready. Distinct from {@link #players} (who has
     * joined) - the game only actually starts once every joined player is ready
     * AND the /startGame command is issued.
     */
    public final Set<Long> readyPlayers = ConcurrentHashMap.newKeySet();

    public boolean allPlayersReady() {
        return !players.isEmpty() && readyPlayers.containsAll(players.keySet());
    }

    private final Map<Long, String> answers = new ConcurrentHashMap<>();

    public Map<Long, String> getAnswers() {
        return answers;
    }

    private final Set<Long> answeredThisRound = ConcurrentHashMap.newKeySet();

    public Set<Long> getAnsweredThisRound() {
        return answeredThisRound;
    }

    public final Map<Long, Boolean> roundCorrect = new ConcurrentHashMap<>();

    /**
     * Monotonically increasing id for the currently open round. Embedded into inline
     * button callback data so a press on a stale (previous-round) keyboard can be
     * detected and ignored instead of being silently attributed to the new round.
     */
    private final AtomicInteger roundId = new AtomicInteger(0);

    /**
     * True while the current round is still accepting answers. Flipped to false via
     * compareAndSet by whichever of {answer-submission, timer-expiry} gets there first -
     * that CAS is the single source of truth for "who gets to close this round", so the
     * two paths can never both act on the same round.
     */
    private final AtomicBoolean roundOpen = new AtomicBoolean(false);

    public enum GamePhase {
        WAITING,   // game created, waiting for players / not yet started
        IN_GAME,   // round(s) in progress
        FINISHED
    }

    private volatile GamePhase phase = GamePhase.WAITING;

    public GamePhase getPhase() {
        return phase;
    }

    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    public int getRoundId() {
        return roundId.get();
    }

    public void advanceIndex() {
        index++;
    }

    /**
     * Opens a new round: bumps the round id, clears all round-scoped answer state and
     * marks the round as open. Must be called exactly once per round, before the
     * question/timer for that round are dispatched.
     *
     * @return the new round id
     */
    public int openRound() {
        answers.clear();
        answeredThisRound.clear();
        roundCorrect.clear();
        roundOpen.set(true);
        return roundId.incrementAndGet();
    }

    /**
     * Attempts to close the round. Only the first caller (per round) succeeds.
     */
    public boolean tryCloseRound() {
        return roundOpen.compareAndSet(true, false);
    }

    public boolean isRoundOpen() {
        return roundOpen.get();
    }
}

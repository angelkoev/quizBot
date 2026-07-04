package com.akoev.quizBot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateTest {

    @Test
    void allPlayersReady_isFalseWhenNoPlayersHaveJoined() {
        GameState g = new GameState();
        assertFalse(g.allPlayersReady());
    }

    @Test
    void allPlayersReady_tracksReadyStateAsPlayersJoinAndReady() {
        GameState g = new GameState();
        g.players.put(1L, new Player(1L, "Alice"));

        assertFalse(g.allPlayersReady());

        g.readyPlayers.add(1L);
        assertTrue(g.allPlayersReady());

        g.players.put(2L, new Player(2L, "Bob"));
        assertFalse(g.allPlayersReady());

        g.readyPlayers.add(2L);
        assertTrue(g.allPlayersReady());
    }

    @Test
    void phase_defaultsToWaitingAndCanBeChanged() {
        GameState g = new GameState();
        assertEquals(GameState.GamePhase.WAITING, g.getPhase());

        g.setPhase(GameState.GamePhase.IN_GAME);
        assertEquals(GameState.GamePhase.IN_GAME, g.getPhase());
    }

    @Test
    void pausedAndAwaitingResume_defaultToFalseAndAreSettable() {
        GameState g = new GameState();

        assertFalse(g.isPaused());
        assertFalse(g.isAwaitingResume());

        g.setPaused(true);
        g.setAwaitingResume(true);

        assertTrue(g.isPaused());
        assertTrue(g.isAwaitingResume());
    }

    @Test
    void openRound_incrementsRoundIdAndOpensRound() {
        GameState g = new GameState();

        assertFalse(g.isRoundOpen());

        int firstRound = g.openRound();
        assertEquals(1, firstRound);
        assertTrue(g.isRoundOpen());

        int secondRound = g.openRound();
        assertEquals(2, secondRound);
    }

    @Test
    void openRound_clearsPreviousRoundScopedState() {
        GameState g = new GameState();
        g.openRound();

        g.getAnswers().put(1L, "A");
        g.getAnsweredThisRound().add(1L);
        g.roundCorrect.put(1L, true);

        g.openRound();

        assertTrue(g.getAnswers().isEmpty());
        assertTrue(g.getAnsweredThisRound().isEmpty());
        assertTrue(g.roundCorrect.isEmpty());
    }

    @Test
    void tryCloseRound_onlyTheFirstCallerSucceeds() {
        GameState g = new GameState();
        g.openRound();

        assertTrue(g.tryCloseRound());
        assertFalse(g.isRoundOpen());
        assertFalse(g.tryCloseRound());
    }
}

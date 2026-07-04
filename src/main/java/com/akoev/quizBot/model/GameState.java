package com.akoev.quizBot.model;

import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameState {

    @Setter
    @Getter
    private boolean finishedRound = false;
    public boolean roundActive = false;
    public String category;

    @Getter
    public List<Question> questions;
    @Getter
    public int index = 0;

    public Map<Long, Player> players = new HashMap<>();
    public Map<Long, Integer> scores = new HashMap<>();

    @Getter
    public Map<Long, String> answers = new ConcurrentHashMap<>();
    @Getter
    public Set<Long> answeredThisRound = ConcurrentHashMap.newKeySet();
    public Map<Long, Boolean> roundCorrect = new HashMap<>();


    public enum GamePhase {
        WAITING,   // създадена игра, чака играчи
        READY,     // има играчи, може да стартира
        IN_GAME,   // върви игра
        FINISHED
    }

    @Setter
    @Getter
    private GamePhase phase = GamePhase.WAITING;

}
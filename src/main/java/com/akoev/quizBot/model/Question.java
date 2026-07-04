package com.akoev.quizBot.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Question {

    private final String question;
    private Map<String, String> options; // A, B, C, D
    private final String correctKey;

    public Question(String question, Map<String, String> options, String correctKey) {
        this.question = question;
        this.options = options;
        this.correctKey = correctKey;
    }

    public String getQuestion() {
        return question;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public String getCorrectKey() {
        return correctKey;
    }

    public boolean isTrueFalse() {
        return options.size() == 2;
    }

    public Question copy() {
        return new Question(
                this.question,
                new LinkedHashMap<>(this.options), // важно: нов Map
                this.correctKey
        );
    }
}
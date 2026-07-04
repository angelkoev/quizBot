package com.akoev.quizBot.model;

import lombok.Getter;
import lombok.Setter;

public class Player {

    @Getter
    private final long id;
    @Getter
    private String name;

    public Player(long id, String name) {
        this.id = id;
        this.name = name;
    }

}

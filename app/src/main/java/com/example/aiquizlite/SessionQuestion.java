package com.example.aiquizlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionQuestion {
    private final Question question;
    private final List<Question.Option> displayOptions;

    public SessionQuestion(Question question, boolean shuffleOptions) {
        this.question = question;
        this.displayOptions = new ArrayList<>(question.getOptions());
        if (shuffleOptions) {
            Collections.shuffle(this.displayOptions);
        }
    }

    public Question getQuestion() {
        return question;
    }

    public List<Question.Option> getDisplayOptions() {
        return displayOptions;
    }
}

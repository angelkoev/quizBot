package com.akoev.quizBot.service;

import com.akoev.quizBot.model.Question;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class QuestionLoader {

    private final Map<String, List<Question>> categories = new HashMap<>();

    @PostConstruct
    public void init() throws IOException {
        loadCategory("animals", "animals");
        loadCategory("brain", "brain-teasers");
        loadCategory("celebrities", "celebrities");
        loadCategory("entertainment", "entertainment");
        loadCategory("for-kids", "for-kids");
        loadCategory("general", "general");
        loadCategory("geography", "geography");
        loadCategory("history", "history");
        loadCategory("hobbies", "hobbies");
        loadCategory("humanities", "humanities");
        loadCategory("literature", "literature");
        loadCategory("movies", "movies");
        loadCategory("music", "music");
        loadCategory("newest", "newest");
        loadCategory("people", "people");
        loadCategory("rated", "rated");
        loadCategory("religion-faith", "religion-faith");
        loadCategory("science", "science-technology");
        loadCategory("sports", "sports");
        loadCategory("television", "television");
        loadCategory("video-games", "video-games");
        loadCategory("world", "world");
        loadCategory("bg", "bg");
    }

    public Set<String> getCategories() {
        return categories.keySet();
    }

    private void loadCategory(String category, String file) throws IOException {

        List<String> lines = readFile(file);

        List<Question> questions = new ArrayList<>();

        String questionText = null;
        String correctText = null;
        String correctKey = null;
        Map<String, String> options = new LinkedHashMap<>();

        for (String raw : lines) {

            String line = raw.trim();
            if (line.isBlank()) {
                continue;
            }

            // 👉 NEW QUESTION START
            if (line.startsWith("#Q")) {

                // save previous question
                if (questionText != null) {
                    addQuestion(options, correctText, correctKey, questions, questionText);
                }

                // reset state
                questionText = line.substring(2).trim();
                correctKey = null;
                options = new LinkedHashMap<>();

                continue;
            }

            // 👉 CORRECT
            if (line.startsWith("^")) {
                correctText = line.substring(1).trim();
                continue;
            }

            // 👉 OPTIONS
            if (line.matches("^[ABCD].*")) {
                String key = line.substring(0, 1);
                String value = line.substring(1).trim();
                options.put(key, value);
            }
        }

        // 👉 last question (важно!)
        if (questionText != null) {
            addQuestion(options, correctText, correctKey, questions, questionText);
        }

        categories.put(category, questions);
    }

    private static void addQuestion(Map<String, String> options, String correctText, String correctKey, List<Question> questions, String questionText) {
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getValue().trim().equalsIgnoreCase(correctText.trim())) {
                correctKey = e.getKey();
            }
        }
        questions.add(new Question(questionText, options, correctKey));
    }

    public List<Question> getCategory(String category) {
        return categories.getOrDefault(category, List.of());
    }

    public Map<String, List<Question>> loadAll() {
        return new HashMap<>(categories);
    }

    private List<String> readFile(String file) {

        try (InputStream is =
                     getClass().getClassLoader()
                             .getResourceAsStream("questions/" + file)) {

            if (is == null) {
                throw new RuntimeException("File not found: " + file);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(is));

            return br.lines().toList();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
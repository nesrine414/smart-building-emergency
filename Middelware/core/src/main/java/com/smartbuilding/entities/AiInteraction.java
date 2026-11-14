package com.smartbuilding.entities;

import jakarta.nosql.Column;
import jakarta.nosql.Entity;
import jakarta.nosql.Id;
import java.util.ArrayList;
import java.util.List;

@Entity
public class AiInteraction {

    @Id
    private String id;

    @Column
    private String kind; // "EXPLAIN" ou "ASK"

    @Column
    private String userId;

    @Column
    private String role;

    @Column
    private String input;

    @Column
    private String answer;

    @Column
    private List<String> toolsUsed = new ArrayList<>();

    @Column
    private String model;

    @Column
    private long latencyMs;

    @Column
    private int inputTokens;

    @Column
    private int outputTokens;

    @Column
    private String error;

    @Column
    private String vote; // "UP" ou "DOWN"

    @Column
    private String comment;

    @Column
    private long timestamp;

    public AiInteraction() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<String> getToolsUsed() { return toolsUsed; }
    public void setToolsUsed(List<String> toolsUsed) { this.toolsUsed = toolsUsed; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
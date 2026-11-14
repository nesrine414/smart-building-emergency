package com.smartbuilding.services;

import com.smartbuilding.ai.BuildingTools;
import com.smartbuilding.ai.EmergencyAiAgent;
import com.smartbuilding.entities.AiInteraction;
import com.smartbuilding.entities.Incident;
import com.smartbuilding.repositories.AiInteractionRepository;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class AiService {

    private static final Logger LOG = Logger.getLogger(AiService.class.getName());

    @Inject
    private EmergencyAiAgent agent;

    @Inject
    private BuildingTools buildingTools;

    @Inject
    private IncidentService incidentService;

    @Inject
    private AiInteractionRepository repository;

    public Map<String, Object> explainIncident(String incidentId, String userId, String role) {
        Optional<Incident> optInc = incidentService.findById(incidentId);
        if (optInc.isEmpty()) {
            throw new IllegalArgumentException("Incident introuvable : " + incidentId);
        }

        Incident inc = optInc.get();
        String prompt = String.format(
            "Analyse cet incident et donne une explication claire avec la marche a suivre : " +
            "Type=%s, Salle=%s, Gravite=%s, Description=%s. " +
            "Consulte les dernieres mesures de la salle et la sortie d'evacuation recommandee.",
            inc.getType(), inc.getRoomId(), inc.getSeverity(), inc.getDescription()
        );

        return executeAndRecord("EXPLAIN", prompt, incidentId, userId, role);
    }

    public Map<String, Object> askQuestion(String question, String roomId, String userId, String role) {
        String prompt = question;
        if (roomId != null && !roomId.isBlank()) {
            prompt += " (Je me trouve actuellement dans la salle " + roomId + ").";
        }
        return executeAndRecord("ASK", prompt, question, userId, role);
    }

    public boolean registerFeedback(String interactionId, String vote, String comment) {
        Optional<AiInteraction> opt = repository.findById(interactionId);
        if (opt.isEmpty()) {
            return false;
        }
        AiInteraction interaction = opt.get();
        interaction.setVote(vote);
        if (comment != null) {
            interaction.setComment(comment);
        }
        repository.save(interaction);
        return true;
    }

    public Map<String, Object> getStats() {
        long count = 0;
        long successfulCount = 0;
        long totalLatency = 0;
        long maxLatency = 0;
        long errors = 0;
        long upVotes = 0;
        long downVotes = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        try (var stream = repository.findAll()) {
            List<AiInteraction> list = stream.toList();
            count = list.size();
            for (AiInteraction i : list) {
                boolean hasError = i.getError() != null && !i.getError().isBlank();
                if (hasError) {
                    errors++;
                } else if (i.getLatencyMs() > 0) {
                    // Calcul propre : seules les requêtes sans erreur contribuent à la moyenne de latence
                    successfulCount++;
                    totalLatency += i.getLatencyMs();
                    if (i.getLatencyMs() > maxLatency) {
                        maxLatency = i.getLatencyMs();
                    }
                }

                totalInputTokens += i.getInputTokens();
                totalOutputTokens += i.getOutputTokens();

                if ("UP".equalsIgnoreCase(i.getVote())) {
                    upVotes++;
                } else if ("DOWN".equalsIgnoreCase(i.getVote())) {
                    downVotes++;
                }
            }
        }

        long avgLatency = successfulCount > 0 ? (totalLatency / successfulCount) : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("count", count);
        stats.put("successfulCount", successfulCount);
        stats.put("avgLatencyMs", avgLatency);
        stats.put("maxLatencyMs", maxLatency);
        stats.put("errors", errors);
        stats.put("upVotes", upVotes);
        stats.put("downVotes", downVotes);
        stats.put("totalInputTokens", totalInputTokens);
        stats.put("totalOutputTokens", totalOutputTokens);
        return stats;
    }

    public List<AiInteraction> getRecentInteractions() {
        try (var stream = repository.findAll()) {
            return stream.limit(50).toList();
        }
    }

    private Map<String, Object> executeAndRecord(String kind, String prompt, String inputRef, String userId, String role) {
        String interactionId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        String answer = null;
        String errorMessage = null;
        List<String> toolsUsed = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String modelName = System.getProperty("ollama.model.name", "qwen2.5:7b");

        // Démarrer le vrai tracking des outils appelés par le modèle
        buildingTools.startTracking();

        try {
            Result<String> result = agent.chat(prompt);
            answer = result.content();

            // Récupération des VRAIS outils exécutés par LangChain4j
            toolsUsed = new ArrayList<>(buildingTools.getTrackedTools());

            // Récupération des VRAIS jetons (tokens)
            TokenUsage tokenUsage = result.tokenUsage();
            if (tokenUsage != null) {
                inputTokens = tokenUsage.inputTokenCount() != null ? tokenUsage.inputTokenCount() : 0;
                outputTokens = tokenUsage.outputTokenCount() != null ? tokenUsage.outputTokenCount() : 0;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Erreur appel Ollama/LLM", e);
            errorMessage = "Service IA indisponible (Ollama arrete ou delai depasse) : " + e.getMessage();
        } finally {
            buildingTools.stopTracking();
        }

        long latency = System.currentTimeMillis() - start;

        // Persistance LLMOps complète dans MongoDB
        AiInteraction interaction = new AiInteraction();
        interaction.setId(interactionId);
        interaction.setKind(kind);
        interaction.setUserId(userId != null ? userId : "anonymous");
        interaction.setRole(role != null ? role : "OCCUPANT");
        interaction.setInput(inputRef);
        interaction.setAnswer(answer);
        interaction.setToolsUsed(toolsUsed);
        interaction.setModel(modelName);
        interaction.setLatencyMs(latency);
        interaction.setInputTokens(inputTokens);
        interaction.setOutputTokens(outputTokens);
        interaction.setError(errorMessage);
        interaction.setTimestamp(System.currentTimeMillis());

        repository.save(interaction);

        if (errorMessage != null) {
            throw new IllegalStateException(errorMessage);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("interactionId", interactionId);
        response.put("answer", answer);
        response.put("toolsUsed", toolsUsed);
        response.put("latencyMs", latency);
        response.put("inputTokens", inputTokens);
        response.put("outputTokens", outputTokens);
        response.put("model", modelName);
        return response;
    }
}
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
import java.util.Locale;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Locale;
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
        if (roomId != null && !roomId.isBlank() && isSensorDataQuestion(question)) {
            return executeVerifiedReadingsAndRecord(question, roomId, userId, role);
        }

        String prompt = question;
        if (roomId != null && !roomId.isBlank()) {
            prompt += " (Je me trouve actuellement dans la salle " + roomId + ").";
        }
        return executeAndRecord("ASK", prompt, question, userId, role);
    }

    private boolean isSensorDataQuestion(String question) {
        if (question == null) {
            return false;
        }

        String text = question.toLowerCase(Locale.ROOT);

        return text.contains("mesure")
                || text.contains("capteur")
                || text.contains("temperature")
                || text.contains("température")
                || text.contains("humidite")
                || text.contains("humidité")
                || text.contains("fumee")
                || text.contains("fumée")
                || text.contains("gaz")
                || text.contains("gas")
                || text.contains("smoke")
                || text.contains("motion")
                || text.contains("mouvement")
                || text.contains("releve")
                || text.contains("relevé")
                || text.contains("lecture")
                || text.contains("ppm");
    }

    /**
     * Les demandes de mesures sont formatees directement par Java.
     * Le modele de langage ne reecrit pas les valeurs techniques.
     */
    private Map<String, Object> executeVerifiedReadingsAndRecord(
            String question, String roomId, String userId, String role) {

        String interactionId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        String answer = null;
        String errorMessage = null;
        List<String> toolsUsed = new ArrayList<>();

        buildingTools.startTracking();

        try {
            String readings = buildingTools.getRecentReadings(roomId);
            toolsUsed = new ArrayList<>(buildingTools.getTrackedTools());
            answer = formatVerifiedReadings(readings, roomId);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Erreur lecture mesures verifiees", e);
            errorMessage = "Impossible de recuperer les mesures de la salle : " + e.getMessage();

        } finally {
            buildingTools.stopTracking();
        }

        long latency = System.currentTimeMillis() - start;
        String modelName = "java-verified-readings";

        AiInteraction interaction = new AiInteraction();
        interaction.setId(interactionId);
        interaction.setKind("ASK");
        interaction.setUserId(userId != null ? userId : "anonymous");
        interaction.setRole(role != null ? role : "OCCUPANT");
        interaction.setInput(question);
        interaction.setAnswer(answer);
        interaction.setToolsUsed(toolsUsed);
        interaction.setModel(modelName);
        interaction.setLatencyMs(latency);
        interaction.setInputTokens(0);
        interaction.setOutputTokens(0);
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
        response.put("inputTokens", 0);
        response.put("outputTokens", 0);
        response.put("model", modelName);

        return response;
    }

    private String formatVerifiedReadings(String readings, String roomId) {
        if (readings == null || readings.isBlank()) {
            return "Aucune mesure n'a ete retournee pour la salle " + roomId + ".";
        }

        Pattern pattern = Pattern.compile("timestamp=(\\d+)");
        Matcher matcher = pattern.matcher(readings);
        StringBuffer formatted = new StringBuffer();

        while (matcher.find()) {
            String replacement;

            try {
                long timestamp = Long.parseLong(matcher.group(1));
                Instant instant = Instant.ofEpochMilli(timestamp);

                long ageMillis = System.currentTimeMillis() - timestamp;
                String age;

                if (ageMillis < 0) {
                    age = "horodatage dans le futur";
                } else {
                    long totalMinutes = ageMillis / 60000;
                    long days = totalMinutes / 1440;
                    long hours = (totalMinutes % 1440) / 60;
                    long minutes = totalMinutes % 60;

                    age = days + "j " + hours + "h " + minutes + "min";
                }

                replacement = "horodatage_utc=" + instant
                        + " | anciennete=" + age;

            } catch (Exception e) {
                replacement = matcher.group();
            }

            matcher.appendReplacement(
                    formatted,
                    Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(formatted);

        return "RELEVE DE MESURES VERIFIEES - Salle " + roomId + " :\n"
                + formatted
                + "\n\nLes types, valeurs, unites et identifiants ci-dessus "
                + "proviennent directement du service de donnees. "
                + "Les dates sont exprimees en UTC avec leur anciennete au traitement."
                + "\nGAS ne permet pas d'identifier une espece chimique. "
                + "SMOKE ne permet pas de deduire une composition. "
                + "Ce releve ne constitue pas une certification de securite. "
                + "Suivre les procedures officielles d'urgence.";
    }


    public boolean registerFeedback(
            String interactionId,
            String vote,
            String comment,
            String userId) {

        // Validation défensive des paramètres
        if (interactionId == null || interactionId.isBlank()) {
            throw new IllegalArgumentException("interactionId obligatoire");
        }

        if (userId == null || userId.isBlank()) {
            throw new SecurityException("Utilisateur non authentifié");
        }

        if (vote == null || vote.isBlank()) {
            throw new IllegalArgumentException("Vote obligatoire");
        }

        String normalizedVote = vote.trim().toUpperCase(Locale.ROOT);

        if (!"UP".equals(normalizedVote)
                && !"DOWN".equals(normalizedVote)) {
            throw new IllegalArgumentException("Le vote doit être UP ou DOWN");
        }

        // Rechercher l'interaction
        Optional<AiInteraction> opt =
                repository.findById(interactionId.trim());

        if (opt.isEmpty()) {
            return false;
        }

        AiInteraction interaction = opt.get();

        // Vérifier que l'interaction appartient à l'utilisateur connecté
        if (interaction.getUserId() == null
                || !interaction.getUserId().equals(userId)) {
            throw new SecurityException(
                    "Tentative de modification d'une interaction appartenant à un autre utilisateur");
        }

        // Enregistrer le feedback
        interaction.setVote(normalizedVote);

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
        String modelName = System.getProperty("ollama.model.name", "llama3.2:3b");

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
package com.smartbuilding.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Duration;

@ApplicationScoped
public class AiAgentFactory {

    @Inject
    private BuildingTools buildingTools;

    @Produces
    @ApplicationScoped
    public EmergencyAiAgent createEmergencyAiAgent() {
        String baseUrl = System.getProperty("ollama.base.url", "http://localhost:11434");
        String modelName = System.getProperty("ollama.model.name", "qwen2.5:7b");

        ChatLanguageModel chatModel = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2) // Température basse pour éviter les hallucinations
                .timeout(Duration.ofSeconds(60))
                .build();

        return AiServices.builder(EmergencyAiAgent.class)
                .chatLanguageModel(chatModel)
                .tools(buildingTools)
                .build();
    }
}
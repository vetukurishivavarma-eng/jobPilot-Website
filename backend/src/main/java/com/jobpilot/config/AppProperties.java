package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("jobpilot")
public record AppProperties(String jwtSecret, int jwtTtlHours, String storageDir, List<String> corsOrigins,
                            String adzunaAppId, String adzunaAppKey, String adzunaCountry,
                            String ollamaUrl, String ollamaModel,
                            String llmUrl, String llmApiKey, String llmModel,
                            String llm2Url, String llm2ApiKey, String llm2Model) {
    public AppProperties {
        if (jwtSecret == null || jwtSecret.length() < 32)
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
        if (adzunaCountry == null || adzunaCountry.isBlank()) adzunaCountry = "in";
        if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = "http://localhost:11434";
        if (ollamaModel == null || ollamaModel.isBlank()) ollamaModel = "qwen2.5:3b-instruct";
    }

    /** One OpenAI-compatible endpoint + model to try. */
    public record Llm(String url, String key, String model) {}

    /**
     * Cloud models in the order they are tried: LLM_* first, then LLM2_*. Each MODEL may be a comma list
     * (e.g. Groq "llama-3.3-70b-versatile,llama-3.1-8b-instant"), so one key covers several free quotas.
     * A provider is skipped unless its url, key and model are all set. Ollama stays the last fallback.
     */
    public List<Llm> llms() {
        List<Llm> out = new ArrayList<>();
        add(out, llmUrl, llmApiKey, llmModel);
        add(out, llm2Url, llm2ApiKey, llm2Model);
        return out;
    }

    private static void add(List<Llm> out, String url, String key, String models) {
        if (url == null || url.isBlank() || key == null || key.isBlank() || models == null) return;
        for (String m : models.split(","))
            if (!m.isBlank()) out.add(new Llm(url.strip(), key.strip(), m.strip()));
    }

    public boolean adzunaEnabled() {
        return adzunaAppId != null && !adzunaAppId.isBlank() && adzunaAppKey != null && !adzunaAppKey.isBlank();
    }
}

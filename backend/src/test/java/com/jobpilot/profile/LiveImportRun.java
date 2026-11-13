package com.jobpilot.profile;

import com.jobpilot.profile.ProfileController.ProfileDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Throwaway: runs the import on a real resume against the real local model. -Dlive.resume=path */
@EnabledIfSystemProperty(named = "live.resume", matches = ".+")
class LiveImportRun {
    @Test
    void run() throws Exception {
        String text = Files.readString(Path.of(System.getProperty("live.resume")));
        var http = HttpClient.newHttpClient();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        long t0 = System.currentTimeMillis();
        var r = ResumeImport.run(text, ProfileDto.empty(), prompt -> {
            try {
                var body = mapper.createObjectNode().put("model", "qwen2.5:3b-instruct").put("prompt", prompt).put("stream", false).put("format", "json");
                body.putObject("options").put("temperature", 0.3);
                long t = System.currentTimeMillis();
                String res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:11434/api/generate")).timeout(Duration.ofSeconds(300))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString()).body();
                String out = mapper.readTree(res).path("response").asString("");
                System.out.println("=== LLM (" + (System.currentTimeMillis() - t) / 1000 + "s) " + prompt.lines().findFirst().orElse("") + "\n" + out);
                return out;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("=== TOTAL " + (System.currentTimeMillis() - t0) / 1000 + "s\n=== PROFILE\n" + r.profile() + "\n=== NOTES\n" + r.notes());
    }
}

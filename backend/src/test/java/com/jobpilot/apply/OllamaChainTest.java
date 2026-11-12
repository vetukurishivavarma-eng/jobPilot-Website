package com.jobpilot.apply;

import com.jobpilot.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OllamaChainTest {
    @Test
    void reportTriesTheReportModelFirst() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            asked.add(body.replaceAll(".*\"model\":\"([^\"]+)\".*", "$1"));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{}}".getBytes();
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1";
            AppProperties p = new AppProperties("x".repeat(40), 24, "./target/x", List.of(), null, null, null, null, null,
                    url, "k", "qwen/qwen3.8-27b,openai/gpt-oss-120b,openai/gpt-oss-20b", null, null, null);
            Ollama o = new Ollama(p, "openai/gpt-oss-120b");
            o.generateReport("p");
            o.generateShort("p");
            assertEquals(List.of("openai/gpt-oss-120b", "qwen/qwen3.8-27b"), asked);
        } finally { srv.stop(0); }
    }
}

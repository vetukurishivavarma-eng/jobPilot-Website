package com.jobpilot.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPropertiesTest {
    private static AppProperties props(String u1, String k1, String m1, String u2, String k2, String m2) {
        return new AppProperties("x".repeat(32), 24, null, List.of(), null, null, null, null, null, u1, k1, m1, u2, k2, m2);
    }

    @Test
    void chainTriesEveryModelOfProvider1ThenProvider2() {
        var llms = props("https://groq", "g", "big, small", "https://gem", "k", "flash,").llms();
        assertEquals(List.of("big", "small", "flash"), llms.stream().map(AppProperties.Llm::model).toList());
        assertEquals("https://gem", llms.get(2).url());
    }

    @Test
    void providerWithoutKeyIsSkipped() {
        assertEquals(1, props("https://groq", "", "big", "https://gem", "k", "flash").llms().size());
        assertEquals(0, props(null, null, null, null, null, null).llms().size());
    }
}

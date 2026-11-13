package com.jobpilot.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTest {
    @Autowired WebApplicationContext ctx;
    @Autowired PasswordResetController reset;
    @Autowired UserRepository users;

    @Test
    void resetFlow() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        String email = "r" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"oldpass123\",\"fullName\":\"R\"}")).andExpect(status().isCreated());

        // same answer for a real and an unknown email
        String known = mvc.perform(post("/api/auth/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/auth/forgot").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(known, unknown);

        User u = users.findByEmail(email).orElseThrow();
        String token = reset.token(u, Instant.now().plus(PasswordResetController.TTL));
        mvc.perform(get("/reset").param("token", token)).andExpect(content().string(containsString("Set a new password")));
        mvc.perform(get("/reset").param("token", token + "x")).andExpect(content().string(containsString("Link expired")));
        mvc.perform(get("/reset").param("token", reset.token(u, Instant.now().minusSeconds(1)))).andExpect(content().string(containsString("Link expired")));
        mvc.perform(get("/reset").param("token", "\"><script>")).andExpect(content().string(containsString("Link expired")));

        mvc.perform(post("/reset").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", token).param("password", "newpass123").param("confirm", "different1"))
                .andExpect(content().string(containsString("Try again")));
        mvc.perform(post("/reset").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", token).param("password", "newpass123").param("confirm", "newpass123"))
                .andExpect(content().string(containsString("Password changed")));

        // one-time: the same link is dead now; new password works, old doesn't
        mvc.perform(get("/reset").param("token", token)).andExpect(content().string(containsString("Link expired")));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"newpass123\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"oldpass123\"}")).andExpect(status().isUnauthorized());
    }
}

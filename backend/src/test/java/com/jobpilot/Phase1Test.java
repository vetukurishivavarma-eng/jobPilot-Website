package com.jobpilot;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class Phase1Test {

    @Autowired WebApplicationContext ctx;

    MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    /** Registers a fresh user and returns "Bearer <token>". */
    String register() throws Exception {
        String email = "u" + UUID.randomUUID() + "@example.com";
        String body = mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Asha Rao\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void authFlow() throws Exception {
        String email = "Login" + UUID.randomUUID() + "@Example.com";
        String reg = "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"A\"}";
        mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(reg)).andExpect(status().isCreated());
        mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(reg.replace(email, email.toLowerCase())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toLowerCase() + "\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        String body = mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mvc().perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email.toLowerCase()));
        mvc().perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc().perform(get("/api/auth/me").header("Authorization", "Bearer " + token + "x")).andExpect(status().isUnauthorized());

        mvc().perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void profileRoundTrip() throws Exception {
        String auth = register();
        mvc().perform(get("/api/profile").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skills", hasSize(0)));

        String profile = """
            {"headline":"Backend engineer","phone":"+91 98765 43210","location":"Hyderabad","currentTitle":"SDE 1",
             "yearsExperience":2.5,"expectedSalary":1200000,"salaryCurrency":"INR","noticePeriodDays":30,
             "workAuthorization":"Indian citizen","willingToRelocate":true,"linkedinUrl":"https://linkedin.com/in/asha",
             "githubUrl":"","portfolioUrl":null,"summary":"Builds APIs.",
             "skills":["Java","Spring Boot"," Java "],"desiredRoles":["Backend Developer"],"desiredLocations":["Bengaluru","Remote"],
             "experiences":[{"company":"Acme","title":"SDE 1","startDate":"2023-06-01","endDate":null,"description":"Payments"}],
             "educations":[{"institution":"JNTU","degree":"B.Tech","field":"CSE","startYear":2019,"endYear":2023,"grade":"8.1 CGPA"}]}
            """;
        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isOk());
        mvc().perform(get("/api/profile").header("Authorization", auth))
                .andExpect(jsonPath("$.skills", contains("Java", "Spring Boot")))
                .andExpect(jsonPath("$.githubUrl").value(nullValue()))
                .andExpect(jsonPath("$.experiences[0].company").value("Acme"))
                .andExpect(jsonPath("$.educations[0].grade").value("8.1 CGPA"))
                .andExpect(jsonPath("$.desiredLocations", contains("Bengaluru", "Remote")));

        // PUT replaces lists entirely
        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(profile.replace("\"skills\":[\"Java\",\"Spring Boot\",\" Java \"]", "\"skills\":[\"Go\"]")
                                .replaceAll("\"experiences\":\\[.*?\\]", "\"experiences\":[]")))
                .andExpect(jsonPath("$.skills", contains("Go"))).andExpect(jsonPath("$.experiences", hasSize(0)));

        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(profile.replace("\"endDate\":null", "\"endDate\":\"2020-01-01\"")))
                .andExpect(status().isBadRequest());
        mvc().perform(put("/api/profile").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(profile.replace("https://linkedin.com/in/asha", "javascript:alert(1)")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.linkedinUrl").exists());

        // Profiles are per user
        mvc().perform(get("/api/profile").header("Authorization", register())).andExpect(jsonPath("$.skills", hasSize(0)));
    }

    @Test
    void resumeLifecycle() throws Exception {
        String auth = register();
        MockMultipartFile pdf = new MockMultipartFile("file", "Asha_CV.pdf", "application/pdf", pdf("Asha Rao Java Spring Boot"));
        String first = mvc().perform(multipart("/api/resumes").file(pdf).header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Asha_CV"))
                .andExpect(jsonPath("$.primary").value(true))
                .andExpect(jsonPath("$.textExtracted").value(true))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        MockMultipartFile docx = new MockMultipartFile("file", "cv.docx", "application/octet-stream", docx("Data Analyst & SQL"));
        String second = mvc().perform(multipart("/api/resumes").file(docx).param("label", "Analyst CV").header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value(org.hamcrest.Matchers.containsString("wordprocessingml")))
                .andExpect(jsonPath("$.primary").value(false))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(get("/api/resumes/" + first).header("Authorization", auth))
                .andExpect(jsonPath("$.extractedText").value(containsString("Java Spring Boot")));
        mvc().perform(get("/api/resumes/" + second).header("Authorization", auth))
                .andExpect(jsonPath("$.extractedText").value(containsString("Data Analyst & SQL")));
        // the exact bytes come back (proves DB persistence round-trips, not just a 200)
        byte[] downloaded = mvc().perform(get("/api/resumes/" + first + "/file").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(pdf.getBytes(), downloaded);

        // A renamed .exe is rejected by content, not by extension
        mvc().perform(multipart("/api/resumes").file(new MockMultipartFile("file", "cv.pdf", "application/pdf", "MZ\u0090 not a pdf".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isUnsupportedMediaType());

        // Another user cannot see or touch it
        String other = register();
        mvc().perform(get("/api/resumes/" + first + "/file").header("Authorization", other)).andExpect(status().isNotFound());
        mvc().perform(delete("/api/resumes/" + first).header("Authorization", other)).andExpect(status().isNotFound());

        // Switch primary, then delete it: the remaining one becomes primary again
        mvc().perform(patch("/api/resumes/" + second).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primary\":true}"))
                .andExpect(jsonPath("$.primary").value(true));
        mvc().perform(get("/api/resumes").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.primary == true)]", hasSize(1)));
        mvc().perform(delete("/api/resumes/" + second).header("Authorization", auth)).andExpect(status().isNoContent());
        mvc().perform(get("/api/resumes").header("Authorization", auth))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].primary").value(true));
    }

    static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] docx(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document><w:body><w:p><w:r><w:t>" + text.replace("&", "&amp;") + "</w:t></w:r></w:p></w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}

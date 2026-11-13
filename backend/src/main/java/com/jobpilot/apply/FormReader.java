package com.jobpilot.apply;

import com.jobpilot.job.SafeHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the questions on a Greenhouse or Lever application form, without a browser.
 * Greenhouse publishes them in its board API; Lever embeds them in the apply page (standard fields as HTML,
 * custom questions as a JSON template per card).
 */
final class FormReader {
    private FormReader() {}

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Field keys starting with this are CSS selectors, for inputs without a name. */
    static final String BY_CSS = "css:";

    static List<FormField> greenhouse(String token, String jobId) {
        return parseGreenhouse(SafeHttp.get("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs/" + jobId + "?questions=true"));
    }

    static List<FormField> lever(String token, String postingId) {
        return parseLever(SafeHttp.get("https://jobs.lever.co/" + token + "/" + postingId + "/apply"));
    }

    /**
     * ponytail: skips Greenhouse's optional EEOC/demographic blocks; add them if a board makes them required.
     */
    static List<FormField> parseGreenhouse(String json) {
        JsonNode root = JSON.readTree(json);
        List<FormField> out = new ArrayList<>();
        for (String section : List.of("questions", "location_questions")) {
            for (JsonNode q : root.path(section)) {
                String label = q.path("label").asString("").strip();
                boolean required = q.path("required").asBoolean(false);
                JsonNode fields = q.path("fields");
                // "Resume/CV" offers a file or a pasted-text alternative: use the file. Cover letters: use the text box.
                JsonNode chosen = null;
                for (JsonNode f : fields) {
                    String t = f.path("type").asString("");
                    if (t.equals("input_hidden")) continue;
                    if (chosen == null) chosen = f;
                    if (t.equals("input_file") && !label.toLowerCase().contains("cover")) { chosen = f; break; }
                    if (t.equals("textarea") && label.toLowerCase().contains("cover")) { chosen = f; break; }
                }
                if (chosen == null) continue;
                String type = switch (chosen.path("type").asString("")) {
                    case "textarea" -> "TEXTAREA";
                    case "input_file" -> "FILE";
                    case "multi_value_single_select" -> "SELECT";
                    case "multi_value_multi_select" -> "MULTISELECT";
                    default -> "TEXT";
                };
                List<String> options = new ArrayList<>();
                for (JsonNode v : chosen.path("values")) options.add(v.path("label").asString(""));
                out.add(new FormField(chosen.path("name").asString(), label, type, required, options, null, null));
            }
        }
        return out;
    }

    static List<FormField> parseLever(String html) {
        Document doc = Jsoup.parse(html);
        Element form = doc.selectFirst("form#application-form, form[action*=apply], form");
        if (form == null) throw new SafeHttp.FetchException(422, "Could not find the application form on this Lever page");
        List<FormField> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Custom questions (cards[..]) and surveys (surveysResponses[..]) each carry a JSON template with types and options.
        List<String> templatePrefixes = new ArrayList<>();
        for (Element tpl : form.select("input[name$=[baseTemplate]]")) {
            String card = tpl.attr("name").replace("[baseTemplate]", "");
            templatePrefixes.add(card);
            JsonNode t;
            try { t = JSON.readTree(tpl.attr("value")); } catch (RuntimeException e) { continue; }
            int i = 0;
            for (JsonNode f : t.path("fields")) {
                // cards name inputs <card>[fieldN]; surveys use <survey>[responses][fieldN]
                String plain = card + "[field" + i + "]", nested = card + "[responses][field" + i + "]";
                i++;
                String key = form.selectFirst("[name=\"" + nested + "\"]") != null ? nested : plain;
                String type = switch (f.path("type").asString("")) {
                    case "textarea" -> "TEXTAREA";
                    case "multiple-choice", "dropdown" -> "SELECT";
                    case "multiple-select" -> "MULTISELECT";
                    case "file-upload" -> "FILE";
                    default -> "TEXT";
                };
                List<String> options = new ArrayList<>();
                for (JsonNode o : f.path("options")) options.add(o.path("text").asString(""));
                out.add(new FormField(key, f.path("text").asString("").strip(), type, f.path("required").asBoolean(false), options, null, null));
                seen.add(key);
            }
        }

        // Standard questions: plain HTML inputs.
        List<FormField> standard = new ArrayList<>();
        for (Element li : form.select("li.application-question")) {
            Element label = li.selectFirst(".application-label");
            Element input = li.selectFirst("input:not([type=hidden]), textarea, select");
            if (label == null || input == null || input.attr("name").isEmpty()) continue;
            String name = input.attr("name");
            if (templatePrefixes.stream().anyMatch(name::startsWith) || !seen.add(name)) continue; // read from its template already
            boolean radio = input.attr("type").equals("radio"), box = input.attr("type").equals("checkbox");
            String type = input.tagName().equals("textarea") ? "TEXTAREA" : input.tagName().equals("select") || radio ? "SELECT"
                    : input.attr("type").equals("file") ? "FILE" : box ? "MULTISELECT" : "TEXT";
            List<String> options = new ArrayList<>();
            if (input.tagName().equals("select")) for (Element o : input.select("option")) if (!o.attr("value").isEmpty()) options.add(o.text().strip());
            if (radio || box)
                for (Element choice : li.select("input[name=\"" + name + "\"]")) {
                    Element l = choice.parent();
                    options.add(l != null && !l.text().isBlank() ? l.text().strip() : choice.attr("value"));
                }
            String text = label.selectFirst(".text") != null ? label.selectFirst(".text").ownText() : label.ownText();
            boolean required = input.hasAttr("required") || label.selectFirst(".required") != null;
            standard.add(new FormField(input.attr("name"), text.replace("✱", "").strip(), type, required, options, null, null));
        }
        // resume first, like the page
        Element resume = form.selectFirst("input[type=file][name=resume]");
        if (resume != null && seen.add("resume")) standard.addFirst(new FormField("resume", "Resume/CV", "FILE", true, List.of(), null, null));
        // The demographic survey's country picker has no name: target it by CSS. It goes before the survey questions,
        // because the survey shown can depend on the country.
        Element country = form.selectFirst("select.candidate-location");
        if (country != null) {
            List<String> options = new ArrayList<>();
            for (Element o : country.select("option")) if (!o.attr("value").isEmpty()) options.add(o.text().strip());
            standard.add(new FormField(BY_CSS + "select.candidate-location", "What is your location? (demographic survey)", "SELECT", false, options, null, null));
        }
        standard.addAll(out);
        return standard;
    }
}

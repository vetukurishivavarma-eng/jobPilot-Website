package com.jobpilot.job;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every job source: fetch + parse. Parsers are static and pure so they are tested against real captured responses.
 * Sources were chosen for terms that allow this use: ATS public job-board APIs (Greenhouse, Lever),
 * Adzuna's official API (needs a key), and schema.org JobPosting data that sites publish for search engines.
 */
public final class JobSources {
    private JobSources() {}

    static final ObjectMapper JSON = JsonMapper.builder().build();
    public static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern GH_URL = Pattern.compile("^https?://(?:job-boards|boards)(?:\\.eu)?\\.greenhouse\\.io/([A-Za-z0-9_-]+)(?:/jobs/(\\d+))?/?(?:[?#].*)?$");
    private static final Pattern LEVER_URL = Pattern.compile("^https?://jobs\\.lever\\.co/([A-Za-z0-9_-]+)(?:/([0-9a-f-]{36}))?(?:/apply)?/?(?:[?#].*)?$");

    public enum Ats { GREENHOUSE, LEVER }

    public record BoardRef(Ats ats, String token, String jobId) {}

    /** Recognises Greenhouse/Lever board or job URLs; null for anything else. */
    public static BoardRef parseBoardUrl(String url) {
        Matcher m = GH_URL.matcher(url.trim());
        if (m.matches()) return new BoardRef(Ats.GREENHOUSE, m.group(1), m.group(2));
        m = LEVER_URL.matcher(url.trim());
        if (m.matches()) return new BoardRef(Ats.LEVER, m.group(1), m.group(2));
        return null;
    }

    // ---------------------------------------------------------------- Greenhouse

    public static String greenhouseBoardName(String token) {
        return str(JSON.readTree(SafeHttp.get("https://boards-api.greenhouse.io/v1/boards/" + token)).path("name"));
    }

    public static List<JobDraft> greenhouseBoard(String token, String company) {
        return parseGreenhouse(SafeHttp.get("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs?content=true"), token, company);
    }

    public static JobDraft greenhouseJob(String token, String id) {
        JsonNode j = JSON.readTree(SafeHttp.get("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs/" + id));
        return greenhouse(j, token, null);
    }

    static List<JobDraft> parseGreenhouse(String json, String token, String company) {
        List<JobDraft> out = new ArrayList<>();
        for (JsonNode j : JSON.readTree(json).path("jobs")) out.add(greenhouse(j, token, company));
        return out;
    }

    private static JobDraft greenhouse(JsonNode j, String token, String company) {
        String location = str(j.path("location").path("name"));
        String content = str(j.path("content"));
        return new JobDraft("greenhouse", j.path("id").asString(), "greenhouse:" + token,
                str(j.path("absolute_url")), str(j.path("absolute_url")),
                str(j.path("title")), firstNonNull(str(j.path("company_name")), company, token),
                location, workplaceFrom(location, null), null, null, null, null,
                content == null ? null : htmlToText(Parser.unescapeEntities(content, false)),
                instant(firstNonNull(str(j.path("first_published")), str(j.path("updated_at")))));
    }

    // ---------------------------------------------------------------- Lever

    public static void leverCheck(String token) {
        SafeHttp.get("https://api.lever.co/v0/postings/" + token + "?mode=json&limit=1");
    }

    public static List<JobDraft> leverBoard(String token, String company) {
        return parseLever(SafeHttp.get("https://api.lever.co/v0/postings/" + token + "?mode=json"), token, company);
    }

    public static JobDraft leverJob(String token, String id) {
        return lever(JSON.readTree(SafeHttp.get("https://api.lever.co/v0/postings/" + token + "/" + id + "?mode=json")), token, prettify(token));
    }

    static List<JobDraft> parseLever(String json, String token, String company) {
        List<JobDraft> out = new ArrayList<>();
        for (JsonNode j : JSON.readTree(json)) out.add(lever(j, token, company));
        return out;
    }

    private static JobDraft lever(JsonNode j, String token, String company) {
        JsonNode cat = j.path("categories");
        List<String> locs = new ArrayList<>();
        for (JsonNode l : cat.path("allLocations")) locs.add(l.asString());
        String location = locs.isEmpty() ? str(cat.path("location")) : String.join(" / ", locs);

        StringBuilder d = new StringBuilder(firstNonNull(str(j.path("descriptionPlain")), ""));
        for (JsonNode l : j.path("lists")) {
            String heading = str(l.path("text")), body = str(l.path("content"));
            if (heading != null) d.append("\n\n").append(heading);
            if (body != null) d.append('\n').append(htmlToText(body));
        }
        String extra = str(j.path("additionalPlain"));
        if (extra != null) d.append("\n\n").append(extra);

        Long min = null, max = null;
        String cur = null;
        JsonNode sal = j.path("salaryRange");
        if (sal.isObject() && "per-year-salary".equals(str(sal.path("interval")))) {
            min = num(sal.path("min"));
            max = num(sal.path("max"));
            cur = str(sal.path("currency"));
        }
        long created = j.path("createdAt").asLong(0);
        return new JobDraft("lever", str(j.path("id")), "lever:" + token, str(j.path("hostedUrl")), str(j.path("applyUrl")),
                str(j.path("text")), company, location, workplaceFrom(location, str(j.path("workplaceType"))),
                str(cat.path("commitment")), min, max, cur, blankToNull(d.toString().strip()),
                created > 0 ? Instant.ofEpochMilli(created) : null);
    }

    // ---------------------------------------------------------------- Adzuna (official API, key required)

    private static final Map<String, String> ADZUNA_CURRENCY = Map.of(
            "in", "INR", "gb", "GBP", "us", "USD", "au", "AUD", "ca", "CAD", "de", "EUR", "fr", "EUR", "nl", "EUR", "sg", "SGD", "za", "ZAR");

    public static List<JobDraft> adzunaSearch(String appId, String appKey, String country, String what, String where) {
        String url = "https://api.adzuna.com/v1/api/jobs/" + country + "/search/1?app_id=" + enc(appId) + "&app_key=" + enc(appKey)
                + "&results_per_page=50&max_days_old=30&what=" + enc(what) + (where == null ? "" : "&where=" + enc(where));
        return parseAdzuna(SafeHttp.get(url), country);
    }

    /**
     * Adzuna descriptions are ~500-char snippets, so parsed requirements for these jobs are partial.
     * Predicted (estimated) salaries are dropped: we only show salaries an employer stated.
     */
    static List<JobDraft> parseAdzuna(String json, String country) {
        List<JobDraft> out = new ArrayList<>();
        for (JsonNode j : JSON.readTree(json).path("results")) {
            boolean predicted = "1".equals(j.path("salary_is_predicted").asString());
            String location = str(j.path("location").path("display_name"));
            out.add(new JobDraft("adzuna", j.path("id").asString(), null, str(j.path("redirect_url")), str(j.path("redirect_url")),
                    htmlToText(firstNonNull(str(j.path("title")), "")), firstNonNull(str(j.path("company").path("display_name")), "Unknown company"),
                    location, workplaceFrom(location, null), str(j.path("contract_time")),
                    predicted ? null : num(j.path("salary_min")), predicted ? null : num(j.path("salary_max")),
                    predicted ? null : ADZUNA_CURRENCY.get(country), htmlToText(firstNonNull(str(j.path("description")), "")),
                    instant(str(j.path("created")))));
        }
        return out;
    }

    // ---------------------------------------------------------------- Any page with schema.org JobPosting

    /** Import from a pasted link: ATS links go through their API, anything else through the page's JSON-LD. */
    public static JobDraft fromUrl(String url) {
        BoardRef ref = parseBoardUrl(url);
        if (ref != null && ref.jobId() != null)
            return ref.ats() == Ats.GREENHOUSE ? greenhouseJob(ref.token(), ref.jobId()) : leverJob(ref.token(), ref.jobId());
        String unreadable = "This page doesn't publish job data we can read (many sites like LinkedIn block it). Paste the job description in instead.";
        String html;
        try {
            html = SafeHttp.getUserUrl(url);
        } catch (SafeHttp.FetchException e) {
            // the site refused us (403/404/999...), as opposed to a bad link (400) or our own limits (413)
            if (e.status > 400 && e.status != 413) throw new SafeHttp.FetchException(422, unreadable);
            throw e;
        }
        JobDraft d = parseJsonLd(html, url);
        if (d == null) throw new SafeHttp.FetchException(422, unreadable);
        return d;
    }

    static JobDraft parseJsonLd(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        for (Element s : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try { root = JSON.readTree(s.data()); } catch (RuntimeException e) { continue; }
            JsonNode p = findJobPosting(root);
            if (p == null) continue;

            String title = str(p.path("title"));
            if (title == null) continue;
            JsonNode org = p.path("hiringOrganization");
            String company = org.isObject() ? str(org.path("name")) : str(org);

            List<String> places = new ArrayList<>();
            for (JsonNode loc : p.path("jobLocation").isArray() ? p.path("jobLocation") : List.of(p.path("jobLocation"))) {
                JsonNode a = loc.path("address");
                if (a.isObject()) {
                    List<String> parts = new ArrayList<>();
                    for (String k : List.of("addressLocality", "addressRegion", "addressCountry")) {
                        JsonNode v = a.path(k);
                        String sv = v.isObject() ? str(v.path("name")) : str(v);
                        if (sv != null && !parts.contains(sv)) parts.add(sv);
                    }
                    if (!parts.isEmpty()) places.add(String.join(", ", parts));
                } else if (str(a) != null) places.add(str(a));
            }
            String location = places.isEmpty() ? null : String.join(" / ", places);
            boolean remote = "TELECOMMUTE".equalsIgnoreCase(firstNonNull(str(p.path("jobLocationType")), ""));

            JsonNode et = p.path("employmentType");
            String employment = et.isArray() ? (et.isEmpty() ? null : et.get(0).asString()) : str(et);

            Long min = null, max = null;
            String cur = null;
            JsonNode sal = p.path("baseSalary");
            JsonNode val = sal.path("value");
            if (sal.isObject() && "YEAR".equalsIgnoreCase(firstNonNull(str(val.path("unitText")), str(sal.path("unitText")), ""))) {
                min = firstNonNull(num(val.path("minValue")), num(val.path("value")));
                max = firstNonNull(num(val.path("maxValue")), num(val.path("value")));
                cur = str(sal.path("currency"));
            }
            String canonical = canonical(pageUrl);
            return new JobDraft("web", canonical, null, pageUrl, firstNonNull(str(p.path("url")), pageUrl),
                    Parser.unescapeEntities(title, false), firstNonNull(company, URI.create(canonical).getHost()),
                    location, remote ? "REMOTE" : workplaceFrom(location, null), employment, min, max, cur,
                    htmlToText(firstNonNull(str(p.path("description")), "")), instant(str(p.path("datePosted"))));
        }
        return null;
    }

    private static JsonNode findJobPosting(JsonNode n) {
        if (n == null) return null;
        if (n.isArray()) {
            for (JsonNode c : n) { JsonNode f = findJobPosting(c); if (f != null) return f; }
            return null;
        }
        if (!n.isObject()) return null;
        JsonNode type = n.path("@type");
        if ("JobPosting".equals(str(type))) return n;
        if (type.isArray()) for (JsonNode t : type) if ("JobPosting".equals(t.asString())) return n;
        return findJobPosting(n.path("@graph").isMissingNode() ? null : n.path("@graph"));
    }

    // ---------------------------------------------------------------- helpers

    /** HTML to readable plain text, keeping paragraph and list breaks. */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) return null;
        Document d = Jsoup.parse(html);
        d.outputSettings(new Document.OutputSettings().prettyPrint(false));
        d.select("br").after("\\n");
        d.select("li").before("\\n• ");
        d.select("p, div, h1, h2, h3, h4, h5, h6, tr, ul, ol").before("\\n\\n");
        String marked = Jsoup.clean(d.body().html(), "", Safelist.none(), new Document.OutputSettings().prettyPrint(false));
        String text = Parser.unescapeEntities(marked, false).replace("\\n", "\n").replace(' ', ' ');
        return blankToNull(text.replaceAll("[ \\t]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").strip());
    }

    static String workplaceFrom(String location, String declared) {
        String d = declared == null ? "" : declared.toLowerCase();
        if (d.contains("remote")) return "REMOTE";
        if (d.contains("hybrid")) return "HYBRID";
        if (d.contains("onsite") || d.contains("on-site")) return "ONSITE";
        String l = location == null ? "" : location.toLowerCase();
        if (l.contains("remote")) return "REMOTE";
        if (l.contains("hybrid")) return "HYBRID";
        return null;
    }

    static Instant instant(String s) {
        if (s == null) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (DateTimeException ignored) { }
        try { return Instant.parse(s); } catch (DateTimeException ignored) { }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (DateTimeException ignored) { }
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (DateTimeException ignored) { }
        return null;
    }

    static String canonical(String url) {
        URI u = URI.create(url.trim());
        return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath());
    }

    static String prettify(String token) {
        String s = token.replaceAll("[-_]+", " ").strip();
        return s.isEmpty() ? token : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String str(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || n.isObject() || n.isArray()) return null;
        return blankToNull(n.asString());
    }

    static Long num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asLong();
        try { return Math.round(Double.parseDouble(n.asString().replace(",", ""))); } catch (NumberFormatException e) { return null; }
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    @SafeVarargs
    static <T> T firstNonNull(T... xs) {
        for (T x : xs) if (x != null) return x;
        return null;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}

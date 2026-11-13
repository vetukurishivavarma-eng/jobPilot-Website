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

    public enum Ats { GREENHOUSE, LEVER, WORKDAY, SMARTRECRUITERS, ASHBY }

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

    // ---------------------------------------------------------------- Workday (the JSON API its own careers pages call)

    static final int WORKDAY_MAX_JOBS = 400, WORKDAY_PAGE = 20; // Workday rejects pages over 20
    private static final Pattern WORKDAY_TOKEN = Pattern.compile("([a-z0-9-]+)\\.(wd\\d+)/([A-Za-z0-9_-]+)");
    private static final Pattern INDIA = Pattern.compile("(?i)\\bindia\\b");

    /**
     * A Workday careers site's India openings. {@code token} is "tenant.wdN/site", from the site's URL
     * (https://tenant.wdN.myworkdayjobs.com/site). Lists through the site's location facet, then reads each job.
     */
    public static List<JobDraft> workdayBoard(String token, String company) {
        Matcher m = WORKDAY_TOKEN.matcher(token);
        if (!m.matches()) throw new IllegalArgumentException("Workday token must be tenant.wdN/site: " + token);
        String host = "https://" + m.group(1) + "." + m.group(2) + ".myworkdayjobs.com";
        String api = host + "/wday/cxs/" + m.group(1) + "/" + m.group(3);

        Map.Entry<String, List<String>> india = indiaFacet(JSON.readTree(SafeHttp.postJson(api + "/jobs", workdaySearch(null, 0, 1))));
        if (india == null) return List.of();
        List<String> paths = new ArrayList<>();
        for (int offset = 0, total = 1; offset < Math.min(total, WORKDAY_MAX_JOBS); offset += WORKDAY_PAGE) {
            JsonNode page = JSON.readTree(SafeHttp.postJson(api + "/jobs", workdaySearch(india, offset, WORKDAY_PAGE)));
            total = page.path("total").asInt(0);
            if (page.path("jobPostings").isEmpty()) break;
            for (JsonNode p : page.path("jobPostings")) if (str(p.path("externalPath")) != null) paths.add(str(p.path("externalPath")));
        }
        String site = host + "/" + m.group(3);
        return eachJob(paths, path -> parseWorkdayJob(SafeHttp.get(api + path), token, company, site + path));
    }

    private static String workdaySearch(Map.Entry<String, List<String>> facet, int offset, int limit) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("appliedFacets", facet == null ? Map.of() : Map.of(facet.getKey(), facet.getValue()));
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("searchText", "");
        return JSON.writeValueAsString(body);
    }

    /**
     * Each site names its location facets differently. A value that is exactly "India" (a country facet) wins;
     * otherwise every location value naming India ("Pune, India"), from the facet with the most of them. Null if none.
     */
    static Map.Entry<String, List<String>> indiaFacet(JsonNode search) {
        Map<String, List<String>> named = new java.util.LinkedHashMap<>();
        Map.Entry<String, List<String>> country = indiaFacet(search.path("facets"), named);
        if (country != null) return country;
        return named.entrySet().stream().max(java.util.Comparator.comparingInt(e -> e.getValue().size())).orElse(null);
    }

    private static Map.Entry<String, List<String>> indiaFacet(Iterable<JsonNode> facets, Map<String, List<String>> named) {
        for (JsonNode f : facets) {
            String param = str(f.path("facetParameter"));
            for (JsonNode v : f.path("values")) {
                if (v.has("facetParameter")) { // a group of facets
                    var found = indiaFacet(List.of(v), named);
                    if (found != null) return found;
                    continue;
                }
                String d = str(v.path("descriptor")), id = str(v.path("id"));
                if (d == null || id == null || param == null) continue;
                if (d.strip().equalsIgnoreCase("india")) return Map.entry(param, List.of(id));
                if (INDIA.matcher(d).find()) named.computeIfAbsent(param, k -> new ArrayList<>()).add(id);
            }
        }
        return null;
    }

    static JobDraft parseWorkdayJob(String json, String token, String company, String pageUrl) {
        JsonNode i = JSON.readTree(json).path("jobPostingInfo");
        List<String> locs = new ArrayList<>();
        if (str(i.path("location")) != null) locs.add(str(i.path("location")));
        for (JsonNode l : i.path("additionalLocations")) if (str(l) != null) locs.add(str(l));
        String location = locs.isEmpty() ? null : String.join(" / ", locs);
        String url = firstNonNull(str(i.path("externalUrl")), pageUrl);
        return new JobDraft("workday", firstNonNull(str(i.path("id")), str(i.path("jobReqId"))), "workday:" + token, url, url,
                str(i.path("title")), company, location, workplaceFrom(location, str(i.path("remoteType"))), str(i.path("timeType")),
                null, null, null, htmlToText(str(i.path("jobDescription"))), instant(str(i.path("startDate"))));
    }

    /**
     * Reads one detail page per job (these APIs' lists have no descriptions), 4 at a time.
     * ponytail: refetches every job each run; cache by id + posted date if boards grow.
     */
    static <T> List<JobDraft> eachJob(List<T> items, java.util.function.Function<T, JobDraft> read) {
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(4);
        try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<JobDraft>> fs = new ArrayList<>();
            for (T item : items) fs.add(ex.submit(() -> {
                gate.acquire();
                try { return read.apply(item); }
                catch (SafeHttp.FetchException e) { return null; } // one unreadable job doesn't sink the board
                finally { gate.release(); }
            }));
            List<JobDraft> out = new ArrayList<>();
            for (var f : fs) { JobDraft d = f.get(); if (d != null) out.add(d); }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SafeHttp.FetchException(502, "Interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }

    // ---------------------------------------------------------------- SmartRecruiters (public Posting API)

    /** A company's India postings (the API filters by country), then each posting for its description. */
    public static List<JobDraft> smartRecruitersBoard(String company, String name) {
        if (!TOKEN.matcher(company).matches()) throw new IllegalArgumentException("Bad SmartRecruiters company id: " + company);
        String api = "https://api.smartrecruiters.com/v1/companies/" + company + "/postings";
        List<String> ids = new ArrayList<>();
        for (int offset = 0, total = 1; offset < Math.min(total, WORKDAY_MAX_JOBS); offset += 100) {
            JsonNode page = JSON.readTree(SafeHttp.get(api + "?country=in&limit=100&offset=" + offset));
            total = page.path("totalFound").asInt(0);
            if (page.path("content").isEmpty()) break;
            for (JsonNode p : page.path("content")) if (str(p.path("id")) != null) ids.add(str(p.path("id")));
        }
        return eachJob(ids, id -> parseSmartRecruitersJob(SafeHttp.get(api + "/" + id), company, name));
    }

    static JobDraft parseSmartRecruitersJob(String json, String company, String name) {
        JsonNode j = JSON.readTree(json), loc = j.path("location");
        String location = str(loc.path("fullLocation"));
        if (location != null) location = location.replaceAll("(,\\s*)+", ", ");
        StringBuilder d = new StringBuilder();
        for (String k : List.of("jobDescription", "qualifications", "additionalInformation")) {
            JsonNode sec = j.path("jobAd").path("sections").path(k);
            String text = htmlToText(str(sec.path("text")));
            if (text == null) continue;
            if (str(sec.path("title")) != null) d.append(str(sec.path("title"))).append('\n');
            d.append(text).append("\n\n");
        }
        return new JobDraft("smartrecruiters", str(j.path("id")), "smartrecruiters:" + company, str(j.path("postingUrl")),
                firstNonNull(str(j.path("applyUrl")), str(j.path("postingUrl"))), str(j.path("name")),
                firstNonNull(name, str(j.path("company").path("name")), company), location,
                loc.path("remote").asBoolean(false) ? "REMOTE" : loc.path("hybrid").asBoolean(false) ? "HYBRID" : workplaceFrom(location, null),
                str(j.path("typeOfEmployment").path("label")), null, null, null, blankToNull(d.toString().strip()), instant(str(j.path("releasedDate"))));
    }

    // ---------------------------------------------------------------- Ashby (public job-board API)

    public static List<JobDraft> ashbyBoard(String org, String name) {
        if (!TOKEN.matcher(org).matches()) throw new IllegalArgumentException("Bad Ashby board: " + org);
        return parseAshby(SafeHttp.get("https://api.ashbyhq.com/posting-api/job-board/" + org), org, name);
    }

    static List<JobDraft> parseAshby(String json, String org, String name) {
        List<JobDraft> out = new ArrayList<>();
        for (JsonNode j : JSON.readTree(json).path("jobs")) {
            if (!j.path("isListed").asBoolean(true)) continue;
            List<String> locs = new ArrayList<>();
            if (str(j.path("location")) != null) locs.add(str(j.path("location")));
            for (JsonNode l : j.path("secondaryLocations")) if (str(l.path("location")) != null) locs.add(str(l.path("location")));
            String country = str(j.path("address").path("postalAddress").path("addressCountry"));
            String location = locs.isEmpty() ? country : String.join(" / ", locs);
            if (country != null && location != null && !location.toLowerCase().contains(country.toLowerCase())) location += ", " + country;
            out.add(new JobDraft("ashby", str(j.path("id")), "ashby:" + org, str(j.path("jobUrl")), firstNonNull(str(j.path("applyUrl")), str(j.path("jobUrl"))),
                    str(j.path("title")), firstNonNull(name, prettify(org)), location,
                    j.path("isRemote").asBoolean(false) ? "REMOTE" : workplaceFrom(location, str(j.path("workplaceType"))),
                    str(j.path("employmentType")), null, null, null,
                    firstNonNull(str(j.path("descriptionPlain")), htmlToText(str(j.path("descriptionHtml")))), instant(str(j.path("publishedAt")))));
        }
        return out;
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

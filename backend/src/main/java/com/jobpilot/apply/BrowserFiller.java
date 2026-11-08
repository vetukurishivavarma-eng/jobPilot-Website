package com.jobpilot.apply;

import com.jobpilot.config.AppProperties;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.SelectOption;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Opens the real application form in a visible Chrome window (the user's installed Chrome, with its own JobPilot
 * profile), fills every prepared answer, attaches the resume, and leaves the window to the user: they check it,
 * solve any CAPTCHA and press Submit. We watch for the site's confirmation page and report back.
 * We never touch CAPTCHAs and never press Submit ourselves (auto mode is a later, opt-in step).
 *
 * ponytail: one window at a time on one thread (Playwright isn't thread-safe); a queue per user if that's ever limiting.
 * Needs a desktop: works when JobPilot runs on the user's own PC, not on a headless server.
 */
@Component
public class BrowserFiller {
    private static final Logger log = LoggerFactory.getLogger(BrowserFiller.class);
    static final Duration WATCH_FOR = Duration.ofMinutes(30);
    /** URL or page text the sites show after a successful submit. */
    static final Pattern CONFIRMED_URL = Pattern.compile("/(confirmation|thanks|thank-you|thank_you)(\\b|/|\\?|$)");
    static final Pattern CONFIRMED_TEXT = Pattern.compile(
            "thank you for applying|application (has been )?(submitted|received)|thanks for applying|we('ve| have) received your application",
            Pattern.CASE_INSENSITIVE);

    /** @param autoSubmit press the site's Submit button when every field is filled (auto mode) */
    public record Session(String url, List<FormField> fields, Path resume, Path screenshot, boolean autoSubmit) {}
    /** How a session ended: SUBMITTED, CLOSED, TIMEOUT or FAILED. */
    public record Report(String outcome, List<String> notFilled, String error) {}

    /** A visible CAPTCHA challenge (not the invisible checkbox badge). */
    static final String CAPTCHA_CHALLENGE = "iframe[src*='hcaptcha.com'][src*='challenge'], iframe[title*='hCaptcha challenge' i], "
            + "iframe[src*='recaptcha/api2/bframe'], iframe[src*='recaptcha/enterprise/bframe'], iframe[src*='challenges.cloudflare.com']";
    static final Duration CONFIRM_WAIT = Duration.ofSeconds(45);

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "browser-filler"); t.setDaemon(true); return t; });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Path profileDir;
    private final boolean dryRun;

    BrowserFiller(AppProperties props) {
        this.profileDir = Path.of(props.storageDir()).toAbsolutePath().getParent().resolve("browser-profile");
        this.dryRun = props.applyDryRun();
    }

    boolean busy() { return busy.get(); }

    /**
     * Starts a session in the background; null if a window is already open.
     * {@code onNote} gets messages for the user while the window is open (fields we couldn't fill, a CAPTCHA to solve);
     * {@code onDone} and the returned future get the final report.
     */
    CompletableFuture<Report> start(Session s, Consumer<String> onNote, Consumer<Report> onDone) {
        if (!busy.compareAndSet(false, true)) return null;
        CompletableFuture<Report> result = new CompletableFuture<>();
        thread.submit(() -> {
            Report r;
            try { r = run(s, onNote); }
            catch (Exception e) {
                log.error("Browser session failed", e);
                r = new Report("FAILED", List.of(), "The browser session failed: " + e.getMessage());
            }
            try { onDone.accept(r); } finally { busy.set(false); result.complete(r); }
        });
        return result;
    }

    private Report run(Session s, Consumer<String> onNote) throws Exception {
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Files.createDirectories(profileDir);
            BrowserContext ctx = pw.chromium().launchPersistentContext(profileDir, new BrowserType.LaunchPersistentContextOptions()
                    .setChannel("chrome").setHeadless(false).setViewportSize(null).setArgs(List.of("--start-maximized")));
            try {
                Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().getFirst();
                page.setDefaultTimeout(6000);
                if (dryRun) page.route("**/*", r -> {
                    // test mode: pages load, but no POST/PUT leaves the browser, so nothing can reach the employer
                    if (!r.request().method().equals("GET") && !r.request().method().equals("HEAD")) {
                        log.info("dry run: blocked {} {}", r.request().method(), r.request().url());
                        r.abort();
                    } else r.resume();
                });
                page.navigate(s.url());
                settle(page);
                openFormIfCollapsed(page);

                List<String> notFilled = new ArrayList<>();
                for (FormField f : s.fields()) {
                    if (f.value() == null || f.value().isBlank()) continue;
                    boolean ok;
                    try { ok = fill(page, f, s.resume()); } catch (PlaywrightException e) { ok = false; }
                    if (!ok) notFilled.add(f.label());
                }
                // React forms can re-render while hydrating and wipe what was typed: check plain text fields and retype once
                for (FormField f : s.fields()) {
                    if (f.value() == null || f.value().isBlank() || notFilled.contains(f.label())) continue;
                    if (!f.type().equals("TEXT") && !f.type().equals("TEXTAREA")) continue;
                    try {
                        Locator el = field(page, f);
                        if (el == null || "combobox".equals(el.getAttribute("role")) || Objects.toString(el.getAttribute("class"), "").contains("location-input")) continue;
                        if (!f.value().equals(el.inputValue())) el.fill(f.value());
                        if (!f.value().equals(el.inputValue())) notFilled.add(f.label());
                    } catch (PlaywrightException e) { notFilled.add(f.label()); }
                }
                try { page.screenshot(new Page.ScreenshotOptions().setPath(s.screenshot()).setFullPage(true)); } catch (PlaywrightException ignored) { }
                if (!notFilled.isEmpty()) {
                    onNote.accept("Couldn't fill: " + String.join("; ", notFilled) + ". Please fill these in the browser window, then press Submit there.");
                } else if (s.autoSubmit()) {
                    String handover = submit(page);
                    if (handover == null) return new Report("SUBMITTED", List.of(), null);
                    onNote.accept(handover);
                }
                return watch(page);
            } finally {
                try { ctx.close(); } catch (PlaywrightException ignored) { }
            }
        }
    }

    /** Lets the page finish loading its scripts before we type (some pages never go fully idle, hence the cap). */
    private static void settle(Page page) {
        try { page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000)); }
        catch (PlaywrightException ignored) { }
        page.waitForTimeout(1000);
    }

    /** Greenhouse puts the form below the description behind an "Apply" button on some boards. */
    private static void openFormIfCollapsed(Page page) {
        try {
            if (page.locator("form input[type=file], form#application-form, #application_form").count() > 0) return;
            Locator apply = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply"));
            if (apply.count() > 0) apply.first().click();
        } catch (PlaywrightException ignored) { }
    }

    /**
     * Auto mode: presses Submit and waits for the confirmation page. Returns null when confirmed, otherwise a message
     * telling the user what to finish in the window. A CAPTCHA challenge is always handed to the user, never solved.
     */
    static String submit(Page page) {
        Locator button = page.locator("button#btn-submit, button[data-qa=btn-submit], form button[type=submit], form input[type=submit]");
        if (button.count() == 0)
            button = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("submit", Pattern.CASE_INSENSITIVE)));
        if (button.count() == 0) return "Everything is filled, but the Submit button wasn't found. Please press it in the browser window.";
        try {
            button.first().click();
        } catch (PlaywrightException e) {
            return "Everything is filled, but pressing Submit failed. Please press it in the browser window.";
        }
        Instant until = Instant.now().plus(CONFIRM_WAIT);
        while (Instant.now().isBefore(until)) {
            if (confirmed(page)) return null;
            if (captchaShown(page))
                return "The site is asking for a human check (CAPTCHA). Please complete it in the browser window; it then submits.";
            page.waitForTimeout(1500);
        }
        return "Submit was pressed, but the site hasn't confirmed yet. Please check the browser window for errors.";
    }

    static boolean captchaShown(Page page) {
        Locator frames = page.locator(CAPTCHA_CHALLENGE);
        try {
            for (int i = 0; i < frames.count(); i++) if (frames.nth(i).isVisible()) return true;
        } catch (PlaywrightException ignored) { }
        return false;
    }

    static boolean confirmed(Page page) {
        if (CONFIRMED_URL.matcher(page.url()).find()) return true;
        try {
            String text = page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout(2000));
            return CONFIRMED_TEXT.matcher(text.length() > 5000 ? text.substring(0, 5000) : text).find();
        } catch (PlaywrightException e) {
            return false; // page navigating
        }
    }

    /** Waits for the user to submit (confirmation page) or close the window. */
    private static Report watch(Page page) {
        Instant until = Instant.now().plus(WATCH_FOR);
        try {
            while (Instant.now().isBefore(until)) {
                if (page.isClosed()) return new Report("CLOSED", List.of(), null);
                if (confirmed(page)) return new Report("SUBMITTED", List.of(), null);
                page.waitForTimeout(2000);
            }
            return new Report("TIMEOUT", List.of(), null);
        } catch (PlaywrightException e) {
            return new Report("CLOSED", List.of(), null); // window or browser closed by the user
        }
    }

    // ------------------------------------------------------------------ filling one field

    static boolean fill(Page page, FormField f, Path resume) {
        String k = css(f.key());
        switch (f.type()) {
            case "FILE" -> {
                Locator input = page.locator("input[type=file][name=\"" + k + "\"], input[type=file][id=\"" + k + "\"]");
                if (input.count() == 0) input = page.locator("input[type=file]");
                if (input.count() == 0 || resume == null) return false;
                input.first().setInputFiles(resume);
                return true;
            }
            case "SELECT" -> { return choose(page, f, List.of(f.value())); }
            case "MULTISELECT" -> { return choose(page, f, Arrays.stream(f.value().split(",")).map(String::strip).toList()); }
            default -> {
                Locator el = field(page, f);
                if (el == null) return false;
                if ("combobox".equals(el.getAttribute("role"))) return combo(page, el, f.value());
                if (Objects.toString(el.getAttribute("class"), "").contains("location-input")) return leverLocation(page, el, f.value());
                el.fill(f.value());
                return true;
            }
        }
    }

    /** Radio buttons, checkboxes, a native select, or a searchable combobox (Greenhouse's React selects). */
    private static boolean choose(Page page, FormField f, List<String> values) {
        String k = css(f.key());
        Locator boxes = page.locator("input[type=radio][name=\"" + k + "\"], input[type=checkbox][name=\"" + k + "\"], input[type=checkbox][name=\"" + k + "[]\"]");
        if (boxes.count() > 0) {
            int done = 0;
            for (int i = 0; i < boxes.count(); i++) {
                Locator b = boxes.nth(i);
                String v = Objects.toString(b.getAttribute("value"), "");
                String text = Objects.toString(b.evaluate("e => (e.closest('label') || e.parentElement).innerText"), "").strip();
                if (values.stream().anyMatch(x -> x.equalsIgnoreCase(v) || x.equalsIgnoreCase(text))) {
                    b.check(new Locator.CheckOptions().setForce(true));
                    done++;
                }
            }
            return done == values.size();
        }
        Locator el = field(page, f);
        if (el == null) return false;
        if (Objects.toString(el.evaluate("e => e.tagName"), "").equals("SELECT")) {
            el.selectOption(new SelectOption().setLabel(values.getFirst()));
            page.waitForTimeout(800); // some selects (survey country) re-render the questions after them
            return true;
        }
        for (String v : values) if (!combo(page, el, v)) return false;
        return true;
    }

    /** Type into a searchable dropdown and take the matching suggestion. */
    private static boolean combo(Page page, Locator el, String value) {
        el.click();
        el.fill(value);
        page.waitForTimeout(1200); // suggestions (e.g. location autocomplete) load asynchronously
        page.keyboard().press("Enter");
        return true;
    }

    /** Lever's location box only keeps a value picked from its suggestions, which load on real keystrokes. */
    private static boolean leverLocation(Page page, Locator el, String value) {
        el.fill("");
        el.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(60));
        Locator first = page.locator(".dropdown-results > *").first();
        try {
            first.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            first.click();
            return true;
        } catch (PlaywrightException e) {
            return false; // no suggestion matched: reported back so the user picks one in the window
        }
    }

    /** Finds a field by its form name or id, falling back to its visible label. */
    private static Locator field(Page page, FormField f) {
        if (f.key().startsWith(FormReader.BY_CSS)) {
            Locator el = page.locator(f.key().substring(FormReader.BY_CSS.length()));
            return el.count() > 0 ? el.first() : null;
        }
        String k = css(f.key());
        Locator el = page.locator("[name=\"" + k + "\"]:not([type=hidden]), [id=\"" + k + "\"]:not([type=hidden])");
        if (el.count() > 0) return el.first();
        el = page.getByLabel(f.label().length() > 120 ? f.label().substring(0, 120) : f.label());
        return el.count() > 0 ? el.first() : null;
    }

    static String css(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    @PreDestroy
    void shutdown() { thread.shutdownNow(); }
}

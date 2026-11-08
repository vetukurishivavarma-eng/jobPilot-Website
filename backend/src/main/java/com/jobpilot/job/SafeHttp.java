package com.jobpilot.job;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Small HTTP GET client with a body-size cap and timeouts.
 * {@link #getUserUrl} additionally blocks SSRF: only http(s), ports 80/443, public IPs, redirects re-checked hop by hop.
 */
public class SafeHttp {
    public static class FetchException extends RuntimeException {
        public final int status;
        public FetchException(int status, String msg) { super(msg); this.status = status; }
    }

    static final int MAX_BYTES = 3_000_000;          // pages users link to
    static final int MAX_API_BYTES = 30_000_000;     // whole ATS boards with descriptions (big boards run 10+ MB)
    static final String UA = "JobPilotBot/0.1 (job discovery; respects robots and ATS public APIs)";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** GET a fixed, trusted API URL (follows up to 3 redirects). */
    public static String get(String url) {
        return fetch(URI.create(url), false, MAX_API_BYTES);
    }

    /** GET a URL a user typed. */
    public static String getUserUrl(String url) {
        URI uri;
        try { uri = new URI(url.trim()); } catch (URISyntaxException e) { throw new FetchException(400, "That is not a valid URL"); }
        return fetch(uri, true, MAX_BYTES);
    }

    private static String fetch(URI uri, boolean guard, int maxBytes) {
        for (int hop = 0; hop < 4; hop++) {
            if (guard) checkPublic(uri);
            HttpResponse<InputStream> res;
            try {
                res = CLIENT.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(guard ? 15 : 60))
                        .header("User-Agent", UA).header("Accept", "application/json, text/html;q=0.9, */*;q=0.5").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new FetchException(502, "Could not reach " + uri.getHost());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(502, "Interrupted");
            }
            int s = res.statusCode();
            if (s >= 300 && s < 400) {
                close(res);
                String loc = res.headers().firstValue("Location").orElseThrow(() -> new FetchException(502, "Redirect without Location"));
                uri = uri.resolve(loc);
                continue;
            }
            if (s >= 400) {
                close(res);
                throw new FetchException(s, uri.getHost() + " answered HTTP " + s);
            }
            try (InputStream in = res.body()) {
                byte[] b = in.readNBytes(maxBytes + 1);
                if (b.length > maxBytes) throw new FetchException(413, "Page is too large");
                return new String(b, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FetchException(502, "Read failed from " + uri.getHost());
            }
        }
        throw new FetchException(508, "Too many redirects");
    }

    /**
     * ponytail: checks the resolved IPs before connecting, but HttpClient resolves again (DNS-rebinding window).
     * Close it with an egress proxy / network policy that denies private ranges when deploying.
     */
    static void checkPublic(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) throw new FetchException(400, "Only http(s) links are supported");
        if (uri.getHost() == null) throw new FetchException(400, "That is not a valid URL");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new FetchException(400, "Only standard web ports are allowed");
        if (uri.getUserInfo() != null) throw new FetchException(400, "Links with credentials are not allowed");
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(uri.getHost()); }
        catch (UnknownHostException e) { throw new FetchException(400, "Unknown host " + uri.getHost()); }
        for (InetAddress a : addrs) if (!isPublic(a)) throw new FetchException(400, "That address is not allowed");
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int x = b[0] & 0xff, y = b[1] & 0xff;
            return !(x == 0 || (x == 100 && y >= 64 && y <= 127) || (x == 192 && y == 0 && (b[2] & 0xff) == 0) || x >= 224
                    || (x == 198 && (y == 18 || y == 19)));
        }
        if ((b[0] & 0xfe) == 0xfc) return false;                  // IPv6 unique-local fc00::/7
        if (a instanceof Inet6Address && isMappedV4(b)) {         // ::ffff:a.b.c.d
            try { return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]})); }
            catch (UnknownHostException e) { return false; }
        }
        return true;
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static void close(HttpResponse<InputStream> res) {
        try { res.body().close(); } catch (IOException ignored) { }
    }
}

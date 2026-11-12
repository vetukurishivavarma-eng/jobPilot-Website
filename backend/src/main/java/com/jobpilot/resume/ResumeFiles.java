package com.jobpilot.resume;

import com.jobpilot.shared.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resume bytes in the DB (survives redeploys; Render's disk is ephemeral) + type sniffing + text extraction.
 */
@Component
public class ResumeFiles {
    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeBlobRepository blobs;

    public ResumeFiles(ResumeBlobRepository blobs) {
        this.blobs = blobs;
    }

    /** Decide the type from the bytes, not the client's Content-Type header. */
    public static String sniff(byte[] b) {
        if (b.length > 5 && Arrays.equals(Arrays.copyOf(b, 5), "%PDF-".getBytes(StandardCharsets.US_ASCII))) return PDF;
        if (b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4 && docxXml(b) != null) return DOCX;
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE", "Upload a PDF or DOCX resume");
    }

    /** Plain text of the resume, or "" when there is none (e.g. a scanned PDF). */
    public static String extractText(byte[] b, String type) {
        try {
            String text = PDF.equals(type) ? pdfText(b) : docxText(b);
            return text.replace("\u0000", "").replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNREADABLE_FILE", "Could not read this file - is it corrupted or password-protected?");
        }
    }

    private static String pdfText(byte[] b) throws IOException {
        try (PDDocument doc = Loader.loadPDF(b)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String docxText(byte[] b) throws IOException {
        String xml = docxXml(b);
        if (xml == null) throw new IOException("no word/document.xml");
        return xml.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String docxXml(byte[] b) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml"))
                    return new String(zip.readNBytes(20_000_000), StandardCharsets.UTF_8); // cap: zip-bomb guard
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    public void write(String key, byte[] bytes) {
        blobs.save(new ResumeBlob(key, bytes));
    }

    public byte[] read(String key) {
        return blobs.findById(key).map(ResumeBlob::content).orElseThrow(() -> ApiException.notFound("Resume file"));
    }

    public void delete(String key) {
        // deleteById throws if the row is absent (rollback case, or a legacy resume with no blob) — ignore that
        try { blobs.deleteById(key); } catch (org.springframework.dao.EmptyResultDataAccessException ignored) { /* already gone */ }
    }
}

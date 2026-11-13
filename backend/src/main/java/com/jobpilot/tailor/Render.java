package com.jobpilot.tailor;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Turns a resume or letter into PDF, DOCX or plain text. Single column, ATS-friendly (real text, no tables or images). */
final class Render {
    private Render() {}

    enum Kind { NAME, CONTACT, HEADLINE, HEADING, SUBHEAD, META, TEXT, BULLET, GAP }

    record Line(Kind kind, String text) {}

    static List<Line> resume(Tailor.TailoredResume r) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(Kind.NAME, r.name()));
        if (!r.contact().isBlank()) out.add(new Line(Kind.CONTACT, r.contact()));
        if (r.headline() != null && !r.headline().isBlank()) out.add(new Line(Kind.HEADLINE, r.headline()));
        String summary = r.summary().chosen();
        if (summary != null && !summary.isBlank()) { out.add(new Line(Kind.HEADING, "SUMMARY")); out.add(new Line(Kind.TEXT, summary)); }
        String skills = r.skills().chosen();
        if (skills != null && !skills.isBlank()) { out.add(new Line(Kind.HEADING, "SKILLS")); out.add(new Line(Kind.TEXT, skills)); }
        if (!r.experiences().isEmpty()) {
            out.add(new Line(Kind.HEADING, "EXPERIENCE"));
            for (Tailor.ExpBlock e : r.experiences()) {
                out.add(new Line(Kind.SUBHEAD, e.heading()));
                out.add(new Line(Kind.META, e.meta()));
                for (String b : Tailor.bullets(e.bullets().chosen())) out.add(new Line(Kind.BULLET, b));
                out.add(new Line(Kind.GAP, ""));
            }
        }
        if (!r.education().isEmpty()) {
            out.add(new Line(Kind.HEADING, "EDUCATION"));
            r.education().forEach(e -> out.add(new Line(Kind.TEXT, e)));
        }
        return out;
    }

    static List<Line> letter(String text) {
        List<Line> out = new ArrayList<>();
        for (String p : text.split("\\R")) out.add(p.isBlank() ? new Line(Kind.GAP, "") : new Line(Kind.TEXT, p));
        return out;
    }

    // ------------------------------------------------------------------ text

    static byte[] txt(List<Line> lines) {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) {
            switch (l.kind()) {
                case HEADING -> b.append('\n').append(l.text()).append('\n');
                case BULLET -> b.append("- ").append(l.text()).append('\n');
                case GAP -> b.append('\n');
                default -> b.append(l.text()).append('\n');
            }
        }
        return b.toString().replaceAll("\n{3,}", "\n\n").strip().concat("\n").getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ DOCX (hand-written WordprocessingML, no extra library)

    static byte[] docx(List<Line> lines) {
        StringBuilder body = new StringBuilder();
        for (Line l : lines) {
            String t = xml(l.text());
            body.append(switch (l.kind()) {
                case NAME -> para(run(t, true, 36), "center", 0, 60);
                case CONTACT -> para(run(t, false, 19), "center", 0, 40);
                case HEADLINE -> para(run(t, false, 22), "center", 0, 120);
                case HEADING -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr>"
                        + "<w:spacing w:before=\"200\" w:after=\"80\"/></w:pPr>" + run(t, true, 22) + "</w:p>";
                case SUBHEAD -> para(run(t, true, 21), null, 0, 0);
                case META -> para("<w:r><w:rPr><w:i/><w:color w:val=\"555555\"/><w:sz w:val=\"19\"/></w:rPr><w:t xml:space=\"preserve\">" + t + "</w:t></w:r>", null, 0, 40);
                case BULLET -> "<w:p><w:pPr><w:ind w:left=\"360\" w:hanging=\"220\"/><w:spacing w:after=\"30\"/></w:pPr>" + run("•\t" + t, false, 21) + "</w:p>";
                case GAP -> "<w:p/>";
                case TEXT -> para(run(t, false, 21), null, 0, 60);
            });
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1000\" w:right=\"1000\" w:bottom=\"1000\" w:left=\"1000\"/></w:sectPr>"
                + "</w:body></w:document>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/document.xml", document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String para(String runs, String align, int before, int after) {
        return "<w:p><w:pPr>" + (align == null ? "" : "<w:jc w:val=\"" + align + "\"/>")
                + "<w:spacing w:before=\"" + before + "\" w:after=\"" + after + "\"/></w:pPr>" + runs + "</w:p>";
    }

    private static String run(String escaped, boolean bold, int halfPoints) {
        return "<w:r><w:rPr>" + (bold ? "<w:b/>" : "") + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"" + halfPoints + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + escaped.replace("\t", "</w:t><w:tab/><w:t xml:space=\"preserve\">") + "</w:t></w:r>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (c >= 0x20 || c == '\t') b.append(c); } // control chars are invalid XML
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ PDF (PDFBox, standard fonts)

    private static final float MARGIN = 50, WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    static byte[] pdf(List<Line> lines) {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Pdf w = new Pdf(doc);
            for (Line l : lines) {
                switch (l.kind()) {
                    case NAME -> w.centered(l.text(), bold, 20, 6);
                    case CONTACT -> w.centered(l.text(), regular, 9.5f, 3);
                    case HEADLINE -> w.centered(l.text(), regular, 11, 8);
                    case HEADING -> { w.space(8); w.wrapped(l.text(), bold, 11, 0, 2); w.rule(); w.space(4); }
                    case SUBHEAD -> w.wrapped(l.text(), bold, 10.5f, 0, 1);
                    case META -> w.wrapped(l.text(), italic, 9.5f, 0, 3);
                    case BULLET -> w.bullet(l.text(), regular, 10);
                    case GAP -> w.space(5);
                    case TEXT -> w.wrapped(l.text(), regular, 10, 0, 4);
                }
            }
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Minimal top-to-bottom writer with word wrap and page breaks. */
    private static final class Pdf {
        final PDDocument doc;
        PDPageContentStream cs;
        float y;

        Pdf(PDDocument doc) throws IOException { this.doc = doc; page(); }

        void page() throws IOException {
            if (cs != null) cs.close();
            PDPage p = new PDPage(PDRectangle.A4);
            doc.addPage(p);
            cs = new PDPageContentStream(doc, p);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void need(float h) throws IOException { if (y - h < MARGIN) page(); }

        void space(float h) { y -= h; }

        void text(String s, PDType1Font f, float size, float x) throws IOException {
            cs.beginText();
            cs.setFont(f, size);
            cs.newLineAtOffset(x, y);
            cs.showText(s);
            cs.endText();
        }

        void centered(String s, PDType1Font f, float size, float after) throws IOException {
            s = safe(s, f);
            need(size * 1.3f);
            y -= size;
            text(s, f, size, MARGIN + (WIDTH - width(s, f, size)) / 2);
            y -= after;
        }

        void wrapped(String s, PDType1Font f, float size, float indent, float after) throws IOException {
            for (String line : wrap(safe(s, f), f, size, WIDTH - indent)) {
                need(size * 1.35f);
                y -= size * 1.35f;
                text(line, f, size, MARGIN + indent);
            }
            y -= after;
        }

        void bullet(String s, PDType1Font f, float size) throws IOException {
            List<String> lines = wrap(safe(s, f), f, size, WIDTH - 14);
            for (int i = 0; i < lines.size(); i++) {
                need(size * 1.35f);
                y -= size * 1.35f;
                if (i == 0) text("•", f, size, MARGIN + 3);
                text(lines.get(i), f, size, MARGIN + 14);
            }
            y -= 1.5f;
        }

        void rule() throws IOException {
            y -= 2;
            cs.setLineWidth(0.5f);
            cs.setStrokingColor(0.6f, 0.6f, 0.6f);
            cs.moveTo(MARGIN, y);
            cs.lineTo(MARGIN + WIDTH, y);
            cs.stroke();
        }

        void close() throws IOException { cs.close(); }
    }

    static List<String> wrap(String s, PDType1Font f, float size, float max) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (width(trial, f, size) <= max || line.isEmpty()) { line.setLength(0); line.append(trial); }
            else { out.add(line.toString()); line.setLength(0); line.append(word); }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private static float width(String s, PDType1Font f, float size) throws IOException {
        return f.getStringWidth(s) / 1000 * size;
    }

    /** The standard PDF fonts only cover Latin-1-ish text: map common symbols, drop what can't be drawn. */
    static String safe(String s, PDType1Font f) {
        String t = s.replace("₹", "Rs ").replace("→", "->").replace("✓", "-").replace("\t", " ");
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            try { f.encode(ch); b.append(ch); } catch (IOException | IllegalArgumentException e) { b.append('?'); }
            i += Character.charCount(cp);
        }
        return b.toString();
    }
}

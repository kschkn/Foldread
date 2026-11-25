package ru.foldread;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Converts local book files into chapter titles and plain-text paragraphs. */
final class BookImporter {
    static final int MAX_FILE = 64 * 1024 * 1024;
    private static final int MAX_XML = 20 * 1024 * 1024;
    private final JSONArray chapters = new JSONArray();
    private final BookNotes notes = new BookNotes();
    private JSONArray stanzas = new JSONArray();
    private long expanded;
    private String title;
    private String author = "";
    private BookArtwork artwork;

    static JSONObject read(File file, String filename, String id, File directory) throws Exception {
        BookImporter reader = new BookImporter();
        reader.artwork = new BookArtwork(directory,id);
        reader.title = filename.replaceFirst("(?i)\\.(fb2|epub)$", "");
        boolean zipped;
        try (InputStream in = new FileInputStream(file)) {
            zipped = in.read() == 'P' && in.read() == 'K';
        }
        if (zipped) {
            try (ZipFile zip = new ZipFile(file)) { reader.epub(zip); }
        } else {
            if (file.length() > MAX_XML) throw new IOException("FB2 больше 20 МБ.");
            try (InputStream in = new FileInputStream(file)) {
                reader.fb2(Jsoup.parse(in, null, "", Parser.xmlParser()));
            }
        }
        if (reader.chapters.length() == 0) throw new IOException("В книге нет доступного текста.");
        return new JSONObject().put("id", id).put("title", reader.title)
                .put("author", reader.author).put("format", zipped ? "EPUB" : "FB2")
                .put("chapters", reader.chapters).put("notes", reader.notes.texts).put("schema", 4)
                .put("cover",reader.artwork.cover).put("accent",reader.artwork.accent)
                .put("series",reader.artwork.series).put("volume",reader.artwork.volume).put("images",reader.artwork.images);
    }

    private void addChapter(String name, List<String> paragraphs) throws Exception {
        if (paragraphs.isEmpty()) return;
        String fallback = "Раздел " + (chapters.length() + 1);
        JSONObject chapter=notes.chapter(name.isEmpty() ? fallback : name, paragraphs);
        if(stanzas.length()>0)chapter.put("stanzas",stanzas);
        chapters.put(chapter);stanzas=new JSONArray();
        paragraphs.clear();
    }

    private static String text(Element element, String selector, String fallback) {
        Element found = element.selectFirst(selector);
        return found == null || found.text().isEmpty() ? fallback : found.text();
    }

    private void fb2(Document doc) throws Exception {
        Element root = doc.selectFirst("FictionBook");
        if (root == null) throw new IOException("Выберите файл FB2 или EPUB.");
        Element info = root.selectFirst("description > title-info");
        if (info != null) {
            title = text(info, "book-title", title);
            List<String> names = new ArrayList<>();
            for (Element person : info.select("author")) {
                List<String> parts = new ArrayList<>();
                for (Element part : person.select("first-name, middle-name, last-name")) parts.add(part.text());
                names.add(parts.isEmpty() ? text(person, "nickname", "") : String.join(" ", parts));
            }
            author = String.join(", ", names);
        }
        artwork.fb2(doc,info);
        notes.fb2(doc);
        for (Element body : root.children()) {
            if (body.tagName().equals("body")) section(body, title);
        }
    }

    private void section(Element section, String fallback) throws Exception {
        String heading = fallback;
        List<String> paragraphs = new ArrayList<>();
        for (Element child : section.children()) {
            switch (child.tagName()) {
                case "title": heading = child.text(); break;
                case "section":
                    addChapter(heading, paragraphs);
                    section(child, heading);
                    break;
                default:
                    collect(child, paragraphs);
            }
        }
        addChapter(heading, paragraphs);
    }

    private Document entry(ZipFile zip, String name, Parser parser) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("В архиве отсутствует " + name);
        if (entry.getSize() > MAX_XML) throw new IOException("Слишком большой раздел EPUB.");
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                expanded += n;
                if (out.size() + n > MAX_XML || expanded > MAX_FILE)
                    throw new IOException("Распакованный текст EPUB слишком велик.");
                out.write(buf, 0, n);
            }
            return Jsoup.parse(new ByteArrayInputStream(out.toByteArray()), null, "", parser);
        }
    }

    private void epub(ZipFile zip) throws Exception {
        Document container = entry(zip, "META-INF/container.xml", Parser.xmlParser());
        Element root = container.selectFirst("rootfile[full-path]");
        if (root == null) throw new IOException("Не найдено описание EPUB.");
        String opf = root.attr("full-path");
        Document pkg = entry(zip, opf, Parser.xmlParser());
        title = text(pkg, "metadata > dc|title, metadata > title", title);
        author = String.join(", ", pkg.select("metadata > dc|creator, metadata > creator").eachText());
        Map<String, Element> items = new HashMap<>();
        for (Element item : pkg.select("manifest > item")) items.put(item.id(), item);
        URI base = new URI(null, null, "/" + opf, null);
        artwork.epub(pkg,zip,base);
        Set<String> textFiles = new HashSet<>();
        for (Element item : items.values()) {
            if (!item.attr("media-type").contains("html")) continue;
            try {
                URI uri = base.resolve(item.attr("href").replace(" ", "%20"));
                if (!uri.isAbsolute() && uri.getAuthority() == null)
                    textFiles.add(uri.getPath().substring(1));
            } catch (IllegalArgumentException ignored) { }
        }
        for (Element ref : pkg.select("spine > itemref")) {
            Element item = items.get(ref.attr("idref"));
            if (item == null || "no".equals(ref.attr("linear"))) continue;
            if (!item.attr("media-type").contains("html")) continue;
            URI uri = base.resolve(item.attr("href").replace(" ", "%20"));
            if (uri.isAbsolute() || uri.getAuthority() != null) throw new IOException("Внешний раздел EPUB.");
            String name = uri.getPath().substring(1);
            Document doc = entry(zip, name, Parser.htmlParser());
            doc.select("script, style, nav, [hidden], svg, noscript").remove();
            Element head = doc.body().selectFirst("h1,h2,h3");
            String heading = head == null ? doc.title() : head.text();
            if (head != null) head.remove();
            notes.epubLinks(doc, name);
            List<String> paragraphs = new ArrayList<>();
            collect(doc.body(), paragraphs);
            addChapter(heading, paragraphs);
        }
        // Note files may also occur in the spine. Bound each pass independently.
        expanded = 0;
        for (Map.Entry<String, Set<String>> target : notes.epubTargets.entrySet()) {
            String path = target.getKey();
            if (textFiles.contains(path) && zip.getEntry(path) != null)
                notes.epubDefinitions(entry(zip, path, Parser.htmlParser()), path, target.getValue());
        }
    }

    private void collect(Element root, List<String> paragraphs) {
        StringBuilder line = new StringBuilder();
        collectNodes(root, paragraphs, line);
        flush(paragraphs, line);
    }

    private void collectNodes(Element el, List<String> paragraphs, StringBuilder line) {
        boolean stanza=el.tagName().equals("stanza")||el.hasClass("stanza");
        if(stanza)flush(paragraphs,line);
        int start=paragraphs.size();
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode) line.append(((TextNode) node).getWholeText());
            else if (node instanceof Element) {
                Element child = (Element) node;
                boolean block = child.isBlock() || child.tagName().equals("v") || child.tagName().equals("br");
                if (block) flush(paragraphs, line);
                collectNodes(child, paragraphs, line);
                if (block) flush(paragraphs, line);
            }
        }
        if(stanza){
            flush(paragraphs,line);
            if(paragraphs.size()>start)stanzas.put(new JSONArray().put(start).put(paragraphs.size()));
        }
    }

    private static void flush(List<String> paragraphs, StringBuilder line) {
        String text = line.toString().replaceAll("\\s+", " ").trim();
        if (!text.isEmpty()) paragraphs.add(text);
        line.setLength(0);
    }
}

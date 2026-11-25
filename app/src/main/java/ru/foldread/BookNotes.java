package ru.foldread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps note references at UTF-16 character offsets after the importer normalizes whitespace. */
final class BookNotes {
    final JSONObject texts = new JSONObject();
    final Map<String, Set<String>> epubTargets = new HashMap<>();
    private final Set<String> presumedNotes = new HashSet<>();
    private final String token = "\uE000" + UUID.randomUUID() + ":";
    private final Pattern marker = Pattern.compile(Pattern.quote(token) + "(\\d+)\uE001");
    private final List<String> targets = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private void mark(Element link, String key) {
        String label = link.text();
        int index = targets.size();
        targets.add(key);
        labels.add(label.isEmpty() ? "⁎" : label);
        link.text(token + index + "\uE001");
    }

    void fb2(Document doc) throws JSONException {
        Map<String, Element> byId = new HashMap<>();
        for (Element section : doc.select("section[id]")) byId.put(section.id(), section);
        for (Element link : doc.select("a")) {
            if (link.closest("title") != null) continue;
            String href = link.attr("href");
            for (Attribute attr : link.attributes()) {
                if (attr.getKey().endsWith(":href")) href = attr.getValue();
            }
            if (!href.startsWith("#")) continue;
            String key = href.substring(1);
            Element target = byId.get(key);
            if (target == null) continue;
            Element body = target.closest("body");
            String name = body == null ? "" : body.attr("name");
            if (!"note".equalsIgnoreCase(link.attr("type")) && !name.equals("notes") && !name.equals("comments")) continue;
            if (!texts.has(key)) {
                Element copy = target.clone();
                copy.select("title").remove();
                String value = String.join("\n\n", copy.select("p, v").eachText());
                texts.put(key, value.isEmpty() ? copy.text() : value);
            }
            mark(link, key);
        }
    }

    void epubLinks(Document doc, String path) throws Exception {
        URI base = new URI(null, null, "/" + path, null);
        for (Element link : doc.select("a[href*=#]")) {
            if (link.attr("role").equals("doc-backlink") || link.attr("epub:type").contains("backlink")) continue;
            String href = link.attr("href");
            String label = link.text();
            boolean reference = link.attr("epub:type").contains("noteref") || link.attr("role").equals("doc-noteref")
                    || label.matches("[\\s\\[\\]()①-⑳*†‡⁰¹²³⁴-⁹0-9]{1,12}") || link.closest("sup") != null;
            if (label.isEmpty() && !reference) continue;
            URI uri;
            try { uri = base.resolve(href.replace(" ", "%20")); }
            catch (IllegalArgumentException ignored) { continue; }
            String fragment = uri.getFragment();
            if (uri.isAbsolute() || uri.getAuthority() != null || fragment == null || fragment.isEmpty()) continue;
            String target = uri.getPath().substring(1);
            epubTargets.computeIfAbsent(target, unused -> new HashSet<>()).add(fragment);
            String key = target + "#" + fragment;
            if (reference) presumedNotes.add(key);
            mark(link, key);
        }
    }

    void epubDefinitions(Document doc, String path, Set<String> fragments) throws JSONException {
        doc.select("script, style, [role=doc-backlink]").remove();
        for (Element element : doc.select("[id], a[name]")) {
            String id = element.hasAttr("id") ? element.id() : element.attr("name");
            if (!fragments.contains(id)) continue;
            String key = path + "#" + id;
            if (!presumedNotes.contains(key) && !inNote(element)) continue;
            Element target = element;
            if (target.tagName().matches("a|sup|span")) {
                Element block = target.closest("li,p,aside,section,div");
                if (block != null) target = block;
            }
            String value = target.text();
            if (!value.isEmpty()) texts.put(key, value);
        }
    }

    private boolean inNote(Element element) {
        for (Element current = element; current != null; current = current.parent()) {
            String type = current.attr("epub:type") + " " + current.attr("role");
            if (type.contains("footnote") || type.contains("endnote")) return true;
        }
        return false;
    }

    JSONObject chapter(String title, List<String> paragraphs) throws JSONException {
        JSONArray clean = new JSONArray();
        JSONObject references = new JSONObject();
        for (String paragraph : paragraphs) {
            if (!paragraph.contains(token)) { clean.put(paragraph); continue; }
            Matcher match = marker.matcher(paragraph);
            StringBuilder result = new StringBuilder();
            JSONArray links = new JSONArray();
            int previous = 0;
            while (match.find()) {
                result.append(paragraph, previous, match.start());
                int index = Integer.parseInt(match.group(1));
                int start = result.length();
                result.append(labels.get(index));
                links.put(new JSONObject().put("start", start).put("end", result.length()).put("key", targets.get(index)));
                previous = match.end();
            }
            result.append(paragraph, previous, paragraph.length());
            references.put(String.valueOf(clean.length()), links);
            clean.put(result.toString());
        }
        JSONObject chapter = new JSONObject().put("title", title).put("paragraphs", clean);
        if (references.length() != 0) chapter.put("refs", references);
        return chapter;
    }
}

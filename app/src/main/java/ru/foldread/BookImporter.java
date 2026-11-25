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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
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
    private final Map<Element,String> anchors=new IdentityHashMap<>();
    private final Map<Element,String> linkedAnchors=new IdentityHashMap<>();
    private final TreeMap<Integer,String> sectionTitles=new TreeMap<>();
    private final TreeMap<Integer,String> linkedTitles=new TreeMap<>();
    private Element firstHeading;
    private int firstHeadingParagraph;
    static final int SCHEMA=5;

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
                .put("chapters", reader.chapters).put("notes", reader.notes.texts).put("schema", SCHEMA)
                .put("cover",reader.artwork.cover).put("accent",reader.artwork.accent)
                .put("series",reader.artwork.series).put("volume",reader.artwork.volume).put("images",reader.artwork.images);
    }

    private void addChapter(String name, List<String> paragraphs) throws Exception {
        if (paragraphs.isEmpty()) return;
        JSONObject chapter=notes.chapter(name,paragraphs);
        if(firstHeading!=null&&firstHeadingParagraph>0&&firstHeadingParagraph<paragraphs.size())
            chapter.put("inlineHeading",new JSONObject().put("p",firstHeadingParagraph).put("title",anchors.get(firstHeading)));
        if(!sectionTitles.isEmpty()){
            JSONArray sections=new JSONArray();
            for(Map.Entry<Integer,String> s:sectionTitles.entrySet())if(s.getKey()<paragraphs.size())
                sections.put(new JSONObject().put("p",s.getKey()).put("title",s.getValue()));
            chapter.put("sections",sections);
        }
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
            if (body.tagName().equals("body")) section(body, "");
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
        Map<String,Map<String,String>> toc=new HashMap<>();
        for(Element item:items.values())if(java.util.Arrays.asList(item.attr("properties").split("\\s+")).contains("nav"))
            readContents(zip,base,item,true,toc);
        Element spine=pkg.selectFirst("spine");
        Element ncx=spine==null?null:items.get(spine.attr("toc"));
        if(ncx==null)for(Element item:items.values())if(item.attr("media-type").equals("application/x-dtbncx+xml")){ncx=item;break;}
        if(ncx!=null)readContents(zip,base,ncx,false,toc);
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
            anchors.clear();linkedAnchors.clear();sectionTitles.clear();linkedTitles.clear();firstHeadingParagraph=0;
            Map<String,String> labels=toc.getOrDefault(name,java.util.Collections.emptyMap());
            Element head=doc.body().selectFirst("h1,h2,h3,h4,h5,h6");
            firstHeading=head;
            String heading=head==null?"":head.text();
            for(Element h:doc.body().select("h1,h2,h3,h4,h5,h6"))if(h.hasText())anchors.put(h,h.text());
            for(Map.Entry<String,String> label:labels.entrySet()){
                String fragment=label.getKey();
                if(fragment.isEmpty())continue;
                Element target=doc.getElementById(fragment);
                if(target==null)for(Element a:doc.select("a[name]"))if(fragment.equals(a.attr("name"))){target=a;break;}
                if(target!=null){
                    Element h=target.closest("h1,h2,h3,h4,h5,h6");
                    linkedAnchors.put(h==null?target:h,label.getValue());
                }
            }
            if(head!=null)head.empty();
            notes.epubLinks(doc, name);
            List<String> paragraphs = new ArrayList<>();
            collect(doc.body(), paragraphs);
            sectionTitles.putAll(linkedTitles);
            if(labels.containsKey("")&&!linkedTitles.containsKey(0))sectionTitles.put(0,labels.get(""));
            if(heading.isEmpty()||firstHeadingParagraph>0)heading=sectionTitles.getOrDefault(0,doc.title().equals(title)?"":doc.title());
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

    private void readContents(ZipFile zip,URI base,Element item,boolean nav,Map<String,Map<String,String>> toc)throws Exception{
        URI file=localLink(base,item.attr("href"));
        if(file==null||zip.getEntry(file.getPath().substring(1))==null)return;
        Document doc=entry(zip,file.getPath().substring(1),nav?Parser.htmlParser():Parser.xmlParser());
        if(nav){
            for(Element list:doc.select("nav")){
                if(!java.util.Arrays.asList(list.attr("epub:type").split("\\s+")).contains("toc")&&!list.attr("role").equals("doc-toc"))continue;
                for(Element link:list.select("a[href]"))addLabel(toc,file,link.attr("href"),link.text());
            }
        }else{
            for(Element point:doc.select("navMap navPoint")){
                Element label=null,content=null;
                for(Element child:point.children()){
                    if(child.tagName().equalsIgnoreCase("navLabel"))label=child;
                    if(child.tagName().equalsIgnoreCase("content"))content=child;
                }
                if(label!=null&&content!=null)addLabel(toc,file,content.attr("src"),label.text());
            }
        }
    }
    private static URI localLink(URI base,String href){
        try{
            URI uri=base.resolve(href.replace(" ","%20"));
            return !href.isEmpty()&&!uri.isAbsolute()&&uri.getAuthority()==null&&uri.getPath()!=null&&uri.getPath().startsWith("/")?uri:null;
        }catch(IllegalArgumentException ex){return null;}
    }
    private static void addLabel(Map<String,Map<String,String>> toc,URI base,String href,String title){
        URI target=localLink(base,href);
        if(target==null||title.isEmpty())return;
        toc.computeIfAbsent(target.getPath().substring(1),k->new LinkedHashMap<>())
            .putIfAbsent(target.getFragment()==null?"":target.getFragment(),title);
    }

    private void collect(Element root, List<String> paragraphs) {
        StringBuilder line = new StringBuilder();
        collectNodes(root, paragraphs, line);
        flush(paragraphs, line);
    }

    private void collectNodes(Element el, List<String> paragraphs, StringBuilder line) {
        if(el==firstHeading)firstHeadingParagraph=paragraphs.size();
        if(linkedAnchors.containsKey(el))linkedTitles.put(paragraphs.size(),linkedAnchors.get(el));
        if(anchors.containsKey(el))sectionTitles.put(paragraphs.size(),anchors.get(el));
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

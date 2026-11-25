package ru.foldread;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Only decoded, size-limited raster art is exposed to the reading surface. */
final class BookArtwork {
    final JSONArray images = new JSONArray();
    String cover = "", accent = "", series = "", volume = "";
    private final File directory;
    private final String id;
    private long bytesRead;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;

    BookArtwork(File directory, String id) { this.directory = directory; this.id = id; }

    void fb2(Document doc, Element info) throws Exception {
        String coverId = "";
        if (info != null) {
            Element seq = info.selectFirst("sequence[name]");
            if (seq != null) { series = seq.attr("name"); volume = seq.attr("number"); }
            Element image = info.selectFirst("coverpage image");
            if (image != null) for (org.jsoup.nodes.Attribute a : image.attributes())
                if (a.getKey().equals("href") || a.getKey().endsWith(":href")) coverId = a.getValue();
        }
        for (Element bin : doc.select("binary[id]")) {
            if (!bin.attr("content-type").startsWith("image/")) continue;
            String encoded = bin.ownText();
            if (encoded.length() > MAX_IMAGE * 1.5) continue;
            try { add(Base64.decode(encoded, Base64.DEFAULT), bin.id(), coverId.equals("#"+bin.id())); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    void epub(Document pkg, ZipFile zip, URI base) throws Exception {
        String coverId = "", collectionId = "";
        for (Element meta : pkg.select("metadata > meta")) {
            switch (meta.attr("name")) {
                case "cover": coverId = meta.attr("content"); break;
                case "calibre:series": series = meta.attr("content"); break;
                case "calibre:series_index": volume = meta.attr("content"); break;
            }
            if (meta.attr("property").equals("belongs-to-collection")) {
                series = meta.text(); collectionId = meta.id();
            }
        }
        if (!collectionId.isEmpty()) for (Element meta : pkg.select("metadata > meta"))
            if (meta.attr("property").equals("group-position") && meta.attr("refines").equals("#"+collectionId))
                volume = meta.text();
        for (Element item : pkg.select("manifest > item")) {
            if (!item.attr("media-type").startsWith("image/")) continue;
            URI uri;
            try { uri = base.resolve(item.attr("href").replace(" ", "%20")); }
            catch (IllegalArgumentException ignored) { continue; }
            if (uri.isAbsolute() || uri.getAuthority() != null) continue;
            String path = uri.getPath().substring(1);
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.getSize() < 0 || entry.getSize() > MAX_IMAGE
                    || bytesRead + entry.getSize() > BookImporter.MAX_FILE) continue;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] buf = new byte[16384]; int n;
                while ((n=in.read(buf)) != -1) {
                    if (out.size()+n > MAX_IMAGE) break;
                    out.write(buf,0,n);
                }
            }
            if (out.size()!=entry.getSize()) continue;
            boolean isCover = item.id().equals(coverId) || item.attr("properties").contains("cover-image");
            String name = path.substring(path.lastIndexOf('/')+1);
            add(out.toByteArray(), name, isCover || cover.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith("cover."));
        }
    }

    private void add(byte[] bytes, String name, boolean isCover) throws Exception {
        bytesRead += bytes.length;
        if (bytesRead > BookImporter.MAX_FILE || bytes.length > MAX_IMAGE || images.length() >= 512) return;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return;
        opts.inJustDecodeBounds = false; opts.inSampleSize = 1;
        while (Math.max(opts.outWidth,opts.outHeight) / opts.inSampleSize > 3000) opts.inSampleSize *= 2;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
        if (bmp == null) return;
        String filename = id+"-"+images.length()+".jpg";
        File tmp = File.createTempFile("art-", ".tmp", directory);
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG,89,out)) return;
            }
            Files.move(tmp.toPath(),new File(directory,filename).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            String src = "art/"+filename;
            images.put(new JSONObject().put("src",src).put("name",name));
            if (isCover) {
                cover=src;
                int best=0;float score=-1;
                float[] hsv=new float[3];
                for(int y=1;y<8;y++) for(int x=1;x<8;x++){
                    int pixel=bmp.getPixel(bmp.getWidth()*x/8,bmp.getHeight()*y/8);
                    Color.colorToHSV(pixel,hsv);
                    float candidate=hsv[1]*(1-Math.abs(.6f-hsv[2]));
                    if(candidate>score){best=pixel;score=candidate;}
                }
                accent=String.format(Locale.ROOT,"#%06x",0xffffff & best);
            }
        } finally { bmp.recycle(); tmp.delete(); }
    }
}

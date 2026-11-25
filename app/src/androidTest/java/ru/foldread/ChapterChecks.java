package ru.foldread;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ChapterChecks {
    static void run(Context context)throws Exception{
        for(boolean epub3:new boolean[]{true,false}){
            File input=File.createTempFile("contents-",".epub",context.getCacheDir());
            try{
                try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(input))){
                    put(zip,"META-INF/container.xml","<container><rootfile full-path='OPS/book.opf'/></container>");
                    String resource=epub3?"href='toc/nav.xhtml' media-type='application/xhtml+xml' properties='nav'":"href='toc/nav.ncx' media-type='application/x-dtbncx+xml'";
                    put(zip,"OPS/book.opf","<package><metadata><title>Книга</title></metadata><manifest><item id='toc' "+resource+"/><item id='s' href='text.xhtml' media-type='application/xhtml+xml'/></manifest><spine toc='toc'><itemref idref='s'/></spine></package>");
                    if(epub3)put(zip,"OPS/toc/nav.xhtml","<html><nav epub:type='toc'><a href='../text.xhtml#a'>Берег</a><a href='../text.xhtml#b'>Глава 12</a></nav></html>");
                    else put(zip,"OPS/toc/nav.ncx","<ncx><navMap><navPoint><navLabel><text>Берег</text></navLabel><content src='../text.xhtml#a'/></navPoint><navPoint><navLabel><text>Глава 12</text></navLabel><content src='../text.xhtml#b'/></navPoint></navMap></ncx>");
                    put(zip,"OPS/text.xhtml","<html><head><title>Книга</title></head><body><h1 id='a'>На берегу</h1><p>Начало текста.</p><section id='b'><h2>XII</h2><p>Продолжение.</p></section></body></html>");
                }
                JSONObject book=BookImporter.read(input,"Книга.epub","sails",context.getCacheDir());
                JSONObject first=book.getJSONArray("chapters").getJSONObject(0);
                JSONArray parts=first.getJSONArray("sections");
                if(!first.getString("title").equals("На берегу")||parts.length()!=2||
                    !parts.getJSONObject(0).getString("title").equals("Берег")||
                    !parts.getJSONObject(1).getString("title").equals("Глава 12")||
                    parts.getJSONObject(1).getInt("p")!=1||!first.getJSONArray("paragraphs").getString(1).equals("XII"))
                    throw new AssertionError("Source heading or linked TOC anchor lost");
            }finally{input.delete();}
        }
    }
    private static void put(ZipOutputStream zip,String path,String xml)throws Exception{
        zip.putNextEntry(new ZipEntry(path));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
}

package ru.foldread;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

final class LibraryBackup {
    private static final String ID = "[a-f0-9]{64}";
    private static final String FILE = ID + "(?:\\.json|-[0-9]+\\.jpg)";
    private static final int MIB = 1024 * 1024;
    private final Context context;
    private final SharedPreferences prefs;
    LibraryBackup(Context context) {
        this.context=context;
        prefs=context.getSharedPreferences("books",Context.MODE_PRIVATE);
    }
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String read(File f)throws IOException {
        return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
    }
    private static void put(ZipOutputStream zip,String name,byte[] value)throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();
    }
    void exportTo(OutputStream out,File state)throws Exception {
        JSONObject catalog=new JSONObject(prefs.getString("catalog","{}"));
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(out))){
            put(zip,"manifest.json",utf(new JSONObject().put("foldread",1).put("books",catalog.names()==null?new JSONArray():catalog.names()).toString()));
            put(zip,"reading.json",Files.readAllBytes(state.toPath()));
            File[] files=context.getFilesDir().listFiles();
            if(files==null)throw new IOException();
            for(File file:files){
                String name=file.getName();
                if(!name.matches(FILE) || !catalog.has(name.substring(0,64)))continue;
                zip.putNextEntry(new ZipEntry(name));Files.copy(file.toPath(),zip);zip.closeEntry();
            }
        }
    }
    void restore(InputStream source)throws Exception {
        File stage=Files.createTempDirectory(context.getCacheDir().toPath(),"restore-").toFile();
        try{
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source))){
                byte[] buffer=new byte[65536];int count=0;long total=0;
                for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                    String name=e.getName();
                    if(++count>50000 || (!name.equals("manifest.json")&&!name.equals("reading.json")&&!name.matches(FILE)))throw invalid();
                    File dest=new File(stage,name);
                    if(!dest.createNewFile())throw invalid();
                    int limit=name.equals("reading.json")?12*MIB:name.endsWith(".jpg")?40*MIB:80*MIB;
                    long entry=0;
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                        for(int n;(n=zip.read(buffer))!=-1;){
                            entry+=n;total+=n;if(entry>limit||total>4L*1024*MIB)throw invalid();
                            out.write(buffer,0,n);
                        }
                    }
                }
            }
            JSONObject manifest=new JSONObject(read(new File(stage,"manifest.json")));
            if(manifest.getInt("foldread")!=1)throw invalid();
            JSONArray ids=manifest.getJSONArray("books");
            JSONObject catalog=new JSONObject(prefs.getString("catalog","{}"));
            Set<String> expected=new HashSet<>();Set<String> added=new HashSet<>();
            for(int i=0;i<ids.length();i++){
                String id=ids.getString(i);if(!id.matches(ID)||!expected.add(id))throw invalid();
                JSONObject b=new JSONObject(read(new File(stage,id+".json")));
                if(!id.equals(b.getString("id")) || b.getString("title").isEmpty())throw invalid();
                JSONArray chapters=b.getJSONArray("chapters");if(chapters.length()==0)throw invalid();
                for(int j=0;j<chapters.length();j++){
                    JSONObject c=chapters.getJSONObject(j);c.getString("title");
                    JSONArray lines=c.getJSONArray("paragraphs");if(lines.length()==0)throw invalid();
                    for(int p=0;p<lines.length();p++)if(!(lines.get(p) instanceof String)||lines.getString(p).isEmpty())throw invalid();
                }
                JSONArray images=b.optJSONArray("images");
                if(images!=null)for(int j=0;j<images.length();j++){
                    String path=images.getJSONObject(j).getString("src");
                    if(!path.matches("art/"+id+"-[0-9]+\\.jpg")||!new File(stage,path.substring(4)).isFile())throw invalid();
                }
                if(!catalog.has(id)){
                    b.remove("chapters");b.remove("images");b.remove("notes");catalog.put(id,b);added.add(id);
                }
            }
            String state=read(new File(stage,"reading.json"));JSONObject values=new JSONObject(state);
            for(Iterator<String> it=values.keys();it.hasNext();){
                String key=it.next();if(!(values.get(key) instanceof String))throw invalid();
                new org.json.JSONTokener(values.getString(key)).nextValue();
            }
            File[] files=stage.listFiles();if(files==null)throw invalid();
            for(File f:files)if(f.getName().matches(FILE)){
                String id=f.getName().substring(0,64);if(!expected.contains(id))throw invalid();
                if(added.contains(id))Files.move(f.toPath(),new File(context.getFilesDir(),f.getName()).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            }
            // Publishing the catalog and pending reader state together makes a stopped restore resumable.
            if(!prefs.edit().putString("catalog",catalog.toString()).putString("restoreState",state)
                .putStringSet("restoreAdded",added).commit())throw new IOException();
        }finally{
            File[] files=stage.listFiles();if(files!=null)for(File f:files)f.delete();
            stage.delete();
        }
    }
    private static IOException invalid(){return new IOException("Некорректная резервная копия Bookfold.");}
}

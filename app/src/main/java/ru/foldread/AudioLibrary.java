package ru.foldread;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser.MediaItem;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Book IDs and UTF-16 text anchors shared by the reader and media browser. */
final class AudioLibrary {
    static final String ROOT="root", SHELF="shelf", RECENT="recent", RESUME="resume";
    final Context context;
    final SharedPreferences prefs;
    AudioLibrary(Context context) {
        this.context=context;
        prefs=context.getSharedPreferences("audio-reader",Context.MODE_PRIVATE);
    }
    static boolean valid(String id){return id!=null && (id.equals("sails") || id.matches("[0-9a-f]{64}"));}
    static JSONObject object(String raw){try{return new JSONObject(raw);}catch(JSONException ex){return new JSONObject();}}
    static JSONObject anchor(int chapter,int p,int offset){
        JSONObject result=new JSONObject();
        try{result.put("chapter",chapter).put("p",p).put("offset",offset);}catch(JSONException ignored){}
        return result;
    }
    String current(){return prefs.getString("current","sails");}
    JSONObject place(String id){return object(prefs.getString("pos."+id,"{}"));}
    void save(String id,JSONObject at){if(valid(id))prefs.edit().putString("current",id).putString("pos."+id,at.toString()).apply();}
    JSONObject read(String id)throws Exception {
        if(!valid(id))throw new IOException("Книга не найдена");
        try(InputStream in=id.equals("sails")?context.getAssets().open("sails.json"):context.openFileInput(id+".json");
            Reader reader=new InputStreamReader(in,StandardCharsets.UTF_8)){
            StringBuilder text=new StringBuilder();char[] buf=new char[8192];int n;
            while((n=reader.read(buf))!=-1)text.append(buf,0,n);
            return new JSONObject(text.toString());
        }
    }
    List<JSONObject> shelf() throws JSONException {
        ArrayList<JSONObject> all=new ArrayList<>();
        all.add(new JSONObject().put("id","sails").put("title","Алые паруса").put("author","Александр Грин"));
        JSONObject catalog=object(context.getSharedPreferences("books",Context.MODE_PRIVATE).getString("catalog","{}"));
        Iterator<String> keys=catalog.keys();
        while(keys.hasNext()){JSONObject item=catalog.optJSONObject(keys.next());if(item!=null && valid(item.optString("id")))all.add(item);}
        return all;
    }
    private static MediaItem item(String id,String title,String subtitle,int flags){
        return new MediaItem(new MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),flags);
    }
    static final class Section{
        final int chapter,p;final String title;
        Section(int chapter,int p,String title){this.chapter=chapter;this.p=p;this.title=title;}
        String mediaId(String id){return "chapter/"+id+"/"+chapter+(p==0?"":"/"+p);}
    }
    static List<Section> sections(JSONObject book)throws JSONException{
        List<Section> list=new ArrayList<>();
        JSONArray chapters=book.getJSONArray("chapters");
        for(int c=0;c<chapters.length();c++){
            JSONObject chunk=chapters.getJSONObject(c);
            JSONArray entries=chunk.optJSONArray("sections");
            if(entries==null||entries.length()==0||entries.getJSONObject(0).getInt("p")!=0){
                String title=chunk.optString("title");
                if(title.isEmpty()){String text=chunk.getJSONArray("paragraphs").getString(0);title=text.substring(0,Math.min(64,text.length()));}
                list.add(new Section(c,0,title));
            }
            if(entries!=null)for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);list.add(new Section(c,entry.getInt("p"),entry.getString("title")));
            }
        }
        return list;
    }
    List<MediaItem> children(String parent)throws Exception{
        ArrayList<MediaItem> items=new ArrayList<>();
        if(parent.equals(ROOT)){
            items.add(item(RECENT,"Сейчас читаю","С последнего места",MediaItem.FLAG_BROWSABLE));
            items.add(item(SHELF,"Библиотека","Книги на телефоне",MediaItem.FLAG_BROWSABLE));
        }else if(parent.equals(RECENT)){
            String title="С последнего места";
            for(JSONObject b:shelf())if(b.optString("id").equals(current()))title=b.optString("title");
            items.add(item(RESUME,"Продолжить чтение",title,MediaItem.FLAG_PLAYABLE));
        }else if(parent.equals(SHELF)){
            for(JSONObject b:shelf())items.add(item("book/"+b.getString("id"),b.getString("title"),b.optString("author"),MediaItem.FLAG_BROWSABLE));
        }else if(parent.startsWith("book/")){
            String id=parent.substring(5);
            JSONObject b=read(id);
            items.add(item("continue/"+id,"С сохранённого места",b.optString("title"),MediaItem.FLAG_PLAYABLE));
            for(Section s:sections(b))items.add(item(s.mediaId(id),s.title,b.optString("title"),MediaItem.FLAG_PLAYABLE));
        }
        return items;
    }
}

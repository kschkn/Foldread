package ru.foldread;

import android.content.res.AssetManager;
import android.util.LruCache;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

final class ReaderDictionary {
    private final AssetManager assets;
    private final LruCache<Integer,Map<String,String>> cache=new LruCache<>(4);
    ReaderDictionary(AssetManager assets){this.assets=assets;}
    private String entry(String key)throws IOException {
        CRC32 crc=new CRC32();
        int prefix=Math.min(2,key.length());
        crc.update(key.substring(0,prefix).getBytes(StandardCharsets.UTF_8));
        int bucket=(int)(crc.getValue()%64);
        Map<String,String> words=cache.get(bucket);
        if(words==null){
            words=new HashMap<>();
            // AAPT strips .gz and exposes the inflated asset through AssetManager.
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                assets.open(String.format(Locale.ROOT,"dictionary/%02d",bucket)),StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null){int tab=line.indexOf('\t');if(tab>0)words.put(line.substring(0,tab),line.substring(tab+1));}
            }
            cache.put(bucket,words);
        }
        return words.get(key);
    }
    synchronized String lookup(String term){
        String key=term.trim().toLowerCase(Locale.ROOT).replace('ё','е').replace("\u0301","");
        JSONObject out=new JSONObject();
        if(key.isEmpty()||key.length()>72)return "{}";
        try{
            String text=entry(key);
            if(text!=null)return out.put("text",text).put("word",key).toString();
            // Candidates are presented for explicit selection; related spellings can have different meanings.
            Set<String> matches=new LinkedHashSet<>();
            String[] endings={"иями","ями","ами","ого","ему","ому","ыми","ими","иях","ах","ях","ом","ем","ов","ев","ей","ам","ям","ую","юю","ых","их","ая","яя","ет","ит","ют","ут","ят","ат","ы","и","а","я","у","ю","е"};
            for(String ending:endings)if(key.endsWith(ending)&&key.length()>ending.length()+1){
                String stem=key.substring(0,key.length()-ending.length());
                for(String suffix:new String[]{"","ь","а","я","о","е","й","ый","ий","ой","ть","ить","ать"}){
                    String candidate=stem+suffix;
                    if(!candidate.equals(key)&&entry(candidate)!=null)matches.add(candidate);
                    if(matches.size()>=5)break;
                }
                if(matches.size()>=5)break;
            }
            return out.put("suggestions",new JSONArray(matches)).toString();
        }catch(IOException|JSONException ex){return "{}";}
    }
}

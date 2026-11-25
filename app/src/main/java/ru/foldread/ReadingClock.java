package ru.foldread;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs only while the focused activity is showing book text. */
final class ReadingClock {
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long since;
    private final Runnable checkpoint=new Runnable(){
        @Override public void run(){
            long now=SystemClock.uptimeMillis();
            record(now);
            since=now;
            handler.postDelayed(this,60000);
        }
    };

    ReadingClock(Context context,SharedPreferences prefs){
        this.context=context.getApplicationContext();
        this.prefs=prefs;
    }
    void active(boolean active){
        if(active==(since!=0))return;
        if(active){
            since=SystemClock.uptimeMillis();
            handler.postDelayed(checkpoint,60000);
        }else{
            handler.removeCallbacks(checkpoint);
            record(SystemClock.uptimeMillis());
            since=0;
        }
    }
    private void record(long now){
        long elapsed=now-since,end=System.currentTimeMillis();
        long before=prefs.getLong("readingMillis",0),after=before+elapsed;
        SharedPreferences.Editor edit=prefs.edit().putLong("readingMillis",after);
        ZoneId zone=ZoneId.systemDefault();
        for(long start=end-elapsed;start<end;){
            LocalDate date=Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
            long stop=Math.min(end,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            String key="day."+date;
            edit.putLong(key,prefs.getLong(key,0)+(stop-start));start=stop;
        }
        edit.apply();
        if(after/60000!=before/60000)ReadingWidgets.refresh(context,ReadingWidgets.Time.class);
        ReadingWidgets.refresh(context,ReadingWidgets.Week.class);
    }
    static JSONObject history(SharedPreferences prefs){
        JSONObject result=new JSONObject();
        try{
            for(Map.Entry<String,?> entry:prefs.getAll().entrySet())if(entry.getKey().startsWith("day."))result.put(entry.getKey().substring(4),entry.getValue());
        }catch(JSONException ex){throw new IllegalStateException(ex);}
        return result;
    }
}

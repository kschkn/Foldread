package ru.foldread;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public abstract class ReadingWidgets extends AppWidgetProvider {
    public static final class CurrentBook extends ReadingWidgets {}
    public static final class Time extends ReadingWidgets {}
    public static final class Bookmark extends ReadingWidgets {}
    public static final class Week extends ReadingWidgets {
        @Override public void onReceive(Context context,Intent intent){
            super.onReceive(context,intent);
            String action=intent.getAction();
            if(Intent.ACTION_DATE_CHANGED.equals(action)||Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action))refresh(context,Week.class);
        }
    }
    static final String OPEN_READER="ru.foldread.WIDGET_READ";
    static final String OPEN_MARK="ru.foldread.WIDGET_MARK";
    static final String OPEN_STATS="ru.foldread.WIDGET_STATS";
    private static String cachedCover="";
    private static Bitmap thumbnail;

    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){
        update(context,manager,getClass(),ids);
    }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options){
        update(context,manager,getClass(),new int[]{id});
    }

    static void refresh(Context context,Class<? extends ReadingWidgets> provider){
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] ids=manager.getAppWidgetIds(new ComponentName(context,provider));
        update(context,manager,provider,ids);
    }

    private static void update(Context context,AppWidgetManager manager,Class<? extends ReadingWidgets> provider,int[] ids){
        if(ids.length==0)return;
        RemoteViews compact=views(context,provider,true),large=views(context,provider,false);
        int wide=provider==Time.class?108:provider==Bookmark.class?135:provider==Week.class?192:175;
        int tall=provider==Week.class?110:100;
        if(Build.VERSION.SDK_INT>=31){
            Map<SizeF,RemoteViews> layouts=new LinkedHashMap<>();
            layouts.put(new SizeF(100,40),compact);layouts.put(new SizeF(wide,tall),large);
            manager.updateAppWidget(ids,new RemoteViews(layouts));
        }else for(int id:ids){
            Bundle options=manager.getAppWidgetOptions(id);
            boolean portrait=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)>=wide && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)>=tall;
            boolean landscape=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)>=wide && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)>=tall;
            manager.updateAppWidget(id,new RemoteViews(landscape?large:compact,portrait?large:compact));
        }
    }

    static void saveBook(Context context,String id,String title,String cover,String range,int percent){
        if(!AudioLibrary.valid(id)||percent<0||percent>100)return;
        SharedPreferences prefs=context.getSharedPreferences("reading-widget",Context.MODE_PRIVATE);
        prefs.edit().putString("id",id).putString("title",title).putString("cover",cover)
            .putString("range",range).putInt("percent",percent).apply();
        refresh(context,CurrentBook.class);
    }

    private static RemoteViews views(Context context,Class<? extends ReadingWidgets> provider,boolean compact){
        if(provider==Week.class)return week(context,compact);
        int layout;
        if(provider==Bookmark.class)layout=compact?R.layout.widget_bookmark_compact:R.layout.widget_bookmark;
        else if(provider==Time.class)layout=compact?R.layout.widget_time_compact:R.layout.widget_time;
        else layout=compact?R.layout.widget_book_compact:R.layout.widget_book;
        RemoteViews views=new RemoteViews(context.getPackageName(),layout);
        String action=provider==Bookmark.class?OPEN_MARK:provider==Time.class?OPEN_STATS:OPEN_READER;
        Intent open=new Intent(context,ReaderActivity.class).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        SharedPreferences prefs=context.getSharedPreferences("reading-widget",Context.MODE_PRIVATE);
        if(provider==Bookmark.class){
            JSONObject mark=AudioLibrary.object(prefs.getString("bookmark",""));
            views.setTextViewText(R.id.widget_title,mark.optString("title",context.getString(R.string.widget_no_bookmark)));
            views.setTextViewText(R.id.widget_mark_location,mark.optString("label",context.getString(R.string.widget_mark_hint)));
            return views;
        }
        if(provider==Time.class){
            long minutes=context.getSharedPreferences("audio-reader",Context.MODE_PRIVATE).getLong("readingMillis",0)/60000;
            views.setTextViewText(R.id.widget_duration,minutes<60?context.getString(R.string.widget_minutes,minutes):context.getString(R.string.widget_hours,minutes/60,minutes%60));
            return views;
        }
        String id=prefs.getString("id","");
        boolean hasBook=!id.isEmpty();
        int percent=prefs.getInt("percent",0);
        views.setTextViewText(R.id.widget_title,hasBook?prefs.getString("title",""):context.getString(R.string.widget_open_book));
        String range=prefs.getString("range","");
        views.setTextViewText(R.id.widget_pages,range.isEmpty()?context.getString(R.string.widget_counting):compact?range.replace(" из ","/"):range);
        views.setTextViewText(R.id.widget_percent,percent+"%");
        views.setViewVisibility(R.id.widget_numbers,hasBook?View.VISIBLE:View.GONE);
        if(compact)return views;
        views.setViewVisibility(R.id.widget_bar,hasBook?View.VISIBLE:View.GONE);
        views.setProgressBar(R.id.widget_bar,100,percent,false);
        Bitmap image=cover(context,id,prefs.getString("cover",""));
        if(image==null)views.setImageViewResource(R.id.widget_cover,R.drawable.ic_audio_book);
        else views.setImageViewBitmap(R.id.widget_cover,image);
        return views;
    }

    static int weeklyGoal(Context context){
        return context.getSharedPreferences("audio-reader",Context.MODE_PRIVATE).getInt("weeklyGoal",140);
    }

    private static RemoteViews week(Context context,boolean compact){
        String pkg=context.getPackageName();
        RemoteViews views=new RemoteViews(pkg,compact?R.layout.widget_week_compact:R.layout.widget_week);
        SharedPreferences prefs=context.getSharedPreferences("audio-reader",Context.MODE_PRIVATE);
        LocalDate today=LocalDate.now(),monday=today.with(DayOfWeek.MONDAY);
        String[] names=context.getResources().getStringArray(R.array.widget_days);
        long total=0;int goal=weeklyGoal(context);
        if(!compact)views.removeAllViews(R.id.widget_days);
        for(int i=0;i<7;i++){
            LocalDate date=monday.plusDays(i);
            long millis=prefs.getLong("day."+date,0);total+=millis;
            if(compact)continue;
            RemoteViews cell=new RemoteViews(pkg,R.layout.widget_day);
            cell.setTextViewText(R.id.day_name,names[i]);
            cell.setTextViewText(R.id.day_minutes,date.isAfter(today)?"·":Long.toString(millis/60000));
            if(date.equals(today))cell.setTextColor(R.id.day_name,0xff8b4939);
            views.addView(R.id.widget_days,cell);
        }
        long minutes=total/60000;
        views.setTextViewText(R.id.widget_week_total,context.getString(R.string.widget_week_minutes,minutes,goal));
        if(!compact)views.setProgressBar(R.id.widget_bar,goal,(int)Math.min(minutes,goal),false);
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,0,new Intent(context,WeeklyGoalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }

    private static synchronized Bitmap cover(Context context,String id,String path){
        if(!(id.equals("sails")&&path.equals("sails-cover.jpg")) &&
            !(id.length()==64&&path.startsWith("art/"+id+"-")&&path.matches("art/[0-9a-f]{64}-[0-9]+\\.jpg")))return null;
        if(path.equals(cachedCover)&&thumbnail!=null)return thumbnail;
        BitmapFactory.Options options=new BitmapFactory.Options();
        try{
            options.inJustDecodeBounds=true;
            try(InputStream in=openCover(context,path)){BitmapFactory.decodeStream(in,null,options);}
            options.inSampleSize=1;
            while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            try(InputStream in=openCover(context,path)){thumbnail=BitmapFactory.decodeStream(in,null,options);}
            cachedCover=path;return thumbnail;
        }catch(IOException ex){return null;}
    }

    private static InputStream openCover(Context context,String path)throws IOException{
        if(path.equals("sails-cover.jpg"))return context.getAssets().open(path);
        return new java.io.FileInputStream(new File(context.getFilesDir(),path.substring(4)));
    }
}

package ru.foldread;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.*;
import android.os.*;
import android.service.media.MediaBrowserService;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Android Auto, the lock screen and the reader use the same playback cursor. */
public final class BookPlaybackService extends MediaBrowserService {
    private static volatile boolean running;
    static String snapshot(AudioLibrary library){
        JSONObject state=AudioLibrary.object(library.prefs.getString("state","{}"));
        if(!running && ("playing".equals(state.optString("state"))||"loading".equals(state.optString("state"))))
            try{state.put("state","paused");}catch(JSONException ignored){}
        return state.toString();
    }
    private static final String CHANNEL="spoken-books";
    private static final int NOTIFICATION=61;
    private static final Pattern SENTENCE=Pattern.compile("[.!?…]+[»”\"’)]*(?:\\s+|$)");
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private AudioLibrary library;
    private List<AudioLibrary.Section> contents=Collections.emptyList();
    private int sectionIndex;
    private ReaderVoice voice;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private NotificationManager notifications;
    private JSONObject book;
    private String id="", title="Bookfold", author="", heading="", state="stopped", error="";
    private int chapter,p,offset,end,generation,phrase;
    private boolean limit;
    private final BroadcastReceiver unplug=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){if(active())pause("");}
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener catalogChange=(prefs,key)->{
        if("catalog".equals(key))notifyChildrenChanged(AudioLibrary.SHELF);
    };
    private static synchronized String signature(Context c){
        SharedPreferences prefs=c.getSharedPreferences("playback-internal",MODE_PRIVATE);
        String key=prefs.getString("key",null);
        if(key==null){key=UUID.randomUUID().toString();prefs.edit().putString("key",key).apply();}
        return key;
    }
    static Intent command(Context c,String action){
        return new Intent(c,BookPlaybackService.class).setAction(action).putExtra("auth",signature(c));
    }
    private PendingIntent control(String action){
        Intent intent=command(this,action);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        return action.equals("play")?PendingIntent.getForegroundService(this,0,intent,flags):PendingIntent.getService(this,0,intent,flags);
    }
    @Override public void onCreate(){
        super.onCreate();
        running=true;
        library=new AudioLibrary(this);
        notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Чтение вслух",NotificationManager.IMPORTANCE_LOW));
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Bookfold:speech");
        wake.setReferenceCounted(false);
        voice=new ReaderVoice(this,(token,event)->{
            if(token!=phrase||!active())return;
            if(event.equals("done")){offset=end;nextPhrase();}
            else pause(event.equals("unavailable")?"Установите офлайн-голос для языка книги в настройках синтеза речи Android.":"");
        });
        session=new MediaSession(this,"Bookfold");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(PendingIntent.getActivity(this,0,new Intent(this,ReaderActivity.class).setAction("ru.foldread.SHOW_AUDIO")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE));
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){request("play",null);}
            @Override public void onPause(){if(active())pause("");}
            @Override public void onStop(){finishPlayback();}
            @Override public void onSkipToNext(){skip(1);}
            @Override public void onSkipToPrevious(){skip(-1);}
            @Override public void onPlayFromMediaId(String mediaId,Bundle extras){request("media",mediaId);}
            @Override public void onPlayFromSearch(String query,Bundle extras){
                if(query==null||query.trim().isEmpty()){onPlay();return;}
                try{for(JSONObject b:library.shelf()){
                    String name=(b.optString("title")+" "+b.optString("author")).toLowerCase(Locale.ROOT);
                    if(name.contains(query.trim().toLowerCase(Locale.ROOT))){request("media","continue/"+b.optString("id"));return;}
                }}catch(JSONException ignored){}
                if(!active())pause("Книга по запросу не найдена");
            }
        },main);
        setSessionToken(session.getSessionToken());
        transport();
        registerReceiver(unplug,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        getSharedPreferences("books",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(catalogChange);
    }
    private boolean active(){return state.equals("playing")||state.equals("loading");}
    private void request(String action,String media){
        Intent intent=command(this,action).putExtra("media",media);
        startForegroundService(intent);
    }
    static boolean allowedBrowser(Context context,String pkg,int uid){
        if(uid==android.os.Process.myUid()||uid==android.os.Process.SYSTEM_UID)return true;
        PackageManager pm=context.getPackageManager();
        if(pm.checkPermission("android.permission.MEDIA_CONTENT_CONTROL",pkg)==PackageManager.PERMISSION_GRANTED)return true;
        if(!"com.google.android.projection.gearhead".equals(pkg))return false;
        try{
            // Android Auto rotates its signing key on Android 13 and later.
            Set<String> signers=new HashSet<>(Arrays.asList(
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
                "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"));
            for(android.content.pm.Signature cert:pm.getPackageInfo(pkg,PackageManager.GET_SIGNATURES).signatures){
                StringBuilder hash=new StringBuilder();
                for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()))
                    hash.append(String.format(Locale.ROOT,"%02x",b));
                if(signers.contains(hash.toString()))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){
        return allowedBrowser(this,pkg,uid)?new BrowserRoot(AudioLibrary.ROOT,null):null;
    }
    @Override public void onLoadChildren(String parent,Result<List<MediaItem>> result){
        result.detach();
        io.execute(()->{
            List<MediaItem> items;
            try{items=library.children(parent);}catch(Exception ex){items=Collections.emptyList();}
            result.sendResult(items);
        });
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||!signature(this).equals(intent.getStringExtra("auth")))return START_NOT_STICKY;
        String action=intent.getAction();
        if("stop".equals(action)){finishPlayback();return START_NOT_STICKY;}
        if("pause".equals(action)){if(active())pause("");return START_NOT_STICKY;}
        if("next".equals(action)||"previous".equals(action)){skip("next".equals(action)?1:-1);return START_NOT_STICKY;}
        if(!"play".equals(action)&&!"media".equals(action)&&!"open".equals(action))return START_NOT_STICKY;
        startForeground(NOTIFICATION,notification());
        try{
            String target=library.current();JSONObject at=null;
            boolean stopAtEnd=false;
            if("open".equals(action)){
                target=intent.getStringExtra("book");
                at=AudioLibrary.anchor(intent.getIntExtra("chapter",0),intent.getIntExtra("p",0),intent.getIntExtra("offset",0));
                stopAtEnd=intent.getBooleanExtra("limit",false);
            }else if("media".equals(action)){
                String media=intent.getStringExtra("media");
                if(!AudioLibrary.RESUME.equals(media)){
                    String[] parts=media==null?new String[0]:media.split("/");
                    if(parts.length==2&&parts[0].equals("continue"))target=parts[1];
                    else if((parts.length==3||parts.length==4)&&parts[0].equals("chapter")){target=parts[1];at=AudioLibrary.anchor(Integer.parseInt(parts[2]),parts.length==4?Integer.parseInt(parts[3]):0,0);}
                    else throw new IllegalArgumentException();
                }
            }else{
                if(active())return START_NOT_STICKY;
                if(!id.isEmpty()){target=id;at=AudioLibrary.anchor(chapter,p,offset);}
                stopAtEnd=library.prefs.getBoolean("limit",false);
            }
            if(!AudioLibrary.valid(target))throw new IllegalArgumentException();
            if(at==null)at=library.place(target);
            open(target,at,stopAtEnd);
        }catch(Exception ex){pause("Не удалось открыть книгу");}
        return START_NOT_STICKY;
    }
    private void open(String target,JSONObject at,boolean stopAtEnd){
        int request=++generation;
        voice.stop();
        boolean cached=book!=null&&target.equals(id);
        if(!cached){book=null;contents=Collections.emptyList();title="Bookfold";author="";heading="Открываем книгу";}
        sectionIndex=-1;
        id=target;chapter=Math.max(0,at.optInt("chapter"));p=Math.max(0,at.optInt("p"));offset=Math.max(0,at.optInt("offset"));end=offset;
        limit=stopAtEnd;library.prefs.edit().putBoolean("limit",limit).apply();
        state="loading";error="";session.setActive(true);publish(false);updateNotification();
        if(cached){nextPhrase();return;}
        io.execute(()->{
            try{JSONObject loaded=library.read(target);List<AudioLibrary.Section> toc=AudioLibrary.sections(loaded);main.post(()->{
                if(request!=generation)return;
                book=loaded;contents=toc;title=book.optString("title");author=book.optString("author");nextPhrase();
            });}catch(Exception ex){main.post(()->{if(request==generation)pause("Не удалось прочитать книгу");});}
        });
    }
    private void nextPhrase(){
        try{
            JSONArray chapters=book.getJSONArray("chapters");
            chapter=Math.min(chapter,chapters.length()-1);
            while(true){
                JSONObject section=chapters.getJSONObject(chapter);
                JSONArray lines=section.getJSONArray("paragraphs");
                if(p>=lines.length()){
                    if(chapter+1>=chapters.length()){finishPlayback();return;}
                    chapter++;p=0;offset=0;
                    continue;
                }
                String line=lines.getString(p);
                if(offset>=line.length()){p++;offset=0;continue;}
                end=Math.min(line.length(),offset+900);
                Matcher m=SENTENCE.matcher(line).region(offset,end);
                if(m.find())end=m.end();
                else if(end<line.length()){int space=line.lastIndexOf(' ',end);if(space>offset)end=space+1;}
                String text=line.substring(offset,end);
                if(text.trim().isEmpty()){offset=end;continue;}
                int previous=sectionIndex;
                if(sectionIndex<0)sectionIndex=0;
                while(sectionIndex+1<contents.size()){
                    AudioLibrary.Section next=contents.get(sectionIndex+1);
                    if(next.chapter>chapter||next.chapter==chapter&&next.p>p)break;
                    sectionIndex++;
                }
                String nextHeading=contents.get(sectionIndex).title;
                if(limit&&previous>=0&&sectionIndex!=previous){end=offset;heading=nextHeading;pause("");return;}
                boolean changed=previous!=sectionIndex||!nextHeading.equals(heading)||!state.equals("playing");
                heading=nextHeading;state="playing";publish(true);
                if(changed){metadata();updateNotification();}
                wake.acquire(10*60*1000L);
                voice.speak(text,++phrase);return;
            }
        }catch(Exception ex){pause("В этой главе не удалось прочитать текст");}
    }
    private void skip(int delta){
        if(book==null)return;
        int target=sectionIndex+delta;
        if(target<0||target>=contents.size())return;
        request("media",contents.get(target).mediaId(id));
    }
    private void metadata(){
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,heading)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,author).putString(MediaMetadata.METADATA_KEY_ALBUM,title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,heading).putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,title)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,sectionIndex>=0&&sectionIndex<contents.size()?contents.get(sectionIndex).mediaId(id):"chapter/"+id+"/"+chapter).build());
    }
    private void transport(){
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_PLAY_FROM_MEDIA_ID|PlaybackState.ACTION_PLAY_FROM_SEARCH|PlaybackState.ACTION_STOP;
        if(active())actions|=PlaybackState.ACTION_PAUSE;
        if(sectionIndex>0)actions|=PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if(sectionIndex>=0&&sectionIndex+1<contents.size())actions|=PlaybackState.ACTION_SKIP_TO_NEXT;
        int value=state.equals("loading")?PlaybackState.STATE_BUFFERING:state.equals("playing")?PlaybackState.STATE_PLAYING:state.equals("paused")?PlaybackState.STATE_PAUSED:PlaybackState.STATE_STOPPED;
        PlaybackState.Builder b=new PlaybackState.Builder().setActions(actions).setState(error.isEmpty()?value:PlaybackState.STATE_ERROR,PlaybackState.PLAYBACK_POSITION_UNKNOWN,active()?1f:0f);
        if(!error.isEmpty())b.setErrorMessage(error);
        session.setPlaybackState(b.build());
    }
    private void publish(boolean save){
        JSONObject at=AudioLibrary.anchor(chapter,p,offset);
        if(save)library.save(id,at);
        try{at.put("book",id).put("end",end).put("state",state).put("message",error).put("title",title).put("revision",System.nanoTime());}catch(JSONException ignored){}
        library.prefs.edit().putString("state",at.toString()).apply();
        transport();
    }
    private Notification notification(){
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_audio_book)
            .setContentTitle(title).setContentText(error.isEmpty()?heading:error).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(session.getController().getSessionActivity()).setDeleteIntent(control("stop"))
            .addAction(android.R.drawable.ic_media_previous,"Предыдущая глава",control("previous"))
            .addAction(active()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,active()?"Пауза":"Продолжить",control(active()?"pause":"play"))
            .addAction(android.R.drawable.ic_media_next,"Следующая глава",control("next"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Остановить",control("stop"))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        return b.build();
    }
    private void updateNotification(){notifications.notify(NOTIFICATION,notification());}
    private void silence(){++generation;++phrase;voice.stop();if(wake.isHeld())wake.release();}
    private void pause(String reason){
        silence();state="paused";error=reason;publish(book!=null);metadata();updateNotification();
        stopForeground(STOP_FOREGROUND_DETACH);
    }
    private void finishPlayback(){
        silence();state="stopped";error="";publish(false);session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);notifications.cancel(NOTIFICATION);stopSelf();
    }
    @Override public void onDestroy(){
        getSharedPreferences("books",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(catalogChange);
        unregisterReceiver(unplug);silence();voice.close();
        if(active()){state="paused";publish(false);}
        session.release();io.shutdownNow();super.onDestroy();
        running=false;
    }
}

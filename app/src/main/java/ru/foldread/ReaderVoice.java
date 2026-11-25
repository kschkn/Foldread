package ru.foldread;

import android.content.Context;
import android.media.*;
import android.speech.tts.*;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/** Synthesize locally, then play under this app's UID so Android routes media keys to our session. */
final class ReaderVoice {
    private final Context context;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private final BiConsumer<Integer,String> callback;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final java.io.File folder;
    private TextToSpeech engine;
    private MediaPlayer player;
    private java.io.File rendered;
    private String pending,language="";
    private int current=-1;
    private boolean ready,focused,closed;

    ReaderVoice(Context context,BiConsumer<Integer,String> callback){
        this.context=context.getApplicationContext();this.callback=callback;
        audio=context.getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(change<0&&focused)interrupt();},main).build();
        folder=new java.io.File(context.getCacheDir(),"speech");
        java.io.File[] leftovers=folder.listFiles();
        if(leftovers!=null)for(java.io.File file:leftovers)file.delete();
    }
    void speak(String text,int id){
        if(closed)return;
        current=id;pending=text;
        if(engine==null){
            engine=new TextToSpeech(context,status->main.post(()->{
                if(closed)return;
                if(status!=TextToSpeech.SUCCESS){fail();return;}
                ready=true;
                engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String id){}
                    @Override public void onDone(String id){main.post(()->{if(id.equals(String.valueOf(current)))playRendered();});}
                    @Override public void onError(String id){main.post(()->{if(id.equals(String.valueOf(current)))fail();});}
                });
                synthesize();
            }));
        }else if(ready)synthesize();
    }
    private boolean selectVoice(String lang){
        if(language.equals(lang))return true;
        Set<Voice> voices=engine.getVoices();Voice best=null;
        if(voices!=null)for(Voice v:voices){
            if(v.isNetworkConnectionRequired()||!lang.equals(v.getLocale().getLanguage()))continue;
            if(v.getFeatures()!=null&&v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))continue;
            if(best==null||best.getQuality()<v.getQuality())best=v;
        }
        if(best==null||engine.setVoice(best)==TextToSpeech.ERROR)return false;
        language=lang;return true;
    }
    private void synthesize(){
        if(pending==null||closed)return;
        String lang=pending.matches("(?s).*[А-Яа-яЁё].*")?"ru":Locale.getDefault().getLanguage();
        if(!selectVoice(lang)){fail();return;}
        if(!focused)focused=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if(!focused){interrupt();return;}
        try{
            if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException();
            releaseAudio();
            rendered=java.io.File.createTempFile("phrase-",".wav",folder);
            String phrase=pending;pending=null;
            if(engine.synthesizeToFile(phrase,new android.os.Bundle(),rendered,String.valueOf(current))==TextToSpeech.ERROR)fail();
        }catch(java.io.IOException ex){fail();}
    }
    private void playRendered(){
        if(closed||rendered==null)return;
        int token=current;
        try{
            player=new MediaPlayer();player.setAudioAttributes(attributes);player.setDataSource(rendered.getPath());
            player.setOnPreparedListener(mp->{if(mp==player&&current==token)mp.start();});
            player.setOnCompletionListener(mp->{if(mp==player&&current==token){releaseAudio();callback.accept(token,"done");}});
            player.setOnErrorListener((mp,what,extra)->{if(mp==player&&current==token)fail();return true;});
            player.prepareAsync();
        }catch(java.io.IOException|RuntimeException ex){fail();}
    }
    private void releaseAudio(){
        if(player!=null){player.release();player=null;}
        if(rendered!=null){rendered.delete();rendered=null;}
    }
    private void fail(){
        int id=current;stop();
        if(engine!=null&&!ready){engine.shutdown();engine=null;language="";}
        if(id>=0)callback.accept(id,"unavailable");
    }
    void interrupt(){int id=current;stop();if(id>=0)callback.accept(id,"paused");}
    void stop(){
        pending=null;current=-1;
        if(engine!=null)engine.stop();
        releaseAudio();
        if(focused){audio.abandonAudioFocusRequest(focus);focused=false;}
    }
    void close(){closed=true;stop();if(engine!=null){engine.shutdown();engine=null;}}
}

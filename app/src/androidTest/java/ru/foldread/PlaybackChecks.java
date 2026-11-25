package ru.foldread;

import android.app.*;
import android.content.*;
import android.media.browse.MediaBrowser;
import android.media.session.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Run on a test device with a Russian offline TTS voice. Exercises the car media contract. */
public class PlaybackChecks extends Instrumentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private MediaBrowser browser;
    private MediaController controller;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void require(boolean passed,String text){if(!passed)throw new AssertionError(text);}
    private void checkRoundIcon(Context context)throws Exception{
        int size=144; // A 48 dp launcher icon at 3x density.
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
        android.graphics.Path circle=new android.graphics.Path();
        circle.addCircle(72,72,72,android.graphics.Path.Direction.CW);
        canvas.clipPath(circle);
        android.graphics.drawable.Drawable icon=context.getPackageManager().getApplicationIcon(context.getPackageName());
        icon.setBounds(0,0,size,size);icon.draw(canvas);
        int cream=0,red=0;
        for(int x=0;x<size;x++)for(int y=0;y<size;y++){
            int color=bitmap.getPixel(x,y),r=android.graphics.Color.red(color),g=android.graphics.Color.green(color);
            if(r<125||android.graphics.Color.alpha(color)<250)continue;
            if(r>g*1.6)red++;else if(r>170&&g>150)cream++;
            require(Math.hypot(x+.5-72,y+.5-72)<69,"The device or ribbon clips the circular mask");
        }
        require(cream>2000 && red>8,"Phone and bookmark must be visible in the launcher");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),"qa-circle.png"))){
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        }
    }
    private List<MediaBrowser.MediaItem> children(String parent)throws Exception{
        CompletableFuture<List<MediaBrowser.MediaItem>> result=new CompletableFuture<>();
        main.post(()->browser.subscribe(parent,new MediaBrowser.SubscriptionCallback(){
            @Override public void onChildrenLoaded(String id,List<MediaBrowser.MediaItem> list){result.complete(list);browser.unsubscribe(id);}
            @Override public void onError(String id){result.completeExceptionally(new AssertionError(id));}
        }));
        return result.get(12,TimeUnit.SECONDS);
    }
    private void await(int state,String chapter)throws Exception{
        for(int i=0;i<100;i++){
            PlaybackState p=controller.getPlaybackState();
            if(p!=null && p.getState()==PlaybackState.STATE_ERROR)throw new AssertionError(p.getErrorMessage());
            if(p!=null && p.getState()==state && (chapter==null || controller.getMetadata()!=null &&
                chapter.equals(controller.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID))))return;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing playback state "+state+" for "+chapter);
    }
    @Override public void onStart(){
        Bundle report=new Bundle();boolean passed=false;
        try{
            Context context=getTargetContext();
            checkRoundIcon(context);
            String auto="com.google.android.projection.gearhead";
            try{
                int uid=context.getPackageManager().getApplicationInfo(auto,0).uid;
                require(BookPlaybackService.allowedBrowser(context,auto,uid),"Android Auto host rejected");
            }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            CompletableFuture<Boolean> connected=new CompletableFuture<>();
            main.post(()->{
                browser=new MediaBrowser(context,new ComponentName(context,BookPlaybackService.class),new MediaBrowser.ConnectionCallback(){
                    @Override public void onConnected(){controller=new MediaController(context,browser.getSessionToken());connected.complete(true);}
                    @Override public void onConnectionFailed(){connected.complete(false);}
                },null);
                browser.connect();
            });
            require(connected.get(12,TimeUnit.SECONDS),"Media browser could not connect");
            List<MediaBrowser.MediaItem> root=children(browser.getRoot());
            require(root.size()==2 && root.stream().allMatch(MediaBrowser.MediaItem::isBrowsable),"Root tabs must be browsable");
            require(children("recent").get(0).isPlayable(),"Resume item missing");
            require(children("shelf").stream().anyMatch(b->"book/sails".equals(b.getMediaId())),"Book missing");
            List<MediaBrowser.MediaItem> chapters=children("book/sails");
            require(chapters.size()>3&&chapters.get(1).isPlayable(),"Chapter list missing");
            MediaController.TransportControls controls=controller.getTransportControls();
            controls.playFromMediaId(chapters.get(1).getMediaId(),null);
            await(PlaybackState.STATE_PLAYING,"chapter/sails/0");
            AudioLibrary library=new AudioLibrary(context);
            String start=library.place("sails").toString();
            for(int i=0;i<120 && start.equals(library.place("sails").toString());i++)Thread.sleep(250);
            require(!start.equals(library.place("sails").toString()),"Speech did not advance without a reader activity");
            controls.pause();await(PlaybackState.STATE_PAUSED,null);
            controls.play();await(PlaybackState.STATE_PLAYING,null);
            controls.skipToNext();await(PlaybackState.STATE_PLAYING,"chapter/sails/1");
            controls.skipToPrevious();await(PlaybackState.STATE_PLAYING,"chapter/sails/0");
            controls.stop();await(PlaybackState.STATE_STOPPED,null);
            passed=true;report.putString("result","Catalog, offline speech without Activity, pause, resume, both chapter skips and stop passed");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{if(browser!=null)runOnMainSync(()->browser.disconnect());}
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
    }
}

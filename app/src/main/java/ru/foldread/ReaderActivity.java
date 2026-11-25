package ru.foldread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity implements SensorEventListener {
    private static final String HOST = "foldread.invalid";
    private static final int PICK_BOOK = 40;
    private static final ExecutorService imports = Executors.newSingleThreadExecutor();
    private WebView web;
    private AlertDialog archiveDialog;
    private File pendingCard;
    private File backupSnapshot;
    private static final int SAVE_BACKUP=42, LOAD_BACKUP=43, PICK_DICTIONARY=44;
    private AudioLibrary audioLibrary;
    private ReadingClock readingClock;
    private boolean readingVisible;
    private volatile boolean openWidgetBookmark;
    private volatile boolean showStats;
    private ReaderDictionary dictionary;
    private boolean showAudio;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener audioUpdates=(prefs,key)->{
        if(this.resumed && "state".equals(key))callJs("window.syncSpeech?.()");
    };
    private long lastAngleAt;
    private float lastAngle=Float.NaN,tableFraction=.5f;
    private boolean inTabletop;
    private volatile int foldAxis=-1;
    private androidx.window.java.layout.WindowInfoTrackerCallbackAdapter windowTracker;
    private final androidx.core.util.Consumer<androidx.window.layout.WindowLayoutInfo> posture=info->{
        boolean tabletop=false;float fraction=.5f;int axis=-1;
        for(androidx.window.layout.DisplayFeature feature:info.getDisplayFeatures()){
            if(feature instanceof androidx.window.layout.FoldingFeature){
                androidx.window.layout.FoldingFeature f=(androidx.window.layout.FoldingFeature)feature;
                axis=f.getOrientation()==androidx.window.layout.FoldingFeature.Orientation.VERTICAL?0:1;
                tabletop=f.getOrientation()==androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL && f.getState()==androidx.window.layout.FoldingFeature.State.HALF_OPENED;
                if(tabletop && web.getHeight()>0)fraction=(float)f.getBounds().centerY()/web.getHeight();
            }
        }
        inTabletop=tabletop;tableFraction=fraction;
        if(foldAxis!=axis){foldAxis=axis;callJs("window.foldAxisChanged?.()");}
        callJs("window.tabletopChanged?.("+tabletop+","+fraction+")");
    };
    private static final int SAVE_CARD = 41;
    private SensorManager sensors;
    private Sensor hinge;
    private final FoldGesture fold = new FoldGesture();
    private boolean resumed, wantsFold, listening;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioLibrary=new AudioLibrary(this);
        readingClock=new ReadingClock(this,audioLibrary.prefs);
        dictionary=new ReaderDictionary(getAssets());
        showAudio="ru.foldread.SHOW_AUDIO".equals(getIntent().getAction());
        openWidgetBookmark=ReadingWidgets.OPEN_MARK.equals(getIntent().getAction());
        showStats=state==null&&ReadingWidgets.OPEN_STATS.equals(getIntent().getAction());
        windowTracker=new androidx.window.java.layout.WindowInfoTrackerCallbackAdapter(androidx.window.layout.WindowInfoTracker.getOrCreate(this));
        if(state!=null && state.containsKey("backupSnapshot"))backupSnapshot=new File(getCacheDir(),state.getString("backupSnapshot"));
        if (state != null && state.getString("pendingCard") != null)
            pendingCard = new File(new File(getCacheDir(),"cards"),state.getString("pendingCard"));
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        web = new WebView(this);
        web.setBackgroundColor(0xff233b36);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setTextZoom(100);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.addJavascriptInterface(new Library(), "Library");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String path = uri.getPath();
                if (HOST.equals(uri.getHost()) && path != null) {
                    try {
                        if ("/bookfold-icon.png".equals(path))
                            return response("image/png",getResources().openRawResource(R.drawable.bookfold_device));
                        if (path.matches("/art/[0-9a-f]{64}-[0-9]+\\.jpg"))
                            return response("image/jpeg", new FileInputStream(new File(getFilesDir(),path.substring(5))));
                        if (path.matches("/books/[0-9a-f]{64}\\.json"))
                            return response("application/json", new FileInputStream(new File(getFilesDir(), path.substring(7))));
                        if (path.matches("/[a-zA-Z0-9.-]+")) {
                            String mime = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript"
                                    : path.endsWith(".json") ? "application/json" : path.endsWith(".jpg") ? "image/jpeg" : path.endsWith(".ttf") ? "font/ttf" : "text/html";
                            return response(mime, getAssets().open(path.substring(1)));
                        }
                    } catch (IOException ignored) { }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
            }
        });
        setContentView(web);
        immersive();
        web.loadUrl("https://" + HOST + "/index.html");
    }

    private WebResourceResponse response(String mime, InputStream data) {
        return new WebResourceResponse(mime, mime.startsWith("font/") ? null : "UTF-8", data);
    }

    private final class Library {
        @JavascriptInterface public String appVersion(){
            try{
                android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
                long build=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
                return new JSONObject().put("name",info.versionName).put("build",build).toString();
            }catch(Exception ex){return "{}";}
        }
        @JavascriptInterface public void openProjectLink(String key){
            String url;
            switch(key){
                case "author": url="https://github.com/kschkn";break;
                case "source": url="https://github.com/kschkn/Foldread";break;
                case "release": url="https://github.com/kschkn/Foldread/releases";break;
                case "issues": url="https://github.com/kschkn/Foldread/issues";break;
                case "credits": url="https://github.com/kschkn/Foldread#материалы";break;
                default:return;
            }
            runOnUiThread(()->{
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
                catch(android.content.ActivityNotFoundException ex){callJs("notify('На устройстве не найден браузер')");}
            });
        }
        @JavascriptInterface public int innerAxis(){
            if(foldAxis>=0)return foldAxis;
            if(hinge==null)return -1;
            // On Galaxy book foldables the hinge is vertical in natural rotation, even on a 4:3 landscape panel.
            int rotation=getWindowManager().getDefaultDisplay().getRotation();
            return rotation==android.view.Surface.ROTATION_0 || rotation==android.view.Surface.ROTATION_180?0:1;
        }
        @JavascriptInterface public void refreshPosture(){runOnUiThread(()->callJs("window.tabletopChanged?.("+inTabletop+","+tableFraction+")"));}
        @JavascriptInterface public String definition(String word){return dictionary.lookup(word);}
        @JavascriptInterface public String audioState(){return BookPlaybackService.snapshot(audioLibrary);}
        @JavascriptInterface public String nativePlace(String id){return audioLibrary.place(id).toString();}
        @JavascriptInterface public void bookOrientation(String mode){
            int requested="portrait".equals(mode)?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                "landscape".equals(mode)?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            runOnUiThread(()->{if(!isDestroyed()&&getRequestedOrientation()!=requested)setRequestedOrientation(requested);});
        }
        @JavascriptInterface public void widgetProgress(String id,String title,String cover,String range,int percent){
            ReadingWidgets.saveBook(ReaderActivity.this,id,title,cover,range,percent);
        }
        @JavascriptInterface public void setReadingVisible(boolean visible){
            runOnUiThread(()->{readingVisible=visible;updateReadingClock();});
        }
        @JavascriptInterface public long readingMillis(){return audioLibrary.prefs.getLong("readingMillis",0);}
        @JavascriptInterface public String readingDays(){return ReadingClock.history(audioLibrary.prefs).toString();}
        @JavascriptInterface public boolean takeWidgetStats(){boolean open=showStats;showStats=false;return open;}
        @JavascriptInterface public int weeklyGoal(){return ReadingWidgets.weeklyGoal(ReaderActivity.this);}
        @JavascriptInterface public void widgetBookmark(String json){
            android.content.SharedPreferences prefs=getSharedPreferences("reading-widget",MODE_PRIVATE);
            if(json.equals(prefs.getString("bookmark","")))return;
            prefs.edit().putString("bookmark",json).apply();
            ReadingWidgets.refresh(ReaderActivity.this,ReadingWidgets.Bookmark.class);
        }
        @JavascriptInterface public String takeWidgetBookmark(){
            if(!openWidgetBookmark)return "";
            openWidgetBookmark=false;
            return getSharedPreferences("reading-widget",MODE_PRIVATE).getString("bookmark","");
        }
        @JavascriptInterface public void savePlace(String id,int chapter,int p,int offset){audioLibrary.save(id,AudioLibrary.anchor(chapter,p,offset));}
        @JavascriptInterface public String audioTarget(){
            if(!showAudio)return "";
            showAudio=false;
            String id=AudioLibrary.object(BookPlaybackService.snapshot(audioLibrary)).optString("book");
            return AudioLibrary.valid(id)?id:audioLibrary.current();
        }
        @JavascriptInterface public void playBook(String id,int chapter,int p,int offset,boolean limit){
            if(AudioLibrary.valid(id))runOnUiThread(()->startForegroundService(BookPlaybackService.command(ReaderActivity.this,"open")
                .putExtra("book",id).putExtra("chapter",chapter).putExtra("p",p).putExtra("offset",offset).putExtra("limit",limit)));
        }
        @JavascriptInterface public void audioControl(String action){runOnUiThread(()->{
            if("play".equals(action))startForegroundService(BookPlaybackService.command(ReaderActivity.this,action));
            if("pause".equals(action)||"stop".equals(action))startService(BookPlaybackService.command(ReaderActivity.this,action));
        });}
        @JavascriptInterface public void chooseBackup(boolean save){runOnUiThread(()->{
            if(save){
                web.evaluateJavascript("backupState()",result->{
                    try{
                        String state=(String)new org.json.JSONTokener(result).nextValue();
                        backupSnapshot=File.createTempFile("reading-",".json",getCacheDir());
                        Files.write(backupSnapshot.toPath(),state.getBytes(StandardCharsets.UTF_8));
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Bookfold.zip"),SAVE_BACKUP);
                    }catch(Exception ex){callJs("notify('Не удалось подготовить копию')");}
                });
            }else openDocument(LOAD_BACKUP);
        });}
        @JavascriptInterface public String pendingRestore(){return getSharedPreferences("books",MODE_PRIVATE).getString("restoreState","");}
        @JavascriptInterface public boolean finishRestore(boolean accepted){
            android.content.SharedPreferences prefs=getSharedPreferences("books",MODE_PRIVATE);
            android.content.SharedPreferences.Editor edit=prefs.edit().remove("restoreState").remove("restoreAdded");
            if(accepted)try{
                JSONObject values=new JSONObject(prefs.getString("restoreState","{}"));
                android.content.SharedPreferences.Editor restored=audioLibrary.prefs.edit().remove("state");
                java.util.Iterator<String> keys=values.keys();
                while(keys.hasNext()){String k=keys.next();if(k.startsWith("pos.") && AudioLibrary.valid(k.substring(4)))restored.putString(k,values.getString(k));}
                if(values.has("active")){String id=(String)new org.json.JSONTokener(values.getString("active")).nextValue();if(AudioLibrary.valid(id))restored.putString("current",id);}
                if(values.has("readingMillis"))restored.putLong("readingMillis",values.getLong("readingMillis"));
                if(values.has("readingDays")){
                    JSONObject days=new JSONObject(values.getString("readingDays"));
                    for(String k:audioLibrary.prefs.getAll().keySet())if(k.startsWith("day."))restored.remove(k);
                    for(java.util.Iterator<String> it=days.keys();it.hasNext();){String day=it.next();restored.putLong("day."+day,days.getLong(day));}
                }
                if(values.has("weeklyGoal"))restored.putInt("weeklyGoal",values.getInt("weeklyGoal"));
                if(!restored.commit())return false;
                ReadingWidgets.refresh(ReaderActivity.this,ReadingWidgets.Time.class);
                ReadingWidgets.refresh(ReaderActivity.this,ReadingWidgets.Week.class);
            }catch(Exception ex){return false;}
            if(!accepted)try{
                JSONObject catalog=new JSONObject(prefs.getString("catalog","{}"));
                for(String id:prefs.getStringSet("restoreAdded",java.util.Collections.emptySet()))catalog.remove(id);
                edit.putString("catalog",catalog.toString());
            }catch(org.json.JSONException ex){return false;}
            return edit.commit();
        }
        @JavascriptInterface public void pickDictionary(){runOnUiThread(()->openDocument(PICK_DICTIONARY));}
        @JavascriptInterface public void exportCard(String base64, boolean share) {
            if (base64.length() > 8 * 1024 * 1024) { callJs("notify('Открытка слишком велика')"); return; }
            imports.execute(() -> {
                try {
                    File folder = new File(getCacheDir(),"cards");
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException();
                    File card = File.createTempFile("Bookfold-", ".png", folder);
                    Files.write(card.toPath(),android.util.Base64.decode(base64,android.util.Base64.DEFAULT));
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (share) {
                            Uri uri = androidx.core.content.FileProvider.getUriForFile(ReaderActivity.this,"ru.foldread.cards",card);
                            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                                    .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            send.setClipData(android.content.ClipData.newRawUri("Bookfold",uri));
                            startActivity(Intent.createChooser(send,"Поделиться цитатой"));
                        } else {
                            pendingCard=card;
                            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png")
                                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Цитата Bookfold.png"),SAVE_CARD);
                        }
                    });
                } catch (Exception e) {callJs("notify('Не удалось создать открытку')");}
            });
        }
        @JavascriptInterface public int systemBrightness() {
            int value = android.provider.Settings.System.getInt(getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            return Math.max(2, Math.min(100, Math.round(100f * value / 255)));
        }
        @JavascriptInterface public void setReaderBrightness(int percent) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                attrs.screenBrightness = percent < 0 ? -1f : Math.max(2, Math.min(100, percent)) / 100f;
                getWindow().setAttributes(attrs);
            });
        }
        @JavascriptInterface public boolean hasHinge() { return hinge != null; }
        @JavascriptInterface public void enableFold(boolean enabled) {
            runOnUiThread(() -> { wantsFold = enabled; updateSensor(); });
        }
        @JavascriptInterface public String catalog() {
            return getSharedPreferences("books", MODE_PRIVATE).getString("catalog", "{}");
        }
        @JavascriptInterface public void choose() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*"); // FB2 MIME types vary between document providers.
                startActivityForResult(intent, PICK_BOOK);
            });
        }
    }

    private void openDocument(int request){
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),request);
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
        updateReadingClock();
    }

    private void updateSensor() {
        boolean needed = resumed && wantsFold && hinge != null;
        if (needed == listening) return;
        fold.reset();lastAngle=Float.NaN;
        if (needed) listening = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
        else { sensors.unregisterListener(this); listening = false; }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        long time=event.timestamp/1_000_000;
        float angle=event.values[0];
        boolean changedBand=(angle>=163)!=(lastAngle>=163)||(angle<145)!=(lastAngle<145);
        if(listening && Float.isFinite(angle) && (Float.isNaN(lastAngle)||changedBand||time-lastAngleAt>=80)){
            lastAngle=angle;lastAngleAt=time;callJs("window.hingeChanged?.("+angle+")");
        }
        if (listening && fold.sample(event.values[0], event.timestamp / 1_000_000)) {
            android.util.Log.d("Bookfold", "Hinge page-turn gesture");
            callJs("window.foldTurn?.()");
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override protected void onStart(){super.onStart();windowTracker.addWindowLayoutInfoListener(this,androidx.core.content.ContextCompat.getMainExecutor(this),posture);}
    @Override public void onConfigurationChanged(android.content.res.Configuration next){
        super.onConfigurationChanged(next);foldAxis=-1;callJs("window.foldAxisChanged?.()");
    }
    @Override protected void onStop(){windowTracker.removeWindowLayoutInfoListener(posture);super.onStop();}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);showAudio="ru.foldread.SHOW_AUDIO".equals(intent.getAction());
        openWidgetBookmark=ReadingWidgets.OPEN_MARK.equals(intent.getAction());
        showStats=ReadingWidgets.OPEN_STATS.equals(intent.getAction());
        if(showStats)callJs("if(typeof book!=='undefined' && book)openWidgetStats()");
        else if(openWidgetBookmark)callJs("if(typeof book!=='undefined' && book && !openWidgetMark())openBook(read('active',book.id))");
        else if(ReadingWidgets.OPEN_READER.equals(intent.getAction()))callJs("if(typeof book!=='undefined' && book)openBook(read('active',book.id))");
        else callJs("window.audioOpened?.()");
    }
    private void updateReadingClock(){if(readingClock!=null)readingClock.active(resumed&&hasWindowFocus()&&readingVisible);}
    @Override protected void onResume() { super.onResume(); resumed = true; updateSensor();
        updateReadingClock();audioLibrary.prefs.registerOnSharedPreferenceChangeListener(audioUpdates);callJs("window.audioOpened?.()");}
    @Override protected void onPause() {
        resumed = false;
        updateReadingClock();
        audioLibrary.prefs.unregisterOnSharedPreferenceChangeListener(audioUpdates);
        updateSensor();
        super.onPause();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==SAVE_BACKUP || request==LOAD_BACKUP || request==PICK_DICTIONARY){
            File snapshot=backupSnapshot;if(request==SAVE_BACKUP)backupSnapshot=null;
            if(result!=RESULT_OK||data==null||data.getData()==null){if(request==SAVE_BACKUP&&snapshot!=null)snapshot.delete();return;}
            Uri uri=data.getData();
            imports.execute(()->{
                try{
                    LibraryBackup backup=new LibraryBackup(this);
                    if(request==SAVE_BACKUP){
                        try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                            if(out==null||snapshot==null)throw new IOException();backup.exportTo(out,snapshot);
                        }callJs("notify('Копия библиотеки сохранена')");
                    }else try(InputStream in=getContentResolver().openInputStream(uri)){
                        if(in==null)throw new IOException();
                        if(request==LOAD_BACKUP){backup.restore(in);callJs("window.resumeRestore?.()");}
                        else{
                            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                            while((n=in.read(buf))!=-1){if(bytes.size()+n>3*1024*1024)throw new IOException();bytes.write(buf,0,n);}
                            callJs("window.dictionaryLoaded("+JSONObject.quote(bytes.toString("UTF-8"))+")");
                        }
                    }
                }catch(Exception ex){callJs("notify('Не удалось прочитать или записать файл. Проверьте формат и доступное место.')");}
                finally{if(request==SAVE_BACKUP&&snapshot!=null)snapshot.delete();}
            });return;
        }
        if(request==SAVE_CARD && pendingCard!=null) {
            File card=pendingCard;pendingCard=null;
            if(result!=RESULT_OK || data==null || data.getData()==null){card.delete();return;}
            Uri target=data.getData();
            imports.execute(()->{
                try(java.io.OutputStream out=getContentResolver().openOutputStream(target)){
                    if(out==null)throw new IOException();
                    Files.copy(card.toPath(),out);
                    callJs("notify('Открытка сохранена')");
                }catch(IOException ex){callJs("notify('Не удалось сохранить открытку')");}
                finally {card.delete();}
            });
            return;
        }
        if (request != PICK_BOOK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        callJs("window.importStarted?.()");
        imports.execute(() -> importBook(uri));
    }

    private void importBook(Uri uri) {
        File temp = null;
        try {
            String name = "Книга";
            try (Cursor info = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (info != null && info.moveToFirst() && !info.isNull(0)) name = info.getString(0);
            }
            temp = File.createTempFile("import-", ".book", getCacheDir());
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IOException("Не удалось прочитать файл.");
                int n, total = 0;
                byte[] buffer = new byte[32768];
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > BookArchive.MAX_CONTAINER) throw new IOException("Файл больше 512 МиБ.");
                    out.write(buffer, 0, n);
                }
            }
            List<BookArchive.Member> members = BookArchive.books(temp);
            if (members == null) storeBook(temp, name);
            else if (members.size() == 1) importMember(temp, members.get(0));
            else {
                File archive = temp;
                runOnUiThread(() -> chooseMember(archive, members));
                temp = null; // The selection dialog now owns the staged archive.
            }
        } catch (Exception | StackOverflowError error) {
            importError(error);
        } finally {
            if (temp != null) temp.delete();
        }
    }

    private void chooseMember(File archive, List<BookArchive.Member> members) {
        if (isDestroyed() || isFinishing()) { archive.delete(); return; }
        String[] paths = new String[members.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = members.get(i).path;
        archiveDialog = new AlertDialog.Builder(this).setTitle("Выберите книгу из архива")
                .setItems(paths, (dialog, which) -> {
                    archiveDialog = null;
                    imports.execute(() -> {
                        try { importMember(archive, members.get(which)); }
                        catch (Exception | StackOverflowError error) { importError(error); }
                        finally { archive.delete(); }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null).create();
        archiveDialog.setOnDismissListener(dialog -> {
            if (dialog == archiveDialog) {
                archiveDialog = null;
                archive.delete();
                callJs("window.importCancelled()");
            }
        });
        archiveDialog.show();
    }

    private void importMember(File archive, BookArchive.Member member) throws Exception {
        File inner = File.createTempFile("member-", ".book", getCacheDir());
        try {
            BookArchive.extract(archive, member, inner);
            storeBook(inner, member.path.substring(member.path.lastIndexOf('/') + 1));
        } finally { inner.delete(); }
    }

    private void storeBook(File temp, String name) throws Exception {
        if (temp.length() > BookImporter.MAX_FILE) throw new IOException("Книга больше 64 МиБ.");
        File staged = null;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(temp)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            StringBuilder digest = new StringBuilder();
            for (byte b : hash.digest()) digest.append(String.format(Locale.ROOT, "%02x", b));
            String id = digest.toString();
            android.content.SharedPreferences prefs = getSharedPreferences("books", MODE_PRIVATE);
            JSONObject catalog = new JSONObject(prefs.getString("catalog", "{}"));
            File bookFile = new File(getFilesDir(), id + ".json");
            JSONObject metadata = catalog.optJSONObject(id);
            if (!bookFile.isFile() || metadata == null || metadata.optInt("schema") < BookImporter.SCHEMA) {
                JSONObject book = BookImporter.read(temp, name, id, getFilesDir());
                staged = File.createTempFile("book-", ".tmp", getFilesDir());
                try (FileOutputStream out = new FileOutputStream(staged)) {
                    out.write(book.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                Files.move(staged.toPath(), bookFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                book.remove("chapters");
                book.remove("notes");
                book.remove("images");
                catalog.put(id, book);
                if (!prefs.edit().putString("catalog", catalog.toString()).commit()) throw new IOException("Не удалось сохранить книгу.");
            }
            callJs("window.bookImported(" + JSONObject.quote(id) + ")");
        } finally {
            if (staged != null) staged.delete();
        }
    }

    private void importError(Throwable error) {
        String message = error instanceof IOException && error.getMessage() != null
                ? error.getMessage() : "Не удалось прочитать книгу. Выберите FB2, EPUB или архив с книгой.";
        if (error instanceof me.zhanghai.android.libarchive.ArchiveException)
            message = "Не удалось распаковать архив. Проверьте, что он целый и без пароля.";
        callJs("window.importFailed(" + JSONObject.quote(message) + ")");
    }

    @Override protected void onDestroy() {
        if (archiveDialog != null) archiveDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if(backupSnapshot!=null)out.putString("backupSnapshot",backupSnapshot.getName());
        if(pendingCard!=null) out.putString("pendingCard",pendingCard.getName());
    }

    private void callJs(String code) {
        runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript(code, null); });
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.handleBack?.() || false", result -> {
            if (!"true".equals(result)) ReaderActivity.super.onBackPressed();
        });
    }
}

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
    private static final int SAVE_CARD = 41;
    private SensorManager sensors;
    private Sensor hinge;
    private final FoldGesture fold = new FoldGesture();
    private boolean resumed, wantsFold, listening;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        @JavascriptInterface public void exportCard(String base64, boolean share) {
            if (base64.length() > 8 * 1024 * 1024) { callJs("notify('Открытка слишком велика')"); return; }
            imports.execute(() -> {
                try {
                    File folder = new File(getCacheDir(),"cards");
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException();
                    File card = File.createTempFile("Foldread-", ".png", folder);
                    Files.write(card.toPath(),android.util.Base64.decode(base64,android.util.Base64.DEFAULT));
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (share) {
                            Uri uri = androidx.core.content.FileProvider.getUriForFile(ReaderActivity.this,"ru.foldread.cards",card);
                            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                                    .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            send.setClipData(android.content.ClipData.newRawUri("Foldread",uri));
                            startActivity(Intent.createChooser(send,"Поделиться цитатой"));
                        } else {
                            pendingCard=card;
                            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png")
                                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Цитата Foldread.png"),SAVE_CARD);
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
    }

    private void updateSensor() {
        boolean needed = resumed && wantsFold && hinge != null;
        if (needed == listening) return;
        fold.reset();
        if (needed) listening = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
        else { sensors.unregisterListener(this); listening = false; }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (listening && fold.sample(event.values[0], event.timestamp / 1_000_000)) {
            android.util.Log.d("Foldread", "Hinge page-turn gesture");
            callJs("window.foldTurn?.()");
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override protected void onResume() { super.onResume(); resumed = true; updateSensor(); }
    @Override protected void onPause() {
        resumed = false;
        updateSensor();
        super.onPause();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
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
            if (!bookFile.isFile() || metadata == null || metadata.optInt("schema") < 3) {
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

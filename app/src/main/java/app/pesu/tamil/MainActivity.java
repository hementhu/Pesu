package app.pesu.tamil;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pesu: a thin native shell around assets/index.html.
 * The page does the tutoring; this class gives it a real microphone (SpeechRecognizer),
 * a real voice (TextToSpeech, preferring Google's engine for Tamil), durable storage,
 * and plain HTTP(S) calls to whichever model provider the user picks.
 * Callbacks into the page go to window.NativeCb.<name>(...).
 */
public class MainActivity extends Activity {

    private static final String GOOGLE_TTS = "com.google.android.tts";
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private static final int REQ_MIC = 7;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private SpeechRecognizer recognizer;
    private String pendingListenLang;
    private long lastLevelAt;

    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsEngine = "";
    private float speechRate = 0.9f;
    private android.speech.tts.Voice taVoice;   // an installed (offline) Tamil voice, if the engine has one
    private int speakSeq;

    private LocalLlm local;
    private static final int REQ_PICK_MODEL = 8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pesu", MODE_PRIVATE);
        applySystemBars();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setBackgroundColor(isDark() ? Color.parseColor("#0F1512") : Color.parseColor("#F2F4EF"));
        web.addJavascriptInterface(new Bridge(), "Native");
        local = new LocalLlm(this);
        web.addJavascriptInterface(local, "Local");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // The newer (WebResourceRequest) overload's default implementation delegates here.
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternal(url);
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        initTts();
    }

    private boolean openExternal(String url) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applySystemBars() {
        boolean dark = isDark();
        int bg = dark ? Color.parseColor("#0F1512") : Color.parseColor("#F2F4EF");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
        call("onTheme", isDark() ? "dark" : "light");
    }

    // ---------- calling into the page ----------

    void call(final String fn, final Object... args) {
        StringBuilder sb = new StringBuilder("window.NativeCb&&NativeCb.").append(fn).append("&&NativeCb.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object a = args[i];
            if (a == null) sb.append("null");
            else if (a instanceof Number || a instanceof Boolean) sb.append(a.toString());
            else sb.append(JSONObject.quote(a.toString()));
        }
        sb.append(")");
        final String script = sb.toString();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
    }

    // ---------- text to speech ----------

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void initTts() {
        TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        // Utterance ids look like "s<seq>_<index>" and the final one ends in "_last".
                        @Override
                        public void onStart(String id) {
                            if (id.matches("s\\d+_0(_last)?")) call("onSpeak", "start", seqOf(id));
                        }

                        @Override
                        public void onDone(String id) {
                            if (id.endsWith("_last")) call("onSpeak", "done", seqOf(id));
                        }

                        @SuppressWarnings("deprecation")
                        @Override
                        public void onError(String id) {
                            call("onSpeak", "error", seqOf(id));
                        }

                        @Override
                        public void onError(String id, int code) {
                            call("onSpeak", "error", seqOf(id));
                        }

                        @Override
                        public void onStop(String id, boolean interrupted) {
                            if (id.endsWith("_last")) call("onSpeak", "stopped", seqOf(id));
                        }
                    });
                }
                if (ttsReady) pickTamilVoice();
                call("onTtsReady", ttsStatusJson());
            }
        };
        if (installed(GOOGLE_TTS)) {
            ttsEngine = GOOGLE_TTS;
            tts = new TextToSpeech(this, onInit, GOOGLE_TTS);
        } else {
            tts = new TextToSpeech(this, onInit);
            ttsEngine = "default";
        }
    }

    /** Prefer a Tamil voice that is installed on the phone: network voices add a round trip before every sentence. */
    private void pickTamilVoice() {
        taVoice = null;
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices == null) return;
            android.speech.tts.Voice best = null;
            for (android.speech.tts.Voice v : voices) {
                if (v.getLocale() == null || !"ta".equals(v.getLocale().getLanguage())) continue;
                if (v.isNetworkConnectionRequired()) continue;
                java.util.Set<String> f = v.getFeatures();
                if (f != null && f.contains("notInstalled")) continue;
                if (best == null || v.getQuality() > best.getQuality()) best = v;
            }
            taVoice = best;
        } catch (Exception ignored) {
        }
    }

    private String ttsStatusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", ttsReady);
            o.put("engine", ttsEngine);
            o.put("googleInstalled", installed(GOOGLE_TTS));
            boolean ta = false;
            if (ttsReady) {
                int r = tts.isLanguageAvailable(new Locale("ta", "IN"));
                ta = r >= TextToSpeech.LANG_AVAILABLE;
            }
            o.put("tamil", ta);
            o.put("tamilOffline", taVoice != null);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static int seqOf(String id) {
        try {
            int u = id.indexOf('_');
            return Integer.parseInt(id.substring(1, u));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Speaks [{text, lang:"ta"|"en"}...] in order. Returns the sequence number reported in onSpeak callbacks. */
    private int speakSegments(String json) {
        int seq = ++speakSeq;
        if (!ttsReady || tts == null) {
            call("onSpeak", "error", seq);
            return seq;
        }
        try {
            JSONArray arr = new JSONArray(json);
            int n = arr.length();
            if (n == 0) {
                call("onSpeak", "done", seq);
                return seq;
            }
            tts.setSpeechRate(speechRate);
            for (int i = 0; i < n; i++) {
                JSONObject seg = arr.getJSONObject(i);
                String lang = seg.optString("lang", "ta");
                String text = seg.optString("text", "");
                if ("en".equals(lang)) tts.setLanguage(new Locale("en", "IN"));
                else if (taVoice != null) tts.setVoice(taVoice);
                else tts.setLanguage(new Locale("ta", "IN"));
                String id = "s" + seq + "_" + i + (i == n - 1 ? "_last" : "");
                tts.speak(text, i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), id);
            }
        } catch (Exception e) {
            call("onSpeak", "error", seq);
        }
        return seq;
    }

    // ---------- speech recognition ----------

    private SpeechRecognizer makeRecognizer() {
        // Prefer Google's recognizer: it has the best Tamil model. Samsung phones may default to another one.
        ComponentName chosen = null;
        try {
            List<ResolveInfo> services = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : services) {
                if (ri.serviceInfo != null && GOOGLE_APP.equals(ri.serviceInfo.packageName)) {
                    chosen = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        SpeechRecognizer r = chosen != null
                ? SpeechRecognizer.createSpeechRecognizer(this, chosen)
                : SpeechRecognizer.createSpeechRecognizer(this);
        r.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                call("onListen", "ready", "");
            }

            @Override
            public void onBeginningOfSpeech() {
                call("onListen", "begin", "");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                long now = System.currentTimeMillis();
                if (now - lastLevelAt > 90) {
                    lastLevelAt = now;
                    call("onListen", "level", String.valueOf(rmsdB));
                }
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                call("onListen", "end", "");
            }

            @Override
            public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    destroyRecognizer();
                }
                call("onListen", "error", String.valueOf(error));
            }

            @Override
            public void onResults(Bundle results) {
                call("onListen", "final", first(results));
            }

            @Override
            public void onPartialResults(Bundle partial) {
                String t = first(partial);
                if (t.length() > 0) call("onListen", "partial", t);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        return r;
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0) == null) return "";
        return list.get(0);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private void startListening(String lang) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListenLang = lang;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            call("onListen", "error", "unavailable");
            return;
        }
        if (tts != null) tts.stop();
        if (recognizer == null) recognizer = makeRecognizer();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"en-IN"});
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            destroyRecognizer();
            call("onListen", "error", "5");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String lang = pendingListenLang;
            pendingListenLang = null;
            if (ok && lang != null) startListening(lang);
            else call("onListen", "error", "permission");
        }
    }

    // ---------- storage ----------

    private File stateFile() {
        return new File(getFilesDir(), "state.json");
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ---------- the bridge the page calls ----------

    public class Bridge {
        @JavascriptInterface
        public String loadState() {
            try {
                File f = stateFile();
                if (!f.exists()) return "";
                return readAll(new FileInputStream(f));
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean saveState(String json) {
            try {
                File tmp = new File(getFilesDir(), "state.json.tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                out.write(json.getBytes("UTF-8"));
                out.getFD().sync();
                out.close();
                return tmp.renameTo(stateFile());
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String getSecret(String name) {
            return prefs.getString("secret_" + name, "");
        }

        @JavascriptInterface
        public void setSecret(String name, String value) {
            prefs.edit().putString("secret_" + name, value == null ? "" : value.trim()).apply();
        }

        @JavascriptInterface
        public boolean isDark() {
            return MainActivity.this.isDark();
        }

        /** Generic HTTPS/HTTP call. Answers with NativeCb.onHttp(reqId, status, body); status 0 = network failure. */
        @JavascriptInterface
        public void http(final String reqId, final String method, final String url, final String headersJson, final String body) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int code;
                    String text;
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod(method);
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(120000);
                        JSONObject h = new JSONObject(headersJson == null || headersJson.length() == 0 ? "{}" : headersJson);
                        java.util.Iterator<String> it = h.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            c.setRequestProperty(k, h.getString(k));
                        }
                        if (body != null && body.length() > 0) {
                            c.setDoOutput(true);
                            OutputStream os = c.getOutputStream();
                            os.write(body.getBytes("UTF-8"));
                            os.close();
                        }
                        code = c.getResponseCode();
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        text = in == null ? "" : readAll(in);
                    } catch (Exception e) {
                        code = 0;
                        text = String.valueOf(e.getMessage());
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    call("onHttp", reqId, code, text);
                }
            }).start();
        }

        /**
         * Streaming POST for OpenAI-style server-sent events. Each "data:" line goes to
         * NativeCb.onHttpChunk(reqId, data); the end is NativeCb.onHttpEnd(reqId, status, errorBody).
         */
        @JavascriptInterface
        public void httpStream(final String reqId, final String url, final String headersJson, final String body) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int code = 0;
                    String err = "";
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestMethod("POST");
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(90000);
                        c.setRequestProperty("accept", "text/event-stream");
                        // Compressed streams can be held back until a buffer fills; ask for plain bytes.
                        c.setRequestProperty("Accept-Encoding", "identity");
                        JSONObject h = new JSONObject(headersJson == null || headersJson.length() == 0 ? "{}" : headersJson);
                        java.util.Iterator<String> it = h.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            c.setRequestProperty(k, h.getString(k));
                        }
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(body.getBytes("UTF-8"));
                        os.close();
                        code = c.getResponseCode();
                        if (code >= 400) {
                            InputStream es = c.getErrorStream();
                            err = es == null ? "" : readAll(es);
                        } else {
                            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                            String line;
                            StringBuilder plain = new StringBuilder();
                            boolean sawData = false;
                            while ((line = br.readLine()) != null) {
                                if (line.startsWith("data:")) {
                                    sawData = true;
                                    String data = line.substring(5).trim();
                                    if (data.equals("[DONE]")) break;
                                    call("onHttpChunk", reqId, data);
                                } else if (!sawData) {
                                    plain.append(line).append('\n');
                                }
                            }
                            br.close();
                            // A server that ignored "stream" answers with one plain JSON body instead.
                            if (!sawData && plain.length() > 0) err = plain.toString();
                        }
                    } catch (Exception e) {
                        code = 0;
                        err = String.valueOf(e.getMessage());
                    } finally {
                        if (c != null) c.disconnect();
                    }
                    call("onHttpEnd", reqId, code, err);
                }
            }).start();
        }

        @JavascriptInterface
        public void listen(final String lang) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startListening(lang);
                }
            });
        }

        @JavascriptInterface
        public void stopListening() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (recognizer != null) recognizer.stopListening();
                }
            });
        }

        @JavascriptInterface
        public void cancelListening() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (recognizer != null) recognizer.cancel();
                }
            });
        }

        @JavascriptInterface
        public int speak(String segmentsJson) {
            return speakSegments(segmentsJson);
        }

        @JavascriptInterface
        public void stopSpeaking() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public void setRate(float rate) {
            speechRate = Math.max(0.5f, Math.min(1.5f, rate));
        }

        @JavascriptInterface
        public String ttsStatus() {
            return ttsStatusJson();
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void installTamilVoice() {
            try {
                Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                if (installed(GOOGLE_TTS)) i.setPackage(GOOGLE_TTS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                openTtsSettings();
            }
        }

        @JavascriptInterface
        public void openTtsSettings() {
            try {
                Intent i = new Intent("com.android.settings.TTS_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }

        @JavascriptInterface
        public void getGoogleTts() {
            openExternal("https://play.google.com/store/apps/details?id=com.google.android.tts");
        }

        @JavascriptInterface
        public void reinitTts() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (tts != null) tts.shutdown();
                    } catch (Exception ignored) {
                    }
                    ttsReady = false;
                    initTts();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            openExternal(url);
        }

        /** Let the user pick a model file they downloaded themselves (e.g. in the browser). */
        @JavascriptInterface
        public void pickModelFile() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(i, REQ_PICK_MODEL);
                    } catch (Exception e) {
                        call("onLocal", "error", "Couldn't open the file picker.");
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Pesu", text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ---------- lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        call("onAppResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        call("onAppPause");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MODEL) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) local.importFrom(data.getData());
            else call("onLocal", "cancelled", "");
        }
    }

    @Override
    protected void onDestroy() {
        if (local != null) local.shutdown();
        destroyRecognizer();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.NativeCb&&NativeCb.onBack?NativeCb.onBack():false", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }
}

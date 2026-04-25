package tomi.assoc.gti5800;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-stílusú <strong>chat/completions</strong> kliens, két választható végpontra:
 * <ol>
 *   <li><strong>Felhő</strong> — {@link BuildConfig#AI_CHAT_COMPLETIONS_URL}, modell
 *   {@link BuildConfig#AI_MODEL}, Bearer {@link BuildConfig#AI_API_KEY} ({@code local.properties:
 *   ASSOC_AI_API_KEY})</li>
 *   <li><strong>ASUS / LAN</strong> — ha {@link BuildConfig#AI_ASUS_OLLAMA_NATIVE}: Ollama
 *   <code>POST {AI_ASUS_OLLAMA_BASE}/api/chat</code> (nem a gyakori 404-es <code>/v1/chat/completions</code>).
 *   Ha false: OpenAI-s URL ({@link BuildConfig#AI_ASUS_CHAT_COMPLETIONS_URL}, pl. DeepSeek cloud).
 *   Modell: {@link BuildConfig#AI_ASUS_MODEL}; kulcs {@link BuildConfig#AI_ASUS_API_KEY} (üres = nincs
 *   {@code Authorization}).
 * </ol>
 * <p>
 * <strong>„Memória” (előzmények):</strong> a kliens {@link #history} listában tartja a
 * (user+assistant) üzeneteket. <strong>Modell- vagy szerverváltáskor nem törjük</strong> — a
 * következő kérés ugyanazt a <strong>teljes</strong> <code>messages</code> tömböt kapja, csak
 * más a cél-URL + <code>model</code> mező. (A tényleges „értelmezés” a távoli modell; ha zavaró
 * a másik szerverről jött korábbi válasz, használd az ürítést — új beszédet kezd.)
 * </p>
 */
public class AiChatActivity extends Activity {
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final String PREF_NAME = "assoc_ai_chat";
    private static final String PREF_KEY_BACKEND = "backend_is_asus";
    private static final int URL_MIN = 8;

    private TextView logView;
    private ScrollView logScroll;
    private EditText input;
    private Button sendBtn;
    private Button clearBtn;
    private RadioGroup backendGroup;
    private RadioButton radioCloud;
    private RadioButton radioAsus;
    private final List<Msg> history = new ArrayList<Msg>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final class Msg {
        final String role;
        final String content;

        Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.AI_ENABLED) {
            Toast.makeText(this, "AI csevegés nincs engedélyezve ebben a buildben.", Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        // A manifest: Theme.Black.NoTitleBar — Eclair/DONUTon a requestWindowFeature+theme összeütközhet
        // ("Application does not have a…"), ezért nincs külön requestWindowFeature.
        setContentView(R.layout.activity_ai_chat);
        logView = findViewById(R.id.ai_log);
        logScroll = findViewById(R.id.ai_scroll);
        input = findViewById(R.id.ai_input);
        sendBtn = findViewById(R.id.ai_send);
        clearBtn = findViewById(R.id.ai_clear);
        backendGroup = findViewById(R.id.ai_backend_group);
        radioCloud = findViewById(R.id.ai_backend_cloud);
        radioAsus = findViewById(R.id.ai_backend_asus);

        final boolean hasAsus = hasAsusChatUrl();
        if (!hasAsus) {
            backendGroup.setVisibility(View.GONE);
        } else {
            final SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            final boolean cloudKeyOk =
                    BuildConfig.AI_API_KEY != null && !BuildConfig.AI_API_KEY.isEmpty();
            final boolean asusWanted =
                    p.contains(PREF_KEY_BACKEND)
                            ? p.getBoolean(PREF_KEY_BACKEND, false)
                            : !cloudKeyOk;
            RadioGroup.OnCheckedChangeListener listener =
                    new RadioGroup.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(RadioGroup group, int checkedId) {
                            boolean asus = checkedId == R.id.ai_backend_asus;
                            p.edit().putBoolean(PREF_KEY_BACKEND, asus).apply();
                            appendLog(
                                    (asus ? "→ backend: ASUS / LAN, modell: " : "→ backend: felhő, modell: ")
                                            + (asus ? BuildConfig.AI_ASUS_MODEL : BuildConfig.AI_MODEL) + ".\n"
                                            + "(Az üzenetek megmaradnak; a következő küldéskor ezt a szervert hívjuk.)\n");
                        }
                    };
            backendGroup.setOnCheckedChangeListener(null);
            if (asusWanted) {
                radioAsus.setChecked(true);
            } else {
                radioCloud.setChecked(true);
            }
            backendGroup.setOnCheckedChangeListener(listener);
        }

        final Runnable firstLogs =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!hasAsus) {
                            appendLog(getString(R.string.ai_asus_unconfigured) + "\n\n");
                        }
                        appendLog("Rendszer: " + headerLineForLog(hasAsus) + "\n");
                    }
                };
        if (BuildConfig.GALAXY3_LEGACY) {
            // Első képkocka: ne az onCreate főszálon sokat appendeljünk (Eclair/kevés RAM).
            main.post(firstLogs);
        } else {
            firstLogs.run();
        }
        clearBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        history.clear();
                        appendLog("\n— " + getString(R.string.ai_clear) + " —\n");
                    }
                });
        sendBtn.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        send();
                    }
                });
    }

    private boolean hasAsusChatUrl() {
        if (BuildConfig.AI_ASUS_OLLAMA_NATIVE) {
            String b = BuildConfig.AI_ASUS_OLLAMA_BASE;
            return b != null && b.trim().length() >= URL_MIN;
        }
        String u = BuildConfig.AI_ASUS_CHAT_COMPLETIONS_URL;
        return u != null && u.trim().length() >= URL_MIN;
    }

    private boolean isAsusBackend() {
        if (!hasAsusChatUrl()) {
            return false;
        }
        return radioAsus.isChecked();
    }

    private String headerLineForLog(boolean hasAsus) {
        if (BuildConfig.AI_API_KEY == null || BuildConfig.AI_API_KEY.isEmpty()) {
            return getString(R.string.ai_no_key) + (hasAsus
                    ? " · ASUS: " + BuildConfig.AI_ASUS_MODEL
                    : "");
        }
        if (!hasAsus) {
            return "Kulcs ok · felhő: " + BuildConfig.AI_MODEL;
        }
        return "Kulcs ok · felhő: " + BuildConfig.AI_MODEL + " | ASUS: " + BuildConfig.AI_ASUS_MODEL;
    }

    private void send() {
        boolean asus = isAsusBackend();
        if (!asus) {
            if (BuildConfig.AI_API_KEY == null || BuildConfig.AI_API_KEY.isEmpty()) {
                Toast.makeText(this, R.string.ai_no_key, Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (!hasAsusChatUrl()) {
                Toast.makeText(this, R.string.ai_asus_unconfigured, Toast.LENGTH_LONG).show();
                return;
            }
        }
        String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        history.add(new Msg("user", text));
        appendLog("\n[Te] " + text + "\n");
        sendBtn.setEnabled(false);
        sendBtn.setText(R.string.ai_sending);
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                final String err = doChat();
                                main.post(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                sendBtn.setEnabled(true);
                                                sendBtn.setText(R.string.ai_send);
                                                if (err != null) {
                                                    appendLog("[Hiba] " + err + "\n");
                                                    Toast.makeText(AiChatActivity.this, err, Toast.LENGTH_LONG)
                                                            .show();
                                                }
                                            }
                                        });
                            }
                        })
                .start();
    }

    private org.json.JSONArray buildMessagesJsonArray() throws org.json.JSONException {
        JSONArray msgs = new JSONArray();
        for (Msg m : history) {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            o.put("content", m.content);
            msgs.put(o);
        }
        return msgs;
    }

    private static String stripTrailingSlashes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * Ollama natív: {@code /api/chat} + nem streaming — a {@code /v1/chat/completions} sok hoszton 404
     * (más a route).
     */
    private String doOllamaNativeChat() {
        HttpURLConnection c = null;
        try {
            String base = stripTrailingSlashes(BuildConfig.AI_ASUS_OLLAMA_BASE);
            if (base.length() < URL_MIN) {
                return "ASSOC_ASUS_OLLAMA_BASE nincs beállítva (build: local.properties).";
            }
            String model = BuildConfig.AI_ASUS_MODEL;
            String bearer = BuildConfig.AI_ASUS_API_KEY != null ? BuildConfig.AI_ASUS_API_KEY : "";
            String urlS = base + "/api/chat";
            URL u = new URL(urlS);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearer.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            c.setDoOutput(true);
            JSONObject root = new JSONObject();
            root.put("model", model);
            root.put("messages", buildMessagesJsonArray());
            root.put("stream", false);
            JSONObject options = new JSONObject();
            options.put("temperature", 0.7);
            root.put("options", options);
            byte[] body = utf8Bytes(root.toString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                c.setFixedLengthStreamingMode(body.length);
            } else {
                c.setRequestProperty("Content-Length", Integer.toString(body.length));
            }
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.close();
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? c.getInputStream()
                    : c.getErrorStream();
            if (in == null) {
                return "HTTP " + code + " (Ollama " + urlS + "). Próbáld: ollama pull " + model;
            }
            String resp = readAll(in);
            in.close();
            if (code < 200 || code >= 300) {
                return "HTTP " + code + " (Ollama /api/chat): " + trunc(resp, 500);
            }
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                Object err = json.opt("error");
                if (err != null && err != org.json.JSONObject.NULL) {
                    return "Ollama: " + String.valueOf(err);
                }
            }
            JSONObject message = json.optJSONObject("message");
            if (message == null) {
                return "Nincs message (Ollama): " + trunc(resp, 200);
            }
            String content = message.optString("content", "");
            if (content.isEmpty()) {
                return "Üres Ollama válasz.";
            }
            history.add(new Msg("assistant", content));
            final String show = content;
            main.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            appendLog("[ASUS/Ollama] " + show + "\n");
                        }
                    });
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private String doChat() {
        HttpURLConnection c = null;
        final boolean asus = isAsusBackend();
        if (asus && BuildConfig.AI_ASUS_OLLAMA_NATIVE) {
            return doOllamaNativeChat();
        }
        try {
            String urlS = asus
                    ? BuildConfig.AI_ASUS_CHAT_COMPLETIONS_URL
                    : BuildConfig.AI_CHAT_COMPLETIONS_URL;
            String model = asus ? BuildConfig.AI_ASUS_MODEL : BuildConfig.AI_MODEL;
            String bearer = asus ? BuildConfig.AI_ASUS_API_KEY : BuildConfig.AI_API_KEY;
            if (bearer == null) {
                bearer = "";
            }

            URL u = new URL(urlS);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearer.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            c.setDoOutput(true);
            JSONObject root = new JSONObject();
            root.put("model", model);
            root.put("messages", buildMessagesJsonArray());
            root.put("temperature", 0.7);
            byte[] body = utf8Bytes(root.toString());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                c.setFixedLengthStreamingMode(body.length);
            } else {
                c.setRequestProperty("Content-Length", Integer.toString(body.length));
            }
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.close();
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? c.getInputStream()
                    : c.getErrorStream();
            if (in == null) {
                return "HTTP " + code;
            }
            String resp = readAll(in);
            in.close();
            if (code < 200 || code >= 300) {
                return "HTTP " + code + ": " + trunc(resp, 500);
            }
            JSONObject json = new JSONObject(resp);
            JSONArray ch = json.optJSONArray("choices");
            if (ch == null || ch.length() < 1) {
                return "Nincs choices a válaszban: " + trunc(resp, 200);
            }
            JSONObject ch0 = ch.getJSONObject(0);
            JSONObject msg = ch0.optJSONObject("message");
            if (msg == null) {
                return "Üres message: " + trunc(resp, 200);
            }
            String content = msg.optString("content", "");
            if (content.isEmpty()) {
                return "Üres content.";
            }
            history.add(new Msg("assistant", content));
            final String show = content;
            main.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            appendLog((asus ? "[ASUS] " : "[Felhő] ") + show + "\n");
                        }
                    });
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * UTF-8 bájtok — {@link String#getBytes(java.nio.charset.Charset)} nincs a régi (pl. 2.1) Dalvik
     * referenciájában mindenhol; a {@code "UTF-8"} sztringgel kompatibilis.
     */
    private static byte[] utf8Bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        // InputStreamReader(InputStream, Charset) → régi API-n hiányzhat; "UTF-8" sztringgel oké.
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            b.append(line).append('\n');
        }
        return b.toString();
    }

    private static String trunc(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private void appendLog(final String s) {
        logView.append(s);
        logScroll.post(
                new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
    }
}

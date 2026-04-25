package tomi.assoc.gti5800;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-stílusú <strong>chat/completions</strong> kliens: {@link BuildConfig#AI_CHAT_COMPLETIONS_URL},
 * {@link BuildConfig#AI_MODEL}, Bearer {@link BuildConfig#AI_API_KEY} (a projekt
 * <code>local.properties</code>: <code>ASSOC_AI_API_KEY</code> – ne legyen verziókezelve). Ha
 * {@link BuildConfig#AI_ENABLED} hamis, a képernyő azonnal leáll. <code>full</code> és
 * <code>galaxy3</code> (Samsung I5800 / régi) egyaránt tudják, ha a buildekben be van kapcsolva; régi
 * Androidon modern HTTPS miatt a kapcsolat elhasalhat.
 */
public class AiChatActivity extends Activity {
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int CONNECT_TIMEOUT_MS = 30_000;

    private TextView logView;
    private ScrollView logScroll;
    private EditText input;
    private Button sendBtn;
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
            Toast.makeText(this, "AI csevegés nincs engedélyezve ebben a buildekben.", Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_ai_chat);
        logView = findViewById(R.id.ai_log);
        logScroll = findViewById(R.id.ai_scroll);
        input = findViewById(R.id.ai_input);
        sendBtn = findViewById(R.id.ai_send);
        appendLog("Rendszer: " + (BuildConfig.AI_API_KEY == null || BuildConfig.AI_API_KEY.isEmpty()
                ? getString(R.string.ai_no_key)
                : "Kulcs beállítva. Modell: " + BuildConfig.AI_MODEL + "\n"));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });
    }

    private void send() {
        if (BuildConfig.AI_API_KEY == null || BuildConfig.AI_API_KEY.isEmpty()) {
            Toast.makeText(this, R.string.ai_no_key, Toast.LENGTH_LONG).show();
            return;
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
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = doChat();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        sendBtn.setEnabled(true);
                        sendBtn.setText(R.string.ai_send);
                        if (err != null) {
                            appendLog("[Hiba] " + err + "\n");
                            Toast.makeText(AiChatActivity.this, err, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private String doChat() {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BuildConfig.AI_CHAT_COMPLETIONS_URL);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.AI_API_KEY);
            c.setDoOutput(true);
            JSONObject root = new JSONObject();
            root.put("model", BuildConfig.AI_MODEL);
            JSONArray msgs = new JSONArray();
            for (Msg m : history) {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", m.content);
                msgs.put(o);
            }
            root.put("messages", msgs);
            root.put("temperature", 0.7);
            byte[] body = root.toString().getBytes(Charset.forName("UTF-8"));
            // KITKAT+ (19): különben NoSuchMethodError galaxy3 (min 7) buildeken; régi: Content-Length
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
            main.post(new Runnable() {
                @Override
                public void run() {
                    appendLog("[AI] " + show + "\n");
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

    private static String readAll(InputStream in) throws Exception {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, Charset.forName("UTF-8")));
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
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}

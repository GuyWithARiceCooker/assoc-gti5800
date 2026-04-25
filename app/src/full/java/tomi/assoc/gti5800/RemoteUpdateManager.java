package tomi.assoc.gti5800;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Távoli önfrissítés (csak {@code full} flavor): a {@link BuildConfig#UPDATE_JSON_URL} (GitHub
 * {@code main/docs/update.json} alapból) — a JSON-t távolról cseréled, új apkUrl + latestVersionCode;
 * kliens verzió: {@code BuildConfig#VERSION_CODE}. A {@code galaxy3} buildben a stub osztály pótolja.
 * Install: hosszú érintés a 3D nézeten, vagy automatikus felugró, ha távoli kód &gt; helyi.
 */
public final class RemoteUpdateManager {
    public static final int RC_INSTALL_SETTINGS = 5101;
    private static final String TAG = "RemoteUpdate";

    private RemoteUpdateManager() {
    }

    /**
     * Háttérben: ha a távoli {@code latestVersionCode} nagyobb, felugró a fő szálon.
     */
    public static void maybeAutoCheck(final Activity act) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo u = fetchUpdateInfo();
                if (u == null || u.latestCode <= 0) {
                    return;
                }
                int cur;
                try {
                    cur = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
                } catch (Exception e) {
                    return;
                }
                if (u.latestCode > cur && u.apkUrl != null && u.apkUrl.length() > 7) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            promptInstall(act, u);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Kézi ellenőrzés (hosszú érintés).
     */
    public static void startManualCheck(final Activity act) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo u = fetchUpdateInfo();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (u == null) {
                            toast(act, "Hálózat / távoli JSON hiba (update.json).");
                            return;
                        }
                        int cur;
                        try {
                            cur = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
                        } catch (Exception e) {
                            return;
                        }
                        if (u.latestCode > cur) {
                            promptInstall(act, u);
                        } else {
                            toast(act, "Naprakész. Helyi: " + cur + " — távoli max: " + u.latestCode);
                        }
                    }
                });
            }
        }).start();
    }

    private static void toast(Activity a, String m) {
        Toast.makeText(a, m, Toast.LENGTH_LONG).show();
    }

    private static class UpdateInfo {
        int latestCode;
        String name;
        String apkUrl;
    }

    private static UpdateInfo fetchUpdateInfo() {
        HttpURLConnection c = null;
        try {
            String u = BuildConfig.UPDATE_JSON_URL;
            if (u == null || u.length() < 6) {
                return null;
            }
            URL url = new URL(u);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod("GET");
            c.connect();
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code);
                return null;
            }
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            JSONObject o = new JSONObject(sb.toString());
            UpdateInfo i = new UpdateInfo();
            i.latestCode = o.optInt("latestVersionCode", 0);
            i.name = o.optString("latestVersionName", "");
            i.apkUrl = o.optString("apkUrl", "");
            if (i.apkUrl == null) {
                i.apkUrl = "";
            }
            return i;
        } catch (Exception e) {
            Log.e(TAG, "fetch", e);
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void promptInstall(final Activity act, final UpdateInfo u) {
        if (u.apkUrl == null || u.apkUrl.length() < 8) {
            toast(act, "A távoli JSON-ban nincs apkUrl.");
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Frissítés")
                .setMessage("Új: " + u.name + " (kód " + u.latestCode + ").\nLetöltöd?")
                .setPositiveButton("Igen", (d, w) -> downloadAndInstall(act, u.apkUrl))
                .setNegativeButton("Később", null)
                .show();
    }

    private static void downloadAndInstall(final Activity act, final String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!act.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(act)
                        .setMessage("Engedélyezd az alkalmazás telepítését: „ismeretlen forrás”.")
                        .setPositiveButton("Beállítások", (d, w) -> {
                            Intent i = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName()));
                            act.startActivityForResult(i, RC_INSTALL_SETTINGS);
                        })
                        .setNegativeButton("Mégse", null)
                        .show();
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File out = new File(act.getCacheDir(), "self-update.apk");
                try {
                    if (out.exists() && !out.delete()) {
                        // ignore
                    }
                } catch (Exception ignored) {
                }
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(apkUrl).openConnection();
                    c.setConnectTimeout(30000);
                    c.setReadTimeout(10 * 60 * 1000);
                    c.setRequestMethod("GET");
                    c.connect();
                    if (c.getResponseCode() != 200) {
                        final int sc = c.getResponseCode();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                toast(act, "Letöltés: HTTP " + sc);
                            }
                        });
                        return;
                    }
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                    }
                    in.close();
                    fo.close();
                    if (out.length() < 10000) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                toast(act, "Letöltés: a fájl túl kicsi.");
                            }
                        });
                        return;
                    }
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            launchInstall(act, out);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "dl", e);
                    final String msg = e.getMessage() != null ? e.getMessage() : "hiba";
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            toast(act, "Letöltés: " + msg);
                        }
                    });
                } finally {
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    static void launchInstall(Activity act, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(
                        act, act.getPackageName() + ".fileprovider", apk);
                Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                in.setData(uri);
                in.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(in);
            } else {
                Intent in = new Intent(Intent.ACTION_VIEW);
                in.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            Toast.makeText(act, "Telepítés: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

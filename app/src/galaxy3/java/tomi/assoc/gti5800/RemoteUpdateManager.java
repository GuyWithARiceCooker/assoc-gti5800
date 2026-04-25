package tomi.assoc.gti5800;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GT-I5800 (galaxy3): távoli frissítés <strong>USB nélkül</strong> – {@link BuildConfig#UPDATE_JSON_URL} JSON
 * a GitHubról, letöltés + régi stílusú {@code file://} telepítési intent (Nincs FileProvider, nincs AndroidX).
 * A JSON <strong>előnyben</strong> a {@code galaxy3ApkUrl} mező (külön APK a {@code full} helyett);
 * ha üres, visszaesik a köznapi {@code apkUrl}-ra.
 * <p>
 * <strong>TLS:</strong> Android 2.2 modern HTTPS (pl. {@code raw.githubusercontent.com}) sokszor
 * elhasal – ilyenkor HTTP/ LAN / egy régi hosztra vitt APK szükséges, vagy a böngészőből
 * (SD / letöltés) manuális telepítés.
 */
public final class RemoteUpdateManager {
    public static final int RC_INSTALL_SETTINGS = 5101;
    private static final String TAG = "RemoteUpdateGal";

    private RemoteUpdateManager() {
    }

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

    public static void startManualCheck(final Activity act) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateInfo u = fetchUpdateInfo();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (u == null) {
                            toast(act, "Hálózat / távoli JSON hiba. (Régi telefon: HTTPS lehet, hogy nem megy.)");
                            return;
                        }
                        if (u.apkUrl == null || u.apkUrl.length() < 8) {
                            toast(
                                    act,
                                    "A JSON-ban töltsd ki: galaxy3ApkUrl (vagy apkUrl) — távoli APK-URL.");
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
                            toast(
                                    act,
                                    "Naprakész. Helyi: " + cur + " — távoli: " + u.latestCode
                                            + (u.name.length() > 0 ? " (" + u.name + ")" : ""));
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
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + c.getResponseCode());
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
            // Galaxy3 külön URL; ha nincs, a közös apkUrl (vigyázat: lehet, hogy a full APK, ne töltsd rosszat)
            i.apkUrl = o.optString("galaxy3ApkUrl", "");
            if (i.apkUrl == null || i.apkUrl.length() < 8) {
                i.apkUrl = o.optString("apkUrl", "");
            }
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
            toast(act, "Nincs letöltési URL (galaxy3ApkUrl / apkUrl).");
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Frissítés")
                .setMessage("Új: " + u.name + " (kód " + u.latestCode + ").\nLetöltöd?")
                .setPositiveButton("Igen", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        downloadAndInstall(act, u.apkUrl);
                    }
                })
                .setNegativeButton("Később", null)
                .show();
    }

    private static void downloadAndInstall(final Activity act, final String apkUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File out = new File(act.getCacheDir(), "self-update-galaxy3.apk");
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
                    // Régi installerek: a csomagolvasó hozzáférjen (Linux chmod)
                    try {
                        Runtime.getRuntime().exec("chmod 644 " + out.getAbsolutePath());
                    } catch (Exception ignored) {
                    }
                    // API 7–8: nincs FileProvider ág; API 9+: setReadable, ha beépítve van
                    if (Build.VERSION.SDK_INT >= 9) {
                        try {
                            out.setReadable(true, false);
                        } catch (Exception ignored) {
                        }
                    }
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            launchInstallFroyoAndOlder(act, out);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "dl", e);
                    final String msg = e.getMessage() != null ? e.getMessage() : "hiba";
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            toast(
                                    act,
                                    "Letöltés: " + msg
                                            + " — régi telefon: próbálj HTTP-t vagy másik tükröt.");
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

    /**
     * Install csak {@code file:} + {@link Intent#ACTION_VIEW} (Nougat előtti csatorna, nincs content: URI).
     */
    static void launchInstallFroyoAndOlder(Activity act, File apk) {
        try {
            Intent in = new Intent(Intent.ACTION_VIEW);
            in.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(in);
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            Toast.makeText(
                            act,
                            "Telepítés: " + (e.getMessage() != null ? e.getMessage() : "ismeretlen forrás?"),
                            Toast.LENGTH_LONG)
                    .show();
        }
    }
}

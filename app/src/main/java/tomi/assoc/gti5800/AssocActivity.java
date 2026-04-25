package tomi.assoc.gti5800;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Fő Activity: teljes képernyő {@link GLSurfaceView}, OpenGL ES 1.0.
 * Cél: GT-I5800 (FIMG) — egyszerű, régi API, nincs AppCompat függőség.
 */
public class AssocActivity extends Activity {
    private GLSurfaceView glView;
    private AssocRenderer renderer;
    private GestureDetector gesture;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        renderer = new AssocRenderer();
        glView = new GLSurfaceView(this);
        // `setEGLContextClientVersion(1)` csak API 8+ (Froyo). API 7-en az alapértelmezett GL kontextus ES 1.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
            glView.setEGLContextClientVersion(1);
        }
        // Froyo/GLSurfaceView: a config chooser a setRenderer ELŐTT. Régi: 565 + mélység; Lollipop+:
        // színes, 8-8-8-8 — a OnePlus/új chipeken a 5-6-5 gyakran üres vagy fagyott képet ad.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        } else {
            glView.setEGLConfigChooser(5, 6, 5, 0, 16, 0);
        }
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        // Érintés: a GLSurfaceView alapból gyakran nem viszi a touchot — „nem reagál”
        glView.setClickable(true);
        glView.setFocusable(true);
        // egy kopp: nudge · dupla: AI csevegés (full + galaxy3, ha van kulcs) · hosszú: távoli frissítés
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                renderer.nudgeCamera();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                RemoteUpdateManager.startManualCheck(AssocActivity.this);
            }
        });
        gesture.setOnDoubleTapListener(
                new GestureDetector.OnDoubleTapListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (BuildConfig.AI_ENABLED) {
                            startActivity(new Intent(AssocActivity.this, AiChatActivity.class));
                        } else {
                            Toast.makeText(
                                            AssocActivity.this,
                                            "AI csevegés nincs engedélyezve ebben a buildekben.",
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                        return true;
                    }

                    @Override
                    public boolean onDoubleTapEvent(MotionEvent e) {
                        return false;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        return false;
                    }
                });
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gesture.onTouchEvent(event);
            }
        });
        setContentView(glView);

        // Toast: ne az onCreate legelején (GL setup mellett) — rövid késleltetéssel
        try {
            final PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        String tip = getString(R.string.ai_gesture);
                        Toast.makeText(
                                        AssocActivity.this,
                                        "assoc v" + pi.versionName + " (" + pi.versionCode + ")\n" + tip,
                                        Toast.LENGTH_LONG)
                                .show();
                    } catch (Throwable ignored) {
                    }
                }
            }, 800);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.getRepeatCount() == 0) {
            boolean next = !renderer.isDollyZoomEnabled();
            renderer.setDollyZoomEnabled(next);
            Toast.makeText(
                            this,
                            next ? R.string.dolly_on : R.string.dolly_off,
                            Toast.LENGTH_SHORT)
                    .show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glView != null) {
            glView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) {
            glView.onResume();
        }
        // Távoli update.json: ha később nagyobb latestVersionCode, felugró
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                RemoteUpdateManager.maybeAutoCheck(AssocActivity.this);
            }
        }, 5000L);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RemoteUpdateManager.RC_INSTALL_SETTINGS) {
            Toast.makeText(
                    this,
                    "Ismeretlen forrás engedély: hosszú érintés → frissítés",
                    Toast.LENGTH_LONG).show();
        }
    }
}

package tomi.assoc.gti5800;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
        glView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    renderer.nudgeCamera();
                }
                return true;
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
                        Toast.makeText(AssocActivity.this, "assoc v" + pi.versionName + " (" + pi.versionCode + ")", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            }, 800);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
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
    }
}

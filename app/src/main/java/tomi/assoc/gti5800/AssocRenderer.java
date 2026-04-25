package tomi.assoc.gti5800;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 1.0 renderelő: a „assoc” szöveg sárga, textúrázott négyzeten, Y tengely körüli forgatás,
 * kicsi dőléssel 3D érzet. Cél: FIMG 3DSE (OpenGL ES 1.x) — nincs shader.
 */
public class AssocRenderer implements GLSurfaceView.Renderer {
    /** Nagyobb atlas: a 240×400 kijelzőre skálázva is marad körvonal + a GL LINEAR szűrés nem mos el mindent */
    private static final int TEX_W = 512;
    private static final int TEX_H = 256;

    private int textureId = -1;
    private int viewportW;
    private int viewportH;
    private float angleY;
    private float angleX;
    private float zCam = 4.2f;

    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texBuffer;

    public AssocRenderer() {
        // négyzet az XY síkban, középpont (0,0,0) — 2,5 egység széles (széles képernyőarányhoz húzva később a textúrát is)
        float v[] = {
            -1.2f, -0.5f, 0,
            1.2f, -0.5f, 0,
            -1.2f, 0.5f, 0,
            1.2f, 0.5f, 0
        };
        ByteBuffer vbb = ByteBuffer.allocateDirect(v.length * 4);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        vertexBuffer.put(v);
        vertexBuffer.position(0);

        float t[] = {
            0, 1, 1, 1, 0, 0, 1, 0
        };
        ByteBuffer tbb = ByteBuffer.allocateDirect(t.length * 4);
        tbb.order(ByteOrder.nativeOrder());
        texBuffer = tbb.asFloatBuffer();
        texBuffer.put(t);
        texBuffer.position(0);
    }

    /** Érintéskor kicsit mozgatja a kamerát / dőlést, hogy reagáljon a gép. */
    public void nudgeCamera() {
        zCam = 3.4f + (float) (Math.random() * 0.5);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
        gl.glClearColor(0.12f, 0.1f, 0.16f, 1f);
        gl.glShadeModel(GL10.GL_SMOOTH);
        gl.glDisable(GL10.GL_DITHER);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glCullFace(GL10.GL_BACK);

        try {
            buildAssocTexture(gl);
        } catch (Throwable t) {
            textureId = -1;
        }
    }

    /**
     * „assoc” textúra: három réteg (külső vastag + középső + kitöltés), mind
     * {@link Paint.Join#ROUND} / {@link Paint.Cap#ROUND} — látványos „gömbölyded” vastag betű a pici kijelzőn is.
     */
    private void buildAssocTexture(GL10 gl) {
        Bitmap bmp = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF000000);
        final float x = TEX_W * 0.5f;
        final float y = TEX_H * 0.58f;
        final float textSize = TEX_H * 0.34f;

        Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        outer.setTypeface(Typeface.DEFAULT_BOLD);
        outer.setTextSize(textSize);
        outer.setTextAlign(Paint.Align.CENTER);
        outer.setStyle(Paint.Style.STROKE);
        outer.setStrokeWidth(TEX_H * 0.20f);
        outer.setStrokeJoin(Paint.Join.ROUND);
        outer.setStrokeCap(Paint.Cap.ROUND);
        outer.setColor(0xFFE65100);

        Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        mid.setTypeface(Typeface.DEFAULT_BOLD);
        mid.setTextSize(textSize);
        mid.setTextAlign(Paint.Align.CENTER);
        mid.setStyle(Paint.Style.STROKE);
        mid.setStrokeWidth(TEX_H * 0.11f);
        mid.setStrokeJoin(Paint.Join.ROUND);
        mid.setStrokeCap(Paint.Cap.ROUND);
        mid.setColor(0xFFFFC107);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setTextSize(textSize);
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFF9C4);

        c.drawText("assoc", x, y, outer);
        c.drawText("assoc", x, y, mid);
        c.drawText("assoc", x, y, fill);

        int[] tid = new int[1];
        gl.glGenTextures(1, tid, 0);
        textureId = tid[0];
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId);
        // Apró képernyő: NEAREST, hogy a vastag kerek körvonal ne linear mosódjon el
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        if (h == 0) h = 1;
        viewportW = w;
        viewportH = h;
        gl.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
        // Első kockák: még nincs onSurfaceChanged → viewport 0 → aspect NaN, fekete / fagyás
        if (viewportW < 1 || viewportH < 1) {
            return;
        }
        if (textureId < 0) {
            return;
        }
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();
        float aspect = (float) viewportW / (float) viewportH;
        GLU.gluPerspective(gl, 50.0f, aspect, 0.1f, 100.0f);

        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
        GLU.gluLookAt(gl, 0, 0, zCam, 0, 0, 0, 0, 1, 0);

        angleY += 1.1f;
        if (angleY >= 360f) {
            angleY -= 360f;
        }
        angleX = 8f * (float) Math.sin(System.currentTimeMillis() * 0.001);

        gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textureId);

        gl.glPushMatrix();
        gl.glRotatef(angleX, 1, 0, 0);
        gl.glRotatef(angleY, 0, 1, 0);
        // enyhe eltolás, hogy ne „lógjon” a szélén
        gl.glTranslatef(0, 0.1f, 0);

        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer);
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, texBuffer);
        gl.glColor4f(1f, 1f, 1f, 1f);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
        gl.glPopMatrix();

        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glDisable(GL10.GL_TEXTURE_2D);
    }
}

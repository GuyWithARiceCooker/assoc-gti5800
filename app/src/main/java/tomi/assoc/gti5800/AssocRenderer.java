package tomi.assoc.gti5800;

import android.opengl.GLSurfaceView;
import android.opengl.GLU;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 1.0: a „assoc” szöveg valódi 3D-s geometria — 5×7-es rácson minden sárga „pixel”
 * extrudált doboz, világítással, forgatással. Cél: FIMG / OnePlus, shader nélküli fixed pipeline;
 * a régi GL 1.x a lap-normál + egy fénynél sokszínű árnyalatot ad (nem 2D tábla a térben).
 * <p>
 * Dolly zoom (opt): a kamera távolsága <strong>és</strong> a {@code gluPerspective} függő látószög
 * együtt úgy, hogy a középpont körüli jelenet becsípődési mérete közel stabil marad, miközben a
 * 3D-s mélységi viszony (háttér) „levegődik/nyomul” — a 2D UI-réteg ettől független, fix marad.
 */
public class AssocRenderer implements GLSurfaceView.Renderer {
    private static final float CREAM_R = 1f;
    private static final float CREAM_G = 0.95f;
    private static final float CREAM_B = 0.45f;

    /** Rács cella méret világ-egységben: kis szó (assoc) a képernyő közepe. */
    private static final float CS = 0.11f;
    /** Két betű oszlop közötti réselés. */
    private static final float GAP = CS * 0.65f;
    /** Extrudálás: „vastag” 3D betű profil, oldalról is látszik. */
    private static final float DEPTH = 0.32f;
    private static final int NROWS = 7;
    private static final int NCOLS = 5;

    /** 5×7, '#' = extrudált kocka. Az „assoc” minúsz betűi — egységes, olvasható blokk-stílus. */
    private static final String[] PAT_A = {
            " ### ",
            "#   #",
            "#   #",
            "#####",
            "#   #",
            "#   #",
            " ### ",
    };
    private static final String[] PAT_S = {
            " ####",
            "#    ",
            " ### ",
            "    #",
            "    #",
            "#   #",
            " ####",
    };
    private static final String[] PAT_O = {
            " ### ",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            "#   #",
            " ### ",
    };
    private static final String[] PAT_C = {
            " ####",
            "#    ",
            "#    ",
            "#    ",
            "#    ",
            "#    ",
            " ####",
    };

    /**
     * Kalibráció: ezeknél a (távolság, fov) pároknál a forgó „assoc” közel ugyanakkora a képen — dollyhoz
     * a képlet: {@code tan(fov/2) = (Z_DOLLY_REF * tan(FOV_DOLLY_REF/2)) / z} (radián).
     */
    private static final float Z_DOLLY_REF = 3.5f;
    private static final float FOV_DOLLY_REF_DEG = 50f;
    /** Sin hullám közepe, amplitúd [világ-egység] — kis Vertigo, nem hányinger. */
    private static final float DOLLY_Z_CENTER = 3.5f;
    private static final float DOLLY_Z_AMP = 0.75f;
    private static final float DOLLY_PERIOD_SEC = 14f;
    private static final float FOVY_CLAMP_MIN = 24f;
    private static final float FOVY_CLAMP_MAX = 78f;
    private int viewportW;
    private int viewportH;
    private float angleY;
    private float angleX;
    /**
     * Dolly <strong>ki</strong> esetén: kézzel nudge-olt/állandó kamera táv (régi viselkedés: egy szám).
     */
    private float zCamFree = 4.2f;
    /** Dolly fázis: érintés ezt tolja, hogy a hullám „kicsússzon”. */
    private float dollyPhaseOffsetRad = 0f;
    private boolean dollyZoomEnabled = true;
    private FloatBuffer vertexBuffer;
    private FloatBuffer normalBuffer;
    private int triCount;
    private boolean meshReady;

    public AssocRenderer() {
        buildMesh();
    }

    private static void appendAxisBox(
            List<Float> v, List<Float> n,
            float x0, float y0, float z0, float w, float h, float d) {
        // Doboz: [x0,x0+w]×[y0,y0+h]×[z0,z0+d]; a kamera +Z-ból néz, tehát a +Z-s lap z=z0+d.
        final float z1 = z0 + d;
        // +Z: kifelé CCW, ha +Z-ból nézzük a lapot (Y fel, X jobbra) — GL_BACK culling
        addQuad(v, n, 0, 0, 1,
                x0, y0, z1, x0, y0 + h, z1, x0 + w, y0 + h, z1, x0 + w, y0, z1);
        // -Z: kifelé, ha -Z-ból
        addQuad(v, n, 0, 0, -1,
                x0, y0, z0, x0 + w, y0, z0, x0 + w, y0 + h, z0, x0, y0 + h, z0);
        // +X
        addQuad(v, n, 1, 0, 0,
                x0 + w, y0, z0, x0 + w, y0 + h, z0, x0 + w, y0 + h, z1, x0 + w, y0, z1);
        // -X
        addQuad(v, n, -1, 0, 0,
                x0, y0, z0, x0, y0, z1, x0, y0 + h, z1, x0, y0 + h, z0);
        // +Y
        addQuad(v, n, 0, 1, 0,
                x0, y0 + h, z0, x0, y0 + h, z1, x0 + w, y0 + h, z1, x0 + w, y0 + h, z0);
        // -Y
        addQuad(v, n, 0, -1, 0,
                x0, y0, z0, x0 + w, y0, z0, x0 + w, y0, z1, x0, y0, z1);
    }

    private static void addQuad(
            List<Float> v, List<Float> n,
            float nx, float ny, float nz,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        tri(v, n, nx, ny, nz, x1, y1, z1, x2, y2, z2, x3, y3, z3);
        tri(v, n, nx, ny, nz, x1, y1, z1, x3, y3, z3, x4, y4, z4);
    }

    private static void tri(
            List<Float> v, List<Float> n,
            float nx, float ny, float nz,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3) {
        for (int k = 0; k < 3; k++) {
            n.add(nx);
            n.add(ny);
            n.add(nz);
        }
        v.add(x1);
        v.add(y1);
        v.add(z1);
        v.add(x2);
        v.add(y2);
        v.add(z2);
        v.add(x3);
        v.add(y3);
        v.add(z3);
    }

    private void buildMesh() {
        List<Float> v = new ArrayList<Float>(4096);
        List<Float> n = new ArrayList<Float>(4096);
        String[][] patts = {PAT_A, PAT_S, PAT_S, PAT_O, PAT_C};
        float blockW = NCOLS * CS;
        float totalW = patts.length * blockW + (patts.length - 1) * GAP;
        for (int li = 0; li < patts.length; li++) {
            String[] pat = patts[li];
            float left = -totalW * 0.5f + li * (blockW + GAP) - 2.5f * CS;
            for (int r = 0; r < NROWS; r++) {
                String row = pat[r];
                for (int c = 0; c < NCOLS; c++) {
                    if (c >= row.length()) {
                        continue;
                    }
                    if (row.charAt(c) != '#') {
                        continue;
                    }
                    float x0 = left + c * CS;
                    float y0 = 3.5f * CS - (r + 1) * CS;
                    float z0 = -DEPTH * 0.5f;
                    appendAxisBox(v, n, x0, y0, z0, CS, CS, DEPTH);
                }
            }
        }
        triCount = v.size() / 9;
        ByteBuffer vbb = ByteBuffer.allocateDirect(v.size() * 4);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        for (int i = 0; i < v.size(); i++) {
            vertexBuffer.put(v.get(i));
        }
        vertexBuffer.position(0);
        ByteBuffer nbb = ByteBuffer.allocateDirect(n.size() * 4);
        nbb.order(ByteOrder.nativeOrder());
        normalBuffer = nbb.asFloatBuffer();
        for (int i = 0; i < n.size(); i++) {
            normalBuffer.put(n.get(i));
        }
        normalBuffer.position(0);
        meshReady = !v.isEmpty();
    }

    public void nudgeCamera() {
        dollyPhaseOffsetRad += 0.85f + (float) (Math.random() * 0.4f);
        if (!dollyZoomEnabled) {
            zCamFree = 3.2f + (float) (Math.random() * 0.9f);
        }
    }

    /** A hangerő/teszt: dolly+FOV pár be/ki. */
    public void setDollyZoomEnabled(boolean on) {
        dollyZoomEnabled = on;
    }

    public boolean isDollyZoomEnabled() {
        return dollyZoomEnabled;
    }

    private static float fovyDegreesForDolly(float zDistance) {
        if (zDistance < 0.2f) {
            zDistance = 0.2f;
        }
        double halfRefRad = Math.toRadians(FOV_DOLLY_REF_DEG) * 0.5;
        double k = Z_DOLLY_REF * Math.tan(halfRefRad);
        double halfFov = Math.atan(k / (double) zDistance);
        float deg = (float) (2.0 * Math.toDegrees(halfFov));
        if (deg < FOVY_CLAMP_MIN) {
            return FOVY_CLAMP_MIN;
        }
        if (deg > FOVY_CLAMP_MAX) {
            return FOVY_CLAMP_MAX;
        }
        return deg;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
        gl.glClearColor(0.1f, 0.08f, 0.14f, 1f);
        gl.glShadeModel(GL10.GL_SMOOTH);
        gl.glDisable(GL10.GL_DITHER);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glCullFace(GL10.GL_BACK);
        // Kis forgatott meshnél a normálok skálázása: régi MALI/FIMG stabilabb így
        gl.glEnable(GL10.GL_NORMALIZE);
        // Egy enyhe irányfény: +oldal/él a „assoc” lemezén olvasható marad
        gl.glEnable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_LIGHT0);
        // glColorMaterial csak GL11 — itt a sárga kizárólag glMaterialfv + fény (GL10-kompatibilis)
        float[] pos = {1.2f, 1.4f, 2.0f, 0f};
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, pos, 0);
        float[] amb = {0.45f, 0.42f, 0.38f, 1f};
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT, amb, 0);
        float[] dif = {0.95f, 0.88f, 0.7f, 1f};
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE, dif, 0);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        if (h == 0) {
            h = 1;
        }
        viewportW = w;
        viewportH = h;
        gl.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
        if (viewportW < 1 || viewportH < 1 || !meshReady) {
            return;
        }
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();
        float aspect = (float) viewportW / (float) viewportH;
        float fovyDeg;
        float zLook;
        if (dollyZoomEnabled) {
            double t = System.currentTimeMillis() * 0.001;
            double ang = 2.0 * Math.PI * t / DOLLY_PERIOD_SEC + dollyPhaseOffsetRad;
            zLook = DOLLY_Z_CENTER + DOLLY_Z_AMP * (float) Math.sin(ang);
            fovyDeg = fovyDegreesForDolly(zLook);
        } else {
            zLook = zCamFree;
            fovyDeg = 50.0f;
        }
        GLU.gluPerspective(gl, fovyDeg, aspect, 0.1f, 100.0f);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
        GLU.gluLookAt(gl, 0, 0, zLook, 0, 0, 0, 0, 1, 0);
        angleY += 0.85f;
        if (angleY >= 360f) {
            angleY -= 360f;
        }
        angleX = 5f * (float) Math.sin(System.currentTimeMillis() * 0.001);
        gl.glEnable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_LIGHT0);
        gl.glPushMatrix();
        gl.glTranslatef(0, 0.01f, 0);
        gl.glRotatef(angleX, 1, 0, 0);
        gl.glRotatef(angleY, 0, 1, 0);
        gl.glMaterialfv(
                GL10.GL_FRONT_AND_BACK,
                GL10.GL_AMBIENT,
                new float[]{CREAM_R * 0.45f, CREAM_G * 0.45f, CREAM_B * 0.45f, 1f},
                0);
        gl.glMaterialfv(
                GL10.GL_FRONT_AND_BACK,
                GL10.GL_DIFFUSE,
                new float[]{CREAM_R, CREAM_G, CREAM_B, 1f},
                0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer);
        gl.glNormalPointer(GL10.GL_FLOAT, 0, normalBuffer);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_NORMAL_ARRAY);
        gl.glDrawArrays(GL10.GL_TRIANGLES, 0, triCount * 3);
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glPopMatrix();
    }
}

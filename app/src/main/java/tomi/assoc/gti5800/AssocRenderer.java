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
 * OpenGL ES 1.0: <strong>felülnézeti, low-poly panelházak</strong> (téglatestek) egy egyszerű
 * rácson — <strong>nincs</strong> felirat / “assoc” minta, <strong>statikus jelenet</strong> (a kamera
 * és a város <strong>nem forog</strong>, nincs dolly). A talaj <strong>XZ</strong> sík, <strong>Y</strong> a magasság;
 * a nézőpont a <strong>+Y</strong> fölül lefelé. Cél: FIMG / OnePlus, fixed pipeline, shader nélkül.
 */
public class AssocRenderer implements GLSurfaceView.Renderer {
    private static final float CREAM_R = 1f;
    private static final float CREAM_G = 0.95f;
    private static final float CREAM_B = 0.45f;
    private static final float GROUND_R = 0.18f;
    private static final float GROUND_G = 0.2f;
    private static final float GROUND_B = 0.24f;

    private static final float CS = 0.11f;
    private static final float GAP = CS * 0.65f;
    /** Utcarész a cellák között. */
    private static final float BUILD_INSET = 0.012f;
    private static final float BLD_H_LO = 0.18f;
    private static final float BLD_H_SPAN = 0.32f;
    /** Rács: közel négyzetes telek, nincs betűs minta. */
    private static final int GRID_COLS = 10;
    private static final int GRID_ROWS = 8;
    private static final float EYE_Y = 3.55f;
    private static final float PERSP_FOVY_DEG = 50f;
    private int viewportW;
    private int viewportH;
    private FloatBuffer vertexBuffer;
    private FloatBuffer normalBuffer;
    private int triCountTotal;
    /** A talajhoz tartozó háromszögek száma (2 tri egy nagy négyszög) — előtte rajzoljuk. */
    private int triCountGround;
    private boolean meshReady;

    public AssocRenderer() {
        buildMesh();
    }

    private static void appendAxisBox(
            List<Float> v, List<Float> n,
            float x0, float y0, float z0, float w, float h, float d) {
        final float z1 = z0 + d;
        addQuad(v, n, 0, 0, 1,
                x0, y0, z1, x0, y0 + h, z1, x0 + w, y0 + h, z1, x0 + w, y0, z1);
        addQuad(v, n, 0, 0, -1,
                x0 + w, y0, z0, x0, y0, z0, x0, y0 + h, z0, x0 + w, y0 + h, z0);
        addQuad(v, n, 1, 0, 0,
                x0 + w, y0, z0, x0 + w, y0, z1, x0 + w, y0 + h, z1, x0 + w, y0 + h, z0);
        addQuad(v, n, -1, 0, 0,
                x0, y0, z0, x0, y0, z1, x0, y0 + h, z1, x0, y0 + h, z0);
        addQuad(v, n, 0, 1, 0,
                x0, y0 + h, z0, x0, y0 + h, z1, x0 + w, y0 + h, z1, x0 + w, y0 + h, z0);
        addQuad(v, n, 0, -1, 0,
                x0, y0, z0, x0 + w, y0, z0, x0 + w, y0, z1, x0, y0, z1);
    }

    private static void addHorizontalQuadY(
            List<Float> v, List<Float> n, float y, float x0, float z0, float x1, float z1) {
        addQuad(v, n, 0, 1, 0,
                x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
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

    private static float panelHeight(int r, int c) {
        int h = (r * 31 + c * 13) & 0x7fff;
        float t = (h % 1000) / 1000f;
        return BLD_H_LO + t * BLD_H_SPAN;
    }

    private void buildMesh() {
        List<Float> v = new ArrayList<Float>(8192);
        List<Float> n = new ArrayList<Float>(8192);
        float blockW = GRID_COLS * CS;
        float blockD = GRID_ROWS * CS;
        float minX = 1e6f;
        float maxX = -1e6f;
        float minZ = 1e6f;
        float maxZ = -1e6f;
        float leftX = -blockW * 0.5f;
        float topZ = blockD * 0.5f;
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                float x0 = leftX + c * CS;
                float z0 = topZ - (r + 1) * CS;
                float in = BUILD_INSET;
                float w = CS - 2f * in;
                float d = CS - 2f * in;
                float xh = panelHeight(r, c);
                appendAxisBox(v, n, x0 + in, 0f, z0 + in, w, xh, d);
                minX = Math.min(minX, x0);
                maxX = Math.max(maxX, x0 + CS);
                minZ = Math.min(minZ, z0);
                maxZ = Math.max(maxZ, z0 + CS);
            }
        }
        if (minX > 1e5f) {
            minX = -0.4f;
            maxX = 0.4f;
            minZ = -0.4f;
            maxZ = 0.4f;
        }
        float yGround = -0.03f;
        float m = 0.18f;
        addHorizontalQuadY(v, n, yGround, minX - m, minZ - m, maxX + m, maxZ + m);
        triCountGround = 2;
        triCountTotal = v.size() / 9;
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
        meshReady = !v.isEmpty() && triCountTotal > triCountGround;
    }

    /**
     * Korábban a kamera „bökése”; a jelenet statikus, nincs hatás. Megmarad a hívási hely kompat.
     */
    public void nudgeCamera() {
    }

    /** Dolly a régi API miatt; a rajzolás nem használja. */
    public void setDollyZoomEnabled(boolean on) {
    }

    public boolean isDollyZoomEnabled() {
        return false;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
        gl.glClearColor(0.07f, 0.1f, 0.16f, 1f);
        gl.glShadeModel(GL10.GL_SMOOTH);
        gl.glDisable(GL10.GL_DITHER);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glDepthFunc(GL10.GL_LEQUAL);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glCullFace(GL10.GL_BACK);
        gl.glEnable(GL10.GL_NORMALIZE);
        gl.glEnable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_LIGHT0);
        float[] pos = {0.45f, 0.0f, 0.35f, 0f};
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, pos, 0);
        float[] amb = {0.36f, 0.4f, 0.45f, 1f};
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT, amb, 0);
        float[] dif = {0.9f, 0.88f, 0.8f, 1f};
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
        GLU.gluPerspective(gl, PERSP_FOVY_DEG, aspect, 0.1f, 100.0f);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
        GLU.gluLookAt(gl, 0, EYE_Y, 0, 0, 0, 0, 0, 0, 1f);
        // Nincs glRotate: statikus földbeállítás, ne mozduljon a város
        int vertsGround = triCountGround * 3;
        int vertsPanel = triCountTotal * 3 - vertsGround;
        gl.glEnable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_LIGHT0);
        gl.glMaterialfv(
                GL10.GL_FRONT_AND_BACK,
                GL10.GL_AMBIENT,
                new float[]{GROUND_R * 0.6f, GROUND_G * 0.6f, GROUND_B * 0.6f, 1f},
                0);
        gl.glMaterialfv(
                GL10.GL_FRONT_AND_BACK,
                GL10.GL_DIFFUSE,
                new float[]{GROUND_R, GROUND_G, GROUND_B, 1f},
                0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer);
        gl.glNormalPointer(GL10.GL_FLOAT, 0, normalBuffer);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_NORMAL_ARRAY);
        gl.glDrawArrays(GL10.GL_TRIANGLES, 0, vertsGround);
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
        gl.glDrawArrays(GL10.GL_TRIANGLES, vertsGround, vertsPanel);
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY);
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
    }
}

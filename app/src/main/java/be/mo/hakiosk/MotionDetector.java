package be.mo.hakiosk;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Eenvoudige bewegingsdetectie met de frontcamera.
 * Neemt ~3 beelden per seconde in lage resolutie, verdeelt het beeld in een raster
 * en meldt beweging als genoeg vakjes van helderheid veranderen.
 * Een globale lichtverandering (lamp aan, auto-belichting) telt niet als beweging.
 */
@SuppressWarnings("deprecation") // de oude Camera-API werkt op alle Android-versies en is hier ruim voldoende
public class MotionDetector implements Camera.PreviewCallback {

    public interface Listener {
        void onMotion();
    }

    private static final String TAG = "HAKioskMotion";
    private static final int GRID_W = 16;
    private static final int GRID_H = 12;
    private static final long FRAME_INTERVAL_MS = 300;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int sensitivity; // 1 (ongevoelig) .. 10 (zeer gevoelig)

    private HandlerThread thread;
    private Handler camHandler;
    private Camera camera;
    private SurfaceTexture texture;
    private int width, height;
    private int[] previous;
    private long lastAnalysis = 0;
    private int warmupFrames = 0;

    public MotionDetector(int sensitivity, Listener listener) {
        this.sensitivity = Math.max(1, Math.min(10, sensitivity));
        this.listener = listener;
    }

    public void start() {
        if (thread != null) return;
        thread = new HandlerThread("motion");
        thread.start();
        camHandler = new Handler(thread.getLooper());
        camHandler.post(this::open);
    }

    public void stop() {
        if (thread == null) return;
        final HandlerThread t = thread;
        camHandler.post(() -> {
            release();
            t.quitSafely();
        });
        thread = null;
        camHandler = null;
    }

    private int openAttempts = 0;

    private void open() {
        try {
            camera = Camera.open(findCamera());
            Camera.Parameters p = camera.getParameters();
            Camera.Size size = smallest(p.getSupportedPreviewSizes());
            p.setPreviewSize(size.width, size.height);
            p.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(p);
            width = size.width;
            height = size.height;

            // Onzichtbare preview: beelden worden enkel geanalyseerd, nooit getoond of opgeslagen
            texture = new SurfaceTexture(42);
            camera.setPreviewTexture(texture);
            int bufSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            previous = null;
            warmupFrames = 5;
            Log.i(TAG, "Camera gestart " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "Camera openen mislukt", e);
            release();
            // camera kan nog bezet zijn door een vorige sessie: even later opnieuw proberen
            Handler h = camHandler;
            if (h != null && ++openAttempts < 10) h.postDelayed(this::open, 1_000);
        }
    }

    private void release() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            camera.release();
            camera = null;
        }
        if (texture != null) {
            texture.release();
            texture = null;
        }
    }

    private static int findCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return 0;
    }

    private static Camera.Size smallest(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        for (Camera.Size s : sizes) {
            // kleinste resolutie, maar niet absurd klein
            if (s.width * s.height < best.width * best.height && s.width >= 160) best = s;
        }
        return best;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        long now = System.currentTimeMillis();
        if (now - lastAnalysis >= FRAME_INTERVAL_MS) {
            lastAnalysis = now;
            analyze(data);
        }
        cam.addCallbackBuffer(data);
    }

    private void analyze(byte[] nv21) {
        // Gemiddelde helderheid (Y-kanaal) per vakje
        int[] grid = new int[GRID_W * GRID_H];
        int cellW = width / GRID_W;
        int cellH = height / GRID_H;
        for (int gy = 0; gy < GRID_H; gy++) {
            for (int gx = 0; gx < GRID_W; gx++) {
                int sum = 0, count = 0;
                for (int y = gy * cellH; y < (gy + 1) * cellH; y += 4) {
                    int row = y * width;
                    for (int x = gx * cellW; x < (gx + 1) * cellW; x += 4) {
                        sum += nv21[row + x] & 0xFF;
                        count++;
                    }
                }
                grid[gy * GRID_W + gx] = count == 0 ? 0 : sum / count;
            }
        }

        int[] prev = previous;
        previous = grid;
        if (prev == null) return;
        if (warmupFrames > 0) { // camera past eerst nog belichting aan
            warmupFrames--;
            return;
        }

        // Globale lichtverandering eruit halen
        long totalDiff = 0;
        for (int i = 0; i < grid.length; i++) totalDiff += grid[i] - prev[i];
        int meanShift = (int) (totalDiff / grid.length);

        int pixelThreshold = 34 - sensitivity * 2;   // 32 .. 14 helderheidsstappen
        int cellsNeeded = 13 - sensitivity;          // 12 .. 3 vakjes
        int changed = 0;
        for (int i = 0; i < grid.length; i++) {
            if (Math.abs(grid[i] - prev[i] - meanShift) > pixelThreshold) changed++;
        }
        if (changed >= cellsNeeded) {
            mainHandler.post(listener::onMotion);
        }
    }
}

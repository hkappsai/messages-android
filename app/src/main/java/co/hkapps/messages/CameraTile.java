package co.hkapps.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Live camera preview inside the attach panel (Textra's inline camera). Tap takes a picture
 * straight into the message.
 */
final class CameraTile extends FrameLayout implements TextureView.SurfaceTextureListener {

    interface OnPhoto {
        void taken(byte[] jpeg);
    }

    private final TextureView texture;
    private final OnPhoto onPhoto;
    private HandlerThread thread;
    private Handler background;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean front;
    private int sensorOrientation;

    CameraTile(Context context, OnPhoto onPhoto) {
        super(context);
        this.onPhoto = onPhoto;
        texture = new TextureView(context);
        texture.setSurfaceTextureListener(this);
        addView(texture);
        setOnClickListener(v -> capture());
        setContentDescription("Take a picture");
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    void flip() {
        front = !front;
        close();
        if (texture.isAvailable()) open();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        close();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        close();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    @SuppressWarnings("MissingPermission")
    private void open() {
        if (!allowed(getContext()) || camera != null) return;
        thread = new HandlerThread("camera");
        thread.start();
        background = new Handler(thread.getLooper());
        CameraManager cm = getContext().getSystemService(CameraManager.class);
        try {
            String chosen = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
                if (facing != null && facing == want) {
                    chosen = id;
                    break;
                }
            }
            if (chosen == null) return;
            CameraCharacteristics ch = cm.getCameraCharacteristics(chosen);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpeg = largestUnder(map.getOutputSizes(ImageFormat.JPEG), 1920);
            reader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    byte[] upright = rotate(data);
                    post(() -> onPhoto.taken(upright));
                }
            }, background);
            Size preview = largestUnder(map.getOutputSizes(SurfaceTexture.class), 1280);
            cm.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    startPreview(preview);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    camera = null;
                }
            }, background);
        } catch (CameraAccessException | RuntimeException e) {
            close();
        }
    }

    private void startPreview(Size size) {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface surface = new Surface(st);
            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        s.setRepeatingRequest(req.build(), null, background);
                    } catch (CameraAccessException | RuntimeException ignored) {
                        // Preview stays blank.
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {}
            }, background);
            post(() -> fitPreview(size));
        } catch (CameraAccessException | RuntimeException ignored) {
            // No preview.
        }
    }

    /** Scales the preview so it fills the tile without stretching. */
    private void fitPreview(Size size) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Camera buffers are landscape; the tile is portrait-ish.
        float pw = size.getHeight(), ph = size.getWidth();
        float scale = Math.max(w / pw, h / ph);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(pw * scale / w, ph * scale / h, w / 2, h / 2);
        texture.setTransform(m);
    }

    private void capture() {
        if (camera == null || session == null) return;
        try {
            CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            req.addTarget(reader.getSurface());
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            req.set(CaptureRequest.JPEG_QUALITY, (byte) 88);
            session.capture(req.build(), null, background);
            animate().alpha(0.3f).setDuration(80).withEndAction(() -> animate().alpha(1f).setDuration(120)).start();
        } catch (CameraAccessException | RuntimeException ignored) {
            // Try again.
        }
    }

    /** Turns the sensor's sideways JPEG upright. */
    private byte[] rotate(byte[] jpeg) {
        int degrees = front ? (360 - sensorOrientation) % 360 : sensorOrientation;
        if (degrees == 0) return jpeg;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (b == null) return jpeg;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(degrees);
        if (front) m.postScale(-1, 1);
        android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        r.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
        return out.toByteArray();
    }

    private static Size largestUnder(Size[] sizes, int maxEdge) {
        Size best = sizes[0];
        long bestArea = 0;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (Math.max(s.getWidth(), s.getHeight()) <= maxEdge && area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private void close() {
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
        session = null;
        camera = null;
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }
}

package activate.privacy.utilities;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.content.Context.WINDOW_SERVICE;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import activate.privacy.Constants;
import activate.privacy.MainActivity;
import activate.privacy.PSApplication;
import activate.privacy.R;
import activate.privacy.TransparentActivity;


public class CameraBlockingUtilities extends DeviceAdminReceiver {

    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final String TAG = CameraBlockingUtilities.class.getSimpleName();
    private String mCameraId;
    private View view;
    private TextureView mTextureView;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private Size mPreviewSize;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private ImageReader mImageReader;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private volatile boolean opened;
    private volatile boolean loopStarted;

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener;
    private final CameraDevice.StateCallback mStateCallback;

    private static CameraBlockingUtilities INSTANCE;

    public static CameraBlockingUtilities createInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CameraBlockingUtilities();
        }
        return INSTANCE;
    }

    public static CameraBlockingUtilities getInstance() {
        return INSTANCE;
    }


    public CameraBlockingUtilities() {
        WindowManager windowManager = ((WindowManager) PSApplication.getContext().getSystemService(WINDOW_SERVICE));
        LayoutInflater inflater = (LayoutInflater) PSApplication.getContext().getSystemService(LAYOUT_INFLATER_SERVICE);
        view = inflater.inflate(R.layout.service_view, null);
        mTextureView = view.findViewById(R.id.texture);

        int dimension = 1;
        int pixelFormat = PixelFormat.RGBA_8888;
        WindowManager.LayoutParams params;
        if (Build.VERSION.SDK_INT >= 26) {
            params = new WindowManager.LayoutParams(
                    dimension, dimension,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    pixelFormat);

            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } else {
            params = new WindowManager.LayoutParams(
                    dimension, dimension,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    pixelFormat);

            params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        view.setAlpha(0);
        windowManager.addView(view, params);

        mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                openCamera(width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                configureTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }

        };

        mStateCallback = new CameraDevice.StateCallback() {

            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
                Log.e(TAG, "onOpened: camera opened");
                // This method is called when the camera is opened.  We start camera preview here.
                mCameraOpenCloseLock.release();
                mCameraDevice = cameraDevice;
                createCameraPreviewSession();
                opened = true;
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                Log.e(TAG, "onDisconnected: camera disconnected");
                opened = false;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
                BlockingUtilities.whiteListedAppsActive((whitelistedActive, whitelistedActiveCam, whitelistedActiveMic) -> {
                    if (whitelistedActiveCam) {
                        return;
                    }
                    forceCameraOpenOnSeparateThread();
                });
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int error) {
                Log.e(TAG, "onError: " + error);
                opened = false;
                mCameraOpenCloseLock.release();
                cameraDevice.close();
                mCameraDevice = null;
                if (error == 1) {
                    BlockingUtilities.whiteListedAppsActive((whitelistedActive, whitelistedActiveCam, whitelistedActiveMic) -> {
                        if (whitelistedActiveCam) {
                            return;
                        }
                        forceCameraOpenOnSeparateThread();
                    });
                } else {
                    closeCamera();
                }
            }

        };
    }

    public void forceCameraOpenOnSeparateThread() {
        Utilities.forceLooperThread.postRunnable(new Runnable() {
            @Override
            public void run() {
                forceCameraOpen();
            }
        });
    }

    public void forceCameraOpen() {
        if (loopStarted || opened) {
            return;
        }
        SharedPreferences preferences = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean cameraBlockerEnabled = preferences.getBoolean(Constants.APP_STATE.CAM_BLOCK_ENABLED, false);
        if (!cameraBlockerEnabled) {
            return;
        }
        loopStarted = true;
        while (!opened) {
            if (hasUsageAccessPermission()) {
                String currentPackage = BlockingUtilities.getRunningPackage(PSApplication.getContext());
                if (currentPackage != null) {
                    if (currentPackage.equalsIgnoreCase(PSApplication.getContext().getPackageName()) || !useCamera(currentPackage)) {
                        break;
                    }
                }
            } else if (MainActivity.inFocus) {
                break;
            }
            Log.e(TAG, "forceCameraOpen: looping on thread " + Thread.currentThread().getName());
            Intent intent = new Intent(PSApplication.getContext(), TransparentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_NO_HISTORY);
            PSApplication.getContext().startActivity(intent);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (!opened) {
//            Toast.makeText(PSApplication.getContext(), " camera not released", Toast.LENGTH_SHORT).show();
            closeCamera();
        }
        loopStarted = false;
    }

    public void startCamera() {
        if (opened) {
            return;
        }
        SharedPreferences preferences = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean cameraBlockerEnabled = preferences.getBoolean(Constants.APP_STATE.CAM_BLOCK_ENABLED, false);
        if (!cameraBlockerEnabled) {
            return;
        }
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    public void stopCamera() {
        opened = false;
        closeCamera();
        stopBackgroundThread();
    }

    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        CameraManager manager = (CameraManager) PSApplication.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(PSApplication.getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//                showToast("permission not granted");
                return;
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
//            showToast("open camera CameraAccessException");
//            <------------------- zakomentarisano zbog stalnog iskakanja intenta preko ekrana kada nema kamere------------------------->
//           forceCameraOpenOnSeparateThread();
        } catch (InterruptedException e) {
//            showToast("Interrupted while trying to lock camera opening");
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        } catch (Exception e) {
//            showToast("openCamera: other exception when trying to open camera");
            Log.e(TAG, "openCamera: other exception when trying to open camera");
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
//            Toast.makeText(PSApplication.getContext(), "Interrupted closeCamera", Toast.LENGTH_SHORT).show();
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
//            Toast.makeText(PSApplication.getContext(), "closeCamera", Toast.LENGTH_SHORT).show();
            mCameraOpenCloseLock.release();
        }
    }


    private void setUpCameraOutputs(int width, int height) {
        CameraManager manager = (CameraManager) PSApplication.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        null, mBackgroundHandler);

                Point displaySize = new Point();
                ((WindowManager) PSApplication.getContext().getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Toast.makeText(PSApplication.getContext(), "Camera2 API not supported on this device", Toast.LENGTH_LONG).show();
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        null, mBackgroundHandler);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread == null) {
            return;
        }
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e("Camera2", "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = ((WindowManager) PSApplication.getContext().getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private void showToast(final String text) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PSApplication.getContext(), text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void destroy() {
        if (view != null) {
            WindowManager windowManager = ((WindowManager) PSApplication.getContext().getSystemService(WINDOW_SERVICE));
            windowManager.removeViewImmediate(view);
        }
        opened = false;
        loopStarted = false;
        INSTANCE = null;
    }

    public boolean useCamera(String packageName) {
        PackageManager pm = PSApplication.getContext().getPackageManager();
        PackageInfo info = null;
        try {
            info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null && info.requestedPermissions.length > 0) {
                for (String permissionName : info.requestedPermissions) {
                    String[] nameParts = permissionName.split("\\.");
                    if (nameParts[nameParts.length - 1].equalsIgnoreCase(Constants.DANGEROUS_PERMISSIONS.CAMERA.toString())) {
                        return true;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean hasUsageAccessPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return true;
        } else {
            AppOpsManager appOps = (AppOpsManager) PSApplication.getContext().getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), PSApplication.getContext().getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}

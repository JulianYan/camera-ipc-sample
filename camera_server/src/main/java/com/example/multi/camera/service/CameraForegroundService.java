package com.example.multi.camera.service;

import static androidx.core.app.NotificationCompat.PRIORITY_MIN;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RecommendedStreamConfigurationMap;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Xin Xiao on 2022/8/17
 */
public class CameraForegroundService extends Service {
    private static final int MAX_IMAGES = 3;
    private final String TAG = "CameraForegroundService";
    private Handler mCameraHandler;

    //Camera2
    private CameraDevice mCameraDevice;
    private String mCameraId;//后置摄像头ID
    private CaptureRequest.Builder mPreviewBuilder;
    private CaptureRequest mCaptureRequest;
    private CameraCaptureSession mPreviewSession;
    private CameraCharacteristics characteristics;
    private Surface mSurface;
    private Range<Integer> fpsRange;
    private RecommendedStreamConfigurationMap recommendedStreamConfigurationMap;
    private Size mPreviewSize;
    private ImageReader mImageReader;
    private ImageWriter mImageWriter;
    AtomicInteger mCounter = new AtomicInteger(MAX_IMAGES);
    public static final String CHANNEL_ID = "com.example.multi.camera.service.CameraForegroundService";
    public static final String CHANNEL_NAME = "CameraForegroundService";

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate: ");
        super.onCreate();
        createNotificationChannel(CHANNEL_ID, CHANNEL_NAME);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        Notification notification = builder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        startForeground(100, notification, type);
        HandlerThread mCameraThread = new HandlerThread("CameraServerThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
        setupCamera();//配置相机参数
    }

    private void createNotificationChannel(String channelId, String channelName){
        NotificationChannel chan = new NotificationChannel(channelId,
                channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final ICameraService.Stub binder = new ICameraService.Stub() {

        @Override
        public void onSurfaceShared(Surface surface) throws RemoteException {
            Log.d(TAG, "onSurfaceShared");
            mSurface = surface;
            openCamera(mCameraId);//打开相机
        }

    };

    @Override
    public boolean onUnbind(Intent intent) {
        stopCamera();//释放资源
        return super.onUnbind(intent);
    }

    /**
     * ******************************SetupCamera(配置Camera)*****************************************
     */
    private void setupCamera() {
        //获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //0表示后置摄像头,1表示前置摄像头
            mCameraId = manager.getCameraIdList()[0];

            characteristics = manager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputSizes = configurationMap.getOutputSizes(ImageFormat.YUV_420_888);
            Log.i(TAG, "setupCamera: " + Arrays.toString(outputSizes));
            Optional<Size> previewSizeOpt = Arrays.stream(outputSizes).filter(size -> Math.abs(size.getWidth() / (float) size.getHeight() - 4 / 3f) <= 0.01f)
                    .min(Comparator.comparingInt(size -> Math.abs(size.getWidth() * size.getHeight() - 1440 * 1080)));
            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Optional<Range<Integer>> fpsRangeOpt = Arrays.stream(fpsRanges).max((range1, range2) -> {
                int upper = range1.getUpper() - range2.getUpper();
                if (upper == 0) {
                    return range2.getLower() - range1.getLower();
                } else {
                    return upper;
                }
            });
            fpsRange = fpsRangeOpt.orElse(new Range<>(30, 30));
            mPreviewSize = previewSizeOpt.orElse(new Size(640, 480));
            Log.d(TAG, "fpsRange: " + fpsRange + ",size=" + mPreviewSize);
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "setupCamera failed: " + e.toString());
        }
    }

    /**
     * ******************************openCamera(打开Camera)*****************************************
     */
    private void openCamera(String CameraId) {
        //获取摄像头的管理者CameraManager
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //检查权限
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permissions were denied!");
                return;
            }
            //打开相机，第一个参数指示打开哪个摄像头，第二个参数stateCallback为相机的状态回调接口，第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            manager.openCamera(CameraId, mStateCallback, mCameraHandler);
            Log.d(TAG, "openCamera");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "StateCallback:onOpened");
            mCameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.d(TAG, "StateCallback:onDisconnected");
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.d(TAG, "StateCallback:onError:" + error);
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    /**
     * ******************************Camera2成功打开，开始预览(startPreview)*************************
     */
    public void startPreview() {
        Log.d(TAG, "startPreview");
        if (null == mCameraDevice) {
            return;
        }

        try {
            closePreviewSession();
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//创建CaptureRequestBuilder，TEMPLATE_PREVIEW表示预览请求
            //默认预览不开启闪光灯
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            mPreviewBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            //设置预览画面的帧率
            mPreviewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);


            List<OutputConfiguration> outputs = new ArrayList<>();
            if (mImageReader != null) {
                mImageReader.close();
            }
            mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.YUV_420_888, MAX_IMAGES);

            mPreviewBuilder.addTarget(mImageReader.getSurface());//设置Surface作为预览数据的显示界面

            mImageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null && mImageWriter != null) {
                    int i = mCounter.get();
                    Log.i(TAG, "onImageAvailable: imwriter buffer=" + i);
                    if (i > 0) {
                        mCounter.decrementAndGet();
                        mImageWriter.queueInputImage(image);
                    } else {
                        image.close();
                    }
                }
            }, mCameraHandler);
            outputs.add(new OutputConfiguration(mImageReader.getSurface()));

            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, command -> mCameraHandler.post(command), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured");
                    try {
                        //创建捕获请求
                        mCaptureRequest = mPreviewBuilder.build();
                        mPreviewSession = session;
                        if (mImageWriter != null) {
                            mImageWriter.close();
                        }
                        mImageWriter = ImageWriter.newInstance(mSurface, MAX_IMAGES);
                        mImageWriter.setOnImageReleasedListener(writer -> {
                            int i = mCounter.incrementAndGet();
                            Log.i(TAG, "onImageReleased: imwriter buffer=" + i);
                        }, mCameraHandler);
                        //不停的发送获取图像请求，完成连续预览
                        mPreviewSession.setRepeatingRequest(mCaptureRequest, new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                super.onCaptureCompleted(session, request, result);
                                Log.i(TAG, "onCaptureCompleted: ");
                            }

                            @Override
                            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                                super.onCaptureFailed(session, request, failure);
                                Log.i(TAG, "onCaptureFailed: ");
                            }
                        }, mCameraHandler);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {

                }
            });
            mCameraDevice.createCaptureSession(sc);

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "startPreview failed:" + e.toString());
        }
    }

    //清除预览Session
    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    /**
     * **************************************清除操作************************************************
     */
    public void stopCamera() {
        try {
            if (mPreviewSession != null) {
                mPreviewSession.close();
                mPreviewSession = null;
            }

            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

            if (mCameraHandler != null) {
                mCameraHandler.removeCallbacksAndMessages(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "stopCamera failed:" + e.toString());
        }
    }

    @Nullable
    @Override
    public ComponentName startForegroundService(Intent service) {
        return super.startForegroundService(service);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: ");
        super.onDestroy();
    }
}
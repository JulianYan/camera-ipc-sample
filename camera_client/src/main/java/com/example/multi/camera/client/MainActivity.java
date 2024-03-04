package com.example.multi.camera.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.multi.camera.service.ICameraService;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MultiCameraClient";
    private ICameraService iCameraService;

    //views
    private SurfaceView mPreview;
    private TextView mPreviewIsOff;

    //handler
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    // 当前获取的帧数
    private int currentIndex = 0;
    // 处理的间隔帧，可根据自己情况修改
    private static final int PROCESS_INTERVAL = 2;
    private ImageReader imageReader;
    private Surface mSurface;
    private int mWidth;
    private int mHeght;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPreview = findViewById(R.id.preview);
        mPreview.getHolder().addCallback(new SurfaceHolder.Callback2() {
            @Override
            public void surfaceRedrawNeeded(@NonNull SurfaceHolder holder) {

            }

            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                mSurface = holder.getSurface();
                Log.i(TAG, "surfaceCreated: " + mSurface);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                mSurface = holder.getSurface();
                mWidth = width;
                mHeght = height;
                Log.i(TAG, "surfaceCreated: " + mSurface+",wxh="+width+"x"+height);

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
//        mPreview.setVisibility(View.INVISIBLE);
        mPreviewIsOff = findViewById(R.id.preview_is_off);
        mPreviewIsOff.setVisibility(View.VISIBLE);

        findViewById(R.id.start_preview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //连接服务
                Intent intent = new Intent();
                intent.setAction("com.example.camera.aidl");
                intent.setPackage("com.example.multi.camera.service");
                boolean ret = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                Log.d(TAG, "bindService" + ret);
                //更新视图
                mPreview.setVisibility(View.VISIBLE);
                mPreviewIsOff.setVisibility(View.INVISIBLE);
            }
        });
        findViewById(R.id.stop_preview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //断开服务
                unbindService(mConnection);
                iCameraService = null;
                Log.d(TAG, "unbindService");

                //更新视图
                mPreview.setVisibility(View.INVISIBLE);
                mPreviewIsOff.setVisibility(View.VISIBLE);
            }
        });

        mCameraThread = new HandlerThread("CameraClientThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            iCameraService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            iCameraService = ICameraService.Stub.asInterface(service);
            try {
                iCameraService.onSurfaceShared(mSurface);
            } catch (RemoteException e) {
                Log.e(TAG, "onServiceConnected: ", e);
            }
        }
    };

    @Override
    protected void onDestroy() {
        if (iCameraService != null) {
            unbindService(mConnection);
            Log.d(TAG, "unbindService");
        }
        super.onDestroy();
    }
}
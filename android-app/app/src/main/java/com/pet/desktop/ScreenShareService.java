package com.pet.desktop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ScreenShareService extends Service {
    static final String EXTRA_RESULT_CODE = "screen_result_code";
    static final String EXTRA_RESULT_DATA = "screen_result_data";
    private static final int NOTIFICATION_ID = 2016;
    private static final String CHANNEL_ID = "screen_share";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private OkHttpClient client;
    private WebSocket webSocket;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = buildNotification("正在共享当前屏幕");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            finishLater();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            finishLater();
            return START_NOT_STICKY;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            finishLater();
            return START_NOT_STICKY;
        }
        handler.postDelayed(() -> {
            try {
                projection = manager.getMediaProjection(resultCode, resultData);
                registerProjectionCallback();
                captureOnce();
            } catch (Exception exc) {
                finishLater();
            }
        }, 900);        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen Share", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Mobile Pet")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.star_on)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("Mobile Pet")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.star_on)
                .build();
    }

    private void registerProjectionCallback() {
        if (projection == null || projectionCallback != null) return;
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                handler.post(() -> {
                    releaseProjectionResources(false);
                    finishLater();
                });
            }
        };
        projection.registerCallback(projectionCallback, handler);
    }

    private void captureOnce() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null || projection == null) {
            finishLater();
            return;
        }
        android.graphics.Point size = new android.graphics.Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        int width = Math.max(1, size.x);
        int height = Math.max(1, size.y);
        int density = getResources().getDisplayMetrics().densityDpi;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "mobile_pet_screen_once",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
        handler.postDelayed(() -> {
            try {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    finishLater();
                    return;
                }
                String base64 = imageToBase64(image);
                image.close();
                releaseProjectionResources(true);
                openPreview(base64);
            } catch (Exception ignored) {
                finishLater();
            }
        }, 900);    }

    private String imageToBase64(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
        Bitmap scaled = scaleDown(cropped, 900);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
        bitmap.recycle();
        if (cropped != bitmap) cropped.recycle();
        if (scaled != cropped) scaled.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap scaleDown(Bitmap source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longSide = Math.max(width, height);
        if (longSide <= maxSide) return source;
        float ratio = maxSide / (float) longSide;
        return Bitmap.createScaledBitmap(source, Math.round(width * ratio), Math.round(height * ratio), true);
    }

    private void openPreview(String base64) {
        Intent broadcast = new Intent(ChatActivity.ACTION_SCREEN_CAPTURED);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(ChatActivity.EXTRA_SCREEN_BASE64, base64);
        sendBroadcast(broadcast);

        Intent preview = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        preview.putExtra(ChatActivity.EXTRA_SCREEN_BASE64, base64);
        try {
            startActivity(preview);
        } catch (Exception ignored) {}
        finishLater();
    }

    private String getPetDeviceId() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String id = prefs.getString("device_id", "");
        if (id == null || id.length() == 0) {
            id = "android-" + UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void finishLater() {
        handler.postDelayed(this::stopSelf, 100);
    }

    private void releaseProjectionResources(boolean stopProjection) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            if (projectionCallback != null) {
                try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
                projectionCallback = null;
            }
            if (stopProjection) {
                try { projection.stop(); } catch (Exception ignored) {}
            }
            projection = null;
        }
    }

    private void cleanup() {
        if (webSocket != null) webSocket.close(1000, "screen snapshot sent");
        if (client != null) client.dispatcher().executorService().shutdown();
        releaseProjectionResources(true);
    }
}

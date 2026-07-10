package com.pet.desktop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import android.util.Log;

public class ScreenShareRequestActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 2016;
    private static final String TAG = "ScreenShareRequest";
    private boolean hasRequested = false;
    private String requestId = "";
    private String requestAction = "screen_share";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Intent launchIntent = getIntent();
        if (launchIntent != null) {
            requestId = launchIntent.getStringExtra(PetOverlayService.EXTRA_REQUEST_ID) != null ? launchIntent.getStringExtra(PetOverlayService.EXTRA_REQUEST_ID) : "";
            requestAction = launchIntent.getStringExtra(PetOverlayService.EXTRA_REQUEST_ACTION) != null ? launchIntent.getStringExtra(PetOverlayService.EXTRA_REQUEST_ACTION) : "screen_share";
        }
        if (savedInstanceState != null) {
            hasRequested = savedInstanceState.getBoolean("hasRequested", false);
            requestId = savedInstanceState.getString("requestId", requestId);
            requestAction = savedInstanceState.getString("requestAction", requestAction);
        }
        if (!hasRequested) {
            requestScreenShare();
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("hasRequested", hasRequested);
        outState.putString("requestId", requestId);
        outState.putString("requestAction", requestAction);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                savePendingShareRequest();
                Intent intent = new Intent(this, ScreenShareService.class);
                intent.putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode);
                intent.putExtra(ScreenShareService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                Toast.makeText(this, "已取消屏幕共享", Toast.LENGTH_SHORT).show();
                sendRejectedResponse();
                reshowPet("已取消共享");
            }
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void requestScreenShare() {
        hasRequested = true;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "当前设备不支持屏幕共享", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        } catch (Exception exc) {
            Log.e(TAG, "request screen share failed", exc);
            Toast.makeText(this, "截图权限请求失败", Toast.LENGTH_SHORT).show();
            reshowPet();
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void savePendingShareRequest() {
        if (requestId == null || requestId.length() == 0) return;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString(ChatActivity.KEY_PENDING_SCREEN_REQUEST_ID, requestId)
                .putString(ChatActivity.KEY_PENDING_SCREEN_REQUEST_ACTION, requestAction)
                .apply();
    }

    private void sendRejectedResponse() {
        if (requestId == null || requestId.length() == 0) return;
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(PetOverlayService.ACTION_SEND_REQUEST_RESPONSE);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_ID, requestId);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_ACTION, requestAction);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_STATUS, "rejected");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {}
    }

    private void reshowPet() {
        reshowPet("");
    }

    private void reshowPet(String bubbleText) {
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(PetOverlayService.ACTION_RESHOW);
        if (bubbleText != null && bubbleText.length() > 0) {
            intent.putExtra(PetOverlayService.EXTRA_BUBBLE, bubbleText);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {}
    }
}

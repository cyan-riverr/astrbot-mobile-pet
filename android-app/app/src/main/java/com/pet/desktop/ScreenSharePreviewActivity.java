package com.pet.desktop;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ScreenSharePreviewActivity extends Activity {
    static final String EXTRA_IMAGE_BASE64 = "image_base64";
    private String imageBase64 = "";
    private EditText captionInput;
    private OkHttpClient client;
    private WebSocket webSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        imageBase64 = getIntent().getStringExtra(EXTRA_IMAGE_BASE64);
        if (imageBase64 == null || imageBase64.length() == 0) {
            finishAndRestorePet();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0x66000000);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        card.setBackgroundResource(getResources().getIdentifier("bubble_purple", "drawable", getPackageName()));

        TextView title = new TextView(this);
        title.setText("要把这张屏幕发给我吗");
        title.setTextSize(15);
        title.setTextColor(Color.rgb(94, 53, 177));
        title.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Color.rgb(245, 240, 255));
        try {
            byte[] bytes = Base64.decode(imageBase64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            preview.setImageBitmap(bitmap);
        } catch (Exception ignored) {}
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(150));
        previewLp.topMargin = dp(10);
        card.addView(preview, previewLp);

        captionInput = new EditText(this);
        captionInput.setMinLines(1);
        captionInput.setMaxLines(3);
        captionInput.setTextSize(14);
        captionInput.setHint("配一句话一起发");
        captionInput.setSingleLine(false);
        captionInput.setTextColor(Color.rgb(45, 35, 75));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(10);
        card.addView(captionInput, inputLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(10);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finishAndRestorePet());
        row.addView(cancel, new LinearLayout.LayoutParams(dp(76), dp(42)));

        Button send = new Button(this);
        send.setText("发送");
        send.setOnClickListener(v -> sendSnapshot());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(76), dp(42));
        sendLp.leftMargin = dp(8);
        row.addView(send, sendLp);
        card.addView(row, rowLp);

        root.addView(card, new LinearLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(330)), -2));
        setContentView(root);

        captionInput.requestFocus();
        captionInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(captionInput, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void sendSnapshot() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String url = prefs.getString(MainActivity.KEY_WS_URL, "").trim();
        if (url.length() == 0) {
            Toast.makeText(this, "还没有配置 WebSocket 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String captionText = captionInput.getText().toString().trim();
        if (captionText.length() == 0) captionText = "[pet-screen] screen shared";
        final String caption = captionText;
        client = new OkHttpClient();
        webSocket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                String json = "{\"type\":\"screen_snapshot\",\"mime\":\"image/jpeg\",\"image_base64\":\"" + imageBase64 + "\",\"caption\":\"" + escapeJson(caption) + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}";
                ws.send(json);
                runOnUiThread(() -> {
                    Toast.makeText(ScreenSharePreviewActivity.this, "已发送", Toast.LENGTH_SHORT).show();
                    finishAndRestorePet("共享完成");
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                runOnUiThread(() -> Toast.makeText(ScreenSharePreviewActivity.this, "发送失败", Toast.LENGTH_SHORT).show());
            }
        });
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

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void finishAndRestorePet() {
        finishAndRestorePet("已取消共享");
    }

    private void finishAndRestorePet(String bubbleText) {
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(PetOverlayService.ACTION_RESHOW);
        if (bubbleText != null && bubbleText.length() > 0) {
            intent.putExtra(PetOverlayService.EXTRA_BUBBLE, bubbleText);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (Exception ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (webSocket != null) webSocket.close(1000, "preview closed");
        if (client != null) client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

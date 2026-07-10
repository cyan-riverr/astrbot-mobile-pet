package com.pet.desktop;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


public class ChatActivity extends Activity {
    private static final String KEY_CHAT_HISTORY = "chat_history";
    static final String KEY_PENDING_SCREEN_REQUEST_ID = "pending_screen_request_id";
    static final String KEY_PENDING_SCREEN_REQUEST_ACTION = "pending_screen_request_action";
    static final String ACTION_SCREEN_CAPTURED = "com.pet.desktop.action.SCREEN_CAPTURED";
    static final String EXTRA_SCREEN_BASE64 = "screen_image_base64";
    private LinearLayout messages;
    private EditText input;
    private TextView imageStatus;
    private TextView imagePick;
    private String selectedImageBase64 = "";
    private boolean screenSnapshotSent = false;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleIncomingImage(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, new IntentFilter(ACTION_SCREEN_CAPTURED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, new IntentFilter(ACTION_SCREEN_CAPTURED));
        }
        loadHistory();
        handleIncomingImage(getIntent());
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0x00000000);

        BubblePanelView bubbleShell = new BubblePanelView(this);
        bubbleShell.setPadding(dp(10), dp(8), dp(10), dp(8));
        bubbleShell.setShapeSeed(5189L);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(2), dp(1), dp(2), dp(1));
        bubbleShell.addView(bubble, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        imagePick = new TextView(this);
        imagePick.setText("图");
        imagePick.setTextSize(13);
        imagePick.setGravity(Gravity.CENTER);
        imagePick.setTextColor(Color.rgb(94, 53, 177));
        imagePick.setBackgroundResource(getResources().getIdentifier("bg_chat_icon", "drawable", getPackageName()));
        imagePick.setOnClickListener(v -> {
            if (selectedImageBase64.length() > 0) {
                sendPendingScreenRequestStatus("rejected");
                clearSelectedImage();
                sendPetAction(PetOverlayService.ACTION_RESHOW);
            } else {
                requestScreenShare();
            }
        });
        bubble.addView(imagePick, new LinearLayout.LayoutParams(dp(46), dp(46)));

        input = new EditText(this);
        input.setMinLines(1);
        input.setMaxLines(3);
        input.setTextSize(15);
        input.setHint("说点什么");
        input.setSingleLine(false);
        input.setTextColor(Color.rgb(45, 35, 75));
        input.setHintTextColor(Color.rgb(150, 140, 178));
        input.setBackgroundColor(0x00000000);
        input.setPadding(dp(10), 0, dp(6), 0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        inputLp.leftMargin = dp(7);
        bubble.addView(input, inputLp);

        TextView send = new TextView(this);
        send.setText("发");
        send.setTextSize(16);
        send.setGravity(Gravity.CENTER);
        send.setTextColor(Color.rgb(74, 74, 138));
        send.setBackgroundResource(getResources().getIdentifier("bg_chat_send", "drawable", getPackageName()));
        send.setOnClickListener(v -> sendChat());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(54), dp(46));
        sendLp.leftMargin = dp(8);
        bubble.addView(send, sendLp);

        imageStatus = new TextView(this);
        imageStatus.setText("");
        imageStatus.setTextSize(12);
        imageStatus.setTextColor(Color.rgb(94, 53, 177));
        imageStatus.setGravity(Gravity.CENTER);
        imageStatus.setVisibility(TextView.GONE);

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(bubbleShell, new LinearLayout.LayoutParams(-1, -2));
        stack.addView(imageStatus, new LinearLayout.LayoutParams(-1, -2));
        root.addView(stack, new LinearLayout.LayoutParams(dp(330), -2));

        setContentView(root);
    }

    private void sendChat() {
        String text = input.getText().toString().trim();
        if (text.length() == 0 && selectedImageBase64.length() == 0) return;
        if (selectedImageBase64.length() > 0) {
            String caption = text.length() > 0 ? text : "[pet-screen] screen shared";
            Intent intent = new Intent(this, PetOverlayService.class);
            intent.setAction(PetOverlayService.ACTION_SEND_SCREEN);
            intent.putExtra(PetOverlayService.EXTRA_TEXT, caption);
            intent.putExtra(PetOverlayService.EXTRA_IMAGE_BASE64, selectedImageBase64);
            startPetService(intent);
            screenSnapshotSent = true;
            clearPendingScreenRequest();
            addMessage("你", "[屏幕截图] " + text);
            clearSelectedImage();
            input.setText("");
            return;
        }
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(PetOverlayService.ACTION_SEND_CHAT);
        intent.putExtra(PetOverlayService.EXTRA_TEXT, text);
        startPetService(intent);
        addMessage("你", text);
        input.setText("");
    }

    private void requestScreenShare() {
        sendPetAction(PetOverlayService.ACTION_HIDE);
        Intent intent = new Intent(this, ScreenShareRequestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(intent);
            finish();
            overridePendingTransition(0, 0);
        } catch (Exception exc) {
            sendPetAction(PetOverlayService.ACTION_RESHOW);
            Toast.makeText(this, "截图权限请求失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingImage(intent);
    }

    private void handleIncomingImage(Intent intent) {
        if (intent == null || imagePick == null || imageStatus == null) return;
        String base64 = intent.getStringExtra(EXTRA_SCREEN_BASE64);
        if (base64 == null || base64.length() == 0) return;
        selectedImageBase64 = base64;
        imagePick.setText("已截");
        imagePick.setBackgroundResource(getResources().getIdentifier("bg_chat_icon", "drawable", getPackageName()));
        imageStatus.setText("已放入屏幕截图，打字后点发送");
        imageStatus.setVisibility(TextView.VISIBLE);
    }

    private void sendPetAction(String action) {
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(action);
        startPetService(intent);
    }

    private void startPetService(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {}
    }

    private void sendPendingScreenRequestStatus(String status) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String requestId = prefs.getString(KEY_PENDING_SCREEN_REQUEST_ID, "");
        if (requestId == null || requestId.length() == 0) return;
        String requestAction = prefs.getString(KEY_PENDING_SCREEN_REQUEST_ACTION, "screen_share");
        Intent intent = new Intent(this, PetOverlayService.class);
        intent.setAction(PetOverlayService.ACTION_SEND_REQUEST_RESPONSE);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_ID, requestId);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_ACTION, requestAction);
        intent.putExtra(PetOverlayService.EXTRA_REQUEST_STATUS, status);
        startPetService(intent);
        clearPendingScreenRequest();
    }

    private void clearPendingScreenRequest() {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING_SCREEN_REQUEST_ID)
                .remove(KEY_PENDING_SCREEN_REQUEST_ACTION)
                .apply();
    }

    private void clearSelectedImage() {
        selectedImageBase64 = "";
        imagePick.setText("图");
        imagePick.setBackgroundResource(getResources().getIdentifier("bg_chat_icon", "drawable", getPackageName()));
        imageStatus.setText("");
        imageStatus.setVisibility(TextView.GONE);
    }

    private void addMessage(String who, String text) {
        appendHistory(who, text);
    }

    private void loadHistory() {
    }

    private void appendHistory(String who, String text) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String history = prefs.getString(KEY_CHAT_HISTORY, "");
        String line = (who + "：" + text).replace("\n", " ");
        String next = history.length() == 0 ? line : history + "\n" + line;
        String[] lines = next.split("\n");
        if (lines.length > 80) {
            StringBuilder kept = new StringBuilder();
            for (int i = lines.length - 80; i < lines.length; i++) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(lines[i]);
            }
            next = kept.toString();
        }
        prefs.edit().putString(KEY_CHAT_HISTORY, next).apply();
    }

    private String getPetDeviceId() {
        return android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    }

    private static String extractValue(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        if (json.charAt(start) == '\"') {
            start++;
            StringBuilder out = new StringBuilder();
            boolean escape = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escape) {
                    out.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '\"') {
                    return out.toString();
                } else {
                    out.append(c);
                }
            }
        }
        return "";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (selectedImageBase64.length() > 0 && !screenSnapshotSent) sendPendingScreenRequestStatus("rejected");
        sendPetAction(PetOverlayService.ACTION_RESHOW);
        super.onDestroy();
    }
}

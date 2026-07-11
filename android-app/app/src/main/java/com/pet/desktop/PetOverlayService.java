package com.pet.desktop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

public class PetOverlayService extends Service {
    public static final String ACTION_RESHOW = "com.pet.desktop.action.RESHOW";
    public static final String ACTION_HIDE = "com.pet.desktop.action.HIDE";
    public static final String ACTION_SPEAK = "com.pet.desktop.action.SPEAK";
    public static final String ACTION_SEND_CHAT = "com.pet.desktop.action.SEND_CHAT";
    public static final String ACTION_SEND_SCREEN = "com.pet.desktop.action.SEND_SCREEN";
    public static final String ACTION_SEND_REQUEST_RESPONSE = "com.pet.desktop.action.SEND_REQUEST_RESPONSE";
    public static final String ACTION_REPLY_RECEIVED = "com.pet.desktop.action.REPLY_RECEIVED";
    public static final String EXTRA_BUBBLE = "bubble";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_IMAGE_BASE64 = "image_base64";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_REQUEST_ACTION = "request_action";
    public static final String EXTRA_REQUEST_STATUS = "request_status";
    private static final int NOTIFICATION_ID = 1016;
    private static final String CHANNEL_ID = "mobile_pet";
    private WindowManager windowManager;
    private View petView;
    private TextView face;
    private ImageView petImage;
    private SpeechBubbleView bubble;
    private FrameLayout bubbleContainer;
    private TextView starLeft;
    private TextView starRight;
    private TextView pageIndicator;
    private BubblePanelView menuPanel;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams menuParams;
    private boolean menuAdded;
    private WindowManager.LayoutParams bubbleParams;
    private int desiredBubbleWidth;
    private int desiredBubbleHeight;
    private boolean bubbleAdded;
    private boolean overlayAdded;
    private OkHttpClient client;
    private WebSocket webSocket;
    private boolean webSocketConnecting;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable clearBubbleRunnable;
    private Runnable stateResetRunnable;
    private Runnable wanderRunnable;
    private Runnable pageBubbleRunnable;
    private Runnable typeBubbleRunnable;
    private Runnable commandQueueRunnable;
    private final ArrayList<PetCommand> commandQueue = new ArrayList<>();
    private boolean commandQueueRunning;
    private final Runnable reconnectRunnable = this::connectWebSocket;
    private boolean serviceDestroyed;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private long downTime;
    private boolean dragging;
    private boolean autoMoving;
    private boolean autoWanderEnabled = true;
    private int keyboardLift = 0;
    private Bitmap currentHitBitmap;
    private String currentHitPath = "";
    private String pendingRequestId = "";
    private String pendingRequestAction = "";
    private final Random random = new Random();
    private float petScale = 1.0f;
    private int facingDirection = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification("桌宠运行中"));
        SharedPreferences servicePrefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        petScale = servicePrefs.getInt(MainActivity.KEY_PET_SCALE, 100) / 100f;
        autoWanderEnabled = servicePrefs.getBoolean(MainActivity.KEY_AUTO_WANDER, true);
        showOverlay();
        connectWebSocket();
        scheduleWander();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            hideOverlayView();
        } else if (intent != null && ACTION_RESHOW.equals(intent.getAction())) {
            ensureOverlayVisible();
            String bubbleText = intent.getStringExtra(EXTRA_BUBBLE);
            if (bubbleText != null && bubbleText.length() > 0) {
                updateBubble(bubbleText, 1800);
            }
        } else if (intent != null && ACTION_SPEAK.equals(intent.getAction())) {
            ensureOverlayVisible();
            String text = intent.getStringExtra(EXTRA_BUBBLE);
            showReply(text);
        } else if (intent != null && ACTION_SEND_CHAT.equals(intent.getAction())) {
            sendChatMessage(intent.getStringExtra(EXTRA_TEXT));
        } else if (intent != null && ACTION_SEND_SCREEN.equals(intent.getAction())) {
            sendScreenSnapshot(intent.getStringExtra(EXTRA_TEXT), intent.getStringExtra(EXTRA_IMAGE_BASE64));
            ensureOverlayVisible();
        } else if (intent != null && ACTION_SEND_REQUEST_RESPONSE.equals(intent.getAction())) {
            sendRequestResponse(
                    intent.getStringExtra(EXTRA_REQUEST_ID),
                    intent.getStringExtra(EXTRA_REQUEST_ACTION),
                    intent.getStringExtra(EXTRA_REQUEST_STATUS)
            );
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        mainHandler.removeCallbacks(reconnectRunnable);
        if (wanderRunnable != null) mainHandler.removeCallbacks(wanderRunnable);
        if (commandQueueRunnable != null) mainHandler.removeCallbacks(commandQueueRunnable);
        commandQueue.clear();
        commandQueueRunning = false;
        if (webSocket != null) webSocket.close(1000, "service stopped");
        if (client != null) client.dispatcher().executorService().shutdown();
        if (windowManager != null && bubbleAdded && bubbleContainer != null) {
            try { windowManager.removeView(bubbleContainer); } catch (Exception ignored) {}
            bubbleAdded = false;
        }
        if (windowManager != null && menuAdded && menuPanel != null) {
            try { windowManager.removeView(menuPanel); } catch (Exception ignored) {}
        }
        if (windowManager != null && petView != null && overlayAdded) {
            try { windowManager.removeView(petView); } catch (Exception ignored) {}
            overlayAdded = false;
        }
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mobile Pet", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Mobile Pet")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.star_on)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("Mobile Pet")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.star_on)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
    }

    private void connectWebSocket() {
        if (serviceDestroyed || webSocketConnecting) return;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String url = prefs.getString(MainActivity.KEY_WS_URL, "").trim();
        if (url.length() == 0) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        webSocketConnecting = true;
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .pingInterval(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocketConnecting = false;
                updateBubble("connected");
                sendEvent("hello");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                String type = extractValue(text, "type");
                if ("ack".equals(type) || "pong".equals(type)) return;
                String state = extractValue(text, "state");
                if (state.length() == 0) state = extractValue(text, "emotion");
                String message = extractValue(text, "message");
                if (message.length() == 0) message = extractValue(text, "text");
                if (message.length() == 0) message = extractValue(text, "bubble");
                int duration = extractInt(text, "duration", 5000);
                Log.d("PetBubble", "raw=" + text);
                Log.d("PetBubble", "type=" + type + ", state=" + state + ", message=" + message + ", duration=" + duration);
                enqueuePetCommand(type, state, message, duration,
                        extractValue(text, "request_id"), extractValue(text, "action"),
                        extractInt(text, "dx", 0), extractInt(text, "dy", 0),
                        extractInt(text, "stateDuration", duration));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                webSocketConnecting = false;
                PetOverlayService.this.webSocket = null;
                updateBubble("offline");
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                webSocketConnecting = false;
                PetOverlayService.this.webSocket = null;
                updateBubble("closed");
                scheduleReconnect();
            }
        });
    }

    private void enqueuePetCommand(String type, String state, String message, int duration,
                                   String requestId, String requestAction,
                                   int dx, int dy, int stateDuration) {
        runOnMain(() -> {
            commandQueue.add(new PetCommand(type, state, message, duration, requestId, requestAction, dx, dy, stateDuration));
            playNextPetCommand();
        });
    }

    private void playNextPetCommand() {
        if (commandQueueRunning || commandQueue.isEmpty()) return;
        PetCommand command = commandQueue.remove(0);
        commandQueueRunning = true;
        executePetCommand(command);
        int delay = commandDelayMs(command);
        commandQueueRunnable = () -> {
            commandQueueRunning = false;
            playNextPetCommand();
        };
        mainHandler.postDelayed(commandQueueRunnable, delay);
    }

    private int commandDelayMs(PetCommand command) {
        int base = Math.max(900, Math.min(Math.max(command.duration, command.stateDuration), 6000));
        if ("walk".equals(command.type) || "walk".equals(command.state)) {
            base = Math.max(900, Math.min(command.duration, 6000));
        }
        return base + 250;
    }

    private void executePetCommand(PetCommand command) {
        String type = command.type;
        String state = command.state;
        String message = command.message;
        int duration = command.duration;
        if ("pet_request".equals(type)) {
            pendingRequestId = command.requestId;
            pendingRequestAction = command.requestAction;
            if (state.length() > 0) updateFace(state, duration);
            if (message.length() > 0) updateBubble(message, Math.max(duration, 5000));
            return;
        }
        if ("reply".equals(type) || "message".equals(type)) {
            showReply(message, Math.max(duration, 5000), state.length() > 0 ? state : "speak");
            return;
        }
        if ("walk".equals(type)) {
            walkByCommand(command.dx, command.dy, duration);
            if (message.length() > 0) updateBubble(message, Math.max(duration, 5000));
            return;
        }
        if (state.length() > 0) {
            if ("walk".equals(state)) {
                int dx = command.dx;
                int dy = command.dy;
                if (dx == 0 && dy == 0) {
                    dx = random.nextBoolean() ? 90 : -90;
                    dy = -50 + random.nextInt(101);
                }
                walkByCommand(dx, dy, command.stateDuration);
            } else {
                updateFace(state, command.stateDuration);
            }
        }
        if (message.length() > 0) updateBubble(message, duration);
    }

    private static class PetCommand {
        final String type;
        final String state;
        final String message;
        final int duration;
        final String requestId;
        final String requestAction;
        final int dx;
        final int dy;
        final int stateDuration;

        PetCommand(String type, String state, String message, int duration,
                   String requestId, String requestAction, int dx, int dy, int stateDuration) {
            this.type = type != null ? type : "";
            this.state = state != null ? state : "";
            this.message = message != null ? message : "";
            this.duration = duration;
            this.requestId = requestId != null ? requestId : "";
            this.requestAction = requestAction != null ? requestAction : "";
            this.dx = dx;
            this.dy = dy;
            this.stateDuration = stateDuration;
        }
    }

    private void scheduleReconnect() {
        if (serviceDestroyed) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, 3000);
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        FrameLayout root = new FrameLayout(this);
        root.setPadding(0, 0, 0, 0);

        face = new TextView(this);
        face.setText("🐇");
        face.setTextSize(34);
        face.setGravity(Gravity.CENTER);
        face.setTextColor(0xFFFFFFFF);
        face.setVisibility(View.GONE);
        face.setBackgroundResource(getResources().getIdentifier("pet_face_moon_rabbit", "drawable", getPackageName()));
        FrameLayout.LayoutParams faceParams = new FrameLayout.LayoutParams(sdp(84), sdp(84), Gravity.TOP | Gravity.LEFT);
        faceParams.leftMargin = sdp(6);
        faceParams.topMargin = sdp(6);
        root.addView(face, faceParams);

        petImage = new ImageView(this);
        petImage.setVisibility(View.GONE);
        petImage.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(sdp(96), sdp(96), Gravity.TOP | Gravity.LEFT);
        imageParams.leftMargin = 0;
        imageParams.topMargin = 0;
        root.addView(petImage, imageParams);
        updateFace("idle", 0);

        bubbleContainer = new FrameLayout(this);
        bubbleContainer.setVisibility(View.GONE);

        bubble = new SpeechBubbleView(this);
        bubble.setText("connecting");
        FrameLayout.LayoutParams bubbleTextParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        bubbleContainer.addView(bubble, bubbleTextParams);

        starLeft = new TextView(this);
        starLeft.setText("✦");
        starLeft.setTextSize(17);
        starLeft.setTextColor(0xFFFFE889);
        starLeft.setGravity(Gravity.CENTER);
        starLeft.setRotation(-10f);
        FrameLayout.LayoutParams starLeftParams = new FrameLayout.LayoutParams(sdp(20), sdp(28), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        bubbleContainer.addView(starLeft, starLeftParams);

        starRight = new TextView(this);
        starRight.setText("✦");
        starRight.setTextSize(17);
        starRight.setTextColor(0xFFFFE889);
        starRight.setGravity(Gravity.CENTER);
        starRight.setRotation(10f);
        FrameLayout.LayoutParams starRightParams = new FrameLayout.LayoutParams(sdp(20), sdp(28), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bubbleContainer.addView(starRight, starRightParams);

        pageIndicator = new TextView(this);
        pageIndicator.setTextSize(9);
        pageIndicator.setTextColor(0xEFFFFFFF);
        pageIndicator.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageIndicator.setGravity(Gravity.CENTER);
        pageIndicator.setShadowLayer(sdp(1), 0, sdp(1), 0x50000000);
        pageIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(sdp(34), sdp(16), Gravity.RIGHT | Gravity.BOTTOM);
        pageParams.rightMargin = sdp(28);
        pageParams.bottomMargin = sdp(5);
        bubbleContainer.addView(pageIndicator, pageParams);

        addBubbleDot(bubbleContainer, 154, 8, 6, "bubble_dot");
        addBubbleDot(bubbleContainer, 165, 14, 4, "bubble_dot_small");
        addBubbleDot(bubbleContainer, 149, 45, 4, "bubble_dot_small");
        addBubbleDot(bubbleContainer, 35, 48, 5, "bubble_dot");

        // bubbleContainer is now a separate floating window, not part of petView root

        menuPanel = new BubblePanelView(this);
        menuPanel.setVisibility(View.GONE);
        menuPanel.setPadding(sdp(18), sdp(14), sdp(18), sdp(14));
        menuPanel.setShapeSeed(2407L);
        LinearLayout menuContent = new LinearLayout(this);
        menuContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout firstMenuRow = new LinearLayout(this);
        firstMenuRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout secondMenuRow = new LinearLayout(this);
        secondMenuRow.setOrientation(LinearLayout.HORIZONTAL);
        menuContent.addView(firstMenuRow, new LinearLayout.LayoutParams(-2, sdp(38)));
        LinearLayout.LayoutParams secondRowLp = new LinearLayout.LayoutParams(-2, sdp(38));
        secondRowLp.topMargin = sdp(3);
        menuContent.addView(secondMenuRow, secondRowLp);
        menuPanel.addView(menuContent, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        String[] labels = {"✦ 戳戳", "♡ 抱抱", "☆ 摸摸", "◇ 投喂", "✧ 叫你", "✎ 聊天"};
        String[] actions = {"poke", "hug", "pat", "feed", "call", "chat"};
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            final String action = actions[i];
            TextView item = new TextView(this);
            item.setText(label);
            item.setTextSize(12);
            item.setSingleLine(true);
            item.setTextColor(0xFF4B2A87);
            item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.setGravity(Gravity.CENTER);
            item.setPadding(sdp(5), sdp(5), sdp(5), sdp(5));
            item.setBackgroundResource(getResources().getIdentifier("menu_item_transparent_ripple", "drawable", getPackageName()));
            item.setOnClickListener(v -> {
                hideActionMenu();
                if ("chat".equals(action)) {
                    Intent intent = new Intent(this, ChatActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(intent);
                } else if ("screen_share".equals(action)) {
                    Intent intent = new Intent(this, ScreenShareRequestActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    try {
                        startActivity(intent);
                        updateBubble("准备共享屏幕", 1800);
                    } catch (Exception exc) {
                        updateBubble("共享屏幕启动失败", 2400);
                    }
                } else {
                    sendEvent(action);
                }
            });
            LinearLayout targetRow = i < 3 ? firstMenuRow : secondMenuRow;
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(sdp(74), sdp(34));
            itemLp.setMargins(sdp(2), sdp(2), sdp(2), sdp(2));
            targetRow.addView(item, itemLp);
        }

        petView = root;
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(compactWindowWidth(), compactWindowHeight(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 80;
        params.y = 220;

        petView.setOnTouchListener(this::handleTouch);
        windowManager.addView(petView, params);
        overlayAdded = true;
        startKeyboardWatch();
    }

    private void startKeyboardWatch() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateKeyboardLift();
                if (petView != null) mainHandler.postDelayed(this, 500);
            }
        }, 500);
    }

    private void updateKeyboardLift() {
        if (params == null || petView == null || windowManager == null) return;
        int previous = keyboardLift;
        keyboardLift = isKeyboardLikelyVisible() ? sdp(320) : 0;
        if (previous != keyboardLift) {
            clampToScreen();
            try { windowManager.updateViewLayout(petView, params); } catch (Exception ignored) {}
        }
    }

    private boolean isKeyboardLikelyVisible() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        return imm != null && imm.isAcceptingText();
    }

    private void hideOverlayView() {
        hideActionMenu();
        if (windowManager != null && bubbleAdded && bubbleContainer != null) {
            try { windowManager.removeView(bubbleContainer); } catch (Exception ignored) {}
            bubbleAdded = false;
        }
        if (windowManager != null && petView != null && overlayAdded) {
            try {
                windowManager.removeView(petView);
            } catch (Exception ignored) {}
            overlayAdded = false;
        }
    }

    private void ensureOverlayVisible() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (petView == null || params == null) {
            showOverlay();
            return;
        }
        if (!overlayAdded) {
            try {
                updateFace("idle", 0);
                windowManager.addView(petView, params);
                overlayAdded = true;
            } catch (IllegalArgumentException exc) {
                petView = null;
                params = null;
                menuAdded = false;
                overlayAdded = false;
                showOverlay();
            }
            return;
        }
        try {
            safeUpdateViewLayout();
        } catch (IllegalArgumentException exc) {
            petView = null;
            params = null;
            menuAdded = false;
            overlayAdded = false;
            showOverlay();
        }
    }

    private boolean handleTouch(View view, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!isTouchablePetPixel(event)) return false;
                autoMoving = false;
                scheduleWander();
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                downTime = System.currentTimeMillis();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float moveDx = Math.abs(event.getRawX() - downX);
                float moveDy = Math.abs(event.getRawY() - downY);
                if (!dragging && (moveDx > sdp(18) || moveDy > sdp(18))) {
                    dragging = true;
                    hideActionMenu();
                    hideBubbleWindow();
                    updateFace("drag", 0);
                }
                if (dragging) {
                    updateFacing(-(event.getRawX() - downX));
                    params.x = startX + Math.round(event.getRawX() - downX);
                    params.y = startY + Math.round(event.getRawY() - downY);
                    clampToScreen();
                    safeUpdateViewLayout();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    updateFace("idle", 0);
                    scheduleWander();
                    return true;
                }
                if (params.x != startX || params.y != startY) {
                    params.x = startX;
                    params.y = startY;
                    clampToScreen();
                    safeUpdateViewLayout();
                }
                if (menuAdded) {
                    hideActionMenu();
                    scheduleWander();
                } else if (pendingRequestId.length() > 0) {
                    String requestId = pendingRequestId;
                    String requestAction = pendingRequestAction;
                    pendingRequestId = "";
                    pendingRequestAction = "";
                    handleAcceptedRequest(requestId, requestAction);
                    scheduleWander();
                } else {
                    showActionMenu();
                    scheduleWander();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                updateFace("idle", 0);
                scheduleWander();
                return false;
            default:
                return false;
        }
    }


    private boolean isTouchablePetPixel(MotionEvent event) {
        if (petImage == null || petImage.getVisibility() != View.VISIBLE || currentHitBitmap == null) return true;
        if (petImage.getWidth() <= 0 || petImage.getHeight() <= 0) return true;
        float localX = event.getX() - petImage.getLeft();
        float localY = event.getY() - petImage.getTop();
        if (localX < 0 || localY < 0 || localX >= petImage.getWidth() || localY >= petImage.getHeight()) return false;
        int bitmapWidth = currentHitBitmap.getWidth();
        int bitmapHeight = currentHitBitmap.getHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return true;
        int pixelX = Math.min(bitmapWidth - 1, Math.max(0, Math.round(localX * bitmapWidth / petImage.getWidth())));
        int pixelY = Math.min(bitmapHeight - 1, Math.max(0, Math.round(localY * bitmapHeight / petImage.getHeight())));
        int alpha = Color.alpha(currentHitBitmap.getPixel(pixelX, pixelY));
        return alpha > 24;
    }

    private void updateHitBitmap(String path) {
        if (path == null || path.length() == 0 || path.equals(currentHitPath)) return;
        currentHitPath = path;
        if (currentHitBitmap != null && !currentHitBitmap.isRecycled()) {
            currentHitBitmap.recycle();
            currentHitBitmap = null;
        }
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            currentHitBitmap = BitmapFactory.decodeFile(path, options);
        } catch (Exception ignored) {
            currentHitBitmap = null;
        }
    }

    private void clearHitBitmap() {
        currentHitPath = "";
        if (currentHitBitmap != null && !currentHitBitmap.isRecycled()) currentHitBitmap.recycle();
        currentHitBitmap = null;
    }

    private void scheduleWander() {
        if (wanderRunnable != null) mainHandler.removeCallbacks(wanderRunnable);
        if (!autoWanderEnabled) return;
        wanderRunnable = () -> {
            if (!autoWanderEnabled) return;
            if (params == null || petView == null || windowManager == null) return;
            if (dragging || autoMoving) {
                scheduleWander();
                return;
            }
            if (menuAdded) {
                scheduleWander();
                return;
            }
            startAutoWalk();
        };
        mainHandler.postDelayed(wanderRunnable, 35000 + random.nextInt(40000));
    }

    private void startAutoWalk() {
        autoMoving = true;
        int steps = 42 + random.nextInt(34);
        int nextDx = sdp(-70 + random.nextInt(141));
        int nextDy = sdp(-44 + random.nextInt(89));
        if (Math.abs(nextDx) < sdp(24)) nextDx = nextDx < 0 ? -sdp(45) : sdp(45);
        if (Math.abs(nextDy) < sdp(16)) nextDy = nextDy < 0 ? -sdp(30) : sdp(30);
        final int targetDx = nextDx;
        final int targetDy = nextDy;
        updateFacing(targetDx);
        updateFace("walk", 0);
        final int originX = params.x;
        final int originY = params.y;
        final int[] currentStep = {0};
        final int[] left = {steps};
        Runnable walker = new Runnable() {
            @Override
            public void run() {
                if (!autoMoving || params == null || petView == null || windowManager == null) return;
                if (left[0] <= 0) {
                    autoMoving = false;
                    updateFace("idle", 0);
                    scheduleWander();
                    return;
                }
                currentStep[0]++;
                float progress = currentStep[0] / (float) steps;
                params.x = originX + Math.round(targetDx * progress);
                params.y = originY + Math.round(targetDy * progress);
                clampToScreen();
                safeUpdateViewLayout();
                updateBubbleWindowPosition();
                left[0]--;
                mainHandler.postDelayed(this, 55);
            }
        };
        mainHandler.post(walker);
    }

    private void walkByCommand(int dx, int dy, int durationMs) {
        if (params == null || petView == null || windowManager == null) return;
        if (stateResetRunnable != null) mainHandler.removeCallbacks(stateResetRunnable);
        autoMoving = true;
        int duration = Math.max(350, durationMs);
        int steps = Math.max(8, duration / 55);
        final int originX = params.x;
        final int originY = params.y;
        final int targetDx = sdp(dx);
        final int targetDy = sdp(dy);
        final int[] currentStep = {0};
        updateFacing(targetDx);
        updateFace("walk", 0);
        Runnable walker = new Runnable() {
            @Override
            public void run() {
                if (!autoMoving || params == null || petView == null || windowManager == null) return;
                currentStep[0]++;
                float progress = Math.min(1f, currentStep[0] / (float) steps);
                params.x = originX + Math.round(targetDx * progress);
                params.y = originY + Math.round(targetDy * progress);
                clampToScreen();
                safeUpdateViewLayout();
                updateBubbleWindowPosition();
                if (progress < 1f) {
                    mainHandler.postDelayed(this, 55);
                } else {
                    autoMoving = false;
                    updateFace("idle", 0);
                }
            }
        };
        mainHandler.post(walker);
    }

    private void safeUpdateViewLayout() {
        if (petView == null || windowManager == null || !petView.isAttachedToWindow()) return;
        try { windowManager.updateViewLayout(petView, params); } catch (Exception ignored) {}
    }

    private void clampToScreen() {
        try {
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            int w = compactWindowWidth();
            int h = compactWindowHeight();
            int minX = 0;
            int maxX = Math.max(0, size.x - w);
            int minY = 0;
            int maxY = Math.max(0, size.y - h - keyboardLift - getNavigationBarAllowance());
            if (maxY < minY) maxY = minY;
            if (params.x < minX) params.x = minX;
            if (params.x > maxX) params.x = maxX;
            if (params.y < minY) params.y = minY;
            if (params.y > maxY) params.y = maxY;
        } catch (Exception ignored) {}
    }

    private int getStatusBarAllowance() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return getResources().getDimensionPixelSize(id) + sdp(4);
            } catch (Exception ignored) {}
        }
        return sdp(28);
    }

    private int getNavigationBarAllowance() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return getResources().getDimensionPixelSize(id);
            } catch (Exception ignored) {}
        }
        return sdp(24);
    }

    private void sendEvent(String type) {
        if (webSocket != null) {
            if ("hello".equals(type)) {
                webSocket.send("{\"type\":\"hello\",\"device_id\":\"" + getPetDeviceId() + "\"}");
            } else {
                webSocket.send("{\"type\":\"interaction\",\"action\":\"" + type + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}");
            }
        }
    }

    private void sendLastCrashIfAny() {
        if (webSocket == null) return;
        String crash = CrashLogger.readLastCrash(this);
        if (crash.length() == 0) return;
        String trimmed = crash.length() > 3800 ? crash.substring(0, 3800) : crash;
        String payload = "{\"type\":\"chat\",\"text\":\"[pet-crash] 上次崩溃日志\\n" + escapeJson(trimmed) + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}";
        webSocket.send(payload);
        CrashLogger.clearLastCrash(this);
    }

    private void sendRequestResponse(String requestId, String action) {
        sendRequestResponse(requestId, action, "accepted");
    }

    private void sendRequestResponse(String requestId, String action, String status) {
        if (webSocket == null) return;
        webSocket.send("{\"type\":\"request_response\",\"request_id\":\"" + escapeJson(requestId) + "\",\"action\":\"" + escapeJson(action) + "\",\"status\":\"" + escapeJson(status) + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}");
    }

    private void handleAcceptedRequest(String requestId, String action) {
        if ("screen_share".equals(action)) {
            hideOverlayView();
            Intent intent = new Intent(this, ScreenShareRequestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(EXTRA_REQUEST_ID, requestId);
            intent.putExtra(EXTRA_REQUEST_ACTION, action);
            try {
                startActivity(intent);
            } catch (Exception exc) {
                ensureOverlayVisible();
                updateBubble("共享屏幕启动失败", 2400);
            }
        }
    }

    private void sendChatMessage(String text) {
        if (webSocket == null || text == null || text.length() == 0) return;
        webSocket.send("{\"type\":\"chat\",\"text\":\"" + escapeJson(text) + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}");
    }

    private void sendScreenSnapshot(String caption, String imageBase64) {
        if (webSocket == null || imageBase64 == null || imageBase64.length() == 0) return;
        String text = caption != null && caption.length() > 0 ? caption : "[pet-screen] screen shared";
        webSocket.send("{\"type\":\"screen_snapshot\",\"mime\":\"image/jpeg\",\"image_base64\":\"" + imageBase64 + "\",\"caption\":\"" + escapeJson(text) + "\",\"msg_id\":\"" + UUID.randomUUID().toString() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"device_id\":\"" + getPetDeviceId() + "\"}");
    }

    private void showReply(String text) {
        showReply(text, 5000, "speak");
    }

    private void showReply(String text, int durationMs, String state) {
        Log.d("PetBubble", "showReply text=" + text + ", state=" + state + ", duration=" + durationMs);
        if (text == null || text.length() == 0) return;
        updateFace(state != null && state.length() > 0 ? state : "speak", 4000);
        updateBubble(text, Math.max(durationMs, 5000));
        playPagedBubble(text);
        Intent intent = new Intent(ACTION_REPLY_RECEIVED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TEXT, text);
        sendBroadcast(intent);
    }

    private void playPagedBubble(String text) {
        if (pageBubbleRunnable != null) mainHandler.removeCallbacks(pageBubbleRunnable);
        if (typeBubbleRunnable != null) mainHandler.removeCallbacks(typeBubbleRunnable);
        String cleaned = text.replaceAll("\\[NEXT:[^\\]]+\\]", "").replaceAll("\\s+", " ").trim();
        ArrayList<String> pages = splitBubblePages(cleaned);
        Log.d("PetBubble", "playPagedBubble cleaned=" + cleaned + ", pages=" + pages.size());
        if (pages.isEmpty()) return;
        final int[] index = {0};
        pageBubbleRunnable = new Runnable() {
            @Override
            public void run() {
                if (index[0] >= pages.size()) {
                    updateBubble("", 1);
                    return;
                }
                String page = pages.get(index[0]);
                updateBubble(page, 6000, index[0] + 1, pages.size());
                index[0]++;
                if (index[0] < pages.size()) mainHandler.postDelayed(this, 5600);
            }
        };
        mainHandler.post(pageBubbleRunnable);
    }

    private void playTypewriterBubble(String page, int durationMs, int pageIndex, int totalPages) {
        if (typeBubbleRunnable != null) mainHandler.removeCallbacks(typeBubbleRunnable);
        final int[] pos = {0};
        typeBubbleRunnable = new Runnable() {
            @Override
            public void run() {
                if (page == null) return;
                int next = Math.min(page.length(), pos[0] + 1);
                pos[0] = next;
                updateBubble(page.substring(0, next), durationMs, pageIndex, totalPages);
                if (next < page.length()) mainHandler.postDelayed(this, 34);
            }
        };
        mainHandler.post(typeBubbleRunnable);
    }

    private ArrayList<String> splitBubblePages(String text) {
        ArrayList<String> pages = new ArrayList<>();
        if (text == null) return pages;
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() == 0) return pages;
        int max = 64;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            current.append(c);
            boolean sentenceEnd = c == '。' || c == '！' || c == '？' || c == '，' || c == ',' || c == '!' || c == '?';
            if (current.length() >= max || (sentenceEnd && current.length() >= 10)) {
                pages.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.length() > 0) pages.add(current.toString().trim());
        return pages;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void showActionMenu() {
        runOnMain(() -> {
            if (menuPanel == null || windowManager == null || menuAdded) return;
            hideBubbleWindow();
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            menuParams = new WindowManager.LayoutParams(sdp(316), sdp(112), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            menuParams.gravity = Gravity.TOP | Gravity.START;
            menuParams.x = params != null ? params.x - sdp(110) : 80;
            menuParams.y = params != null ? Math.max(0, params.y - menuParams.height - sdp(52)) : 120;
            clampMenuToScreen();
            menuPanel.setVisibility(View.VISIBLE);
            windowManager.addView(menuPanel, menuParams);
            menuAdded = true;
            mainHandler.postDelayed(this::hideActionMenu, 5000);
        });
    }

    private void hideActionMenu() {
        runOnMain(() -> {
            if (menuPanel == null || windowManager == null || !menuAdded) return;
            try {
                windowManager.removeView(menuPanel);
            } catch (Exception ignored) {}
            menuPanel.setVisibility(View.GONE);
            menuAdded = false;
        });
    }

    private int compactWindowWidth() {
        return sdp(96);
    }

    private int compactWindowHeight() {
        return sdp(96);
    }

    private void clampMenuToScreen() {
        try {
            if (menuParams == null || windowManager == null) return;
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            int maxX = Math.max(0, size.x - menuParams.width);
            int maxY = Math.max(0, size.y - menuParams.height);
            if (menuParams.x < 0) menuParams.x = 0;
            if (menuParams.y < 0) menuParams.y = 0;
            if (menuParams.x > maxX) menuParams.x = maxX;
            if (menuParams.y > maxY) menuParams.y = maxY;
        } catch (Exception ignored) {}
    }

    private void updateFacing(float dx) {
        if (dx == 0) return;
        facingDirection = dx < 0 ? -1 : 1;
        float scale = facingDirection < 0 ? 1.0f : -1.0f;
        if (petImage != null) petImage.setScaleX(scale);
        if (face != null) face.setScaleX(scale);
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

    private String cleanLabel(String label) {
        return label.replace("✦", "").replace("🐇", "").replace("✿", "").replace("🌙", "").replace("☽", "").trim();
    }

    private void updateFace(String state, int durationMs) {
        runOnMain(() -> {
            if (stateResetRunnable != null) mainHandler.removeCallbacks(stateResetRunnable);
            if (face == null) return;
            if (loadAssetForState(state)) return;
            clearHitBitmap();
            if (petImage != null) petImage.setVisibility(View.GONE);
            face.setVisibility(View.VISIBLE);
            if ("working".equals(state)) face.setText("💼");
            else if ("sleep".equals(state)) face.setText("🌙");
            else if ("touch".equals(state)) face.setText("✦");
            else if ("hug".equals(state)) face.setText("🐇");
            else if ("feed".equals(state)) face.setText("🌸");
            else if ("bath".equals(state)) face.setText("🛁");
            else if ("speak".equals(state)) face.setText("✿");
            else if ("drag".equals(state)) face.setText("↕");
            else if ("walk".equals(state)) face.setText("✦");
            else face.setText("🐇");
            if (!"idle".equals(state) && durationMs > 0) {
                stateResetRunnable = () -> updateFace("idle", 0);
                mainHandler.postDelayed(stateResetRunnable, durationMs);
            }
        });
    }

    private boolean loadAssetForState(String state) {
        if (petImage == null || face == null) return false;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String path = prefs.getString(MainActivity.KEY_ASSET_PREFIX + state, "");
        if ((path == null || path.length() == 0) && !"idle".equals(state) && !"walk".equals(state) && !"drag".equals(state)) {
            path = prefs.getString(MainActivity.KEY_ASSET_PREFIX + "idle", "");
        }
        if (path == null || path.length() == 0) return false;
        File file = new File(path);
        if (!file.exists()) return false;
        try {
            Drawable drawable;
            if (Build.VERSION.SDK_INT >= 28) {
                drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(this.getContentResolver(), Uri.fromFile(file)));
            } else {
                drawable = Drawable.createFromPath(file.getAbsolutePath());
            }
            if (drawable == null) return false;
            updateHitBitmap(file.getAbsolutePath());
            petImage.setImageDrawable(drawable);
            petImage.setVisibility(View.VISIBLE);
            petImage.setScaleX(facingDirection < 0 ? 1.0f : -1.0f);
            face.setVisibility(View.GONE);
            if (Build.VERSION.SDK_INT >= 28 && drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).start();
            }
            return true;
        } catch (Exception exc) {
            return false;
        }
    }

    private void updateBubble(String text) {
        updateBubble(text, 3000);
    }

    private void updateBubble(String text, int durationMs) {
        updateBubble(text, durationMs, 0, 0);
    }

    private void updateBubble(String text, int durationMs, int page, int totalPages) {
        runOnMain(() -> {
            Log.d("PetBubble", "updateBubble text=" + text + ", bubbleAdded=" + bubbleAdded + ", duration=" + durationMs);
            if (bubble == null || bubbleContainer == null) {
                Log.d("PetBubble", "updateBubble skipped: bubble or container null");
                return;
            }
            if (clearBubbleRunnable != null) mainHandler.removeCallbacks(clearBubbleRunnable);
            bubbleContainer.animate().cancel();
            if (text == null || text.length() == 0) {
                hideBubbleWindow();
                return;
            }
            resizeBubbleForText(text);
            bubble.setText(text);
            if (pageIndicator != null) {
                if (totalPages > 1 && page > 0) {
                    pageIndicator.setText(page + "/" + totalPages);
                    pageIndicator.setVisibility(View.VISIBLE);
                } else {
                    pageIndicator.setText("");
                    pageIndicator.setVisibility(View.GONE);
                }
            }
            if (!bubbleAdded) {
                showBubbleWindow();
            } else {
                updateBubbleWindowPosition();
                bubbleContainer.setVisibility(View.VISIBLE);
                bubbleContainer.setAlpha(1f);
                bubbleContainer.setScaleX(1f);
                bubbleContainer.setScaleY(1f);
            }
            startStarTwinkle();
            if (durationMs > 0) {
                clearBubbleRunnable = this::hideBubbleWindow;
                mainHandler.postDelayed(clearBubbleRunnable, durationMs);
            }
        });
    }

    private void resizeBubbleForText(String text) {
        int len = text == null ? 0 : text.length();
        int maxContainerWidth = sdp(340);
        try {
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            maxContainerWidth = Math.min(maxContainerWidth, Math.max(sdp(180), size.x - sdp(24)));
        } catch (Exception ignored) {}
        int sideDecorWidth = sdp(44);
        int maxTextBubbleWidth = Math.max(sdp(168), maxContainerWidth - sideDecorWidth);
        int textWidth = Math.min(maxTextBubbleWidth, sdp(Math.max(190, 90 + len * 10)));
        int containerWidth = textWidth + sideDecorWidth;
        int charsPerLine = Math.max(8, (textWidth - sdp(30)) / sdp(13));
        int estimatedLines = Math.max(1, (int) Math.ceil(len / (double) charsPerLine));
        int containerHeight = sdp(Math.min(170, Math.max(58, 34 + estimatedLines * 25)));
        desiredBubbleWidth = containerWidth;
        desiredBubbleHeight = containerHeight;
        if (bubbleParams != null) {
            bubbleParams.width = desiredBubbleWidth;
            bubbleParams.height = desiredBubbleHeight;
            updateBubbleWindowPosition();
        }
        FrameLayout.LayoutParams bubbleLp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        if (bubbleLp != null) {
            bubbleLp.width = textWidth;
            bubbleLp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
            bubbleLp.gravity = Gravity.CENTER;
            bubble.setLayoutParams(bubbleLp);
        }
    }

    private void showBubbleWindow() {
        if (bubbleContainer == null || windowManager == null) return;
        if (bubbleAdded) {
            updateBubbleWindowPosition();
            return;
        }
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int initialBubbleWidth = desiredBubbleWidth > 0 ? desiredBubbleWidth : sdp(198);
        int initialBubbleHeight = desiredBubbleHeight > 0 ? desiredBubbleHeight : sdp(70);
        bubbleParams = new WindowManager.LayoutParams(initialBubbleWidth, initialBubbleHeight, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        updateBubbleWindowPosition();
        bubbleContainer.setVisibility(View.VISIBLE);
        bubbleContainer.setAlpha(0f);
        bubbleContainer.setScaleX(0.72f);
        bubbleContainer.setScaleY(0.72f);
        try {
            windowManager.addView(bubbleContainer, bubbleParams);
            bubbleAdded = true;
            Log.d("PetBubble", "showBubbleWindow addView ok x=" + bubbleParams.x + ", y=" + bubbleParams.y + ", w=" + bubbleParams.width + ", h=" + bubbleParams.height);
        } catch (Exception exc) {
            Log.e("PetBubble", "showBubbleWindow addView failed", exc);
            return;
        }
        bubbleContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.15f))
                .start();
    }

    private void updateBubbleWindowPosition() {
        if (bubbleParams == null || params == null) return;
        bubbleParams.x = params.x - sdp(51);
        int gap = sdp(42);
        int aboveY = params.y - bubbleParams.height - gap;
        int belowY = params.y + compactWindowHeight() + gap;
        try {
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            int maxX = Math.max(0, size.x - bubbleParams.width);
            int maxY = Math.max(0, size.y - bubbleParams.height);
            if (bubbleParams.x < 0) bubbleParams.x = 0;
            if (bubbleParams.x > maxX) bubbleParams.x = maxX;
            bubbleParams.y = aboveY >= sdp(8) ? aboveY : Math.min(maxY, belowY);
        } catch (Exception ignored) {
            bubbleParams.y = Math.max(0, aboveY);
        }
        if (bubbleAdded && windowManager != null && bubbleContainer != null) {
            try { windowManager.updateViewLayout(bubbleContainer, bubbleParams); } catch (Exception ignored) {}
        }
    }

    private void hideBubbleWindow() {
        if (bubbleContainer == null || !bubbleAdded) return;
        bubbleContainer.animate().cancel();
        bubbleContainer.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(180)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (bubbleContainer == null || windowManager == null || !bubbleAdded) return;
                    try { windowManager.removeView(bubbleContainer); } catch (Exception ignored) {}
                    bubbleAdded = false;
                    bubbleContainer.setVisibility(View.GONE);
                    bubbleContainer.setAlpha(1f);
                    bubbleContainer.setScaleX(1f);
                    bubbleContainer.setScaleY(1f);
                    if (bubble != null) bubble.setText("");
                    if (pageIndicator != null) pageIndicator.setVisibility(View.GONE);
                    if (starLeft != null) starLeft.clearAnimation();
                    if (starRight != null) starRight.clearAnimation();
                })
                .start();
    }

    private void startStarTwinkle() {
        if (starLeft == null || starRight == null) return;
        starLeft.animate().cancel();
        starRight.animate().cancel();
        starLeft.setAlpha(0.68f);
        starRight.setAlpha(1f);
        starLeft.animate().alpha(1f).scaleX(1.16f).scaleY(1.16f).setDuration(520).withEndAction(() -> {
            if (starLeft != null) starLeft.animate().alpha(0.76f).scaleX(1f).scaleY(1f).setDuration(520).start();
        }).start();
        starRight.animate().alpha(0.72f).scaleX(0.9f).scaleY(0.9f).setStartDelay(180).setDuration(520).withEndAction(() -> {
            if (starRight != null) starRight.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).start();
        }).start();
    }

    private void addBubbleDot(FrameLayout container, int left, int top, int size, String drawableName) {
        View dot = new View(this);
        dot.setBackgroundResource(getResources().getIdentifier(drawableName, "drawable", getPackageName()));
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(sdp(size), sdp(size));
        dotParams.leftMargin = sdp(left);
        dotParams.topMargin = sdp(top);
        container.addView(dot, dotParams);
    }

    private void runOnMain(Runnable runnable) {
        if (petView != null) petView.post(runnable);
    }

    private String extractValue(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private int extractInt(String json, String key, int fallback) {
        try {
            return new JSONObject(json).optInt(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int sdp(int value) {
        return (int) (dp(value) * petScale + 0.5f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

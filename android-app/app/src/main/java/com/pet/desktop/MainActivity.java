package com.pet.desktop;
import android.Manifest;
import android.app.Activity;
import android.media.projection.MediaProjectionManager;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import java.util.HashMap;
import java.util.Map;
public class MainActivity extends Activity {
    static final String PREFS = "pet_config";
    static final String KEY_WS_URL = "ws_url";
    static final String KEY_ASSET_PREFIX = "asset_";
    static final String KEY_PET_SCALE = "pet_scale";
    static final String KEY_AUTO_WANDER = "auto_wander";
    static final String[] ASSET_STATES = {"idle", "working", "touch", "hug", "sleep", "feed", "bath", "drag", "walk"};
    static final String[] ASSET_LABELS = {"默认", "工作", "开心", "抱抱", "睡觉", "喂食", "洗澡", "拖拽", "走路"};
    static final String[] ASSET_EMOJIS = {"🙂", "💼", "😆", "🤗", "😴", "🍰", "🛁", "✋", "🚶"};
    // ===== 统一色彩常量 =====
    private static final String COLOR_BG_DARK       = "#C2C7F8";
    private static final String COLOR_STATUS_BAR     = "#C2C7F8";
    private static final String COLOR_PRIMARY        = "#C2C7F8";
    private static final String COLOR_ACCENT         = "#AFE4F6";
    private static final String COLOR_TEXT_TITLE     = "#3F4968";
    private static final String COLOR_TEXT_BODY      = "#56617D";
    private static final String COLOR_TEXT_HINT      = "#74809D";
    private static final String COLOR_TEXT_DIM       = "#9AA5BD";
    private static final String COLOR_DECOR          = "#7E89BE";
    private static final String COLOR_DANGER         = "#D9799B";
    private static final String COLOR_PREVIEW_BG     = "#EDF3FC";
    private EditText urlInput;
    private String pendingAssetState;
    private SharedPreferences prefs;
    private TextView scaleValue;
    private ScrollView scrollView;
    private final Map<String, ImageView> assetPreviews = new HashMap<>();
    private final Map<String, TextView> assetHints = new HashMap<>();
    private static final int REQUEST_PICK_ASSET = 1017;
    private static final int REQUEST_SCREEN_CAPTURE = 2016;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateAssetStateKeys();
        // ===== 状态栏 & 导航栏 =====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        scrollView = new ScrollView(this);
        scrollView.setBackgroundResource(getResources().getIdentifier(
                "bg_starry_sky", "drawable", getPackageName()));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));
        // ========== HEADER ==========
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(20), dp(48), dp(20), dp(30));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        // 星星装饰
        TextView stars = new TextView(this);
        stars.setText("✦ ☽ ✦");
        stars.setTextSize(18);
        stars.setTextColor(Color.WHITE);
        stars.setShadowLayer(dp(5), 0, dp(1), Color.parseColor("#80C2C7F8"));
        stars.setGravity(Gravity.CENTER);
        header.addView(stars, new LinearLayout.LayoutParams(-2, -2));
        // 兔子 emoji
        TextView rabbit = new TextView(this);
        rabbit.setText("🐇");
        rabbit.setTextSize(46);
        rabbit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rabbitLp = new LinearLayout.LayoutParams(-2, -2);
        rabbitLp.topMargin = dp(4);
        header.addView(rabbit, rabbitLp);
        // 主标题
        TextView title = new TextView(this);
        title.setText("Mobile Pet");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = dp(2);
        header.addView(title, titleLp);
        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("~ 小球球桌宠 ~");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(2);
        header.addView(subtitle, subLp);
        // 运行状态
        TextView status = new TextView(this);
        status.setText("● 未运行");
        status.setTextSize(12);
        status.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
        status.setPadding(dp(16), dp(6), dp(16), dp(6));
        status.setGravity(Gravity.CENTER);
        status.setBackgroundResource(getResources().getIdentifier(
                "bg_glass_status", "drawable", getPackageName()));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = dp(14);
        statusLp.gravity = Gravity.CENTER;
        header.addView(status, statusLp);
        // 分隔装饰
        TextView decor = new TextView(this);
        decor.setText("⋆ ˚｡⋆୨ ʚɞ ୧⋆ ˚｡⋆");
        decor.setTextSize(12);
        decor.setTextColor(Color.WHITE);
        decor.setShadowLayer(dp(5), 0, dp(1), Color.parseColor("#80C2C7F8"));
        decor.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams decorLp = new LinearLayout.LayoutParams(-2, -2);
        decorLp.topMargin = dp(14);
        decorLp.gravity = Gravity.CENTER;
        header.addView(decor, decorLp);
        // 崩溃提示
        String lastCrash = CrashLogger.readLastCrash(this);
        if (lastCrash.length() > 0) {
            TextView crashView = new TextView(this);
            crashView.setText("⚠ 上次崩溃日志已记录，可点下方按钮复制");
            crashView.setTextSize(12);
            crashView.setTextColor(Color.parseColor("#FFD9A0"));
            crashView.setGravity(Gravity.CENTER);
            crashView.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams crashLp = new LinearLayout.LayoutParams(-1, -2);
            crashLp.topMargin = dp(12);
            header.addView(crashView, crashLp);
        }
        // ========== CONTENT ==========
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(0), dp(18), dp(28));
        root.addView(content, new LinearLayout.LayoutParams(-1, -2));
        // ---- 连接设置卡片 ----
        LinearLayout connectCard = createCard(content, "🌙 连接设置", "");
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText(prefs.getString(KEY_WS_URL,
                "ws://你的服务器IP:1016/pet?token=my_secret_token"));
        urlInput.setTextSize(13);
        urlInput.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
        urlInput.setHintTextColor(Color.parseColor(COLOR_TEXT_DIM));
        urlInput.setPadding(dp(14), 0, dp(14), 0);
        urlInput.setBackgroundResource(getResources().getIdentifier(
                "bg_glass_input", "drawable", getPackageName()));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(46));
        inputLp.setMargins(dp(2), dp(14), dp(2), 0);
        connectCard.addView(urlInput, inputLp);
        Button save = createButton("保存地址", "outline");
        save.setOnClickListener(v -> saveUrl());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(42));
        saveLp.setMargins(dp(2), dp(10), dp(2), 0);
        connectCard.addView(save, saveLp);
        // ---- 桌宠控制卡片 ----
        LinearLayout controlCard = createCard(content, "✿ 桌宠控制", "");
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams controlRowLp = new LinearLayout.LayoutParams(-1, -2);
        controlRowLp.topMargin = dp(12);
        controlCard.addView(controlRow, controlRowLp);
        Button start = createButton("🐇 启动桌宠", "solid");
        start.setOnClickListener(v -> {
            status.setText("✦ 运行中");
            status.setTextColor(Color.parseColor("#4A4A8A"));
            status.setBackgroundResource(getResources().getIdentifier(
                    "bg_glass_status_online", "drawable", getPackageName()));
            startPet();
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        startLp.rightMargin = dp(6);
        controlRow.addView(start, startLp);
        Button stop = createButton("停止", "danger");
        stop.setOnClickListener(v -> {
            status.setText("● 已停止");
            status.setTextColor(Color.parseColor(COLOR_DANGER));
            status.setBackgroundResource(getResources().getIdentifier(
                    "bg_glass_status", "drawable", getPackageName()));
            stopService(new Intent(this, PetOverlayService.class));
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stopLp.leftMargin = dp(6);
        controlRow.addView(stop, stopLp);
        Button permission = createButton("授权悬浮窗", "outline");
        permission.setOnClickListener(v -> requestOverlayPermission());
        LinearLayout.LayoutParams permissionLp = new LinearLayout.LayoutParams(-1, dp(40));
        permissionLp.topMargin = dp(10);
        controlCard.addView(permission, permissionLp);
        Button crashButton = createButton("复制上次崩溃日志", "outline");
        crashButton.setOnClickListener(v -> copyLastCrashLog());
        LinearLayout.LayoutParams crashButtonLp = new LinearLayout.LayoutParams(-1, dp(40));
        crashButtonLp.topMargin = dp(8);
        controlCard.addView(crashButton, crashButtonLp);
        // ---- 桌宠大小卡片 ----
        LinearLayout sizeCard = createCard(content, "☽ 桌宠大小", "调完后重新启动桌宠生效");
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sizeRowLp = new LinearLayout.LayoutParams(-1, -2);
        sizeRowLp.topMargin = dp(12);
        sizeCard.addView(sizeRow, sizeRowLp);
        TextView smallLabel = new TextView(this);
        smallLabel.setText("小");
        smallLabel.setTextSize(13);
        smallLabel.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
        sizeRow.addView(smallLabel, new LinearLayout.LayoutParams(-2, -2));
        SeekBar scaleBar = new SeekBar(this);
        scaleBar.setMax(100);
        int savedScale = prefs.getInt(KEY_PET_SCALE, 100);
        scaleBar.setProgress(Math.max(60, Math.min(160, savedScale)) - 60);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, -2, 1f);
        barLp.leftMargin = dp(10);
        barLp.rightMargin = dp(10);
        sizeRow.addView(scaleBar, barLp);
        TextView bigLabel = new TextView(this);
        bigLabel.setText("大");
        bigLabel.setTextSize(13);
        bigLabel.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
        sizeRow.addView(bigLabel, new LinearLayout.LayoutParams(-2, -2));
        scaleValue = new TextView(this);
        scaleValue.setText(savedScale + "%");
        scaleValue.setTextSize(14);
        scaleValue.setTextColor(Color.parseColor(COLOR_TEXT_BODY));
        scaleValue.setGravity(Gravity.CENTER);
        scaleValue.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams scaleValueLp = new LinearLayout.LayoutParams(-1, -2);
        scaleValueLp.topMargin = dp(6);
        sizeCard.addView(scaleValue, scaleValueLp);
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 60;
                scaleValue.setText(value + "%");
                prefs.edit().putInt(KEY_PET_SCALE, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        // ---- 行为设置卡片 ----
        LinearLayout behaviorCard = createCard(content, "✦ 行为设置",
                "关闭后不会自己走动，手动拖拽动画仍然保留");
        LinearLayout wanderRow = new LinearLayout(this);
        wanderRow.setOrientation(LinearLayout.HORIZONTAL);
        wanderRow.setGravity(Gravity.CENTER_VERTICAL);
        wanderRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        wanderRow.setBackgroundResource(getResources().getIdentifier(
                "bg_glass_asset_item", "drawable", getPackageName()));
        LinearLayout.LayoutParams wanderLp = new LinearLayout.LayoutParams(-1, -2);
        wanderLp.topMargin = dp(12);
        behaviorCard.addView(wanderRow, wanderLp);
        TextView wanderLabel = new TextView(this);
        wanderLabel.setText("🚶 自动闲逛");
        wanderLabel.setTextSize(14);
        wanderLabel.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
        wanderRow.addView(wanderLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch wanderSwitch = new Switch(this);
        wanderSwitch.setChecked(prefs.getBoolean(KEY_AUTO_WANDER, true));
        wanderSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(KEY_AUTO_WANDER, isChecked).apply());
        wanderRow.addView(wanderSwitch, new LinearLayout.LayoutParams(-2, -2));
        // ---- 一起冲浪卡片 ----
        LinearLayout surfCard = createCard(content, "☄ 一起冲浪",
                "每次共享都需要你手动授权，只截一张当前屏幕");
        Button shareScreen = createButton("共享当前屏幕", "solid");
        shareScreen.setOnClickListener(v -> requestScreenShare());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-1, dp(44));
        shareLp.topMargin = dp(12);
        surfCard.addView(shareScreen, shareLp);
        // ---- 素材管理卡片 ----
        LinearLayout assetCard = createCard(content, "⋆ 素材管理",
                "点击上传对应状态的 GIF / 图片");
        for (int i = 0; i < ASSET_STATES.length; i++) {
            addAssetRow(assetCard, ASSET_STATES[i], ASSET_LABELS[i], ASSET_EMOJIS[i]);
        }
        // ---- 页脚 ----
        TextView footer = new TextView(this);
        footer.setText("⋆ ˚ ☽ ˚ ⋆ ·˚ ✿ ˚· ⋆ ˚ ☽ ˚ ⋆");
        footer.setTextSize(13);
        footer.setTextColor(Color.parseColor(COLOR_DECOR));
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
        footerLp.setMargins(0, dp(20), 0, dp(16));
        content.addView(footer, footerLp);
        setContentView(scrollView);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }
    // ========== 卡片工厂 ==========
    private LinearLayout createCard(LinearLayout parent, String title, String hint) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackgroundResource(getResources().getIdentifier(
                "bg_glass_card", "drawable", getPackageName()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
            card.setTranslationZ(dp(2));
        }
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(16);
        parent.addView(card, cardLp);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setPadding(0, 0, 0, dp(2));
        titleView.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        if (hint != null && hint.length() > 0) {
            TextView hintView = new TextView(this);
            hintView.setText(hint);
            hintView.setTextSize(12);
            hintView.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
            hintLp.topMargin = dp(4);
            card.addView(hintView, hintLp);
        }
        return card;
    }
    // ========== 按钮工厂 ==========
    private Button createButton(String text, String style) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setLetterSpacing(0.02f);
        if ("solid".equals(style)) {
            button.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
            button.setTypeface(null, android.graphics.Typeface.BOLD);
            button.setBackgroundResource(getResources().getIdentifier(
                    "bg_glass_button_primary", "drawable", getPackageName()));
        } else if ("danger".equals(style)) {
            button.setTextColor(Color.parseColor("#7A3953"));
            button.setBackgroundResource(getResources().getIdentifier(
                    "bg_glass_button_danger", "drawable", getPackageName()));
        } else {
            button.setTextColor(Color.parseColor(COLOR_TEXT_BODY));
            button.setBackgroundResource(getResources().getIdentifier(
                    "bg_glass_button", "drawable", getPackageName()));
        }
        return button;
    }
    // ========== 素材行 ==========
    private void addAssetRow(LinearLayout root, String state, String label, String emoji) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackgroundResource(getResources().getIdentifier(
                "bg_glass_asset_item", "drawable", getPackageName()));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(10);
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = new TextView(this);
        labelView.setText(emoji + "  " + label);
        labelView.setTextSize(14);
        labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        labelView.setTextColor(Color.parseColor(COLOR_TEXT_TITLE));
        headerRow.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = new TextView(this);
        clear.setText("✕ 清除");
        clear.setTextSize(13);
        clear.setTextColor(Color.parseColor(COLOR_DANGER));
        clear.setPadding(dp(8), dp(4), dp(8), dp(4));
        clear.setOnClickListener(v -> {
            String path = prefs.getString(KEY_ASSET_PREFIX + state, "");
            if (path.length() > 0) new File(path).delete();
            prefs.edit().remove(KEY_ASSET_PREFIX + state).apply();
            refreshAssetCard(state);
        });
        headerRow.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        card.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        ImageView preview = new ImageView(this);
        preview.setBackgroundColor(Color.parseColor(COLOR_PREVIEW_BG));
        preview.setPadding(dp(4), dp(4), dp(4), dp(4));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        assetPreviews.put(state, preview);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(100));
        previewLp.topMargin = dp(8);
        card.addView(preview, previewLp);
        TextView hint = new TextView(this);
        hint.setText(hasAssetFile(state) ? "点卡片可重新上传" : "点卡片上传 PNG / GIF");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor(COLOR_TEXT_HINT));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        card.addView(hint, hintLp);
        assetHints.put(state, hint);
        refreshAssetCard(state);
        card.setOnClickListener(v -> pickAsset(state));
        root.addView(card, cardLp);
    }
    // ========== 工具方法（不变） ==========
    private boolean hasAssetFile(String state) {
        String path = prefs.getString(KEY_ASSET_PREFIX + state, "");
        return path.length() > 0 && new File(path).exists();
    }
    private void pickAsset(String state) {
        pendingAssetState = state;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_ASSET);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent intent = new Intent(this, ScreenShareService.class);
                intent.putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode);
                intent.putExtra(ScreenShareService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                Toast.makeText(this, "正在共享当前屏幕", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已取消屏幕共享", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_PICK_ASSET || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        if (pendingAssetState == null || pendingAssetState.length() == 0) return;
        Uri uri = data.getData();
        try {
            File dir = new File(getFilesDir(), "pet_assets");
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, pendingAssetState + ".asset");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IllegalStateException("cannot open asset");
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
            prefs.edit().putString(KEY_ASSET_PREFIX + pendingAssetState,
                    target.getAbsolutePath()).apply();
            refreshAssetCard(pendingAssetState);
            Toast.makeText(this, "素材已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception exc) {
            Toast.makeText(this, "保存失败: " + exc.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
    private void refreshAssetCard(String state) {
        ImageView preview = assetPreviews.get(state);
        TextView hint = assetHints.get(state);
        if (preview == null) return;
        String path = prefs.getString(KEY_ASSET_PREFIX + state, "");
        if (path.length() > 0 && new File(path).exists()) {
            try {
                Drawable drawable = Drawable.createFromPath(path);
                if (drawable != null) preview.setImageDrawable(drawable);
                else preview.setImageResource(android.R.drawable.ic_menu_gallery);
            } catch (Exception ignored) {
                preview.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        } else {
            preview.setImageResource(android.R.drawable.ic_menu_upload);
        }
        if (hint != null)
            hint.setText(hasAssetFile(state) ? "点卡片可重新上传" : "点卡片上传 PNG / GIF");
    }
    private void migrateAssetStateKeys() {
        String migrationKey = "asset_migration_bath_v1";
        if (prefs.getBoolean(migrationKey, false)) return;
        String oldSpeakPath = prefs.getString(KEY_ASSET_PREFIX + "speak", "");
        String bathPath = prefs.getString(KEY_ASSET_PREFIX + "bath", "");
        SharedPreferences.Editor editor = prefs.edit();
        if (oldSpeakPath.length() > 0 && bathPath.length() == 0) {
            editor.putString(KEY_ASSET_PREFIX + "bath", oldSpeakPath);
            editor.remove(KEY_ASSET_PREFIX + "speak");
        }
        editor.putBoolean(migrationKey, true).apply();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
    private void copyLastCrashLog() {
        String crash = CrashLogger.readLastCrash(this);
        if (crash.length() == 0) {
            Toast.makeText(this, "没有记录到崩溃日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("pet_crash_log", crash));
        }
        String preview = crash.length() > 160
                ? crash.substring(0, 160) + "..." : crash;
        Toast.makeText(this, "已复制崩溃日志：" + preview, Toast.LENGTH_LONG).show();
    }
    private void saveUrl() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_WS_URL, urlInput.getText().toString().trim())
                .apply();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }
    private void requestScreenShare() {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "当前设备不支持屏幕共享",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE);
    }
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
        }
    }
    private void startPet() {
        saveUrl();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        Intent intent = new Intent(this, PetOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}

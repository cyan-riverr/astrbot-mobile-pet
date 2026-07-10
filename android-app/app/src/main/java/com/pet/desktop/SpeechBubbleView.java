package com.pet.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

public class SpeechBubbleView extends View {
    private String text = "";
    private StaticLayout textLayout;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubblePath = new Path();

    private final float strokeWidth = dp(1.2f);
    private final float cornerRadius = dp(17);
    private final float horizontalPadding = dp(15);
    private final float verticalPadding = dp(10);

    public SpeechBubbleView(Context context) {
        super(context);
        init();
    }

    public SpeechBubbleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setColor(Color.parseColor("#4A4A8A"));
        textPaint.setTextSize(sp(13));
        textPaint.setFakeBoldText(false);

        fillPaint.setColor(Color.parseColor("#C2C7F8"));
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(0x66FFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        shadowPaint.setColor(0x180F1633);
        shadowPaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    public void setText(String newText) {
        text = newText == null ? "" : newText;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int maxBubbleWidth = dp(296);
        int availableWidth = widthMode == MeasureSpec.EXACTLY ? widthSize : maxBubbleWidth;
        int maxTextWidth = Math.max(dp(132), availableWidth - (int) (horizontalPadding * 2));

        textLayout = new StaticLayout(text, textPaint, maxTextWidth,
                Layout.Alignment.ALIGN_CENTER, 1.08f, 0f, false);

        int textWidth = 0;
        for (int index = 0; index < textLayout.getLineCount(); index++) {
            textWidth = Math.max(textWidth, (int) Math.ceil(textLayout.getLineWidth(index)));
        }
        int desiredWidth = Math.max(dp(168), textWidth + (int) (horizontalPadding * 2));
        if (widthMode == MeasureSpec.EXACTLY) desiredWidth = widthSize;
        int desiredHeight = Math.max(dp(45), textLayout.getHeight() + (int) (verticalPadding * 2));
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        RectF body = new RectF(dp(3), dp(2), width - dp(3), height - dp(3));

        bubblePath.reset();
        bubblePath.addRoundRect(body, cornerRadius, cornerRadius, Path.Direction.CW);

        shadowPaint.setShadowLayer(dp(5), 0, dp(2), 0x240F1633);
        canvas.drawPath(bubblePath, shadowPaint);
        shadowPaint.clearShadowLayer();
        fillPaint.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                Color.parseColor("#C2C7F8"), Color.parseColor("#AFE4F6"), Shader.TileMode.CLAMP));
        canvas.drawPath(bubblePath, fillPaint);
        fillPaint.setShader(null);
        canvas.drawPath(bubblePath, strokePaint);

        if (textLayout != null) {
            canvas.save();
            float textX = (width - textLayout.getWidth()) / 2f;
            float textY = (body.height() - textLayout.getHeight()) / 2f + dp(1);
            canvas.translate(textX, textY);
            textLayout.draw(canvas);
            canvas.restore();
        }
    }

    private float getMaxLineWidth(StaticLayout layout) {
        float max = 0f;
        for (int index = 0; index < layout.getLineCount(); index++) {
            max = Math.max(max, layout.getLineWidth(index));
        }
        return max;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}

package com.pet.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BubblePanelView extends FrameLayout {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubblePath = new Path();
    private final float strokeWidth = dp(1.2f);
    private final float cornerRadius = dp(26);
    private long shapeSeed = 3917L;

    public BubblePanelView(Context context) {
        super(context);
        init();
    }

    public BubblePanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClipToPadding(false);
        fillPaint.setColor(Color.parseColor("#C2C7F8"));
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(0x66FFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setShapeSeed(long seed) {
        shapeSeed = seed;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawBubble(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawBubble(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        RectF rect = new RectF(strokeWidth / 2f, strokeWidth / 2f,
                w - strokeWidth / 2f, h - strokeWidth / 2f);
        bubblePath.reset();
        bubblePath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
        fillPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                Color.parseColor("#C2C7F8"), Color.parseColor("#AFE4F6"), Shader.TileMode.CLAMP));
        canvas.drawPath(bubblePath, fillPaint);
        fillPaint.setShader(null);
        canvas.drawPath(bubblePath, strokePaint);
    }

    private void buildJitteredRoundRectPath(Path path, RectF rect, float radius, float amplitude, long seed) {
        path.reset();
        Random random = new Random(seed);
        float[] pts = buildBaseRoundRectPoints(rect, radius, 6);
        for (int i = 0; i < pts.length; i += 2) {
            pts[i] += (random.nextFloat() - 0.5f) * 2f * amplitude;
            pts[i + 1] += (random.nextFloat() - 0.5f) * 2f * amplitude;
        }
        path.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) {
            float midX = (pts[i - 2] + pts[i]) / 2f;
            float midY = (pts[i - 1] + pts[i + 1]) / 2f;
            path.quadTo(pts[i - 2], pts[i - 1], midX, midY);
        }
        path.close();
    }

    private float[] buildBaseRoundRectPoints(RectF r, float radius, int segmentsPerSide) {
        List<Float> list = new ArrayList<>();
        addArcPoints(list, r.right - radius, r.top + radius, radius, -90, 90, 4);
        addLinePoints(list, r.right, r.top + radius, r.right, r.bottom - radius, segmentsPerSide);
        addArcPoints(list, r.right - radius, r.bottom - radius, radius, 0, 90, 4);
        addLinePoints(list, r.right - radius, r.bottom, r.left + radius, r.bottom, segmentsPerSide);
        addArcPoints(list, r.left + radius, r.bottom - radius, radius, 90, 90, 4);
        addLinePoints(list, r.left, r.bottom - radius, r.left, r.top + radius, segmentsPerSide);
        addArcPoints(list, r.left + radius, r.top + radius, radius, 180, 90, 4);
        addLinePoints(list, r.left + radius, r.top, r.right - radius, r.top, segmentsPerSide);
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private void addArcPoints(List<Float> list, float cx, float cy, float radius,
                              float startAngleDeg, float sweepDeg, int segments) {
        for (int i = 0; i <= segments; i++) {
            double angle = Math.toRadians(startAngleDeg + sweepDeg * i / (float) segments);
            list.add((float) (cx + radius * Math.cos(angle)));
            list.add((float) (cy + radius * Math.sin(angle)));
        }
    }

    private void addLinePoints(List<Float> list, float x1, float y1, float x2, float y2, int segments) {
        for (int i = 1; i <= segments; i++) {
            float t = i / (float) segments;
            list.add(x1 + (x2 - x1) * t);
            list.add(y1 + (y2 - y1) * t);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** The ticker card's intraday line: the day's prices left to right, a dotted line at the previous close. */
final class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double[] values = new double[0];
    private double reference;
    private int color = 0xFF4ADE80;

    SparklineView(Context ctx) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        base.setStyle(Paint.Style.STROKE);
        base.setStrokeWidth(d);
        base.setColor(0x80FFFFFF);
        base.setPathEffect(new DashPathEffect(new float[]{2 * d, 3 * d}, 0));
    }

    void setData(double[] values, double reference, int color) {
        this.values = values;
        this.reference = reference;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (values.length < 2) return;
        double min = reference, max = reference;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1e-9) max = min + 1;
        float w = getWidth(), h = getHeight(), pad = line.getStrokeWidth();
        Path path = new Path();
        for (int i = 0; i < values.length; i++) {
            float x = w * i / (values.length - 1);
            float y = pad + (float) ((max - values[i]) / (max - min)) * (h - 2 * pad);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        Path fill = new Path(path);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();
        area.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x55000000,
                color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(fill, area);
        float ref = pad + (float) ((max - reference) / (max - min)) * (h - 2 * pad);
        canvas.drawLine(0, ref, w, ref, base);
        line.setColor(color);
        canvas.drawPath(path, line);
    }
}

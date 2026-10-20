package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the tvOS style band behind the first (dock) row, one panel behind the app rows under it, and adds a gap
 * below the dock. Translucent glass like the top bar pills (pill_bg.xml: the same white gradient and edge), so a
 * wallpaper or video shows through.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rowsRect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x38FFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean dock = false, rows = false;
        float cardWidth = 0f, left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            View card = pos < 0 ? null : child.findViewById(R.id.card);
            if (card == null) continue;   // the App library footer has no card and no glass
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            cardWidth = card.getWidth();
            left = Math.min(left, l);
            right = Math.max(right, r);
            if (pos < dockSize) {
                float b = t + card.getHeight();
                if (!dock) rect.set(l, t, r, b); else rect.union(l, t, r, b);
                dock = true;
            } else {
                // Down to the item's bottom, so the app names under the tiles sit inside the glass too.
                float b = child.getBottom() + child.getTranslationY();
                if (!rows) rowsRect.set(l, t, r, b); else rowsRect.union(l, t, r, b);
                rows = true;
            }
        }
        float pad = 14 * density;
        // A focused tile zooms by FOCUS_SCALE; widen the sides by that growth so the first and last tile keep
        // the same margin when focused instead of nearly touching the edge. Top and bottom have room already.
        float side = pad + cardWidth * (AppAdapter.FOCUS_SCALE - 1f) / 2f;
        // Dock band and rows panel share one width, so a short last row still lines up with the dock.
        if (dock) drawGlass(c, rect, left - side, right + side, pad);
        if (rows) drawGlass(c, rowsRect, left - side, right + side, pad);
    }

    private void drawGlass(Canvas c, RectF r, float left, float right, float pad) {
        r.set(left, r.top - pad, right, r.bottom + pad);
        float radius = 26 * density;
        // The glass moves with the grid, so the gradient follows its rect.
        band.setShader(new LinearGradient(0f, r.top, 0f, r.bottom, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, band);
        c.drawRoundRect(r, radius, radius, edge);
    }
}

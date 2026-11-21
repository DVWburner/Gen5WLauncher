package com.gen5w.launcher.render;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.Scroller;
import android.widget.TextView;

import com.gen5w.launcher.model.IndicatorStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free horizontal swipeable workspace pager tailored for KitKat API 19.
 * Yields clicks cleanly to child tiles while snapping crisply on horizontal drags/flicks.
 */
public final class HorizontalPagerView extends ViewGroup {
    public interface OnPageChangeListener {
        void onPageSelected(int index);
    }

    private static final int INVALID_POINTER = -1;
    private final Scroller scroller;
    private final int touchSlop;
    private final int minFlingVel;
    private VelocityTracker velocityTracker;
    private int activePointerId = INVALID_POINTER;
    private float lastX;
    private float startX;
    private float startY;
    private boolean dragging = false;
    private int currentPage = 0;
    private int lastLayoutWidth = -1;
    private final List<String> pageTitles = new ArrayList<String>();
    private final List<OnPageChangeListener> listeners = new ArrayList<OnPageChangeListener>();

    public HorizontalPagerView(Context context) {
        super(context);
        scroller = new Scroller(context, new DecelerateInterpolator());
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVel = vc.getScaledMinimumFlingVelocity();
    }

    public void addPage(View page, String title) {
        pageTitles.add(title == null ? "" : title);
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public int getPageCount() {
        return getChildCount();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public String getPageTitle(int index) {
        return (index >= 0 && index < pageTitles.size()) ? pageTitles.get(index) : "";
    }

    public void addOnPageChangeListener(OnPageChangeListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void setCurrentPage(int index, boolean smooth) {
        int count = getChildCount();
        if (count == 0) {
            return;
        }
        int clamped = Math.max(0, Math.min(count - 1, index));
        boolean changed = (clamped != currentPage);
        currentPage = clamped;
        int targetX = clamped * getWidth();
        if (!scroller.isFinished()) {
            scroller.abortAnimation();
        }
        if (smooth && getWidth() > 0) {
            int dx = targetX - getScrollX();
            scroller.startScroll(getScrollX(), 0, dx, 0, 280);
            invalidate();
        } else {
            scrollTo(targetX, 0);
        }
        if (changed) {
            notifyListeners(clamped);
        }
    }

    public boolean setCurrentPageByIdOrIndex(String target, boolean smooth) {
        if (target == null || target.trim().isEmpty()) {
            return false;
        }
        String t = target.trim();
        for (int i = 0; i < getChildCount(); i++) {
            Object tag = getChildAt(i).getTag();
            if (tag != null && t.equals(tag.toString())) {
                setCurrentPage(i, smooth);
                return true;
            }
        }
        try {
            int idx = Integer.parseInt(t);
            setCurrentPage(idx, smooth);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int cwSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int chSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cwSpec, chSpec);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            int left = i * w;
            getChildAt(i).layout(left, 0, left + w, h);
        }
        // Only snap scrollX on initial layout or genuine viewport width changes —
        // never snap mid-drag or mid-scroll when a child TextView updates telemetry at 100ms!
        if (w > 0 && w != lastLayoutWidth) {
            lastLayoutWidth = w;
            if (!scroller.isFinished()) {
                scroller.abortAnimation();
            }
            scrollTo(currentPage * w, 0);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (getChildCount() <= 1) {
            return false;
        }
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && dragging) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = ev.getPointerId(0);
                startX = ev.getX(0);
                startY = ev.getY(0);
                lastX = startX;
                if (!scroller.isFinished()) {
                    scroller.computeScrollOffset();
                    int remaining = Math.abs(scroller.getFinalX() - scroller.getCurrX());
                    scroller.abortAnimation();
                    if (remaining > touchSlop) {
                        dragging = true;
                        requestDisallowIntercept(true);
                    } else {
                        scrollTo(scroller.getFinalX(), 0);
                        dragging = false;
                    }
                } else {
                    dragging = false;
                }
                initVelocityTracker(ev);
                break;
            case MotionEvent.ACTION_MOVE: {
                int pIdx = activePointerId == INVALID_POINTER ? -1 : ev.findPointerIndex(activePointerId);
                if (pIdx < 0) {
                    break;
                }
                float x = ev.getX(pIdx);
                float y = ev.getY(pIdx);
                float dx = Math.abs(x - startX);
                float dy = Math.abs(y - startY);
                if (dx > touchSlop && dx > dy * 1.2f) {
                    dragging = true;
                    lastX = x;
                    requestDisallowIntercept(true);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                activePointerId = INVALID_POINTER;
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (getChildCount() <= 1) {
            return false;
        }
        initVelocityTracker(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                activePointerId = ev.getPointerId(0);
                lastX = ev.getX(0);
                startX = lastX;
                break;
            case MotionEvent.ACTION_MOVE: {
                int pIdx = activePointerId == INVALID_POINTER ? 0 : ev.findPointerIndex(activePointerId);
                if (pIdx < 0) {
                    pIdx = 0;
                    activePointerId = ev.getPointerId(0);
                    lastX = ev.getX(0);
                }
                float x = ev.getX(pIdx);
                int dx = Math.round(lastX - x);
                lastX = x;
                int maxScroll = Math.max(0, (getChildCount() - 1) * getWidth());
                int nextScroll = Math.max(0, Math.min(maxScroll, getScrollX() + dx));
                scrollTo(nextScroll, 0);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
            case MotionEvent.ACTION_CANCEL: {
                recycleVelocityTracker();
                dragging = false;
                activePointerId = INVALID_POINTER;
                int w = Math.max(1, getWidth());
                setCurrentPage(Math.round((float) getScrollX() / w), true);
                break;
            }
            case MotionEvent.ACTION_UP: {
                velocityTracker.computeCurrentVelocity(1000);
                float vx = activePointerId != INVALID_POINTER
                        ? velocityTracker.getXVelocity(activePointerId)
                        : velocityTracker.getXVelocity();
                int pIdx = activePointerId == INVALID_POINTER ? -1 : ev.findPointerIndex(activePointerId);
                float endX = pIdx >= 0 ? ev.getX(pIdx) : lastX;
                float totalDeltaX = endX - startX;
                recycleVelocityTracker();
                dragging = false;
                activePointerId = INVALID_POINTER;
                int w = Math.max(1, getWidth());
                int scrollX = Math.max(0, getScrollX());
                int leftPage = scrollX / w;
                int target;
                if (Math.abs(totalDeltaX) > touchSlop * 2 && Math.abs(vx) > Math.max(400, minFlingVel)) {
                    target = vx < 0 ? leftPage + 1 : leftPage;
                } else {
                    target = Math.round((float) scrollX / w);
                }
                setCurrentPage(target, true);
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        int pointerIndex = ev.getActionIndex();
        int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == activePointerId) {
            int newIndex = pointerIndex == 0 ? 1 : 0;
            if (newIndex < ev.getPointerCount()) {
                lastX = ev.getX(newIndex);
                activePointerId = ev.getPointerId(newIndex);
            }
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    private void requestDisallowIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void initVelocityTracker(MotionEvent ev) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void notifyListeners(int index) {
        for (OnPageChangeListener l : listeners) {
            l.onPageSelected(index);
        }
    }

    /** Clickable page indicator strip displaying labeled pills, numbers, or compact dots/bars synced to HorizontalPagerView. */
    public static final class PageDotsBar extends LinearLayout {
        private final HorizontalPagerView pager;
        private final IndicatorStyle cfg;
        private final int accentColor;
        private final float scaleX;
        private final float scaleY;
        private final float scaleMin;
        private final boolean dotsMode;
        private final List<TextView> chips = new ArrayList<TextView>();

        private static IndicatorStyle legacyStyle(boolean labeled) {
            IndicatorStyle s = new IndicatorStyle();
            s.mode = labeled ? "pills" : "dots";
            return s;
        }

        public PageDotsBar(Context context, final HorizontalPagerView pager, int accentColor, boolean labeled) {
            this(context, pager, legacyStyle(labeled), accentColor, null, 1f, 1f, 1f);
        }

        public PageDotsBar(
                Context context,
                final HorizontalPagerView pager,
                IndicatorStyle style,
                int accentColor,
                AssetResolver assets,
                float scaleX,
                float scaleY,
                float scaleMin) {
            super(context);
            this.pager = pager;
            this.cfg = style != null ? style : new IndicatorStyle();
            this.accentColor = accentColor;
            this.scaleX = scaleX > 0f ? scaleX : 1f;
            this.scaleY = scaleY > 0f ? scaleY : 1f;
            this.scaleMin = scaleMin > 0f ? scaleMin : 1f;
            this.dotsMode = "dots".equalsIgnoreCase(this.cfg.mode);
            boolean numbersMode = "numbers".equalsIgnoreCase(this.cfg.mode);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            int count = pager.getPageCount();
            boolean bold = !"normal".equalsIgnoreCase(this.cfg.fontWeight);
            Typeface tf = assets != null
                    ? assets.loadTypeface(this.cfg.fontFamily, bold, this.cfg.fontItalic)
                    : Typeface.create(Typeface.DEFAULT, (bold ? Typeface.BOLD : Typeface.NORMAL) | (this.cfg.fontItalic ? Typeface.ITALIC : 0));

            for (int i = 0; i < count; i++) {
                final int idx = i;
                String title = pager.getPageTitle(i);
                TextView chip = new TextView(context);
                chip.setGravity(Gravity.CENTER);
                LayoutParams lp;
                if (!dotsMode) {
                    String label = numbersMode
                            ? String.valueOf(i + 1)
                            : (!title.isEmpty() ? title : ("Page " + (i + 1)));
                    chip.setText(label);
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(6f, this.cfg.textSizePx * this.scaleMin));
                    chip.setTypeface(tf);
                    chip.setPadding(
                            sx(this.cfg.padXPx),
                            sy(this.cfg.padYPx),
                            sx(this.cfg.padXPx),
                            sy(this.cfg.padYPx));
                    lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                } else {
                    chip.setText("");
                    lp = new LayoutParams(Math.max(2, sx(this.cfg.dotWidthPx)), Math.max(2, sy(this.cfg.dotHeightPx)));
                }
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pager.setCurrentPage(idx, true);
                    }
                });
                if (i < count - 1) {
                    lp.rightMargin = sx(this.cfg.gapPx);
                }
                addView(chip, lp);
                chips.add(chip);
            }
            updateActive(pager.getCurrentPage());
            pager.addOnPageChangeListener(new OnPageChangeListener() {
                @Override
                public void onPageSelected(int index) {
                    updateActive(index);
                }
            });
        }

        private int sx(float v) {
            return Math.round(v * scaleX);
        }

        private int sy(float v) {
            return Math.round(v * scaleY);
        }

        public void updateActive(int activeIdx) {
            int activeFill = cfg.resolveActiveFillColor(accentColor);
            int activeBorder = cfg.resolveActiveBorderColor(accentColor);
            int borderPx = cfg.borderWidthPx > 0 ? Math.max(1, Math.round(cfg.borderWidthPx * scaleMin)) : 0;
            float radiusPx = Math.max(0f, cfg.cornerRadiusPx * scaleMin);

            for (int i = 0; i < chips.size(); i++) {
                boolean active = (i == activeIdx);
                TextView tv = chips.get(i);
                if (!dotsMode) {
                    tv.setTextColor(active ? cfg.activeTextColor : cfg.inactiveTextColor);
                } else if (cfg.activeDotWidthPx > 0) {
                    ViewGroup.LayoutParams rawLp = tv.getLayoutParams();
                    int targetW = Math.max(2, sx(active ? cfg.activeDotWidthPx : cfg.dotWidthPx));
                    if (rawLp != null && rawLp.width != targetW) {
                        rawLp.width = targetW;
                        tv.setLayoutParams(rawLp);
                    }
                }
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(radiusPx);
                gd.setColor(active ? activeFill : cfg.inactiveFillColor);
                if (borderPx > 0) {
                    gd.setStroke(borderPx, active ? activeBorder : cfg.inactiveBorderColor);
                }
                tv.setBackground(gd);
            }
        }
    }
}

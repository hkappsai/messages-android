package co.hkapps.messages;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.ListView;

/**
 * Swipe a conversation row sideways: right to call, left to delete.
 * Rows need a "content" view on top of a "swipe_bg" view (see item_conversation.xml).
 */
final class SwipeHelper implements View.OnTouchListener, AbsListView.OnScrollListener {

    interface Callback {
        void onSwipeRight(int position);
        void onSwipeLeft(int position);
    }

    private static final float TRIGGER_FRACTION = 0.3f;
    private static final long ANIMATION_MS = 150;

    private final ListView list;
    private final Callback callback;
    private final int slop;
    private final int callColor;
    private final int deleteColor;

    private View content;
    private View background;
    private int position = ListView.INVALID_POSITION;
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean scrolling;

    SwipeHelper(ListView list, Callback callback) {
        this.list = list;
        this.callback = callback;
        this.slop = ViewConfiguration.get(list.getContext()).getScaledTouchSlop();
        this.callColor = list.getContext().getColor(R.color.swipe_call);
        this.deleteColor = list.getContext().getColor(R.color.swipe_delete);
        list.setOnTouchListener(this);
        list.setOnScrollListener(this);
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int state) {
        scrolling = state == SCROLL_STATE_TOUCH_SCROLL;
    }

    @Override
    public void onScroll(AbsListView view, int first, int visible, int total) {}

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                if (!scrolling) findRow(e.getX(), e.getY());
                downX = e.getRawX();
                downY = e.getRawY();
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (content == null || scrolling) return false;
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 2) {
                    swiping = true;
                    list.requestDisallowInterceptTouchEvent(true);
                    // Tell the list to drop its own tap/press so the row doesn't open.
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    list.onTouchEvent(cancel);
                    cancel.recycle();
                }
                if (swiping) {
                    content.setTranslationX(dx);
                    showBackground(dx);
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_UP:
                if (swiping) {
                    finish(e.getRawX() - downX);
                    reset();
                    return true;
                }
                reset();
                return false;

            case MotionEvent.ACTION_CANCEL:
                if (swiping) finish(0);
                reset();
                return false;

            default:
                return false;
        }
    }

    private void findRow(float x, float y) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom()) {
                content = child.findViewById(R.id.content);
                background = child.findViewById(R.id.swipe_bg);
                position = list.getPositionForView(child);
                if (background == null) content = null;
                return;
            }
        }
    }

    private void showBackground(float dx) {
        if (dx == 0) {
            background.setVisibility(View.INVISIBLE);
            return;
        }
        background.setVisibility(View.VISIBLE);
        background.setBackgroundColor(dx > 0 ? callColor : deleteColor);
        background.findViewById(R.id.swipe_call).setVisibility(dx > 0 ? View.VISIBLE : View.INVISIBLE);
        background.findViewById(R.id.swipe_delete).setVisibility(dx < 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void finish(float dx) {
        final View row = content;
        final View bg = background;
        final int pos = position;
        float width = list.getWidth();

        if (Math.abs(dx) > width * TRIGGER_FRACTION && pos != ListView.INVALID_POSITION) {
            row.animate().translationX(dx > 0 ? width : -width).setDuration(ANIMATION_MS)
                    .withEndAction(() -> {
                        row.setTranslationX(0);
                        bg.setVisibility(View.INVISIBLE);
                        if (dx > 0) callback.onSwipeRight(pos); else callback.onSwipeLeft(pos);
                    }).start();
        } else {
            row.animate().translationX(0).setDuration(ANIMATION_MS)
                    .withEndAction(() -> bg.setVisibility(View.INVISIBLE)).start();
        }
    }

    private void reset() {
        swiping = false;
        content = null;
        background = null;
        position = ListView.INVALID_POSITION;
    }
}

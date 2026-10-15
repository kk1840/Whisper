package com.stepos.whisper.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stepos.whisper.R;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.UserColors;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.databinding.ItemFactCardBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;

/**
 * 待办 / 事实卡堆：卡片完全不透明、不规则地错位叠放。按住最上面一张可以任意拖动（完全跟手），
 * 拖得够远或甩得够快就飞出屏幕，然后滑回整叠的最底下，同时下一张上前；没拖够就弹回原位。
 * 轻点最上面一张触发点击。
 */
public class CardStackView extends FrameLayout {
    public interface Listener {
        void onCardClick(FactCard card);

        /** 最上面一张变化，index 为它在原始列表中的序号。 */
        void onTopChanged(int index, int count);
    }

    /** 能看到的层数，更深的卡片叠在最后一层下面。 */
    private static final int SLOTS = 5;
    private static final float[] SLOT_ROTATION = {0f, -5f, 4.5f, -3f, 6f};
    private static final float[] SLOT_TX_DP = {0f, -16f, 18f, -10f, 14f};
    private static final float[] SLOT_TY_DP = {0f, 12f, 22f, 30f, 36f};
    private static final float[] SLOT_SCALE = {1f, 0.95f, 0.91f, 0.875f, 0.85f};
    /**
     * 每层在卡片自身 elevation 上再抬起的高度，越靠上越高、阴影越重；更深的卡片为 0。
     * 可见的卡片 Z 必须两两不同：渲染线程给 Z 相同的子 View 排序时沿用上一帧的先后而不是子 View 顺序，
     * 拖动或回弹改过 Z 之后会把下面的卡片画到最上面。
     */
    private static final float[] SLOT_Z_DP = {10f, 7.5f, 5f, 3f, 1.5f};
    /** 拖动、飞出中的卡片比最上面一层再高出这么多。 */
    private static final float DRAG_Z_DP = 8f;
    private static final float DRAG_ROTATION = 16f;
    private static final float FLY_OUT_FRACTION = 0.35f;
    private static final float FLY_OUT_VELOCITY_DP = 1200f;
    /** 甩出至少要拖动这么远；手指点按时的抖动速度可能很大，不能只看速度。 */
    private static final float FLY_OUT_MIN_DISTANCE_DP = 64f;
    /** 松手时总位移小于这个值视为点击。 */
    private static final float TAP_MAX_DISTANCE_DP = 24f;
    /** 卡片飞行或被拖动时把整个卡片区抬到下半部分面板之上，卡片才能盖过面板飞出屏幕。 */
    private static final float LIFT_DP = 24f;

    private static class Card {
        FactCard data;
        final View view;
        final ItemFactCardBinding binding;
        /** 正在被拖动或飞出，不参与重新排位动画。 */
        boolean busy;

        Card(ItemFactCardBinding binding) {
            this.binding = binding;
            this.view = binding.getRoot();
        }
    }

    private final float mDensity;
    private final int mTouchSlop;
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
    /** 下标 0 为最上面一张。 */
    private final List<Card> mDeck = new ArrayList<>();
    private List<Long> mSourceOrder = new ArrayList<>();
    @Nullable
    private List<FactCard> mPendingSubmit;
    private Listener mListener;

    private Card mDragging;
    private boolean mDragStarted;
    private float mDownX;
    private float mDownY;
    private float mStartTx;
    private float mStartTy;
    private VelocityTracker mVelocity;
    private final Runnable mUnlift = () -> {
        if (!anyBusy()) {
            setLifted(false);
        }
    };

    public CardStackView(@NonNull Context context) {
        this(context, null);
    }

    public CardStackView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public int getCount() {
        return mDeck.size();
    }

    // ---------------------------------------------------------------- 数据

    /** 保留现有卡片的先后顺序，新事项放到最上面。拖动或飞行中先暂存，结束后再应用。 */
    public void submit(List<FactCard> cards) {
        if (anyBusy()) {
            mPendingSubmit = cards;
            return;
        }
        mPendingSubmit = null;
        Map<Long, FactCard> incoming = new HashMap<>();
        List<Long> order = new ArrayList<>();
        for (FactCard c : cards) {
            incoming.put(c.fact.id, c);
            order.add(c.fact.id);
        }
        mSourceOrder = order;

        Map<Long, Card> updated = new HashMap<>();
        List<Card> kept = new ArrayList<>();
        Map<Long, Card> existing = new HashMap<>();
        for (Card card : mDeck) {
            FactCard data = incoming.get(card.data.fact.id);
            if (data == null) {
                removeView(card.view);
            } else {
                UserFact old = card.data.fact;
                UserFact next = data.fact;
                boolean changed = next.kind == UserFact.KIND_TODO
                        && (!Objects.equals(old.content, next.content)
                        || !Objects.equals(old.dueTime, next.dueTime)
                        || !Objects.equals(old.location, next.location)
                        || !Objects.equals(old.people, next.people));
                card.data = data;
                bind(card);
                if (changed) updated.put(next.id, card);
                else kept.add(card);
                existing.put(data.fact.id, card);
            }
        }
        List<Card> added = new ArrayList<>();
        for (FactCard data : cards) {
            if (existing.containsKey(data.fact.id)) {
                Card changed = updated.get(data.fact.id);
                if (changed != null) added.add(changed);
                continue;
            }
            Card card = new Card(ItemFactCardBinding.inflate(LayoutInflater.from(getContext()), this, false));
            card.data = data;
            bind(card);
            addView(card.view);
            card.view.setAlpha(1f);
            card.view.setScaleX(0.8f);
            card.view.setScaleY(0.8f);
            added.add(card);
        }
        mDeck.clear();
        mDeck.addAll(added);
        mDeck.addAll(kept);
        settle(true);
        notifyTop();
    }

    private void bind(Card card) {
        FactCard data = card.data;
        UserFact f = data.fact;
        ItemFactCardBinding b = card.binding;
        Context ctx = getContext();
        b.card.setCardBackgroundColor(ColorStateList.valueOf(0xFF000000 | UserColors.orDefault(data.userColor, f.userId)));
        b.user.setText(data.userName);
        b.type.setText(f.type);
        b.event.setText(f.content);
        boolean fact = f.kind == UserFact.KIND_FACT;
        b.time.setVisibility(fact ? GONE : VISIBLE);
        b.location.setVisibility(fact ? GONE : VISIBLE);
        b.people.setVisibility(fact ? GONE : VISIBLE);
        b.recorded.setVisibility(fact ? VISIBLE : GONE);
        if (fact) {
            b.recorded.setText(ctx.getString(R.string.card_recorded, mDateFormat.format(new Date(f.createdAt))));
        } else {
            b.time.setText(ctx.getString(R.string.card_time, FactFormat.orEmpty(f.dueTime)));
            b.location.setText(ctx.getString(R.string.card_location, FactFormat.orEmpty(f.location)));
            b.people.setText(ctx.getString(R.string.card_people, FactFormat.orEmpty(f.people)));
        }
    }

    private void notifyTop() {
        if (mListener == null) {
            return;
        }
        int index = mDeck.isEmpty() ? 0 : Math.max(0, mSourceOrder.indexOf(mDeck.get(0).data.fact.id));
        mListener.onTopChanged(index, mDeck.size());
    }

    // ---------------------------------------------------------------- 排位

    private int slotOf(int deckIndex) {
        return Math.min(deckIndex, SLOTS - 1);
    }

    /** 每张卡片在自身槽位的基础上再带一点由事项 id 决定的倾角，叠起来更不规则；最上面一张摆正。 */
    private float rotationFor(Card card, int slot) {
        if (slot == 0) {
            return 0f;
        }
        long seed = card.data.fact.id * 2654435761L;
        return SLOT_ROTATION[slot] + (Math.floorMod(seed, 7) - 3) * 0.5f;
    }

    private float zFor(int deckIndex) {
        return deckIndex < SLOTS ? SLOT_Z_DP[deckIndex] * mDensity : 0f;
    }

    private void settle(boolean animate) {
        // 上下层次由 zFor 决定；子 View 顺序也按牌堆排好，被盖住的卡片之间保持一致。
        for (int i = mDeck.size() - 1; i >= 0; i--) {
            bringChildToFront(mDeck.get(i).view);
        }
        for (int i = 0; i < mDeck.size(); i++) {
            Card card = mDeck.get(i);
            if (card.busy) {
                continue;
            }
            moveToSlot(card, i, animate, 260, null);
        }
    }

    private void moveToSlot(Card card, int deckIndex, boolean animate, long duration, @Nullable Runnable end) {
        int slot = slotOf(deckIndex);
        View v = card.view;
        v.animate().cancel();
        v.setTranslationZ(zFor(deckIndex));
        // 叠在最后一层下面的卡片完全被盖住，不画。最后一张保留可见，方便刚甩到底的卡片看得到落位。
        v.setVisibility(deckIndex < SLOTS || deckIndex == mDeck.size() - 1 ? VISIBLE : INVISIBLE);
        float tx = SLOT_TX_DP[slot] * mDensity;
        float ty = SLOT_TY_DP[slot] * mDensity;
        float rot = rotationFor(card, slot);
        float scale = SLOT_SCALE[slot];
        if (!animate) {
            v.setTranslationX(tx);
            v.setTranslationY(ty);
            v.setRotation(rot);
            v.setScaleX(scale);
            v.setScaleY(scale);
            if (end != null) {
                end.run();
            }
            return;
        }
        v.animate()
                .translationX(tx)
                .translationY(ty)
                .rotation(rot)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(end)
                .start();
    }

    private void setLifted(boolean lifted) {
        if (getParent() instanceof View) {
            ((View) getParent()).setTranslationZ(lifted ? LIFT_DP * mDensity : 0f);
        }
    }

    private boolean anyBusy() {
        for (Card card : mDeck) {
            if (card.busy) {
                return true;
            }
        }
        return false;
    }

    /** 某张卡片结束拖动或飞出后调用：稍后放下卡片区，并应用期间暂存的数据。 */
    private void onCardReleased(long settleMs) {
        removeCallbacks(mUnlift);
        postDelayed(mUnlift, settleMs);
        if (!anyBusy() && mPendingSubmit != null) {
            submit(mPendingSubmit);
        }
    }

    // ---------------------------------------------------------------- 拖动

    private boolean hitTop(float x, float y) {
        if (mDeck.isEmpty()) {
            return false;
        }
        Card top = mDeck.get(0);
        if (top.busy) {
            return false;
        }
        Rect r = new Rect();
        top.view.getHitRect(r);
        return r.contains((int) x, (int) y);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return ev.getActionMasked() == MotionEvent.ACTION_DOWN && hitTop(ev.getX(), ev.getY());
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!hitTop(ev.getX(), ev.getY())) {
                    return false;
                }
                mDragging = mDeck.get(0);
                mDragging.busy = true;
                mDragging.view.animate().cancel();
                mDragStarted = false;
                mDownX = ev.getX();
                mDownY = ev.getY();
                mStartTx = mDragging.view.getTranslationX();
                mStartTy = mDragging.view.getTranslationY();
                mVelocity = VelocityTracker.obtain();
                mVelocity.addMovement(ev);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mDragging == null) {
                    return false;
                }
                mVelocity.addMovement(ev);
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                if (!mDragStarted && Math.hypot(dx, dy) > mTouchSlop) {
                    mDragStarted = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    removeCallbacks(mUnlift);
                    setLifted(true);
                    mDragging.view.setTranslationZ(zFor(0) + DRAG_Z_DP * mDensity);
                }
                if (mDragStarted) {
                    View v = mDragging.view;
                    v.setTranslationX(mStartTx + dx);
                    v.setTranslationY(mStartTy + dy);
                    v.setRotation(dx / Math.max(1, v.getWidth()) * DRAG_ROTATION);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragging == null) {
                    return false;
                }
                mVelocity.addMovement(ev);
                mVelocity.computeCurrentVelocity(1000);
                float vx = mVelocity.getXVelocity();
                float vy = mVelocity.getYVelocity();
                mVelocity.recycle();
                mVelocity = null;
                Card card = mDragging;
                mDragging = null;
                boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                boolean tap = !cancel && Math.hypot(ev.getX() - mDownX, ev.getY() - mDownY)
                        < TAP_MAX_DISTANCE_DP * mDensity;
                if (mDragStarted) {
                    release(card, vx, vy, cancel || tap);
                } else {
                    card.busy = false;
                    onCardReleased(0);
                }
                if (tap && mListener != null) {
                    performClick();
                    mListener.onCardClick(card.data);
                }
                return true;
            }
            default:
                return mDragging != null;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** springBack 为 true 时（取消或其实是点击）一律弹回原位。 */
    private void release(Card card, float vx, float vy, boolean springBack) {
        View v = card.view;
        float dx = v.getTranslationX() - SLOT_TX_DP[0] * mDensity;
        float dy = v.getTranslationY() - SLOT_TY_DP[0] * mDensity;
        float speed = (float) Math.hypot(vx, vy);
        boolean flung = speed > FLY_OUT_VELOCITY_DP * mDensity && (vx * dx + vy * dy) > 0
                && Math.hypot(dx, dy) > FLY_OUT_MIN_DISTANCE_DP * mDensity;
        boolean far = Math.abs(dx) > v.getWidth() * FLY_OUT_FRACTION
                || Math.abs(dy) > v.getHeight() * FLY_OUT_FRACTION;
        if (springBack || !(flung || far)) {
            card.busy = false;
            v.animate().cancel();
            v.animate()
                    .translationX(SLOT_TX_DP[0] * mDensity)
                    .translationY(SLOT_TY_DP[0] * mDensity)
                    .rotation(rotationFor(card, 0))
                    .translationZ(zFor(0))
                    .setDuration(280)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .withEndAction(null)
                    .start();
            onCardReleased(300);
            return;
        }

        // 飞出：沿甩动方向（甩得不够快时沿拖动方向）飞到屏幕外。
        float dirX = flung ? vx : dx;
        float dirY = flung ? vy : dy;
        float len = (float) Math.hypot(dirX, dirY);
        dirX /= len;
        dirY /= len;
        View root = getRootView();
        float distance = (float) Math.hypot(root.getWidth(), root.getHeight());
        long duration = flung ? (long) Math.max(160, Math.min(320, distance / speed * 1000)) : 260;

        // 牌堆先轮换，后面的卡片同时上前。
        mDeck.remove(card);
        mDeck.add(card);
        settle(true);
        notifyTop();

        v.animate().cancel();
        v.animate()
                .translationX(v.getTranslationX() + dirX * distance)
                .translationY(v.getTranslationY() + dirY * distance)
                .rotation(v.getRotation() + dirX * 25f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(0.8f))
                .withEndAction(() -> {
                    // 已在屏幕外：压到最底层，再滑回整叠卡片的最后面。
                    card.busy = false;
                    int index = mDeck.indexOf(card);
                    if (index >= 0) {
                        moveToSlot(card, index, true, 420, null);
                    }
                    onCardReleased(450);
                })
                .start();
    }
}

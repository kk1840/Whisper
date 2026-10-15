package com.stepos.whisper.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.stepos.whisper.R;
import com.stepos.whisper.data.FactCard;
import com.stepos.whisper.data.UserFact;
import com.stepos.whisper.databinding.ItemMergeCardBinding;
import com.stepos.whisper.llm.MergeSuggestion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real view binding, isolated synthetic cards, no activity or network. */
public final class CardStackUiChecks {
    public static void runChecks(Instrumentation runner) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runner.runOnMainSync(() -> {
            try { checks(new ContextThemeWrapper(runner.getTargetContext(), R.style.Theme_Whisper_NoActionBar)); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void checks(Context context) throws Exception {
        CardStackView stack = new CardStackView(context);
        FrameLayout parent = new FrameLayout(context);
        parent.addView(stack);
        FactCard a = card(1, "A", null, null, null);
        FactCard b = card(2, "B", null, null, null);
        stack.submit(List.of(b, a));
        check(ids(stack).equals(List.of(2L, 1L)), "initial newest first");
        List<Object> deck = deck(stack);
        Collections.rotate(deck, -1); // The same deck rotation used when a card is flung.
        stack.submit(List.of(b, a));
        check(ids(stack).equals(List.of(1L, 2L)), "refresh preserves browsing order");
        FactCard renamed = card(2, "B", null, null, null);
        renamed.userName = "renamed"; renamed.userColor = 123;
        stack.submit(List.of(renamed, a));
        check(ids(stack).equals(List.of(1L, 2L)), "name/color do not promote");
        for (FactCard updated : List.of(card(2, "Merged B", null, null, null),
                card(2, "Merged B", "16点", null, null), card(2, "Merged B", "16点", "B室", null),
                card(2, "Merged B", "16点", "B室", "李四"))) {
            stack.submit(List.of(updated, a));
            check(ids(stack).get(0) == 2L, "each semantic field change promotes old id");
            Collections.rotate(deck(stack), -1);
        }
        FactCard c = card(3, "C", null, null, null);
        stack.submit(List.of(c, card(2, "Merged B", "16点", "B室", "李四"), a));
        check(ids(stack).equals(List.of(3L, 1L, 2L)), "new id first, remaining order preserved");
        stack.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        stack.layout(0, 0, 600, 500);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100, 100, 0);
        check(stack.onTouchEvent(down), "touch starts drag"); down.recycle();
        FactCard d = card(4, "D", null, null, null);
        stack.submit(List.of(d, c, card(2, "Merged B", "16点", "B室", "李四"), a));
        check(stack.getCount() == 3, "drag defers submit");
        MotionEvent cancel = MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 100, 100, 0);
        stack.onTouchEvent(cancel); cancel.recycle();
        check(ids(stack).get(0) == 4L && stack.getCount() == 4, "release applies deferred newest");
        mergePreview(context, parent);
    }

    private static FactCard card(long id, String text, String time, String location, String people) {
        FactCard c = new FactCard(); c.userName = "Synthetic user"; c.userColor = 0xFF1565C0;
        c.fact = new UserFact(); c.fact.id = id; c.fact.userId = 1; c.fact.content = text;
        c.fact.dueTime = time; c.fact.location = location; c.fact.people = people;
        return c;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> deck(CardStackView view) throws Exception {
        Field field = CardStackView.class.getDeclaredField("mDeck"); field.setAccessible(true);
        return (List<Object>) field.get(view);
    }
    private static List<Long> ids(CardStackView view) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (Object card : deck(view)) {
            Field field = card.getClass().getDeclaredField("data"); field.setAccessible(true);
            ids.add(((FactCard) field.get(card)).fact.id);
        }
        return ids;
    }
    private static void mergePreview(Context context, FrameLayout parent) throws Exception {
        FactCard target = card(1, "Old event", "周五15点", "A室", "张三");
        FactCard incoming = card(2, "Materials", null, "B室", "张三、李四");
        MergeOverlay overlay = new MergeOverlay(context);
        Method bind = MergeOverlay.class.getDeclaredMethod("bindCard", ItemMergeCardBinding.class,
                FactCard.class, String.class, MergeSuggestion.class);
        bind.setAccessible(true);
        ItemMergeCardBinding binding = ItemMergeCardBinding.inflate(LayoutInflater.from(context), parent, false);
        MergeSuggestion suggestion = new MergeSuggestion(1, target, incoming, "Merged event", null,
                "B室", "张三、李四");
        bind.invoke(overlay, binding, target, "Merged", suggestion);
        check(binding.time.getText().toString().contains("周五15点"), "preview retains missing time");
        check(binding.location.getText().toString().contains("B室"), "preview overrides location");
        check(binding.people.getText().toString().contains("张三、李四"), "preview displays merged people");
        suggestion = new MergeSuggestion(1, target, incoming, "Merged event", "周六16点", null, null);
        bind.invoke(overlay, binding, target, "Merged", suggestion);
        check(binding.time.getText().toString().contains("周六16点"), "preview overrides time");
        check(binding.people.getText().toString().contains("张三"), "preview retains missing people");
        bind.invoke(overlay, binding, incoming, "Incoming", null);
        check(binding.people.getText().toString().contains("张三、李四"), "incoming displays people");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

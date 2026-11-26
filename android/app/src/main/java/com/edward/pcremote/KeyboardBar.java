package com.edward.pcremote;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Typing field + a row of special keys (Ctrl, Alt, Win, arrows, F-keys…). Used by the touchpad and screen views. */
public class KeyboardBar extends LinearLayout {
    private final EditText input;
    private final Set<Integer> mods = new LinkedHashSet<>();
    private final List<TextView> modViews = new ArrayList<>();
    private boolean suppress;

    public KeyboardBar(Activity a) {
        super(a);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BAR);
        int p = Ui.dp(a, 6);
        setPadding(p, p, p, p);

        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = Ui.hbox(a);
        hs.addView(keys);
        mod(keys, "Ctrl", Keys.CTRL);
        mod(keys, "Alt", Keys.ALT);
        mod(keys, "Shift", Keys.SHIFT);
        mod(keys, "Win", Keys.WIN);
        key(keys, "Esc", Keys.ESC);
        key(keys, "Tab", Keys.TAB);
        key(keys, "⌫", Keys.BACK);
        key(keys, "Del", Keys.DEL);
        key(keys, "↵", Keys.ENTER);
        key(keys, "←", Keys.LEFT);
        key(keys, "↑", Keys.UP);
        key(keys, "↓", Keys.DOWN);
        key(keys, "→", Keys.RIGHT);
        key(keys, "Home", Keys.HOME);
        key(keys, "End", Keys.END);
        key(keys, "PgUp", Keys.PGUP);
        key(keys, "PgDn", Keys.PGDN);
        for (int i = 1; i <= 12; i++) key(keys, "F" + i, Keys.F1 + i - 1);
        key(keys, "PrtSc", Keys.PRTSC);
        key(keys, "Ins", Keys.INS);
        key(keys, "Menu", Keys.APPS);
        addView(hs, Ui.lp(-1, -2));

        input = new EditText(a);
        input.setHint("Type here to type on the PC…");
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setBackground(Ui.round(a, Ui.CARD, 12));
        input.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        // no autocorrect: every key you press goes straight to the PC
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.topMargin = Ui.dp(a, 6);
        addView(input, lp);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppress) return;
                Conn conn = Conn.get();
                for (int i = 0; i < before; i++) conn.keys(Keys.BACK);
                if (count > 0) typed(s.subSequence(start, start + count).toString());
            }
            @Override public void afterTextChanged(Editable e) {
                if (!suppress && e.length() > 40) { suppress = true; e.clear(); suppress = false; }
            }
        });
        input.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DEL && input.length() == 0) { press(Keys.BACK); return true; }
            if (code == KeyEvent.KEYCODE_ENTER) { press(Keys.ENTER); return true; }
            return false;
        });
        input.setOnEditorActionListener((v, id, ev) -> { press(Keys.ENTER); clear(); return true; });
    }

    private void typed(String s) {
        if (mods.isEmpty()) { Conn.get().text(s); return; }
        for (char c : s.toCharArray()) {
            Integer vk = Keys.forChar(c);
            if (vk != null) press(vk); else Conn.get().text(String.valueOf(c));
        }
    }

    /** Presses a key, together with any held modifiers (which are then released). */
    private void press(int vk) {
        int[] combo = new int[mods.size() + 1];
        int i = 0;
        for (int m : mods) combo[i++] = m;
        combo[i] = vk;
        Conn.get().keys(combo);
        if (!mods.isEmpty()) { mods.clear(); refreshMods(); }
    }

    private void clear() { suppress = true; input.setText(""); suppress = false; }

    private TextView keyView(LinearLayout row, String label) {
        TextView t = Ui.text(getContext(), label, 15, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinWidth(Ui.dp(getContext(), 46));
        t.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10));
        Ui.setBg(t, Ui.CARD2, 10);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.setMarginEnd(Ui.dp(getContext(), 6));
        row.addView(t, lp);
        return t;
    }

    private void key(LinearLayout row, String label, int vk) {
        TextView t = keyView(row, label);
        t.setOnClickListener(v -> { Ui.haptic(v); press(vk); });
    }

    private void mod(LinearLayout row, String label, int vk) {
        TextView t = keyView(row, label);
        t.setTag(vk);
        modViews.add(t);
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            if (!mods.remove(vk)) mods.add(vk);
            refreshMods();
        });
    }

    private void refreshMods() {
        for (TextView t : modViews) {
            boolean on = mods.contains((Integer) t.getTag());
            Ui.setBg(t, on ? Ui.ACCENT : Ui.CARD2, 10);
            t.setTextColor(on ? Ui.WHITE : Ui.TEXT);
        }
    }

    public void showKeyboard() {
        setVisibility(VISIBLE);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        setVisibility(GONE);
    }

    public void toggle() {
        if (getVisibility() == VISIBLE) hideKeyboard(); else showKeyboard();
    }

    public boolean isShown2() { return getVisibility() == View.VISIBLE; }
}

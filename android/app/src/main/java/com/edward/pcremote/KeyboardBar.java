package com.edward.pcremote;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
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

/**
 * Keyboard for the PC, in two parts:
 *  - inputView: the text you've typed (put it near the top of the screen). It keeps everything you type.
 *  - keysView:  a row of special keys (Ctrl, Alt, Win, arrows, F-keys…) that sits above the phone keyboard.
 */
public class KeyboardBar {
    public final LinearLayout inputView;
    public final HorizontalScrollView keysView;
    private final EndEditText input;
    private final Set<Integer> mods = new LinkedHashSet<>();
    private final List<TextView> modViews = new ArrayList<>();
    private final Activity a;
    private boolean suppress, shown;

    /** An EditText whose cursor always stays at the end, so what you see matches what the PC received. */
    static final class EndEditText extends EditText {
        EndEditText(Context c) { super(c); }
        @Override protected void onSelectionChanged(int start, int end) {
            int len = length();
            if (start != len || end != len) { setSelection(len); return; }
            super.onSelectionChanged(start, end);
        }
    }

    public KeyboardBar(Activity a) {
        this.a = a;

        // ---- typed text (top) ----
        inputView = Ui.hbox(a);
        inputView.setBackgroundColor(Ui.BAR);
        int p = Ui.dp(a, 8);
        inputView.setPadding(p, p, p, p);
        input = new EndEditText(a);
        input.setHint("Type here: it goes straight to the PC");
        input.setHorizontallyScrolling(false);
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(16);
        input.setBackground(Ui.round(a, Ui.CARD, 12));
        input.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setMaxLines(3);
        input.setVerticalScrollBarEnabled(true);
        input.setGravity(Gravity.TOP | Gravity.START);
        inputView.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = Ui.text(a, "Clear", 14, Ui.ACCENT_LIGHT);
        clear.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));
        clear.setOnClickListener(v -> clearField()); // only clears this box, not the PC
        inputView.addView(clear);
        inputView.setVisibility(View.GONE);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppress) return;
                Conn conn = Conn.get();
                for (int i = 0; i < before; i++) conn.keys(Keys.BACK);
                if (count > 0) typed(s.subSequence(start, start + count).toString());
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        input.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DEL && input.length() == 0) { press(Keys.BACK); return true; }
            return false;
        });
        input.setOnEditorActionListener((v, id, ev) -> { press(Keys.ENTER); appendQuietly("\n"); return true; });

        // ---- special keys (bottom) ----
        keysView = new HorizontalScrollView(a);
        keysView.setHorizontalScrollBarEnabled(false);
        keysView.setBackgroundColor(Ui.BAR);
        LinearLayout keys = Ui.hbox(a);
        keys.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        keysView.addView(keys);
        mod(keys, "Ctrl", Keys.CTRL);
        mod(keys, "Alt", Keys.ALT);
        mod(keys, "Shift", Keys.SHIFT);
        mod(keys, "Win", Keys.WIN);
        key(keys, "Esc", Keys.ESC);
        key(keys, "Tab", Keys.TAB);
        key(keys, "Bksp", Keys.BACK);
        key(keys, "Del", Keys.DEL);
        key(keys, "Enter", Keys.ENTER);
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
        keysView.setVisibility(View.GONE);

        // When the phone keyboard closes (Back or the down-arrow), close ours too and clear the text box.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            View decor = a.getWindow().getDecorView();
            decor.setOnApplyWindowInsetsListener((v, ins) -> {
                boolean ime = ins.isVisible(android.view.WindowInsets.Type.ime());
                if (shown && imeWasVisible && !ime) hide();
                imeWasVisible = ime;
                return v.onApplyWindowInsets(ins);
            });
        }
    }

    private boolean imeWasVisible;

    private void typed(String s) {
        if (mods.isEmpty()) { Conn.get().text(s); return; }
        for (char c : s.toCharArray()) {
            Integer vk = Keys.forChar(c);
            if (vk != null) press(vk); else Conn.get().text(String.valueOf(c));
        }
    }

    /** Presses a key together with any held modifiers (which are then released). */
    private void press(int vk) {
        int[] combo = new int[mods.size() + 1];
        int i = 0;
        for (int m : mods) combo[i++] = m;
        combo[i] = vk;
        Conn.get().keys(combo);
        if (!mods.isEmpty()) { mods.clear(); refreshMods(); }
    }

    private void appendQuietly(String s) { suppress = true; input.append(s); suppress = false; }
    private void clearField() { suppress = true; input.setText(""); suppress = false; }

    private TextView keyView(LinearLayout row, String label) {
        TextView t = Ui.text(a, label, 15, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(a, 46));
        t.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        Ui.setBg(t, Ui.CARD2, 10);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.setMarginEnd(Ui.dp(a, 6));
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

    public void show() {
        shown = true;
        inputView.setVisibility(View.VISIBLE);
        keysView.setVisibility(View.VISIBLE);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    public void hide() {
        shown = false;
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        inputView.setVisibility(View.GONE);
        keysView.setVisibility(View.GONE);
        clearField(); // the box keeps your text while you type, and starts empty next time
    }

    public void toggle() { if (shown) hide(); else show(); }

    public boolean isShown() { return shown; }
}

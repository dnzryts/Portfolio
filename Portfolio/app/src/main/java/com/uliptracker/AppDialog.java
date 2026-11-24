package com.uliptracker;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Pop-ups in the app's own style: card colour, rounded corners, the app's fonts, pill buttons, filled text fields,
 * and rich menu rows (icon in a circle, title, and a description). Works with any AlertDialog.Builder code.
 *
 * Menu items can carry an icon and a description: "📈 Title\nDescription", or "Title (description)".
 */
public class AppDialog extends AlertDialog.Builder {

    private final Context ctx;
    private final float dp;
    private CharSequence[] items;

    public AppDialog(Context c) {
        super(c, Ui.LIGHT ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert);
        ctx = c;
        dp = Ui.dp(c);
    }

    @Override
    public AlertDialog.Builder setItems(CharSequence[] list, DialogInterface.OnClickListener l) {
        items = list;
        return super.setItems(list, l);
    }

    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        try {
            d.create();      // build the views now, so the dialog is styled before its first frame (no flash of the old look)
            style(d);
        } catch (Throwable ignored) { }   // never let styling break a dialog
        return d;
    }

    // ------------------------------------------------------------------ styling
    private void style(AlertDialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        bg.setCornerRadius(26 * dp);
        if (!Ui.LIGHT) bg.setStroke(Math.max(1, (int) dp), 0x14FFFFFF);
        w.setBackgroundDrawable(new InsetDrawable(bg, (int) (16 * dp), (int) (24 * dp), (int) (16 * dp), (int) (24 * dp)));
        w.setDimAmount(Ui.LIGHT ? 0.32f : 0.62f);

        int titleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId != 0 ? (TextView) d.findViewById(titleId) : null;
        if (title != null) {
            title.setTextSize(20);
            title.setTextColor(Ui.TEXT);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setLetterSpacing(-0.01f);
        }
        TextView msg = (TextView) d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(15);
            msg.setTextColor(Ui.SUB);
            msg.setLineSpacing(4 * dp, 1f);
        }
        styleButtons(d);
        styleFields(w.getDecorView());
        ListView lv = d.getListView();
        if (lv != null && items != null) {
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
            lv.setPadding(0, (int) (4 * dp), 0, (int) (12 * dp));
            lv.setClipToPadding(false);
            lv.setAdapter(new Rows(items));
        }
    }

    private void styleButtons(AlertDialog d) {
        Button pos = d.getButton(DialogInterface.BUTTON_POSITIVE);
        Button neg = d.getButton(DialogInterface.BUTTON_NEGATIVE);
        Button neu = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        for (Button b : new Button[]{pos, neg, neu}) {
            if (b == null || b.getVisibility() != View.VISIBLE) continue;
            boolean primary = b == pos;
            b.setAllCaps(false);
            b.setTextSize(15);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(primary ? Ui.ON_ACCENT : b == neu ? Ui.SUB : Ui.ACCENT);
            b.setMinHeight((int) (44 * dp));
            b.setMinimumHeight((int) (44 * dp));
            b.setPadding((int) (20 * dp), 0, (int) (20 * dp), 0);
            b.setStateListAnimator(null);
            b.setElevation(0);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(22 * dp);
            pill.setColor(primary ? Ui.ACCENT : 0x00000000);
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : (Ui.ACCENT & 0x00FFFFFF) | 0x22000000), pill, pill));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
                b.setLayoutParams(lp);
            }
        }
        View bar = pos != null ? (View) pos.getParent() : null;
        if (bar != null) bar.setPadding((int) (16 * dp), (int) (4 * dp), (int) (16 * dp), (int) (14 * dp));
    }

    /** Filled, rounded text fields with an accent outline while typing. */
    private void styleFields(View v) {
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Ui.TEXT);
            e.setHintTextColor(Ui.HINT);
            e.setTextSize(16);
            StateListDrawable s = new StateListDrawable();
            s.addState(new int[]{android.R.attr.state_focused}, field(true));
            s.addState(new int[]{}, field(false));
            e.setBackground(s);
            e.setPadding((int) (14 * dp), (int) (12 * dp), (int) (14 * dp), (int) (12 * dp));
            ViewGroup.LayoutParams lp = e.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.topMargin = Math.max(m.topMargin, (int) (4 * dp));
                m.bottomMargin = Math.max(m.bottomMargin, (int) (10 * dp));
                e.setLayoutParams(m);
            }
            return;
        }
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) styleFields(((ViewGroup) v).getChildAt(i));
    }

    private Drawable field(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.CARD2);
        g.setCornerRadius(14 * dp);
        g.setStroke((int) Math.max(1, (focused ? 1.6f : 1f) * dp), focused ? Ui.ACCENT : Ui.OUTLINE);
        return g;
    }

    // ------------------------------------------------------------------ menu rows
    /** Splits "📈 Title\nDescription" / "Title (description)" into icon, title and description. */
    static String[] parse(CharSequence cs) {
        String s = cs.toString().trim();
        String icon = null;
        int sp = s.indexOf(' ');
        if (sp > 0 && sp <= 8) {
            String first = s.substring(0, sp);
            boolean symbol = true;
            for (int i = 0; i < first.length(); ) {
                int cp = first.codePointAt(i);
                if (Character.isLetterOrDigit(cp) || cp == '₹' || cp == '(' || cp == '+' || cp == '-') { symbol = false; break; }
                i += Character.charCount(cp);
            }
            if (symbol) { icon = first; s = s.substring(sp).trim(); }
        }
        String title = s, sub = null;
        int nl = s.indexOf('\n');
        if (nl > 0) { title = s.substring(0, nl).trim(); sub = s.substring(nl + 1).trim(); }
        else if (s.endsWith(")") && s.indexOf(" (") > 0) {
            int p = s.lastIndexOf(" (");
            title = s.substring(0, p).trim();
            sub = s.substring(p + 2, s.length() - 1).trim();
        }
        return new String[]{icon, title, sub};
    }

    private class Rows extends BaseAdapter {
        private final CharSequence[] list;
        Rows(CharSequence[] list) { this.list = list; }
        @Override public int getCount() { return list.length; }
        @Override public Object getItem(int i) { return list[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            String[] p = parse(list[i]);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) (54 * dp));
            row.setPadding((int) (14 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            GradientDrawable press = new GradientDrawable();
            press.setColor(0xFF000000);
            press.setCornerRadius(16 * dp);
            RippleDrawable rd = new RippleDrawable(ColorStateList.valueOf((Ui.ACCENT & 0x00FFFFFF) | 0x26000000), null, press);
            row.setBackground(new InsetDrawable(rd, (int) (8 * dp), 0, (int) (8 * dp), 0));
            if (p[0] != null) {
                TextView ic = new TextView(ctx);
                ic.setText(p[0]);
                ic.setTextSize(19);
                ic.setGravity(Gravity.CENTER);
                GradientDrawable c = new GradientDrawable();
                c.setShape(GradientDrawable.OVAL);
                c.setColor((Ui.ACCENT & 0x00FFFFFF) | (Ui.LIGHT ? 0x1F000000 : 0x2E000000));
                ic.setBackground(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (42 * dp), (int) (42 * dp));
                lp.setMargins((int) (10 * dp), 0, (int) (14 * dp), 0);
                row.addView(ic, lp);
            } else {
                row.setPadding((int) (24 * dp), (int) (8 * dp), (int) (14 * dp), (int) (8 * dp));
            }
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(ctx);
            t.setText(p[1]);
            t.setTextSize(16);
            t.setTextColor(Ui.TEXT);
            if (p[0] != null) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            col.addView(t);
            if (p[2] != null && !p[2].isEmpty()) {
                TextView s = new TextView(ctx);
                s.setText(p[2]);
                s.setTextSize(13);
                s.setTextColor(Ui.SUB);
                s.setPadding(0, (int) (1 * dp), 0, 0);
                col.addView(s);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (p[0] != null) {
                TextView ch = new TextView(ctx);
                ch.setText("›");
                ch.setTextSize(22);
                ch.setTextColor(Ui.HINT);
                ch.setPadding((int) (8 * dp), 0, (int) (10 * dp), (int) (2 * dp));
                row.addView(ch);
            }
            return row;
        }
    }
}

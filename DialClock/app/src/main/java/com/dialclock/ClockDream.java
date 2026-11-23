package com.dialclock;

import android.service.dreams.DreamService;

/** Screen saver (shows while charging / docked) using the same dial clock. */
public class ClockDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        ClockView v = new ClockView(this);
        v.set24h(getSharedPreferences("clock", MODE_PRIVATE).getBoolean("24h", true));
        setContentView(v);
    }
}

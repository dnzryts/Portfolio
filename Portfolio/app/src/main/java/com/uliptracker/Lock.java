package com.uliptracker;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/** Fingerprint / face / screen-lock gate using the system BiometricPrompt. */
public final class Lock {
    private Lock() { }

    public interface Result { void ok(); void fail(String why); }

    public static boolean enabled(Context c) { return Alerts.prefs(c).getBoolean("lock", false); }

    public static void setEnabled(Context c, boolean on) { Alerts.prefs(c).edit().putBoolean("lock", on).apply(); }

    /** null if the phone can lock the app, otherwise the reason it can't. */
    public static String unavailableReason(Context c) {
        if (Build.VERSION.SDK_INT < 28) return "Needs Android 9 or newer";
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) return "Set up a screen lock and fingerprint in phone Settings first";
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            int r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            if (r != BiometricManager.BIOMETRIC_SUCCESS) return "No fingerprint or screen lock available";
        }
        return null;
    }

    public static void authenticate(final Activity a, String title, final Result r) {
        String why = unavailableReason(a);
        if (why != null) { r.ok(); return; }   // never lock the user out if the phone lost its lock
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setSubtitle("Use your fingerprint, or your phone PIN / pattern");
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else if (Build.VERSION.SDK_INT == 29) {
            b.setDeviceCredentialAllowed(true);
        } else {
            b.setNegativeButton("Cancel", a.getMainExecutor(), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { r.fail(null); }
            });
        }
        b.build().authenticate(new CancellationSignal(), a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) { r.ok(); }
            @Override public void onAuthenticationError(int code, CharSequence msg) {
                r.fail(code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ? null : String.valueOf(msg));
            }
        });
    }
}

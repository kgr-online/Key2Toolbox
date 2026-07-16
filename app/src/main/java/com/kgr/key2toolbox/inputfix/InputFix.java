package com.kgr.key2toolbox.inputfix;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;

/**
 * A physical-key handler ported from kgr17/q25toolbox. Returns true if it
 * consumed the event (so the app/system shouldn't also see it).
 */
public interface InputFix {
    boolean onKeyEvent(AccessibilityService service, KeyEvent event);
}

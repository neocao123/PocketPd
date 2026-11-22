package com.pocketpd.app.ui

import android.view.InputDevice
import android.view.KeyEvent

/**
 * App-wide rule (user, 2026-11-17): a mouse right-click ANYWHERE in the app
 * must NEVER navigate back — the most it does is open the object menu under
 * the cursor (editor, edit mode).
 *
 * Some devices map the mouse's right button to KEYCODE_BACK at the system
 * level; the touch pipeline never sees the button, the key arrives instead.
 * The injected key KEEPS the originating mouse's identity — verified live on
 * GM800 (Telink wireless mouse, BTN_RIGHT replayed on its evdev node):
 * deviceId = the mouse, source = SOURCE_MOUSE exactly, scanCode = 0, and
 * repeatCount ticks while the button is held. That makes an exact app-side
 * test possible.
 *
 * The composite-bit trap this must avoid: SOURCE_MOUSE (0x2002) shares its
 * SOURCE_CLASS_POINTER bit (0x2) with SOURCE_TOUCHSCREEN (0x1002), so a
 * naive `source and SOURCE_MOUSE != 0` also matches touchscreen-sourced
 * keys and would swallow genuine gesture/nav back. Both the event source
 * and the device's own source set are therefore compared against the FULL
 * SOURCE_MOUSE mask — a nav-bar or gesture back (virtual keyboard or
 * touchscreen identity) never matches, so genuine back navigation keeps
 * working on mouse-less devices.
 */
object MouseBack {

    /** True when this BACK key event originates from a mouse device. */
    fun isRightClick(e: KeyEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true
        val d = if (e.deviceId >= 0) InputDevice.getDevice(e.deviceId) else null
        return d != null &&
            (d.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }
}

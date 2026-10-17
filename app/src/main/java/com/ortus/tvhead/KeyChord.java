package com.ortus.tvhead;

import android.view.KeyEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A normalized set of simultaneously-held keys.
 *
 * <p>A single key ({@code "F13"}), a modifier combination ({@code "Ctrl+Shift+A"}) and
 * several keys held together ({@code "A+S"}) all reduce to a sorted {@code Set<String>}
 * of canonical key names, so matching a configured chord against the keys the user is
 * currently holding is a plain set comparison.</p>
 *
 * <p>Canonical names are upper-case:</p>
 * <ul>
 *   <li>Modifiers: {@code CTRL}, {@code SHIFT}, {@code ALT}, {@code META}</li>
 *   <li>Letters/digits: {@code A}-{@code Z}, {@code 0}-{@code 9}</li>
 *   <li>Function keys: {@code F1}-{@code F12}</li>
 *   <li>Named keys: {@code ENTER}, {@code SPACE}, {@code TAB}, {@code ESCAPE},
 *       {@code BACKSPACE}, {@code DELETE}, {@code HOME}, {@code BACK},
 *       {@code VOLUME_UP}, {@code VOLUME_DOWN}, {@code MEDIA_PLAY_PAUSE},
 *       {@code MEDIA_NEXT}, {@code MEDIA_PREVIOUS}, {@code MEDIA_STOP}</li>
 *   <li>Anything unrecognised falls back to {@code KEYCODE_<number>} so a binding can
 *       never become silently unmatchable - check logcat to see the real name.</li>
 * </ul>
 *
 * <p>Left/right modifier variants are collapsed (both {@code CTRL_LEFT} and
 * {@code CTRL_RIGHT} become {@code CTRL}) because programmable keyboards are
 * inconsistent about which one they report.</p>
 */
public final class KeyChord {

    private final Set<String> keys;

    private KeyChord(Set<String> keys) {
        this.keys = Collections.unmodifiableSet(keys);
    }

    /**
     * Builds a chord from already-canonical key names.
     *
     * @param names canonical names, see class docs
     * @return an immutable chord containing exactly those names
     */
    public static KeyChord of(Set<String> names) {
        return new KeyChord(new TreeSet<>(names));
    }

    /**
     * Parses a human-written chord such as {@code "F13"}, {@code "ctrl + a"} or
     * {@code "Ctrl+Shift+A"}.
     *
     * <p>Parts are separated by {@code +}. Whitespace is ignored and the comparison is
     * case-insensitive, so {@code "Ctrl + A"} and {@code "ctrl+a"} are the same chord.
     * A {@code KEYCODE_} prefix on any part is stripped, so pasted logcat values such as
     * {@code "KEYCODE_F13"} work as-is.</p>
     *
     * @param spec the chord specification from the config file
     * @return the parsed chord; an empty chord if {@code spec} is blank
     */
    public static KeyChord parse(String spec) {
        Set<String> names = new TreeSet<>();
        if (spec == null) {
            return new KeyChord(names);
        }
        for (String rawPart : spec.split("\\+")) {
            String part = rawPart.trim();
            if (!part.isEmpty()) {
                names.add(canonicalName(part));
            }
        }
        return new KeyChord(names);
    }

    /**
     * Maps one user-written token to its canonical key name.
     *
     * @param token a single part of a chord, e.g. {@code "Control"}, {@code "a"}, {@code "KEYCODE_F13"}
     * @return the canonical name, e.g. {@code "CTRL"}, {@code "A"}, {@code "F13"}
     */
    public static String canonicalName(String token) {
        String trimmed = token.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        switch (trimmed) {
            case "CTRL":
            case "CONTROL":
                return "CTRL";
            case "SHIFT":
                return "SHIFT";
            case "ALT":
            case "OPTION":
                return "ALT";
            case "META":
            case "WIN":
            case "SUPER":
            case "CMD":
            case "COMMAND":
                return "META";
            default:
                break;
        }
        if (trimmed.startsWith("KEYCODE_")) {
            return trimmed.substring("KEYCODE_".length());
        }
        return trimmed;
    }

    /**
     * Canonical name for an Android key code.
     *
     * @param keyCode a {@link KeyEvent} key code
     * @return the canonical name, or {@code KEYCODE_<number>} when unknown
     */
    public static String nameOfKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1);
        }
        // KEYCODE_F13..KEYCODE_F24 (183..194). These are contiguous and follow on directly
        // from KEYCODE_F12, which is where programmable keypads most often land.
        if (keyCode >= 183 && keyCode <= 194) {
            return "F" + (keyCode - 183 + 13);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return "CTRL";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return "SHIFT";
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return "ALT";
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return "META";
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return "ENTER";
            case KeyEvent.KEYCODE_SPACE:
                return "SPACE";
            case KeyEvent.KEYCODE_TAB:
                return "TAB";
            case KeyEvent.KEYCODE_ESCAPE:
                return "ESCAPE";
            case KeyEvent.KEYCODE_DEL:
                return "BACKSPACE";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "DELETE";
            case KeyEvent.KEYCODE_HOME:
                return "HOME";
            case KeyEvent.KEYCODE_BACK:
                return "BACK";
            case KeyEvent.KEYCODE_VOLUME_UP:
                return "VOLUME_UP";
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return "VOLUME_DOWN";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return "MEDIA_PLAY_PAUSE";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "MEDIA_NEXT";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "MEDIA_PREVIOUS";
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "MEDIA_STOP";
            default:
                return "KEYCODE_" + keyCode;
        }
    }

    /**
     * Builds the canonical set of currently-held keys from held key codes plus the
     * modifier state reported by the event.
     *
     * <p>Both sources are needed: external keyboards frequently report modifiers only via
     * {@code metaState} and never send a separate key event for them.</p>
     *
     * @param heldKeyCodes raw Android key codes currently held down
     * @param metaState    {@link KeyEvent#getMetaState()} of the triggering event
     * @return canonical names of every held key
     */
    public static Set<String> heldKeyNames(Set<Integer> heldKeyCodes, int metaState) {
        Set<String> names = new TreeSet<>();
        for (Integer code : heldKeyCodes) {
            names.add(nameOfKeyCode(code));
        }
        if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
            names.add("CTRL");
        }
        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            names.add("SHIFT");
        }
        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            names.add("ALT");
        }
        if ((metaState & KeyEvent.META_META_ON) != 0) {
            names.add("META");
        }
        return names;
    }

    /**
     * True when the held keys are exactly this chord's keys.
     *
     * @param heldKeyNames canonical names of the currently-held keys
     * @return {@code true} on an exact match
     */
    public boolean matches(Set<String> heldKeyNames) {
        return keys.equals(heldKeyNames);
    }

    /**
     * @return the immutable, sorted canonical key names of this chord
     */
    public Set<String> keys() {
        return keys;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyChord && keys.equals(((KeyChord) other).keys);
    }

    @Override
    public int hashCode() {
        return keys.hashCode();
    }

    @Override
    public String toString() {
        return new LinkedHashSet<>(keys).toString();
    }
}

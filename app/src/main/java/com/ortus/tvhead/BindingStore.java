package com.ortus.tvhead;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Durable, per-file-name record of which key plays which media.
 *
 * <h2>Why this exists</h2>
 * <p>Bindings used to live only in {@code media.json}, which meant anything that rewrote or
 * replaced that file - pushing new media, restructuring the config, updating the app - could
 * lose them. This store keeps them separately, keyed on the <em>file name</em> rather than a
 * list position, so:
 * <ul>
 *   <li>Adding or removing media does not disturb the keys of files that are still present.</li>
 *   <li>Re-installing or updating the app keeps them, because the data is in app storage and
 *       survives an upgrade (only an uninstall clears it).</li>
 *   <li>The same key is never handed to two different files.</li>
 * </ul>
 *
 * <p>{@code media.json} is still honoured: anything bound there but not yet known to this
 * store is imported on first load, so an existing hand-written config keeps working. After
 * that the store is authoritative and is what the bindings panel writes to.</p>
 *
 * <h2>Storage</h2>
 * <p>Two string sets in {@link SharedPreferences}: one mapping file name to key spec, one
 * mapping key spec to file name. Keeping both directions makes the two invariants - one key
 * per file, one file per key - cheap to enforce without scanning. Sets are used rather than
 * JSON so a corrupt entry can never take the whole store down with it.</p>
 */
public final class BindingStore {

    private static final String TAG = "TvHead";

    private static final String PREFS_NAME = "bindings";
    private static final String KEY_FILE_TO_CHORD = "file_to_chord";
    private static final String KEY_CHORD_TO_FILE = "chord_to_file";

    private final SharedPreferences prefs;

    /**
     * @param context application context
     */
    public BindingStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Imports any binding found in a config file that this store does not already know about.
     *
     * <p>Only fills gaps: a binding already in the store always wins, so importing an older
     * {@code media.json} can never undo a change made on the tablet.</p>
     *
     * @param config the config loaded from {@code media.json}
     */
    public void importMissingFrom(Config config) {
        Map<String, KeyChord> known = fileNameToChord();
        int imported = 0;
        for (Map.Entry<KeyChord, String> entry : config.bindings().entrySet()) {
            String fileName = entry.getValue();
            if (fileName == null || fileName.trim().isEmpty()) {
                continue;
            }
            if (known.containsKey(normalise(fileName))) {
                continue;
            }
            if (lookup(entry.getKey().keys()) != null) {
                // That key is already spoken for on the tablet; the store wins.
                continue;
            }
            bind(entry.getKey(), fileName);
            known.put(normalise(fileName), entry.getKey());
            imported++;
        }
        if (imported > 0) {
            Log.i(TAG, "Imported " + imported + " binding(s) from " + config.origin());
        }
    }

    /**
     * Looks up the key bound to a file name.
     *
     * @param fileName the media file name
     * @return the chord bound to it, or {@code null} when unbound
     */
    public KeyChord chordForFile(String fileName) {
        if (fileName == null) {
            return null;
        }
        return fileNameToChord().get(normalise(fileName));
    }

    /**
     * Binds a key to a file, moving both the key and the file off whatever they were on.
     *
     * <p>This is what makes re-binding a clean swap rather than an accumulation.</p>
     *
     * @param chord    the key the user pressed
     * @param fileName the media file name to play
     */
    public void bind(KeyChord chord, String fileName) {
        if (chord == null || chord.keys().isEmpty() || fileName == null || fileName.trim().isEmpty()) {
            return;
        }
        String normalised = normalise(fileName);
        String spec = chord.keys().toString();

        // Free the key from any other file, and the file from any other key.
        unbindFile(normalised);
        unbindChord(spec);

        Map<String, String> fileToChord = readMap(KEY_FILE_TO_CHORD);
        Map<String, String> chordToFile = readMap(KEY_CHORD_TO_FILE);
        fileToChord.put(normalised, spec);
        chordToFile.put(spec, normalised);
        writeMap(KEY_FILE_TO_CHORD, fileToChord);
        writeMap(KEY_CHORD_TO_FILE, chordToFile);

        Log.i(TAG, "Bound " + normalised + " to " + spec + " (" + fileToChord.size() + " total)");
    }

    /**
     * Removes whatever key is bound to a file.
     *
     * @param fileName the media file name
     * @return true when something was removed
     */
    public boolean unbindFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalised = normalise(fileName);
        Map<String, String> fileToChord = readMap(KEY_FILE_TO_CHORD);
        String spec = fileToChord.remove(normalised);
        if (spec == null) {
            return false;
        }
        Map<String, String> chordToFile = readMap(KEY_CHORD_TO_FILE);
        chordToFile.remove(spec);
        writeMap(KEY_FILE_TO_CHORD, fileToChord);
        writeMap(KEY_CHORD_TO_FILE, chordToFile);
        Log.i(TAG, "Unbound " + normalised + " (was " + spec + ")");
        return true;
    }

    /**
     * Removes a key from whatever file holds it.
     *
     * @param chordSpec the canonical chord spec
     */
    private void unbindChord(String chordSpec) {
        Map<String, String> chordToFile = readMap(KEY_CHORD_TO_FILE);
        String fileName = chordToFile.remove(chordSpec);
        if (fileName == null) {
            return;
        }
        Map<String, String> fileToChord = readMap(KEY_FILE_TO_CHORD);
        fileToChord.remove(fileName);
        writeMap(KEY_FILE_TO_CHORD, fileToChord);
        writeMap(KEY_CHORD_TO_FILE, chordToFile);
        Log.i(TAG, "Freed " + chordSpec + " from " + fileName);
    }

    /**
     * Finds the file bound to the keys currently held.
     *
     * @param heldKeyNames canonical names of the held keys
     * @return the media file name to show, or {@code null} when nothing matches
     */
    public String lookup(java.util.Set<String> heldKeyNames) {
        KeyChord held = KeyChord.of(heldKeyNames);
        Map<String, String> chordToFile = readMap(KEY_CHORD_TO_FILE);
        return chordToFile.get(held.keys().toString());
    }

    /**
     * @param fileName the media file name
     * @return true when the file currently has a key
     */
    public boolean hasBinding(String fileName) {
        return chordForFile(fileName) != null;
    }

    /**
     * @return how many files currently have a key
     */
    public int size() {
        return fileNameToChord().size();
    }

    /**
     * Every binding, ordered by file name so the panel list is stable.
     *
     * @return file name to chord, sorted by file name
     */
    public Map<String, KeyChord> all() {
        return new TreeMap<>(fileNameToChord());
    }

    /**
     * Removes bindings whose file no longer exists on the device.
     *
     * <p>Deliberately not called automatically: a file can be missing simply because it was
     * not pushed yet, and silently forgetting a key in that case would be worse than keeping
     * a harmless stale entry.</p>
     *
     * @param presentFileNames the file names currently on the device
     * @return how many stale entries were removed
     */
    public int pruneMissing(java.util.Set<String> presentFileNames) {
        Map<String, String> fileToChord = readMap(KEY_FILE_TO_CHORD);
        Map<String, String> chordToFile = readMap(KEY_CHORD_TO_FILE);
        int removed = 0;
        for (String fileName : new java.util.ArrayList<>(fileToChord.keySet())) {
            if (!presentFileNames.contains(fileName)) {
                String spec = fileToChord.remove(fileName);
                chordToFile.remove(spec);
                removed++;
            }
        }
        if (removed > 0) {
            writeMap(KEY_FILE_TO_CHORD, fileToChord);
            writeMap(KEY_CHORD_TO_FILE, chordToFile);
            Log.i(TAG, "Pruned " + removed + " binding(s) for media that is gone");
        }
        return removed;
    }

    /**
     * Reads the file-to-chord map, ignoring unparseable entries.
     *
     * @return a mutable copy of the stored mapping
     */
    private Map<String, String> readMap(String key) {
        Map<String, String> map = new TreeMap<>();
        for (String entry : prefs.getStringSet(key, java.util.Collections.emptySet())) {
            int separator = entry.indexOf('\u0000');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            map.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return map;
    }

    /**
     * Writes a map back as a set of {@code name\0value} entries.
     *
     * @param key   the preference key
     * @param map   the mapping to store
     */
    private void writeMap(String key, Map<String, String> map) {
        java.util.Set<String> encoded = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            encoded.add(entry.getKey() + '\u0000' + entry.getValue());
        }
        prefs.edit().putStringSet(key, encoded).apply();
    }

    /**
     * @return file name to chord, keyed on the normalised file name
     */
    private Map<String, KeyChord> fileNameToChord() {
        Map<String, KeyChord> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : readMap(KEY_FILE_TO_CHORD).entrySet()) {
            KeyChord chord = KeyChord.of(parseSpec(entry.getValue()));
            if (!chord.keys().isEmpty()) {
                result.put(entry.getKey(), chord);
            }
        }
        return result;
    }

    /**
     * Parses a stored chord spec back into canonical key names.
     *
     * <p>The spec is written with {@code Set.toString()}, i.e. {@code [CTRL, SHIFT, A]}, so
     * that is stripped before parsing.</p>
     *
     * @param spec the stored spec
     * @return the canonical key names
     */
    private static java.util.Set<String> parseSpec(String spec) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        if (spec == null) {
            return names;
        }
        String cleaned = spec.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) {
            return names;
        }
        for (String part : cleaned.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * @param fileName a file name
     * @return the canonical form used as the map key
     */
    private static String normalise(String fileName) {
        return fileName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return a snapshot of the store for logging
     */
    @Override
    public String toString() {
        return "BindingStore(" + new LinkedHashMap<>(all()).size() + " entries)";
    }
}

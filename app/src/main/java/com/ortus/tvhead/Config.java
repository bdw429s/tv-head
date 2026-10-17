package com.ortus.tvhead;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The key-to-media mapping, read from {@code media.json}.
 *
 * <p>The file lives next to the media in {@code /sdcard/Movies/tv-head/} so it can be
 * edited on a PC and re-read by the app without rebuilding. Format:</p>
 *
 * <pre>
 * {
 *   "default": "face.mp4",
 *   "scale": "fit",
 *   "showKeyDebug": true,
 *   "bindings": [
 *     { "keys": "F13",          "media": "laugh.mp4" },
 *     { "keys": "Ctrl+Shift+A", "media": "sad.mp4" },
 *     { "keys": "F16",          "media": "still.jpg" },
 *     { "keys": "F17",          "media": "__rescan__" }
 *   ]
 * }
 * </pre>
 *
 * <p>Fields, all optional:</p>
 * <ul>
 *   <li>{@code default} - the face shown on launch; {@code "*"} or {@code "random"} picks
 *       one at random from the media folder, and when omitted the first file found is used</li>
 *   <li>{@code scale} - {@code "fit"} (letterbox, default), {@code "fill"} (stretch to
 *       fill the screen) or {@code "zoom"} (crop to fill)</li>
 *   <li>{@code showKeyDebug} - when true an overlay prints every key press with its
 *       canonical name, which is how you discover what the programmable keyboard sends</li>
 *   <li>{@code bindings} - the chords; a chord matches only when exactly those keys are
 *       held, so {@code "A"} and {@code "Ctrl+A"} are different bindings</li>
 * </ul>
 *
 * <p>Each binding has a {@code video} value naming a file to show. A video loops; a still
 * image is held on screen. Whichever it is, the display stays there until another chord is
 * pressed - nothing returns to the default face on its own.</p>
 *
 * <p>A {@code video} value may also be an action instead of a file:
 * {@code "__quit__"} exits the app and {@code "__rescan__"} re-reads this config and the
 * media folder - useful as an escape hatch because unmapped keys are ignored and the
 * device's Back button is consumed.</p>
 *
 * <p>A missing {@code media.json} is not an error: the app writes a starter file on first
 * run and falls back to showing whatever media it can find.</p>
 */
public final class Config {

    private static final String TAG = "TvHead";

    /** Action value that closes the app. */
    public static final String ACTION_QUIT = "__quit__";

    /** Action value that re-reads the config and media folders. */
    public static final String ACTION_RESCAN = "__rescan__";

    /** Scale mode that letterboxes to preserve aspect ratio. */
    public static final String SCALE_FIT = "fit";

    /** Scale mode that stretches the media to fill the screen. */
    public static final String SCALE_FILL = "fill";

    /** Scale mode that crops the media to fill the screen. */
    public static final String SCALE_ZOOM = "zoom";

    private final String defaultMedia;
    private final String scaleMode;
    private final boolean showKeyDebug;
    private final LinkedHashMap<KeyChord, String> bindings;
    private final String origin;

    public Config(String defaultMedia, String scaleMode, boolean showKeyDebug,
                  LinkedHashMap<KeyChord, String> bindings, String origin) {
        this.defaultMedia = defaultMedia;
        this.scaleMode = scaleMode;
        this.showKeyDebug = showKeyDebug;
        this.bindings = bindings;
        this.origin = origin;
    }

    /**
     * Replaces the whole binding map, used by the settings panel when a key is re-bound.
     *
     * @param newBindings the bindings to use, in display order
     * @return a new config with those bindings; the other settings are carried over
     */
    public Config withBindings(LinkedHashMap<KeyChord, String> newBindings) {
        return new Config(defaultMedia, scaleMode, showKeyDebug, new LinkedHashMap<>(newBindings), origin);
    }

    /**
     * Finds the chord currently bound to a media file.
     *
     * @param mediaName the file name to look up
     * @return the chord bound to it, or {@code null} when it has no binding
     */
    public KeyChord chordFor(String mediaName) {
        for (Map.Entry<KeyChord, String> entry : bindings.entrySet()) {
            if (entry.getValue().equals(mediaName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Loads the config, seeding a starter file when none exists anywhere.
     *
     * <p>See {@link MediaLibrary#configDirectories} for why the app-specific folder is
     * tried before the media folder.</p>
     *
     * @param context application context
     * @return the loaded config; a defaults-only config when the file is unreadable
     */
    public static Config load(Context context) {
        File file = findConfigFile(context);
        if (file == null) {
            file = new File(MediaLibrary.configDirectory(context), MediaLibrary.CONFIG_FILE_NAME);
            seedStarterConfig(file);
        }
        if (file.exists()) {
            try {
                return parse(readText(file), file.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "Unable to read " + file, e);
            } catch (JSONException e) {
                Log.w(TAG, "Malformed JSON in " + file, e);
            }
        }

        String bundled = readBundledConfig(context);
        if (bundled != null) {
            try {
                return parse(bundled, "assets/media.json");
            } catch (JSONException e) {
                Log.w(TAG, "Malformed bundled media.json", e);
            }
        }
        return new Config(null, SCALE_FIT, true, new LinkedHashMap<>(), "defaults");
    }

    /**
     * Finds the first readable {@code media.json} among the candidate folders.
     *
     * @param context application context
     * @return the config file, or {@code null} when it does not exist in any of them
     */
    private static File findConfigFile(Context context) {
        for (File directory : MediaLibrary.configDirectories(context)) {
            File candidate = new File(directory, MediaLibrary.CONFIG_FILE_NAME);
            if (candidate.isFile() && candidate.canRead()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Parses config JSON.
     *
     * @param json   the file contents (a leading UTF-8 BOM is tolerated)
     * @param origin description of where the JSON came from, used in logs
     * @return the parsed config
     * @throws JSONException when the JSON is malformed
     */
    public static Config parse(String json, String origin) throws JSONException {
        String cleaned = json.replace("\uFEFF", "");
        JSONObject root = new JSONObject(cleaned);

        String defaultMedia = root.isNull("default") ? null : root.optString("default", null);
        if (defaultMedia != null && defaultMedia.trim().isEmpty()) {
            defaultMedia = null;
        }

        String scale = root.optString("scale", SCALE_FIT).trim().toLowerCase(Locale.ROOT);
        if (!SCALE_FILL.equals(scale) && !SCALE_ZOOM.equals(scale)) {
            scale = SCALE_FIT;
        }

        boolean showKeyDebug = root.optBoolean("showKeyDebug", true);

        LinkedHashMap<KeyChord, String> bindings = new LinkedHashMap<>();
        JSONArray array = root.optJSONArray("bindings");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String keys = entry.optString("keys", "");
                // "video" is still accepted for configs written before the rename.
                String media = entry.optString("media", entry.optString("video", ""));
                KeyChord chord = KeyChord.parse(keys);
                if (chord.keys().isEmpty() || media.trim().isEmpty()) {
                    Log.w(TAG, "Skipping binding #" + i + " in " + origin + ": " + entry);
                    continue;
                }
                if (bindings.put(chord, media.trim()) != null) {
                    Log.w(TAG, "Duplicate binding for " + chord + " in " + origin + "; last one wins");
                }
            }
        }

        Config config = new Config(defaultMedia, scale, showKeyDebug, bindings, origin);
        Log.i(TAG, "Loaded config from " + origin + ": " + config);
        return config;
    }

    /**
     * Reads a file as UTF-8 text.
     *
     * <p>Deliberately uses a plain {@link FileInputStream} rather than
     * {@code java.nio.file.Files}. Under Android's scoped storage the FUSE layer can refuse
     * {@code java.nio} path access to shared storage ({@code AccessDeniedException}) even
     * when the file is readable and the permission is granted, so the older API is the
     * reliable one here.</p>
     *
     * @param file the file to read
     * @return the file contents
     * @throws IOException when the file cannot be opened or read
     */
    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes a starter {@code media.json} so there is something to edit.
     *
     * @param file the destination file
     */
    private static void seedStarterConfig(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "Unable to create " + parent);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(STARTER_JSON.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Wrote starter config to " + file);
        } catch (IOException e) {
            Log.w(TAG, "Unable to write starter config to " + file, e);
        }
    }

    /**
     * @param context application context
     * @return the contents of {@code assets/media.json}, or {@code null} when absent
     */
    private static String readBundledConfig(Context context) {
        try (InputStream in = context.getAssets().open("media.json")) {
            byte[] buffer = new byte[in.available()];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @return the media shown on launch, may be {@code null} to mean "whatever is found first"
     */
    public String defaultVideo() {
        return defaultMedia;
    }

    /**
     * @return one of {@link #SCALE_FIT}, {@link #SCALE_FILL} or {@link #SCALE_ZOOM}
     */
    public String scaleMode() {
        return scaleMode;
    }

    /**
     * @return true when unrecognised key presses should be printed on screen
     */
    public boolean showKeyDebug() {
        return showKeyDebug;
    }

    /**
     * @return the configured chords mapped to a media name or action, in file order
     */
    public Map<KeyChord, String> bindings() {
        return bindings;
    }

    /**
     * @return a description of where this config was loaded from
     */
    public String origin() {
        return origin;
    }

    /**
     * Finds the binding matching the keys currently held down.
     *
     * @param heldKeyNames canonical names of the held keys
     * @return the bound media name or action, or {@code null} when nothing matches
     */
    public String lookup(Set<String> heldKeyNames) {
        for (Map.Entry<KeyChord, String> entry : bindings.entrySet()) {
            if (entry.getKey().matches(heldKeyNames)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "default=" + defaultMedia + ", scale=" + scaleMode
                + ", showKeyDebug=" + showKeyDebug + ", bindings=" + bindings.size();
    }

    /** Written to the media folder on first run so there is something to edit. */
    private static final String STARTER_JSON =
            "{\n"
                    + "  \"//\": \"TV Head - drop media files in this folder and map keys below.\",\n"
                    + "  \"//keys\": \"Chords look like F13, a, Ctrl+Shift+A or A+S. Match is exact.\",\n"
                    + "  \"//media\": \"A video loops; a .jpg/.png is held as a still. Display stays until the next key.\",\n"
                    + "  \"//debug\": \"Leave showKeyDebug on, press a key, and read its name off the screen.\",\n"
                    + "  \"default\": \"face.mp4\",\n"
                    + "  \"scale\": \"fit\",\n"
                    + "  \"showKeyDebug\": true,\n"
                    + "  \"bindings\": [\n"
                    + "    { \"keys\": \"F13\", \"media\": \"clip1.mp4\" },\n"
                    + "    { \"keys\": \"F14\", \"media\": \"clip2.mp4\" },\n"
                    + "    { \"keys\": \"F16\", \"media\": \"still1.jpg\" },\n"
                    + "    { \"keys\": \"F17\", \"media\": \"__rescan__\" },\n"
                    + "    { \"keys\": \"Ctrl+Shift+Q\", \"media\": \"__quit__\" }\n"
                    + "  ]\n"
                    + "}\n";
}

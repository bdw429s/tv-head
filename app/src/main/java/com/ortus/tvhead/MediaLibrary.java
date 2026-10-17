package com.ortus.tvhead;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Locates the playable media (videos and still images) on the device.
 *
 * <p>Media is read from external storage so it can be dropped in over USB without
 * rebuilding the APK. Directories are searched in this order, first match wins:</p>
 * <ol>
 *   <li>{@code /sdcard/Movies/tv-head/} - the recommended drop folder, visible in Windows
 *       Explorer when the tablet is plugged in over USB</li>
 *   <li>{@code /sdcard/tv-head/}</li>
 *   <li>the app-specific folder {@code /sdcard/Android/data/com.ortus.tvhead/files/media/}
 *       (no permission needed, but hidden from USB browsing on Android 11+)</li>
 *   <li>the bundled {@code assets/media/} folder</li>
 * </ol>
 *
 * <p>Names are matched leniently so a config entry of {@code "loop"} will find
 * {@code loop.mp4}: exact file name first, then case-insensitive, then file name without
 * its extension.</p>
 */
public final class MediaLibrary {

    private static final String TAG = "TvHead";

    /** Name of the folder the media lives in, under each search root. */
    public static final String FOLDER_NAME = "tv-head";

    /** File name of the mapping config, which sits beside the media. */
    public static final String CONFIG_FILE_NAME = "media.json";

    private static final String[] VIDEO_EXTENSIONS =
            {".mp4", ".m4v", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".ts"};
    private static final String[] IMAGE_EXTENSIONS =
            {".jpg", ".jpeg", ".png", ".webp", ".bmp"};

    private MediaLibrary() {
    }

    /**
     * The user-visible media folder, i.e. {@code /sdcard/Movies/tv-head}. This is where
     * videos and images should be copied to.
     *
     * @return the recommended drop folder (it may not exist yet)
     */
    public static File primaryDirectory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER_NAME);
    }

    /**
     * The folder the mapping config normally lives in.
     *
     * <p>This is the app-specific external folder rather than the media folder, and that is
     * deliberate. Under scoped storage an app can read <em>media</em> files by path from
     * shared storage, but a non-media file such as a {@code .json} is refused with
     * {@code EACCES} even when the media permissions are granted and the file is readable
     * by other means. The app-specific folder is owned by this app, so it needs no
     * permission and is always readable. It is still reachable over USB with
     * {@code adb push}/{@code adb pull}.</p>
     *
     * @param context application context
     * @return the config folder, or the media folder when external storage is unavailable
     */
    public static File configDirectory(Context context) {
        File appSpecific = context.getExternalFilesDir(null);
        return appSpecific != null ? appSpecific : primaryDirectory();
    }

    /**
     * Every location the mapping config is looked for, in priority order.
     *
     * <p>The app-specific folder is first because it always works. The media folder is
     * included as a convenience: it works when the app has been granted all-files access,
     * which lets the config sit beside the videos.</p>
     *
     * @param context application context
     * @return the ordered list of folders to check for {@code media.json}
     */
    public static List<File> configDirectories(Context context) {
        List<File> directories = new ArrayList<>();
        directories.add(configDirectory(context));
        directories.add(primaryDirectory());
        return directories;
    }

    /**
     * Every directory that is searched for media, in priority order.
     *
     * @param context application context, used to locate the app-specific folder
     * @return the ordered list of candidate directories
     */
    public static List<File> searchDirectories(Context context) {
        List<File> directories = new ArrayList<>();
        directories.add(primaryDirectory());
        directories.add(new File(Environment.getExternalStorageDirectory(), FOLDER_NAME));
        File appSpecific = context.getExternalFilesDir("media");
        if (appSpecific != null) {
            directories.add(appSpecific);
        }
        return directories;
    }

    /**
     * Resolves a config entry to something the app can display.
     *
     * @param context application context
     * @param name    the file name from the config, e.g. {@code "loop.mp4"} or {@code "face.jpg"}
     * @return the resolved source, or {@code null} when nothing matches
     */
    public static MediaSource resolve(Context context, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String wanted = name.trim();

        for (File directory : searchDirectories(context)) {
            File match = findInDirectory(directory, wanted);
            if (match != null) {
                return MediaSource.ofFile(match);
            }
        }

        MediaSource asset = findInAssets(context, wanted);
        if (asset != null) {
            return asset;
        }

        Log.w(TAG, "No media found matching '" + wanted + "' in " + searchDirectories(context)
                + " or assets/media");
        return null;
    }

    /**
     * Picks the media to show on launch (the "default face").
     *
     * <p>Supports the special values {@code "*"} and {@code "random"} to pick at random from
     * the primary folder. When nothing is configured, or the configured name cannot be
     * found, the first media file discovered is used so the app always shows something.</p>
     *
     * @param context    application context
     * @param configured the {@code default} value from the config, may be {@code null}
     * @return the resolved default, or {@code null} when there is no media at all
     */
    public static MediaSource resolveDefault(Context context, String configured) {
        if (configured != null) {
            String value = configured.trim();
            if ("*".equals(value) || "random".equalsIgnoreCase(value)) {
                List<File> media = listMedia(primaryDirectory());
                if (!media.isEmpty()) {
                    return MediaSource.ofFile(media.get((int) (Math.random() * media.size())));
                }
            } else if (!value.isEmpty()) {
                MediaSource explicit = resolve(context, value);
                if (explicit != null) {
                    return explicit;
                }
            }
        }
        for (File directory : searchDirectories(context)) {
            List<File> media = listMedia(directory);
            if (!media.isEmpty()) {
                return MediaSource.ofFile(media.get(0));
            }
        }
        return findInAssets(context, "default.mp4");
    }

    /**
     * Lists every displayable file across all search folders, grouped by type then name.
     *
     * <p>Used by the settings panel, which shows all available media rather than only the
     * ones that already have a binding. A file appearing in more than one folder is listed
     * once, under the highest-priority folder it was found in.</p>
     *
     * @param context application context
     * @return the available media as sources, never {@code null}
     */
    public static List<MediaSource> listAllMedia(Context context) {
        List<MediaSource> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File directory : searchDirectories(context)) {
            for (File file : listMedia(directory)) {
                if (seen.add(file.getName().toLowerCase(Locale.ROOT))) {
                    sources.add(MediaSource.ofFile(file));
                }
            }
        }
        sortByTypeThenName(sources);
        return sources;
    }

    /**
     * Sorts media into two groups - clips first, then stills - each alphabetical by name.
     *
     * <p>Grouping rather than a single alphabetical list makes the two kinds easy to scan,
     * which matters once the list runs to a couple of dozen entries.</p>
     *
     * @param sources the list to sort in place
     */
    public static void sortByTypeThenName(List<MediaSource> sources) {
        sources.sort((a, b) -> {
            if (a.isImage() != b.isImage()) {
                return a.isImage() ? 1 : -1;
            }
            String nameA = a.file() != null ? a.file().getName() : a.displayName();
            String nameB = b.file() != null ? b.file().getName() : b.displayName();
            return nameA.compareToIgnoreCase(nameB);
        });
    }

    /**
     * Lists the displayable media in a folder, sorted by name.
     *
     * @param directory the folder to scan; a missing folder yields an empty list
     * @return the supported files found, never {@code null}
     */
    public static List<File> listMedia(File directory) {
        List<File> media = new ArrayList<>();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) {
            return media;
        }
        for (File file : files) {
            if (file.isFile() && isSupportedFileName(file.getName())) {
                media.add(file);
            }
        }
        media.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return media;
    }

    /**
     * Finds a file inside one folder using the lenient matching rules described on the class.
     *
     * @param directory the folder to search
     * @param wanted    the requested file name
     * @return the matching file, or {@code null}
     */
    private static File findInDirectory(File directory, String wanted) {
        List<File> media = listMedia(directory);
        for (File file : media) {
            if (file.getName().equals(wanted)) {
                return file;
            }
        }
        for (File file : media) {
            if (file.getName().equalsIgnoreCase(wanted)) {
                return file;
            }
        }
        String wantedBase = stripExtension(wanted);
        for (File file : media) {
            if (stripExtension(file.getName()).equalsIgnoreCase(wantedBase)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Looks for a bundled asset under {@code assets/media/}, then {@code assets/}.
     *
     * @param context application context
     * @param wanted  the requested file name
     * @return an asset-backed source, or {@code null}
     */
    private static MediaSource findInAssets(Context context, String wanted) {
        for (String folder : new String[]{"media", "videos", ""}) {
            String prefix = folder.isEmpty() ? "" : folder + "/";
            String candidate = prefix + wanted;
            if (assetExists(context, candidate) || assetExists(context, candidate + ".mp4")) {
                return MediaSource.ofAsset(candidate);
            }
        }
        return null;
    }

    /**
     * @param context application context
     * @param path    a path relative to the {@code assets} folder
     * @return {@code true} when that asset exists
     */
    private static boolean assetExists(Context context, String path) {
        try {
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                String dir = path.substring(0, slash);
                String file = path.substring(slash + 1);
                String[] children = context.getAssets().list(dir);
                return children != null && Arrays.asList(children).contains(file);
            }
            String[] found = context.getAssets().list("");
            return found != null && Arrays.asList(found).contains(path);
        } catch (IOException e) {
            Log.w(TAG, "Unable to list assets for '" + path + "'", e);
        }
        return false;
    }

    /**
     * @param fileName a file name
     * @return {@code true} when the extension is a supported video or image
     */
    public static boolean isSupportedFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : VIDEO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param fileName a file name
     * @return {@code true} when the extension is a still image
     */
    public static boolean isImageFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param fileName a file name, possibly with an extension
     * @return the file name without its extension
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Some media the app can display: either a video or a still image. */
    public static final class MediaSource {

        /** What kind of media this is, which decides how it gets shown. */
        public enum Kind {
            /** Decoded by the video player and looped. */
            VIDEO,
            /** Decoded to a bitmap and held on screen. */
            IMAGE
        }

        private final Uri uri;
        private final String displayName;
        private final Kind kind;
        private final File file;
        private final String assetPath;

        private MediaSource(Uri uri, String displayName, Kind kind, File file, String assetPath) {
            this.uri = uri;
            this.displayName = displayName;
            this.kind = kind;
            this.file = file;
            this.assetPath = assetPath;
        }

        /**
         * @param file a media file on disk
         * @return a source backed by that file
         */
        public static MediaSource ofFile(File file) {
            Kind kind = isImageFileName(file.getName()) ? Kind.IMAGE : Kind.VIDEO;
            return new MediaSource(Uri.fromFile(file), file.getAbsolutePath(), kind, file, null);
        }

        /**
         * @param assetPath a path relative to {@code assets}, e.g. {@code "media/loop.mp4"}
         * @return a source backed by that asset
         */
        public static MediaSource ofAsset(String assetPath) {
            Kind kind = isImageFileName(assetPath) ? Kind.IMAGE : Kind.VIDEO;
            return new MediaSource(Uri.parse("asset:///" + assetPath), "assets/" + assetPath, kind, null, assetPath);
        }

        /**
         * @return the URI handed to the video player, not used for images
         */
        public Uri uri() {
            return uri;
        }

        /**
         * @return whether this is a video or a still image
         */
        public Kind kind() {
            return kind;
        }

        /**
         * @return {@code true} when this source is a still image
         */
        public boolean isImage() {
            return kind == Kind.IMAGE;
        }

        /**
         * @return the file on disk, or {@code null} for asset-backed sources
         */
        public File file() {
            return file;
        }

        /**
         * @return the asset path, or {@code null} for file-backed sources
         */
        public String assetPath() {
            return assetPath;
        }

        /**
         * Opens the raw bytes, working for both files and assets.
         *
         * @param context application context
         * @return an open stream the caller must close
         * @throws IOException when the media cannot be opened
         */
        public InputStream openStream(Context context) throws IOException {
            if (assetPath != null) {
                return context.getAssets().open(assetPath);
            }
            if (file != null) {
                return new java.io.FileInputStream(file);
            }
            throw new IOException("No readable location for " + displayName);
        }

        /**
         * @return a human-readable name used in logs and the on-screen status
         */
        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName + " (" + kind + ")";
        }
    }
}

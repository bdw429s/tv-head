package com.ortus.tvhead;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The whole app: a fullscreen, landscape display driven by key chords.
 *
 * <p>On launch the configured default face is shown, looping if it is a video. Pressing a
 * bound key switches to that clip or still image and <em>stays there</em> - nothing returns
 * to the default face until another key is pressed. Videos loop while shown; images are
 * decoded to a bitmap and held on screen.</p>
 *
 * <p>Keys that are not bound are ignored. While {@code showKeyDebug} is on in the config,
 * each unrecognised press is written to logcat as {@code KEY: <name>  (no binding)}, which
 * is how you learn what a programmable keyboard sends. Watch it with
 * {@code adb logcat -s TvHead}.</p>
 *
 * <h2>Where bindings live</h2>
 * <p>Bindings are owned by {@link BindingStore}, keyed on the media <em>file name</em>, and
 * kept in app storage. That means adding or removing media, replacing {@code media.json}, or
 * updating the app all leave existing keys intact. {@code media.json} is only read to seed
 * the store the first time a file is seen. See {@link BindingStore} for the details.</p>
 *
 * <h2>Bindings panel</h2>
 * <p>Because unmapped keys are swallowed and the Back button is consumed, normal Android
 * navigation is unavailable, so the panel is reached by tapping the screen. A tap reveals a
 * gear icon for a few seconds; tapping that opens a list of every media file, grouped clips
 * then stills, each with a thumbnail and its current key. The app is closed with
 * {@code adb shell am force-stop com.ortus.tvhead}.</p>
 */
public final class MainActivity extends Activity {

    private static final String TAG = "TvHead";

    /** How long the gear icon stays visible after a tap. */
    private static final long GEAR_VISIBLE_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Raw Android key codes currently held down. */
    private final Set<Integer> heldKeyCodes = new LinkedHashSet<>();

    private ExoPlayer player;
    private PlayerView playerView;
    private ImageView imageView;
    private ImageView settingsButton;

    private Config config;

    /** Durable per-file-name bindings; the authority on which key plays what. */
    private BindingStore bindingStore;

    /** The face shown on launch; resolved lazily so it is not read before permissions. */
    private MediaLibrary.MediaSource defaultSource;

    /** The bitmap currently shown by {@link #imageView}, recycled when replaced. */
    private Bitmap currentBitmap;

    /** Last chord that fired, so holding keys down only triggers once. */
    private KeyChord lastTriggeredChord;

    /** URI of the video currently prepared in the player, so re-selecting it is a no-op. */
    private android.net.Uri currentVideoUri;

    /** Modifier state of the most recent key-down, supplied by the keyboard. */
    private int lastMetaState;

    private GestureDetector gestures;
    private Thumbnails thumbnails;

    /** Non-null while the bindings panel is open. */
    private SettingsPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        playerView = findViewById(R.id.player_view);
        imageView = findViewById(R.id.image_view);
        settingsButton = findViewById(R.id.settings_button);

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new PlayerListener());
        playerView.setPlayer(player);

        thumbnails = new Thumbnails(this);
        setUpTapToRevealSettings();

        config = Config.load(this);
        bindingStore = new BindingStore(this);
        // Adopt anything hand-written in media.json the store has not seen before. The store
        // wins on conflict, so this can never undo a change made on the tablet.
        bindingStore.importMissingFrom(config);
        Log.i(TAG, "Bindings known: " + bindingStore.size());

        applyScaleMode();
        requestMediaPermission();
        showDefaultFace();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    /**
     * Feeds every touch to the tap detector before the view hierarchy sees it.
     *
     * <p>The media views fill the screen and consume touches, so a listener attached to the
     * root layout would never fire. Dispatching here means a tap anywhere is noticed, while
     * the gear icon still receives its own clicks because it is drawn on top and
     * {@code dispatchTouchEvent} only observes, it does not consume.</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestures != null) {
            gestures.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        releaseBitmap();
        if (thumbnails != null) {
            thumbnails.shutdown();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- displaying media

    /**
     * Shows the configured default face.
     *
     * <p>Resolved once and cached. Falls back to the first media file discovered so the app
     * still shows something before any configuration has been written.</p>
     */
    private void showDefaultFace() {
        if (defaultSource == null) {
            defaultSource = MediaLibrary.resolveDefault(this, config.defaultVideo());
        }
        if (defaultSource == null) {
            Log.w(TAG, "No media found in " + MediaLibrary.primaryDirectory()
                    + "; copy .mp4 / .jpg files there or push them with adb");
            return;
        }
        Log.i(TAG, "Showing default face: " + defaultSource);
        showMedia(defaultSource);
        Log.i(TAG, "Ready: " + countMedia() + " file(s) on device, "
                + bindingStore.size() + " bound");
    }

    /**
     * Puts a source on screen, choosing the video player or the image view as appropriate.
     *
     * @param source the media to display
     */
    private void showMedia(MediaLibrary.MediaSource source) {
        if (source.isImage()) {
            showImage(source);
        } else {
            playVideo(source);
        }
    }

    /**
     * Loops a video full screen.
     *
     * <p>Called both when switching to a clip and when re-preparing the clip already
     * showing. In the second case the media item is left alone: re-setting the same item
     * resets the player to idle, which blanks the surface and shows a flash for no reason.
     * So the current item is only replaced when it is a different file.</p>
     *
     * @param source a video-backed source
     */
    private void playVideo(MediaLibrary.MediaSource source) {
        releaseBitmap();
        imageView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);

        if (source.uri().equals(currentVideoUri)) {
            if (!player.isPlaying()) {
                player.play();
            }
            Log.i(TAG, "Already looping " + source);
            return;
        }

        currentVideoUri = source.uri();
        player.setMediaItem(MediaItem.fromUri(source.uri()));
        player.prepare();
        player.play();
        Log.i(TAG, "Looping video " + source);
    }

    /**
     * Decodes a still image and shows it full screen.
     *
     * <p>The bitmap is sub-sampled to roughly screen size so a large photo cannot exhaust
     * memory. The image view is only swapped in once the bitmap is ready: hiding the player
     * first would expose the bare window background for the duration of the decode, which is
     * the visible flash this avoids.</p>
     *
     * @param source an image-backed source
     */
    private void showImage(MediaLibrary.MediaSource source) {
        Bitmap bitmap = decodeScaled(source);
        if (bitmap == null) {
            Log.w(TAG, "Cannot load image: " + source.displayName());
            return;
        }

        player.stop();
        playerView.setVisibility(View.GONE);
        releaseBitmap();
        currentBitmap = bitmap;
        imageView.setImageBitmap(bitmap);
        imageView.setVisibility(View.VISIBLE);
        Log.i(TAG, "Showing image " + source + " at " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    /**
     * Decodes a source to a bitmap no larger than the screen.
     *
     * @param source the image to decode
     * @return the decoded bitmap, or {@code null} when it cannot be read or decoded
     */
    private Bitmap decodeScaled(MediaLibrary.MediaSource source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = source.openStream(this)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read image " + source, e);
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Not a decodable image: " + source);
            return null;
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= metrics.widthPixels
                && bounds.outHeight / (sampleSize * 2) >= metrics.heightPixels) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        try (InputStream in = source.openStream(this)) {
            return BitmapFactory.decodeStream(in, null, options);
        } catch (IOException e) {
            Log.w(TAG, "Unable to decode image " + source, e);
            return null;
        }
    }

    /** Frees the currently displayed bitmap, if any. */
    private void releaseBitmap() {
        if (currentBitmap != null && imageView != null) {
            imageView.setImageBitmap(null);
        }
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    /**
     * Displays the media named in a binding, reporting on screen when it is missing.
     *
     * @param mediaName the file name from the config
     */
    private void showBoundMedia(String mediaName) {
        MediaLibrary.MediaSource source = MediaLibrary.resolve(this, mediaName);
        if (source == null) {
            Log.w(TAG, "Missing media: " + mediaName);
            return;
        }
        showMedia(source);
    }

    /** Applies the configured {@code scale} to both the player surface and the image view. */
    private void applyScaleMode() {
        int resizeMode;
        ImageView.ScaleType scaleType;
        switch (config.scaleMode()) {
            case Config.SCALE_FILL:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                scaleType = ImageView.ScaleType.FIT_XY;
                break;
            case Config.SCALE_ZOOM:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                scaleType = ImageView.ScaleType.CENTER_CROP;
                break;
            case Config.SCALE_FIT:
            default:
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                scaleType = ImageView.ScaleType.FIT_CENTER;
                break;
        }
        playerView.setResizeMode(resizeMode);
        imageView.setScaleType(scaleType);
    }

    /**
     * @return how many videos and images the app can see on the device
     */
    private int countMedia() {
        for (File directory : MediaLibrary.searchDirectories(this)) {
            List<File> media = MediaLibrary.listMedia(directory);
            if (!media.isEmpty()) {
                return media.size();
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- tap to reveal settings

    /**
     * Wires up tap detection.
     *
     * <p>Both single and double taps are accepted, because either is a natural thing to try
     * on a touchscreen and neither conflicts with anything else the app does.</p>
     */
    private void setUpTapToRevealSettings() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                revealSettingsButton();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                revealSettingsButton();
                return true;
            }
        });

        // No touch listener on the layout: the media views fill the screen and swallow
        // touches, so taps are observed in dispatchTouchEvent instead.
        settingsButton.setOnClickListener(view -> openSettings());
    }

    /** Shows the gear icon for a few seconds, restarting the timer on each tap. */
    private void revealSettingsButton() {
        if (panel != null) {
            // The panel is already open; taps behind it should not re-trigger anything.
            return;
        }
        settingsButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(this::hideSettingsButton);
        handler.postDelayed(this::hideSettingsButton, GEAR_VISIBLE_MS);
        Log.i(TAG, "Settings gear revealed for " + (GEAR_VISIBLE_MS / 1000) + "s");
    }

    /** Hides the gear icon, unless the panel is open. */
    private void hideSettingsButton() {
        if (panel == null && settingsButton != null) {
            settingsButton.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- settings panel

    /** Opens the bindings panel, building it from the stored bindings and media list. */
    private void openSettings() {
        if (panel != null) {
            return;
        }
        handler.removeCallbacks(this::hideSettingsButton);
        settingsButton.setVisibility(View.GONE);

        FrameLayout container = findViewById(android.R.id.content);
        View root = LayoutInflater.from(this).inflate(R.layout.view_settings, container, false);
        container.addView(root);

        panel = new SettingsPanel(root);
        panel.refresh();
        Log.i(TAG, "Settings opened");
    }

    /** Closes the panel and restores the media display. */
    private void closeSettings() {
        if (panel == null) {
            return;
        }
        FrameLayout container = findViewById(android.R.id.content);
        container.removeView(panel.root);
        panel = null;
        enterImmersiveMode();
        Log.i(TAG, "Settings closed");
    }

    /**
     * The on-screen bindings editor.
     *
     * <p>Edits go straight to {@link BindingStore}, so every change is durable the moment it
     * is made and cannot be lost by a later config rewrite.</p>
     */
    private final class SettingsPanel {

        private final View root;
        private final LinearLayout list;
        private final TextView hint;
        private final ImageView preview;
        private final TextView previewName;
        private final Button rebindButton;
        private final Button resetButton;

        /** The row the user has chosen, or {@code null}. */
        private MediaLibrary.MediaSource selected;

        /** True while waiting for the user to press the key to bind. */
        private boolean capturing;

        SettingsPanel(View root) {
            this.root = root;
            this.list = root.findViewById(R.id.settings_list);
            this.hint = root.findViewById(R.id.settings_hint);
            this.preview = root.findViewById(R.id.settings_preview);
            this.previewName = root.findViewById(R.id.settings_preview_name);
            this.rebindButton = root.findViewById(R.id.settings_rebind);
            this.resetButton = root.findViewById(R.id.settings_reset);

            rebindButton.setOnClickListener(v -> startCapture());
            resetButton.setOnClickListener(v -> clearSelectionBinding());
            root.findViewById(R.id.settings_close).setOnClickListener(v -> closeSettings());
        }

        /** Rebuilds the list from the store and the files on the device. */
        void refresh() {
            list.removeAllViews();

            LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
            List<MediaLibrary.MediaSource> sources = MediaLibrary.listAllMedia(MainActivity.this);

            if (sources.isEmpty()) {
                hint.setText("No media found in " + MediaLibrary.primaryDirectory());
                updateButtons();
                updatePreview();
                return;
            }

            boolean inStills = false;
            int bound = 0;
            boolean videosHeaderAdded = false;
            boolean stillsHeaderAdded = false;

            for (MediaLibrary.MediaSource source : sources) {
                if (source.isImage() && !inStills) {
                    inStills = true;
                    list.addView(makeGroupHeader(inflater, getString(R.string.settings_group_stills)));
                    stillsHeaderAdded = true;
                }
                if (!inStills && !videosHeaderAdded) {
                    list.addView(makeGroupHeader(inflater, getString(R.string.settings_group_videos)));
                    videosHeaderAdded = true;
                }
                if (bindingStore.hasBinding(fileName(source))) {
                    bound++;
                }
                View row = inflater.inflate(R.layout.item_media, list, false);
                bindRow(row, source);
                list.addView(row);
            }

            hint.setText(sources.size() + " file(s), " + bound + " bound. "
                    + "Select one, then press Re-bind key.");
            updateButtons();
            updatePreview();
        }

        /**
         * Builds a group heading such as {@code VIDEOS}.
         *
         * @param inflater the layout inflater
         * @param label    the heading text
         * @return the header view
         */
        private View makeGroupHeader(LayoutInflater inflater, String label) {
            TextView header = (TextView) inflater.inflate(R.layout.item_group_header, list, false);
            header.setText(label);
            return header;
        }

        /**
         * Fills one row and wires its tap handler.
         *
         * @param row    the inflated row view
         * @param source the media this row represents
         */
        private void bindRow(View row, MediaLibrary.MediaSource source) {
            TextView name = row.findViewById(R.id.row_name);
            TextView key = row.findViewById(R.id.row_key);
            ImageView thumb = row.findViewById(R.id.row_thumb);
            ImageView play = row.findViewById(R.id.row_play);

            name.setText(fileName(source));
            key.setText(describeBinding(source));
            // A play badge marks a clip; a still has no badge, which reads at a glance.
            play.setVisibility(source.isImage() ? View.GONE : View.VISIBLE);
            thumbnails.loadInto(source, thumb);

            // Tagged so selection can be marked without re-deriving the name from a view.
            row.setTag(source.displayName());
            row.setOnClickListener(v -> select(source));
        }

        /**
         * @param source the media to describe
         * @return the chord bound to it, or a dash when unbound
         */
        private String describeBinding(MediaLibrary.MediaSource source) {
            KeyChord chord = chordForSource(source);
            return chord == null ? "\u2014  (not bound)" : chord.toString().replaceAll("[\\[\\]]", "");
        }

        /**
         * @param source the media to look up
         * @return the chord bound to it, or {@code null} when unbound
         */
        private KeyChord chordForSource(MediaLibrary.MediaSource source) {
            return bindingStore.chordForFile(fileName(source));
        }

        /**
         * @param source a media source
         * @return its file name, which is the key bindings are stored under
         */
        private String fileName(MediaLibrary.MediaSource source) {
            return source.file() != null ? source.file().getName() : source.displayName();
        }

        /**
         * Marks a row as selected.
         *
         * @param source the media the user tapped
         */
        private void select(MediaLibrary.MediaSource source) {
            if (capturing) {
                return;
            }
            selected = source;
            Log.i(TAG, "Selected " + source.displayName());
            for (int i = 0; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                boolean isSelected = source.displayName().equals(row.getTag());
                row.setActivated(isSelected);
                row.setBackgroundColor(isSelected ? 0x332A6FD6 : 0x00000000);
            }
            updateButtons();
            updatePreview();
        }

        /** Begins capturing the next key press as the binding for the selected row. */
        private void startCapture() {
            if (selected == null) {
                return;
            }
            capturing = true;
            hint.setText("Press the key (or combo) to bind to " + fileName(selected));
            updateButtons();
            Log.i(TAG, "Capturing next key for " + selected.displayName());
        }

        /**
         * Applies a captured chord to the selected media.
         *
         * <p>{@link BindingStore#bind} moves both the key and the file off whatever they were
         * previously on, so a file never ends up with two keys and a key never fires two
         * files.</p>
         *
         * @param chord the chord the user pressed
         */
        private void applyCapture(KeyChord chord) {
            capturing = false;
            bindingStore.bind(chord, fileName(selected));
            refresh();
        }

        /** Removes the binding from the selected media. */
        private void clearSelectionBinding() {
            if (selected == null) {
                return;
            }
            if (bindingStore.unbindFile(fileName(selected))) {
                refresh();
            }
        }

        /** Enables or disables the action buttons for the current selection. */
        private void updateButtons() {
            boolean hasSelection = selected != null && !capturing;
            rebindButton.setEnabled(hasSelection);
            resetButton.setEnabled(hasSelection && chordForSource(selected) != null);
            rebindButton.setText(capturing ? "Press a key\u2026" : getString(R.string.settings_rebind));
        }

        /** Updates the large preview for the current selection. */
        private void updatePreview() {
            if (selected == null) {
                preview.setImageBitmap(null);
                previewName.setText("");
                return;
            }
            thumbnails.loadInto(selected, preview);
            previewName.setText(fileName(selected));
        }

        /**
         * @return true when the panel is waiting for a key to be pressed
         */
        boolean isCapturing() {
            return capturing;
        }

        /**
         * Offers a key press to the panel.
         *
         * @param heldKeys canonical names of the keys currently held
         * @return true when the panel consumed the press
         */
        boolean offerKey(Set<String> heldKeys) {
            if (!capturing || heldKeys.isEmpty()) {
                return false;
            }
            applyCapture(KeyChord.of(heldKeys));
            return true;
        }
    }

    // ---------------------------------------------------------------- key handling

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) {
            // Auto-repeat while held: the chord has already been dispatched.
            return true;
        }
        lastMetaState = event.getMetaState();
        heldKeyCodes.add(keyCode);
        Log.v(TAG, "keyDown " + KeyChord.nameOfKeyCode(keyCode) + " (keyCode=" + keyCode + ")");

        Set<String> heldKeys = KeyChord.heldKeyNames(heldKeyCodes, lastMetaState);
        if (panel != null) {
            // Set aside hardware volume buttons so the panel can still be navigated.
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.onKeyDown(keyCode, event);
            }
            // Escape closes the panel without rebinding, so capture is never a dead end.
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeSettings();
                return true;
            }
            if (panel.isCapturing()) {
                panel.offerKey(heldKeys);
            }
            // Either way the press is consumed here, so the media behind the panel does not
            // change while it is open.
            return true;
        }

        dispatchChord(heldKeys);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        heldKeyCodes.remove(keyCode);
        if (heldKeyCodes.isEmpty()) {
            lastTriggeredChord = null;
        }
        if (panel != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return super.onKeyUp(keyCode, event);
        }
        return true;
    }

    /**
     * Matches the currently held keys against the stored bindings and acts on a hit.
     *
     * <p>A chord fires only once per press: {@link #lastTriggeredChord} suppresses repeats
     * until all keys are released, so adding a modifier after a letter does not fire twice.</p>
     *
     * @param heldKeys canonical names of the keys currently held
     */
    private void dispatchChord(Set<String> heldKeys) {
        String mediaName = bindingStore.lookup(heldKeys);
        if (mediaName == null) {
            if (config.showKeyDebug()) {
                Log.i(TAG, "KEY: " + String.join(" + ", heldKeys) + "  (no binding)");
            }
            return;
        }

        KeyChord chord = KeyChord.of(heldKeys);
        if (chord.equals(lastTriggeredChord)) {
            return;
        }
        lastTriggeredChord = chord;

        showBoundMedia(mediaName);
    }

    // ---------------------------------------------------------------- permissions

    /**
     * Asks for read access to media in shared storage.
     *
     * <p>Not strictly required: the app-specific folder works without any permission, and
     * display starts straight away either way. Access is only needed to read the convenient
     * {@code /sdcard/Movies/tv-head} folder, so a denial degrades rather than breaks.</p>
     *
     * <p>On Android 13+ video and image access are separate permissions and both are needed,
     * because a binding may point at either a clip or a still.</p>
     */
    private void requestMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            List<String> missing = new ArrayList<>();
            for (String permission : new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES}) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            if (!missing.isEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), 1001);
            }
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1001) {
            return;
        }
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            allGranted = allGranted && result == PackageManager.PERMISSION_GRANTED;
        }
        Log.i(TAG, "Media read permissions granted=" + allGranted);
        // Re-resolve so a late grant picks up the shared folder.
        defaultSource = null;
        showDefaultFace();
    }

    // ---------------------------------------------------------------- chrome

    /** Hides the status and navigation bars so nothing draws over the media. */
    private void enterImmersiveMode() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** Reports playback failures; nothing else changes what is on screen. */
    private final class PlayerListener implements Player.Listener {

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException error) {
            Log.e(TAG, "Playback error", error);
        }
    }
}

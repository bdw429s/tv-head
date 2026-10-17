package com.ortus.tvhead;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Supplies small preview bitmaps for the settings list, for both clips and stills.
 *
 * <p>Decoding is slow and must not run on the main thread, so requests are queued on a
 * single background executor and posted back to the UI thread when ready. Results are held
 * in a small memory cache keyed by source path, so re-opening the settings panel or
 * scrolling does not re-decode.</p>
 *
 * <p>For videos the frame is taken from
 * {@link MediaMetadataRetriever#getFrameAtTime} - cheap, and for these short loops the first
 * frame is representative. Only one retriever is kept alive at a time because it holds a
 * codec: creating many without releasing them can exhaust the device's decoder slots, which
 * would break the main player.</p>
 */
public final class Thumbnails {

    /** Longest edge of a generated thumbnail, in pixels. */
    private static final int MAX_EDGE = 320;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);

    /** The retriever currently open; released before another is created. */
    private MediaMetadataRetriever openRetriever;

    /**
     * @param context application context
     */
    public Thumbnails(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Loads a thumbnail for a source and shows it once ready.
     *
     * <p>The target is tagged with the source path so a row recycled by the list cannot end
     * up displaying a thumbnail meant for a different source.</p>
     *
     * @param source the media to preview
     * @param target the image view to fill
     */
    public void loadInto(final MediaLibrary.MediaSource source, final ImageView target) {
        final String key = source.displayName();
        target.setTag(key);
        target.setImageBitmap(null);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = decode(source);
            if (bitmap == null) {
                return;
            }
            cache.put(key, bitmap);
            mainHandler.post(() -> {
                if (key.equals(target.getTag())) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    /**
     * Decodes a thumbnail for a source.
     *
     * @param source the media to preview
     * @return the thumbnail, or {@code null} when it cannot be produced
     */
    private Bitmap decode(MediaLibrary.MediaSource source) {
        try {
            if (source.isImage()) {
                return decodeImage(source);
            }
            return grabVideoFrame(source);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes a still, sub-sampled so the thumbnail stays small.
     *
     * @param source an image-backed source
     * @return the scaled bitmap, or {@code null} when it cannot be read
     */
    private Bitmap decodeImage(MediaLibrary.MediaSource source) throws java.io.IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream in = source.openStream(context)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try (java.io.InputStream in = source.openStream(context)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    /**
     * Grabs a frame from a video.
     *
     * @param source a video-backed source
     * @return the frame, or {@code null} when it cannot be read
     */
    private Bitmap grabVideoFrame(MediaLibrary.MediaSource source) {
        // Only one retriever may be open at a time: each holds a decoder slot.
        closeRetriever();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, source.uri());
            // OPTION_CLOSEST_SYNC seeks to the nearest keyframe, which is fast and, for a
            // short loop, still an accurate picture of the clip.
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing useful to do; the retriever is being discarded anyway.
            }
        }
    }

    /** Releases the retained retriever, if any. */
    private void closeRetriever() {
        if (openRetriever != null) {
            try {
                openRetriever.release();
            } catch (Exception ignored) {
                // Already released or unusable.
            }
            openRetriever = null;
        }
    }

    /** Releases every resource held by this helper. Call from the activity's onDestroy. */
    public void shutdown() {
        executor.shutdownNow();
        closeRetriever();
        cache.evictAll();
    }
}

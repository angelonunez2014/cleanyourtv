package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rotating photos and videos behind the launcher (config.xml slideshow_dir, slideshow_seconds, slideshow_dim):
 * every picture and video in a folder on shared storage, shuffled, crossfading one into the next. A photo stays
 * slideshow_seconds; a video loops, muted, for as long as Home stays in front, and each return to Home moves on
 * to the next item in the shuffled order (every file once before any repeats). The folder is listed again before each item, so files added or removed show up without a rebuild.
 * Photos are decoded off the main thread at screen size, so at most two screen-sized bitmaps are alive. Videos
 * play only while Home is in front: stop() releases the decoder before any other app runs.
 */
final class Slideshow {
    private static final long FADE_MS = 1500;
    /** Pause before trying again after a video fails, so a folder of unplayable files cannot spin. */
    private static final long RETRY_MS = 1000;

    private final File dir;
    private final long intervalMs;
    private final long fadeMs;
    private final int screenW, screenH;
    private final Handler handler;
    /** Called on the main thread when the folder turns out empty, after everything is cleared. */
    private final Runnable onEmpty;
    /** Two stacked views: the next photo goes into the hidden one, then the top view fades in or out. */
    private final ImageView bottom, top;
    /** Above both photo views; fades in over a photo and out to reveal one. */
    private final TextureView video;
    /** Darkens whatever shows so the clock and tiles stay readable. */
    private final View scrim;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Deque<File> queue = new java.util.concurrent.ConcurrentLinkedDeque<>();   // decoder thread and clear()
    /** One instance, so stop() can remove the pending tick. */
    private final Runnable tick = this::next;
    /** Last file handed out; decoder thread only. Keeps a reshuffle from repeating it straight away. */
    private File last;
    private Surface surface;
    private MediaPlayer player;
    private boolean topShowing;
    private boolean running;
    /** Bumped by stop(), so a decode or video callback that arrives after it is dropped. */
    private int generation;

    Slideshow(FrameLayout root, File dir, int seconds, int dimPercent, boolean animate, Handler handler,
              Runnable onEmpty) {
        this.onEmpty = onEmpty;
        this.dir = dir;
        this.intervalMs = seconds * 1000L;
        this.fadeMs = animate ? FADE_MS : 0;
        this.handler = handler;
        android.util.DisplayMetrics dm = root.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        bottom = photoView(root);
        top = photoView(root);
        top.setAlpha(0f);
        video = new TextureView(root.getContext());
        video.setAlpha(0f);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = new Surface(st); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        scrim = new View(root.getContext());
        scrim.setBackgroundColor(Color.argb(dimPercent * 255 / 100, 0, 0, 0));
        scrim.setVisibility(View.GONE);
        // Indexes 0 to 3: behind the sky, the glow and everything else.
        root.addView(bottom, 0, matchParent());
        root.addView(top, 1, matchParent());
        root.addView(video, 2, matchParent());
        root.addView(scrim, 3, matchParent());
    }

    /** Photos and videos in {@code dir} the slideshow can show; empty when the folder is missing or unreadable. */
    static List<File> media(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || !f.isFile() || f.length() == 0) continue;
            if (isVideo(f) || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".webp")) out.add(f);
        }
        return out;
    }

    static boolean isVideo(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv");
    }

    /** True when the folder has something to show; the caller shows the sky backdrop otherwise. */
    boolean hasMedia() {
        return !media(dir).isEmpty();
    }

    /** Shows the next item now, then keeps going until stop(). */
    void start() {
        if (running) return;
        running = true;
        next();
    }

    /** Keeps a photo on screen, so Home comes back to it at once; a video's decoder is released. */
    void stop() {
        running = false;
        generation++;
        handler.removeCallbacks(tick);
        releasePlayer();
    }

    /** Takes everything off screen, so the sky or wallpaper underneath shows again. */
    void clear() {
        releasePlayer();
        video.animate().cancel();
        video.setAlpha(0f);
        top.animate().cancel();
        top.setAlpha(0f);
        top.setImageDrawable(null);
        bottom.setImageDrawable(null);
        scrim.setVisibility(View.GONE);
        topShowing = false;
        queue.clear();
    }

    /** For onDestroy: also ends the decoder thread, which would otherwise outlive a recreate(). */
    void close() {
        stop();
        decoder.shutdownNow();
    }

    private void next() {
        if (!running) return;
        final int gen = generation;
        decoder.execute(() -> {
            File chosen = null;
            Bitmap bmp = null;
            // A photo that fails to decode is skipped; give up after one pass so a folder of bad files can't spin.
            for (int tries = 0; chosen == null && tries < 50; tries++) {
                File f = nextFile();
                if (f == null) break;
                if (isVideo(f)) chosen = f;
                else if ((bmp = decode(f, screenW, screenH)) != null) chosen = f;
            }
            final File item = chosen;
            final Bitmap photo = bmp;
            handler.post(() -> {
                if (gen != generation) return;
                if (item == null) {
                    if (!media(dir).isEmpty()) { handler.postDelayed(tick, intervalMs); return; }
                    // Every file was removed: stop showing the last one and hand back to the sky.
                    running = false;
                    clear();
                    onEmpty.run();
                } else if (photo != null) {
                    showPhoto(photo);
                    handler.postDelayed(tick, intervalMs);
                } else {
                    playVideo(item, gen);
                }
            });
        });
    }

    /** Runs on the decoder thread. Refills the shuffled queue from the folder when it runs out. */
    private File nextFile() {
        if (queue.isEmpty()) {
            List<File> files = media(dir);
            Collections.shuffle(files);
            if (files.size() > 1 && files.get(0).equals(last)) Collections.swap(files, 0, files.size() - 1);
            queue.addAll(files);
        }
        last = queue.poll();
        return last;
    }

    private void showPhoto(Bitmap bmp) {
        scrim.setVisibility(View.VISIBLE);
        top.animate().cancel();
        if (video.getAlpha() > 0f) {
            // Coming from a video: the photo goes underneath, then the video fades away to reveal it.
            (topShowing ? top : bottom).setImageBitmap(bmp);
            video.animate().alpha(0f).setDuration(fadeMs).withEndAction(this::releasePlayer).start();
            return;
        }
        if (topShowing) {
            // Next photo underneath, then the top one fades away to reveal it.
            bottom.setImageBitmap(bmp);
            top.animate().alpha(0f).setDuration(fadeMs).withEndAction(() -> top.setImageDrawable(null)).start();
        } else {
            top.setImageBitmap(bmp);
            top.animate().alpha(1f).setDuration(fadeMs).withEndAction(() -> bottom.setImageDrawable(null)).start();
        }
        topShowing = !topShowing;
    }

    private void playVideo(File file, int gen) {
        releasePlayer();
        if (surface == null) {   // the view is not laid out yet; try again shortly
            handler.postDelayed(tick, RETRY_MS);
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);   // stays on this one until Home is left; start() picks the next
            mp.setOnVideoSizeChangedListener((p, w, h) -> centerCrop(w, h));
            mp.setOnPreparedListener(p -> {
                if (gen != generation || p != player) return;
                p.start();
                scrim.setVisibility(View.VISIBLE);
                video.animate().cancel();
                video.animate().alpha(1f).setDuration(fadeMs).start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                // Unsupported codec or a broken file: skip it.
                if (p == player) releasePlayer();
                if (gen == generation) handler.postDelayed(tick, RETRY_MS);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            handler.postDelayed(tick, RETRY_MS);
        }
    }

    /** Scales the video to cover the screen like CENTER_CROP, cutting off what overflows. */
    private void centerCrop(int videoW, int videoH) {
        float viewW = video.getWidth(), viewH = video.getHeight();
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return;
        float scale = Math.max(viewW / videoW, viewH / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / viewW, videoH * scale / viewH, viewW / 2f, viewH / 2f);
        video.setTransform(m);
    }

    private void releasePlayer() {
        if (player == null) return;
        MediaPlayer p = player;
        player = null;
        try {
            p.release();
        } catch (Exception ignored) {
            // Already released or in an error state; nothing left to free.
        }
    }

    /**
     * Decodes {@code file} at the size CENTER_CROP shows on a {@code screenW} x {@code screenH} screen; null for
     * an unreadable picture or one too big to decode safely (#166). Shared with the theme wallpaper.
     */
    static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = WallpaperSize.sampleSize(w, h, screenW, screenH);
            // The limit applies to what is decoded, so a 12 MP photo still works once it is sampled down.
            if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(w / sample, h / sample, screenW, screenH);
            if (scale != null) {
                opts.inScaled = true;
                opts.inDensity = scale[0];
                opts.inTargetDensity = scale[1];
            }
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down.
            return null;
        }
    }

    private static ImageView photoView(FrameLayout root) {
        ImageView v = new ImageView(root.getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}

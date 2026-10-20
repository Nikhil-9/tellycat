package com.nikhil.glowhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal TV home screen: looping video background, one row of app banners, focused-app title, clock.
 * The video only exists while this screen is visible (released in onStop), so it costs nothing
 * while another app is in front.
 */
public class HomeActivity extends Activity implements TextureView.SurfaceTextureListener {

    /** Shown first, in this order (the apps used most); everything else follows alphabetically. */
    private static final List<String> PREFERRED = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "org.jellyfin.androidtv");

    /** Never shown as tiles (add packages here to hide them from the row). */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.nikhil.glowhome",
            "com.nikhil.glowcat"));

    /** TellyCat stands on the selected tile; it is told where that tile ends up and when an app launches. */
    private static final String CAT_PKG = "com.nikhil.glowcat";
    private static final String CAT_EVENT = "com.nikhil.glowcat.HOME_EVENT";

    /** Light theme: the background video is white at the top with a warm glow along the bottom. */
    private static final int PAPER = 0xFFFDFBF8;      // shown until the video's first frame
    private static final int INK = 0xFF2B1D17;        // clock, title
    private static final int INK_SOFT = 0xCC2B1D17;
    private static final int TEXT_HALO = 0x80FFFFFF;  // soft light halo keeps text readable over the glow

    private TextureView videoView;
    private Surface videoSurface;
    private MediaPlayer player;
    private boolean started;

    private HorizontalScrollView scroller;
    private LinearLayout row;
    private TextView title;
    private TextView clock;
    private boolean appsDirty = true;
    private boolean resetting;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (started) loadApps();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(PAPER);

        FrameLayout root = new FrameLayout(this);

        videoView = new TextureView(this);
        videoView.setSurfaceTextureListener(this);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        // faint light wash behind the row so the title stays readable over the brightest part of the glow
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x26FFFFFF, 0x00FFFFFF}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM));

        clock = text(28, INK_SOFT);
        // top-left: the TV's own mute/volume indicator lives in the top-right corner
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clp.setMargins(dp(52), dp(24), 0, 0);
        root.addView(clock, clp);

        title = text(22, INK);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tlp.setMargins(dp(52), 0, 0, dp(150));
        root.addView(title, tlp);

        scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(dp(44), dp(12), dp(44), dp(12));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // trailing space so even the last tile can scroll to the centre
        row.setPadding(0, 0, dp(400), 0);
        scroller.addView(row, new FrameLayout.LayoutParams(-2, -2));
        root.addView(scroller, new FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM));
        ((FrameLayout.LayoutParams) scroller.getLayoutParams()).bottomMargin = dp(28);

        setContentView(root);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        updateClock();
        if (appsDirty) loadApps();
        startVideo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        row.post(() -> {
            View f = row.getFocusedChild();
            if (f != null) tellCat("shown", f, scroller.getScrollX());
        });
    }

    @Override
    protected void onStop() {
        started = false;
        unregisterReceiver(tick);
        releaseVideo();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    /** HOME pressed while already home: jump back to the first tile. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (row.getChildCount() == 0) return;
        View first = row.getChildAt(0);
        if (first.hasFocus() || scroller.getScrollX() == 0) {
            first.requestFocus();
            return;
        }
        resetting = true;            // the focus change below must not send its own "focus" event
        first.requestFocus();
        resetting = false;
        tellCat("reset", first, 0);
        scroller.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // home screen: nowhere to go back to
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) releaseVideo();
    }

    // ------------------------------------------------------------------ video background

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        videoSurface = new Surface(st);
        startVideo();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releaseVideo();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void startVideo() {
        if (!started || videoSurface == null || player != null) return;
        int res = getResources().getIdentifier("glow", "raw", getPackageName());
        if (res == 0) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_UNKNOWN)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDataSource(this, Uri.parse("android.resource://" + getPackageName() + "/" + res));
            mp.setSurface(videoSurface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((p, what, extra) -> {
                releaseVideo();
                return true;
            });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            releaseVideo();
        }
    }

    private void releaseVideo() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ------------------------------------------------------------------ apps row

    private void loadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);

        List<ResolveInfo> first = new ArrayList<>();
        List<ResolveInfo> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            if (PREFERRED.contains(pkg)) first.add(ri);
            else rest.add(ri);
        }
        Collections.sort(first, (a, b) -> PREFERRED.indexOf(a.activityInfo.packageName)
                - PREFERRED.indexOf(b.activityInfo.packageName));
        Collections.sort(rest, (a, b) -> String.valueOf(a.loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));

        View focused = row.getFocusedChild();
        String keepFocus = focused != null ? (String) focused.getTag() : null;
        row.removeAllViews();
        for (ResolveInfo ri : first) row.addView(tileFor(ri, pm));
        for (ResolveInfo ri : rest) row.addView(tileFor(ri, pm));

        View target = row.getChildCount() > 0 ? row.getChildAt(0) : null;
        if (keepFocus != null) {
            for (int i = 0; i < row.getChildCount(); i++) {
                if (keepFocus.equals(row.getChildAt(i).getTag())) target = row.getChildAt(i);
            }
        }
        if (target != null) target.requestFocus();
    }

    private View tileFor(ResolveInfo ri, PackageManager pm) {
        final String pkg = ri.activityInfo.packageName;
        final String cls = ri.activityInfo.name;
        final String label = String.valueOf(ri.loadLabel(pm));
        Drawable banner = ri.activityInfo.loadBanner(pm);
        View content;
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            content = iv;
        } else {
            content = iconAndLabel(ri.loadIcon(pm), label);
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName(pkg, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return tile(content, label, pkg, launch);
    }

    private View iconAndLabel(Drawable icon, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        box.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = text(13, INK);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setPadding(dp(6), dp(4), dp(6), 0);
        box.addView(tv, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }

    private View tile(View content, final String label, String tag, final Intent launch) {
        final FrameLayout t = new FrameLayout(this);
        t.setTag(tag);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59FFFFFF);
        bg.setCornerRadius(dp(8));
        t.setBackground(bg);
        t.setClipToOutline(true);
        t.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(90));
        lp.setMargins(dp(8), 0, dp(8), 0);
        t.setLayoutParams(lp);

        // the selected tile is shown by its zoom and lifted shadow only (no outline)
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(8) : 0);
            if (has) {
                title.setText(label);
                if (resetting) return;
                // keep the focused tile centred so its zoom is never cut off at the screen edge
                int target = finalScrollFor(v);
                tellCat("focus", v, target);
                scroller.smoothScrollTo(target, 0);
            }
        });
        t.setOnClickListener(v -> {
            tellCat("launch", v, scroller.getScrollX());
            try {
                startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException e) {
                title.setText(label + " can't be opened");
            }
        });
        return t;
    }

    // ------------------------------------------------------------------ Glow Cat

    /** Where the row will rest once the focused tile is centred (clamped like HorizontalScrollView does). */
    private int finalScrollFor(View v) {
        int inner = scroller.getWidth() - scroller.getPaddingLeft() - scroller.getPaddingRight();
        int max = Math.max(0, row.getWidth() - inner);
        int target = v.getLeft() + v.getWidth() / 2 - scroller.getWidth() / 2;
        return Math.max(0, Math.min(max, target));
    }

    /**
     * Tells Glow Cat about the tile it should stand on: its final centre x and (zoomed) top y on screen,
     * and how far the row is about to slide (+ = row moves left). Only Glow Cat can receive it.
     */
    private void tellCat(String type, View tile, int finalScroll) {
        if (!tile.isLaidOut()) return;
        int[] at = new int[2];
        scroller.getLocationOnScreen(at);
        int cx = at[0] + scroller.getPaddingLeft() + tile.getLeft() + tile.getWidth() / 2 - finalScroll;
        int top = at[1] + scroller.getPaddingTop() + tile.getTop() - Math.round(tile.getHeight() * 0.06f);
        sendBroadcast(new Intent(CAT_EVENT).setPackage(CAT_PKG)
                .putExtra("type", type)
                .putExtra("cx", cx)
                .putExtra("top", top)
                .putExtra("slide", finalScroll - scroller.getScrollX()));
    }

    // ------------------------------------------------------------------ helpers

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // keep focus inside the row: UP/DOWN do nothing on a one-row home screen
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return super.onKeyDown(keyCode, event);
    }

    private void updateClock() {
        String fmt = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(DateFormat.format(fmt, new Date()));
    }

    private TextView text(float sp, int color) {
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setShadowLayer(dp(6), 0, 0, TEXT_HALO);
        return tv;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

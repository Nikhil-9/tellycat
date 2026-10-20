package com.nikhil.glowcat;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * TellyCat ("Soot"): a small, moody cat in a non-focusable overlay window. It lives on the home screen:
 * with Glow Home it stands on the selected tile (Glow Home says where that is), hops between tiles, runs on
 * the sliding row and falls when an app opens; with any other launcher it lives on the bottom edge. Once an
 * app is open it walks off and stays away until home is back. A hidden annoyance meter picks grumpy /
 * irritated / furious versions. Keys always pass through.
 *
 * Frames: 128x128 WebP in assets/cat/clip/NNN.webp (+ clips.txt: name frames loop), drawn at 12/s; feet on
 * y=112, facing right. Clips play in place; this class moves the window (walks, hop arcs, the fall).
 * A missing clip is skipped (whatever follows it plays straight away).
 *
 * Clips (L = loops):
 *   idle_grumpy L, idle_grumpy_2 L  default idle; alternated            idle_irritated L  annoyance 30-70
 *   idle_furious L                 annoyance 70+ (calm_down plays when it drops back)
 *   walk L                         walking in / off screen, following Left/Right on the bottom edge
 *   run L -> skid_stop             the row slides under the cat; fast Left/Right presses
 *   hop_takeoff -> hop_air L -> hop_land     tile to tile, and the leap from the floor up onto a tile
 *   fall_air L -> fall_crash -> dizzy L -> recover_angry     an app opens under the cat
 *   exhausted L -> tantrum         4 s of non-stop moving, then the presses stop
 *   leave_huff -> walk             a video starts playing (when the cat is still on screen)
 *   fall_asleep -> sleep L -> wake_grumpy    3 minutes without the remote / the next press
 *   react_back                     Back pressed          what   Up/Down on the Glow Home row (does nothing)
 *   swept_tumble                   Home pressed while far along the row (it whizzes back to the start)
 *   notice                         another app came to the front
 *   soft_moment                    very rarely, after a long calm spell
 *   react_ok, calm_down            OK inside an app; cooling off from furious
 */
public class CatService extends AccessibilityService {

    // ---- frames ----
    private static final int FPS = 12;
    private static final int CANVAS = 128, FOOT = 112;
    private static final long FADE_MS = 160;                    // crossfade between unrelated clips

    // ---- places ----
    private static final String GLOW_HOME = "com.nikhil.glowhome";   // companion launcher: tells the cat where tiles are
    private static final String HOME_EVENT = "com.nikhil.glowcat.HOME_EVENT";
    private static final String HOME_EVENT_PERM = "com.nikhil.glowcat.permission.HOME_EVENTS";
    private static final int TILE_SPOT_DX = 60;                 // stand on the right half of the tile (clear of the title)
    private static final int APP_GROUND_MARGIN = 3;             // inside apps: feet on the very bottom edge

    // ---- motion (px/s) ----
    private static final float WALK_SPEED = 120f, RUN_SPEED = 520f, GRAVITY = 2600f;
    private static final float FOLLOW_STEP_PX = 180f, FOLLOW_WALK_SPEED = 220f;  // in apps: one Left/Right press
    private static final long FOLLOW_BURST_MS = 500;            // presses closer than this make it run
    private static final float WALK_OFF_SPEED = 240f;           // leaving an app screen: a brisk stomp

    // ---- timing ----
    private static final long VIDEO_LEAVE_MS = 5_000, VIDEO_RETURN_MS = 1_500;
    private static final long SLEEP_AFTER_MS = 3 * 60_000;
    private static final long BUSY_GAP_MS = 900;                // presses closer than this count as one burst
    private static final long EXHAUSTED_AFTER_MS = 4_000;       // a burst this long wears it out
    private static final long TANTRUM_AFTER_QUIET_MS = 1_500;
    private static final long SOFT_CALM_MS = 5 * 60_000, SOFT_EVERY_MS = 8 * 60_000;

    // ---- mood ----
    private static final float IRRITATED = 30f, FURIOUS = 70f;

    private static final Set<String> IGNORED_PKGS = new HashSet<>(Arrays.asList(
            "com.nikhil.glowcat", "android", "com.android.systemui",
            "com.google.android.inputmethod.latin", "com.vt.tv.commonui"));

    /** Clip pairs drawn to follow each other exactly: no crossfade between them. */
    private static final Set<String> SEAMLESS = new HashSet<>(Arrays.asList(
            "hop_takeoff>hop_air", "hop_air>hop_land", "fall_air>fall_crash", "fall_crash>dizzy",
            "dizzy>recover_angry", "leave_huff>walk", "fall_asleep>sleep", "run>skid_stop",
            "exhausted>tantrum", "tantrum>idle_grumpy_2", "idle_furious>calm_down"));

    private enum Mode {
        IDLE, REACT, WALK, RUN, SKID, HOP_UP, HOP_AIR, HOP_LAND, FALL_AIR, FALL_CRASH, DIZZY, RECOVER,
        EXHAUSTED, TANTRUM, SLEEP_IN, SLEEP, WAKE, LEAVE_HUFF, LEAVING, HIDDEN, RETURNING, SWEPT
    }

    private enum Motion { NONE, LINEAR, ARC, FALL }

    private static final class Clip {
        final String name; final int frames; final boolean loop;
        Clip(String n, int f, boolean l) { name = n; frames = f; loop = l; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Map<String, Clip> clips = new HashMap<>();
    private final Map<String, Boolean> activityCache = new HashMap<>();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private CatView view;
    private AudioManager audio;
    private int screenW, screenH;

    // animation
    private Mode mode = Mode.HIDDEN;
    private Clip clip;
    private long clipStart;
    private int shownFrame = -1, loopsSeen, loopsWanted;
    private boolean clipDone;
    private int playSeq;                    // bumps on every play(), so callers can tell a new clip started
    private Runnable then;                  // what follows a one-shot
    private Bitmap cur, prev;
    private long fadeStart = -1;
    private final BitmapFactory.Options opts = new BitmapFactory.Options();
    private boolean ticking;

    // position: x = centre of the cat, y = where its feet are
    private float x, y;
    private int facing = 1;
    private Motion motion = Motion.NONE;
    private float targetX, speed, vy, groundTo;
    private float ax0, ay0, ax1, ay1, acy;  // hop arc (quadratic curve)
    private long arcStart, arcMs;
    private int lastLpX = Integer.MIN_VALUE, lastLpY = Integer.MIN_VALUE;

    // world
    private boolean onHome = true;
    private String currentPkg = GLOW_HOME;
    private String launcherPkg = GLOW_HOME;  // the TV's default home app, whichever it is
    private long launcherCheckedAt = -60_000;
    private int tileCx = -1, tileTop = -1;  // the selected Glow Home tile (final resting place)
    private boolean leftByLeftEdge;

    // mood and timers
    private float annoy;
    private long lastKeyAt, lastMoveAt, busyStart, lastAnnoyAt, lastSoftAt, playingSince, quietSince, runUntil;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        loadClipIndex();

        cur = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888);
        prev = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888);
        opts.inMutable = true;
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        view = new CatView(this);
        lp = new WindowManager.LayoutParams(CANVAS, CANVAS,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle("GlowCat");
        view.setVisibility(View.GONE);

        long now = SystemClock.uptimeMillis();
        lastKeyAt = now;
        lastAnnoyAt = now;
        lastSoftAt = now;
        isHome("");                              // resolve the default launcher now
        currentPkg = launcherPkg;
        x = screenW + CANVAS;
        y = homeGround();
        place();                                 // position is set before the window is added
        wm.addView(view, lp);
        registerReceiver(homeEvents, new IntentFilter(HOME_EVENT), HOME_EVENT_PERM, main);
        main.post(watchdog);
        comeBack();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(homeEvents); } catch (Exception ignored) { }
        if (view != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    // ------------------------------------------------------------------ inputs

    private final BroadcastReceiver homeEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String type = i.getStringExtra("type");
            int cx = i.getIntExtra("cx", -1), top = i.getIntExtra("top", -1), slide = i.getIntExtra("slide", 0);
            debug("home " + type + " cx=" + cx + " top=" + top + " slide=" + slide + " mode=" + mode);
            if (type == null || cx < 0 || top < 0) return;
            tileCx = cx;
            tileTop = top;
            lastKeyAt = SystemClock.uptimeMillis();   // the home screen only moves when someone uses the remote
            if ("focus".equals(type) && (mode == Mode.SLEEP || mode == Mode.SLEEP_IN)) {
                annoy = Math.max(annoy, IRRITATED + 5);
                lastAnnoyAt = lastKeyAt;
                play("wake_grumpy", Mode.WAKE, CatService.this::goIdle);
                return;
            }
            switch (type) {
                case "focus": onTileFocus(slide); break;
                case "launch": onLaunch(); break;
                case "shown": onHomeShown(); break;
                case "reset": onHomeReset(slide); break;
                default: break;
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), c = e.getClassName();
        if (p == null || c == null) return;
        String pkg = p.toString();
        if (IGNORED_PKGS.contains(pkg) || pkg.equals(currentPkg) || !isActivity(pkg, c.toString())) return;
        String from = currentPkg;
        currentPkg = pkg;
        debug("app " + from + " -> " + pkg + " mode=" + mode);
        onAppSwitched(isHome(pkg), isHome(from));
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            final int code = e.getKeyCode();
            main.post(() -> onKey(code));
        }
        return false; // never consume: the TV gets every key
    }

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean known = activityCache.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException ex) {
                known = false;
            }
            activityCache.put(key, known);
        }
        return known;
    }

    // ------------------------------------------------------------------ home screen

    /** The selection moved: hop to the new tile, or run on the sliding row. */
    private void onTileFocus(int slide) {
        long now = SystemClock.uptimeMillis();
        if (!onHome) return;
        if (now - lastMoveAt > BUSY_GAP_MS) busyStart = now;
        lastMoveAt = now;
        annoy(6);
        switch (mode) {
            case HIDDEN: case SLEEP_IN: case SLEEP: case WAKE: case LEAVE_HUFF: case LEAVING: case RETURNING:
            case FALL_AIR: case FALL_CRASH: case DIZZY: case RECOVER: case TANTRUM: case SWEPT:
                return;                                   // busy; it walks to the tile when it is done
            case EXHAUSTED:
                return;                                   // too tired to follow; the tantrum comes when you stop
            case HOP_UP: case HOP_AIR:
                return;                                   // land first; goIdle() then catches up with the new tile
            default:
        }
        if (now - busyStart >= EXHAUSTED_AFTER_MS) {
            exhausted();
            return;
        }
        if (Math.abs(slide) > 8) {
            facing = slide > 0 ? 1 : -1;
            runUntil = now + 650;                         // the row slides for ~250 ms; keep running a bit longer
            if (mode != Mode.RUN) play("run", Mode.RUN, null);
            moveLinear(spotX(), RUN_SPEED);
        } else {
            hopTo(spotX(), homeGround());
        }
    }

    /** OK on a tile: the home screen vanishes from under the cat. */
    private void onLaunch() {
        if (mode == Mode.HIDDEN || mode == Mode.LEAVING || isFalling()) return;
        fall();
    }

    /** Home is visible again (back from an app): leap from the bottom onto the selected tile. */
    private void onHomeShown() {
        onHome = true;
        if (mode == Mode.HIDDEN) {
            comeBack();
            return;
        }
        if (isFalling() || mode == Mode.LEAVE_HUFF || mode == Mode.LEAVING) return;
        if (Math.abs(y - homeGround()) > 4 || Math.abs(x - spotX()) > 6) settle();
    }

    /** Home pressed while home: the row whizzes back to the first tile. */
    private void onHomeReset(int slide) {
        if (!onHome || !standingOnTiles()) return;
        if (Math.abs(slide) > 400 && (mode == Mode.IDLE || mode == Mode.REACT || mode == Mode.RUN || mode == Mode.SKID)) {
            annoy(15);
            facing = slide > 0 ? 1 : -1;
            Clip c = clips.get("swept_tumble");
            long ms = c != null ? c.frames * 1000L / FPS : 1500;
            moveLinear(spotX(), Math.max(200f, Math.abs(spotX() - x) * 1000f / ms));
            play("swept_tumble", Mode.SWEPT, this::goIdle);
        } else if (mode == Mode.IDLE || mode == Mode.REACT) {
            settle();
        }
    }

    /**
     * Is this the home screen? Works with any launcher (the TV's default HOME app), not just Glow Home.
     * Re-checked every 30 s in case the default launcher changes.
     */
    private boolean isHome(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - launcherCheckedAt > 30_000) {
            launcherCheckedAt = now;
            try {
                ResolveInfo ri = getPackageManager().resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
                // "android" is the chooser shown when no default launcher is set
                if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                    launcherPkg = ri.activityInfo.packageName;
                }
            } catch (Exception ignored) {
            }
        }
        return pkg.equals(launcherPkg) || pkg.equals(GLOW_HOME);
    }

    private boolean standingOnTiles() { return tileTop > 0 && Math.abs(y - homeGround()) < 40; }

    // ------------------------------------------------------------------ apps

    private void onAppSwitched(boolean home, boolean fromHome) {
        playingSince = 0;                       // only count playback inside the new app
        if (home) {
            onHome = true;
            onHomeShown();                      // Glow Home also sends "shown" with exact tile coordinates
            return;
        }
        onHome = false;
        if (mode == Mode.HIDDEN || isFalling() || mode == Mode.LEAVE_HUFF || mode == Mode.LEAVING) return;
        if (fromHome && y < appGround() - 20) {
            fall();                             // the app opened some other way: same drama
        } else {
            walkOff();                          // somehow still around inside an app: leave
        }
    }

    private void onKey(int code) {
        lastKeyAt = SystemClock.uptimeMillis();
        debug("key " + KeyEvent.keyCodeToString(code) + " mode=" + mode);
        boolean leftRight = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        // inside apps, and on launchers that don't report their tiles, the cat follows along the bottom edge
        if ((!onHome || tileTop < 0) && leftRight && canFollow()) {
            followInApp(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return;
        }
        switch (mode) {
            case SLEEP:
            case SLEEP_IN:
                annoy = Math.max(annoy, IRRITATED + 5);
                lastAnnoyAt = lastKeyAt;
                play("wake_grumpy", Mode.WAKE, this::goIdle);
                return;
            case IDLE:
            case WALK:
                break;
            default:
                return;                         // busy with something more important
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean upDown = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            annoy(15);
            stopMotion();
            play("react_back", Mode.REACT, this::goIdle);
        } else if (onHome && upDown && standingOnTiles()) {
            play("what", Mode.REACT, this::goIdle);         // nothing happens up/down on the home row
        } else if (!onHome && ok) {
            stopMotion();
            play("react_ok", Mode.REACT, this::goIdle);
        }
    }

    private boolean canFollow() {
        switch (mode) {
            case IDLE: case WALK: case RUN: case SKID: case REACT: return true;
            default: return false;
        }
    }

    /**
     * Inside apps the cat keeps pace with your browsing along the bottom edge: one press trots a step,
     * quick presses make it run, and at the screen edge it keeps running on the spot until you stop.
     */
    private void followInApp(int dir) {
        long now = SystemClock.uptimeMillis();
        boolean burst = now - lastMoveAt < FOLLOW_BURST_MS && (mode == Mode.RUN || mode == Mode.WALK);
        if (now - lastMoveAt > BUSY_GAP_MS) busyStart = now;
        lastMoveAt = now;
        annoy(3);
        if (now - busyStart >= EXHAUSTED_AFTER_MS) {
            exhausted();
            return;
        }
        boolean sameWay = motion == Motion.LINEAR && Math.signum(targetX - x) == dir;
        float to = (sameWay ? targetX : x) + dir * FOLLOW_STEP_PX;
        to = Math.max(80, Math.min(screenW - 80, to));
        facing = dir;
        if (burst || mode == Mode.RUN) {
            runUntil = now + FOLLOW_BURST_MS;
            if (mode != Mode.RUN) play("run", Mode.RUN, null);
            moveLinear(to, RUN_SPEED);
        } else {
            if (mode != Mode.WALK) play("walk", Mode.WALK, null);
            moveLinear(to, FOLLOW_WALK_SPEED);
        }
    }

    /** Once a second: video playback, the exhausted -> tantrum hand-off, mood decay, sleep. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            boolean playing = audio.isMusicActive();
            if (playing) {
                quietSince = 0;
                if (playingSince == 0) playingSince = now;
                if (now - playingSince >= VIDEO_LEAVE_MS && !onHome) leave();
            } else {
                playingSince = 0;
                if (quietSince == 0) quietSince = now;
                // the cat only lives on the home screen now; inside apps it stays away
                if (mode == Mode.HIDDEN && onHome && now - quietSince >= VIDEO_RETURN_MS) comeBack();
            }
            if (mode == Mode.EXHAUSTED && now - lastMoveAt >= TANTRUM_AFTER_QUIET_MS) {
                annoy = 90f;
                lastAnnoyAt = now;
                play("tantrum", Mode.TANTRUM, () -> {
                    play("idle_grumpy_2", Mode.REACT, null);   // tantrum ends turned away
                    goIdleAfter(1);
                });
            }
            if (mode != Mode.EXHAUSTED && mode != Mode.TANTRUM) annoy = Math.max(0f, annoy - 1f);
            if ((mode == Mode.IDLE || mode == Mode.WALK) && now - lastKeyAt >= SLEEP_AFTER_MS) {
                stopMotion();
                play("fall_asleep", Mode.SLEEP_IN, () -> play("sleep", Mode.SLEEP, null));
            }
            main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ behaviours

    private void annoy(float amount) {
        annoy = Math.min(100f, annoy + amount);
        lastAnnoyAt = SystemClock.uptimeMillis();
    }

    private String idleClip() {
        if (annoy >= FURIOUS) return "idle_furious";
        if (annoy >= IRRITATED) return "idle_irritated";
        return rnd.nextInt(3) == 0 ? "idle_grumpy_2" : "idle_grumpy";
    }

    /** Back to standing around: first make sure it is where it belongs. */
    private void goIdle() {
        stopMotion();
        if (onHome && tileTop > 0 && (Math.abs(x - spotX()) > 6 || Math.abs(y - homeGround()) > 4)) {
            settle();
            return;
        }
        if (!onHome && Math.abs(y - appGround()) > 4) {
            y = appGround();
            place();
        }
        loopsWanted = 2 + rnd.nextInt(3);
        play(idleClip(), Mode.IDLE, null);
    }

    /** Plays the current clip as a one-shot-ish hold, then idles after n loops. */
    private void goIdleAfter(int loops) {
        mode = Mode.IDLE;
        loopsWanted = loops;
    }

    /** Get onto the selected tile (home) from wherever the cat is. */
    private void settle() {
        float tx = spotX(), ty = homeGround();
        float dist = (float) Math.hypot(tx - x, ty - y);
        if (dist <= 6) {
            goIdle();
        } else if (Math.abs(ty - y) > 4 || dist <= 500) {
            hopTo(tx, ty);
        } else {
            facing = tx > x ? 1 : -1;
            runUntil = 0;
            play("run", Mode.RUN, null);
            moveLinear(tx, RUN_SPEED);
        }
    }

    private void exhausted() {
        stopMotion();
        y = onHome ? homeGround() : appGround();   // may have been mid-hop: flop down onto the row
        place();
        annoy(20);
        play("exhausted", Mode.EXHAUSTED, null);
    }

    private void hopTo(float tx, float ty) {
        if (mode == Mode.HOP_AIR) return;           // finish this hop; after landing goIdle() hops on to the latest tile
        facing = tx >= x ? 1 : (tx < x - 2 ? -1 : facing);
        ax1 = tx;
        ay1 = ty;
        if (mode == Mode.HOP_UP) return;            // the new target is picked up at take-off
        stopMotion();
        play("hop_takeoff", Mode.HOP_UP, () -> {
            play("hop_air", Mode.HOP_AIR, null);
            startArc();
        });
    }

    private void startArc() {
        ax0 = x;
        ay0 = y;
        float dist = (float) Math.hypot(ax1 - ax0, ay1 - ay0);
        arcMs = (long) Math.max(300, Math.min(700, 300 + dist / 1.5f));
        float apex = Math.min(ay0, ay1) - (24 + dist * 0.08f);
        acy = 2 * apex - (ay0 + ay1) / 2;           // control point so the curve peaks at apex
        arcStart = SystemClock.uptimeMillis();
        motion = Motion.ARC;
    }

    private void fall() {
        stopMotion();
        annoy(25);
        vy = 0;
        groundTo = appGround();
        motion = Motion.FALL;
        play("fall_air", Mode.FALL_AIR, null);
    }

    private void landFromFall() {
        y = groundTo;
        place();
        play("fall_crash", Mode.FALL_CRASH, () -> {
            loopsWanted = 1;
            play("dizzy", Mode.DIZZY, null);
        });
    }

    private boolean isFalling() {
        return mode == Mode.FALL_AIR || mode == Mode.FALL_CRASH || mode == Mode.DIZZY || mode == Mode.RECOVER;
    }

    private void leave() {
        if (mode == Mode.HIDDEN || mode == Mode.LEAVE_HUFF || mode == Mode.LEAVING || isFalling()) return;
        if (mode == Mode.SLEEP || mode == Mode.SLEEP_IN || mode == Mode.WAKE) {
            hide();                                 // not in a state to stomp off: just slip away
            return;
        }
        stopMotion();
        play("leave_huff", Mode.LEAVE_HUFF, this::walkOff);
    }

    /** After the launch fall: on home it settles on a tile; inside an app it stomps off and stays away. */
    private void afterFall() {
        if (onHome) goIdle();
        else walkOff();
    }

    /** Walk off the nearest screen edge, then hide until the home screen is back. */
    private void walkOff() {
        stopMotion();
        leftByLeftEdge = x < screenW / 2f;
        facing = leftByLeftEdge ? -1 : 1;
        play("walk", Mode.LEAVING, null);
        moveLinear(leftByLeftEdge ? -CANVAS : screenW + CANVAS, WALK_OFF_SPEED);
    }

    private void comeBack() {
        if (mode != Mode.HIDDEN) return;
        lastKeyAt = SystemClock.uptimeMillis();
        boolean fromLeft = onHome ? false : leftByLeftEdge;
        x = fromLeft ? -CANVAS / 2f : screenW + CANVAS / 2f;
        y = onHome ? homeGround() : appGround();
        facing = fromLeft ? 1 : -1;
        place();
        view.setVisibility(View.VISIBLE);
        float to = onHome ? spotX()               // spotX() falls back to mid-row until Glow Home reports a tile
                : (fromLeft ? 160 + rnd.nextInt(260) : screenW - 160 - rnd.nextInt(260));
        play("walk", Mode.RETURNING, null);
        moveLinear(to, WALK_SPEED * 1.4f);
    }

    private void hide() {
        stopMotion();
        mode = Mode.HIDDEN;
        stopTicking();
        view.setVisibility(View.GONE);
    }

    private void wander() {
        int lo = 80, hi = screenW - 80;
        float to = x + (rnd.nextBoolean() ? 1 : -1) * (150 + rnd.nextInt(250));
        if (to < lo || to > hi) to = x - (to - x);
        to = Math.max(lo, Math.min(hi, to));
        facing = to >= x ? 1 : -1;
        play("walk", Mode.WALK, null);
        moveLinear(to, WALK_SPEED);
    }

    /** A looping clip wrapped around. */
    private void onLoopEnd() {
        long now = SystemClock.uptimeMillis();
        switch (mode) {
            case DIZZY:
                if (loopsSeen >= loopsWanted) {
                    play("recover_angry", Mode.RECOVER, this::afterFall);
                }
                return;
            case IDLE:
                break;
            default:
                return;
        }
        String want = idleClip();
        boolean wasFurious = clip.name.equals("idle_furious");
        if (wasFurious && annoy < FURIOUS) {   // cooled off: a visible calm-down
            play("calm_down", Mode.REACT, this::goIdle);
            return;
        }
        boolean moodChanged = want.startsWith("idle_grumpy") != clip.name.startsWith("idle_grumpy")
                || (!want.startsWith("idle_grumpy") && !want.equals(clip.name));
        if (moodChanged) {
            goIdle();
            return;
        }
        if (loopsSeen < loopsWanted) return;
        if (annoy < 5 && now - lastAnnoyAt >= SOFT_CALM_MS && now - lastSoftAt >= SOFT_EVERY_MS && rnd.nextInt(3) == 0) {
            lastSoftAt = now;
            play("soft_moment", Mode.REACT, this::goIdle);
        } else if (!onHome && rnd.nextInt(2) == 0) {
            wander();
        } else {
            goIdle();
        }
    }

    /** The current movement reached its target. */
    private void onArrived() {
        switch (mode) {
            case HOP_AIR:
                play("hop_land", Mode.HOP_LAND, this::goIdle);
                break;
            case RUN:
                if (SystemClock.uptimeMillis() < runUntil) return;   // treadmill: keep running while it slides
                play("skid_stop", Mode.SKID, this::goIdle);
                break;
            case LEAVING:
                hide();
                if (onHome) comeBack();                 // home came back while it was walking off
                break;
            case RETURNING:
            case WALK:
                goIdle();
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ motion

    private void moveLinear(float tx, float pxPerS) {
        targetX = tx;
        speed = pxPerS;
        motion = Motion.LINEAR;
        startTicking();
    }

    private void stopMotion() {
        motion = Motion.NONE;
    }

    /** Advances the current movement by dt seconds; true when it has arrived. */
    private boolean move(float dt) {
        switch (motion) {
            case LINEAR: {
                float step = speed * dt, d = targetX - x;
                if (Math.abs(d) <= step) {
                    x = targetX;
                    motion = Motion.NONE;
                    return true;
                }
                x += Math.signum(d) * step;
                return false;
            }
            case ARC: {
                float t = Math.min(1f, (SystemClock.uptimeMillis() - arcStart) / (float) arcMs);
                float u = 1 - t;
                x = ax0 + (ax1 - ax0) * t;
                y = u * u * ay0 + 2 * u * t * acy + t * t * ay1;
                if (t >= 1f) {
                    motion = Motion.NONE;
                    return true;
                }
                return false;
            }
            case FALL: {
                vy += GRAVITY * dt;
                y += vy * dt;
                if (y >= groundTo) {
                    motion = Motion.NONE;
                    landFromFall();
                }
                return false;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ animation engine

    private void play(String name, Mode m, Runnable after) {
        Clip c = clips.get(name);
        if (c == null) {
            debug("missing clip " + name);
            if (after != null) main.post(after);
            return;
        }
        debug("play " + name + " " + m + " x=" + Math.round(x) + " y=" + Math.round(y) + " annoy=" + Math.round(annoy));
        long now = SystemClock.uptimeMillis();
        boolean seamless =clip != null && SEAMLESS.contains(clip.name + ">" + name);
        if (clip != null && view.getVisibility() == View.VISIBLE && !seamless && clip != c) {
            Bitmap t = prev; prev = cur; cur = t;  // keep the last frame for a short crossfade
            fadeStart = now;
        }
        clip = c;
        mode = m;
        then = after;
        playSeq++;
        clipStart = now;
        clipDone = false;
        loopsSeen = 0;
        shownFrame = -1;
        if (m != Mode.IDLE && m != Mode.DIZZY) loopsWanted = 0;
        showFrame(0);
        startTicking();
    }

    private final Runnable tick = new Runnable() {
        private long last;

        @Override
        public void run() {
            if (!ticking) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0f : Math.min(0.1f, (now - last) / 1000f);
            last = now;
            step(now, dt);
            if (ticking) {
                boolean busy = motion != Motion.NONE || fadeStart >= 0;
                main.postDelayed(this, busy ? 33 : 1000 / FPS);
            } else {
                last = 0;
            }
        }
    };

    private void startTicking() {
        if (!ticking) {
            ticking = true;
            main.post(tick);
        }
    }

    private void stopTicking() {
        ticking = false;
        main.removeCallbacks(tick);
    }

    private void step(long now, float dt) {
        if (clip == null) return;
        if (motion != Motion.NONE) {
            boolean arrived = move(dt);
            place();
            if (arrived) {
                onArrived();
                return;
            }
        } else if (mode == Mode.RUN && now >= runUntil) {
            onArrived();                       // treadmill run with no distance to cover
            return;
        }
        if (fadeStart >= 0 && now - fadeStart >= FADE_MS) fadeStart = -1;

        int f = (int) ((now - clipStart) * FPS / 1000);
        if (clip.loop) {
            int loops = f / clip.frames;
            if (loops > loopsSeen) {
                loopsSeen = loops;
                int seq = playSeq;
                onLoopEnd();
                if (playSeq != seq) return;
            }
            showFrame(f % clip.frames);
        } else if (f >= clip.frames) {
            showFrame(clip.frames - 1);
            if (!clipDone) {
                clipDone = true;
                Runnable r = then;
                then = null;
                if (r != null) r.run();
            }
        } else {
            showFrame(f);
        }
        if (fadeStart >= 0) view.invalidate();
    }

    private void showFrame(int i) {
        if (i == shownFrame) return;
        shownFrame = i;
        String path = "cat/" + clip.name + "/" + String.format(java.util.Locale.ROOT, "%03d", i) + ".webp";
        try (InputStream in = getAssets().open(path)) {
            opts.inBitmap = cur;
            Bitmap b = BitmapFactory.decodeStream(in, null, opts);
            if (b != null) cur = b;
        } catch (Exception ignored) {
        }
        view.invalidate();
    }

    private void loadClipIndex() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("cat/clips.txt")))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 3) clips.put(f[0], new Clip(f[0], Integer.parseInt(f[1]), "1".equals(f[2])));
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ placement

    private float spotX() { return tileCx > 0 ? tileCx + TILE_SPOT_DX : screenW * 0.55f; }

    /** On Glow Home the cat stands on the selected tile; on any other launcher, on the bottom edge. */
    private float homeGround() { return tileTop > 0 ? tileTop : appGround(); }

    private float appGround() { return screenH - APP_GROUND_MARGIN; }

    private void place() {
        int nx = Math.round(x) - CANVAS / 2, ny = Math.round(y) - FOOT;
        if (nx == lastLpX && ny == lastLpY) return;
        lastLpX = nx;
        lastLpY = ny;
        lp.x = nx;
        lp.y = ny;
        if (view.getParent() != null) {
            try {
                wm.updateViewLayout(view, lp);
            } catch (IllegalArgumentException ignored) {
                // window not attached yet: lp is applied when it is added
            }
        }
    }

    private static void debug(String s) {
        if (Log.isLoggable("GlowCat", Log.DEBUG)) Log.d("GlowCat", s);
    }

    // ------------------------------------------------------------------ view

    private final class CatView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        CatView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            if (facing < 0) canvas.scale(-1f, 1f, CANVAS / 2f, 0f);
            if (fadeStart >= 0) {
                float k = 1f - Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
                paint.setAlpha(Math.round(255 * k));
                canvas.drawBitmap(prev, 0, 0, paint);
                paint.setAlpha(Math.round(255 * (1 - k)));
            } else {
                paint.setAlpha(255);
            }
            canvas.drawBitmap(cur, 0, 0, paint);
            canvas.restore();
        }
    }
}

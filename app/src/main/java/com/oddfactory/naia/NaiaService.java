package com.oddfactory.naia;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 나이아 오버레이(접근성 오버레이: 알림창 위에도 그려진다).
 * - 큰 창(입력 통과): 파도 → 웅덩이 → 나이아 → 물방울·철퍽 → 말풍선 순으로 그린다.
 * - 작은 창(입력 받음): 나이아 몸 위치에만 둔다. 톡 = 간지럼, 끌기 = 볼 당김 이동, 두 번 톡 = 메뉴.
 */
public final class NaiaService extends AccessibilityService {
    static volatile NaiaService current;
    static final String PREFS = "naia";

    // ---- 외부 이벤트(알림 리스너·메인 화면에서 호출) ----
    static void onPosted(String pkg, Icon small, Icon large, String title, String text) {
        NaiaService s = current; if (s != null) s.main.post(() -> s.notifyPosted(pkg, small, large, title, text));
    }
    static void onDismiss(int pos) { NaiaService s = current; if (s != null) s.main.post(() -> s.shoot(pos)); }
    static void onClearAll() { NaiaService s = current; if (s != null) s.main.post(s::callTide); }
    static void onDnd(boolean on) { NaiaService s = current; if (s != null) s.main.post(() -> s.setDnd(on)); else pendingDnd = on; }
    static volatile boolean pendingDnd;
    static void summon() { NaiaService s = current; if (s != null) s.main.post(s::enter); }
    static void dismiss() { NaiaService s = current; if (s != null) s.main.post(s::exit); }
    static boolean shown() { NaiaService s = current; return s != null && s.mode != HIDDEN; }

    static final int HIDDEN = 0, ENTER = 1, IDLE = 2, WALK = 3, ACTION = 4, DRAG = 5, EXIT = 6;

    final Handler main = new Handler(Looper.getMainLooper());
    final Random rnd = new Random();
    WindowManager wm; Sprites sp; SharedPreferences prefs;
    Stage stage; View grip; View menu;
    WindowManager.LayoutParams gripLp;
    float dp;

    // 캐릭터 상태
    int mode = HIDDEN;
    float x, y;            // 프레임 왼쪽 위(화면 px)
    int px;                // 프레임을 그릴 한 변(px)
    boolean mirror;        // true = 오른쪽을 보게 좌우 반전(원본 그림은 왼쪽을 봄)
    String motion = "idle"; int frame; long frameAt; int frameMs = 110; boolean loop = true; Runnable onEnd;
    long modeUntil, lastStepAt; float walkTo;
    // 웅덩이(등장·퇴장 때 발밑): scale 0~1
    float puddleFrom, puddleTo; long puddleT0, puddleDur; boolean puddleOn;

    @Override protected void onServiceConnected() {
        current = this;
        wm = getSystemService(WindowManager.class);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try { sp = new Sprites(this); } catch (Exception e) { return; }
        px = Math.round(prefs.getInt("size", 170) * dp);
        dnd = pendingDnd;
        stage = new Stage(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("NaiaStage");
        wm.addView(stage, lp);
        grip = new View(this);
        grip.setOnTouchListener(this::onGripTouch);
        gripLp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        gripLp.gravity = Gravity.TOP | Gravity.START;
        gripLp.setTitle("NaiaGrip");
        grip.setVisibility(View.GONE);
        wm.addView(grip, gripLp);
        // 화면 크기를 안 뒤 첫 등장(설정에서 켰거나 재부팅 후 켜졌을 때)
        stage.post(() -> { placeDefault(); if (prefs.getBoolean("shown", true)) enter(); });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {
        if (current == this) current = null;
        main.removeCallbacksAndMessages(null);
        for (View v : new View[]{stage, grip, menu}) try { if (v != null) wm.removeView(v); } catch (RuntimeException ignored) {}
        super.onDestroy();
    }

    int W() { return stage.getWidth() > 0 ? stage.getWidth() : getResources().getDisplayMetrics().widthPixels; }
    int H() { return stage.getHeight() > 0 ? stage.getHeight() : getResources().getDisplayMetrics().heightPixels; }
    float cx() { return x + px * 0.5f; }

    void placeDefault() {
        float sx = prefs.getFloat("x", -1), sy = prefs.getFloat("y", -1);
        if (sx >= 0 && sy >= 0) { x = sx * W(); y = sy * H(); }
        else { x = W() - px - 8 * dp; y = H() - px - 110 * dp; }
        clamp();
    }
    void clamp() { x = Math.max(-px * 0.15f, Math.min(W() - px * 0.85f, x)); y = Math.max(0, Math.min(H() - px, y)); }
    void savePos() { prefs.edit().putFloat("x", x / W()).putFloat("y", y / H()).apply(); }

    // ---- 모션 재생 ----
    void play(String m, boolean lp, int ms, Runnable end) {
        motion = m; loop = lp; frameMs = ms; onEnd = end; frame = 0; frameAt = SystemClock.uptimeMillis();
        stage.kick();
    }
    boolean dnd;
    void setDnd(boolean on) { dnd = on; if (mode == IDLE || mode == WALK) toIdle(); }
    void toIdle() {
        mode = IDLE; mirror = false;
        if (dnd) { play("sleep", true, 130, null); modeUntil = Long.MAX_VALUE; return; }   // 방해 금지: 제자리에서 꾸벅꾸벅
        play("idle", true, 110, null);
        modeUntil = SystemClock.uptimeMillis() + 2000;
    }
    void startWalk() {
        float lo = 0, hi = W() - px;
        float t = lo + rnd.nextFloat() * (hi - lo);
        if (Math.abs(t - x) < W() * 0.15f) t = (x > (lo + hi) / 2) ? x - W() * 0.25f : x + W() * 0.25f;
        walkTo = Math.max(lo, Math.min(hi, t));
        mirror = walkTo > x;   // 걷기 그림은 왼쪽을 봄 → 오른쪽으로 갈 때만 뒤집는다
        mode = WALK; play("walk", true, 140, null);
    }

    // ---- 등장·퇴장 ----
    void enter() {
        if (sp == null || mode == ENTER) return;
        hideMenu();
        prefs.edit().putBoolean("shown", true).apply();
        if (mode != HIDDEN) return;
        mode = ENTER; mirror = false;
        puddle(0, 1, 300);
        motion = "enter"; frame = 0; frameAt = SystemClock.uptimeMillis() + 300; loop = false; frameMs = 110;
        onEnd = () -> { puddle(1, 0, 450); toIdle(); };
        showGrip(true); stage.kick();
    }
    void exit() {
        if (sp == null || mode == HIDDEN || mode == EXIT) return;
        hideMenu(); clearFx();
        prefs.edit().putBoolean("shown", false).apply();
        mode = EXIT; mirror = false;
        motion = "idle"; frame = 0; loop = true;
        puddle(0, 1, 300);
        main.postDelayed(() -> play("exit", false, 110, () -> {
            puddle(1, 0, 450);
            motion = null;
            main.postDelayed(() -> { mode = HIDDEN; puddleOn = false; stage.invalidate(); }, 460);
        }), 300);
        showGrip(false);
    }
    void puddle(float from, float to, long dur) { puddleOn = true; puddleFrom = from; puddleTo = to; puddleT0 = SystemClock.uptimeMillis(); puddleDur = dur; stage.kick(); }
    float puddleScale(long now) {
        if (!puddleOn) return 0;
        float t = Math.min(1f, (now - puddleT0) / (float) Math.max(1, puddleDur));
        float s = puddleFrom + (puddleTo - puddleFrom) * t;
        if (t >= 1f && puddleTo == 0) puddleOn = false;
        return s;
    }

    boolean busy() { return mode == HIDDEN || mode == ENTER || mode == EXIT || mode == DRAG; }

    // ---- 알림 반응 ----
    void notifyPosted(String pkg, Icon small, Icon large, String title, String text) {
        if (mode == HIDDEN || mode == EXIT) return;
        Drawable icon = null; String label = pkg;
        PackageManager pm = getPackageManager();
        try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
        if (icon == null) try { icon = (large != null ? large : small).loadDrawable(this); } catch (Exception ignored) {}
        bubble = new Bubble(icon, label, title, text, SystemClock.uptimeMillis());
        if (!dnd && !busy() && mode != ACTION) { mode = ACTION; mirror = false; play("wave", false, 110, this::toIdle); }
        stage.kick();
    }

    /** 알림창에서 pos번째(0부터) 알림이 대략 있는 화면 높이. 실기(갤럭시) 알림창 카드 간격 기준 추정. */
    float notifY(int pos) {
        float h = H();
        if (pos < 0) return h * 0.3f;
        return Math.max(h * 0.15f, Math.min(h * 0.8f, h * (0.225f + 0.105f * pos)));
    }

    void shoot(int pos) {
        if (busy()) return;
        float ty = notifY(pos);
        float tx = cx() > W() / 2f ? W() * 0.40f : W() * 0.60f;
        mirror = tx > cx();
        mode = ACTION;
        shotTarget = new PointF(tx, ty);
        shotAt = SystemClock.uptimeMillis();
        play("shoot", false, 80, this::toIdle);
        frame = 4;                       // 겨누기 끝 장면부터 바로 시작
        fireDrops(shotAt);               // 첫 물방울은 즉시
    }
    long shotAt;
    PointF shotTarget;
    /** 물총 그림에서 물을 쏘는 프레임(겨누기 이후 발사 구간). */
    boolean firingFrame(String m, int f) { return "shoot".equals(m) && f >= 5 && f <= 10; }

    void callTide() {
        // 방금 하나 지우기로 쏘기 시작했는데 연달아 지워졌다면(=모두 지우기) 물총을 접고 파도로
        if (mode == ACTION && "shoot".equals(motion) && SystemClock.uptimeMillis() - shotAt < 600) { drops.clear(); mode = IDLE; }
        if (busy() || (mode == ACTION && "tide".equals(motion))) return;
        mode = ACTION;
        waveDir = cx() > W() / 2f ? -1 : 1;
        mirror = waveDir < 0;    // 파도 부르기 그림은 오른쪽으로 내밂 → 왼쪽으로 보낼 땐 뒤집는다
        play("tide", false, 110, this::toIdle);
    }
    int waveDir = 1;

    // ---- 효과 ----
    final class Drop { float x0, y0, x1, y1, sc; long t0, dur; Drop(float a, float b, float c, float d, float s, long t, long u) { x0 = a; y0 = b; x1 = c; y1 = d; sc = s; t0 = t; dur = u; } }
    final class Splat { float x, y, sc; long t0; boolean flip; Splat(float a, float b, float s, long t, boolean f) { x = a; y = b; sc = s; t0 = t; flip = f; } }
    final class Wave { long t0; int dir; float x0, ground; Wave(long t, int d, float a, float g) { t0 = t; dir = d; x0 = a; ground = g; } }
    final class Bubble { Drawable icon; String label, title, text; long t0; Bubble(Drawable i, String l, String a, String b, long t) { icon = i; label = l; title = a; text = b; t0 = t; } }
    final List<Drop> drops = new ArrayList<>(); final List<Splat> splats = new ArrayList<>(); Wave wave; Bubble bubble;
    void clearFx() { drops.clear(); splats.clear(); wave = null; bubble = null; }

    void fireDrops(long now) {
        if (shotTarget == null) return;
        float mx = x + (mirror ? 1 - sp.mouthX : sp.mouthX) * px, my = y + sp.mouthY * px;
        for (int k = 0; k < 3; k++) {
            // 지운 알림 자리(카드 가운데 줄)로 모아서
            float tx = shotTarget.x + (rnd.nextFloat() - 0.5f) * W() * 0.12f, ty = shotTarget.y + (rnd.nextFloat() - 0.5f) * 24 * dp;
            float dist = (float) Math.hypot(tx - mx, ty - my);
            long dur = Math.max(90, (long) (dist / (H() * 3.2f) * 1000));
            float[] scs = {1.1f, 1.3f, 1.5f, 1.5f, 1.7f};
            drops.add(new Drop(mx, my, tx, ty, scs[rnd.nextInt(scs.length)], now + k * 27L, dur));
        }
    }

    // ---- 매 프레임 ----
    void step(long now) {
        if (motion != null && sp != null) {
            while (now - frameAt >= frameMs) {
                frameAt += frameMs; frame++;
                int n = sp.count(motion);
                if (frame >= n) {
                    if (loop) frame = 0;
                    else { frame = n - 1; Runnable r = onEnd; onEnd = null; frameAt = now; if (r != null) r.run(); break; }
                }
                if (firingFrame(motion, frame)) fireDrops(now);
                if ("tide".equals(motion) && frame == 6) wave = new Wave(now, waveDir, cx() + waveDir * px * 0.25f, y + sp.foot * px);
            }
        }
        long dt = lastStepAt == 0 ? 16 : Math.min(50, now - lastStepAt); lastStepAt = now;
        if (mode == IDLE && now > modeUntil) startWalk();
        if (mode == WALK) {
            float speed = 0.7057f * px / 1000f * dt; // 걷기 그림 보폭에 맞춘 속도(초당 0.7배 몸 크기)
            float d = walkTo - x;
            if (Math.abs(d) <= speed) { x = walkTo; savePos(); toIdle(); }
            else x += Math.signum(d) * speed;
        }
        // 물방울 → 도착하면 철퍽
        for (Iterator<Drop> it = drops.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            if (now >= d.t0 + d.dur) { splats.add(new Splat(d.x1, d.y1, d.sc * 1.3f, now, d.x1 > d.x0)); it.remove(); }
        }
        for (Iterator<Splat> it = splats.iterator(); it.hasNext(); ) if (now - it.next().t0 > 360) it.remove();
        if (bubble != null && now - bubble.t0 > 4500) bubble = null;
        updateGrip();
    }

    boolean animating() { return mode != HIDDEN || puddleOn || !drops.isEmpty() || !splats.isEmpty() || wave != null || bubble != null; }

    // ---- 그리기 ----
    final class Stage extends View {
        final Paint pix = new Paint(); final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tBold = new Paint(Paint.ANTI_ALIAS_FLAG), tLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect src = new Rect(); final RectF dst = new RectF();
        Stage(Context c) {
            super(c);
            pix.setFilterBitmap(false);
            card.setColor(0xFFFFFFFF); border.setColor(0xFF5A3A2E); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2.5f * dp);
            Typeface b = null, l = null;
            try { b = getResources().getFont(R.font.maple_bold); l = getResources().getFont(R.font.maple_light); } catch (Exception ignored) {}
            tBold.setTypeface(b); tBold.setColor(0xFF3A2620); tBold.setTextSize(13 * dp);
            tLight.setTypeface(l); tLight.setColor(0xFF4A3A34); tLight.setTextSize(12 * dp);
        }
        void kick() { postInvalidateOnAnimation(); }

        void sprite(Canvas c, Bitmap b, float left, float top, float w, float h, boolean flip) {
            if (b == null) return;
            src.set(0, 0, b.getWidth(), b.getHeight());
            // 세로로 긴 프레임(공중 장면 머리 위 여백)은 바닥선을 맞추고 위로 늘린다
            if (b.getHeight() != b.getWidth()) { float h2 = w * b.getHeight() / b.getWidth(); top -= h2 - h; h = h2; }
            if (flip) { c.save(); c.scale(-1, 1, left + w / 2, 0); }
            dst.set(left, top, left + w, top + h); c.drawBitmap(b, src, dst, pix);
            if (flip) c.restore();
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (sp == null) return;
            step(now);
            float k = px / (float) sp.size;          // 자산 px → 화면 px
            drawWave(c, now, k);
            float ps = puddleScale(now);
            if (ps > 0 && !("enter".equals(motion) || "exit".equals(motion)) || (ps > 0 && "enter".equals(motion) && now < frameAt)) {
                c.save(); c.scale(ps, ps, x + px * 0.5f, y + sp.foot * px);
                sprite(c, sp.frame(sp.puddleFrame), x, y, px, px, false); c.restore();
            }
            if (mode != HIDDEN && motion != null && !("enter".equals(motion) && now < frameAt))
                sprite(c, sp.frame(motion, frame), x, y, px, px, mirror);
            for (Drop d : drops) {
                if (now < d.t0) continue;
                float t = Math.min(1f, (now - d.t0) / (float) d.dur);
                float dx = d.x1 - d.x0, dy = d.y1 - d.y0;
                float arc = -Math.abs(dx) * 0.06f;      // 살짝 위로 솟았다 떨어지는 포물선
                float xx = d.x0 + dx * t, yy = d.y0 + dy * t + arc * (float) Math.sin(Math.PI * t);
                float vy = dy + arc * (float) (Math.PI * Math.cos(Math.PI * t));
                float st = 1f + 0.45f * (float) (Math.exp(-t * 4) * Math.cos(t * 10));  // 막 나왔을 땐 길쭉, 출렁이며 둥글어짐
                Bitmap b = sp.fx.get("drop"); float w = b.getWidth() * k * d.sc * st, h = b.getHeight() * k * d.sc / st;
                c.save();
                c.rotate((float) Math.toDegrees(Math.atan2(vy, Math.abs(dx))) * (dx < 0 ? -1 : 1), xx, yy);
                sprite(c, b, xx - w / 2, yy - h / 2, w, h, dx > 0);   // 물방울 그림은 왼쪽으로 날아가는 모양
                c.restore();
            }
            for (Splat s : splats) {
                float t = (now - s.t0) / 360f; int f = Math.min(3, (int) (t * 4));
                float scl = new float[]{0.55f, 1f, 1f, 0.9f}[f];
                Bitmap b = sp.fx.get("splat"); float w = b.getWidth() * k * s.sc * scl, h = b.getHeight() * k * s.sc * scl;
                pix.setAlpha(new int[]{255, 255, 160, 80}[f]);
                sprite(c, b, s.flip ? s.x - w * 0.65f : s.x - w * 0.35f, s.y - h / 2 + f * 2 * dp, w, h, s.flip);
                pix.setAlpha(255);
            }
            drawBubble(c, now);
            if (animating()) postInvalidateOnAnimation();
        }

        void drawWave(Canvas c, long now, float k) {
            if (wave == null) return;
            long t = now - wave.t0;
            Bitmap ref = sp.fx.get("wave_k2");
            float scale = (px * 0.9f * 1.6f) / ref.getHeight();     // 나이아보다 크게
            float w2 = ref.getWidth() * scale;
            float far = wave.dir > 0 ? W() + w2 * 0.1f : -w2 * 1.1f;
            float travel = Math.abs(far - wave.x0);
            long rise = 330, run = (long) (travel / (W() * 0.9f) * 1000), crash = 500;
            String key; float xx; float sink = 0;
            if (t < rise) { key = "wave_k1"; xx = wave.x0; }
            else if (t < rise + run) { key = ((t / 110) % 2 == 0) ? "wave_k2" : "wave_k2b"; float u = (t - rise) / (float) run; u = u * u * (3 - 2 * u); xx = wave.x0 + (far - wave.x0) * u; }
            else if (t < rise + run + crash) { key = "wave_k3"; xx = far; sink = (t - rise - run) / (float) crash; }
            else { wave = null; return; }
            Bitmap b = sp.fx.get(key);
            float w = b.getWidth() * scale, h = b.getHeight() * scale;
            float left = wave.dir > 0 ? xx - (key.equals("wave_k3") ? w * 0.6f : 0) : xx - w * 0.4f;
            if (key.equals("wave_k3")) left = wave.dir > 0 ? W() - w : 0;
            float top = wave.ground - h + sink * h * 0.8f;
            c.save(); c.clipRect(0, 0, W(), wave.ground + 4 * dp);
            sprite(c, b, left, top, w, h, wave.dir < 0);   // 파도 그림은 오른쪽으로 말림 → 왼쪽으로 갈 땐 뒤집음
            c.restore();
        }

        void drawBubble(Canvas c, long now) {
            if (bubble == null || mode == HIDDEN) return;
            float age = now - bubble.t0, a = age < 200 ? age / 200f : age > 4000 ? Math.max(0, 1 - (age - 4000) / 500f) : 1f;
            float bw = Math.min(W() - 24 * dp, 250 * dp), pad = 10 * dp, ic = 28 * dp;
            float bh = pad * 2 + Math.max(ic, tBold.getTextSize() * 1.3f + tLight.getTextSize() * 2.6f);
            float bx = Math.max(12 * dp, Math.min(W() - bw - 12 * dp, cx() - bw / 2));
            float by = y + px * 0.08f - bh - 10 * dp;
            if (by < 24 * dp) by = y + px * 0.95f;
            int al = (int) (255 * a);
            card.setAlpha(al); border.setAlpha(al); tBold.setAlpha(al); tLight.setAlpha(al);
            RectF r = new RectF(bx, by, bx + bw, by + bh);
            c.drawRoundRect(r, 14 * dp, 14 * dp, card); c.drawRoundRect(r, 14 * dp, 14 * dp, border);
            // 꼬리
            Path tail = new Path(); float tx = Math.max(bx + 20 * dp, Math.min(bx + bw - 20 * dp, cx()));
            boolean below = by > y;
            if (!below) { tail.moveTo(tx - 7 * dp, r.bottom - 1); tail.lineTo(tx, r.bottom + 9 * dp); tail.lineTo(tx + 7 * dp, r.bottom - 1); }
            else { tail.moveTo(tx - 7 * dp, r.top + 1); tail.lineTo(tx, r.top - 9 * dp); tail.lineTo(tx + 7 * dp, r.top + 1); }
            c.drawPath(tail, card);
            float textX = bx + pad;
            if (bubble.icon != null) { bubble.icon.setBounds((int) (bx + pad), (int) (by + pad), (int) (bx + pad + ic), (int) (by + pad + ic)); bubble.icon.setAlpha(al); bubble.icon.draw(c); textX += ic + 8 * dp; }
            float tw = bx + bw - pad - textX, ly = by + pad + tBold.getTextSize();
            c.drawText(fit(bubble.label, tBold, tw), textX, ly, tBold);
            String line = bubble.title.isEmpty() ? bubble.text : bubble.title;
            c.drawText(fit(line, tLight, tw), textX, ly + tLight.getTextSize() * 1.35f, tLight);
            if (!bubble.title.isEmpty() && !bubble.text.isEmpty())
                c.drawText(fit(bubble.text, tLight, tw), textX, ly + tLight.getTextSize() * 2.6f, tLight);
        }
        String fit(String s, Paint p, float w) {
            if (s == null) return ""; s = s.replace('\n', ' ');
            if (p.measureText(s) <= w) return s;
            int n = p.breakText(s, true, w - p.measureText("…"), null); return s.substring(0, Math.max(0, n)) + "…";
        }
    }

    // ---- 터치(몸 위치의 작은 창) ----
    float gripLastX = -1, gripLastY = -1;
    void showGrip(boolean on) {
        grip.setVisibility(on ? View.VISIBLE : View.GONE);
        gripLp.flags = on ? (gripLp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) : (gripLp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        gripLastX = -1; updateGrip();
    }
    void updateGrip() {
        if (grip == null || grip.getVisibility() != View.VISIBLE) return;
        int gx = Math.round(x + px * 0.2f), gy = Math.round(y + px * 0.07f), gw = Math.round(px * 0.6f), gh = Math.round(px * 0.85f);
        if (gx == gripLastX && gy == gripLastY && gripLp.width == gw) return;
        gripLastX = gx; gripLastY = gy;
        gripLp.x = gx; gripLp.y = gy; gripLp.width = gw; gripLp.height = gh;
        try { wm.updateViewLayout(grip, gripLp); } catch (RuntimeException ignored) {}
    }

    float downX, downY, grabDX, grabDY; long downAt, lastTapAt; boolean moved; Runnable pendingTap, longPress;
    boolean onGripTouch(View v, MotionEvent e) {
        if (mode == HIDDEN || mode == ENTER || mode == EXIT) return true;
        float rx = e.getRawX(), ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = rx; downY = ry; downAt = SystemClock.uptimeMillis(); moved = false;
                grabDX = rx - x; grabDY = ry - y;
                longPress = this::startDrag; main.postDelayed(longPress, 350);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode != DRAG && Math.hypot(rx - downX, ry - downY) > 10 * dp) { moved = true; startDrag(); }
                if (mode == DRAG) { x = rx - grabDX; y = ry - grabDY; clamp(); stage.kick(); }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                if (mode == DRAG) { savePos(); toIdle(); return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return true;
                long now = SystemClock.uptimeMillis();
                if (now - lastTapAt < 300 && pendingTap != null) {   // 두 번 톡 → 메뉴
                    main.removeCallbacks(pendingTap); pendingTap = null; lastTapAt = 0; showMenu(); return true;
                }
                lastTapAt = now;
                pendingTap = () -> { pendingTap = null; tickle(); };
                main.postDelayed(pendingTap, 300);
                return true;
        }
        return true;
    }
    void startDrag() {
        if (mode == DRAG || mode == HIDDEN || mode == ENTER || mode == EXIT) return;
        main.removeCallbacks(longPress); if (pendingTap != null) { main.removeCallbacks(pendingTap); pendingTap = null; }
        mode = DRAG; mirror = false;
        play("pull", true, 110, null);
        frame = 1;   // 첫 장(대기)은 건너뛰고 바로 허우적
    }
    void tickle() {
        if (busy()) return;
        mode = ACTION; mirror = false; play("tickle", false, 110, this::toIdle);
    }

    // ---- 두 번 톡 메뉴 ----
    void showMenu() {
        hideMenu();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(10 * dp); box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFFFFFFF); bg.setCornerRadius(16 * dp); bg.setStroke(Math.round(3 * dp), 0xFF5A3A2E);
        box.setBackground(bg);
        Typeface tf = null; try { tf = getResources().getFont(R.font.maple_bold); } catch (Exception ignored) {}
        String[] labels = {"설정 열기", "나이아 돌려보내기", "닫기"};
        Runnable[] acts = {
                () -> { hideMenu(); Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); },
                this::exit, this::hideMenu};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this); b.setText(labels[i]); b.setAllCaps(false); if (tf != null) b.setTypeface(tf);
            final Runnable r = acts[i]; b.setOnClickListener(v -> r.run());
            box.addView(b);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(Math.max(8 * dp, Math.min(W() - 220 * dp, cx() - 110 * dp))); lp.y = Math.round(Math.max(24 * dp, y - 170 * dp));
        box.setOnTouchListener((v, ev) -> { if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu(); return false; });
        menu = box; wm.addView(box, lp);
    }
    void hideMenu() { if (menu != null) { try { wm.removeView(menu); } catch (RuntimeException ignored) {} menu = null; } }
}

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
    /** 지워진 알림 한 장의 내용(화면에 다시 그려 맞고 사라지게 하려고). */
    static final class Gone {
        final String pkg, title, text; final Icon small;
        Gone(String p, Icon s, String a, String b) { pkg = p; small = s; title = a; text = b; }
    }
    static void onDismiss(int pos, Gone g) { NaiaService s = current; if (s != null) s.main.post(() -> s.shoot(pos, g)); }
    static void onClearAll(Gone g) { NaiaService s = current; if (s != null) s.main.post(() -> s.callTide(g)); }
    static void onDnd(boolean on) { NaiaService s = current; if (s != null) s.main.post(() -> s.setDnd(on)); else pendingDnd = on; }
    static volatile boolean pendingDnd;
    static void summon() { NaiaService s = current; if (s != null) s.main.post(s::enter); }
    static void dismiss() { NaiaService s = current; if (s != null) s.main.post(s::exit); }
    /** 앱의 크기 막대: 발밑(아래 가운데)을 그대로 두고 바로 크기를 바꾼다 */
    static void resize(int sizeDp) { NaiaService s = current; if (s != null) s.main.post(() -> s.applySize(sizeDp)); }
    /** 기본 크기 120. 예전 기본값(170 등)으로 저장돼 있던 것은 한 번만 120으로 맞춘다. */
    static int sizeDp(SharedPreferences p) {
        if (!p.getBoolean("size120", false)) p.edit().putInt("size", 120).putBoolean("size120", true).apply();
        return p.getInt("size", 120);
    }
    /** 금융 앱(네이버페이·은행 등)이 접근성 앱을 '원격제어'로 막을 때: 나이아 접근성을 스스로 끈다. 다시 켜는 건 설정에서. */
    static boolean pauseForFinance() {
        NaiaService s = current; if (s == null) return false;
        s.main.post(() -> {
            android.widget.Toast.makeText(s, "나이아 접근성을 껐어요. 금융 앱을 다 쓰면 앱의 ②로 다시 켜 주세요", android.widget.Toast.LENGTH_LONG).show();
            s.disableSelf();
        });
        return true;
    }
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
    boolean facingRight;   // 마지막으로 걷던 방향(대기 때 계속 그쪽을 본다)
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
        px = Math.round(sizeDp(prefs) * dp);
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
        stage.post(() -> { placeDefault(); if (prefs.getBoolean("shown", false)) enter(); });
    }

    // ---- 알림창 카드 위치 기억 ----
    // 알림창(시스템 UI) 화면 구조에서 글자 칸의 위치만 읽어, 알림 제목 → 카드 테두리를 잠깐 기억한다.
    // 저장·전송하지 않고, 지워진 알림을 맞히는 데만 쓴다. 알림은 지워진 뒤에야 알려 주므로 직전 위치를 보관.
    static final class Seen { final RectF r; final long at; Seen(RectF a, long b) { r = a; at = b; } }
    final java.util.HashMap<String, Seen> cardAt = new java.util.HashMap<>();
    long scanAt; boolean scanQueued;
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null || !"com.android.systemui".contentEquals(e.getPackageName())) return;
        // 알림창의 '모두 지우기' 버튼을 누르는 순간 파도(알림이 사라지길 기다리지 않는다)
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && isClearAll(e)) { tideByTap = SystemClock.uptimeMillis(); callTide(null); return; }
        if (scanQueued) return;
        scanQueued = true;
        long wait = Math.max(0, 120 - (SystemClock.uptimeMillis() - scanAt));
        main.postDelayed(() -> { scanQueued = false; scanCards(); }, wait);
    }
    void scanCards() {
        scanAt = SystemClock.uptimeMillis();
        List<android.view.accessibility.AccessibilityWindowInfo> ws;
        try { ws = getWindows(); } catch (RuntimeException ex) { return; }
        float maxH = 150 * dp;
        for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
            android.view.accessibility.AccessibilityNodeInfo root;
            try { root = w.getRoot(); } catch (RuntimeException ex) { continue; }
            if (root == null || root.getPackageName() == null || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
            ArrayList<android.view.accessibility.AccessibilityNodeInfo> q = new ArrayList<>(); q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen < 1500) {
                android.view.accessibility.AccessibilityNodeInfo n = q.remove(q.size() - 1); seen++;
                CharSequence t = n.getText();
                if (t != null && t.length() > 0 && n.isVisibleToUser()) {
                    // 글자 칸에서 위로 올라가며, 키가 카드 한 장(150dp) 이하인 가장 큰 테두리 = 카드
                    Rect b = new Rect(); n.getBoundsInScreen(b);
                    android.view.accessibility.AccessibilityNodeInfo p = n.getParent();
                    for (int i = 0; p != null && i < 8; i++) {
                        Rect pb = new Rect(); p.getBoundsInScreen(pb);
                        if (pb.height() > maxH || pb.width() <= 0) break;
                        b = pb; p = p.getParent();
                    }
                    if (b.width() > 120 * dp) cardAt.put(t.toString(), new Seen(new RectF(b), scanAt));
                }
                for (int i = 0; i < n.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo ch = n.getChild(i); if (ch != null) q.add(ch); }
            }
        }
        // 10초 넘게 안 보인 카드는 잊는다
        for (Iterator<Seen> it = cardAt.values().iterator(); it.hasNext(); ) if (scanAt - it.next().at > 10000) it.remove();
    }
    /** 지워진 알림의 카드 테두리(모르면 null). 제목·내용 글자로 찾는다. */
    RectF cardOf(Gone g) {
        if (g == null) return null;
        Seen s = g.title.isEmpty() ? null : cardAt.get(g.title);
        if (s == null && !g.text.isEmpty()) s = cardAt.get(g.text);
        return s == null ? null : new RectF(s.r);
    }
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
    void applySize(int sizeDp) {
        int n = Math.round(sizeDp * dp); if (n == px || n <= 0) return;
        float bx = x + px * 0.5f, by = y + px;
        px = n; x = bx - px * 0.5f; y = by - px; clamp();
        if (mode == WALK) walkTo = Math.max(0, Math.min(W() - px, walkTo));
        gripLastX = -1; updateGrip(); stage.kick(); savePos();
    }
    void clamp() { x = Math.max(-px * 0.15f, Math.min(W() - px * 0.85f, x)); y = Math.max(0, Math.min(H() - px, y)); }
    void savePos() { prefs.edit().putFloat("x", x / W()).putFloat("y", y / H()).apply(); }

    // ---- 모션 재생 ----
    int loopFrom, loopEnd;   // 반복 구간(loopEnd=0이면 전체)
    void play(String m, boolean lp, int ms, Runnable end) {
        motion = m; loop = lp; frameMs = ms; onEnd = end; frame = 0; frameAt = SystemClock.uptimeMillis(); loopFrom = 0; loopEnd = 0;
        stage.kick();
    }
    boolean dnd;
    void setDnd(boolean on) { dnd = on; if (mode == IDLE || mode == WALK) toIdle(); }
    void toIdle() {
        mode = IDLE; mirror = facingRight;
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
        facingRight = mirror;
        mode = WALK; play("walk", true, 140, null);
        modeUntil = SystemClock.uptimeMillis() + 2000;   // 2초만 걷고 멈춘다(2초 대기 → 다시 2초 걷기)
    }

    // ---- 등장·퇴장 ----
    void enter() {
        if (sp == null || mode == ENTER) return;
        hideMenu();
        prefs.edit().putBoolean("shown", true).apply();
        if (mode != HIDDEN) return;
        placeDefault();   // 지금 화면 크기 기준으로 다시 놓는다(접힘·회전 뒤 화면 밖에 남아 있지 않게)
        mode = ENTER; mirror = false;
        puddle(0, 1, 300);
        motion = "enter"; frame = 0; frameAt = SystemClock.uptimeMillis() + 300; loop = false; frameMs = 73;   // 등장 1.5배 빠르게(110→73)
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
        main.postDelayed(() -> play("exit", false, 73, () -> {
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

    void shoot(int pos, Gone g) {
        if (busy() || SystemClock.uptimeMillis() - tideByTap < 3000) return;   // 모두 지우기로 파도가 이미 나갔으면 물총 생략
        RectF r = cardOf(g);
        float ty, tx;
        if (r != null) { tx = r.centerX(); ty = r.centerY(); }
        else { ty = notifY(pos); tx = cx() > W() / 2f ? W() * 0.40f : W() * 0.60f; }
        mirror = tx > cx();
        mode = ACTION;
        shotTarget = new PointF(tx, ty);
        shotAt = SystemClock.uptimeMillis();
        play("shoot", false, 80, this::toIdle);
        frame = 4;                       // 겨누기 끝 장면부터 바로 시작
        fireDrops(shotAt);               // 첫 물방울은 즉시
        // 지워진 알림을 다시 그리는 복사본 연출은 쓰지 않는다(사용자 요청 2026-11-15)
    }
    long shotAt;
    PointF shotTarget;
    /** 물총 그림에서 물을 쏘는 프레임(겨누기 이후 발사 구간). */
    boolean firingFrame(String m, int f) { return "shoot".equals(m) && f >= 5 && f <= 10; }

    long tideByTap;
    /** 누른 버튼이 '모두 지우기'인지: 버튼 글자·설명·화면 id만 본다 */
    static boolean isClearAll(AccessibilityEvent e) {
        StringBuilder b = new StringBuilder();
        for (CharSequence t : e.getText()) if (t != null) b.append(t).append(' ');
        if (e.getContentDescription() != null) b.append(e.getContentDescription()).append(' ');
        android.view.accessibility.AccessibilityNodeInfo n = e.getSource();
        if (n != null) { String id = n.getViewIdResourceName(); if (id != null) b.append(id); }
        String k = b.toString().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return k.contains("모두지우기") || k.contains("전체삭제") || k.contains("모두삭제") || k.contains("clearall")
                || k.contains("clear_all") || k.contains("dismiss_text") || k.contains("dismissall") || k.contains("dismiss_all");
    }
    void callTide(Gone g) {
        // 버튼을 누를 때 이미 파도를 보냈으면, 뒤이어 오는 '알림이 지워졌다' 소식으로 또 보내지 않는다
        if (g != null && SystemClock.uptimeMillis() - tideByTap < 3000) return;
        // 방금 하나 지우기로 쏘기 시작했는데 연달아 지워졌다면(=모두 지우기) 물총을 접고 파도로
        if (mode == ACTION && "shoot".equals(motion) && SystemClock.uptimeMillis() - shotAt < 600) {
            drops.clear(); mode = IDLE;
        }
        if (busy() || (mode == ACTION && "tide".equals(motion))) return;
        mode = ACTION;
        waveDir = facingRight ? 1 : -1;   // 바라보는 쪽(마지막 걷던 방향)으로 보낸다
        mirror = waveDir < 0;    // 파도 부르기 그림은 오른쪽으로 내밂 → 왼쪽으로 보낼 땐 뒤집는다
        play("tide", false, 110, this::toIdle);
        frame = 5;               // 알림은 이미 사라지는 중이라 기다리지 않고 바로 파도를 부른다
        wave = new Wave(SystemClock.uptimeMillis(), waveDir, cx() + waveDir * px * 0.25f, y + sp.foot * px);
    }
    int waveDir = 1;

    // ---- 지워진 알림 카드(다시 그려 맞고 사라지는 연출) ----
    final class Card {
        final RectF r; final Drawable icon; final String label, title, text; int kind; // 0 = 물총(젖어 찢어짐), 1 = 파도(휩쓸림)
        final long t0; long hitAt = Long.MAX_VALUE; float hitFront;
        Card(RectF a, Drawable i, String l, String t, String x, int k, long now) { r = a; icon = i; label = l; title = t; text = x; kind = k; t0 = now; }
    }
    final List<Card> cards = new ArrayList<>();
    Card card(Gone g, RectF r, int kind) {
        Drawable icon = null; String label = g.pkg;
        PackageManager pm = getPackageManager();
        try { label = pm.getApplicationLabel(pm.getApplicationInfo(g.pkg, 0)).toString(); icon = pm.getApplicationIcon(g.pkg); } catch (Exception ignored) {}
        if (icon == null && g.small != null) try { icon = g.small.loadDrawable(this); } catch (Exception ignored) {}
        stage.kick();
        return new Card(r, icon, label, g.title, g.text, kind, SystemClock.uptimeMillis());
    }
    float waveFront = Float.NaN;   // 지금 파도 앞머리의 화면 x(없으면 NaN)

    // ---- 효과 ----
    final class Drop { float x0, y0, x1, y1, sc; long t0, dur; Drop(float a, float b, float c, float d, float s, long t, long u) { x0 = a; y0 = b; x1 = c; y1 = d; sc = s; t0 = t; dur = u; } }
    final class Splat { float x, y, sc; long t0; boolean flip; Splat(float a, float b, float s, long t, boolean f) { x = a; y = b; sc = s; t0 = t; flip = f; } }
    final class Wave { long t0; int dir; float x0, ground; Wave(long t, int d, float a, float g) { t0 = t; dir = d; x0 = a; ground = g; } }
    final class Bubble { Drawable icon; String label, title, text; long t0; Bubble(Drawable i, String l, String a, String b, long t) { icon = i; label = l; title = a; text = b; t0 = t; } }
    final List<Drop> drops = new ArrayList<>(); final List<Splat> splats = new ArrayList<>(); Wave wave; Bubble bubble;
    void clearFx() { drops.clear(); splats.clear(); wave = null; bubble = null; cards.clear(); waveFront = Float.NaN; }

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
                int end = loop && loopEnd > 0 ? Math.min(n, loopEnd) : n;
                if (frame >= end) {
                    if (loop) frame = loopFrom;
                    else { frame = n - 1; Runnable r = onEnd; onEnd = null; frameAt = now; if (r != null) r.run(); break; }
                }
                if (firingFrame(motion, frame)) fireDrops(now);
            }
        }
        long dt = lastStepAt == 0 ? 16 : Math.min(50, now - lastStepAt); lastStepAt = now;
        if (mode == IDLE && now > modeUntil) startWalk();
        if (mode == WALK) {
            float speed = 0.7057f * px / 1000f * dt; // 걷기 그림 보폭에 맞춘 속도(초당 0.7배 몸 크기)
            float d = walkTo - x;
            if (Math.abs(d) <= speed || now > modeUntil) { if (Math.abs(d) <= speed) x = walkTo; savePos(); toIdle(); }
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

    boolean animating() { return mode != HIDDEN || puddleOn || !drops.isEmpty() || !splats.isEmpty() || wave != null || bubble != null || !cards.isEmpty(); }

    // ---- 그리기 ----
    final class Stage extends View {
        final Paint pix = new Paint(); final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tBold = new Paint(Paint.ANTI_ALIAS_FLAG), tLight = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect src = new Rect(); final RectF dst = new RectF();
        /** 폴드 접기·펴기, 회전으로 화면 크기가 바뀌면 같은 비율 위치로 옮기고 화면 안으로 넣는다 */
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (ow > 0 && oh > 0) { x = x * w / ow; y = y * h / oh; } else placeDefault();
            clamp();
            if (mode == WALK) walkTo = Math.max(0, Math.min(w - px, walkTo));
            gripLastX = -1; updateGrip(); kick();
        }
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
            drawCards(c, now);
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

        final Paint wavePaint = new Paint();   // 도트 그대로(보간 없음)
        void drawWave(Canvas c, long now, float k) {
            if (wave == null) return;
            long t = now - wave.t0;
            Bitmap ref = sp.fx.get("wave_k2");
            float scale = (px * 0.9f * 1.6f) / ref.getHeight();     // 나이아보다 크게
            float w2 = ref.getWidth() * scale;
            int dir = wave.dir;
            // 앞머리(진행 방향 끝) 위치: 등 뒤 화면 끝에서 솟아 → 바라보는 쪽 화면 끝까지 달리고,
            // 부서지는 동안에도 같은 속도로 계속 나아가 화면 밖으로 사라진다
            float start = dir > 0 ? -w2 * 0.4f : W() - w2 * 0.6f;
            float stop = dir > 0 ? W() - w2 : 0;
            float fStart = dir > 0 ? start + w2 : start, fStop = dir > 0 ? stop + w2 : stop;
            float travel = Math.abs(stop - start);
            long rise = 175, run = Math.max(233, (long) (travel / (W() * 1.35f) * 1000));   // 이전보다 1.5배 빠르게
            float v = travel / run;                                      // px/ms (달리기 끝 속도와 같게)
            long blend = 200, crash = Math.max(340, (long) (w2 * 1.15f / v));
            c.save(); c.clipRect(0, 0, W(), wave.ground + 4 * dp);
            if (t < rise) {
                Bitmap b = sp.fx.get("wave_k1");
                drawWaveFrame(c, b, fStart, dir, scale, 0, 255);
                waveFront = fStart;
            } else if (t < rise + run) {
                float u = (t - rise) / (float) run; u = u * u * (2 - u);   // 끝에서 속도 v로 이어지게
                float f = fStart + (fStop - fStart) * u;
                drawWaveFrame(c, sp.fx.get(((t / 75) % 2 == 0) ? "wave_k2" : "wave_k2b"), f, dir, scale, 0, 255);
                waveFront = f;
            } else if (t < rise + run + crash) {
                long a = t - rise - run;
                float f = fStop + dir * v * a;
                float sink = Math.min(1f, a / 470f) * 0.5f;
                Bitmap k3 = sp.fx.get("wave_k3");
                if (a < blend) {
                    // 말린 파도가 내려앉으며 부서진 파도로 겹쳐 바뀐다(뚝 바뀌지 않게)
                    float q = a / (float) blend; q = q * q * (3 - 2 * q);
                    Bitmap k2 = sp.fx.get(((t / 75) % 2 == 0) ? "wave_k2" : "wave_k2b");
                    float squash = 1 - q * (1 - (float) k3.getHeight() / k2.getHeight());
                    drawWaveFrameSquash(c, k2, f, dir, scale, squash, (int) (255 * (1 - q)));
                    drawWaveFrame(c, k3, f, dir, scale, sink, (int) (255 * q));
                } else drawWaveFrame(c, k3, f, dir, scale, sink, 255);
                waveFront = f;
            } else { wave = null; waveFront = Float.NaN; }
            c.restore();
        }
        void drawWaveFrame(Canvas c, Bitmap b, float front, int dir, float scale, float sink, int alpha) {
            if (b == null) return;
            float w = b.getWidth() * scale, h = b.getHeight() * scale;
            float top = wave.ground - h + sink * h * 0.8f;
            drawWaveRect(c, b, front, dir, w, top, h, alpha);
        }
        void drawWaveFrameSquash(Canvas c, Bitmap b, float front, int dir, float scale, float squash, int alpha) {
            if (b == null) return;
            float w = b.getWidth() * scale, h = b.getHeight() * scale * squash;
            drawWaveRect(c, b, front, dir, w, wave.ground - h, h, alpha);
        }
        void drawWaveRect(Canvas c, Bitmap b, float front, int dir, float w, float top, float h, int alpha) {
            float left = dir > 0 ? front - w : front;
            src.set(0, 0, b.getWidth(), b.getHeight());
            if (dir < 0) { c.save(); c.scale(-1, 1, left + w / 2, 0); }   // 파도 그림은 오른쪽으로 말림 → 왼쪽으로 갈 땐 뒤집음
            dst.set(left, top, left + w, top + h);
            wavePaint.setAlpha(alpha); c.drawBitmap(b, src, dst, wavePaint);
            if (dir < 0) c.restore();
        }

        /** 지워진 알림 카드: 물총은 젖었다가 지그재그로 찢어져 떨어지고, 파도는 흔들리다 휩쓸려 밀려난다. */
        void drawCards(Canvas c, long now) {
            for (Iterator<Card> it = cards.iterator(); it.hasNext(); ) {
                Card cd = it.next();
                if (now - cd.t0 > 4000) { it.remove(); continue; }
                if (cd.kind == 0) {
                    long a = now - cd.hitAt;
                    if (a < 0) { drawCard(c, cd, 0, 0, 0, 255, 0); continue; }
                    if (a < 220) { drawCard(c, cd, 0, 0, 0, 255, a / 220f); continue; }       // 젖는 중
                    float u = (a - 220) / 700f; if (u >= 1) { it.remove(); continue; }
                    int al = (int) (255 * (1 - u * u));
                    float fall = u * u * cd.r.height() * 3f, spread = u * cd.r.width() * 0.12f;
                    // 지그재그 찢긴 선(카드 가운데 세로)
                    Path left = new Path(), right = new Path(); float mid = cd.r.centerX(), zz = 7 * dp; int steps = 8;
                    left.moveTo(cd.r.left - 4, cd.r.top - 4); right.moveTo(cd.r.right + 4, cd.r.top - 4);
                    for (int i = 0; i <= steps; i++) { float yy = cd.r.top + cd.r.height() * i / steps, xx = mid + (i % 2 == 0 ? -zz : zz); left.lineTo(xx, yy); }
                    for (int i = 0; i <= steps; i++) { float yy = cd.r.top + cd.r.height() * i / steps, xx = mid + (i % 2 == 0 ? -zz : zz); right.lineTo(xx, yy); }
                    left.lineTo(cd.r.left - 4, cd.r.bottom + 4); left.close(); right.lineTo(cd.r.right + 4, cd.r.bottom + 4); right.close();
                    c.save(); c.translate(-spread, fall); c.rotate(-12 * u, cd.r.left, cd.r.bottom); c.clipPath(left); drawCard(c, cd, 0, 0, 0, al, 1); c.restore();
                    c.save(); c.translate(spread, fall * 1.15f); c.rotate(14 * u, cd.r.right, cd.r.bottom); c.clipPath(right); drawCard(c, cd, 0, 0, 0, al, 1); c.restore();
                } else {
                    float front = waveFront; int dir = wave != null ? wave.dir : 1;
                    if (cd.hitAt == Long.MAX_VALUE && !Float.isNaN(front) && (front - cd.r.centerX()) * dir >= 0) { cd.hitAt = now; cd.hitFront = front; }
                    if (cd.hitAt == Long.MAX_VALUE) {
                        if (wave == null && now - cd.t0 > 1500) { it.remove(); continue; }
                        // 파도가 다가올수록 흔들림이 커진다
                        float near = Float.isNaN(front) ? 0.2f : Math.max(0.2f, 1 - Math.abs(cd.r.centerX() - front) / W());
                        float rot = (float) Math.sin(now / 70.0) * 3f * near;
                        drawCard(c, cd, 0, (float) Math.sin(now / 90.0) * 2 * dp * near, rot, 255, 0.15f * near);
                        continue;
                    }
                    float u = (now - cd.hitAt) / 900f; if (u >= 1) { it.remove(); continue; }
                    float dx = Float.isNaN(front) ? dir * u * W() : front - cd.hitFront;     // 파도 앞머리와 함께 밀려남
                    float bob = (float) Math.sin(u * 12) * 8 * dp - u * 20 * dp;
                    float rot = dir * (8 + 10 * (float) Math.sin(u * 9)) * Math.min(1, u * 3);
                    drawCard(c, cd, dx, bob, rot, (int) (255 * (1 - u)), 0.6f);
                }
            }
        }
        final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG), wet = new Paint(Paint.ANTI_ALIAS_FLAG);
        void drawCard(Canvas c, Card cd, float dx, float dy, float rot, int alpha, float wetness) {
            RectF r = new RectF(cd.r); r.offset(dx, dy); float rad = Math.min(r.height() / 2, 22 * dp);
            c.save(); c.rotate(rot, r.centerX(), r.centerY());
            cardFill.setColor(0xFFFFFFFF); cardFill.setAlpha(alpha); c.drawRoundRect(r, rad, rad, cardFill);
            float pad = 14 * dp, ic = Math.min(30 * dp, r.height() - 2 * pad), tx = r.left + pad;
            if (cd.icon != null && ic > 0) { cd.icon.setBounds((int) tx, (int) (r.centerY() - ic / 2), (int) (tx + ic), (int) (r.centerY() + ic / 2)); cd.icon.setAlpha(alpha); cd.icon.draw(c); tx += ic + 10 * dp; }
            float tw = r.right - pad - tx;
            tBold.setAlpha(alpha); tLight.setAlpha(alpha);
            String head = cd.title.isEmpty() ? cd.label : cd.title;
            c.drawText(fit(head, tBold, tw), tx, r.centerY() - 2 * dp, tBold);
            c.drawText(fit(cd.text, tLight, tw), tx, r.centerY() + tLight.getTextSize(), tLight);
            tBold.setAlpha(255); tLight.setAlpha(255);
            if (wetness > 0) {   // 젖음: 푸른 물빛 + 물방울 자국
                wet.setColor(0xFF4FA8E0); wet.setAlpha((int) (alpha * 0.35f * wetness)); c.drawRoundRect(r, rad, rad, wet);
                wet.setColor(0xFF2E86C8); wet.setAlpha((int) (alpha * 0.55f * wetness));
                Random dr = new Random(cd.t0);
                for (int i = 0; i < 9; i++) { float ddx = r.left + dr.nextFloat() * r.width(), ddy = r.top + dr.nextFloat() * r.height(); c.drawCircle(ddx, ddy, (2 + dr.nextFloat() * 4) * dp, wet); }
            }
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
        // 방해 금지 중에는 깨우지 않고 자는 모습 그대로 자리만 옮긴다
        if (dnd) { mode = DRAG; if (!"sleep".equals(motion)) play("sleep", true, 130, null); return; }
        mode = DRAG; mirror = false;
        play("pull", true, 110, null);
        frame = 1;   // 첫 장(대기)은 건너뛰고 바로 허우적
        // 누르고 있는 동안은 허우적 구간(1~12번)만 반복: 앞뒤의 대기 장면으로 돌아가지 않는다
        loopFrom = 1; loopEnd = 13;
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
        String[] labels = {"설정 열기", "나이아 종료", "금융 앱 쓸 때 잠깐 끄기", "닫기"};
        Runnable[] acts = {
                () -> { hideMenu(); Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); },
                this::exit, () -> { hideMenu(); pauseForFinance(); }, this::hideMenu};
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

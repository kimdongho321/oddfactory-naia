package com.oddfactory.naia;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** 첫 화면: 나이아 시작·종료, 권한 상태, 크기 막대, 시험 알림, 버전·글꼴 고지. 앱을 여는 것만으로는 나오지 않고 '시작'을 눌러야 나온다. */
public final class MainActivity extends Activity {
    Typeface bold, light; float dp;
    TextView status; Button startBtn, stopBtn;
    SharedPreferences prefs;
    com.oddfactory.update.UpdateCheck appUpdates;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        appUpdates = new com.oddfactory.update.UpdateCheck(this, v -> {});
        prefs = getSharedPreferences(NaiaService.PREFS, MODE_PRIVATE);
        try { bold = getResources().getFont(R.font.maple_bold); light = getResources().getFont(R.font.maple_light); } catch (Exception ignored) {}
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * dp); l.setPadding(p, p * 2, p, p); l.setBackgroundColor(0xFFEAF6FA);
        TextView title = text("나이아 알림 청소", 26, true); title.setGravity(Gravity.CENTER); l.addView(title);
        TextView sub = text("알림을 밀어 지우면 물총, 모두 지우기는 파도!", 14, false); sub.setGravity(Gravity.CENTER); l.addView(sub);
        // 시작 · 종료를 나란히
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button("시작", 0xFF2FAE8F, v -> start());
        stopBtn = button("종료", 0xFFE07A6B, v -> stop());
        for (Button bt : new Button[]{startBtn, stopBtn}) {
            bt.setTextSize(22);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, (int) (72 * dp), 1f);
            half.setMarginEnd(bt == startBtn ? (int) (10 * dp) : 0);
            row.addView(bt, half);
        }
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(-1, -2); big.topMargin = (int) (20 * dp);
        l.addView(row, big);
        status = text("", 14, false); status.setPadding(0, (int) (14 * dp), 0, (int) (6 * dp)); l.addView(status);
        l.addView(button("① 알림 접근 허용하기", 0xFF5AA9D6, v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        l.addView(button("② 접근성에서 '나이아 알림 청소' 켜기", 0xFF5AA9D6, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        // 크기
        // 크기: 다른 앱들처럼 막대로, 끄는 동안 바로 반영
        final int MIN = 100, MAX = 280;
        int cur = Math.max(MIN, Math.min(MAX, NaiaService.sizeDp(prefs)));
        TextView sz = text("나이아 크기: " + cur, 15, true); sz.setPadding(0, (int) (14 * dp), 0, 0); l.addView(sz);
        SeekBar seek = new SeekBar(this); seek.setMax(MAX - MIN); seek.setProgress(cur - MIN);
        seek.setPadding((int) (8 * dp), (int) (10 * dp), (int) (8 * dp), (int) (10 * dp));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { sz.setText("나이아 크기: " + (p + MIN)); if (user) NaiaService.resize(p + MIN); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { prefs.edit().putInt("size", b.getProgress() + MIN).apply(); NaiaService.resize(b.getProgress() + MIN); }
        });
        l.addView(seek);
        l.addView(button("시험 알림 3개 보내기", 0xFF9DB0B8, v -> postTests()));
        // 금융 앱 호환: 네이버페이·은행 앱이 접근성 앱을 '원격제어 앱'으로 보고 막는 경우
        TextView fin = text("네이버페이·은행 앱에서 '원격제어 앱' 오류가 나면 아래 버튼으로 나이아 접근성을 잠깐 끄고 쓴 뒤, ②로 다시 켜 주세요.", 13, false);
        fin.setPadding(0, (int) (14 * dp), 0, 0); l.addView(fin);
        l.addView(button("금융 앱 쓸 때 잠깐 끄기", 0xFFB0868B, v -> {
            if (!NaiaService.pauseForFinance()) Toast.makeText(this, "지금은 접근성이 꺼져 있어요", Toast.LENGTH_SHORT).show();
            getWindow().getDecorView().postDelayed(this::refresh, 600);
        }));
        TextView help = text("나이아 조작: 톡 = 간지럼 · 꾹 눌러 끌기 = 볼 당겨 옮기기 · 두 번 톡 = 메뉴(설정·종료)", 13, false);
        help.setPadding(0, (int) (12 * dp), 0, 0); l.addView(help);
        l.addView(button("업데이트 확인 (현재 " + BuildConfig.VERSION_NAME + ")", 0xFF7C8CC4, v -> appUpdates.checkNow()));
        TextView ver = text("현재 버전 " + BuildConfig.VERSION_NAME + "\n글꼴: 메이플스토리 서체(넥슨) 사용", 12, false);
        ver.setTextColor(0xFF6A7A82); ver.setPadding(0, (int) (16 * dp), 0, 0); l.addView(ver);
        ScrollView sv = new ScrollView(this); sv.addView(l); setContentView(sv);
    }

    TextView text(String s, int sp, boolean b) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(b ? bold : light); t.setTextColor(0xFF24323A); return t; }
    Button button(String s, int color, View.OnClickListener c) {
        Button x = new Button(this); x.setText(s); x.setAllCaps(false); x.setTypeface(bold); x.setTextColor(0xFFFFFFFF); x.setTextSize(15);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(18 * dp); bg.setStroke((int) (2.5f * dp), 0xFF24323A); x.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = (int) (8 * dp); x.setLayoutParams(lp);
        x.setOnClickListener(c); return x;
    }

    boolean listenerOn() { String nl = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners"); return nl != null && nl.contains(getPackageName()); }

    @Override protected void onResume() {
        super.onResume();
        appUpdates.resume();
        getWindow().getDecorView().postDelayed(this::refresh, 400);
        refresh();
    }

    void refresh() {
        boolean a11y = NaiaService.current != null;
        status.setText("알림 접근: " + (listenerOn() ? "켜짐 ✔" : "꺼짐 — 아래 ①") + "\n화면 위 나이아(접근성): " + (a11y ? "켜짐 ✔" : "꺼짐 — 아래 ②"));
        boolean on = a11y && NaiaService.shown();
        startBtn.setAlpha(on ? 0.45f : 1f); stopBtn.setAlpha(on ? 1f : 0.45f);
        status.append("\n나이아: " +(on ? "나와 있음" : "쉬는 중"));
    }

    void start() {
        if (NaiaService.current == null) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); Toast.makeText(this, "'나이아 알림 청소'를 켜 주세요", Toast.LENGTH_LONG).show(); return; }
        if (!NaiaService.shown()) NaiaService.summon();
        getWindow().getDecorView().postDelayed(this::refresh, 300);
    }
    void stop() {
        if (NaiaService.shown()) NaiaService.dismiss();
        getWindow().getDecorView().postDelayed(this::refresh, 300);
    }

    void postTests() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); return;
        }
        NotificationManager n = getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel("test", "시험 알림", NotificationManager.IMPORTANCE_DEFAULT));
        String[] t = {"나이아의 물총 연습", "정령산 수문장 소식", "돌고래가 기다려요"};
        for (int k = 0; k < 3; k++) {
            n.notify(100 + k, new Notification.Builder(this, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(t[k]).setContentText("밀어서 지우면 물총, 모두 지우기는 파도").setGroup("t" + k).build());
        }
    }
    @Override protected void onPause() { appUpdates.pause(); super.onPause(); }
    @Override protected void onDestroy() { appUpdates.destroy(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); appUpdates.onActivityResult(request); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) appUpdates.onWindowFocus(); }
    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) postTests(); }
}

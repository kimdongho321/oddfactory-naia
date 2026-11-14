package com.oddfactory.naia;

import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.*;

/**
 * 알림 수신/제거를 받아 오버레이(NaiaService)로 넘긴다.
 * - 새 알림: 인사 + 말풍선
 * - 사용자가 하나 지움(REASON_CANCEL): 물총 (알림창 위에서 몇 번째였는지 함께)
 * - 모두 지우기(REASON_CANCEL_ALL, 또는 400ms 안에 연달아 CANCEL): 파도
 * 판정 방식은 probe 실기 시험(qa/logcat_NaiaProbe.txt)에서 확인한 것.
 */
public final class NaiaListener extends NotificationListenerService {
    static volatile NaiaListener current;
    /** 마지막으로 알고 있던 순서(알림창 위→아래 추정). 제거 콜백 시점엔 이미 빠진 뒤라 직전 값을 보관. */
    List<String> lastOrder = new ArrayList<>();
    long lastUser;

    @Override public void onListenerConnected() {
        current = this; try { remember(getCurrentRanking()); } catch (RuntimeException ignored) {}
        NaiaService.onDnd(getCurrentInterruptionFilter() > INTERRUPTION_FILTER_ALL);
    }
    /** 방해 금지 모드가 켜지면 졸기 */
    @Override public void onInterruptionFilterChanged(int f) { NaiaService.onDnd(f > INTERRUPTION_FILTER_ALL); }
    @Override public void onListenerDisconnected() { if (current == this) current = null; }

    void remember(RankingMap map) {
        if (map == null) return;
        Set<String> active = new HashSet<>();
        try { StatusBarNotification[] a = getActiveNotifications(); if (a != null) for (StatusBarNotification s : a) active.add(s.getKey()); }
        catch (RuntimeException ignored) {}
        List<String> order = new ArrayList<>();
        for (String k : map.getOrderedKeys()) if (active.isEmpty() || active.contains(k)) order.add(k);
        lastOrder = order;
    }

    int activeCount() {
        try { StatusBarNotification[] a = getActiveNotifications(); return a == null ? 0 : a.length; } catch (RuntimeException e) { return 0; }
    }

    @Override public void onNotificationRankingUpdate(RankingMap map) { remember(map); }

    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap map) {
        remember(map);
        if (sbn.isOngoing()) return;                               // 음악·다운로드 같은 상시 알림은 무시
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return; // 묶음 요약은 무시(개별 알림으로 이미 인사)
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        NaiaService.onPosted(sbn.getPackageName(), n.getSmallIcon(), n.getLargeIcon(),
                title == null ? "" : title.toString(), text == null ? "" : text.toString());
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap map, int reason) {
        List<String> before = lastOrder;
        int idx = before.indexOf(sbn.getKey());
        List<String> next = new ArrayList<>(before); next.remove(sbn.getKey()); lastOrder = next;
        if (reason != REASON_CANCEL && reason != REASON_CANCEL_ALL) return;
        long now = SystemClock.uptimeMillis();
        boolean burst = now - lastUser < 400; lastUser = now;
        // 지워진 알림을 화면에 다시 그려 맞고 사라지는 연출을 하려고 내용을 넘긴다
        Notification n = sbn.getNotification();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        NaiaService.Gone g = new NaiaService.Gone(sbn.getPackageName(), n.getSmallIcon(),
                title == null ? "" : title.toString(), text == null ? "" : text.toString());
        // 삼성 '지우기'는 보이는 알림을 CANCEL로 하나씩, 나머지를 CANCEL_ALL로 보낸다 → 연속 제거면 파도
        if (reason == REASON_CANCEL_ALL || burst) { NaiaService.onClearAll(g); return; }
        // 하나 지우기는 기다리지 않고 바로 쏜다(곧이어 연속 제거가 오면 서비스에서 파도로 바꾼다)
        NaiaService.onDismiss(idx, g);
    }
}

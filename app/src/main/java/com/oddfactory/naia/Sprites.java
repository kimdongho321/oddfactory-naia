package com.oddfactory.naia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** assets/frames/meta.json 의 모션 → 프레임 이름 목록과 효과 그림을 읽는다. 프레임은 필요할 때만 디코딩(LRU). */
final class Sprites {
    final Context ctx;
    final int size;                 // 프레임 한 변(px, 자산 기준)
    final float mouthX, mouthY, foot;
    final Map<String, String[]> motions = new HashMap<>();
    final Map<String, Bitmap> fx = new HashMap<>();
    final String puddleFrame;
    final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    Sprites(Context c) throws Exception {
        ctx = c.getApplicationContext();
        JSONObject m = new JSONObject(read("frames/meta.json"));
        size = m.getInt("size");
        JSONArray mo = m.getJSONArray("mouth");
        mouthX = (float) mo.getDouble(0); mouthY = (float) mo.getDouble(1);
        foot = (float) m.getDouble("foot");
        JSONObject ms = m.getJSONObject("motions");
        for (java.util.Iterator<String> it = ms.keys(); it.hasNext(); ) {
            String k = it.next(); JSONArray a = ms.getJSONArray(k);
            String[] f = new String[a.length()]; for (int i = 0; i < f.length; i++) f[i] = a.getString(i);
            motions.put(k, f);
        }
        JSONObject fo = m.getJSONObject("fx");
        puddleFrame = fo.getString("puddle");
        for (String k : new String[]{"drop", "splat", "wave_k1", "wave_k2", "wave_k2b", "wave_k3"}) fx.put(k, decode("fx/" + k + ".webp"));
    }

    String read(String path) throws Exception {
        try (InputStream in = ctx.getAssets().open(path)) {
            byte[] b = in.readAllBytes(); return new String(b, "UTF-8");
        }
    }

    Bitmap decode(String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inScaled = false;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) { return null; }
    }

    Bitmap frame(String name) {
        Bitmap b = cache.get(name);
        if (b == null) { b = decode("frames/" + name + ".webp"); if (b != null) cache.put(name, b); }
        return b;
    }

    int count(String motion) { String[] f = motions.get(motion); return f == null ? 0 : f.length; }
    Bitmap frame(String motion, int i) { String[] f = motions.get(motion); return f == null ? null : frame(f[Math.max(0, Math.min(f.length - 1, i))]); }
}

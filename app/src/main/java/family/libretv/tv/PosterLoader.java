package family.libretv.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;

/** Small, bounded cover loader. Images use the authenticated NAS proxy, never third-party cookies. */
final class PosterLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 队列需容纳整页海报（分类页 100 条），否则丢弃最早任务会导致中间卡片长时间空图。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardPolicy());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private boolean closed;
    interface Loaded { void onBitmap(Bitmap bitmap); }
    void load(ImageView view, NasClient client, String url) { load(view,client,url,R.drawable.poster_placeholder,null,false); }
    void loadPublic(ImageView view,String url,int placeholder) { load(view,null,url,placeholder,null,false); }
    void loadPublic(ImageView view,String url,int placeholder,Loaded callback) { load(view,null,url,placeholder,callback,false); }
    /** 轮播大图：放宽解码上限，避免海报被压成小图后放大发虚。 */
    void loadLarge(ImageView view,String url,int placeholder) { load(view,null,url,placeholder,null,true); }
    private void load(ImageView view,NasClient client,String url,int placeholder,Loaded callback,boolean large) {
        String key = (large?"large|":"") + (client==null?"public":client.origin) + "|" + url;
        view.setTag(key);
        view.setImageResource(placeholder);
        if (url.isEmpty() || closed) { if (callback != null) callback.onBitmap(null); return; }
        Bitmap saved = cache.get(key);
        if (saved != null) { view.setImageBitmap(saved); if (callback != null) callback.onBitmap(saved); return; }
        workers.execute(() -> {
            Bitmap bitmap = fetch(client, url, large);
            main.post(() -> {
                if (closed || bitmap == null) { if (callback != null) callback.onBitmap(null); return; }
                cache.put(key, bitmap);
                if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
                if (callback != null) callback.onBitmap(bitmap);
            });
        });
    }
    private Bitmap fetch(NasClient client, String url) { return fetch(client, url, false); }
    private Bitmap fetch(NasClient client, String url, boolean large) {
        HttpURLConnection connection = null;
        try {
            connection = client==null?LocalRepository.open(LocalRepository.validate(url)):client.open(client.proxy(url));
            String host=java.net.URI.create(url).getHost();
            if((client==null||client.local!=null) && host!=null && (host.equals("doubanio.com") || host.endsWith(".doubanio.com")))
                connection.setRequestProperty("Referer","https://movie.douban.com/");
            connection.setReadTimeout(12000);
            if (connection.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 5 * 1024 * 1024) return null;
                    out.write(buffer, 0, count);
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 20000 || bounds.outHeight > 20000) return null;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            int maxWidth = large ? 1080 : 480, maxHeight = large ? 1620 : 720;
            while (bounds.outWidth / options.inSampleSize > maxWidth || bounds.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | IllegalArgumentException ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    void close() { closed = true; workers.shutdownNow(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}

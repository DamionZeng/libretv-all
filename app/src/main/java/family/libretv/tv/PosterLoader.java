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
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private boolean closed;
    interface Loaded { void onBitmap(Bitmap bitmap); }
    void load(ImageView view, NasClient client, String url) { load(view,client,url,R.drawable.poster_placeholder,null,false); }
    void loadPublic(ImageView view,String url,int placeholder) { load(view,null,url,placeholder,null,false); }
    void loadPublic(ImageView view,String url,int placeholder,Loaded callback) { load(view,null,url,placeholder,callback,false); }
    /** 轮播大图：放宽解码上限，避免海报被压成小图后放大发虚。 */
    void loadLarge(ImageView view,String url,int placeholder) { load(view,null,url,placeholder,null,true,1080,1620); }
    /** 手机轮播：宽度够用即可，控制单张解码内存，避免多张轮播图相互挤出缓存。 */
    void loadHero(ImageView view,String url,int placeholder) { load(view,null,url,placeholder,null,true,720,1080,false); }
    private void load(ImageView view,NasClient client,String url,int placeholder,Loaded callback,boolean large) {
        load(view,client,url,placeholder,callback,large,large?1080:480,large?1620:720);
    }
    private void load(ImageView view,NasClient client,String url,int placeholder,Loaded callback,boolean large,int maxWidth,int maxHeight) {
        load(view,client,url,placeholder,callback,large,maxWidth,maxHeight,true);
    }
    private void load(ImageView view,NasClient client,String url,int placeholder,Loaded callback,boolean large,int maxWidth,int maxHeight,boolean resetPlaceholder) {
        String key = (large?"large"+maxWidth+"|":"") + (client==null?"public":client.origin) + "|" + url;
        view.setTag(key);
        // 轮播切图时保留上一张，避免每次自动轮换都闪回占位图。
        if (resetPlaceholder || view.getDrawable() == null) view.setImageResource(placeholder);
        if (url.isEmpty() || closed) { if (callback != null) callback.onBitmap(null); return; }
        Bitmap saved = cache.get(key);
        if (saved != null) { view.setImageBitmap(saved); if (callback != null) callback.onBitmap(saved); return; }
        workers.execute(() -> {
            Bitmap bitmap = fetch(client, url, large, maxWidth, maxHeight);
            main.post(() -> {
                if (closed || bitmap == null) { if (callback != null) callback.onBitmap(null); return; }
                cache.put(key, bitmap);
                if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
                if (callback != null) callback.onBitmap(bitmap);
            });
        });
    }
    private Bitmap fetch(NasClient client, String url) { return fetch(client, url, false, 480, 720); }
    private Bitmap fetch(NasClient client, String url, boolean large) { return fetch(client, url, large, large?1080:480, large?1620:720); }
    private Bitmap fetch(NasClient client, String url, boolean large, int maxWidth, int maxHeight) {
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
            while (bounds.outWidth / options.inSampleSize > maxWidth || bounds.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | IllegalArgumentException ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    void close() { closed = true; workers.shutdownNow(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}

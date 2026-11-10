package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** CMS 片库聚合：最近更新、分类映射与分页浏览。所有内容均来自用户已启用的源。 */
final class DiscoveryRepository {
    static final String[] SECTIONS = {"电影", "电视剧", "综艺", "动漫", "短剧", "纪录片"};
    private static final long HOME_TTL = 20 * 60 * 1000L, CLASS_TTL = 12 * 60 * 60 * 1000L;
    private final SharedPreferences prefs;
    private final LocalRepository repo;

    DiscoveryRepository(Context context, LocalRepository repo) {
        this.prefs = context.getApplicationContext().getSharedPreferences("discovery", 0);
        this.repo = repo;
    }

    /** 把 CMS 的 type_name 归一到首页使用的六个分类，顺序不可调换。 */
    static String section(String typeName) {
        String name = typeName == null ? "" : typeName;
        if (name.contains("短剧")) return "短剧";
        if (name.contains("纪录") || name.contains("纪錄") || name.contains("记录")) return "纪录片";
        if (name.contains("动漫") || name.contains("动画") || name.contains("動漫")) return "动漫";
        if (name.contains("综艺") || name.contains("綜藝") || name.contains("真人秀")) return "综艺";
        if (name.contains("电视剧") || name.contains("连续剧") || name.contains("劇集") || name.contains("剧集")
                || name.contains("美剧") || name.contains("英剧") || name.contains("韩剧") || name.contains("日剧")
                || name.contains("港剧") || name.contains("台剧") || name.contains("国产剧")) return "电视剧";
        if (name.contains("电影") || name.contains("電影") || name.contains("片")) return "电影";
        if (name.contains("剧") || name.contains("劇") || name.contains("戏剧") || name.contains("戲劇")) return "电视剧";
        return "";
    }

    JSONArray cachedRecent() {
        try { return new JSONArray(prefs.getString("recent2", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    boolean recentFresh() {
        long age = System.currentTimeMillis() - prefs.getLong("recent-at", 0);
        return age >= 0 && age < HOME_TTL && cachedRecent().length() > 0;
    }

    int enabledCount() throws Exception {
        return enabled().size();
    }

    /** 每个源拉取最新一页，按更新时间倒序并按片名去重。 */
    JSONArray recent(int limit) throws Exception {
        List<JSONObject> enabled = enabled();
        JSONArray all = fetchAll(enabled, source -> {
            String url = LocalRepository.api(source.getString("url"), "pg", "1");
            JSONArray items = new JSONArray();
            append(items, new JSONObject(LocalRepository.fetch(url)), source);
            return items;
        });
        List<JSONObject> sorted = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item == null) continue;
            String name = LocalRepository.normalize(item.optString("name"));
            if (name.isEmpty() || !names.add(name)) continue;
            sorted.add(item);
        }
        Collections.sort(sorted, (a, b) -> {
            int by = b.optString("vodTime").compareTo(a.optString("vodTime"));
            return by != 0 ? by : b.optString("name").compareTo(a.optString("name"));
        });
        JSONArray out = new JSONArray();
        for (int i = 0; i < sorted.size() && i < limit; i++) out.put(sorted.get(i));
        if (out.length() > 0) {
            prefs.edit().putString("recent2", out.toString()).putLong("recent-at", System.currentTimeMillis()).commit();
        }
        return out;
    }

    /** 分类页流式回调：added 为本批新增条目，total 为累计条数，pages 为已知总页数，done 表示全部来源结束。 */
    interface BrowseListener {
        void onBatch(JSONArray added, int total, int pages, boolean done);
    }
    private static final Map<String, Long> LATENCY = new ConcurrentHashMap<>();

    /** 分类聚合页（兼容旧调用）：等待全部来源完成后一次性返回。 */
    JSONObject browse(String section, int page) throws Exception {
        final JSONArray all = new JSONArray();
        final int[] pages = {1};
        browse(section, page, (added, total, pageCount, done) -> {
            pages[0] = Math.max(pages[0], pageCount);
            for (int i = 0; i < added.length(); i++) all.put(added.opt(i));
        });
        return new JSONObject().put("items", all).put("pages", pages[0]);
    }

    /** 先请求最快的来源并立即回调，其余来源后台补齐，边到边追加。 */
    void browse(String section, int page, BrowseListener listener) throws Exception {
        final int currentPage = Math.max(1, page);
        List<JSONObject> enabled = enabled();
        if (enabled.isEmpty()) throw new IOException("请先在设置中启用至少一个来源");
        Collections.sort(enabled, (a, b) -> Long.compare(speedOf(a), speedOf(b)));
        final Map<String, String> typeIds = typeIds(enabled, section);
        final AtomicInteger pages = new AtomicInteger(1);
        final Set<String> names = new HashSet<>();
        JSONObject primary = null;
        for (JSONObject source : enabled) if (typeIds.containsKey(source.optString("key"))) { primary = source; break; }
        if (primary == null) { listener.onBatch(new JSONArray(), 0, 1, true); return; }

        List<JSONObject> first = unique(fetchPage(primary, typeIds.get(primary.optString("key")), currentPage, pages), names);
        int total = first.size();
        List<JSONObject> rest = new ArrayList<>();
        for (JSONObject source : enabled)
            if (source != primary && typeIds.containsKey(source.optString("key"))) rest.add(source);
        listener.onBatch(toArray(first), total, pages.get(), rest.isEmpty());
        if (rest.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, rest.size()));
        CompletionService<JSONArray> ready = new ExecutorCompletionService<>(pool);
        Map<Future<JSONArray>, JSONObject> pending = new LinkedHashMap<>();
        try {
            for (JSONObject source : rest) {
                final String typeId = typeIds.get(source.optString("key"));
                pending.put(ready.submit(() -> fetchPage(source, typeId, currentPage, pages)), source);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
            while (!pending.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                Future<JSONArray> finished;
                try { finished = ready.poll(left, TimeUnit.NANOSECONDS); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
                if (finished == null) break;
                pending.remove(finished);
                JSONArray got;
                try { got = finished.get(); } catch (Exception ignored) { continue; }
                List<JSONObject> added = unique(got, names);
                if (added.isEmpty()) continue;
                total += added.size();
                listener.onBatch(toArray(added), total, pages.get(), false);
            }
        } finally {
            for (Future<?> future : pending.keySet()) future.cancel(true);
            pool.shutdownNow();
        }
        listener.onBatch(new JSONArray(), total, pages.get(), true);
    }

    private JSONArray fetchPage(JSONObject source, String typeId, int page, AtomicInteger pages) throws Exception {
        long started = System.currentTimeMillis();
        try {
            String url = LocalRepository.api(source.getString("url"), "t", typeId) + "&pg=" + page;
            JSONObject payload = new JSONObject(LocalRepository.fetch(url));
            pages.accumulateAndGet(Math.max(1, payload.optInt("pagecount", 1)), Math::max);
            JSONArray items = new JSONArray();
            append(items, payload, source);
            return items;
        } finally {
            long elapsed = System.currentTimeMillis() - started;
            LATENCY.merge(source.optString("key"), elapsed, (a, b) -> (a * 3 + b) / 4);
        }
    }

    private static List<JSONObject> unique(JSONArray items, Set<String> names) {
        List<JSONObject> added = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = LocalRepository.normalize(item.optString("name"));
            if (name.isEmpty() || !names.add(name)) continue;
            added.add(item);
        }
        return added;
    }

    private static JSONArray toArray(List<JSONObject> items) {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return out;
    }

    /** 并行解析各源的分类 type_id，超过 4 秒未返回的源本次跳过。 */
    private Map<String, String> typeIds(List<JSONObject> sources, String section) {
        Map<String, String> ids = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, sources.size())));
        CompletionService<String[]> ready = new ExecutorCompletionService<>(pool);
        Map<Future<String[]>, String> pending = new HashMap<>();
        try {
            for (JSONObject source : sources) {
                String key = source.optString("key");
                pending.put(ready.submit(() -> new String[]{key, typeId(source, section)}), key);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!pending.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                Future<String[]> finished;
                try { finished = ready.poll(left, TimeUnit.NANOSECONDS); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
                if (finished == null) break;
                pending.remove(finished);
                try {
                    String[] pair = finished.get();
                    if (pair != null && !pair[1].isEmpty()) ids.put(pair[0], pair[1]);
                } catch (Exception ignored) { }
            }
        } finally {
            for (Future<?> future : pending.keySet()) future.cancel(true);
            pool.shutdownNow();
        }
        return ids;
    }

    private String typeId(JSONObject source, String section) throws Exception {
        JSONArray classes = classes(source);
        for (int i = 0; i < classes.length(); i++) {
            JSONObject entry = classes.optJSONObject(i);
            if (entry != null && section.equals(entry.optString("section"))) return entry.optString("id");
        }
        return "";
    }

    private static long speedOf(JSONObject source) {
        Long value = LATENCY.get(source.optString("key"));
        return value == null ? Long.MAX_VALUE / 2 : value;
    }

    JSONArray related(String section, String excludeName, int limit) throws Exception {
        JSONArray items = browse(section, 1).optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items == null) return out;
        String skip = LocalRepository.normalize(excludeName == null ? "" : excludeName);
        for (int i = 0; i < items.length() && out.length() < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (!skip.isEmpty() && skip.equals(LocalRepository.normalize(item.optString("name")))) continue;
            out.put(item);
        }
        return out;
    }

    /** 读取并缓存源的分类表，用于把首页分类映射到 CMS 的 type_id。 */
    private JSONArray classes(JSONObject source) throws Exception {
        String key = source.getString("key");
        if (System.currentTimeMillis() - prefs.getLong("classes-at-" + key, 0) < CLASS_TTL) {
            try { return new JSONArray(prefs.getString("classes-" + key, "[]")); }
            catch (Exception ignored) { }
        }
        String url = source.getString("url");
        String payload = LocalRepository.fetch(url + (url.contains("?") ? "&" : "?") + "ac=list");
        JSONArray raw = new JSONObject(payload).optJSONArray("class");
        JSONArray out = new JSONArray();
        if (raw != null) for (int i = 0; i < raw.length(); i++) {
            JSONObject entry = raw.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("type_id").trim(), section = section(entry.optString("type_name"));
            if (id.isEmpty() || section.isEmpty()) continue;
            out.put(new JSONObject().put("id", id)
                    .put("name", entry.optString("type_name").trim()).put("section", section));
        }
        if (out.length() > 0) {
            prefs.edit().putString("classes-" + key, out.toString())
                    .putLong("classes-at-" + key, System.currentTimeMillis()).commit();
        }
        return out;
    }

    private List<JSONObject> enabled() throws Exception {
        JSONArray all = repo.sources();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject source = all.optJSONObject(i);
            if (source != null && source.optBoolean("enabled", true)) list.add(source);
        }
        return list;
    }

    private interface SourceTask { JSONArray run(JSONObject source) throws Exception; }

    private JSONArray fetchAll(List<JSONObject> sources, SourceTask task) throws Exception {
        if (sources.isEmpty()) throw new IOException("请先在设置中启用至少一个来源");
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, sources.size()));
        CompletionService<JSONArray> ready = new ExecutorCompletionService<>(pool);
        Map<Future<JSONArray>, JSONObject> pending = new HashMap<>();
        JSONArray all = new JSONArray();
        try {
            for (JSONObject source : sources) pending.put(ready.submit(() -> task.run(source)), source);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
            while (!pending.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                Future<JSONArray> done = ready.poll(left, TimeUnit.NANOSECONDS);
                if (done == null) break;
                pending.remove(done);
                try {
                    JSONArray got = done.get();
                    for (int i = 0; i < got.length(); i++) all.put(got.getJSONObject(i));
                } catch (Exception ignored) { }
            }
            return all;
        } finally {
            for (Future<?> future : pending.keySet()) future.cancel(true);
            pool.shutdownNow();
        }
    }

    private static void append(JSONArray out, JSONObject payload, JSONObject source) {
        JSONArray list = payload.optJSONArray("list");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject vod = list.optJSONObject(i);
            if (vod == null) continue;
            String name = vod.optString("vod_name").trim(), type = vod.optString("type_name");
            String id = vod.optString("vod_id").trim();
            if (name.isEmpty() || id.isEmpty() || type.matches(".*(伦理|倫理|福利|情色|成人|里番).*")) continue;
            try {
                out.put(new JSONObject()
                        .put("sourceKey", source.getString("key"))
                        .put("sourceName", source.getString("name"))
                        .put("sourceUrl", source.getString("url"))
                        .put("vodId", id)
                        .put("name", name)
                        .put("pic", vod.optString("vod_pic"))
                        .put("picWide", vod.optString("vod_pic_slide"))
                        .put("remarks", vod.optString("vod_remarks"))
                        .put("typeName", type)
                        .put("year", vod.optString("vod_year"))
                        .put("vodTime", vod.optString("vod_time")));
            } catch (Exception ignored) { }
        }
    }
}

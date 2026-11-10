package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;

/**
 * 豆瓣展示层：只提供片名、评分、年份、类型与海报等元数据，用于首页、片库和榜单的展示。
 * 点击条目后仍然走本地 CMS 搜索获取播放来源，因此这里不出现任何播放地址。
 */
final class CatalogRepository {
    static final String[] CATEGORIES = {"全部", "电影", "电视剧", "综艺", "动漫", "纪录片", "短剧"};
    static final String[] YEARS = {"全部", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018"};
    static final int SORT_TIME = 0, SORT_RATE = 1;
    static final String ALL = "全部";
    private static final long TTL = 4 * 60 * 60 * 1000L;
    private static final int PAGE_SIZE = 28, HOME_SIZE = 20, RANK_SIZE = 20;
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
    private final SharedPreferences prefs;

    CatalogRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("catalog", 0);
    }

    /** 分类对应的豆瓣入口：type 决定电影/剧集，tag 是分类标签。 */
    private static String type(String category) {
        return "短剧".equals(category) || "电影".equals(category) || ALL.equals(category) ? "movie" : "tv";
    }

    /** 「全部」同时查询电影与剧集两个豆瓣入口后再合并。 */
    private static boolean both(String category) { return ALL.equals(category); }

    private static String tag(String category) {
        switch (category) {
            case "综艺": return "综艺";
            case "动漫": return "动画";
            case "纪录片": return "纪录片";
            case "短剧": return "短片";
            default: return "";
        }
    }

    /** 首页数据：每个分类取综合排序前 20 条合并，分类字段写入 typeName 供首页分组。 */
    JSONArray home() throws Exception {
        final String[] categories = CATEGORIES;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<JSONArray>> pending = new ArrayList<>();
            for (String category : categories) pending.add(pool.submit(() -> homeRow(category)));
            JSONArray out = new JSONArray();
            for (int i = 0; i < categories.length; i++) {
                JSONArray part;
                try { part = pending.get(i).get(12, TimeUnit.SECONDS); }
                catch (Exception ignored) { part = cachedHomeRow(categories[i]); }
                for (int k = 0; k < Math.min(HOME_SIZE, part.length()); k++) out.put(part.opt(k));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 首页横排使用豆瓣综合排序，取前 20 条。缓存与片库分页分开，避免首页的 20 条覆盖片库第一页。 */
    private JSONArray homeRow(String category) throws Exception {
        JSONArray cached = cachedHomeRow(category);
        if (fresh(homeKey(category)) && cached.length() > 0) return cached;
        // sort=T（近期热度）在电影入口经常只返回几条，导致首页横排铺不满；综合排序稳定返回 20 条。
        String url = recommend(category, tag(category), "U", 0, HOME_SIZE);
        JSONArray out = parse(fetch(url), category);
        if (out.length() > 0) {
            prefs.edit().putString(homeKey(category), out.toString())
                    .putLong(homeKey(category) + "-at", System.currentTimeMillis()).apply();
        }
        return out.length() > 0 ? out : cached;
    }

    /** 已缓存的首页数据，用于打开应用时先渲染再刷新。 */
    JSONArray cachedHome() {
        JSONArray out = new JSONArray();
        for (String category : CATEGORIES) {
            JSONArray part = cachedHomeRow(category);
            for (int k = 0; k < Math.min(HOME_SIZE, part.length()); k++) out.put(part.opt(k));
        }
        return out;
    }

    boolean homeFresh() {
        for (String category : CATEGORIES) if (!fresh(homeKey(category))) return false;
        return true;
    }

    private static String homeKey(String category) {
        return "home3-" + category;
    }

    private JSONArray cachedHomeRow(String category) {
        try { return new JSONArray(prefs.getString(homeKey(category), "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    /** 一页片库数据、是否还有下一页以及总页数（由豆瓣 total 计算）。 */
    static final class Page {
        final JSONArray items;
        final boolean more;
        final int pages;
        Page(JSONArray items, boolean more, int pages) { this.items = items; this.more = more; this.pages = pages; }
    }

    /** 片库分页：不缓存、不做本地过滤，每次翻页/筛选都按参数向豆瓣重新请求。 */
    Page page(String category, String year, int sort, int pageNo) throws Exception {
        final int requested = Math.max(1, pageNo);
        final String order = sort == SORT_RATE ? "S" : "R";
        JSONArray out;
        boolean more;
        int pages;
        if (both(category)) {
            // 「全部」= 电影 + 剧集：两个豆瓣入口各取半页，交替合并；页码直接换算成豆瓣的 start 偏移。
            final int half = PAGE_SIZE / 2, start = (requested - 1) * half;
            String movieBody = fetch(recommend("电影", tags("电影", year), order, start, half));
            String tvBody = fetch(recommend("电视剧", tags("电视剧", year), order, start, half));
            out = interleave(parse(movieBody, "电影"), parse(tvBody, "电视剧"));
            pages = Math.max(pageCount(totalOf(movieBody), half), pageCount(totalOf(tvBody), half));
            more = requested < pages;
        } else {
            final int start = (requested - 1) * PAGE_SIZE;
            String body = fetch(recommend(category, tags(category, year), order, start, PAGE_SIZE));
            out = parse(body, category);
            int total = totalOf(body);
            if (total > 0) {
                pages = pageCount(total, PAGE_SIZE);
                more = requested < pages;
            } else {
                boolean hasNext = rawCount(body) >= PAGE_SIZE;
                pages = hasNext ? requested + 1 : requested;
                more = hasNext;
            }
        }
        return new Page(out, more, pages);
    }

    private static int pageCount(int total, int size) {
        return Math.max(1, (Math.max(0, total) + size - 1) / size);
    }

    /** 两个来源交替合并，避免「全部」第一页清一色电影。 */
    private static JSONArray interleave(JSONArray left, JSONArray right) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.max(left.length(), right.length()); i++) {
            JSONObject a = left.optJSONObject(i), b = right.optJSONObject(i);
            if (a != null) out.put(a);
            if (b != null) out.put(b);
        }
        return out;
    }

    /** 豆瓣接口的 total 字段，用于判断是否还有下一页。 */
    private static int totalOf(String body) {
        try {
            return new JSONObject(body).optInt("total", 0);
        } catch (JSONException ignored) {
            return 0;
        }
    }

    /** 豆瓣多标签按「年份,分类」顺序才同时生效，例如 2024,综艺。 */
    private static String tags(String category, String year) {
        String categoryTag = tag(category);
        boolean hasYear = year != null && !year.isEmpty() && !ALL.equals(year);
        if (!hasYear) return categoryTag;
        return categoryTag.isEmpty() ? year : year + "," + categoryTag;
    }

    private static String recommend(String category, String tag, String sort, int start, int count) throws Exception {
        return "https://m.douban.com/rexxar/api/v2/" + type(category) + "/recommend?refresh=0"
                + "&start=" + start
                + "&count=" + count
                + "&tags=" + URLEncoder.encode(tag, "UTF-8")
                + "&sort=" + sort
                + "&type=" + type(category);
    }

    /** 排行榜：豆瓣高分优先的电影，用于片库右侧榜单。 */
    JSONArray ranking() throws Exception {
        String key = "rank";
        if (fresh(key)) {
            JSONArray cached = new JSONArray(prefs.getString(key, "[]"));
            if (cached.length() > 0) return cached;
        }
        JSONArray out = parse(fetch("https://m.douban.com/rexxar/api/v2/movie/recommend?refresh=0&start=0"
                + "&count=" + RANK_SIZE + "&tags=&sort=S&type=movie"), "电影");
        if (out.length() > 0) {
            prefs.edit().putString(key, out.toString()).putLong(key + "-at", System.currentTimeMillis()).apply();
        }
        return out;
    }

    private JSONArray parse(String body, String category) {
        JSONArray out = new JSONArray();
        try {
            JSONObject payload = new JSONObject(body);
            JSONArray items = payload.optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject raw = items.optJSONObject(i);
                if (raw == null) continue;
                JSONObject item = parseItem(raw, category);
                if (item != null) out.put(item);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static final String[] GENRES = {"真人秀", "脱口秀", "纪录片", "动画", "剧情", "喜剧", "动作", "爱情",
            "科幻", "悬疑", "惊悚", "恐怖", "犯罪", "同性", "音乐", "歌舞", "传记", "历史", "战争", "西部", "奇幻",
            "冒险", "灾难", "武侠", "古装", "运动", "家庭", "儿童", "短片", "情色", "鬼怪", "青春", "校园", "职场",
            "都市", "励志", "生活", "美食", "旅行", "访谈", "综艺"};

    /** card_subtitle 第三段有时是类型、有时是导演，只有命中类型词才认作类型。 */
    private static String genreOf(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String genre : GENRES) {
            if (!text.contains(genre)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(genre);
        }
        return out.length() > 10 ? out.substring(0, 10) : out.toString();
    }

    private static JSONObject parseItem(JSONObject raw, String category) {
        String name = raw.optString("title").trim();
        JSONObject pic = raw.optJSONObject("pic");
        String cover = pic == null ? "" : pic.optString("normal", pic.optString("large"));
        // 豆瓣无海报时会给一张默认图，直接丢弃，避免片库出现白色占位卡。
        if (name.isEmpty() || cover.isEmpty() || cover.contains("_normal.png") || cover.contains("/frodo/")) return null;
        JSONObject rating = raw.optJSONObject("rating");
        String rate = rating == null ? "" : rating.optString("value");
        if ("0".equals(rate) || "0.0".equals(rate)) rate = "";
        String[] parts = raw.optString("card_subtitle").split("/");
        String year = parts.length > 0 ? parts[0].trim() : "";
        if (year.length() != 4 || !Character.isDigit(year.charAt(0))) return null;
        String genre = parts.length > 2 ? genreOf(parts[2].trim()) : "";
        if (genre.length() > 10) genre = genre.substring(0, 10);
        try {
            return new JSONObject()
                    .put("name", name)
                    .put("pic", poster(cover))
                    .put("rate", rate)
                    .put("year", year)
                    .put("genre", genre)
                    .put("doubanId", raw.optString("id"))
                    .put("typeName", category)
                    .put("sourceName", "豆瓣");
        } catch (JSONException e) {
            return null;
        }
    }

    /** 接口原始条目数，用于判断是否还有下一页（过滤掉无效海报后数量会变少）。 */
    private static int rawCount(String body) {
        try {
            JSONArray items = new JSONObject(body).optJSONArray("items");
            return items == null ? 0 : items.length();
        } catch (JSONException ignored) {
            return 0;
        }
    }

    private boolean fresh(String key) {
        long at = prefs.getLong(key + "-at", 0);
        return at > 0 && System.currentTimeMillis() - at < TTL;
    }

    /** 卡片用小图足够，轮播需要大图，因此按用途替换豆瓣的图片尺寸。 */
    static String poster(String cover) { return cover.replace("/s_ratio_poster/", "/l_ratio_poster/"); }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", MOBILE_UA);
        connection.setRequestProperty("Referer", "https://m.douban.com/movie/");
        connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("豆瓣 HTTP " + code);
            return LocalRepository.read(connection.getInputStream(), 4 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }
}

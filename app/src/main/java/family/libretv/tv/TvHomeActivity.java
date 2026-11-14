package family.libretv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** TV 片库首页：全幅轮播推荐 + 分类横排 + 分类聚合页，全部由 CMS 源驱动。 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TvHomeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService discoveryWorker = Executors.newFixedThreadPool(2);
    private final PosterLoader posters = new PosterLoader();
    private CatalogRepository catalog;
    private PlaybackStore history;
    private FrameLayout root;
    private LinearLayout body;
    private FrameLayout hero;
    private ScrollView scroller;
    private LinearLayout browseGrid, browseRow;
    private List<String> browseRowKeys;
    private int browseRowUsed;
    private TextView homeStatus, browseStatus, browseFooter;
    private boolean browseHasMore;
    private String page = "home", section = "电影", focusKey = "nav-voice";
    private String year = CatalogRepository.ALL;
    private int sort = CatalogRepository.SORT_TIME;
    private String rankCategory = "电影";
    private JSONArray ranking = new JSONArray();
    private boolean rankLoaded, rankLoading;
    private int rankToken;
    private int browsePage = 1;
    private JSONArray items = new JSONArray(), browseItems = new JSONArray(), heroSlides = new JSONArray();
    private int heroIndex, heroToken;
    private final Runnable heroTick = new Runnable() { @Override public void run() {
        if (dead || !started || !"home".equals(page) || heroSlides.length() < 2) return;
        moveHero(1); main.postDelayed(this, 6000);
    } };
    private final Map<String, View> targets = new HashMap<>();
    private final List<List<String>> focusRows = new ArrayList<>();
    private int generation, browseToken;
    private boolean dead, started, restoring, browseLoaded, browseLoading, browseFinished;
    private Future<?> pending, browsePending;
    private static final int BG = 0xff0e0f11, SURFACE = 0xff16181b, SURFACE_2 = 0xff1e2126;
    private static final int TEXT_1 = 0xfff3f4f6, TEXT_2 = 0xffa8aeb6, TEXT_3 = 0xff6d737b;
    private static final int ACCENT = 0xffe8a33d;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private StateListDrawable selectable(int normal) { return selectable(normal, 16); }
    private StateListDrawable selectable(int normal, int radius) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focused = rounded(SURFACE_2, radius); focused.setStroke(dp(2), ACCENT);
        d.addState(new int[]{android.R.attr.state_focused}, focused);
        d.addState(new int[]{}, rounded(normal, radius));
        return d;
    }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextColor(color); t.setTextSize(size); return t; }
    private TextView heading(String value, int size) { TextView t = text(value, size, TEXT_1); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        catalog = new CatalogRepository(this);
        history = new PlaybackStore(this);
        if (state != null) {
            page = state.getString("page", "home"); section = state.getString("section", "电影");
            focusKey = state.getString("focusKey", "hero"); browsePage = state.getInt("browsePage", 1);
        }
        if (state == null) applyPage(getIntent());
        UpdateActivity.checkInBackground(this);
    }
    /** 处理「片库 / 榜单 / 历史」等直达参数；复用已有实例时也走这里。 */
    private void applyPage(Intent intent) {
        String target = intent == null ? null : intent.getStringExtra("page");
        if ("history".equals(target)) { page = "history"; focusKey = "history-0"; }
        else if ("browse".equals(target)) {
            page = "browse"; focusKey = "browse-0"; browsePage = 1;
            browseItems = new JSONArray(); browseLoaded = false; browseLoading = false; browseHasMore = false;
        } else if ("rank".equals(target)) { page = "rank"; focusKey = "rank-0"; }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent != null && intent.hasExtra("page")) { applyPage(intent); if (started) render(); }
    }
    @Override protected void onResume() { super.onResume(); started = true; render(); }

    private void register(View view, String key, Runnable action) { register(view, key, action, SURFACE, 16); }
    private void register(View view, String key, Runnable action, int normal, int radius) {
        StateListDrawable ring = new StateListDrawable();
        GradientDrawable outline = rounded(0x00000000, radius); outline.setStroke(dp(2), ACCENT);
        ring.addState(new int[]{android.R.attr.state_focused}, outline);
        ring.addState(new int[]{}, rounded(0x00000000, radius));
        view.setForeground(ring);
        targets.put(key, view); view.setId(View.generateViewId());
        view.setFocusableInTouchMode(true); view.setFocusable(true); view.setClickable(true);
        view.setBackground(selectable(normal, radius));
        view.setOnClickListener(v -> { focusKey = key; action.run(); });
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused && !restoring) focusKey = key;
            v.setElevation(focused ? dp(8) : 0);
            if (v instanceof PosterCard) v.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(120).start();
        });
    }
    private Button button(LinearLayout parent, String label, String key, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{ACCENT, TEXT_2}));
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(14), 0, dp(14), 0);
        register(b, key, action, 0x00000000, 999);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40)); p.setMargins(dp(2), 0, dp(2), 0); parent.addView(b, p);
        return b;
    }
    private LinearLayout row(LinearLayout parent) { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); parent.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r; }
    /** 顶部导航图标项：搜索与语音用图标代替文字，进入搜索页后默认选中语音。 */
    private void navIcon(LinearLayout parent, int iconRes, String key, String label, Runnable action) {
        FrameLayout box = new FrameLayout(this);
        ImageView icon = new ImageView(this); icon.setImageResource(iconRes); icon.setDuplicateParentStateEnabled(true);
        icon.setImageTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{ACCENT, TEXT_2}));
        box.addView(icon, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        box.setContentDescription(label);
        register(box, key, action, 0x00000000, 999);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(40)); p.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(box, p);
    }
    private void open(String destination) { startActivity(new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).putExtra("tvDestination", destination)); }
    private void openDetail(JSONObject item) {
        if (item == null) return;
        if (!item.has("sourceKey")) { openSearch(item.optString("name")); return; }
        startActivity(new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .putExtra("tvDestination", "detail").putExtra("detailItem", item.toString()));
    }
    /** 片库条目没有片源，点击后带着片名进入搜索页，由现有搜索接口解析出可播放来源。 */
    private void openSearch(String keywords) {
        startActivity(new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .putExtra("tvDestination", "search").putExtra("query", keywords));
    }
    /** 语音搜索：进入搜索页后立即开始录音，不用先打字。 */
    private void openVoice() {
        startActivity(new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .putExtra("tvDestination", "voice"));
    }
    private void resume(JSONObject entry) {
        if (entry == null) return;
        startActivity(new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .putExtra("tvDestination", "resume").putExtra("resumeEntry", entry.toString()));
    }

    private void render() {
        if (dead) return;
        int ticket = ++generation;
        if (pending != null) pending.cancel(true);
        main.removeCallbacks(heroTick);
        String restore = focusKey;
        restoring = true; targets.clear(); focusRows.clear();
        items = catalog == null ? new JSONArray() : catalog.cachedHome();
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        List<String> navRow = new ArrayList<>();
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(48), 0, dp(48), 0);
        int[] headerShade = page.equals("home") ? new int[]{0xf20e0f11, 0xa60e0f11, 0x000e0f11}
                : new int[]{0xff0e0f11, 0xff0e0f11, 0xff0e0f11};
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, headerShade));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.spark_mark); mark.setContentDescription("星光影院");
        header.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView wordmark = heading("星光影院", 21); wordmark.setLetterSpacing(0.02f); wordmark.setPadding(dp(10), 0, dp(26), 0); header.addView(wordmark);
        button(header, "首页", "nav-home", () -> { page = "home"; focusKey = "nav-voice"; render(); }); navRow.add("nav-home");
        button(header, "片库", "nav-library", () -> openBrowse(section)); navRow.add("nav-library");
        button(header, "榜单", "nav-rank", this::openRank); navRow.add("nav-rank");
        navIcon(header, R.drawable.ic_nav_search, "nav-search", "搜索", () -> open("search")); navRow.add("nav-search");
        navIcon(header, R.drawable.ic_nav_mic, "nav-voice", "语音搜索", this::openVoice); navRow.add("nav-voice");
        TextView time = text(new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 14, TEXT_3);
        time.setGravity(Gravity.END); time.setPadding(0, 0, dp(18), 0); header.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        button(header, "历史", "nav-history", () -> { page = "history"; focusKey = "history-0"; render(); }); navRow.add("nav-history");
        button(header, "设置", "nav-settings", () -> open("settings")); navRow.add("nav-settings");
        focusRows.add(navRow);
        String activeKey = page.equals("history") ? "nav-history" : page.equals("browse") ? "nav-library"
                : page.equals("rank") ? "nav-rank" : "nav-home";
        View active = targets.get(activeKey);
        if (active != null) active.setBackground(selectable(0x1fe8a33d, 999));
        hero = null;
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroller = scroll;
        if (!page.equals("home")) scroll.setPadding(0, dp(76), 0, 0);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        if (page.equals("home")) {
            hero = new FrameLayout(this);
            content.addView(hero, new LinearLayout.LayoutParams(-1, heroHeight()));
        }
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(48), dp(6), dp(48), dp(6));
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("home")) {
            final boolean[] solid = {false};
            scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
                boolean next = y >= Math.max(0, heroHeight() - dp(76));
                if (next == solid[0]) return;
                solid[0] = next;
                if (next) header.setBackgroundColor(BG);
                else header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, headerShade));
            });
        } else if (page.equals("browse")) {
            // 滚动（含方向键带动的滚动）接近底部时自动请求下一页，替代原来的分页按钮。
            scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
                View child = scroll.getChildAt(0);
                if (child == null) return;
                if (y + scroll.getHeight() >= child.getHeight() - dp(420)) loadMoreBrowse();
            });
        }
        if (page.equals("history")) renderHistory();
        else if (page.equals("browse")) renderBrowse();
        else if (page.equals("rank")) renderRank();
        else renderHome();
        wireFocus();
        TextView help = text("方向键选择  ·  确认进入  ·  返回键返回", 12, TEXT_3);
        help.setPadding(dp(48), dp(8), dp(48), dp(10)); column.addView(help);
        root.addView(header, new FrameLayout.LayoutParams(-1, dp(76), Gravity.TOP));
        setContentView(root);
        root.post(() -> {
            if (dead || ticket != generation) return;
            View target = targets.get(restore);
            if (target == null) target = targets.get(page.equals("browse") ? "browse-0"
                    : page.equals("rank") ? "rank-0" : page.equals("history") ? "history-0" : "hero");
            if (target == null) target = targets.get("nav-home");
            if (target != null) target.requestFocusFromTouch();
            restoring = false;
            if (page.equals("browse")) autoLoadIfShort();
        });
        if (page.equals("home") && catalog != null) loadHome(ticket);
        if (page.equals("rank") && catalog != null && !rankLoaded && !rankLoading) loadRank(ticket);
        if (page.equals("browse") && catalog != null && !browseLoaded && !browseLoading) loadBrowse(ticket);
    }

    private int heroHeight() {
        int screen = getResources().getConfiguration().screenHeightDp;
        return dp(Math.max(260, Math.round(screen * 0.68f)));
    }
    private void renderHome() {
        homeStatus = null;
        heroSlides = new JSONArray();
        for (int i = 0; i < Math.min(6, items.length()); i++) {
            JSONObject slide = items.optJSONObject(i);
            if (slide != null) heroSlides.put(slide);
        }
        if (heroIndex >= heroSlides.length()) heroIndex = 0;
        if (hero != null) {
            register(hero, "hero", this::openHero, 0x00000000, 0);
            hero.setOnKeyListener((v, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { moveHero(-1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { moveHero(1); return true; }
                return false;
            });
            showHero();
            focusRows.add(Collections.singletonList("hero"));
            main.removeCallbacks(heroTick); main.postDelayed(heroTick, 6000);
        }
        boolean any = false;
        int columns = cardColumns();
        for (String name : DiscoveryRepository.SECTIONS) {
            JSONArray group = group(items, name);
            if (group.length() == 0) continue;
            any = true;
            addRowTitle("热播" + name, name);
            addCardRow(group, columns, "sec-" + name + "-");
        }
        if (!any) {
            homeStatus = text(catalog == null ? "展示数据初始化失败" : "正在读取榜单…", 16, TEXT_2);
            homeStatus.setPadding(dp(4), dp(30), dp(4), dp(4)); body.addView(homeStatus);
        }
    }
    private JSONArray group(JSONArray all, String name) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && name.equals(DiscoveryRepository.section(item.optString("typeName")))) out.put(item);
        }
        return out;
    }
    /** 横排标题行：左侧标题，右侧「查看全部」入口。 */
    private void addRowTitle(String title, String moreSection) {
        LinearLayout titleRow = row(body); titleRow.setPadding(0, dp(22), 0, dp(6)); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(heading(title, 24), new LinearLayout.LayoutParams(0, -2, 1));
        if (moreSection != null) {
            button(titleRow, "查看全部 →", "more-" + moreSection, () -> openBrowse(moreSection));
            List<String> keys = new ArrayList<>(); keys.add("more-" + moreSection); focusRows.add(keys);
        }
    }
    private void moveHero(int delta) {
        if (heroSlides.length() < 2) return;
        heroIndex = (heroIndex + delta + heroSlides.length()) % heroSlides.length();
        showHero();
        main.removeCallbacks(heroTick); if (started) main.postDelayed(heroTick, 6000);
    }
    private void openHero() {
        JSONObject item = heroSlides.optJSONObject(heroIndex);
        if (item != null) openDetail(item); else open("search");
    }
    private TextView chip(String value) {
        TextView chip = text(value, 12, TEXT_1);
        GradientDrawable bg = rounded(0x33ffffff, 999); bg.setStroke(dp(1), 0x40ffffff);
        chip.setBackground(bg); chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); chip.setLayoutParams(lp);
        return chip;
    }
    /** 采集服务只提供竖版海报，轮播按中心裁切铺满 16:9 区域。 */
    private void showHero() {
        if (hero == null) return;
        hero.removeAllViews();
        JSONObject item = heroSlides.optJSONObject(heroIndex);
        if (item == null) return;
        final int token = ++heroToken;
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(image, new FrameLayout.LayoutParams(-1, -1));
        posters.loadLarge(image, item.optString("pic"), R.drawable.poster_placeholder);
        if (token != heroToken) return;
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xe60e0f11, 0x800e0f11, 0x000e0f11}));
        hero.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xe60e0f11, 0x000e0f11}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setGravity(Gravity.BOTTOM);
        copy.setPadding(dp(64), dp(24), dp(240), dp(60)); hero.addView(copy, new FrameLayout.LayoutParams(-1, -1));
        String label = DiscoveryRepository.section(item.optString("typeName"));
        if (label.isEmpty()) label = item.optString("typeName");
        if (label.isEmpty()) label = "推荐";
        TextView eyebrow = text("▍" + label, 14, ACCENT); eyebrow.setLetterSpacing(0.12f); copy.addView(eyebrow);
        TextView name = heading(item.optString("name"), 40); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(8), 0, dp(10)); copy.addView(name);
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL);
        String rate = item.optString("rate");
        meta.addView(chip(rate.isEmpty() ? "推荐" : rate));
        copy.addView(meta);
        TextView action = heading("▶   查看详情", 15);
        action.setBackground(rounded(ACCENT, 999)); action.setTextColor(0xff1a1206); action.setLetterSpacing(0.02f);
        action.setPadding(dp(22), dp(11), dp(22), dp(11));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2); ap.topMargin = dp(18); copy.addView(action, ap);
        LinearLayout dots = new LinearLayout(this); dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < heroSlides.length(); i++) {
            View dot = new View(this); GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL);
            d.setColor(i == heroIndex ? ACCENT : 0x66f3f4f6); dot.setBackground(d);
            int size = i == heroIndex ? 9 : 7;
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(size), dp(size));
            dl.setMargins(dp(4), 0, dp(4), 0); dots.addView(dot, dl);
        }
        FrameLayout.LayoutParams dotsParams = new FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM);
        dotsParams.bottomMargin = dp(22); hero.addView(dots, dotsParams);
        hero.setContentDescription("推荐 · " + item.optString("name") + "，" + (heroIndex + 1) + "/" + heroSlides.length());
        hero.setAlpha(0f); hero.animate().alpha(1f).setDuration(320).start();
    }
    private int cardWidthDp(int columns) {
        int gap = 10;
        // 卡片左右各 5dp 外边距，合计 10dp；按整行可用宽度均分，保证横排两端对齐、铺满宽度。
        return Math.max(80, (getResources().getConfiguration().screenWidthDp - 96) / columns - gap);
    }
    private int cardColumns() {
        return getResources().getConfiguration().screenWidthDp >= 1000 ? 8 : 7;
    }
    private LinearLayout.LayoutParams cardParams(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), -2);
        lp.setMargins(dp(5), dp(4), dp(5), dp(8)); return lp;
    }
    private PosterCard posterCard(JSONObject item, String title, String subtitle, String metadata, String key, Runnable action, int widthDp) {
        int artDp = Math.round(widthDp * 3f / 2f);
        PosterCard card = new PosterCard(this, dp(artDp));
        card.setLines(title, subtitle, metadata);
        card.setContentDescription(title + "，" + subtitle);
        posters.loadPublic(card.image, item == null ? "" : item.optString("pic"), R.drawable.poster_placeholder);
        register(card, key, action, SURFACE, 16);
        return card;
    }
    /** 海报右上角展示类型（取元数据类型的第一个），没有类型时退回分类。 */
    private static String cardBadge(JSONObject item) {
        String genre = item.optString("genre");
        if (!genre.isEmpty()) return genre.split(" ")[0];
        return item.optString("typeName");
    }
    private void addCardRow(JSONArray list, int max, String keyPrefix) {
        // 条目不足一屏时按实际条数分列，卡片自动变宽，整行始终铺满宽度（不留「更多」占位骨架）。
        int columns = Math.max(1, Math.min(max, list.length())), width = cardWidthDp(columns);
        int count = Math.min(columns, list.length());
        LinearLayout row = row(body); List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String key = keyPrefix + i;
            PosterCard card = posterCard(item, item.optString("name"), "", "", key, () -> openDetail(item), width);
            card.setScore(item.optString("rate"));
            card.setCategory(cardBadge(item));
            row.addView(card, cardParams(width)); keys.add(key);
        }
        if (!keys.isEmpty()) focusRows.add(keys);
    }
    private void loadHome(int ticket) {
        if (catalog == null) return;
        if (catalog.homeFresh() && items.length() > 0) return;
        pending = discoveryWorker.submit(() -> {
            try {
                JSONArray fresh = catalog.home();
                main.post(() -> {
                    if (dead || ticket != generation || !started || !page.equals("home")) return;
                    if (fresh.toString().equals(items.toString())) return;
                    items = fresh; render();
                });
            } catch (Exception e) {
                main.post(() -> { if (!dead && ticket == generation && page.equals("home")) status("片库榜单暂时无法加载"); });
            }
        });
    }
    private void status(String message) {
        if (homeStatus == null) {
            homeStatus = text("", 15, TEXT_2); homeStatus.setPadding(dp(4), dp(18), dp(4), dp(4)); body.addView(homeStatus);
        }
        homeStatus.setText(message == null ? "片库暂时无法加载" : message);
    }

    private void openBrowse(String name) {
        if (browsePending != null) browsePending.cancel(true);
        section = name == null || name.isEmpty() ? "电影" : name;
        page = "browse"; browsePage = 1; browseItems = new JSONArray();
        browseHasMore = false;
        browseLoaded = false; browseLoading = false; browseFinished = false; focusKey = "browse-0"; render();
    }
    private void filterCategory(String name) {
        if (name.equals(section)) return;
        section = name; reloadBrowse();
    }
    private void filterYear(String value) {
        if (value.equals(year)) return;
        year = value; reloadBrowse();
    }
    private void filterSort(int value) {
        if (value == sort) return;
        sort = value; reloadBrowse();
    }
    private void reloadBrowse() {
        if (browsePending != null) browsePending.cancel(true);
        browsePage = 1; browseItems = new JSONArray();
        browseHasMore = false;
        browseLoaded = false; browseLoading = false; browseFinished = false; focusKey = "browse-0"; render();
    }
    private void renderBrowse() {
        LinearLayout titleRow = row(body); titleRow.setGravity(Gravity.CENTER_VERTICAL); titleRow.setPadding(0, dp(4), 0, dp(8));
        titleRow.addView(heading("片库", 30), new LinearLayout.LayoutParams(0, -2, 1));
        browseStatus = text("", 13, TEXT_3);
        titleRow.addView(browseStatus);
        filterRow("类型", CatalogRepository.CATEGORIES, section, this::filterCategory);
        filterRow("排序", new String[]{"按时间", "按评分"},
                sort == CatalogRepository.SORT_RATE ? "按评分" : "按时间",
                value -> filterSort("按评分".equals(value) ? CatalogRepository.SORT_RATE : CatalogRepository.SORT_TIME));
        filterRow("年份", CatalogRepository.YEARS, year, this::filterYear);
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, dp(10), 0, 0);
        body.addView(left, new LinearLayout.LayoutParams(-1, -2));
        browseGrid = null; browseRow = null; browseRowKeys = null; browseRowUsed = 0;
        if (browseItems.length() == 0) {
            if (browseFinished) {
                LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
                box.setPadding(0, dp(56), 0, dp(56)); box.setBackground(rounded(SURFACE, 18));
                left.addView(box, new LinearLayout.LayoutParams(-1, -2));
                TextView hint = heading("该筛选下暂时没有内容", 20);
                hint.setGravity(Gravity.CENTER); box.addView(hint);
            } else {
                renderBrowseSkeleton(left);
            }
        } else {
            browseGrid = new LinearLayout(this); browseGrid.setOrientation(LinearLayout.VERTICAL);
            left.addView(browseGrid, new LinearLayout.LayoutParams(-1, -2));
            appendBrowseCards(0);
        }
        browseFooter = text("", 13, TEXT_3);
        browseFooter.setPadding(0, dp(10), 0, dp(6));
        left.addView(browseFooter);
        updateBrowseStatus();
    }
    /** 列表底部提示：滚动到末尾时自动请求片库下一页，无需点击分页按钮。 */
    private void updateBrowseFooter() {
        if (browseFooter == null) return;
        if (browseItems.length() == 0) browseFooter.setText("");
        else if (browseLoading) browseFooter.setText("正在加载更多…");
        else if (browseHasMore) browseFooter.setText("继续下滑自动加载更多");
        else browseFooter.setText("已加载全部 " + browseItems.length() + " 部");
    }
    /** 片库顶部的一行筛选胶囊，单选。 */
    private void filterRow(String label, String[] values, String current, java.util.function.Consumer<String> pick) {
        LinearLayout row = row(body); row.setPadding(0, dp(1), 0, dp(1)); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(label, 13, TEXT_3);
        row.addView(caption, new LinearLayout.LayoutParams(dp(48), -2));
        List<String> keys = new ArrayList<>();
        for (String value : values) {
            boolean selected = value.equals(current);
            String key = "filter-" + label + "-" + value;
            Button chip = new Button(this); chip.setText(value); chip.setAllCaps(false); chip.setTextSize(14);
            chip.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{selected ? ACCENT : TEXT_2, selected ? ACCENT : TEXT_2}));
            chip.setMinWidth(0); chip.setMinimumWidth(0); chip.setMinHeight(0); chip.setMinimumHeight(0);
            chip.setPadding(dp(14), 0, dp(14), 0);
            register(chip, key, () -> pick.accept(value), selected ? 0x1fe8a33d : 0x00000000, 999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(dp(3), dp(2), dp(3), dp(2)); row.addView(chip, lp);
            keys.add(key);
        }
        focusRows.add(keys);
    }
    private void openRank() {
        page = "rank"; focusKey = "rank-0"; render();
    }
    /** 独立榜单页：按分类切换，热播优先，双列展示，点击进入搜索。 */
    private void renderRank() {
        LinearLayout titleRow = row(body); titleRow.setPadding(0, dp(8), 0, dp(6)); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(heading("榜单", 30), new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(text("热播优先", 13, TEXT_3));
        LinearLayout tabs = row(body); tabs.setPadding(0, 0, 0, dp(10));
        for (String name : RANK_CATEGORIES) {
            final String category = name;
            Button tab = button(tabs, name, "rankcat-" + name, () -> switchRank(category));
            if (name.equals(rankCategory)) tab.setTextColor(ACCENT);
        }
        if (ranking.length() == 0) {
            body.addView(text(rankLoaded ? rankCategory + "榜单暂时无法加载" : "正在加载" + rankCategory + "榜单…", 16, TEXT_2));
            return;
        }
        int total = Math.min(20, ranking.length()), half = (total + 1) / 2;
        LinearLayout columns = new LinearLayout(this); columns.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout leftCol = new LinearLayout(this); leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this); rightCol.setOrientation(LinearLayout.VERTICAL);
        columns.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 1));
        columns.addView(rightCol, new LinearLayout.LayoutParams(0, -2, 1));
        for (int i = 0; i < total; i++) addRankRow(i < half ? leftCol : rightCol, i, ranking.optJSONObject(i));
    }
    private void addRankRow(LinearLayout parent, int index, JSONObject item) {
        if (item == null) return;
        final String keywords = item.optString("name");
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(dp(16), 0, dp(16), 0);
        line.addView(text(String.valueOf(index + 1), 16, index < 3 ? ACCENT : TEXT_3), new LinearLayout.LayoutParams(dp(42), -2));
        TextView name = text(keywords, 17, TEXT_1); name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        String yearText = item.optString("year");
        if (!yearText.isEmpty()) line.addView(text(yearText, 13, TEXT_3));
        String rate = item.optString("rate");
        if (!rate.isEmpty()) {
            TextView value = text(rate, 17, ACCENT); value.setPadding(dp(14), 0, dp(4), 0); line.addView(value);
        }
        register(line, "rank-" + index, () -> openSearch(keywords), SURFACE, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(dp(4), dp(3), dp(4), dp(3)); parent.addView(line, lp);
        focusRows.add(Collections.singletonList("rank-" + index));
    }
    private static final String[] RANK_CATEGORIES = {"电影", "电视剧", "综艺", "动漫", "纪录片", "短剧"};

    /** 切换榜单分类：清空旧数据并重新拉取，焦点停留在刚选中的分类上。 */
    private void switchRank(String category) {
        if (category.equals(rankCategory)) return;
        rankCategory = category;
        ranking = new JSONArray();
        rankLoaded = false;
        rankLoading = false;
        focusKey = "rankcat-" + category;
        render();
    }
    private void loadRank(int ticket) {
        if (catalog == null) return;
        rankLoading = true;
        final int token = ++rankToken;
        discoveryWorker.submit(() -> {
            try {
                JSONArray fresh = catalog.ranking(rankCategory);
                main.post(() -> {
                    if (dead || token != rankToken || !page.equals("rank")) return;
                    ranking = fresh; rankLoaded = true; rankLoading = false; render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    rankLoading = false;
                    if (dead || token != rankToken || !page.equals("rank")) return;
                    rankLoaded = true; render();
                });
            }
        });
    }
    private int browseColumns() { return cardColumns(); }
    private int browseCardWidth() { return cardWidthDp(browseColumns()); }
    /** 加载中的骨架占位，避免片库只显示一句「正在加载」。 */
    private void renderBrowseSkeleton(LinearLayout parent) {
        int columns = browseColumns(), width = browseCardWidth();
        int posterHeight = Math.round(width * 3f / 2f);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = row(parent);
            for (int c = 0; c < columns; c++) {
                View placeholder = new View(this); placeholder.setBackground(rounded(SURFACE, 16));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(posterHeight + 62));
                lp.setMargins(dp(5), dp(4), dp(5), dp(8)); row.addView(placeholder, lp);
            }
        }
    }
    /** 从 from 开始把已加载条目补进卡片网格，行满则新起一行（已有卡片位置不变）。 */
    private void appendBrowseCards(int from) {
        if (browseGrid == null) return;
        for (int i = from; i < browseItems.length(); i++) {
            JSONObject item = browseItems.optJSONObject(i);
            if (item != null) addBrowseCard(i, item);
        }
    }
    /** 把一部片子放进当前行，行满则新起一行（保持已有卡片位置不变）。 */
    private void addBrowseCard(int index, JSONObject item) {
        if (browseGrid == null) return;
        int columns = browseColumns(), width = browseCardWidth();
        if (browseRow == null || browseRowUsed >= columns) {
            browseRow = new LinearLayout(this); browseRow.setOrientation(LinearLayout.HORIZONTAL);
            browseGrid.addView(browseRow, new LinearLayout.LayoutParams(-1, -2));
            browseRowKeys = new ArrayList<>();
            focusRows.add(browseRowKeys);
            browseRowUsed = 0;
        }
        String key = "browse-" + index;
        PosterCard card = posterCard(item, item.optString("name"), "", "", key, () -> openDetail(item), width);
        card.setScore(item.optString("rate"));
        card.setCategory(cardBadge(item));
        browseRow.addView(card, cardParams(width));
        browseRowKeys.add(key); browseRowUsed++;
    }
    private void updateBrowseStatus() {
        if (browseStatus == null) return;
        if (browseItems.length() == 0) {
            browseStatus.setText(browseFinished ? "没有匹配内容" : "正在加载…");
            return;
        }
        browseStatus.setText("已加载 " + browseItems.length() + " 部");
    }
    /** 片库数据来自采集服务：筛选靠 tags、翻页靠 start，全部由服务端完成，本地不再过滤。 */
    private void loadBrowse(int ticket) {
        if (catalog == null) return;
        browseLoading = true;
        updateBrowseFooter();
        final String requestedSection = section, requestedYear = year;
        final int requestedSort = sort, token = ++browseToken;
        browsePending = discoveryWorker.submit(() -> {
            try {
                CatalogRepository.Page result = catalog.page(requestedSection, requestedYear, requestedSort, 1);
                main.post(() -> {
                    if (dead || token != browseToken || !page.equals("browse")) return;
                    browseItems = result.items;
                    browsePage = 1;
                    browseHasMore = result.more;
                    browseLoaded = true; browseLoading = false; browseFinished = true;
                    render();
                });
            } catch (Exception e) {
                main.post(() -> {
                    browseLoading = false;
                    if (dead || token != browseToken || !page.equals("browse")) return;
                    browseFinished = true;
                    if (browseStatus != null) browseStatus.setText("片库榜单暂时无法加载");
                });
            }
        });
    }
    /** 滚动接近底部时自动请求片库下一页，追加到已有列表末尾（不做全量重绘，保持滚动位置）。 */
    private void loadMoreBrowse() {
        if (catalog == null || browseLoading || !browseHasMore) return;
        browseLoading = true;
        updateBrowseFooter();
        final int next = browsePage + 1;
        final String requestedSection = section, requestedYear = year;
        final int requestedSort = sort, token = browseToken;
        browsePending = discoveryWorker.submit(() -> {
            try {
                CatalogRepository.Page result = catalog.page(requestedSection, requestedYear, requestedSort, next);
                main.post(() -> {
                    if (dead || token != browseToken || !page.equals("browse")) return;
                    browseLoading = false;
                    if (browseItems.length() == 0) return;
                    if (result.items.length() == 0) {
                        // 这一页没有可用条目，视为到底，避免继续空转请求。
                        browseHasMore = false;
                        updateBrowseFooter();
                        return;
                    }
                    browsePage = next;
                    browseHasMore = result.more;
                    int start = browseItems.length();
                    for (int i = 0; i < result.items.length(); i++) {
                        JSONObject item = result.items.optJSONObject(i);
                        if (item != null) browseItems.put(item);
                    }
                    appendBrowseCards(start);
                    wireFocus();
                    updateBrowseStatus();
                    updateBrowseFooter();
                    autoLoadIfShort();
                });
            } catch (Exception e) {
                main.post(() -> {
                    browseLoading = false;
                    if (dead || token != browseToken || !page.equals("browse")) return;
                    updateBrowseFooter();
                });
            }
        });
    }

    /** 内容不足一屏时无法滚动，直接补下一页，保证始终有内容可翻。 */
    private void autoLoadIfShort() {
        if (!page.equals("browse") || !browseHasMore || browseLoading || scroller == null) return;
        scroller.post(() -> {
            View child = scroller.getChildAt(0);
            if (child == null || !browseHasMore || browseLoading || !page.equals("browse")) return;
            if (child.getHeight() <= scroller.getHeight() + dp(40)) loadMoreBrowse();
        });
    }

    private void renderHistory() {
        LinearLayout title = row(body); title.setPadding(0, dp(8), 0, dp(10)); title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(heading("观看历史", 26), new LinearLayout.LayoutParams(0, -2, 1));
        button(title, "我的片单", "nav-playlist", () -> open("playlist"));
        List<String> topKeys = new ArrayList<>(); topKeys.add("nav-playlist");
        JSONArray all = history.all();
        if (all.length() == 0) {
            focusRows.add(topKeys);
            body.addView(text("还没有观看记录，先找一部想看的。", 18, TEXT_2));
            button(body, "去搜索", "empty-search", () -> open("search"));
            focusRows.add(Collections.singletonList("empty-search"));
            return;
        }
        button(title, "管理", "manage-history", () -> new android.app.AlertDialog.Builder(this).setTitle("清空观看历史？")
                .setMessage("来源和片单中的进度会保留。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> { history.clear(); focusKey = "nav-history"; render(); }).show());
        topKeys.add("manage-history"); focusRows.add(topKeys);
        int columns = 4, width = cardWidthDp(columns);
        for (int start = 0; start < all.length(); start += columns) {
            LinearLayout row = row(body); List<String> keys = new ArrayList<>();
            for (int i = start; i < Math.min(start + columns, all.length()); i++) {
                JSONObject entry = all.optJSONObject(i); if (entry == null) continue;
                JSONObject item = entry.optJSONObject("item");
                String key = "history-" + i;
                PosterCard card = posterCard(item, entry.optString("title"), PiankeUi.position(entry), "", key, () -> resume(entry), width);
                row.addView(card, cardParams(width)); keys.add(key);
            }
            if (!keys.isEmpty()) focusRows.add(keys);
        }
    }

    private void wireFocus() {
        for (int r = 0; r < focusRows.size(); r++) {
            List<String> row = focusRows.get(r);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) link(row.get(i), row.get(i - 1), View.FOCUS_LEFT);
                if (i + 1 < row.size()) link(row.get(i), row.get(i + 1), View.FOCUS_RIGHT);
                if (r > 0) { List<String> up = focusRows.get(r - 1); if (!up.isEmpty()) link(row.get(i), up.get(Math.min(i, up.size() - 1)), View.FOCUS_UP); }
                if (r + 1 < focusRows.size()) { List<String> down = focusRows.get(r + 1); if (!down.isEmpty()) link(row.get(i), down.get(Math.min(i, down.size() - 1)), View.FOCUS_DOWN); }
            }
        }
    }
    private void link(String from, String to, int direction) {
        View a = targets.get(from), b = targets.get(to);
        if (a == null || b == null) return;
        switch (direction) {
            case View.FOCUS_UP: a.setNextFocusUpId(b.getId()); break;
            case View.FOCUS_DOWN: a.setNextFocusDownId(b.getId()); break;
            case View.FOCUS_LEFT: a.setNextFocusLeftId(b.getId()); break;
            case View.FOCUS_RIGHT: a.setNextFocusRightId(b.getId()); break;
            default: break;
        }
    }

    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if (!page.equals("home")) { page = "home"; focusKey = "nav-voice"; render(); }
        else super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("page", page); out.putString("section", section);
        out.putString("focusKey", focusKey); out.putInt("browsePage", browsePage);
        out.putString("rankCategory", rankCategory);
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() {
        started = false; generation++;
        main.removeCallbacks(heroTick);
        if (pending != null) pending.cancel(true);
        if (browsePending != null) browsePending.cancel(true);
        browseLoading = false;
        super.onStop();
    }
    @Override protected void onDestroy() {
        dead = true; discoveryWorker.shutdownNow(); posters.close();
        main.removeCallbacksAndMessages(null); super.onDestroy();
    }
}

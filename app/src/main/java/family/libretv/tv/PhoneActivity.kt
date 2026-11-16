package family.libretv.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.CastOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Touch UI; NAS sessions and protocol implementation are shared with the TV app/library. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PhoneActivity : Activity() {
    companion object {
        private var castOwner: java.lang.ref.WeakReference<PhoneActivity>? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val castCommands = Mutex()
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView
    private lateinit var client: NasClient
    private val prefs by lazy { getSharedPreferences("phone-ui", MODE_PRIVATE) }
    private var sources = JSONArray()
    private var results = JSONArray()
    private var episodes = JSONArray()
    private var selected = JSONObject()
    private var source = JSONObject()
    private var title = ""
    private var query = ""
    private var page = "login"
    private var generation = 0
    private var busy = false
    private var device: DLNACast.Device? = null
    private val posters = PosterLoader()
    private val castPrefs by lazy { getSharedPreferences("phone-cast", MODE_PRIVATE) }
    private var queue: PhoneCastQueue? = null
    private val castTitle get() = queue?.label.orEmpty()
    private var attached = false
    private var knownDevices = emptySet<String>()
    private var detailInfo = JSONObject()
    private var searchJob: Job? = null
    private var searchToken = 0
    private var searching = false
    private var searchCompleted = 0
    private var searchTotal = 0
    private var searchFailed = 0
    private var searchStatus: TextView? = null
    private var resultHeading: TextView? = null
    private var resultCards: LinearLayout? = null
    private var searchControls: LinearLayout? = null
    /** 来源筛选：null 表示全部来源，否则只显示该来源的结果。 */
    private var sourceFilter: String? = null
    private var renderJob: Job? = null
    private var lastRenderAt = 0L
    private var moreResults: Button? = null
    private var shownResults = 30
    private var pendingExport: String? = null
    private var batchRunning = false
    private var batchStopRequested = false
    private val backgroundColor = Color.rgb(14, 15, 17)
    private val panelColor = Color.rgb(22, 24, 27)
    private val surface2Color = Color.rgb(30, 33, 38)
    private val accentColor = Color.rgb(232, 163, 61)
    private val accentHiColor = Color.rgb(242, 185, 90)
    private val textColor = Color.rgb(243, 244, 246)
    private val secondaryColor = Color.rgb(168, 174, 182)
    private val tertiaryColor = Color.rgb(109, 115, 123)
    private val strokeColor = Color.rgb(42, 46, 53)
    private val washColor = 0x1FE8A33D
    private var castDevice: DLNACast.Device? = null
    private var progress: TextView? = null
    private var polling: Job? = null
    private var dialog: AlertDialog? = null

    private val catalog by lazy { CatalogRepository(this) }
    private var discoveryJob: Job? = null
    private var discoveryLoading = false
    private var categorySection = "电影"
    private var categoryYear = CatalogRepository.ALL
    private var categoryArea = CatalogRepository.ALL
    private var categorySort = CatalogRepository.SORT_TIME
    private var categoryPage = 1
    private var categoryPages = 1
    private var categoryItems = JSONArray()
    private var categoryLoaded = false
    private var categoryLoading = false
    private var heroView: FrameLayout? = null
    private var heroItems = JSONArray()
    private var heroIndex = 0
    private var heroJob: Job? = null
    private var heroPoster: ImageView? = null
    private var heroImage: ImageView? = null
    private var heroOverlay: FrameLayout? = null
    private var sourceScanToken = 0

    private val wideTouch get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** 手机竖屏 16dp、横屏 / 大屏 24dp；平板上限宽 780dp，居中留白，避免内容被拉成长条。 */
    private val contentPadding get(): Int {
        val available = resources.configuration.screenWidthDp - (if(wideTouch) 88 else 0)
        val base = if(wideTouch || available >= 600) 24 else 16
        return if(available > 820) ((available - 780) / 2).coerceAtLeast(base) else base
    }
    private val touchContentWidthDp get() = (resources.configuration.screenWidthDp - (if(wideTouch) 88 else 0) - contentPadding * 2).coerceAtLeast(240)
    private val twoTouchColumns get() = touchContentWidthDp >= 560
    private val railCardWidth get() = when { touchContentWidthDp >= 700 -> 138; touchContentWidthDp >= 380 -> 116; else -> 104 }
    /** 竖版海报按 2:3 比例展示，列数随可用宽度增减，兼容大字体与平板。 */
    private val gridColumns get(): Int {
        val byWidth = when {
            touchContentWidthDp >= 860 -> 6
            touchContentWidthDp >= 700 -> 5
            touchContentWidthDp >= 520 -> 4
            touchContentWidthDp >= 300 -> 3
            else -> 2
        }
        return if(resources.configuration.fontScale > 1.3f) (byWidth - 1).coerceAtLeast(2) else byWidth
    }
    private var pendingLayout = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if(busy || batchRunning) pendingLayout = true else rebuildTouchLayout()
    }
    // Keep the Activity, network search and cast ownership alive when the system rotates/resizes it.
    private fun rebuildTouchLayout() {
        if(!::content.isInitialized) return
        pendingLayout = false
        val drafts = mutableMapOf<String,String>()
        var focusedInput: String? = null
        val numbers = mutableMapOf<String,Int>()
        fun remember(view: View) {
            if(view is EditText) { val key=view.hint?.toString().orEmpty(); drafts[key]=view.text.toString(); if(view.hasFocus()) focusedInput=key }
            if(view is NumberPicker) numbers[view.contentDescription.toString()]=view.value
            if(view is ViewGroup) for(i in 0 until view.childCount) remember(view.getChildAt(i))
        }
        remember(content)
        val oldPage=page; val oldY=scroll.scrollY; val feedback=notice.text.toString()
        when(oldPage) {
            "home" -> home(); "search-landing" -> searchLanding(); "search" -> searchScreen(); "detail" -> details()
            "settings" -> settingsScreen(); "config" -> login(); "category" -> category()
            "history" -> historyScreen(); "playlist" -> playlistScreen()
            "controls" -> controls(); "batch" -> batchScreen(); else -> home()
        }
        fun restore(view: View) {
            if(view is EditText) { val key=view.hint?.toString().orEmpty(); drafts[key]?.let {view.setText(it);view.setSelection(view.text.length)}; if(key==focusedInput)view.requestFocus() }
            if(view is NumberPicker) numbers[view.contentDescription.toString()]?.let {view.value=it.coerceIn(view.minValue,view.maxValue)}
            if(view is ViewGroup) for(i in 0 until view.childCount) restore(view.getChildAt(i))
        }
        restore(content); if(feedback.isNotEmpty()) notice.text=feedback
        scroll.post {scroll.scrollTo(0,oldY)}
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 系统栏透明并保持浅色图标，让底部导航延伸到手势条 / 三键导航区域，避免出现白色横条。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if(android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        DLNACast.init(applicationContext)
        castOwner = java.lang.ref.WeakReference(this)
        try { client = NasClient(this); sources = client.local.sources(); restoreCast(); home() }
        catch(e: Exception) { login(); notice.text = e.message }
        UpdateActivity.checkInBackground(this)
        syncRemoteSources()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun surface(color: Int = panelColor, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun bordered(color: Int, radius: Int, stroke: Int = strokeColor) = surface(color, radius).apply { setStroke(dp(1), stroke) }
    private fun ripple(shape: GradientDrawable, wash: Int = 0x33E8A33D) = RippleDrawable(ColorStateList.valueOf(wash), shape, null)
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(textColor)
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun heading(value: String, size: Float = 17f) = text(value, size).apply {
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    /** 小标签：评分、地区、集数等，圆角胶囊样式。放在流式容器里会自动换行。 */
    private fun chip(parent: ViewGroup, label: String, tone: Int = accentColor, bg: Int = washColor): TextView {
        val view = TextView(this).apply {
            text = label; textSize = 12f; setTextColor(tone); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(5)); background = surface(bg, 999)
        }
        parent.addView(view, ViewGroup.MarginLayoutParams(-2, -2).apply { marginEnd = dp(6); bottomMargin = dp(6) })
        return view
    }
    /** 宽度不够时自动换行的流式布局，避免详情页标签被压成竖排。 */
    private class FlowLayout(context: android.content.Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth=MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
            var lineWidth=0;var lineHeight=0;var total=0
            for(i in 0 until childCount) {
                val child=getChildAt(i)
                if(child.visibility==GONE) continue
                measureChildWithMargins(child,widthMeasureSpec,0,heightMeasureSpec,0)
                val lp=child.layoutParams as MarginLayoutParams
                val childWidth=child.measuredWidth+lp.leftMargin+lp.rightMargin
                val childHeight=child.measuredHeight+lp.topMargin+lp.bottomMargin
                if(lineWidth+childWidth>maxWidth && lineWidth>0) {total+=lineHeight;lineHeight=0;lineWidth=0}
                lineWidth+=childWidth;lineHeight=maxOf(lineHeight,childHeight)
            }
            total+=lineHeight+paddingTop+paddingBottom
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else total)
        }
        override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
            val maxWidth=r-l-paddingLeft-paddingRight
            var x=paddingLeft;var y=paddingTop;var lineHeight=0
            for(i in 0 until childCount) {
                val child=getChildAt(i)
                if(child.visibility==GONE) continue
                val lp=child.layoutParams as MarginLayoutParams
                val childWidth=child.measuredWidth+lp.leftMargin+lp.rightMargin
                if(x-paddingLeft+childWidth>maxWidth && x>paddingLeft) {x=paddingLeft;y+=lineHeight;lineHeight=0}
                child.layout(x+lp.leftMargin,y+lp.topMargin,x+lp.leftMargin+child.measuredWidth,y+lp.topMargin+child.measuredHeight)
                x+=childWidth;lineHeight=maxOf(lineHeight,child.measuredHeight+lp.topMargin+lp.bottomMargin)
            }
        }
        override fun generateDefaultLayoutParams(): LayoutParams=MarginLayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT)
        override fun generateLayoutParams(p: LayoutParams): LayoutParams=p as? MarginLayoutParams ?: MarginLayoutParams(p)
        override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams=MarginLayoutParams(context,attrs)
    }
    /** 分类 / 标签胶囊，支持选中态。 */
    private fun pill(parent: LinearLayout, label: String, selected: Boolean = false, action: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if(selected) accentColor else secondaryColor)
            background = if(selected) bordered(washColor, 999, 0x66E8A33D) else bordered(panelColor, 999)
            foreground = ripple(surface(Color.TRANSPARENT, 999), 0x26E8A33D)
            isClickable = true; isFocusable = true
            setOnClickListener { if(!busy) action() }
        }
        parent.addView(view, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(8); topMargin = dp(8) })
        return view
    }
    private fun topIcon(iconRes: Int, label: String, glyph: String = "", action: () -> Unit): FrameLayout {
        val box = FrameLayout(this).apply {
            contentDescription = label; isClickable = true; isFocusable = true
            background = ripple(surface(Color.TRANSPARENT, 999), 0x26E8A33D)
            setOnClickListener { if(!busy) action() }
        }
        val view: View = if(iconRes != 0) ImageView(this).apply {
            setImageResource(iconRes); imageTintList = ColorStateList.valueOf(textColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else TextView(this).apply {
            text = glyph; textSize = 26f; setTextColor(textColor); gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(view, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        return box
    }
    private fun screen(name: String, headingText: String) {
        generation++; busy = false; page = name; polling?.cancel(); progress = null
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(backgroundColor); clipToPadding = false; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(contentPadding), dp(4), dp(contentPadding), dp(28))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor)
            tag = if(wideTouch) "touch-landscape" else "touch-portrait"
        }
        // 固定顶部栏：首页展示品牌，其余页面提供返回与标题，避免页面内容随滚动消失。
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if(wideTouch) 18 else 10), 0, dp(if(wideTouch) 18 else 10), 0)
        }
        if(name == "home") {
            header.addView(PiankeUi.brand(this, 30, 30), LinearLayout.LayoutParams(dp(30), dp(30)))
            val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            brand.addView(text("星光影院", 19f).apply { setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)) })
            brand.addView(text(PiankeUi.SLOGAN, 10f).apply { setTextColor(tertiaryColor) })
            header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            header.addView(topIcon(0, "返回", "‹") { navigateBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            header.addView(heading(headingText, 20f).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val activeTab = when(name) {
            "home","category" -> "首页"
            "detail" -> if(query.isNotEmpty()) "搜索" else "首页"
            "history","playlist","controls","batch" -> "历史"
            "settings","config" -> "设置"
            else -> "搜索"
        }
        val navigation = LinearLayout(this).apply {
            orientation = if(wideTouch) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.rgb(11, 13, 15))
        }
        val tabs = listOf("首页" to R.drawable.ic_nav_home, "搜索" to R.drawable.ic_nav_search, "历史" to R.drawable.ic_nav_history, "设置" to R.drawable.ic_nav_settings)
        tabs.forEach { (label, icon) ->
            val selected = label == activeTab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isClickable = true; isSelected = selected; contentDescription = (if(wideTouch) "侧边导航" else "底部导航") + " · $label"
                background = if(selected) surface(washColor, 14) else surface(Color.TRANSPARENT, 14)
                foreground = ripple(surface(Color.TRANSPARENT, 14), 0x26E8A33D)
                setOnClickListener { if(!busy) { hideKeyboard(); when(label) { "首页" -> home(); "搜索" -> searchLanding(); "历史" -> historyScreen(); "设置" -> settingsScreen() } } }
            }
            item.addView(ImageView(this).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(if(selected) accentColor else tertiaryColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(text(label, 10.5f).apply {
                setTextColor(if(selected) accentColor else tertiaryColor); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            navigation.addView(item, if(wideTouch) LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(2) }
                else LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        val divider = View(this).apply { setBackgroundColor(strokeColor) }
        val body = LinearLayout(this).apply { orientation = if(wideTouch) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        scroll.addView(content)
        if(wideTouch) {
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11, 13, 15))
                setPadding(dp(6), dp(6), dp(6), dp(6)); addView(navigation)
            }
            body.addView(rail, LinearLayout.LayoutParams(dp(88), -1))
            body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        } else body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // 状态提示固定在顶部栏下方，避免出现在长页面底部看不见。
        notice = text("", 13.5f).apply {
            setTextColor(accentColor); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE; setPadding(dp(contentPadding), dp(6), dp(contentPadding), dp(4))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { visibility = if(s.isNullOrEmpty()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(notice, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        if(!wideTouch) {
            root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        // 全面屏适配：状态栏 / 刘海 / 手势条 / 输入法均按真实 insets 留白。
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = !wideTouch && insets.isVisible(WindowInsetsCompat.Type.ime())
            if(wideTouch) {
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(bars.left, bars.top, bars.right, if(imeVisible) ime.bottom else 0)
                navigation.setPadding(dp(6), dp(6), dp(6), dp(6) + bars.bottom)
                navigation.visibility = if(imeVisible) View.GONE else View.VISIBLE
                divider.visibility = navigation.visibility
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
    private fun section(title: String? = null, parent: LinearLayout = content): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = bordered(panelColor, 18)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if(title != null) {
            val head = buttonRow(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
            head.addView(View(this@PhoneActivity).apply { background = surface(accentColor, 2) }, LinearLayout.LayoutParams(dp(3), dp(15)))
            head.addView(heading(title, 17f).apply { setPadding(dp(9), 0, 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun fieldBackground(): android.graphics.drawable.StateListDrawable {
        val states = android.graphics.drawable.StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), bordered(surface2Color, 14, accentColor).apply { setStroke(dp(2), accentColor) })
        states.addState(intArrayOf(), bordered(surface2Color, 14, strokeColor))
        return states
    }
    private fun button(label: String, parent: LinearLayout = content, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false; minHeight = dp(50); minimumHeight = dp(50)
        setTextColor(if(primary) 0xFF1A1206.toInt() else textColor)
        val shape = if(primary) surface(accentColor, 999) else bordered(surface2Color, 999)
        background = ripple(shape, if(primary) 0x40FFFFFF else 0x33E8A33D)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setOnClickListener { if (!busy) action() }
    }
    private fun buttonRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun rowButton(row: LinearLayout, label: String, enabled: Boolean = true, action: () -> Unit): Button {
        val b = button(label, row, action = action)
        b.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6); topMargin = dp(8) }
        b.setPadding(dp(4), 0, dp(4), 0)
        b.isEnabled = enabled; b.alpha = if(enabled) 1f else .35f
        return b
    }
    private fun input(hintText: String, value: String, password: Boolean = false, parent: LinearLayout = content) = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(textColor); setHintTextColor(tertiaryColor)
        isSingleLine = true; isSaveEnabled = false; textSize = 16f
        inputType = if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background = fieldBackground(); setPadding(dp(14), 0, dp(14), 0)
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
    }
    private fun poster(parent: LinearLayout, url: String, width: Int, height: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; background = surface(); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(width, height))
        if(::client.isInitialized) posters.load(this, client, url) else setImageResource(R.drawable.poster_placeholder)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** 采集服务的海报是公网地址，不需要经过本机 CMS 代理。 */
    private fun posterPublic(parent: LinearLayout, url: String, width: Int, height: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; background = surface(); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(width, height))
        posters.loadPublic(this, url, R.drawable.poster_placeholder)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** 列数、列宽与海报高度；列宽按 section 左右内边距（14dp×2）扣除卡片间距后计算。 */
    private fun gridMetrics(): Triple<Int, Int, Int> {
        val columns = gridColumns
        val available = touchContentWidthDp - 28
        val width = ((available - 8 * (columns - 1)) / columns).coerceAtLeast(72)
        return Triple(columns, width, (width * 1.46f).toInt())
    }
    /**
     * 先按列数铺好等宽空槽再往槽里放卡片。
     * 搜索结果按来源分批返回，若每批都单独成行，只有 1–2 项的批次会被拉成整行宽，
     * 导致每行卡片宽度不一致；固定槽位可以让流式追加保持列宽不变。
     */
    private fun gridRow(parent: LinearLayout, columns: Int): LinearLayout {
        val row = buttonRow(parent)
        repeat(columns) {
            row.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(4)) })
        }
        return row
    }
    private fun posterCard(slot: LinearLayout, item: JSONObject, posterHeight: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bordered(panelColor, 14)
            isClickable = true; isFocusable = true; clipToOutline = true
            foreground = ripple(surface(Color.TRANSPARENT, 14), 0x26E8A33D)
            contentDescription = item.optString("name") + "，" + item.optString("sourceName")
            setOnClickListener { if(!busy) openCatalogItem(item) }
        }
        slot.addView(card, LinearLayout.LayoutParams(-1, -2))
        // 采集接口的片库条目没有 sourceKey，海报走公网直连。
        if(item.optString("sourceKey").isEmpty()) posterPublic(card, item.optString("pic"), -1, dp(posterHeight))
        else poster(card, item.optString("pic"), -1, dp(posterHeight))
        card.addView(text(item.optString("name"), 14f).apply {
            minLines = 2; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(7), dp(7), dp(7), 0); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        val catalogItem = item.optString("sourceKey").isEmpty()
        val kicker = if(catalogItem) item.optString("typeName").ifEmpty { "片库" } else cleanSourceName(item.optString("sourceName"))
        card.addView(text(kicker, 11f).apply {
            setTextColor(accentColor); maxLines = 1; setPadding(dp(7), dp(3), dp(7), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        val rate = item.optString("rate")
        val extra = item.optString("remarks").ifEmpty {
            val parts = mutableListOf(item.optString("year"), item.optString("genre"))
            if(rate.isNotBlank() && rate!="null") parts += "★$rate"
            parts.filter { it.isNotBlank() }.joinToString(" · ")
        }
        if(extra.isNotEmpty()) card.addView(text(extra, 11f).apply {
            setTextColor(tertiaryColor); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(7), dp(2), dp(7), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun posterGrid(items: List<JSONObject>, parent: LinearLayout) {
        val (columns, _, posterHeight) = gridMetrics()
        var row = gridRow(parent, columns)
        var slot = 0
        for(item in items) {
            if(slot == columns) { row = gridRow(parent, columns); slot = 0 }
            posterCard(row.getChildAt(slot) as LinearLayout, item, posterHeight)
            slot++
        }
    }
    /** 首页大图推荐：16:9 裁切海报 + 底部渐变压暗，标题直接压在图上。 */
    private fun featureCard(parent: LinearLayout, item: JSONObject) {
        val frame = FrameLayout(this).apply {
            clipToOutline = true; background = surface(panelColor, 18)
            foreground = ripple(surface(Color.TRANSPARENT, 18), 0x26E8A33D)
            isClickable = true; isFocusable = true
            contentDescription = item.optString("name")
            setOnClickListener { if(!busy) detail(item) }
        }
        parent.addView(frame, LinearLayout.LayoutParams(-1, dp(if(wideTouch) 200 else 186)).apply { topMargin = dp(8) })
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            frame.addView(this, FrameLayout.LayoutParams(-1, -1))
            if(::client.isInitialized) posters.load(this, client, item.optString("pic")) else setImageResource(R.drawable.poster_placeholder)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x000e0f11, 0x660e0f11, 0xf20e0f11.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(14)) }
        frame.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        copy.addView(text(item.optString("sourceName"), 11f).apply { setTextColor(accentColor) })
        copy.addView(text(item.optString("name"), 22f).apply {
            setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val meta = listOf(item.optString("remarks"), item.optString("typeName")).filter { it.isNotBlank() }.joinToString(" · ")
        if(meta.isNotEmpty()) copy.addView(text(meta, 12f).apply { setTextColor(secondaryColor); maxLines = 1 })
        copy.addView(text("查看详情  ›", 13f).apply { setTextColor(accentColor); setPadding(0, dp(6), 0, 0) })
    }
    private fun hero(parent: LinearLayout, name: String, cover: String, subtitle: String): LinearLayout {
        val row = buttonRow(parent)
        heroPoster = poster(row, cover, dp(if(wideTouch) 88 else 92), dp(if(wideTouch) 124 else 132))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(text(name, 21f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(text(subtitle, 13f).apply { setTextColor(secondaryColor) })
        return info
    }
    private fun hideKeyboard() {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
    }
    private fun work(hint: String, feedback: TextView? = null, block: suspend () -> Unit) {
        if (busy) return
        val ticket = generation; busy = true; notice.text = hint; feedback?.text = hint
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(ticket == generation) {
                val message = when(e) {
                is NasClient.ApiException -> if(e.status == 401) "登录已失效，请返回重新登录" else "NAS 请求失败（${e.status}）"
                is IllegalArgumentException -> e.message ?: "参数错误"
                else -> e.message ?: "操作失败，请检查源站或接收设备"
                }
                notice.text = message; feedback?.text = message
            } }
            finally { if(ticket == generation) busy = false; if(pendingLayout && !busy && !batchRunning) rebuildTouchLayout() }
        }
    }
    private suspend fun request(path: String, body: JSONObject? = null, method: String = if(body == null) "GET" else "POST"): JSONObject =
        withContext(Dispatchers.IO) { client.request(path, body, method) }

    private fun login(manualMessage: String? = null) {
        screen("config", "视频来源配置")
        content.addView(text("添加自己的电影、电视剧来源。",14f).apply {setTextColor(secondaryColor)})
        val shortcuts=buttonRow(content)
        rowButton(shortcuts,"扫码添加视频源") {startActivityForResult(android.content.Intent(this,SourceScannerActivity::class.java),412)}
        rowButton(shortcuts,"从文件导入") {startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {type="*/*";addCategory(android.content.Intent.CATEGORY_OPENABLE)},410)}
        val box = section("订阅与分享").apply {visibility=View.GONE}
        button("扫码配置源", box, true) { startActivityForResult(android.content.Intent(this,SourceScannerActivity::class.java),412) }
        button("显示来源二维码", box) {
            val payload=try { SourceQrDecoder.sharePayload(JSONObject(client.local.exportSources()).toString()) } catch(e: Exception) { notice.text=e.message; return@button }
            val image=ImageView(this).apply { setImageBitmap(PairingQr.create(payload,720)); adjustViewBounds=true; setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(Color.WHITE) }
            dialog=AlertDialog.Builder(this).setTitle("扫码导入本机来源").setView(image).setPositiveButton("关闭",null).show()
        }
        val config = input("订阅地址或 sources JSON", "", parent = box).apply { isSingleLine = false; minLines = 2; maxLines = 6; layoutParams.height = dp(120) }
        button("导入配置", box, true) {
            hideKeyboard()
            work("正在导入…") {
                val value = config.text.toString()
                val count = withContext(Dispatchers.IO) { client.local.importSources(value) }
                sources = client.local.sources(); login(); notice.text = "已新增 $count 个源"
            }
        }
        button("从文件导入", box) {
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(android.content.Intent.CATEGORY_OPENABLE) }, 410)
        }
        val manual = section("手动添加来源")
        manual.addView(text("支持 CMS JSON 接口地址",13f).apply {setTextColor(secondaryColor)})
        val name = input("源名称", "", parent = manual)
        val url = input("来源地址", "", parent = manual)
        val manualFeedback = text(manualMessage.orEmpty(), 14f).apply {
            setTextColor(accentColor); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        button("添加来源", manual,true) {
            hideKeyboard()
            val value = JSONObject().put("name", name.text.toString().trim().ifBlank { "自定义源" }).put("url",url.text.toString()).toString()
            work("正在保存…", manualFeedback) {
                val count = withContext(Dispatchers.IO) { client.local.importSources(value) }
                sources=client.local.sources()
                login(if(count > 0) "来源已添加" else "该来源地址已存在，无需重复添加")
            }
        }
        manual.addView(manualFeedback)
        if(manualMessage != null) manualFeedback.post {
            manualFeedback.requestRectangleOnScreen(android.graphics.Rect(0, 0, manualFeedback.width, manualFeedback.height), true)
        }
        button("导出来源配置", box) {
            pendingExport = client.local.exportSources()
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"; addCategory(android.content.Intent.CATEGORY_OPENABLE); putExtra(android.content.Intent.EXTRA_TITLE,"libretv-sources.json")
            }, 411)
        }
        setting(content,"订阅与分享","粘贴配置、二维码与导出") {box.visibility=if(box.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        val saved = section("已保存 · ${sources.length()} 个源")
        for(i in 0 until sources.length()) {
            val item=sources.getJSONObject(i);val row=buttonRow(saved).apply {gravity=Gravity.CENTER_VERTICAL}
            Switch(this).apply {
                text=item.optString("name");setTextColor(textColor);isChecked=item.optBoolean("enabled",true);setPadding(dp(4),dp(12),dp(8),dp(12))
                row.addView(this,LinearLayout.LayoutParams(0,-2,1f));setOnCheckedChangeListener {_,enabled->work("正在保存…"){withContext(Dispatchers.IO){client.local.setSourceEnabled(item.getString("key"),enabled)};sources=client.local.sources();notice.text="已保存"}}
            }
            button("⋮",row) {
                libraryMenu(item.optString("name"),listOf("删除来源" to {
                    dialog=AlertDialog.Builder(this).setMessage("删除 ${item.optString("name")}？重启后不会自动补回。")
                        .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->work("正在删除…"){withContext(Dispatchers.IO){client.local.removeSource(item.getString("key"))};sources=client.local.sources();results=JSONArray();login()}}.show()
                }))
            }.apply {contentDescription="来源管理 · "+item.optString("name");layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))}
        }
        button("返回选片") { home() }
    }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==412 && resultCode==RESULT_OK) {
            data?.getStringExtra("sourceConfig")?.let { confirmScannedSources(it) }
        }
        if(requestCode==411) {
            val payload=pendingExport; pendingExport=null
            if(resultCode==RESULT_OK && data?.data!=null && payload!=null) {
                val uri=data.data!!
                work("正在导出…") {
                    withContext(Dispatchers.IO) { (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件")).use { it.write(payload.toByteArray(Charsets.UTF_8)) } }
                    notice.text="配置已导出，可分享给其他设备"
                }
            }
        }
        if(requestCode==410 && resultCode==RESULT_OK && data?.data!=null) {
            val uri=data.data!!
            work("正在读取配置…") {
                val count=withContext(Dispatchers.IO) {
                    val input=contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件")
                    client.local.importSources(LocalRepository.read(input,1024*1024))
                }
                sources=client.local.sources(); login(); notice.text="已新增 $count 个源"
            }
        }
    }
    private fun confirmScannedSources(value: String) {
        val preview=if(value.startsWith("http")) "订阅地址：\n$value\n\n确认后才会下载并导入。" else "来源 JSON：\n${value.take(600)}"
        dialog=AlertDialog.Builder(this).setTitle("导入扫码来源？").setMessage(preview)
            .setNegativeButton("取消",null).setPositiveButton("确认导入") { _,_ ->
                work("正在导入扫码来源…") {
                    val count=withContext(Dispatchers.IO) { client.local.importSources(value) }
                    sources=client.local.sources(); login(); notice.text="扫码配置完成，新增 $count 个来源"
                }
            }.show()
    }
    private fun deviceButton(parent: LinearLayout) {
        button(device?.let { "投屏设备 · ${it.name}" } ?: "选择投屏设备", parent) { chooseDevice() }
    }
    private fun home() {
        screen("home","星光影院")
        heroJob?.cancel(); heroView=null; heroPoster=null
        val cached=if(::client.isInitialized) catalog.cachedHome() else JSONArray()
        heroItems=JSONArray()
        for(i in 0 until minOf(6,cached.length())) cached.optJSONObject(i)?.let {heroItems.put(it)}
        if(heroIndex>=heroItems.length()) heroIndex=0
        if(heroItems.length()>0) heroBanner(content)
        else content.addView(text("正在读取片库…",14f).apply {setTextColor(tertiaryColor);setPadding(dp(3),dp(20),0,0)})
        renderCatalogRows(cached)
        if(cached.length()==0 || !catalog.homeFresh()) loadCatalogHome()
    }
    /** 与 TV 首屏一致：通栏剧照 + 底部渐变，每 6 秒自动轮换，圆点可点选。 */
    private fun heroBanner(parent: LinearLayout) {
        val height=dp(if(wideTouch) 236 else (touchContentWidthDp*0.66f).toInt().coerceIn(180,270))
        val frame=FrameLayout(this).apply {
            clipToOutline=true;background=surface(panelColor,18)
            foreground=ripple(surface(Color.TRANSPARENT,18),0x26E8A33D)
            isClickable=true;isFocusable=true
            setOnClickListener {
                val item=heroItems.optJSONObject(heroIndex)
                if(item!=null && !busy) openCatalogItem(item)
            }
        }
        parent.addView(frame,LinearLayout.LayoutParams(-1,height).apply {topMargin=dp(6)})
        heroView=frame;heroImage=null;heroOverlay=null
        renderHero()
        heroJob?.cancel()
        heroJob=scope.launch {
            while(isActive) {
                delay(6000)
                if(page!="home" || heroView!==frame) break
                if(heroItems.length()>1) {heroIndex=(heroIndex+1)%heroItems.length();renderHero()}
            }
        }
    }
    private fun renderHero() {
        val frame=heroView ?: return
        val item=heroItems.optJSONObject(heroIndex) ?: return
        // 图片层只创建一次，轮换时保留上一张直到新图加载完成，避免闪回占位图。
        val art=heroImage ?: ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            frame.addView(this,FrameLayout.LayoutParams(-1,-1))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            heroImage=this
        }
        posters.loadHero(art,item.optString("pic"),R.drawable.poster_placeholder)
        heroOverlay?.let {frame.removeView(it)}
        val overlay=FrameLayout(this)
        frame.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        heroOverlay=overlay
        overlay.addView(View(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x000e0f11,0x330e0f11,0xf70e0f11.toInt()))
        },FrameLayout.LayoutParams(-1,-1))
        val copy=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(32))}
        overlay.addView(copy,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val typeName=item.optString("typeName")
        val label=DiscoveryRepository.section(typeName).ifEmpty {if(typeName.isBlank() || typeName=="全部") "精选" else typeName}
        copy.addView(text("▍"+label,12f).apply {setTextColor(accentColor);letterSpacing=0.12f})
        copy.addView(text(item.optString("name"),22f).apply {
            setTypeface(typeface,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(2),0,dp(6))
        })
        val meta=buttonRow(copy).apply {gravity=Gravity.CENTER_VERTICAL}
        TextView(this).apply {
            text=item.optString("rate").ifEmpty {"推荐"};textSize=12f;setTextColor(accentColor);gravity=Gravity.CENTER
            background=surface(0x38E8A33D,999);setPadding(dp(10),dp(3),dp(10),dp(4))
            meta.addView(this,LinearLayout.LayoutParams(-2,-2))
        }
        TextView(this).apply {
            text="查看详情  ›";textSize=12f;setTextColor(backgroundColor);gravity=Gravity.CENTER
            background=surface(accentColor,999);setPadding(dp(14),dp(5),dp(14),dp(5))
            meta.addView(this,LinearLayout.LayoutParams(-2,-2).apply {leftMargin=dp(8)})
        }
        val dots=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        for(i in 0 until heroItems.length()) {
            val dot=View(this).apply {
                background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(if(i==heroIndex) accentColor else 0x66F3F4F6)}
                isClickable=true;isFocusable=true
                setOnClickListener {if(!busy){heroIndex=i;renderHero()}}
            }
            val size=if(i==heroIndex) 9 else 7
            dots.addView(dot,LinearLayout.LayoutParams(dp(size),dp(size)).apply {setMargins(dp(3),0,dp(3),0)})
        }
        overlay.addView(dots,FrameLayout.LayoutParams(-1,dp(18),Gravity.BOTTOM).apply {bottomMargin=dp(7)})
        frame.contentDescription="推荐 · ${item.optString("name")}"
    }
    private fun loadCatalogHome() {
        if(discoveryLoading) return
        discoveryLoading=true
        val token=generation
        discoveryJob=scope.launch {
            try {
                withContext(Dispatchers.IO) {runInterruptible {catalog.home()}}
                if(token==generation && page=="home") home()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {if(token==generation && page=="home") notice.text=e.message ?: "片库暂时无法加载"}
            finally {discoveryLoading=false}
        }
    }
    /** 与 TV 首页一致：按分类输出「热播电影 / 热播电视剧 …」横向片单，并带「查看全部」。 */
    private fun renderCatalogRows(items: JSONArray) {
        for(name in DiscoveryRepository.SECTIONS) {
            val group=sectionItems(items,name)
            if(group.length()>0) catalogRow("热播$name",group,name)
        }
    }
    private fun sectionItems(all: JSONArray, name: String)=JSONArray().apply {
        for(i in 0 until all.length()) {
            val entry=all.optJSONObject(i) ?: continue
            if(DiscoveryRepository.section(entry.optString("typeName"))==name) put(entry)
        }
    }
    private fun catalogRow(title: String, items: JSONArray, sectionName: String) {
        val box=section()
        val head=buttonRow(box).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,dp(4))}
        head.addView(View(this).apply {background=surface(accentColor,2)},LinearLayout.LayoutParams(dp(3),dp(15)))
        head.addView(heading(title,17f).apply {setPadding(dp(9),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
        head.addView(text("查看全部  ›",13f).apply {
            setTextColor(accentColor);gravity=Gravity.CENTER;setPadding(dp(10),dp(6),dp(2),dp(6))
            isClickable=true;isFocusable=true
            setOnClickListener {if(!busy)openCategory(sectionName)}
        },LinearLayout.LayoutParams(-2,-2))
        val carousel=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;clipToPadding=false}
        val row=LinearLayout(this);carousel.addView(row);box.addView(carousel)
        val cardWidth=railCardWidth
        for(i in 0 until minOf(items.length(),12)) {
            val item=items.optJSONObject(i) ?: continue
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;background=bordered(panelColor,14);clipToOutline=true
                isClickable=true;isFocusable=true;contentDescription=item.optString("name")
                foreground=ripple(surface(Color.TRANSPARENT,14),0x26E8A33D)
                setOnClickListener {if(!busy)openCatalogItem(item)}
            }
            row.addView(card,LinearLayout.LayoutParams(dp(cardWidth),-2).apply {rightMargin=dp(10)})
            posterPublic(card,item.optString("pic"),-1,dp((cardWidth*1.46f).toInt()))
            card.addView(text(item.optString("name"),13.5f).apply {
                minLines=2;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(8),dp(8),dp(8),0);setTypeface(typeface,Typeface.BOLD)
            },LinearLayout.LayoutParams(-1,-2))
            val extra=listOf(item.optString("rate"),item.optString("genre")).filter {it.isNotBlank()}.joinToString(" · ").ifEmpty {item.optString("year")}
            if(extra.isNotEmpty()) card.addView(text(extra,11f).apply {
                setTextColor(accentColor);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(8),dp(3),dp(8),dp(8))
            },LinearLayout.LayoutParams(-1,-2))
        }
    }
    /** 采集片库条目只有元数据，点击后按片名进入搜索页解析可播放来源。 */
    private fun openCatalogItem(item: JSONObject) {
        if(item.optString("sourceKey").isNotEmpty()) {detail(item);return}
        val name=item.optString("name")
        if(name.isBlank()) return
        query=name;startSearch()
    }
    private fun openCategory(name: String) {
        categorySection=name
        categoryYear=CatalogRepository.ALL;categoryArea=CatalogRepository.ALL;categorySort=CatalogRepository.SORT_TIME
        categoryPage=1;categoryPages=1;categoryItems=JSONArray();categoryLoaded=false
        category()
    }
    /** 与 TV 片库一致：使用采集接口的分类 / 年份 / 地区 / 排序筛选与分页。 */
    private fun category() {
        screen("category",if(categorySection==CatalogRepository.ALL) "片库" else "${categorySection}片库")
        val filters=section()
        filterRow(filters,"类型",CatalogRepository.CATEGORIES,categorySection) {value->
            categorySection=value;reloadCategory()
        }
        filterRow(filters,"年份",CatalogRepository.YEARS,categoryYear) {value->
            categoryYear=value;reloadCategory()
        }
        filterRow(filters,"地区",CatalogRepository.AREAS,categoryArea) {value->
            categoryArea=value;reloadCategory()
        }
        filterRow(filters,"排序",arrayOf("按时间","按评分"),if(categorySort==CatalogRepository.SORT_RATE) "按评分" else "按时间") {value->
            categorySort=if(value=="按评分") CatalogRepository.SORT_RATE else CatalogRepository.SORT_TIME
            reloadCategory()
        }
        val box=section()
        val status=text(if(categoryItems.length()==0) "正在加载…" else "共 ${categoryItems.length()} 部 · 第 $categoryPage / $categoryPages 页",13f).apply {setTextColor(secondaryColor)}
        box.addView(status)
        if(categoryItems.length()>0) posterGrid((0 until categoryItems.length()).map {categoryItems.getJSONObject(it)},box)
        val pager=buttonRow(box)
        rowButton(pager,"上一页",categoryPage>1) {changeCategoryPage(-1)}
        pager.addView(text("第 $categoryPage / $categoryPages 页",13f).apply {setTextColor(secondaryColor);gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        rowButton(pager,"下一页",categoryPage<categoryPages) {changeCategoryPage(1)}
        if(!categoryLoaded) loadCategory()
    }
    private fun filterRow(parent: LinearLayout,label: String,options: Array<out String>,selectedValue: String,onPick: (String)->Unit) {
        parent.addView(text(label,12f).apply {setTextColor(tertiaryColor);setPadding(dp(2),dp(9),0,0)})
        val scroll=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false}
        val row=LinearLayout(this);scroll.addView(row);parent.addView(scroll)
        options.forEach {value->pill(row,value,value==selectedValue) {onPick(value)}}
    }
    private fun reloadCategory() {
        categoryPage=1;categoryPages=1;categoryItems=JSONArray();categoryLoaded=false;category()
    }
    private fun changeCategoryPage(delta: Int) {
        val next=categoryPage+delta
        if(next<1 || next>categoryPages) return
        categoryPage=next;categoryLoaded=false;category()
    }
    private fun loadCategory() {
        if(categoryLoading) return
        categoryLoading=true
        val token=generation
        val section=categorySection;val year=categoryYear;val area=categoryArea;val sort=categorySort;val target=categoryPage
        discoveryJob=scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {runInterruptible {catalog.page(section,year,area,sort,target)}}
                if(token==generation && page=="category") {
                    categoryItems=result.items ?: JSONArray()
                    categoryPages=maxOf(1,result.pages)
                    categoryLoaded=true;category()
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {if(token==generation && page=="category") notice.text=e.message ?: "片库暂时无法加载"}
            finally {categoryLoading=false}
        }
    }
    private fun searchLanding() {
        screen("search-landing", "搜索")
        val recent = PlaybackStore(this@PhoneActivity).all().optJSONObject(0)
        if(recent != null) {
            val watching=section("继续观看")
            button("${recent.optString("title")} · 第 ${recent.optInt("index")+1} 集", watching, true) {
                detail(JSONObject(recent.getJSONObject("item").toString()).put("favoriteSource",recent.getJSONObject("source")))
            }
        }
        val searchBox = section("搜索电影、电视剧")
        val row = buttonRow(searchBox)
        val keywords = input("搜索电影、剧集", query, parent = row).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { topMargin=dp(2) }
        }
        fun search() {
            hideKeyboard(); query = keywords.text.toString().trim()
            if(query.isEmpty() || query.length > 100) { notice.text = "请输入 1–100 字片名"; return }
            startSearch()
        }
        button("搜索", row, true) { search() }.layoutParams = LinearLayout.LayoutParams(dp(76), dp(50)).apply { leftMargin = dp(8); topMargin = dp(2) }
        keywords.setOnEditorActionListener { _,action,_ -> if(action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        val utilities=buttonRow(content)
        rowButton(utilities,"投屏设备") { chooseDevice() }
        if(queue != null) rowButton(utilities,"投屏控制") { controls() }
        if(sources.length()==0) {
            val empty=section("开始找一部想看的")
            empty.addView(text("先添加影视来源，再搜索片名。",14f).apply {setTextColor(secondaryColor)})
            button("添加影视来源",empty,true) { login() }
        } else if(recent==null) {
            content.addView(text("输入片名即可搜索，结果会陆续显示。",14f).apply {setTextColor(tertiaryColor);setPadding(dp(3),dp(22),0,dp(10))})
        }
        if(query.isNotEmpty()) button(if(searching) "查看搜索进度 · ${results.length()} 项" else "查看搜索结果 · ${results.length()} 项") { searchScreen() }
    }
    private fun setting(parent: LinearLayout, title: String, subtitle: String = "", action: () -> Unit) {
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;background=bordered(panelColor,14)
            setPadding(dp(16),dp(13),dp(16),dp(13));isClickable=true;isFocusable=true;contentDescription=title
            foreground=ripple(surface(Color.TRANSPARENT,14),0x26E8A33D)
            setOnClickListener {if(!busy)action()}
        }
        parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        box.addView(row,LinearLayout.LayoutParams(-1,-2))
        val names=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        row.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        names.addView(text(title,16f).apply {setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL))})
        if(subtitle.isNotEmpty()) names.addView(text(subtitle,12f).apply {setTextColor(tertiaryColor)})
        row.addView(text("›",22f).apply {setTextColor(accentColor);setPadding(dp(8),0,0,0)})
    }
    private fun libraryTabs(active: String): LinearLayout {
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;background=bordered(panelColor,999)
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        content.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        listOf("历史","片单").forEach { label ->
            val selected=label==active
            row.addView(TextView(this).apply {
                text=label;textSize=14f;gravity=Gravity.CENTER
                setTextColor(if(selected)accentColor else secondaryColor)
                background=if(selected)surface(washColor,999) else surface(Color.TRANSPARENT,999)
                foreground=ripple(surface(Color.TRANSPARENT,999),0x26E8A33D)
                isClickable=true;isSelected=selected
                setOnClickListener {if(!busy){when(label){"历史"->historyScreen();else->playlistScreen()}}}
            },LinearLayout.LayoutParams(0,dp(38),1f))
        }
        return row
    }
    private fun libraryMenu(title: String, actions: List<Pair<String, () -> Unit>>) {
        dialog=AlertDialog.Builder(this).setTitle(title).setItems(actions.map {it.first}.toTypedArray()) { _,which -> actions[which].second() }.setNegativeButton("取消",null).show()
    }
    private fun settingsScreen() {
        screen("settings","设置")
        val settingsRow=if(twoTouchColumns) buttonRow(content) else null
        fun column(): LinearLayout = if(settingsRow==null) content else LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;settingsRow.addView(this,LinearLayout.LayoutParams(0,-2,1f).apply {setMargins(dp(4),0,dp(4),0)})}
        val first=column();val second=if(twoTouchColumns) column() else first
        val sourcesBox=section("内容来源",first)
        setting(sourcesBox,"影视来源","${sources.length()} 个来源 · 电影、电视剧") {login()}
        val playback=section("播放与显示",second)
        setting(playback,"投屏设备",device?.name ?: "选择接收设备") {chooseDevice()}
        if(queue!=null) setting(playback,"投屏控制",castTitle) {controls()}
        val app=section("应用",first)
        setting(app,"检查更新",BuildConfig.VERSION_NAME) {startActivity(android.content.Intent(this,UpdateActivity::class.java))}
        setting(app,"关于星光影院") {PiankeUi.about(this)}
        content.addView(text("来源和观看记录保存在这台设备上。",13f).apply {setTextColor(secondaryColor);setPadding(dp(3),dp(18),0,0)})
    }

    private fun startSearch() {
        searchJob?.cancel(); val token = ++searchToken
        val keyword = query
        val selectedSources = JSONArray(sources.toString())
        results = JSONArray(); shownResults = 30; searchCompleted = 0; searchFailed = 0
        sourceFilter = null; lastRenderAt = 0L
        searchTotal = (0 until selectedSources.length()).count { selectedSources.getJSONObject(it).optBoolean("enabled", true) }
        searching = searchTotal > 0
        searchScreen()
        if(!searching) { notice.text = "请先在来源配置中至少启用一个来源"; return }
        searchJob = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    client.local.search(keyword, selectedSources) { added, completed, total, failed ->
                        scope.launch(Dispatchers.Main) update@{
                            if(token != searchToken) return@update
                            for(i in 0 until added.length()) results.put(added.getJSONObject(i))
                            searchCompleted = completed; searchTotal = total; searchFailed = failed
                            if(page == "search") updateSearchResults()
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(token == searchToken && page == "search") notice.text = e.message ?: "搜索失败，请重试" }
            finally {
                if(token == searchToken) { searching = false; if(page == "search") updateSearchResults(immediate = true) }
            }
        }
    }
    private fun searchScreen() {
        screen("search", "搜索 · $query")
        val controls = buttonRow(content)
        searchControls = controls
        searchStatus = text("", 13f).apply { setTextColor(secondaryColor); setPadding(dp(14), dp(10), dp(14), 0) }.also { content.addView(it) }
        val box = section()
        resultHeading = heading("搜索结果",17f).also { box.addView(it) }
        resultCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; box.addView(this, LinearLayout.LayoutParams(-1, -2)) }
        moreResults = button("再显示 30 项",box) {
            if(sourceFilter != null && sortedResults().isEmpty()) { sourceFilter = null; shownResults = 30; updateSearchResults(immediate = true) }
            else { shownResults += 30; renderResults() }
        }
        updateSearchResults(immediate = true)
    }
    /** 来源筛选下拉框；结果仍在陆续到达时，另给一个停止按钮。 */
    private fun buildSearchControls() {
        val host = searchControls ?: return
        host.removeAllViews()
        val filter = rowButton(host, "${filterLabel()} ▾", true) { chooseSourceFilter() }
        filter.maxLines = 1; filter.ellipsize = android.text.TextUtils.TruncateAt.END; filter.textSize = 14f
        if(searching) rowButton(host, "停止搜索") {
            ++searchToken; searchJob?.cancel(); searching = false; updateSearchResults(immediate = true)
        }
    }
    /** 按钮上显示当前来源与它的结果条数。 */
    private fun filterLabel(): String {
        val key = sourceFilter ?: return "全部来源 · ${results.length()} 项"
        return "${cleanSourceName(sourceNameOf(key))} · ${countForSource(key)} 项"
    }
    private fun sourceNameOf(key: String?): String {
        for(i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            if(item.optString("sourceKey") == key) return item.optString("sourceName")
        }
        for(i in 0 until sources.length()) {
            val item = sources.getJSONObject(i)
            if(item.optString("key") == key) return item.optString("name")
        }
        return key.orEmpty()
    }
    /** 来源名自带 🎬 这类符号，卡片与下拉框里显示会变方框，统一去掉。 */
    private fun cleanSourceName(name: String): String {
        val cleaned = name.replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]+"), "").trim()
        return cleaned.ifEmpty { name }
    }
    private fun countForSource(key: String?): Int {
        var count = 0
        for(i in 0 until results.length()) if(results.getJSONObject(i).optString("sourceKey") == key) count++
        return count
    }
    /** 下拉框只列出本次搜索有结果的来源，按结果条数从多到少排列。 */
    private fun chooseSourceFilter() {
        data class Row(val key: String?, val label: String, val count: Int)
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for(i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val key = item.optString("sourceKey")
            val name = cleanSourceName(item.optString("sourceName"))
            val known = counts[key]
            counts[key] = (known?.first ?: name) to ((known?.second ?: 0) + 1)
        }
        if(counts.isEmpty()) { notice.text = "还没有搜索结果，先搜索片名再筛选来源"; return }
        val rows = mutableListOf(Row(null, "全部来源 · ${results.length()} 项", results.length()))
        counts.entries.map { Row(it.key, "${it.value.first} · ${it.value.second} 项", it.value.second) }
            .sortedWith(compareByDescending<Row> { it.count }.thenBy { it.label })
            .forEach { rows += it }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(2), dp(18), dp(8)) }
        panel.addView(text("${rows.size - 1} 个来源有结果 · 点击筛选", 13f).apply {
            setTextColor(tertiaryColor); setPadding(dp(2), 0, 0, dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val height = (dp(52) * rows.size).coerceAtMost(dp(400)).coerceAtLeast(dp(110))
        panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, height))
        val sheet = AlertDialog.Builder(this).setTitle("按视频源筛选").setView(panel).setNegativeButton("取消", null).create()
        for(row in rows) {
            val chosen = row.key == sourceFilter
            val option = TextView(this).apply {
                text = (if(chosen) "✓ " else "") + row.label
                textSize = 15f; setTextColor(if(chosen) accentColor else textColor)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = bordered(panelColor, 12)
                foreground = ripple(surface(Color.TRANSPARENT, 12), 0x26E8A33D)
                isClickable = true; isFocusable = true; contentDescription = row.label
                setOnClickListener {
                    sheet.dismiss()
                    sourceFilter = row.key
                    shownResults = 30
                    updateSearchResults(immediate = true)
                    scroll.scrollTo(0, 0)
                }
            }
            list.addView(option, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        dialog = sheet
        sheet.show()
    }
    /** 相关度：完全同名 > 前缀命中 > 命中位置靠前 > 名称更短 > 字面覆盖更多。 */
    private fun relevance(item: JSONObject): Int {
        val name = LocalRepository.normalize(item.optString("name"))
        val keyword = LocalRepository.normalize(query)
        if(keyword.isEmpty() || name.isEmpty()) return 0
        val at = name.indexOf(keyword)
        val lengthGap = (name.length - keyword.length).coerceIn(0, 300)
        var score = when {
            name == keyword -> 4000
            at == 0 -> 3200 - lengthGap
            at > 0 -> 2600 - at * 6 - lengthGap
            else -> {
                var hit = 0; var cursor = 0
                for(ch in keyword) {
                    val found = name.indexOf(ch, cursor)
                    if(found < 0) continue
                    hit++; cursor = found + 1
                }
                hit * 2000 / maxOf(1, keyword.length)
            }
        }
        // 类型 / 备注里带着片名的通常是衍生条目，同级时往后放。
        if(item.optString("typeName").contains(query) || item.optString("remarks").contains(query)) score -= 30
        return score
    }
    /** 先按来源筛选，再按相关度排序；相关度相同则片名短的优先，保证顺序稳定。 */
    private fun sortedResults(): List<JSONObject> {
        val list = ArrayList<JSONObject>(results.length())
        for(i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            if(sourceFilter != null && item.optString("sourceKey") != sourceFilter) continue
            list += item
        }
        val scored = list.map { it to relevance(it) }
        return scored.sortedWith(
            compareByDescending<Pair<JSONObject,Int>> { it.second }
                .thenBy { LocalRepository.normalize(it.first.optString("name")).length }
                .thenBy { it.first.optString("name") }
                .thenBy { it.first.optString("sourceKey") }
        ).map { it.first }
    }
    private fun updateSearchResults(immediate: Boolean = false) {
        if(page != "search") return
        buildSearchControls()
        searchStatus?.text = if(searching) "正在搜索 · $searchCompleted / $searchTotal 个来源完成 · $searchFailed 个不可用\n结果会陆续出现，可直接点开观看"
            else "搜索已结束 · $searchCompleted / $searchTotal 个来源完成 · $searchFailed 个不可用"
        val matched = sortedResults()
        val emptyFiltered = matched.isEmpty() && sourceFilter != null && !searching
        resultHeading?.text = when {
            matched.isEmpty() && searching -> "正在等待首个结果…"
            emptyFiltered -> "${cleanSourceName(sourceNameOf(sourceFilter))} · 暂无结果"
            matched.isEmpty() -> "暂无结果，可更换片名或检查来源"
            sourceFilter == null -> "搜索结果 · ${matched.size} · 按相关度排序"
            else -> "${cleanSourceName(sourceNameOf(sourceFilter))} · ${matched.size} 项"
        }
        resultHeading?.maxLines = 1
        resultHeading?.ellipsize = android.text.TextUtils.TruncateAt.END
        moreResults?.visibility = if(emptyFiltered || matched.size > shownResults) View.VISIBLE else View.GONE
        moreResults?.text = when {
            emptyFiltered -> "查看全部来源的结果"
            matched.size > shownResults -> "再显示 30 项 · 还有 ${matched.size - shownResults} 项"
            else -> "再显示 30 项"
        }
        scheduleResultsRender(immediate)
    }
    /** 结果持续到达时按 400ms 节流重排，避免每次回调都重建卡片。 */
    private fun scheduleResultsRender(immediate: Boolean) {
        renderJob?.cancel()
        val wait = if(immediate || !searching) 0L else (400L - (System.currentTimeMillis() - lastRenderAt)).coerceAtLeast(0L)
        renderJob = scope.launch {
            if(wait > 0) delay(wait)
            renderResults()
        }
    }
    private fun renderResults() {
        if(page != "search") return
        val host = resultCards ?: return
        lastRenderAt = System.currentTimeMillis()
        val matched = sortedResults()
        // 重排会重建视图，保留当前滚动位置，避免结果陆续到达时页面不断跳回顶部。
        val keepY = scroll.scrollY
        host.removeAllViews()
        posterGrid(matched.take(shownResults), host)
        if(keepY > 0) scroll.post { if(page == "search") scroll.scrollTo(0, keepY) }
    }

    private fun detail(item: JSONObject, continuePlayback: Boolean = false) {
        work("正在获取剧集…") {
            source = item.optJSONObject("favoriteSource") ?: (0 until sources.length()).map { sources.getJSONObject(it) }
                .firstOrNull { it.optString("key") == item.optString("sourceKey") } ?: throw IllegalArgumentException("来源已失效，请重新搜索")
            val result = request("/api/detail?id=${NasClient.encode(item.optString("vodId"))}&source=${NasClient.encode(source.toString())}")
            episodes = result.optJSONArray("episodes") ?: JSONArray()
            require(episodes.length()>0) { "暂无可播放剧集" }
            detailInfo = result.optJSONObject("videoInfo") ?: JSONObject()
            selected = item; title = detailInfo.optString("title",item.optString("name"))
            details()
            if(continuePlayback) {
                val saved = PlaybackStore(this@PhoneActivity).find(source,selected)
                val index = saved?.optInt("index",0) ?: 0
                if(index in 0 until episodes.length()) playLocal(index)
                else notice.text = "来源剧集数量已变化，请重新选集"
            }
        }
    }
    private fun details() {
        screen("detail", "影片详情")
        if(query.isNotEmpty() || queue != null) {
            val navigation = buttonRow(content)
            if(query.isNotEmpty()) pill(navigation, "‹ 搜索结果") { searchScreen() }
            if(queue != null) pill(navigation, "投屏控制") { controls() }
        }
        val split=if(twoTouchColumns) buttonRow(content).apply {tag="touch-detail-columns";gravity=Gravity.TOP} else null
        fun detailColumn(weight: Float): LinearLayout = if(split==null) content else LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;split.addView(this,LinearLayout.LayoutParams(0,-2,weight).apply {setMargins(dp(4),0,dp(4),0)})}
        val infoParent=detailColumn(.48f);val episodeParent=if(twoTouchColumns) detailColumn(.52f) else infoParent
        val info = section(parent=infoParent)
        val meta = listOf(detailInfo.optString("type"), detailInfo.optString("year"), detailInfo.optString("area"))
            .filter { it.isNotBlank() }.distinct().joinToString(" · ")
        hero(info, title, detailInfo.optString("cover").ifEmpty { selected.optString("pic") }, meta.ifEmpty { "影片" })
        // 标签放在海报信息行下方，横屏窄栏时也能完整展示。
        val chips = FlowLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        info.addView(chips, LinearLayout.LayoutParams(-1, -2))
        chip(chips, selected.optString("sourceName"), accentColor, washColor)
        chip(chips, "共 ${episodes.length()} 集", secondaryColor, 0x1FFFFFFF)
        // 评分 / 类型 / 演职员与 TV 使用同一个采集接口，返回后原地补全。
        val castLine = text("", 13f).apply { setTextColor(tertiaryColor); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        castLine.visibility = View.GONE
        info.addView(castLine)
        val saved=PlaybackStore(this@PhoneActivity).find(source,selected)
        val resumeIndex=saved?.optInt("index")?.takeIf {it in 0 until episodes.length()} ?: 0
        if(saved!=null) {info.addView(text(PiankeUi.position(saved),14f));PiankeUi.progress(info,saved)}
        button(if(saved==null) "开始观看" else "继续播放 · 第 ${resumeIndex+1} 集",info,true) {playLocal(resumeIndex)}
        val secondary=buttonRow(info)
        val listed = PlaybackStore(this@PhoneActivity).contains(source,selected)
        rowButton(secondary,if(listed) "移出片单" else "加入片单") {
            work("正在保存播放列表…") {
                withContext(Dispatchers.IO) {
                    val store = PlaybackStore(this@PhoneActivity)
                    if(listed) store.remove(PlaybackStore.identity(source,selected)) else store.add(source,selected,title)
                }
                details(); notice.text = if(listed) "已移出播放列表，观看进度保留" else "已加入播放列表"
            }
        }
        rowButton(secondary,"切换来源") {showSourcePicker()}
        val castActions=if(twoTouchColumns && touchContentWidthDp<850) buttonRow(info) else secondary
        rowButton(castActions,"投屏") {if(device==null)chooseDevice {cast(resumeIndex)} else cast(resumeIndex)}
        val expanded=booleanArrayOf(false)
        val descView=text(android.text.Html.fromHtml(detailInfo.optString("desc")).toString().ifBlank {"选好剧集，开始观看。"},14f).apply {
            setTextColor(secondaryColor);maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(4).toFloat(),1f);setPadding(0,dp(8),0,0)
        }
        info.addView(descView)
        val expand=text("展开简介",13f).apply {
            setTextColor(accentColor);setPadding(dp(2),dp(8),dp(2),dp(2));visibility=View.GONE
            isClickable=true;isFocusable=true
        }
        expand.setOnClickListener {
            expanded[0]=!expanded[0]
            descView.maxLines=if(expanded[0]) 14 else 4
            expand.text=if(expanded[0]) "收起简介" else "展开简介"
            if(expanded[0]) expand.visibility=View.VISIBLE else syncExpand(descView,expand,expanded)
        }
        info.addView(expand)
        descView.post {syncExpand(descView,expand,expanded)}
        val metaTicket=generation;val metaTitle=title
        scope.launch {
            val found=withContext(Dispatchers.IO) {runCatching {catalog.lookup(metaTitle)}.getOrNull()}
            if(found==null || found.optInt("id",0)<=0 || metaTicket!=generation || page!="detail") return@launch
            applyDetailMeta(found,chips,castLine,descView,expand,expanded)
        }
        val box = section("选择剧集 · 共 ${episodes.length()} 集",episodeParent)
        box.addView(text(SegmentSkipStore.summary(this,title),13f).apply { setTextColor(accentColor) })
        val columns = if(resources.configuration.fontScale > 1.3f) 3
            else if(twoTouchColumns) ((touchContentWidthDp*.52f-32)/72).toInt().coerceIn(4,7)
            else ((touchContentWidthDp-28)/72).toInt().coerceIn(3,6)
        for(start in 0 until episodes.length() step columns) {
            val row = buttonRow(box)
            for(i in start until minOf(start + columns, episodes.length())) rowButton(row, "第 ${i+1} 集") {
                AlertDialog.Builder(this).setCustomTitle(text("第 ${i+1} 集\n${SegmentSkipStore.summary(this,title)}",16f).apply { setPadding(dp(24),dp(18),dp(24),dp(8)) }).setItems(arrayOf("手机播放", "投屏到电视")) { _, which ->
                    if(which==0) playLocal(i)
                    else if(device == null) chooseDevice { cast(i) } else cast(i)
                }.show()
            }.apply {
                textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                if(saved!=null && i==resumeIndex) {background=surface(accentColor,12);setTextColor(backgroundColor)}
            }
            repeat(maxOf(0, start + columns - episodes.length())) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }
    /** 与 TV 详情页一致：补充评分、类型、更新进度、演职员和更完整的简介。 */
    private fun applyDetailMeta(meta: JSONObject,chips: ViewGroup,castLine: TextView,desc: TextView,expand: TextView,expanded: BooleanArray) {
        val score=meta.optString("score")
        if(score.isNotBlank() && score!="null" && score!="0" && score!="0.0") chip(chips,"★ $score",accentColor,0x38E8A33D)
        val genres=meta.optJSONArray("genres")
        if(genres!=null) for(i in 0 until minOf(3,genres.length())) {
            val genre=genres.optString(i)
            if(genre.isNotBlank()) chip(chips,genre,textColor,0x1FFFFFFF)
        }
        val remarks=meta.optString("remarks")
        if(remarks.isNotBlank()) chip(chips,remarks.take(14),secondaryColor,0x14FFFFFF)
        val parts=mutableListOf<String>()
        meta.optString("actor").takeIf {it.isNotBlank()}?.let {parts+="主演  ${it.take(36)}"}
        meta.optString("director").takeIf {it.isNotBlank()}?.let {parts+="导演  ${it.take(20)}"}
        if(parts.isNotEmpty()) {castLine.text=parts.joinToString("　　");castLine.visibility=View.VISIBLE}
        val blurb=android.text.Html.fromHtml(meta.optString("blurb")).toString().replace(Regex("\\s+")," ").trim()
        if(blurb.isNotEmpty()) {desc.text=blurb;desc.post {syncExpand(desc,expand,expanded)}}
        val pic=meta.optString("pic")
        if(pic.isNotEmpty()) heroPoster?.let {posters.loadPublic(it,pic,R.drawable.poster_placeholder)}
    }
    private fun syncExpand(desc: TextView,expand: TextView,expanded: BooleanArray) {
        desc.post {
            if(expanded[0]) {expand.visibility=View.VISIBLE;return@post}
            val layout=desc.layout ?: return@post
            expand.visibility=if(layout.lineCount>3 && layout.getEllipsisCount(3)>0) View.VISIBLE else View.GONE
        }
    }
    /** 与 TV 相同：边扫描来源边把其他源的同一部片追加到弹窗，点击即可切换。 */
    private fun showSourcePicker() {
        val currentKey=source.optString("key");val currentId=selected.optString("vodId")
        val chosen=JSONArray()
        for(i in 0 until sources.length()) {
            val candidate=sources.optJSONObject(i) ?: continue
            if(candidate.optBoolean("enabled",true)) chosen.put(JSONObject(candidate.toString()))
        }
        if(chosen.length()==0) {notice.text="请先在设置中启用至少一个来源";return}
        val keyword=selected.optString("name").ifEmpty { title }
        val wanted=LocalRepository.normalize(keyword)
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(6),dp(18),dp(6))}
        val status=text("正在扫描 ${chosen.length()} 个来源…",14f).apply {setTextColor(secondaryColor)}
        panel.addView(status)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        panel.addView(ScrollView(this).apply {addView(list)},LinearLayout.LayoutParams(-1,dp(320)))
        val picker=AlertDialog.Builder(this).setTitle("切换来源 · $keyword").setView(panel).setNegativeButton("关闭",null).create()
        picker.show()
        val token=++sourceScanToken
        picker.setOnDismissListener {if(token==sourceScanToken) sourceScanToken++}
        scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    client.local.search(keyword,chosen) { added,completed,total,failed ->
                        scope.launch(Dispatchers.Main) {
                            if(token!=sourceScanToken) return@launch
                            for(i in 0 until added.length()) {
                                val item=added.optJSONObject(i) ?: continue
                                if(LocalRepository.normalize(item.optString("name"))!=wanted) continue
                                if(item.optString("sourceKey")==currentKey && item.optString("vodId")==currentId) continue
                                addSourceOption(list,item,picker)
                            }
                            status.text=if(completed>=total)
                                "扫描完成 · ${list.childCount} 个可用来源" + (if(failed>0) " · $failed 个来源不可用" else "")
                            else "已扫描 $completed / $total 个来源 · 找到 ${list.childCount} 个"
                        }
                    }
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {scope.launch(Dispatchers.Main) {if(token==sourceScanToken) status.text="扫描失败：${e.message ?: "来源不可用"}"}}
        }
    }
    private fun addSourceOption(list: LinearLayout,item: JSONObject,picker: AlertDialog) {
        val rows=item.optString("remarks")
        val option=TextView(this).apply {
            text=item.optString("sourceName")+(if(rows.isBlank()) "" else "  ·  $rows")
            textSize=15f;setTextColor(textColor);setPadding(dp(14),dp(14),dp(14),dp(14))
            background=bordered(panelColor,12);foreground=ripple(surface(Color.TRANSPARENT,12),0x26E8A33D)
            isClickable=true;isFocusable=true;contentDescription=text
            setOnClickListener {picker.dismiss();switchSource(item)}
        }
        list.addView(option,LinearLayout.LayoutParams(-1,-2).apply {setMargins(0,dp(4),0,dp(4))})
    }
    /** 原地切换来源，保留当前返回路径与观看进度。 */
    private fun switchSource(item: JSONObject) {
        val target=item.optJSONObject("favoriteSource") ?: (0 until sources.length()).map {sources.getJSONObject(it)}
            .firstOrNull {it.optString("key")==item.optString("sourceKey")}
        if(target==null) {notice.text="该来源已不可用，请重新扫描";return}
        work("正在切换视频来源…") {
            source=target
            val result=request("/api/detail?id=${NasClient.encode(item.optString("vodId"))}&source=${NasClient.encode(target.toString())}")
            episodes=result.optJSONArray("episodes") ?: JSONArray()
            require(episodes.length()>0) {"该来源没有可播放集数"}
            detailInfo=result.optJSONObject("videoInfo") ?: JSONObject()
            selected=item;title=detailInfo.optString("title",item.optString("name"))
            details();notice.text="已切换到 ${target.optString("name")}"
        }
    }
    /** 与 TV 相同：启动时从采集服务同步后台维护的在线片源。 */
    private fun syncRemoteSources() {
        scope.launch {
            try {
                val remote=withContext(Dispatchers.IO) {runCatching {catalog.remoteSources()}.getOrNull()} ?: return@launch
                if(remote.length()==0) return@launch
                val added=withContext(Dispatchers.IO) {client.local.importSources(JSONObject().put("sources",remote).toString())}
                if(added<=0) return@launch
                sources=client.local.sources()
                notice.text="已从服务器同步 $added 个视频源"
                if(page=="config") login() else if(page=="settings") settingsScreen()
            } catch(e: Exception) { }
        }
    }
    private fun playLocal(index: Int) {
        val urls=(0 until episodes.length()).map { episodes.getString(it) }
        if(urls.sumOf { it.length } > 300000) { notice.text="剧集地址过长，请换一个来源"; return }
        startActivity(android.content.Intent(this,DirectPlayerActivity::class.java)
            .putStringArrayListExtra("episodes",ArrayList(urls)).putExtra("index",index).putExtra("title",title)
            .putExtra("source",source.toString()).putExtra("item",selected.toString()))
    }
    private fun watchingRow(entry: JSONObject, saved: JSONObject?, action: () -> Unit, menu: (() -> Unit)? = null) {
        val item=entry.getJSONObject("item")
        val card=section(); val row=buttonRow(card).apply {gravity=Gravity.CENTER_VERTICAL}
        poster(row,item.optString("pic"),dp(76),dp(108))
        val copy=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(8),0)}
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        copy.addView(text(entry.optString("title",item.optString("name")),18f).apply {maxLines=2;setTypeface(typeface,Typeface.BOLD)})
        copy.addView(text(PiankeUi.position(saved),13f).apply {setTextColor(secondaryColor)})
        PiankeUi.progress(copy,saved)
        button("▶",row,true,action).apply {contentDescription="播放 · "+entry.optString("title",item.optString("name"));layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))}
        card.isClickable=true;card.isFocusable=true;card.setOnClickListener {if(!busy)action()}
        if(menu!=null) {
            button("⋮",row) {menu()}.apply {contentDescription="管理 · "+entry.optString("title",item.optString("name"));layoutParams=LinearLayout.LayoutParams(dp(40),dp(48))}
            card.setOnLongClickListener {menu();true}
        }
    }
    private fun historyScreen() {
        screen("history","观看历史")
        val tabs=libraryTabs("历史")
        val history=PlaybackStore(this@PhoneActivity).all()
        if(history.length()==0) {
            val empty=section("还没有观看记录")
            empty.addView(text("找一部喜欢的，留给这一刻。",14f).apply {setTextColor(secondaryColor)})
            button("去搜索",empty,true){searchLanding()}
            return
        }
        tabs.addView(text("管理",13f).apply {
            setTextColor(secondaryColor);gravity=Gravity.CENTER;setPadding(dp(8),0,dp(4),0)
            isClickable=true;setOnClickListener {
                dialog=AlertDialog.Builder(this@PhoneActivity).setMessage("清空历史列表？来源和播放列表中的进度会保留。")
                    .setNegativeButton("取消",null).setPositiveButton("清空") {_,_->PlaybackStore(this@PhoneActivity).clear();historyScreen()}.show()
            }
        },LinearLayout.LayoutParams(dp(58),dp(38)))
        val first=history.getJSONObject(0);val item=first.getJSONObject("item")
        val hero=section("接着看")
        val art=FrameLayout(this).apply {background=surface();clipToOutline=true}
        hero.addView(art,LinearLayout.LayoutParams(-1,dp(if(wideTouch) 134 else 176)))
        ImageView(this).apply {scaleType=ImageView.ScaleType.CENTER_CROP;art.addView(this,FrameLayout.LayoutParams(-1,-1));posters.load(this,client,item.optString("pic"))}
        val copy=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(12));background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x000e0f11,0x660e0f11,0xf20e0f11.toInt()))}
        art.addView(copy,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        copy.addView(text(first.optString("title"),21f).apply {setTypeface(typeface,Typeface.BOLD)})
        copy.addView(text(PiankeUi.position(first),13f));PiankeUi.progress(hero,first)
        button("继续观看",hero,true) {detail(JSONObject(item.toString()).put("favoriteSource",first.getJSONObject("source")),true)}
        var group=""
        for(i in 1 until history.length()) {
            val entry=history.getJSONObject(i);val date=entry.optLong("updatedAt")
            val label=when {date<=0 -> "更早"; android.text.format.DateUtils.isToday(date)->"今天"; android.text.format.DateUtils.isToday(date+24*60*60*1000L)->"昨天";else->"更早"}
            if(label!=group){content.addView(text(label,16f).apply {setTextColor(secondaryColor);setPadding(0,dp(18),0,0)});group=label}
            watchingRow(entry,entry,{detail(JSONObject(entry.getJSONObject("item").toString()).put("favoriteSource",entry.getJSONObject("source")),true)})
        }
    }
    private fun playlistScreen() {
        screen("playlist","我的片单");libraryTabs("片单")
        val store=PlaybackStore(this@PhoneActivity);val entries=store.playlist()
        if(entries.length()==0) {
            val empty=section("片单还是空的")
            empty.addView(text("找一部想看的，留给下一次打开。",14f).apply {setTextColor(secondaryColor)})
            button("去搜索",empty,true){searchLanding()}
        }
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i);val item=entry.getJSONObject("item");val src=entry.getJSONObject("source");val saved=store.find(src,item)
            fun queued()=JSONObject(item.toString()).put("favoriteSource",src)
            watchingRow(entry,saved,{detail(queued(),true)},{
                libraryMenu(entry.optString("title"),listOf("查看剧集" to {detail(queued())},"移出列表" to {
                    dialog=AlertDialog.Builder(this).setMessage("移出片单？观看历史和进度会保留。")
                        .setNegativeButton("取消",null).setPositiveButton("移出") {_,_->work("正在移除…"){withContext(Dispatchers.IO){store.remove(entry.getString("id"))};playlistScreen()}}.show()
                }))
            })
        }
    }
    private fun chooseDevice(after: (() -> Unit)? = null) {
        work("正在搜索局域网设备（约 8 秒）…") {
            val discovered = castCommands.withLock { DLNACast.search(8000) }
            knownDevices = knownDevices + discovered.map { it.id }
            val devices = ReceiverDiscovery.receivers(discovered)
            if(devices.isEmpty()) { notice.text = "没有找到接收设备。请检查投屏设备已开机、手机与设备位于同一 Wi-Fi，以及路由器的设备隔离设置。"; return@work }
            notice.text = "找到 ${devices.size} 个接收设备"
            dialog = AlertDialog.Builder(this@PhoneActivity).setTitle("选择投屏设备")
                .setItems(devices.map { "${it.name} · ${it.address}" }.toTypedArray()) { _,index ->
                    device = devices[index]; notice.text = "已选择 ${devices[index].name}"
                    if(after != null) after()
                    else when(page) { "home" -> home(); "detail" -> details() }
                }.setNegativeButton("取消",null).show()
        }
    }
    private fun restoreCast() {
        if(castPrefs.getString("nas", "") != client.origin.toString()) { queue = null; castDevice = null; attached = false; return }
        if(queue != null) return
        try {
            val data = JSONObject(castPrefs.getString("session", "") ?: "")
            val urls = data.getJSONArray("urls")
            require(urls.length() in 1..2000)
            queue = PhoneCastQueue(data.getString("title"), (0 until urls.length()).map { urls.getString(it) }, data.getInt("index"), data.optString("poster"), data.getJSONObject("item").toString(), data.getJSONObject("source").toString())
            val target = data.getJSONObject("device")
            castDevice = DLNACast.Device(target.getString("id"), target.getString("name"), target.getString("address"), false)
            device = castDevice; attached = false
        } catch(e: Exception) { queue = null; castDevice = null; castPrefs.edit().clear().apply() }
    }
    private suspend fun saveCast() {
        val q = queue ?: return
        val target = castDevice ?: return
        val data = JSONObject().put("title", q.title).put("urls", JSONArray(q.urls)).put("index", q.index).put("poster", q.poster)
            .put("item", JSONObject(q.itemJson)).put("source", JSONObject(q.sourceJson))
            .put("device", JSONObject().put("id", target.id).put("name", target.name).put("address", target.address))
        val origin = client.origin.toString()
        withContext(Dispatchers.IO) { castPrefs.edit().putString("nas", origin).putString("session", data.toString()).commit() }
    }
    private suspend fun resolveTarget(target: DLNACast.Device): DLNACast.Device {
        if(target.id in knownDevices) return target
        val found = DLNACast.search(8000)
        knownDevices = knownDevices + found.map { it.id }
        return found.firstOrNull { it.id == target.id || (it.address == target.address && it.name == target.name) }
            ?: throw IllegalArgumentException("未找到上次投屏的设备，请确认设备开机且仍在同一局域网")
    }
    private suspend fun requestCast(next: PhoneCastQueue, target: DLNACast.Device): DLNACast.Device {
        val url = CastUrl.direct(next.urls[next.index], client.origin)
        val mime = if(java.net.URI(url).path.orEmpty().endsWith(".m3u8",true)) "application/vnd.apple.mpegurl" else "video/mp4"
        return castCommands.withLock {
            val receiver = resolveTarget(target)
            DLNACast.clearProgressCache()
            val accepted = DLNACast.castToDevice(receiver, url, next.label, CastOptions(mimeType = mime))
            require(accepted) { "投屏未成功：连接、播放指令或来源可能不兼容，请重试或更换来源" }
            receiver
        }
    }
    private suspend fun sendQueue(next: PhoneCastQueue, target: DLNACast.Device) {
        val resolved = requestCast(next, target)
        if(queue?.urls != next.urls || queue?.sourceJson != next.sourceJson) castPrefs.edit().remove("batch").apply()
        // Commit the episode only after the receiver accepted it; failed switches preserve the old controls.
        queue = next; castDevice = resolved; device = resolved; attached = true
        saveCast(); controls()
        notice.text = "已发送第 ${next.index+1} 集；请以电视实际播放为准。"
    }
    private fun cast(index: Int) {
        val snapshot = PhoneCastQueue(title, (0 until episodes.length()).map { episodes.getString(it) }, index,
            detailInfo.optString("cover").ifEmpty { selected.optString("pic") }, selected.toString(), source.toString())
        work("正在发送投屏请求…") { sendQueue(snapshot, device ?: throw IllegalArgumentException("请先选择设备")) }
    }
    private fun adjacent(delta: Int) {
        val q = queue ?: return
        val index = q.adjacent(delta) ?: return
        val target = castDevice ?: return
        work("正在切换到第 ${index+1} 集…") { sendQueue(q.at(index), target) }
    }
    private fun queueDetails() {
        val q = queue ?: return
        selected = JSONObject(q.itemJson); source = JSONObject(q.sourceJson); title = q.title
        episodes = JSONArray(q.urls); detailInfo = JSONObject().put("cover", q.poster)
        details()
    }
    private fun batchRecord(q: PhoneCastQueue): JSONObject? = try {
            val record = JSONObject(castPrefs.getString("batch", "") ?: "")
            if(record.optString("title") == q.title && record.optInt("urlsHash") == q.urls.hashCode() && record.optString("source") == q.sourceJson) record else null
    } catch(e: Exception) { null }
    private fun batchSummary(q: PhoneCastQueue): String {
        return try {
            val record = batchRecord(q) ?: return ""
            val count = record.optInt("count")
            val pending = record.optInt("pending", -1)
            when(record.optString("phase")) {
                "done" -> "上次发送完成，已确认发送 $count 集"
                "unconfirmed", "sending" -> "已确认发送 $count 集；第 ${pending+1} 集未能确认，请先检查投屏设备的播放列表再重试"
                else -> "上次添加已停止，已确认发送 $count 集"
            }
        } catch(e: Exception) { "" }
    }
    private suspend fun writeBatch(q: PhoneCastQueue, phase: String, start: Int, end: Int, count: Int, pending: Int = -1) {
        val record = JSONObject().put("title", q.title).put("urlsHash", q.urls.hashCode()).put("source", q.sourceJson)
            .put("phase", phase).put("start", start).put("end", end).put("count", count).put("pending", pending)
        withContext(Dispatchers.IO) { castPrefs.edit().putString("batch", record.toString()).commit() }
    }
    private fun batchScreen() {
        val snapshot = queue ?: return
        val target = castDevice ?: return
        screen("batch", "批量加入投屏列表")
        val ticket = generation
        val info = section()
        hero(info, snapshot.title, snapshot.poster, "${target.name}\n共 ${snapshot.urls.size} 集")
        info.addView(text("此功能仅适用于支持自定义 DLNA 播放列表的投屏设备，按单集投屏方式逐集发送。列表是否保留、播放及排序方式由设备决定；不支持的设备请使用单集投屏，无需使用此功能。", 14f).apply { setTextColor(secondaryColor) })
        val summary = batchSummary(snapshot)
        if(summary.isNotEmpty()) info.addView(text(summary, 14f).apply { setTextColor(accentColor) })
        val rangeBox = section("选择添加范围")
        val row = buttonRow(rangeBox)
        fun picker(label: String, value: Int): NumberPicker {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(text(label, 14f).apply { setTextColor(secondaryColor) })
            return NumberPicker(this).apply {
                minValue = 1; maxValue = snapshot.urls.size; this.value = value; wrapSelectorWheel = false
                contentDescription = label; column.addView(this, LinearLayout.LayoutParams(-1, dp(132)))
            }
        }
        val start = picker("开始集数", minOf(snapshot.index + 2, snapshot.urls.size))
        val end = picker("结束集数", snapshot.urls.size)
        val selection = text("")
        rangeBox.addView(selection)
        val status = text("发送期间请保持此页面打开；完成后即可离开 App。", 14f)
        rangeBox.addView(status)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1; progress = 0 }
        rangeBox.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        var lastRange: IntRange? = null
        val requiresRangeChange = batchRecord(snapshot)?.optString("phase") == "done"
        var rangeTouched = false
        lateinit var begin: Button
        lateinit var stop: Button
        fun updateRange() {
            val valid = start.value <= end.value
            selection.text = if(valid) "第 ${start.value}—${end.value} 集，共 ${end.value-start.value+1} 集" else "开始集数不能大于结束集数"
            begin.isEnabled = valid && !batchRunning && (!requiresRangeChange || rangeTouched) && (start.value-1..end.value-1) != lastRange
            begin.alpha = if(begin.isEnabled) 1f else .35f
        }
        begin = button("开始添加", rangeBox, true) {
            val range = start.value-1..end.value-1
            if(range.isEmpty()) return@button
            lastRange = range
            batchRunning = true; batchStopRequested = false
            start.isEnabled = false; end.isEnabled = false; begin.isEnabled = false; stop.isEnabled = true
            begin.alpha = .35f; stop.alpha = 1f
            progressBar.max = range.count(); progressBar.progress = 0
            window.decorView.keepScreenOn = true
            work("正在准备批量添加…") {
                var confirmed = 0
                var resolved = target
                polling?.cancel()
                try {
                    val result = PhoneCastBatch().run(range, { batchStopRequested }, { index ->
                        writeBatch(snapshot, "sending", range.first, range.last, confirmed, index)
                        status.text = "正在发送第 ${index+1} 集 · 已确认 $confirmed / ${range.count()} 集"
                    }, { index ->
                        resolved = requestCast(snapshot.at(index), resolved)
                        true
                    }, { index, count ->
                        queue = snapshot.at(index); castDevice = resolved; device = resolved; attached = true
                        saveCast()
                        confirmed = count
                        writeBatch(snapshot, "sent", range.first, range.last, count)
                        progressBar.progress = count
                        status.text = "已确认发送 $count / ${range.count()} 集"
                    })
                    val phase = when { result.unconfirmed != null -> "unconfirmed"; result.stopped -> "stopped"; else -> "done" }
                    writeBatch(snapshot, phase, range.first, range.last, result.accepted.size, result.unconfirmed ?: -1)
                    if(ticket == generation) {
                        status.text = when {
                            result.unconfirmed != null -> "已确认发送 ${result.accepted.size} 集；第 ${result.unconfirmed+1} 集未能确认，已停止。请先检查投屏设备的播放列表，避免重复添加。"
                            result.stopped -> "已停止添加，已确认发送 ${result.accepted.size} 集；不会停止投屏设备当前的播放。"
                            else -> "发送完成，已确认发送 ${result.accepted.size} 集。若设备支持自定义 DLNA 播放列表，可在设备或其配套 App 中查看；发送成功不代表已加入列表。"
                        }
                        notice.text = if(phase == "done") "批量发送完成" else "批量添加已停止"
                    }
                } finally {
                    batchRunning = false; window.decorView.keepScreenOn = false
                    if(ticket == generation) {
                        start.isEnabled = true; end.isEnabled = true; stop.isEnabled = false; stop.alpha = .35f; updateRange()
                    }
                }
            }
        }
        stop = button("停止添加", rangeBox) { }
        stop.isEnabled = false
        stop.alpha = .35f
        // This one action must remain available while other cast controls are busy.
        stop.setOnClickListener {
            if(batchRunning) {
                batchStopRequested = true; stop.isEnabled = false; stop.alpha = .35f
                status.text = "正在停止添加，等待本次发送结果…"
            }
        }
        start.setOnValueChangedListener { _,_,_ -> rangeTouched = true; updateRange() }
        end.setOnValueChangedListener { _,_,_ -> rangeTouched = true; updateRange() }
        updateRange()
        if(requiresRangeChange) status.text = "上次已发送完成；如需再次添加，请先调整范围，并核对投屏设备的播放列表以免重复。"
        button("返回投屏控制") { controls() }
        content.addView(text("此操作与逐次点下一集相同，接收器可能切换当前画面。已发送的项目不会由手机撤回，也不会自动重发。", 13f).apply { setTextColor(secondaryColor) })
    }
    private fun controls() {
        val q = queue ?: run { home(); return }
        screen("controls","投屏控制")
        val info = section()
        hero(info, q.title, q.poster, "最近发送 · 第 ${q.index+1} / ${q.urls.size} 集\n${castDevice?.name ?: "未知设备"}")
        progress = text(if(attached) "正在查询设备…" else "上次投屏记录 · 正在连接设备…", 15f)
        info.addView(progress)
        val switch = buttonRow(info)
        rowButton(switch, "上一集", q.adjacent(-1) != null) { adjacent(-1) }
        rowButton(switch, "下一集", q.adjacent(1) != null) { adjacent(1) }
        button("查看选集", info) { queueDetails() }

        val actions = section("播放与音量")
        fun command(row: LinearLayout, label: String, action: suspend () -> Boolean) = rowButton(row, label, attached) {
            work("正在发送控制指令…") {
                val ok = castCommands.withLock { action() }
                notice.text = if(ok) "指令已接受，以接收端实际状态为准" else "设备未接受指令，可能不支持此操作"
            }
        }
        val playback = buttonRow(actions)
        command(playback, "播放 / 继续") { DLNACast.play() }
        command(playback, "暂停") { DLNACast.pause() }
        val seek = buttonRow(actions)
        command(seek, "后退 30 秒") { val p=DLNACast.getProgressRealtime() ?: return@command false; DLNACast.seek((p.first-30000).coerceAtLeast(0)) }
        command(seek, "前进 30 秒") { val p=DLNACast.getProgressRealtime() ?: return@command false; DLNACast.seek(if(p.second>0) (p.first+30000).coerceAtMost(p.second) else p.first+30000) }
        actions.addView(text("音量", 14f).apply { setTextColor(secondaryColor) })
        val volume = SeekBar(this).apply { max=100; isEnabled = attached; actions.addView(this) }
        val ticket = generation
        if(attached) scope.launch {
            try { val value=castCommands.withLock { DLNACast.getVolume()?.first }; if(ticket == generation && value!=null) volume.progress=value }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket == generation) notice.text="音量查询暂不可用，其他操作仍可尝试" }
        }
        volume.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                work("正在调整音量…") { val ok=castCommands.withLock { DLNACast.setVolume(volume.progress) }; notice.text=if(ok) "音量指令已接受" else "设备不支持音量控制或连接已断开" }
            }
        })
        button("停止播放", actions) {
            work("正在停止播放…") {
                val ok = castCommands.withLock { DLNACast.stop() }
                if(ok) { queue = null; castDevice = null; attached = false; castPrefs.edit().clear().apply(); home(); notice.text = "已停止播放" }
                else notice.text = "未能停止设备，请先确认投屏仍在连接"
            }
        }.isEnabled = attached
        setting(content,"批量加入投屏列表","仅部分接收设备支持") {batchScreen()}
        button("返回选片（保留投屏）") { home() }
        content.addView(text("切到其他 App 或锁屏不会停止当前视频。手机显示最近发送的集数；若投屏设备支持自定义 DLNA 播放列表，可通过设备或配套 App 管理，不支持时使用单集投屏即可。", 13f).apply { setTextColor(secondaryColor) })
        if(attached) startPolling() else {
            work("正在重新连接上次的投屏…") {
                val target = castDevice ?: return@work
                val matched = castCommands.withLock {
                    val receiver = resolveTarget(target)
                    val uri = DLNACast.reconnectQueue(receiver, q.urls)
                    if(uri != null) { castDevice = receiver; queue = q.at(q.urls.indexOf(uri)) }
                    uri != null
                }
                if(ticket != generation) return@work
                attached = matched
                if(matched) { saveCast(); controls(); notice.text = "已恢复控制，未重新开始播放" }
                else { progress?.text = "设备已停止或正在播放其他内容"; notice.text = "未自动接管其他播放。可点选集或上一集、下一集重新投屏。" }
            }
        }
    }
    private fun startPolling() {
        polling?.cancel()
        if(page!="controls" || !attached) return
        polling = scope.launch {
            while(isActive) {
                try {
                    val (state,p) = castCommands.withLock { DLNACast.getPlaybackState() to DLNACast.getProgressRealtime() }
                    val name = when(state) {
                        DLNACast.PlaybackState.PLAYING -> "设备报告：播放中"
                        DLNACast.PlaybackState.PAUSED -> "设备报告：已暂停"
                        DLNACast.PlaybackState.BUFFERING -> "设备报告：缓冲中"
                        DLNACast.PlaybackState.STOPPED -> "设备报告：已停止"
                        DLNACast.PlaybackState.ERROR -> "设备报告：播放错误"
                        else -> "设备尚未报告播放"
                    }
                    progress?.text = name + (p?.let { "\n${clock(it.first)} / ${clock(it.second)}" } ?: "\n进度暂不可用")
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { progress?.text="设备状态查询失败，请检查接收设备" }
                delay(3000)
            }
        }
    }
    private fun clock(ms: Long): String { val s=(ms/1000).coerceAtLeast(0); return "%d:%02d".format(s/60,s%60) }
    override fun onStop() { polling?.cancel(); if(batchRunning) batchStopRequested = true; super.onStop() }
    override fun onStart() { super.onStart(); startPolling() }
    override fun onResume() {
        super.onResume()
        // 从播放器返回只刷新详情页进度；首页/片库在恢复时重建会打断滚动并让海报重新加载。
        if(::client.isInitialized && !busy && page=="detail") details()
    }
    @Deprecated("Legacy Activity back navigation")
    override fun onBackPressed() { navigateBack() }
    private fun navigateBack() {
        if(busy) { notice.text="正在操作，请稍候；退出可使用系统主页键"; return }
        when(page) { "batch" -> controls(); "controls" -> home(); "detail" -> if(query.isNotEmpty()) searchScreen() else home(); "config" -> settingsScreen(); "search-landing", "search", "playlist", "history", "category", "settings" -> home(); else -> super.onBackPressed() }
    }
    override fun onDestroy() {
        dialog?.dismiss(); posters.close(); scope.cancel()
        // An old Activity may finish after the replacement has initialized the shared engine.
        if(castOwner?.get() === this) { DLNACast.cleanup(); castOwner = null }
        super.onDestroy()
    }
}

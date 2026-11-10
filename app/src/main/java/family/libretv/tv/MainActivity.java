package family.libretv.tv;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.text.Html;
import android.view.inputmethod.EditorInfo;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private enum Screen { CONFIG, LOGIN, SEARCH, PLAYLIST, SETTINGS, PAIRING, DETAIL, PLAYER }
    private Screen screen = Screen.CONFIG;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService updateChecks = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searches = Executors.newSingleThreadExecutor();
    private final ExecutorService sourceScans = Executors.newCachedThreadPool();
    private Future<?> searchFuture;
    private int searchToken, searchCompleted, searchTotal, searchFailed;
    private boolean searching;
    private TextView searchSummary;
    private Button stopSearchButton;
    private View navVoice;
    private Screen detailReturn = Screen.SEARCH;
    private CheckBox autoNext;
    private long lastProgressSave;
    private int generation;
    private int sourcePickerToken;
    private boolean busy, stopped;
    private PhonePairingServer pairingServer;
    private ImageView pairingQr;
    private android.app.AlertDialog pairingConfirmation;
    private Screen pairingReturn = Screen.SETTINGS;
    private final Runnable expirePairing = () -> {
        if (screen == Screen.PAIRING) { closePairing(); setNotice("二维码已过期，请重新生成。"); }
    };
    private AppUpdater.Release availableUpdate;
    private java.io.File pendingUpdate;
    private int pendingUpdateCode;
    private LinearLayout root, content;
    private TextView message;
    private NasClient client;
    private JSONArray sources = new JSONArray(), results = new JSONArray(), episodes = new JSONArray();
    private JSONObject selected;
    private JSONObject detailInfo = new JSONObject();
    private final PosterLoader posters = new PosterLoader();
    private RecyclerView resultsView;
    private GridLayoutManager resultsLayout;
    private int resultColumns, resultFocusTicket;
    private View searchTarget;
    /** 语音搜索：当前搜索输入框、录音器、聆听弹窗与一次识别的会话号。 */
    private EditText searchField;
    private VoiceSearch.Recorder recorder;
    private android.app.AlertDialog voiceDialog;
    private int voiceToken;
    private final ExecutorService voiceWorker = Executors.newSingleThreadExecutor();
    private final Runnable voicePoll = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            if (recorder.running()) { main.postDelayed(this, 200); return; }
            finishListening();
        }
    };
    private final Set<String> enabledSources = new HashSet<>();
    private String query = "", title = "", mime;
    private int resultIndex, episodeIndex;
    private URI playbackUri;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView playbackStatus;
    private LinearLayout playerTop, playerControls;
    private Button playToggle, subtitleButton, speedButton;
    private TextView seekPreview;
    private int tvSeekKey=KeyEvent.KEYCODE_UNKNOWN;
    private long lastTvSeekEvent;
    private final Runnable clearSeekPreview=()->{if(seekPreview!=null)seekPreview.setVisibility(View.GONE);};
    private SubtitleMenu subtitleMenu;
    private SegmentSkipController segmentSkip;
    private android.app.AlertDialog playerOptions;
    private JSONObject detailSource;
    private boolean detailFromHome;
    private DiscoveryRepository discovery;
    private final ExecutorService relatedWorker = Executors.newSingleThreadExecutor();
    private ProgressBar playbackProgress;
    private boolean controlsVisible, playerHasFrame;
    private final Runnable hidePlayerControls = () -> {
        if (screen == Screen.PLAYER && player != null && player.isPlaying() && (subtitleMenu == null || !subtitleMenu.isShowing()) && (playerOptions == null || !playerOptions.isShowing()) && (segmentSkip == null || !segmentSkip.isCountingDown())) hideControls();
    };
    private long position;
    private boolean playWhenReady = true;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && playbackStatus != null) {
                String state = player.getPlaybackState() == Player.STATE_BUFFERING ? "缓冲中" : player.isPlaying() ? "播放中" : "已暂停";
                playbackStatus.setText(state + "   " + time(player.getCurrentPosition()) + " / " + time(player.getDuration()));
                long duration = player.getDuration();
                playbackProgress.setProgress(duration > 0 ? (int) (player.getCurrentPosition() * 1000 / duration) : 0);
                playToggle.setText(player.getPlayWhenReady() ? "暂停" : "播放");playToggle.setContentDescription(playToggle.getText());
                PlayerStyle.setTvIcon(playToggle,player.getPlayWhenReady()?"pause":"play",22);
                if(SystemClock.elapsedRealtime()-lastProgressSave>=5000) { saveTvProgress(); lastProgressSave=SystemClock.elapsedRealtime(); }
                main.postDelayed(this, 1000);
            }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.app.UiModeManager modes = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (modes.getCurrentModeType() != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                && !getIntent().hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            startActivity(new android.content.Intent(this, PhoneActivity.class)); finish(); return;
        }
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        if(!getIntent().hasExtra("tvDestination")){
            startActivity(new android.content.Intent(this,TvHomeActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();return;
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            client=new NasClient(this);discovery=new DiscoveryRepository(this,client.local);sources=client.local.sources();loadSourceSelection();showSearch();
            String destination=getIntent().getStringExtra("tvDestination");
            if("playlist".equals(destination))showPlaylist();
            else if("settings".equals(destination))showSettings();
            else if("voice".equals(destination))startVoiceSearch();
            else if("detail".equals(destination)){
                detailFromHome=true;
                loadDetail(new JSONObject(getIntent().getStringExtra("detailItem")),0);
            }
            else if("resume".equals(destination)){
                JSONObject entry=new JSONObject(getIntent().getStringExtra("resumeEntry"));
                loadDetail(new JSONObject(entry.getJSONObject("item").toString()).put("favoriteSource",entry.getJSONObject("source")),0,true);
            }else if(getIntent().hasExtra("query")){
                EditText keyword=new EditText(this);keyword.setText(getIntent().getStringExtra("query"));performSearch(keyword);
            }
        }
        catch(Exception e) { showConfig(); setNotice(e.getMessage()); }
        UpdateActivity.checkInBackground(this);
    }
    private boolean hasHouseholdPassword() {
        return !BuildConfig.DEFAULT_NAS_PASSWORD.isEmpty()
                && NasAddress.sameOrigin(NasAddress.parse(BuildConfig.DEFAULT_NAS_URL), client.origin);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /* 星光影院：哑光深灰 + 暖琥珀，仅用于详情页视觉 */
    private static final int SBG=0xff0e0f11,SSURFACE=0xff16181b,SSURFACE_2=0xff1e2126,SWASH=0x1fe8a33d;
    private static final int STEXT_1=0xfff3f4f6,STEXT_2=0xffa8aeb6,STEXT_3=0xff6d737b;
    private static final int SACCENT=0xffe8a33d,SACCENT_HI=0xfff2b95a;
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private StateListDrawable selectable(int normal,int radius){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{-android.R.attr.state_enabled},rounded(0xff141619,radius));
        GradientDrawable focused=rounded(SSURFACE_2,radius);focused.setStroke(dp(2),SACCENT);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{},rounded(normal,radius));return d;
    }
    private void clipRounded(View view,int radius){
        float r=dp(radius);
        view.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),r);}});
        view.setClipToOutline(true);
    }
    private TextView starlightText(String value,int size,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private StateListDrawable detailPrimaryState(){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},rounded(SACCENT_HI,999));
        d.addState(new int[]{},rounded(SACCENT,999));return d;
    }
    private Button detailButton(LinearLayout parent,String text,boolean primary,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(16);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(26),0,dp(26),0);b.setFocusableInTouchMode(true);
        b.setTextColor(primary?0xff1a1206:STEXT_1);
        b.setBackground(primary?detailPrimaryState():selectable(SSURFACE,999));
        b.setOnFocusChangeListener((v,focused)->{
            v.setElevation(focused?dp(6):0);
            if(!primary)b.setTextColor(focused?SACCENT:STEXT_1);
        });
        b.setOnClickListener(v->{if(!busy)action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(52));lp.setMargins(0,dp(16),dp(12),0);
        parent.addView(b,lp);return b;
    }
    private StateListDrawable episodeState(boolean current){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=rounded(SSURFACE_2,14);focused.setStroke(dp(2),SACCENT);
        GradientDrawable active=rounded(SWASH,14);active.setStroke(dp(2),0x80e8a33d);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{},current?active:rounded(SSURFACE,14));return d;
    }
    private void starlightProgress(LinearLayout parent,JSONObject saved){
        if(saved==null||saved.optLong("duration")<=0)return;
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);
        bar.setProgress((int)Math.min(1000,Math.max(0,(double)saved.optLong("position")/saved.optLong("duration")*1000)));
        bar.setProgressTintList(ColorStateList.valueOf(SACCENT));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33383c42));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(4));p.setMargins(0,dp(8),0,0);parent.addView(bar,p);
    }
    /** 详情页专用：把共享外壳的底色与顶部导航切到星光影院视觉，其他页面构建方式不变。 */
    private void starlightChrome(){starlightChrome(true);}
    /** 顶部导航在 page() 里已按星光影院样式构建，这里只需切换底色与标题。 */
    private void starlightChrome(boolean hideHeading){
        root.setBackgroundColor(SBG);
        if(root.getChildCount()>1&&root.getChildAt(1) instanceof TextView){
            TextView heading=(TextView)root.getChildAt(1);
            if(hideHeading)heading.setVisibility(View.GONE);
            else{
                heading.setTextSize(28);heading.setTextColor(STEXT_1);
                heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                heading.setPadding(dp(6),dp(16),dp(6),dp(12));
            }
        }
    }
    /** 顶部导航文字项：与 TV 首页导航保持同一套焦点与选中样式。 */
    private View navText(LinearLayout bar,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_selected},new int[]{}},new int[]{SACCENT,SACCENT,STEXT_2}));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(14),0,dp(14),0);b.setBackground(navState());b.setContentDescription("顶部导航 · "+label);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v,focused)->{v.setElevation(focused?dp(8):0);if(focused&&screen==Screen.SEARCH){resultFocusTicket++;hideKeyboard();}});
        b.setOnClickListener(v->{if(!busy)action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(40));lp.setMargins(dp(2),0,dp(2),0);bar.addView(b,lp);
        return b;
    }
    /** 顶部导航图标项：搜索与语音用图标代替文字。 */
    private View navIcon(LinearLayout bar,int iconRes,String label,Runnable action){
        FrameLayout box=new FrameLayout(this);
        ImageView icon=new ImageView(this);icon.setImageResource(iconRes);icon.setDuplicateParentStateEnabled(true);
        icon.setImageTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_selected},new int[]{}},new int[]{SACCENT,SACCENT,STEXT_2}));
        box.addView(icon,new FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER));
        box.setContentDescription("顶部导航 · "+label);box.setBackground(navState());
        box.setFocusableInTouchMode(true);box.setFocusable(true);box.setClickable(true);
        box.setOnFocusChangeListener((v,focused)->{v.setElevation(focused?dp(8):0);if(focused&&screen==Screen.SEARCH){resultFocusTicket++;hideKeyboard();}});
        box.setOnClickListener(v->{if(!busy)action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(46),dp(40));lp.setMargins(dp(2),0,dp(2),0);bar.addView(box,lp);
        return box;
    }
    private void openHome(String target){
        Intent intent=new Intent(this,TvHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(target!=null)intent.putExtra("page",target);
        startActivity(intent);finish();
    }
    private StateListDrawable navState(){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=rounded(SSURFACE_2,999);focused.setStroke(dp(2),SACCENT);
        GradientDrawable selected=rounded(SWASH,999);selected.setStroke(dp(2),0x80e8a33d);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{android.R.attr.state_selected},selected);
        d.addState(new int[]{},rounded(0x00000000,999));return d;
    }
    private StateListDrawable fieldBackground(){
        StateListDrawable bg=new StateListDrawable();
        GradientDrawable focused=rounded(SSURFACE_2,14);focused.setStroke(dp(2),SACCENT);
        GradientDrawable normal=rounded(SSURFACE,14);normal.setStroke(dp(1),0xff2a2e35);
        bg.addState(new int[]{android.R.attr.state_focused},focused);
        bg.addState(new int[]{},normal);return bg;
    }
    private StateListDrawable tabState(){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=rounded(SSURFACE_2,12);focused.setStroke(dp(2),SACCENT);
        GradientDrawable selected=rounded(SWASH,12);selected.setStroke(dp(2),0x80e8a33d);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{android.R.attr.state_selected},selected);
        d.addState(new int[]{},rounded(0x00000000,12));return d;
    }
    private Button starlightButton(LinearLayout parent,String text,boolean tab,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(16);
        b.setFocusableInTouchMode(true);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setPadding(dp(18),dp(8),dp(18),dp(8));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_selected},new int[]{}},new int[]{0x55ffffff,SACCENT,SACCENT,STEXT_1}));
        b.setBackground(tab?tabState():selectable(SSURFACE,12));
        b.setOnFocusChangeListener((v,focused)->{v.setElevation(focused?dp(4):0);if(focused&&screen==Screen.SEARCH){resultFocusTicket++;hideKeyboard();}});
        b.setOnClickListener(v->{if(!busy)action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.HORIZONTAL?-2:-1,-2);
        lp.setMargins(dp(4),dp(5),dp(4),dp(5));parent.addView(b,lp);return b;
    }
    private Button starlightPrimary(LinearLayout parent,String text,Runnable action){
        Button b=detailButton(parent,text,true,action);
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)b.getLayoutParams();lp.width=-1;lp.height=dp(54);b.setLayoutParams(lp);return b;
    }
    private Button starlightSmallPrimary(LinearLayout parent,String text,Runnable action){
        Button b=detailButton(parent,text,true,action);
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)b.getLayoutParams();
        lp.width=-2;lp.height=dp(50);lp.setMargins(dp(8),0,dp(8),0);b.setLayoutParams(lp);
        b.setPadding(dp(22),0,dp(22),0);return b;
    }
    private LinearLayout settingRow(LinearLayout parent,String title,String detail,Runnable action){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(13),dp(20),dp(13));box.setFocusableInTouchMode(true);box.setClickable(true);
        box.setContentDescription(title);box.setBackground(selectable(SSURFACE,14));
        box.setOnFocusChangeListener((v,focused)->v.setElevation(focused?dp(4):0));
        box.setOnClickListener(v->{if(!busy)action.run();});
        TextView name=starlightText(title,18,STEXT_1);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));box.addView(name);
        if(detail!=null&&!detail.isEmpty()){TextView sub=starlightText(detail,13,STEXT_3);sub.setPadding(0,dp(4),0,0);box.addView(sub);}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(4),0,dp(4),dp(10));parent.addView(box,lp);return box;
    }
    private EditText starlightInput(LinearLayout parent,String hint,String value,int type){
        EditText edit=new EditText(this);edit.setHint(hint);edit.setText(value);edit.setSingleLine(false);
        edit.setTextSize(16);edit.setInputType(type);edit.setSaveEnabled(false);
        edit.setTextColor(STEXT_1);edit.setHintTextColor(STEXT_3);edit.setPadding(dp(18),dp(14),dp(18),dp(14));
        edit.setBackground(fieldBackground());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(120));lp.setMargins(dp(4),dp(12),dp(4),dp(8));parent.addView(edit,lp);return edit;
    }
    private void emptyState(LinearLayout parent,String title,String detail){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40),dp(44),dp(40),dp(44));box.setBackground(rounded(SSURFACE,18));box.setFocusable(false);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.spark_mark);box.addView(mark,new LinearLayout.LayoutParams(dp(46),dp(46)));
        TextView name=starlightText(title,21,STEXT_1);name.setGravity(Gravity.CENTER);
        name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setPadding(0,dp(16),0,0);box.addView(name);
        TextView sub=starlightText(detail,14,STEXT_3);sub.setGravity(Gravity.CENTER);sub.setPadding(0,dp(8),0,0);box.addView(sub);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);parent.addView(box,lp);
    }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(228, 238, 246));
        v.setPadding(dp(6), dp(6), dp(6), dp(6)); return v;
    }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v;
    }
    private void page(Screen next, String heading) {
        closePairing();
        generation++; busy = false; screen = next;
        main.removeCallbacks(hidePlayerControls);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(18), dp(40), dp(18)); root.setBackgroundColor(PiankeUi.BACKGROUND);
        LinearLayout navigation=row();navigation.setGravity(Gravity.CENTER_VERTICAL);root.addView(navigation);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.spark_mark);mark.setContentDescription("星光影院");
        navigation.addView(mark,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView wordmark=starlightText("星光影院",21,STEXT_1);
        wordmark.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        wordmark.setLetterSpacing(0.02f);wordmark.setPadding(dp(10),0,dp(26),0);navigation.addView(wordmark);
        View navHome=navText(navigation,"首页",()->openHome(null));
        View navLibrary=navText(navigation,"片库",()->openHome("browse"));
        View navRank=navText(navigation,"榜单",()->openHome("rank"));
        View navSearch=navIcon(navigation,R.drawable.ic_nav_search,"搜索",this::showSearch);
        navVoice=navIcon(navigation,R.drawable.ic_nav_mic,"语音搜索",this::startVoiceSearch);
        TextView clock=starlightText(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new java.util.Date()),14,STEXT_3);
        clock.setGravity(Gravity.END);clock.setPadding(0,0,dp(18),0);
        navigation.addView(clock,new LinearLayout.LayoutParams(0,-2,1));
        View navHistory=navText(navigation,"历史",()->openHome("history"));
        View navSettings=navText(navigation,"设置",this::showSettings);
        View activeNav=next==Screen.SETTINGS||next==Screen.CONFIG||next==Screen.PAIRING?navSettings
                :next==Screen.PLAYLIST?navHistory:next==Screen.SEARCH?navSearch:navHome;
        activeNav.setSelected(true);
        root.addView(label(heading.replace("星光影院   /   ","").replace("星光影院 · ",""), 24));
        message = label("", 15); message.setVisibility(View.GONE);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(message);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    private void setNotice(String text) {
        message.setText(text);
        message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void focusLink(View from, int key, View target) {
        from.setOnKeyListener((v, code, event) -> {
            if (code == key && event.getAction() == KeyEvent.ACTION_DOWN) { target.requestFocus(); return true; }
            return false;
        });
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(17); b.setTextColor(Color.WHITE);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v, focused) -> {
            if (focused) { if (screen == Screen.SEARCH) resultFocusTicket++; hideKeyboard(); }
        });
        b.setAllCaps(false); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), dp(10), dp(18), dp(10)); b.setMinHeight(dp(50)); b.setBackgroundResource(R.drawable.focus_button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? -2 : -1, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); parent.addView(b, lp);
        b.setOnClickListener(v -> { if (!busy) action.run(); }); return b;
    }
    private EditText input(LinearLayout parent, String hint, String value, int type) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setSingleLine(true);
        edit.setTextSize(19); edit.setInputType(type); edit.setSaveEnabled(false);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(58))); return edit;
    }
    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }
    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value) throws Exception; }
    private <T> void task(String hint, Work<T> work, Done<T> done) {
        if (busy) return;
        busy = true; setNotice(hint + " · 返回键可退出等待"); int ticket = generation;
        network.execute(() -> {
            try {
                T value = work.run();
                main.post(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    busy = false;
                    try { setNotice(""); done.accept(value); }
                    catch (Exception ex) { error(ex); }
                });
            } catch (Exception ex) {
                main.post(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    busy = false; error(ex);
                });
            }
        });
    }
    private void error(Exception ex) {
        if (ex instanceof NasClient.ApiException && ((NasClient.ApiException) ex).status == 401 && screen != Screen.LOGIN && screen != Screen.PAIRING) {
            client.clearSession(); showLogin(); setNotice("会话已过期，请重新登录"); return;
        }
        setNotice("操作失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }
    private void showConfig() {
        page(Screen.CONFIG, "来源配置");
        starlightChrome(false);
        TextView intro=starlightText("独立运行，无需 NAS。默认源可删除；支持粘贴订阅地址或 JSON。",15,STEXT_2);
        intro.setPadding(dp(6),dp(8),dp(6),dp(4));content.addView(intro);
        EditText value=starlightInput(content,"订阅地址或 JSON","",InputType.TYPE_CLASS_TEXT);
        starlightPrimary(content,"导入配置",()->task("正在导入…",()->client.local.importSources(value.getText().toString()),count->{
            sources=client.local.sources();loadSourceSelection();showSearch();setNotice("已新增 "+count+" 个来源");
        }));
        starlightButton(content,"手机扫码填写配置",false,this::showSourcePairing);
        starlightButton(content,"返回选片",false,this::homeOrSearch);
    }
    private void showSourcePairing() {
        page(Screen.PAIRING,"扫码添加来源");
        starlightChrome(false);
        try {
            List<String> addresses=PhonePairingServer.localAddresses();
            if(addresses.isEmpty())throw new Exception("请先连接局域网");
            pairingServer=new PhonePairingServer(addresses.get(0),(PhonePairingServer.SourceSubmission)value->main.post(()->{
                if(screen!=Screen.PAIRING)return;
                pairingConfirmation=new android.app.AlertDialog.Builder(this).setTitle("导入手机提交的来源？").setMessage("将在本机解析并保存，不需要 NAS。")
                    .setNegativeButton("取消",(d,w)->showConfig()).setPositiveButton("导入",(d,w)->task("正在导入…",()->client.local.importSources(value),count->{sources=client.local.sources();loadSourceSelection();showSearch();setNotice("已新增 "+count+" 个来源");})).show();
            }));
            pairingQr=new ImageView(this);pairingQr.setImageBitmap(PairingQr.create(pairingServer.url()));
            pairingQr.setBackground(rounded(SSURFACE,16));pairingQr.setPadding(dp(12),dp(12),dp(12),dp(12));
            content.addView(pairingQr,new LinearLayout.LayoutParams(dp(260),dp(260)));
            TextView hint=starlightText("手机和电视同一局域网，扫码填写订阅或 JSON。二维码 5 分钟有效。",15,STEXT_2);
            hint.setPadding(dp(6),dp(12),dp(6),dp(6));content.addView(hint);
            starlightButton(content,"返回",false,this::showConfig);main.postDelayed(expirePairing,PhonePairingServer.LIFETIME_MS);
        }catch(Exception e){setNotice(e.getMessage());}
    }
    private void showLogin() {
        page(Screen.LOGIN, "星光影院 · 登录 NAS");
        content.addView(label(client.origin.toString(), 17));
        EditText password = input(content, "NAS 访问密码（不保存）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login = button(content, "登录", () -> {
            String secret = password.getText().toString();
            if (secret.isEmpty()) { setNotice("请输入 NAS 访问密码"); return; }
            password.setText(""); hideKeyboard();
            signIn(secret, false);
        });
        if (hasHouseholdPassword()) button(content, "使用家庭预设登录", () -> signIn(BuildConfig.DEFAULT_NAS_PASSWORD, true));
        addPhonePairingButton();
        button(content, "修改 NAS 地址", this::showConfig);
        login.requestFocus();
    }
    private void signIn(String secret, boolean automatic) {
        NasClient active = client;
        task(automatic ? "正在自动连接家庭 NAS" : "正在登录并读取点播源", () -> {
            active.clearSession();
            JSONObject auth = active.request("/api/auth", new JSONObject().put("password", secret));
            if (!auth.optBoolean("success")) throw new Exception("NAS 拒绝登录");
            return active.sources(active.request("/api/status", null));
        }, list -> {
            getPreferences(MODE_PRIVATE).edit().putString("nas", active.origin.toString()).apply();
            sources = list; results = new JSONArray(); loadSourceSelection();
            showSearch();
            checkUpdateQuietly(active);
        });
    }

    private void showSearch() {
        page(Screen.SEARCH, "搜索");
        starlightChrome(false);
        root.removeViewAt(root.getChildCount() - 1);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout searchRow = row(); searchRow.setGravity(Gravity.CENTER_VERTICAL); content.addView(searchRow);
        EditText keywords = input(searchRow, "搜索电影、剧集…", query, InputType.TYPE_CLASS_TEXT);
        keywords.setId(View.generateViewId()); keywords.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        keywords.setTextSize(18); keywords.setTextColor(STEXT_1); keywords.setHintTextColor(STEXT_3);
        keywords.setPadding(dp(18), 0, dp(18), 0); keywords.setBackground(fieldBackground());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(58), 1);
        inputParams.setMargins(dp(4), dp(10), dp(12), dp(6)); keywords.setLayoutParams(inputParams);
        Button search = starlightButton(searchRow, "搜索", false, () -> performSearch(keywords));
        search.setId(View.generateViewId()); search.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams searchParams = (LinearLayout.LayoutParams) search.getLayoutParams();
        searchParams.width = dp(132); searchParams.height = dp(58); searchParams.setMargins(dp(4), dp(10), dp(10), dp(6)); search.setLayoutParams(searchParams);
        Button voice = starlightButton(searchRow, "语音", false, this::startVoiceSearch);
        voice.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams voiceParams = (LinearLayout.LayoutParams) voice.getLayoutParams();
        voiceParams.width = dp(104); voiceParams.height = dp(58);
        voiceParams.setMargins(dp(4), dp(10), dp(4), dp(6)); voice.setLayoutParams(voiceParams);
        stopSearchButton = starlightButton(searchRow, "停止搜索", false, () -> { cancelSearch(); updateSearchSummary(); });
        stopSearchButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stopParams = (LinearLayout.LayoutParams) stopSearchButton.getLayoutParams();
        stopParams.height = dp(58); stopParams.setMargins(dp(4), dp(10), dp(4), dp(6)); stopSearchButton.setLayoutParams(stopParams);
        stopSearchButton.setEnabled(searching);
        LinearLayout navigation = row(); content.addView(navigation);
        Button library = starlightButton(navigation, "我的片单", false, this::showPlaylist);
        Button settings = starlightButton(navigation, "设置", false, this::showSettings);
        if (availableUpdate != null) settings.setText("设置 · 有更新");
        searchTarget = search;
        // EditText's built-in caret navigation and FocusFinder disagree on this TV layout.
        // Define both axes explicitly: UP from Search always reaches the search field.
        search.setNextFocusUpId(keywords.getId()); search.setNextFocusLeftId(keywords.getId());
        keywords.setNextFocusDownId(search.getId());
        focusLink(keywords, KeyEvent.KEYCODE_DPAD_DOWN, search);
        searchField = keywords;
        search.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) { keywords.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { voice.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (results.length() > 0) focusResult(0, 0);else library.requestFocus();
                return true;
            }
            return false;
        });
        voice.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { search.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) { keywords.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (searching) stopSearchButton.requestFocus(); else library.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { if (results.length() > 0) focusResult(0, 0); else library.requestFocus(); return true; }
            return false;
        });
        stopSearchButton.setOnKeyListener((v,code,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(code==KeyEvent.KEYCODE_DPAD_LEFT){voice.requestFocus();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_DOWN){library.requestFocus();return true;}
            return false;
        });
        for(int n=0;n<navigation.getChildCount();n++) {
            View link=navigation.getChildAt(n);
            link.setOnKeyListener((v,code,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(code==KeyEvent.KEYCODE_DPAD_UP){search.requestFocus();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_LEFT && v==library){search.requestFocus();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_DOWN && results.length()>0){focusResult(0,0);return true;}
                return false;
            });
        }
        keywords.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                    || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                search.performClick(); return true;
            }
            return false;
        });
        searchSummary=starlightText("",13,STEXT_3);searchSummary.setPadding(dp(6),dp(8),dp(6),dp(4));content.addView(searchSummary);updateSearchSummary();
            int width = getResources().getConfiguration().screenWidthDp;
            resultColumns = width >= 1000 ? 6 : width >= 800 ? 5 : width >= 600 ? 4 : 3;
            int posterHeight = dp((int) (((width - 80f) / resultColumns - 22) * 1.5f));
            resultsView = new RecyclerView(this); resultsView.setFocusable(false); resultsView.setClipToPadding(false);
            resultsView.setPadding(0, dp(8), 0, dp(10)); resultsView.setItemAnimator(null);
            resultsLayout = new GridLayoutManager(this, resultColumns); resultsView.setLayoutManager(resultsLayout);
            resultsView.setAdapter(new RecyclerView.Adapter<PosterHolder>() {
                @Override public PosterHolder onCreateViewHolder(ViewGroup parent, int type) {
                    PosterCard card = new PosterCard(MainActivity.this, fitPosterHeight(posterHeight));
                    RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
                    params.setMargins(dp(5), dp(5), dp(5), dp(12)); card.setLayoutParams(params);
                    return new PosterHolder(card);
                }
                @Override public void onBindViewHolder(PosterHolder holder, int position) {
                    JSONObject item = results.optJSONObject(position); PosterCard card = holder.card;
                    card.title.setText(item.optString("name")); card.source.setText(item.optString("sourceName"));
                    String extra = item.optString("year"); String remarks = item.optString("remarks");
                    card.metadata.setText(extra.isEmpty() ? remarks : remarks.isEmpty() ? extra : extra + "  ·  " + remarks);
                    card.setContentDescription(item.optString("name") + "，" + item.optString("sourceName") + "，" + remarks);
                    posters.load(card.image, client, item.optString("pic"));
                    card.onFocused = () -> { int current = holder.getBindingAdapterPosition(); if (current != RecyclerView.NO_POSITION) resultIndex = current; };
                    card.setOnClickListener(v -> {
                        int current = holder.getBindingAdapterPosition();
                        if (!busy && current != RecyclerView.NO_POSITION) loadDetail(results.optJSONObject(current), current);
                    });
                    card.setOnKeyListener((v, key, event) -> {
                        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                        int index = holder.getBindingAdapterPosition();
                        if (index == RecyclerView.NO_POSITION) return false;
                        if (key == KeyEvent.KEYCODE_DPAD_UP) { if (index < resultColumns) search.requestFocus(); else focusResult(index - resultColumns, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (index + resultColumns < results.length()) focusResult(index + resultColumns, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (index % resultColumns > 0) focusResult(index - 1, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (index % resultColumns < resultColumns - 1 && index + 1 < results.length()) focusResult(index + 1, 0); return true; }
                        return false;
                    });
                }
                @Override public int getItemCount() { return results.length(); }
            });
            resultsView.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
                if(bottom-top==oldBottom-oldTop)return;
                int height=fitPosterHeight(posterHeight);
                for(int i=0;i<resultsView.getChildCount();i++) {
                    View child=resultsView.getChildAt(i);
                    if(child instanceof PosterCard) {
                        ImageView image=((PosterCard)child).image;
                        if(image.getLayoutParams().height!=height){ViewGroup.LayoutParams params=image.getLayoutParams();params.height=height;image.setLayoutParams(params);}
                    }
                }
            });
            content.addView(resultsView, new LinearLayout.LayoutParams(-1, 0, 1));
        if (sources.length() == 0) setNotice("暂无可用来源，请在设置中导入来源。");
        // 进入搜索页时默认选中顶部的语音图标，按确认即可直接说话搜索。
        if (navVoice != null) { navVoice.setNextFocusDownId(keywords.getId()); navVoice.requestFocus(); }
        else if (results.length() > 0) focusResult(Math.min(resultIndex, results.length() - 1), 0);
        else search.requestFocus();
    }
    /* —— 语音搜索：录音 → Whisper 转写 → 大模型整理关键词 → 复用现有搜索 —— */
    private void startVoiceSearch() {
        if (screen != Screen.SEARCH) { showSearch(); }
        if (VoiceSearch.key(this).isEmpty()) {
            showVoiceSettings();
            setNotice("语音搜索需要先填写硅基流动 API Key，免费注册 cloud.siliconflow.cn");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 73);
            return;
        }
        beginListening();
    }
    private void beginListening() {
        if (screen != Screen.SEARCH) return;
        stopListening(false);
        try {
            recorder = new VoiceSearch.Recorder();
        } catch (Exception e) {
            setNotice("语音搜索不可用：" + (e.getMessage() == null ? "麦克风打开失败" : e.getMessage()));
            return;
        }
        recorder.start();
        voiceDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("正在聆听")
                .setMessage("请说出想看的影片，例如「我想看流浪地球」")
                .setNegativeButton("取消", (d, w) -> stopListening(false))
                .create();
        voiceDialog.setOnDismissListener(d -> { if (recorder != null) stopListening(false); });
        voiceDialog.show();
        main.postDelayed(voicePoll, 200);
    }
    private void finishListening() {
        VoiceSearch.Recorder active = recorder;
        if (active == null) return;
        recorder = null;
        main.removeCallbacks(voicePoll);
        byte[] wav = active.stop();
        android.util.Log.d("VoiceSearch", "peak=" + active.peak() + " bytes=" + wav.length);
        if (active.peak() < 40) {
            setNotice("没有录到声音，请确认电视麦克风可用，或改用蓝牙耳机麦克风");
            dismissVoiceDialog();
            return;
        }
        if (wav.length < 3200) {
            setNotice("没有录到声音，请再试一次");
            dismissVoiceDialog();
            return;
        }
        final int token = ++voiceToken;
        final long started = System.currentTimeMillis();
        if (voiceDialog != null) voiceDialog.setMessage("正在识别…");
        final EditText field = searchField;
        voiceWorker.submit(() -> {
            try {
                String key = VoiceSearch.key(MainActivity.this);
                String text = VoiceSearch.transcribe(wav, key);
                String keyword = VoiceSearch.keyword(text, key);
                android.util.Log.d("VoiceSearch", "ready " + (System.currentTimeMillis() - started) + "ms keyword=" + keyword);
                main.post(() -> {
                    dismissVoiceDialog();
                    if (token != voiceToken || isFinishing() || isDestroyed()) return;
                    if (screen != Screen.SEARCH || field == null) return;
                    field.setText(keyword);
                    performSearch(field);
                });
            } catch (Exception e) {
                main.post(() -> {
                    dismissVoiceDialog();
                    if (token != voiceToken || isFinishing() || isDestroyed()) return;
                    setNotice("语音搜索失败：" + (e.getMessage() == null ? "网络异常" : e.getMessage()));
                });
            }
        });
    }
    /** 停止录音并关闭弹窗；keepNotice 为 true 时保留提示文字。 */
    private void stopListening(boolean keepNotice) {
        main.removeCallbacks(voicePoll);
        if (recorder != null) { recorder.cancel(); recorder = null; }
        dismissVoiceDialog();
        if (!keepNotice && screen == Screen.SEARCH) setNotice("");
    }
    private void dismissVoiceDialog() {
        android.app.AlertDialog dialog = voiceDialog;
        voiceDialog = null;
        if (dialog != null) { try { dialog.dismiss(); } catch (Exception ignored) { } }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != 73) return;
        if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) beginListening();
        else setNotice("未获得麦克风权限，无法语音搜索");
    }
    private void showVoiceSettings() {
        page(Screen.SETTINGS, "语音搜索");
        starlightChrome(false);
        LinearLayout nav = row(); content.addView(nav);
        starlightButton(nav, "‹ 返回设置", false, this::showSettings);
        String savedKey = VoiceSearch.key(this);
        TextView hint = starlightText("电视麦克风录音后先用语音识别模型转成文字，再由大模型整理成搜索关键词，都走硅基流动。"
                + "在 cloud.siliconflow.cn 用手机号注册，左侧「API 密钥」创建后把 sk- 开头的 key 粘贴到这里。"
                + (savedKey.isEmpty() ? "" : "\n已保存的 Key 只以掩码回显，界面上无法查看明文。"), 14, STEXT_2);
        hint.setPadding(dp(6), dp(8), dp(6), dp(6)); content.addView(hint);
        int keyType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        EditText field = starlightInput(content,
                savedKey.isEmpty() ? "粘贴 sk- 开头的 API Key" : "已保存 " + VoiceSearch.masked(savedKey) + "，留空保持不变",
                "", keyType);
        // setSingleLine 会把 inputType 重置成普通文本，必须再设一次密码类型，否则已输入的 Key 会明文显示。
        field.setSingleLine(true);
        field.setInputType(keyType);
        field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        LinearLayout.LayoutParams fieldParams = (LinearLayout.LayoutParams) field.getLayoutParams();
        fieldParams.height = dp(62); field.setLayoutParams(fieldParams);
        starlightPrimary(content, "保存", () -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) {
                if (savedKey.isEmpty()) { setNotice("请填写 API Key"); return; }
                hideKeyboard();
                showSettings();
                setNotice("未修改，继续使用已保存的 API Key");
                return;
            }
            VoiceSearch.saveKey(this, value);
            field.setText("");
            hideKeyboard();
            showSettings();
            setNotice("语音搜索已启用");
        });
        starlightButton(content, "清除 API Key", false, () -> { VoiceSearch.saveKey(this, ""); showVoiceSettings(); });
        starlightButton(content, "去测试语音搜索", false, () -> { showSearch(); startVoiceSearch(); });
    }
    private int fitPosterHeight(int preferred) {
        if(resultsView==null || resultsView.getHeight()==0)return preferred;
        android.graphics.Paint paint=new android.graphics.Paint();int labels=0;
        for(int size:new int[]{18,14,13}) {
            paint.setTextSize(size*getResources().getDisplayMetrics().scaledDensity);
            android.graphics.Paint.FontMetricsInt metrics=paint.getFontMetricsInt();labels+=metrics.bottom-metrics.top;
        }
        int available=resultsView.getHeight()-resultsView.getPaddingTop()-resultsView.getPaddingBottom()-labels-dp(15+12+17+8);
        return Math.min(preferred,Math.max(dp(48),available));
    }
    private String sourcePreferenceKey() { return "disabledSources@" + client.origin; }
    private void loadSourceSelection() throws Exception {
        if(getPreferences(MODE_PRIVATE).contains(sourcePreferenceKey())) {
            Set<String> disabled=getPreferences(MODE_PRIVATE).getStringSet(sourcePreferenceKey(),Collections.emptySet());
            for(int i=0;i<sources.length();i++) {
                JSONObject item=sources.getJSONObject(i);item.put("enabled",!disabled.contains(item.optString("url")));
            }
            client.local.saveSources(sources);
            getPreferences(MODE_PRIVATE).edit().remove(sourcePreferenceKey()).apply();
        }
        enabledSources.clear();
        for(int i=0;i<sources.length();i++) if(sources.getJSONObject(i).optBoolean("enabled",true)) enabledSources.add(sources.getJSONObject(i).getString("key"));
    }
    private void saveSourceSelection() {
        try {
            for(int i=0;i<sources.length();i++) sources.getJSONObject(i).put("enabled",enabledSources.contains(sources.getJSONObject(i).getString("key")));
            client.local.saveSources(sources);
        } catch(Exception e) { error(e); }
    }
    private int settingsSection;
    private void showSettings() {
        page(Screen.SETTINGS,"设置");
        starlightChrome(false);
        LinearLayout layout=row();layout.setPadding(0,dp(8),0,0);content.addView(layout);
        LinearLayout menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);menu.setPadding(dp(4),0,dp(16),0);
        layout.addView(menu,new LinearLayout.LayoutParams(dp(205),-2));
        Button[] selectedSection=new Button[1];
        String[] sections={"内容来源","播放与显示","应用"};
        for(int i=0;i<sections.length;i++){final int index=i;Button entry=starlightButton(menu,sections[i],true,()->{settingsSection=index;showSettings();});entry.setSelected(i==settingsSection);if(i==settingsSection)selectedSection[0]=entry;}
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(8),dp(12),dp(12));layout.addView(panel,new LinearLayout.LayoutParams(0,-2,1));
        if(settingsSection==0){
            settingRow(panel,"视频来源",sources.length()+" 个来源 · 电影、电视剧",this::showVideoSources);
        }else if(settingsSection==1){
            TextView hint=starlightText("方向键选择，确认执行。播放页隐藏控制时，按方向键唤出操作。",14,STEXT_3);
            hint.setPadding(dp(8),dp(8),dp(8),dp(4));panel.addView(hint);
        }else{
            ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.spark_mark);
            panel.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));
            TextView slogan=starlightText(PiankeUi.SLOGAN,14,STEXT_3);slogan.setPadding(0,dp(10),0,dp(16));panel.addView(slogan);
            settingRow(panel,"语音搜索",VoiceSearch.key(this).isEmpty()?"未配置 · 点此填写硅基流动 API Key":"语音识别已配置 · Key 已隐藏",this::showVoiceSettings);
            settingRow(panel,"检查更新",BuildConfig.VERSION_NAME,()->startActivity(new Intent(this,UpdateActivity.class)));
            settingRow(panel,"关于星光影院","原生影音客户端",()->PiankeUi.about(this));
        }
        if(selectedSection[0]!=null)selectedSection[0].requestFocus();
    }
    private void showVideoSources(){
        page(Screen.SETTINGS,"视频来源");starlightChrome(false);
        LinearLayout nav=row();content.addView(nav);
        starlightButton(nav,"‹ 返回设置",false,this::showSettings);
        starlightButton(nav,"添加来源 / 扫码导入",false,this::showConfig);
        if(sources.length()==0)emptyState(content,"还没有视频来源","添加后即可搜索影片");
        for(int i=0;i<sources.length();i++){
            JSONObject source=sources.optJSONObject(i);String key=source.optString("key");
            LinearLayout panel=row();panel.setGravity(Gravity.CENTER_VERTICAL);panel.setPadding(dp(18),dp(10),dp(18),dp(10));panel.setBackground(rounded(SSURFACE,14));
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(dp(4),dp(10),dp(4),0);content.addView(panel,pp);
            Switch choice=new Switch(this);choice.setText(source.optString("name"));choice.setTextColor(STEXT_1);choice.setTextSize(17);choice.setChecked(enabledSources.contains(key));
            choice.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{SACCENT,STEXT_3}));
            choice.setTrackTintList(ColorStateList.valueOf(0x332e333a));
            panel.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
            choice.setOnCheckedChangeListener((v,on)->{if(on)enabledSources.add(key);else enabledSources.remove(key);saveSourceSelection();});
            starlightButton(panel,"更多",false,()->new android.app.AlertDialog.Builder(this).setTitle(source.optString("name")).setItems(new String[]{"删除来源"},(d,w)->new android.app.AlertDialog.Builder(this).setMessage("删除后不会在重启时补回，是否删除？")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->task("正在删除…",()->{client.local.removeSource(key);return client.local.sources();},list->{sources=list;results=new JSONArray();loadSourceSelection();showVideoSources();})).show()).setNegativeButton("取消",null).show());
        }
    }
    private void addPhonePairingButton() {
        Button scan = button(content, "手机扫码配置 NAS", () -> showPairing(0));
        // Let first-time users leave an unreachable default NAS without waiting for its timeout.
        scan.setOnClickListener(view -> showPairing(0));
    }
    private void closePairing() {
        main.removeCallbacks(expirePairing);
        if (pairingServer != null) { pairingServer.close(); pairingServer = null; }
        if (pairingQr != null) { pairingQr.setImageDrawable(null); pairingQr = null; }
        if (pairingConfirmation != null) { pairingConfirmation.dismiss(); pairingConfirmation = null; }
    }
    private void returnFromPairing() {
        if (pairingReturn == Screen.SETTINGS) showSettings();
        else if (pairingReturn == Screen.LOGIN) showLogin();
        else showConfig();
    }
    private void showPairing(int addressIndex) {
        if (screen != Screen.PAIRING) pairingReturn = screen;
        page(Screen.PAIRING, "星光影院   /   手机扫码配置");
        content.addView(label("手机与电视连接同一网络，扫码填写 NAS 地址和密码。", 18));
        try {
            List<String> addresses = PhonePairingServer.localAddresses();
            if (addresses.isEmpty()) throw new Exception("未找到局域网地址，请先连接 Wi-Fi 或网线。");
            int selectedAddress = addressIndex % addresses.size(); int ticket = generation;
            pairingServer = new PhonePairingServer(addresses.get(selectedAddress), client == null ? "" : client.origin.toString(), (origin, password) ->
                main.post(() -> {
                    if (screen != Screen.PAIRING || generation != ticket || pairingServer == null || stopped || isFinishing()) return;
                    closePairing(); setNotice("手机已提交配置，请确认连接。");
                    pairingConfirmation = new android.app.AlertDialog.Builder(this).setTitle("连接并保存这个 NAS？")
                        .setMessage(origin + "\n\n密码已填写，将在登录验证成功后加密保存，以后自动登录。")
                        .setNegativeButton("取消", (dialog, which) -> setNotice("已取消，原来的连接没有更改。可重新生成二维码。"))
                        .setPositiveButton("连接并保存", (dialog, which) -> connectFromPhone(origin, password)).show();
                    pairingConfirmation.setOnCancelListener(dialog -> setNotice("已取消，可重新生成二维码。"));
                    pairingConfirmation.setOnDismissListener(dialog -> pairingConfirmation = null);
                }));
            LinearLayout layout = row(); layout.setGravity(Gravity.CENTER_VERTICAL); content.addView(layout);
            pairingQr = new ImageView(this); pairingQr.setImageBitmap(PairingQr.create(pairingServer.url()));
            pairingQr.setContentDescription("手机配置二维码"); layout.addView(pairingQr, new LinearLayout.LayoutParams(dp(300),dp(300)));
            LinearLayout instructions = new LinearLayout(this); instructions.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,-2,1); params.setMargins(dp(24),0,0,0); layout.addView(instructions,params);
            instructions.addView(label("1  手机相机扫码，或用浏览器打开下方地址\n2  填写 NAS 地址与密码，发送到电视\n3  用遥控器确认连接并保存", 20));
            instructions.addView(label("二维码 5 分钟内有效。退出此页面即失效。\n电视有多个网络地址时，可以切换后重新扫码。", 16));
            TextView address = label(pairingServer.url(),14); address.setContentDescription("手机配置地址"); instructions.addView(address);
            main.postDelayed(expirePairing, PhonePairingServer.LIFETIME_MS);
            button(content, "重新生成二维码", () -> showPairing(selectedAddress));
            if (addresses.size()>1) button(content, "切换网络地址", () -> showPairing(selectedAddress+1));
        } catch (Exception ex) { closePairing(); setNotice("无法开启手机配置：" + ex.getMessage()); button(content,"重试", () -> showPairing(0)); }
        Button back = button(content, "‹ 返回", this::returnFromPairing); back.requestFocus();
    }
    private void connectFromPhone(URI origin, String password) {
        NasClient candidate = new NasClient(origin);
        task("正在验证手机填写的 NAS", () -> {
            JSONObject auth = candidate.request("/api/auth", new JSONObject().put("password",password));
            if (!auth.optBoolean("success")) throw new Exception("NAS 拒绝登录，请重新扫码填写密码");
            JSONObject status = candidate.request("/api/status",null);
            if (!status.optBoolean("passwordRequired")) throw new Exception("NAS 未启用密码，请先配置 NAS 认证");
            return candidate.sources(status);
        }, list -> {
            new CredentialStore(this).save(origin,password);
            if (client != null) client.clearSession(); client = candidate; availableUpdate = null;
            getPreferences(MODE_PRIVATE).edit().putString("nas",origin.toString()).putBoolean("autoLogin",true).apply();
            sources = list; results = new JSONArray(); loadSourceSelection(); showSearch();
            setNotice("手机配置已保存，以后会自动登录。"); checkUpdateQuietly(candidate);
        });
    }
    private void checkUpdateQuietly(NasClient active) {
        updateChecks.execute(() -> {
            try {
                AppUpdater.Release release = new AppUpdater(active).check();
                main.post(() -> {
                    if (client != active || isFinishing() || isDestroyed()) return;
                    availableUpdate = release.code > BuildConfig.VERSION_CODE ? release : null;
                    if (availableUpdate != null && screen == Screen.SEARCH && !busy)
                        setNotice("发现新版 " + release.name + "，可在设置中下载安装。");
                });
            } catch (Exception ignored) { /* An unavailable updater must never block watching. */ }
        });
    }
    private void offerUpdate(AppUpdater.Release release) {
        new android.app.AlertDialog.Builder(this).setTitle("升级到 " + release.name)
                .setMessage(release.notes + "\n\n下载完成后将打开系统安装界面，请用遥控器确认安装。")
                .setNegativeButton("稍后", null).setPositiveButton("下载并安装", (dialog, which) -> {
                    NasClient active = client; int ticket = generation;
                    task("正在下载更新", () -> new AppUpdater(active).download(this, release, percent ->
                        main.post(() -> { if (ticket == generation) setNotice("正在下载更新 " + percent + "%"); })), file -> {
                            pendingUpdate = file; pendingUpdateCode = release.code; installUpdate();
                        });
                }).show();
    }
    private void installUpdate() {
        if (pendingUpdate == null) return;
        try {
            AppUpdater.validateApk(this, pendingUpdate, pendingUpdateCode);
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                new android.app.AlertDialog.Builder(this).setTitle("允许安装应用更新")
                        .setMessage("请在接下来的系统页面允许 星光影院 安装应用，然后返回继续安装。")
                        .setNegativeButton("稍后", null).setPositiveButton("前往设置", (d, w) -> {
                            try { startActivityForResult(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName())), 901); }
                            catch (Exception ex) { error(ex); }
                        }).show();
                return;
            }
            UpdateInstaller.install(this, pendingUpdate);
        } catch (Exception ex) { error(ex); }
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 901 && Build.VERSION.SDK_INT >= 26) {
            if (getPackageManager().canRequestPackageInstalls()) installUpdate();
            else setNotice("尚未允许安装，请再次检查更新后重试。");
        }
    }
    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final PosterCard card;
        PosterHolder(PosterCard card) { super(card); this.card = card; }
    }
    private void focusResult(int index, int attempt) {
        if (screen != Screen.SEARCH || resultsView == null || index < 0 || index >= results.length()) return;
        if (attempt == 0) { resultIndex = index; resultFocusTicket++; }
        View item = resultsLayout.findViewByPosition(index);
        if (item != null) { item.requestFocus(); return; }
        if (attempt >= 8) return;
        resultsView.scrollToPosition(index); int ticket = generation, focusTicket = resultFocusTicket;
        resultsView.postDelayed(() -> { if (ticket == generation && resultIndex == index && focusTicket == resultFocusTicket) focusResult(index, attempt + 1); }, 40);
    }
    private void cancelSearch() {
        searchToken++; searching=false;
        if(searchFuture!=null) searchFuture.cancel(true);
    }
    private void updateSearchSummary() {
        if(screen!=Screen.SEARCH || searchSummary==null)return;
        searchSummary.setText(query.isEmpty()?"输入片名开始搜索 · 观看进度和片单保存在本机":
            "「"+query+"」 · "+results.length()+" 个结果 · "+searchCompleted+" / "+searchTotal+" 个来源完成 · "+searchFailed+" 个不可用"+(searching?" · 正在搜索…":" · 已结束"));
        if(stopSearchButton!=null)stopSearchButton.setEnabled(searching);
    }
    private void performSearch(EditText keywords) {
        String keyword=keywords.getText().toString().trim();
        if(keyword.isEmpty() || keyword.length()>100) {setNotice("请输入 1–100 字的片名");return;}
        try {
            JSONArray chosen=new JSONArray();
            for(int i=0;i<sources.length();i++) if(enabledSources.contains(sources.getJSONObject(i).getString("key"))) chosen.put(new JSONObject(sources.getJSONObject(i).toString()));
            if(chosen.length()==0){setNotice("请在设置中启用至少一个来源");return;}
            hideKeyboard(); cancelSearch(); final int token=searchToken;
            query=keyword; results=new JSONArray(); resultIndex=0; searchCompleted=0; searchFailed=0; searchTotal=chosen.length(); searching=true;
            showSearch();
            searchFuture=searches.submit(()->{
                try {
                    client.local.search(keyword,chosen,(added,completed,total,failed)->main.post(()->{
                        if(token!=searchToken || isDestroyed())return;
                        int start=results.length();
                        for(int i=0;i<added.length();i++)results.put(added.optJSONObject(i));
                        searchCompleted=completed;searchTotal=total;searchFailed=failed;
                        if(screen==Screen.SEARCH) {
                            if(added.length()>0)resultsView.getAdapter().notifyItemRangeInserted(start,added.length());
                            updateSearchSummary();
                        }
                    }));
                } catch(Exception e) {main.post(()->{if(token==searchToken && screen==Screen.SEARCH)error(e);});}
                finally {main.post(()->{if(token==searchToken){searching=false;updateSearchSummary();}});}
            });
        }catch(Exception e){error(e);}
    }
    private void loadDetail(JSONObject item, int index) { loadDetail(item,index,false); }
    private void loadDetail(JSONObject item, int index, boolean continuePlaying) {
        boolean fromHome=detailFromHome;
        Screen returnTo=screen==Screen.PLAYLIST?Screen.PLAYLIST:Screen.SEARCH;
        JSONObject source = item.optJSONObject("favoriteSource");
        for (int i = 0; source == null && i < sources.length(); i++) {
            JSONObject candidate = sources.optJSONObject(i);
            if (candidate.optString("key").equals(item.optString("sourceKey"))) { source = candidate; break; }
        }
        if (source == null) { setNotice("源配置已失效，请重新搜索"); return; }
        String path = "/api/detail?id=" + NasClient.encode(item.optString("vodId")) + "&source=" + NasClient.encode(source.toString());
        JSONObject savedSource = source;
        NasClient active = client;
        task("正在获取详情和剧集", () -> active.request(path, null), detail -> {
            selected = item; resultIndex = index; detailSource=savedSource; detailReturn=returnTo;
            detailFromHome=fromHome;
            episodes = detail.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) throw new Exception("此来源没有可播放集数");
            JSONObject info = detail.optJSONObject("videoInfo");
            detailInfo = info == null ? new JSONObject() : info;
            title = info == null ? item.optString("name") : info.optString("title", item.optString("name"));
            JSONObject saved=new PlaybackStore(this).find(detailSource,selected);
            episodeIndex=saved==null?0:saved.optInt("index");
            if(episodeIndex<0 || episodeIndex>=episodes.length())episodeIndex=0;
            showDetail();
            if(continuePlaying)preparePlayback(episodeIndex);
        });
    }
    @SuppressWarnings("deprecation")
    private void showDetail() {
        page(Screen.DETAIL, "星光影院   /   影片详情");
        starlightChrome();
        String coverUrl = detailInfo.optString("cover");
        String artwork = coverUrl.isEmpty() ? selected.optString("pic") : coverUrl;

        /* —— 剧照 Hero：背景剧照 + 左侧/底部渐变遮罩 + 竖版海报 + 信息 —— */
        FrameLayout hero = new FrameLayout(this); clipRounded(hero, 20);
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        hero.setMinimumHeight(dp(360));
        ImageView backdrop = new ImageView(this); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        posters.load(backdrop, client, artwork);
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xf20e0f11, 0xd90e0f11, 0x260e0f11}));
        hero.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xf20e0f11, 0x000e0f11}));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, dp(180), Gravity.BOTTOM));

        LinearLayout heroBody = new LinearLayout(this); heroBody.setOrientation(LinearLayout.HORIZONTAL);
        heroBody.setGravity(Gravity.BOTTOM); heroBody.setPadding(dp(30), dp(24), dp(30), dp(26));
        hero.addView(heroBody, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        Button back = new Button(this);
        back.setText(detailFromHome ? "‹   返回首页" : detailReturn == Screen.PLAYLIST ? "‹   返回播放列表" : "‹   返回搜索结果");
        back.setAllCaps(false); back.setTextSize(14);
        back.setMinWidth(0); back.setMinimumWidth(0); back.setMinHeight(0); back.setMinimumHeight(0);
        back.setPadding(dp(16), 0, dp(16), 0); back.setFocusableInTouchMode(true);
        back.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{SACCENT, STEXT_1}));
        back.setBackground(selectable(0x73000000, 999));
        back.setOnFocusChangeListener((v, focused) -> v.setElevation(focused ? dp(6) : 0));
        back.setOnClickListener(v -> { if (!busy) returnFromDetail(); });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.START);
        backParams.setMargins(dp(24), dp(20), 0, 0); hero.addView(back, backParams);

        FrameLayout posterFrame = new FrameLayout(this); posterFrame.setBackground(rounded(SSURFACE, 14)); clipRounded(posterFrame, 14);
        heroBody.addView(posterFrame, new LinearLayout.LayoutParams(dp(168), dp(238)));
        ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterFrame.addView(poster, new FrameLayout.LayoutParams(-1, -1)); posters.load(poster, client, artwork);

        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(30), 0, 0, 0);
        heroBody.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout kicker = row(); info.addView(kicker);
        TextView sourceName = starlightText(selected.optString("sourceName"), 13, SACCENT);
        sourceName.setLetterSpacing(0.08f); sourceName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        kicker.addView(sourceName);
        kicker.addView(starlightText("   ·   全 " + episodes.length() + " 集", 13, STEXT_3));

        TextView name = starlightText(title, 34, STEXT_1); name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); name.setPadding(0, dp(8), 0, 0);
        info.addView(name);

        String summary = (detailInfo.optString("year") + "   " + detailInfo.optString("area") + "   " + detailInfo.optString("typeName")).trim();
        if (!summary.isEmpty()) {
            TextView meta = starlightText(summary.replaceAll("\\s+", "   ").trim(), 15, STEXT_2);
            meta.setPadding(0, dp(8), 0, 0); info.addView(meta);
        }

        TextView description = starlightText("", 14, STEXT_2); description.setMaxLines(3);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END); description.setLineSpacing(dp(4), 1f);
        String desc = detailInfo.optString("desc");
        description.setText(desc.isEmpty() ? "选好剧集，开始观看。" : Html.fromHtml(desc));
        description.setPadding(0, dp(12), 0, 0); info.addView(description);

        PlaybackStore store = new PlaybackStore(this); JSONObject saved = store.find(detailSource, selected);
        int resumeIndex = saved != null && saved.optInt("index") >= 0 && saved.optInt("index") < episodes.length() ? saved.optInt("index") : 0;
        if (saved != null) {
            TextView resume = starlightText(PiankeUi.position(saved), 13, STEXT_3);
            resume.setPadding(0, dp(10), 0, 0); info.addView(resume);
            starlightProgress(info, saved);
        }

        LinearLayout actions = row(); actions.setGravity(Gravity.CENTER_VERTICAL); info.addView(actions);
        detailButton(actions, saved == null ? "▶   开始观看" : "▶   继续播放", true, () -> preparePlayback(resumeIndex));
        boolean listed = store.contains(detailSource, selected);
        detailButton(actions, listed ? "移出片单" : "加入片单", false, () -> task("正在保存片单", () -> { if (listed) store.remove(PlaybackStore.identity(detailSource, selected)); else store.add(detailSource, selected, title); return true; }, ok -> showDetail()));
        detailButton(actions, "切换来源", false, this::showSourcePicker);

        /* —— 选集 —— */
        LinearLayout section = row(); section.setGravity(Gravity.BOTTOM);
        section.setPadding(0, dp(28), 0, dp(10)); content.addView(section);
        TextView sectionTitle = starlightText("选集", 26, STEXT_1);
        sectionTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(starlightText("共 " + episodes.length() + " 集", 13, STEXT_3));
        TextView skipInfo = starlightText(SegmentSkipStore.summary(this, title), 13, STEXT_3);
        skipInfo.setPadding(0, 0, 0, dp(8)); content.addView(skipInfo);

        int columns = getResources().getConfiguration().screenWidthDp >= 900 ? 8 : 6;
        GridLayout grid = new GridLayout(this); grid.setColumnCount(columns); content.addView(grid);
        ArrayList<Button> buttons = new ArrayList<>();
        int gap = dp(8), available = getResources().getDisplayMetrics().widthPixels - dp(80);
        int width = Math.max(dp(88), (available - gap * (columns - 1)) / columns);
        for (int i = 0; i < episodes.length(); i++) {
            int index = i;
            boolean current = i == resumeIndex;
            Button episode = new Button(this);
            episode.setText(current ? "第 " + (i + 1) + " 集 · 继续" : "第 " + (i + 1) + " 集");
            episode.setTextSize(15); episode.setAllCaps(false); episode.setGravity(Gravity.CENTER);
            episode.setContentDescription("第 " + (i + 1) + " 集");
            episode.setFocusableInTouchMode(true);
            episode.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{SACCENT, current ? SACCENT : STEXT_2}));
            episode.setBackground(episodeState(current));
            episode.setOnFocusChangeListener((v, focused) -> v.setElevation(focused ? dp(6) : 0));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = width; params.height = dp(56);
            params.setMargins(i % columns == 0 ? 0 : gap, dp(4), 0, dp(4)); grid.addView(episode, params);
            episode.setOnClickListener(v -> { if (!busy) preparePlayback(index); }); buttons.add(episode);
        }
        if (!buttons.isEmpty()) buttons.get(Math.min(episodeIndex, buttons.size() - 1)).requestFocus();
        renderRelated();
    }
    /** 同类推荐：由当前影片分类驱动，卡片直接进入对应详情。 */
    private void renderRelated() {
        String matched = DiscoveryRepository.section(detailInfo.optString("typeName"));
        if (matched.isEmpty()) matched = DiscoveryRepository.section(selected == null ? "" : selected.optString("typeName"));
        if (matched.isEmpty() || discovery == null) return;
        final String section = matched;
        LinearLayout titleRow = row(); titleRow.setPadding(0, dp(30), 0, dp(12)); content.addView(titleRow);
        TextView label = starlightText("同类推荐 · " + section, 23, STEXT_1);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = starlightText("正在加载…", 13, STEXT_3); titleRow.addView(status);
        LinearLayout cards = row(); content.addView(cards);
        int ticket = generation; String excluded = title;
        relatedWorker.execute(() -> {
            try {
                JSONArray items = discovery.related(section, excluded, 8);
                main.post(() -> {
                    if (ticket != generation || screen != Screen.DETAIL || isFinishing() || isDestroyed()) return;
                    status.setText(items.length() == 0 ? "暂无同类内容" : "");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i); if (item == null) continue;
                        PosterCard card = new PosterCard(this, dp(120));
                        card.title.setText(item.optString("name"));
                        card.source.setText(item.optString("sourceName"));
                        String extra = item.optString("year"), remarks = item.optString("remarks");
                        card.metadata.setText(extra.isEmpty() ? remarks : remarks.isEmpty() ? extra : extra + "  ·  " + remarks);
                        card.setContentDescription(item.optString("name") + "，" + item.optString("sourceName"));
                        posters.loadPublic(card.image, item.optString("pic"), R.drawable.poster_placeholder);
                        card.setOnClickListener(v -> { if (!busy) loadDetail(item, 0); });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), -2);
                        lp.setMargins(dp(4), dp(4), dp(4), dp(6)); cards.addView(card, lp);
                    }
                });
            } catch (Exception ex) {
                main.post(() -> { if (ticket == generation && screen == Screen.DETAIL) status.setText("同类推荐暂时无法加载"); });
            }
        });
    }
    private void homeOrSearch(){
        String destination=getIntent().getStringExtra("tvDestination");
        if(destination!=null&&!destination.equals("search"))finish();else showSearch();
    }
    private void returnFromDetail() {
        if("resume".equals(getIntent().getStringExtra("tvDestination"))||detailFromHome){finish();return;}
        if(detailReturn==Screen.PLAYLIST)showPlaylist();else showSearch();
    }
    private void showPlaylist() {
        page(Screen.PLAYLIST,"我的片单");starlightChrome(false);
        LinearLayout nav=row();content.addView(nav);
        Button back=starlightButton(nav,"‹ 返回首页",false,this::homeOrSearch);
        PlaybackStore store=new PlaybackStore(this);JSONArray list=store.playlist();View first=null;
        if(list.length()==0)emptyState(content,"片单还是空的","去找一部想看的吧");
        try {for(int i=0;i<list.length();i++) {
            JSONObject entry=list.getJSONObject(i),source=entry.getJSONObject("source"),item=new JSONObject(entry.getJSONObject("item").toString()).put("favoriteSource",source);JSONObject saved=store.find(source,item);
            LinearLayout panel=row();panel.setGravity(Gravity.CENTER_VERTICAL);panel.setPadding(dp(14),dp(12),dp(14),dp(12));
            panel.setBackground(rounded(SSURFACE,14));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(dp(4),dp(10),dp(4),0);content.addView(panel,pp);
            ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);clipRounded(art,10);
            panel.addView(art,new LinearLayout.LayoutParams(dp(66),dp(92)));posters.load(art,client,item.optString("pic"));
            LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(dp(16),0,dp(16),0);panel.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=starlightText(entry.optString("title"),19,STEXT_1);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));copy.addView(name);
            TextView status=starlightText(PiankeUi.position(saved),13,STEXT_3);status.setPadding(0,dp(4),0,0);copy.addView(status);starlightProgress(copy,saved);
            Button play=starlightSmallPrimary(panel,saved==null?"开始播放":"继续播放",()->loadDetail(item,0,true));if(first==null)first=play;
            starlightButton(panel,"更多",false,()->new android.app.AlertDialog.Builder(this).setTitle(entry.optString("title")).setItems(new String[]{"查看剧集","移出列表"},(d,w)->{
                if(w==0)loadDetail(item,0);else new android.app.AlertDialog.Builder(this).setMessage("移出片单？观看历史和进度会保留。") .setNegativeButton("取消",null).setPositiveButton("移出",(dialog,which)->task("正在移除",()->{store.remove(entry.getString("id"));return true;},ok->showPlaylist())).show();
            }).setNegativeButton("取消",null).show());
        }}catch(Exception e){error(e);}if(first!=null)first.requestFocus();else back.requestFocus();
    }
    /** 切换来源：立即弹出弹窗并边扫描边把每个来源的结果追加进去。 */
    private void showSourcePicker() {
        JSONObject current = detailSource;
        String currentKey = current == null ? "" : current.optString("key");
        String currentId = selected == null ? "" : selected.optString("vodId");
        JSONArray chosen = new JSONArray();
        try {
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source != null && enabledSources.contains(source.getString("key"))) chosen.put(new JSONObject(source.toString()));
            }
        } catch (Exception ignored) { }
        if (chosen.length() == 0) { setNotice("请先在设置中启用至少一个来源"); return; }
        final String keyword = title;
        final int total = chosen.length();

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(6), dp(22), dp(6));
        TextView status = starlightText("正在扫描 " + total + " 个来源…", 15, STEXT_2);
        panel.addView(status);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView options = new ScrollView(this); options.addView(list);
        panel.addView(options, new LinearLayout.LayoutParams(-1, dp(320)));
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("切换来源 · " + keyword)
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create();
        final int token = ++sourcePickerToken;
        dialog.setOnDismissListener(d -> { if (token == sourcePickerToken) sourcePickerToken++; });
        dialog.show();

        sourceScans.execute(() -> {
            try {
                client.local.search(keyword, chosen, (items, completed, count, failed) -> main.post(() -> {
                    if (token != sourcePickerToken) return;
                    int added = 0;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        if (currentKey.equals(item.optString("sourceKey")) && currentId.equals(item.optString("vodId"))) continue;
                        addSourceOption(list, item, dialog);
                        added++;
                    }
                    int found = list.getChildCount();
                    if (completed >= count) {
                        status.setText(found == 0 ? "扫描完成 · 没有找到其他可用来源"
                                : "扫描完成 · 找到 " + found + " 个可用来源" + (failed > 0 ? " · " + failed + " 个来源不可用" : ""));
                    } else {
                        status.setText("已扫描 " + completed + " / " + count + " 个来源 · 找到 " + found + " 个" + (added == 0 ? "，正在继续扫描…" : ""));
                    }
                }));
            } catch (Exception ex) {
                main.post(() -> {
                    if (token == sourcePickerToken) status.setText("扫描失败：" + (ex.getMessage() == null ? "来源不可用" : ex.getMessage()));
                });
            }
        });
    }
    /** 向弹窗追加一个可切换的来源。 */
    private void addSourceOption(LinearLayout list, JSONObject item, android.app.AlertDialog dialog) {
        Button option = new Button(this);
        String remarks = item.optString("remarks");
        option.setText(item.optString("sourceName") + (remarks.isEmpty() ? "" : "  ·  " + remarks));
        option.setAllCaps(false); option.setTextSize(16); option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        option.setMinWidth(0); option.setMinimumWidth(0); option.setMinHeight(dp(48)); option.setMinimumHeight(dp(48));
        option.setPadding(dp(16), dp(8), dp(16), dp(8)); option.setFocusableInTouchMode(true);
        option.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}}, new int[]{SACCENT, STEXT_1}));
        option.setBackground(selectable(SSURFACE, 12));
        option.setOnFocusChangeListener((v, focused) -> v.setElevation(focused ? dp(4) : 0));
        option.setOnClickListener(v -> { dialog.dismiss(); switchDetailSource(item); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4)); list.addView(option, lp);
        if (list.getChildCount() == 1) option.post(option::requestFocus);
    }
    /** 在详情页原地切换到另一个来源，保留返回路径与搜索结果位置。 */
    private void switchDetailSource(JSONObject item) {
        JSONObject source = item.optJSONObject("favoriteSource");
        for (int i = 0; source == null && i < sources.length(); i++) {
            JSONObject candidate = sources.optJSONObject(i);
            if (candidate != null && candidate.optString("key").equals(item.optString("sourceKey"))) source = candidate;
        }
        if (source == null) { setNotice("该来源已不可用，请重新扫描"); return; }
        String path = "/api/detail?id=" + NasClient.encode(item.optString("vodId")) + "&source=" + NasClient.encode(source.toString());
        JSONObject target = source;
        NasClient active = client;
        task("正在切换视频来源", () -> active.request(path, null), detail -> {
            episodes = detail.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) throw new Exception("该来源没有可播放集数");
            selected = item; detailSource = target;
            JSONObject info = detail.optJSONObject("videoInfo");
            detailInfo = info == null ? new JSONObject() : info;
            title = info == null ? item.optString("name") : info.optString("title", item.optString("name"));
            JSONObject saved = new PlaybackStore(this).find(detailSource, selected);
            episodeIndex = saved == null ? 0 : saved.optInt("index");
            if (episodeIndex < 0 || episodeIndex >= episodes.length()) episodeIndex = 0;
            showDetail();
        });
    }
    private void preparePlayback(int index) { preparePlayback(index,true); }
    private void preparePlayback(int index, boolean restore) {
        String upstream = episodes.optString(index); NasClient active = client;
        task("正在连接视频源", () -> {
            URI uri = active.proxy(upstream);
            return new Object[] { uri, active.playbackMime(uri, upstream) };
        }, info -> {
            episodeIndex = index; playbackUri = (URI) info[0]; mime = (String) info[1]; position = 0; playWhenReady = true;
            JSONObject saved=new PlaybackStore(this).find(detailSource,selected);
            if(restore && saved!=null && saved.optInt("index")==index)position=PlaybackStore.resumePosition(saved.optLong("position"),saved.optLong("duration"));
            showPlayer();
        });
    }
    private void showPlayer() {
        generation++; busy = false; screen = Screen.PLAYER; playerHasFrame = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout stage = new FrameLayout(this); stage.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true); stage.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerTop = new LinearLayout(this); playerTop.setOrientation(LinearLayout.VERTICAL);
        playerTop.setPadding(dp(40), dp(20), dp(40), dp(34)); playerTop.setBackgroundResource(R.drawable.player_top);
        LinearLayout heading=row();playerTop.addView(heading);
        TextView headingTitle=label(title + "   ·   第 " + (episodeIndex + 1) + " 集 / 共 "+episodes.length()+" 集",26);
        headingTitle.setTextColor(PlayerStyle.TEXT);
        heading.addView(headingTitle,new LinearLayout.LayoutParams(0,-2,1));
        message = label("正在连接…", 15); playerTop.addView(message);
        stage.addView(playerTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        playerControls = new LinearLayout(this); playerControls.setOrientation(LinearLayout.VERTICAL);
        playerControls.setPadding(dp(40), dp(16), dp(40), dp(12)); playerControls.setBackgroundResource(R.drawable.player_bottom);
        playbackStatus = label("正在缓冲", 16); playbackStatus.setTextColor(PlayerStyle.TEXT); playerControls.addView(playbackStatus);
        playbackProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        playbackProgress.setMax(1000); playbackProgress.setProgressTintList(ColorStateList.valueOf(PlayerStyle.ACCENT));playbackProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x33383c42));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5)); progressParams.setMargins(dp(5), dp(4), dp(5), dp(8));
        playerControls.addView(playbackProgress, progressParams);
        LinearLayout controls = new LinearLayout(this);playerControls.addView(controls);
        playToggle = playerAction(controls, "暂停", "pause", () -> {togglePlay();showControls(false);});
        playerAction(controls,"快退 10秒","key-left",()->seekWithPreview(-10000));
        playerAction(controls,"快进 10秒","key-right",()->seekWithPreview(10000));
        Button previous=playerAction(controls,"上一集","previous",()->switchTvEpisode(episodeIndex-1));previous.setEnabled(episodeIndex>0);
        Button next=playerAction(controls,"下一集","next",()->switchTvEpisode(episodeIndex+1));next.setEnabled(episodeIndex+1<episodes.length());
        LinearLayout settings = new LinearLayout(this);playerControls.addView(settings);
        speedButton=playerAction(settings,"倍速 "+tvPlaybackSpeed()+"×","speed",this::chooseTvSpeed);
        subtitleButton=playerAction(settings,"字幕","subtitle",()->{if(subtitleMenu!=null){main.removeCallbacks(hidePlayerControls);subtitleMenu.show();}});
        playerAction(settings,"更多","more",this::showPlayerOptions);
        playerAction(settings,"返回选集","episodes",this::leavePlayer);
        autoNext=new CheckBox(this);autoNext.setText("自动下一集");autoNext.setTextColor(PlayerStyle.TEXT);
        autoNext.setButtonTintList(ColorStateList.valueOf(PlayerStyle.ACCENT));
        autoNext.setChecked(getPreferences(MODE_PRIVATE).getBoolean("autoNext",true));
        autoNext.setOnCheckedChangeListener((v,on)->getPreferences(MODE_PRIVATE).edit().putBoolean("autoNext",on).apply());
        segmentSkip = new SegmentSkipController(this, title, () -> {showControls(false); main.removeCallbacks(hidePlayerControls);}, () -> showControls(false), this::finishSkippedTvEpisode);
        segmentSkip.addControls(playerControls, true);
        TextView hint=label("收起时：左右快退 / 快进，长按加速 · 上下唤出 · 返回收起",13);hint.setTextColor(PlayerStyle.MUTED);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(8),0,0);playerControls.addView(hint);
        stage.addView(playerControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        seekPreview=label("",20);seekPreview.setGravity(Gravity.CENTER);seekPreview.setPadding(dp(24),dp(12),dp(24),dp(12));
        seekPreview.setBackground(PlayerStyle.surface(0xcc0e0f11,12,this));seekPreview.setVisibility(View.GONE);
        stage.addView(seekPreview,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        setContentView(stage); controlsVisible = true;
        playerView.setOnClickListener(v -> { if (controlsVisible) hideControls(); else showControls(true); });
        playerView.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { togglePlay(); showControls(false); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) { showControls(true); return true; }
            return false;
        });
        playerView.requestFocus();
        if (!stopped) startPlayer();
    }
    private Button playerAction(LinearLayout row,String text,String icon,Runnable run){
        Button b=PlayerStyle.action(this,text,icon,true);b.setPadding(dp(8),dp(6),dp(8),dp(6));b.setTextSize(16);PlayerStyle.setTvIcon(b,icon,22);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(b,p);
        b.setOnClickListener(v->run.run());b.setOnFocusChangeListener((v,focused)->{v.setScaleX(focused?1.04f:1f);v.setScaleY(focused?1.04f:1f);if(focused){main.removeCallbacks(hidePlayerControls);main.postDelayed(hidePlayerControls,3500);}});return b;
    }
    private float tvPlaybackSpeed(){return getPreferences(MODE_PRIVATE).getFloat("playbackSpeed",1f);}
    private void chooseTvSpeed(){
        main.removeCallbacks(hidePlayerControls);
        float[] values={.75f,1f,1.25f,1.5f,2f};String[] labels={"0.75×","1×","1.25×","1.5×","2×"};
        int selected=1;for(int i=0;i<values.length;i++)if(values[i]==tvPlaybackSpeed())selected=i;
        playerOptions=new android.app.AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(labels,selected,(d,which)->{
            getPreferences(MODE_PRIVATE).edit().putFloat("playbackSpeed",values[which]).apply();
            if(player!=null)player.setPlaybackSpeed(values[which]);
            speedButton.setText("倍速 "+values[which]+"×");speedButton.setContentDescription(speedButton.getText());d.dismiss();
        }).setOnDismissListener(d->{if(screen==Screen.PLAYER){showControls(false);speedButton.requestFocus();}}).show();
    }
    private void seekWithPreview(long delta){
        if(player==null || !player.isCurrentMediaItemSeekable())return;
        seek(delta);saveTvProgress();
        seekPreview.setText((delta<0?"快退 ":"快进 ")+Math.abs(delta/1000)+" 秒\n"+time(player.getCurrentPosition())+" / "+time(player.getDuration()));
        seekPreview.setVisibility(View.VISIBLE);main.removeCallbacks(clearSeekPreview);main.postDelayed(clearSeekPreview,1200);
    }
    private void showPlayerOptions(){
        main.removeCallbacks(hidePlayerControls);
        playerOptions=new android.app.AlertDialog.Builder(this).setTitle("更多播放设置").setItems(new String[]{"从头播放","自动下一集 · "+(autoNext.isChecked()?"开启":"关闭")},(d,which)->{
            if(which==0&&player!=null){player.seekTo(0);player.play();saveTvProgress();}else if(which==1)autoNext.setChecked(!autoNext.isChecked());
        }).setOnDismissListener(d->{if(screen==Screen.PLAYER)showControls(true);}).show();
    }
    private void showControls(boolean focus) {
        if (screen != Screen.PLAYER) return;
        controlsVisible = true; playerTop.setVisibility(View.VISIBLE); playerControls.setVisibility(View.VISIBLE);
        setSubtitleInset(true);
        if (focus) playToggle.requestFocus();
        main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500);
    }
    private void hideControls() {
        main.removeCallbacks(hidePlayerControls);
        if (screen != Screen.PLAYER) return;
        controlsVisible = false; playerTop.setVisibility(View.GONE); playerControls.setVisibility(View.GONE);
        setSubtitleInset(false);
        playerView.requestFocus();
    }
    private void setSubtitleInset(boolean controls) {
        if(playerView==null || playerView.getSubtitleView()==null)return;
        View subtitles=playerView.getSubtitleView();
        ViewGroup.LayoutParams current=subtitles.getLayoutParams();
        if(current instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params=(ViewGroup.MarginLayoutParams)current;
            params.bottomMargin=controls?Math.max(dp(170),playerControls.getHeight()):0;
            subtitles.setLayoutParams(params);
        }
        playerView.getSubtitleView().setBottomPaddingFraction(0.06f);
    }
    private void startPlayer() {
        if (player != null || playbackUri == null) return;
        playerHasFrame = false;
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(() -> new NasDataSource(client));
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(factory).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        if(playerView.getSubtitleView()!=null) playerView.getSubtitleView().setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,24);
        subtitleMenu = new SubtitleMenu(this,player,subtitleButton,playerView.getSubtitleView(),getPreferences(MODE_PRIVATE),()->{
            if(screen==Screen.PLAYER && player!=null) { showControls(false);subtitleButton.requestFocus(); }
        },true);
        MediaItem.Builder item = new MediaItem.Builder().setUri(playbackUri.toString());
        if (mime != null) item.setMimeType(mime);
        player.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) { if(subtitleMenu!=null)subtitleMenu.refresh(); }
            @Override public void onPlayerError(PlaybackException ex) {
                main.removeCallbacks(ticker);
                Throwable cause = ex;
                while (cause != null) {
                    if (cause instanceof NasClient.ApiException && ((NasClient.ApiException) cause).status == 401) {
                        releasePlayer(); client.clearSession(); showLogin(); setNotice("播放会话过期，请重新登录"); return;
                    }
                    cause = cause.getCause();
                }
                setNotice("暂时无法播放，请返回选集重试，或选择其他来源。");
                playbackStatus.setText("播放已停止"); showControls(true); main.removeCallbacks(hidePlayerControls);
            }
            @Override public void onRenderedFirstFrame() {
                if(!playerHasFrame){playerHasFrame = true;setNotice("左右快退 / 快进，长按加速 · 上下唤出操作 · 返回收起 / 选集");showControls(false);}
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) { main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500); }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if(subtitleMenu!=null)subtitleMenu.refresh();
                if(state==Player.STATE_READY)try{new PlaybackStore(MainActivity.this).add(detailSource,selected,title);}catch(Exception ignored){}
                if (state == Player.STATE_ENDED) {
                    saveTvProgress();
                    if(autoNext.isChecked() && episodeIndex+1<episodes.length()) main.post(()->{
                        if(screen==Screen.PLAYER && player!=null && player.getPlaybackState()==Player.STATE_ENDED)switchTvEpisode(episodeIndex+1);
                    });
                    else {setNotice("本集已播完，可返回选集继续观看。");showControls(true);main.removeCallbacks(hidePlayerControls);}
                }
            }
        });
        player.setPlaybackSpeed(tvPlaybackSpeed());
        player.setMediaItem(item.build(), position); player.prepare(); player.setPlayWhenReady(playWhenReady);
        segmentSkip.bind(player);
        playerView.setKeepScreenOn(true); main.post(ticker);
    }
    private void togglePlay() { if (player != null) { if (player.getPlayWhenReady()) player.pause(); else player.play(); } }
    private void seek(long delta) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long end = player.getDuration(); long next = Math.max(0, player.getCurrentPosition() + delta);
        player.seekTo(end > 0 ? Math.min(end, next) : next);
    }
    private void finishSkippedTvEpisode() {
        if (autoNext.isChecked() && episodeIndex + 1 < episodes.length()) switchTvEpisode(episodeIndex + 1);
        else {player.pause(); saveTvProgress(); setNotice("本集片尾已跳过，可选集继续观看。"); showControls(true);}
    }
    private void switchTvEpisode(int target) {
        if(target<0 || target>=episodes.length() || busy)return;
        releasePlayer();preparePlayback(target,false);
    }
    private void saveTvProgress() {
        if(player!=null)new PlaybackStore(this).save(detailSource,selected,title,episodeIndex,segmentSkip != null && segmentSkip.isCompleted() ? player.getDuration() : player.getCurrentPosition(),player.getDuration());
    }
    private void releasePlayer() {
        if(segmentSkip!=null)segmentSkip.close();
        tvSeekKey=KeyEvent.KEYCODE_UNKNOWN;main.removeCallbacks(clearSeekPreview);if(seekPreview!=null)seekPreview.setVisibility(View.GONE);
        if(playerOptions!=null){playerOptions.dismiss();playerOptions=null;}
        if(subtitleMenu!=null) { subtitleMenu.close();subtitleMenu=null; }
        main.removeCallbacks(ticker); main.removeCallbacks(hidePlayerControls);
        if (player != null) {
            saveTvProgress(); position = player.getCurrentPosition(); playWhenReady = player.getPlayWhenReady();
            playerView.setPlayer(null); player.release(); player = null; playerView.setKeepScreenOn(false);
        }
    }
    private void leavePlayer() { releasePlayer(); showDetail(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(screen==Screen.PLAYER && player!=null){
            int key=event.getKeyCode();
            boolean horizontal=key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
            // Keep the entire hidden-control press (including key-up) out of focus navigation.
            if(horizontal && (tvSeekKey==key || !controlsVisible)){
                if(event.getAction()==KeyEvent.ACTION_UP){tvSeekKey=KeyEvent.KEYCODE_UNKNOWN;return true;}
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    if(event.getRepeatCount()==0){tvSeekKey=key;lastTvSeekEvent=event.getEventTime();seekWithPreview(key==KeyEvent.KEYCODE_DPAD_LEFT?-10000:10000);}
                    else if(!controlsVisible && event.getEventTime()-lastTvSeekEvent>=250){
                        lastTvSeekEvent=event.getEventTime();long held=event.getEventTime()-event.getDownTime();
                        long step=held>=2000?60000:30000;seekWithPreview(key==KeyEvent.KEYCODE_DPAD_LEFT?-step:step);
                    }
                }
                return true;
            }
            if(event.getAction()==KeyEvent.ACTION_DOWN){
                if (controlsVisible) { main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500); }
                boolean direction=horizontal||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN;
                if(direction && (!controlsVisible || !(getCurrentFocus() instanceof Button))) {showControls(true);return true;}
                if(key==KeyEvent.KEYCODE_MENU){showControls(true);return true;}
                if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&(!controlsVisible||getCurrentFocus()==playerView)){
                    if(event.getRepeatCount()==0)togglePlay();showControls(true);return true;
                }
                if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getRepeatCount()==0)togglePlay();showControls(false);return true;}
                if(key==KeyEvent.KEYCODE_MEDIA_PLAY){player.play();return true;}
                if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){player.pause();return true;}
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if (busy) { generation++; busy = false; setNotice("已取消等待，可重试"); return; }
        String destination=getIntent().getStringExtra("tvDestination");
        if((screen==Screen.PLAYLIST&&"playlist".equals(destination))||(screen==Screen.SETTINGS&&"settings".equals(destination))){finish();return;}
        switch (screen) {
            case PLAYER: if (controlsVisible) hideControls(); else leavePlayer(); break;
            case DETAIL: returnFromDetail(); break;
            case PLAYLIST: showSearch(); break;
            case SETTINGS: showSearch(); break;
            case PAIRING: returnFromPairing(); break;
            case LOGIN: showConfig(); break;
            case SEARCH: if(searching){cancelSearch();updateSearchSummary();}else super.onBackPressed(); break;
            default: super.onBackPressed();
        }
    }
    @Override protected void onStart() { super.onStart(); stopped = false; if (screen == Screen.PLAYER) startPlayer(); }
    @Override protected void onPause() { saveTvProgress(); super.onPause(); }
    @Override protected void onStop() { stopped = true; if (screen == Screen.PAIRING) { closePairing(); setNotice("配置已暂停，请重新生成二维码。"); } releasePlayer(); super.onStop(); }
    @Override protected void onDestroy() { closePairing(); cancelSearch(); searches.shutdownNow(); sourceScans.shutdownNow(); relatedWorker.shutdownNow(); generation++; network.shutdownNow(); updateChecks.shutdownNow(); posters.close(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private static String time(long ms) {
        if (ms < 0) return "--:--";
        return String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60);
    }
}

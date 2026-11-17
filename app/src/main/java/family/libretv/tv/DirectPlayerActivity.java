package family.libretv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;

/** Phone playback: one player, bottom-anchored timeline, and durable local progress. */
@androidx.media3.common.util.UnstableApi
public final class DirectPlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private PlaybackGestureLayout stage;
    private LinearLayout topChrome;
    private boolean controlsVisible=true;
    private SubtitleMenu subtitles;
    private AlertDialog dialog;
    private long position;
    private boolean resume = true;
    private int index;
    private ArrayList<String> urls;
    /** 与详情页一致：综艺的集名是更新日期，优先显示集名。 */
    private ArrayList<String> episodeNames;
    private JSONObject source, item;
    private String title;
    private TextView status, episodeCaption;
    private LinearLayout episodeGrid;
    private boolean landscape;
    private int entryOrientation;
    private Button previous, next, speed;
    private Switch autoNext;
    private PlaybackStore history;
    private SharedPreferences settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() { saveProgress(); main.postDelayed(this, 5000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        entryOrientation=state==null?getResources().getConfiguration().orientation:state.getInt("entryOrientation",getResources().getConfiguration().orientation);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(state!=null&&state.containsKey("windowBrightness")){android.view.WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=state.getFloat("windowBrightness");getWindow().setAttributes(attrs);}
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // 竖屏播放页同样使用深色系统栏，避免底部出现白色三键导航条。
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if(android.os.Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);controller.setAppearanceLightNavigationBars(false);
        history = new PlaybackStore(this);
        settings = getSharedPreferences("standalone-player", 0);
        urls = getIntent().getStringArrayListExtra("episodes");
        episodeNames = getIntent().getStringArrayListExtra("episodeNames");
        if (urls == null || urls.isEmpty()) urls = new ArrayList<>(Arrays.asList(getIntent().getStringExtra("url")));
        index = Math.max(0, Math.min(urls.size() - 1, getIntent().getIntExtra("index", 0)));
        title = getIntent().getStringExtra("title");
        try {
            source = new JSONObject(getIntent().getStringExtra("source"));
            item = new JSONObject(getIntent().getStringExtra("item"));
            JSONObject saved = history.find(source, item);
            if (saved != null && saved.optInt("index") == index)
                position = PlaybackStore.resumePosition(saved.optLong("position"), saved.optLong("duration"));
        } catch (Exception ignored) { source = null; item = null; }
        if (state != null) {
            index = Math.max(0, Math.min(urls.size() - 1, state.getInt("index", index)));
            position = state.getLong("position"); resume = state.getBoolean("resume", true);
            controlsVisible=state.getBoolean("controlsVisible",true);
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String value,int size) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(PlayerStyle.TEXT);t.setTextSize(size);return t;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    /** 悬浮条上的文字按钮：选集、倍速，保持透明底 + 按压高亮。 */
    private Button overlayAction(String text,Runnable run){
        Button b=PlayerStyle.action(this,text,"",false);
        // action() 给带文字的按钮塞了一个 24dp 的空图标占位（drawableTop），
        // 会把文字整体顶到按钮底部，这里清掉才能垂直居中。
        b.setCompoundDrawables(null,null,null,null);b.setCompoundDrawablePadding(0);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);b.setPadding(0,0,0,0);b.setMinimumHeight(dp(44));
        b.setOnClickListener(v->{playerView.showController();run.run();});
        return b;
    }
    /** drawableLeft 会被画在按钮最左边而不居中，这里用整块方框包一层，把图标摆在方框正中。 */
    private static final class CenteredIcon extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable child;private final int inset;
        CenteredIcon(android.graphics.drawable.Drawable child,int inset){this.child=child;this.inset=inset;}
        @Override public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect b=getBounds();
            child.setBounds(b.left+inset,b.top+inset,b.right-inset,b.bottom-inset);child.draw(canvas);
        }
        @Override public void setAlpha(int alpha){child.setAlpha(alpha);}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){child.setColorFilter(filter);}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        @Override public boolean isStateful(){return child.isStateful();}
        @Override protected boolean onStateChange(int[] state){boolean changed=child.setState(state);invalidateSelf();return changed;}
    }
    private void centerIcon(Button b,String kind,int sizeDp,int boxDp){
        int box=dp(boxDp);
        CenteredIcon icon=new CenteredIcon(new PlayerStyle.Icon(kind),(box-dp(sizeDp))/2);
        icon.setBounds(0,0,box,box);
        b.setCompoundDrawables(icon,null,null,null);b.setCompoundDrawablePadding(0);
    }
    private void changeOrientation(){setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);}
    @Override protected void onStart() {
        super.onStart();
        boolean restoreControls=controlsVisible;
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(Color.BLACK);
        playerView=(PlayerView)LayoutInflater.from(this).inflate(R.layout.player_phone_view,stage,false);
        stage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        playerView.setControllerShowTimeoutMs(3000);playerView.setControllerHideOnTouch(true);playerView.setControllerAutoShow(true);
        // 底部一条控制条：上一集 / 播放暂停 / 下一集 靠左，选集 / 倍速 靠右。
        previous=playerView.findViewById(R.id.player_previous_episode);next=playerView.findViewById(R.id.player_next_episode);
        for(Button b:new Button[]{previous,next}){
            b.setMinWidth(0);b.setMinimumWidth(0);b.setText("");b.setBackground(PlayerStyle.background(this,false));
        }
        // 图标在 42dp 方框内居中，三个按钮的视觉中心才对得上。
        centerIcon(previous,"previous",26,42);centerIcon(next,"next",26,42);
        previous.setOnClickListener(v->{playerView.showController();switchEpisode(index-1);});
        next.setOnClickListener(v->{playerView.showController();switchEpisode(index+1);});
        LinearLayout controlsEnd=playerView.findViewById(R.id.player_controls_end);
        if(controlsEnd!=null){
            controlsEnd.addView(overlayAction("选集",this::chooseEpisode),new LinearLayout.LayoutParams(dp(56),dp(44)));
            speed=overlayAction("倍速",this::chooseSpeed);
            controlsEnd.addView(speed,new LinearLayout.LayoutParams(dp(64),dp(44)));
        }
        View shade=playerView.findViewById(R.id.player_lower_shade);
        if(shade!=null){ViewGroup.LayoutParams p=shade.getLayoutParams();p.height=dp(landscape?190:112);shade.setLayoutParams(p);}
        topChrome=column();topChrome.setBackgroundResource(R.drawable.player_top);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);topChrome.addView(heading);
        Button back=PlayerStyle.action(this,"","back",false);back.setContentDescription("返回选集");heading.addView(back,new LinearLayout.LayoutParams(dp(44),dp(48)));back.setOnClickListener(v->finish());
        LinearLayout titleBox=column();heading.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        TextView topTitle=label(landscape?title:"播放详情",landscape?18:20);topTitle.setMaxLines(2);titleBox.addView(topTitle);
        episodeCaption=label("",12);episodeCaption.setTextColor(PlayerStyle.MUTED);if(landscape)titleBox.addView(episodeCaption);
        Button rotate=PlayerStyle.action(this,"","rotate",false);rotate.setContentDescription("横屏 / 竖屏");heading.addView(rotate,new LinearLayout.LayoutParams(dp(48),dp(48)));rotate.setOnClickListener(v->changeOrientation());
        Button moreTop=PlayerStyle.action(this,"","more",false);moreTop.setContentDescription("更多");heading.addView(moreTop,new LinearLayout.LayoutParams(dp(44),dp(48)));moreTop.setOnClickListener(v->showMore());
        autoNext=new Switch(this);autoNext.setText("自动下一集");autoNext.setTextColor(PlayerStyle.TEXT);autoNext.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{PlayerStyle.ACCENT,PlayerStyle.MUTED}));autoNext.setTrackTintList(android.content.res.ColorStateList.valueOf(0x332e333a));
        autoNext.setChecked(settings.getBoolean("autoNext",true));autoNext.setOnCheckedChangeListener((v,on)->settings.edit().putBoolean("autoNext",on).apply());
        LinearLayout root=column();root.setBackgroundColor(PlayerStyle.BACKDROP);
        if(landscape){
            // 横屏：只有顶栏 + 视频，播放控制全部收在底部的悬浮条里。
            stage.addView(topChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
            status=topTitle;
            setContentView(stage);
        }else{
            root.addView(topChrome,new LinearLayout.LayoutParams(-1,-2));
            root.addView(stage,new LinearLayout.LayoutParams(-1,dp(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density*9/16f))));
            ScrollView details=new ScrollView(this);details.setFillViewport(true);
            LinearLayout panel=column();panel.setBackgroundColor(PlayerStyle.PANEL);
            panel.setPadding(dp(18),dp(12),dp(18),dp(16));
            status=label(title,24);status.setTypeface(null,Typeface.BOLD);panel.addView(status);
            panel.addView(episodeCaption);
            TextView caption=label("选集",18);caption.setTypeface(null,Typeface.BOLD);caption.setPadding(0,dp(14),0,dp(6));panel.addView(caption);
            episodeGrid=column();panel.addView(episodeGrid);
            panel.addView(autoNext,new LinearLayout.LayoutParams(-1,dp(48)));
            details.addView(panel);
            root.addView(details,new LinearLayout.LayoutParams(-1,0,1));
            setContentView(root);
        }
        ViewCompat.setOnApplyWindowInsetsListener(landscape?stage:root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            if(landscape){
                topChrome.setPadding(bars.left+dp(16),bars.top+dp(6),bars.right+dp(16),dp(12));
            }else{
                root.setPadding(bars.left,bars.top,bars.right,bars.bottom);topChrome.setPadding(dp(6),0,dp(6),0);
            }
            return insets;
        });
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{
            controlsVisible=visibility==View.VISIBLE;
            if(landscape)topChrome.setVisibility(visibility);
            adjustControllerInsets();
        });
        applySystemBars();ViewCompat.requestApplyInsets(landscape?stage:root);
        try {
            NasClient direct = new NasClient(this);
            player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new NasDataSource(direct))).build();
            playerView.setPlayer(player); playerView.setKeepScreenOn(true);
            stage.bind(player,playerView,topChrome);stage.setVodSeekGestures(true);
            subtitles = new SubtitleMenu(this, player, null, playerView.getSubtitleView(), settings, this::resumeControls, true);
            player.setPlaybackSpeed(settings.getFloat("speed", 1));
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    status.setText("播放失败，请返回选集换源（" + error.getErrorCodeName() + "）");
                    playerView.showController();
                }
                @Override public void onTracksChanged(Tracks tracks) { if (subtitles != null) subtitles.refresh(); }
                @Override public void onPlaybackStateChanged(int state) {
                    if (subtitles != null) subtitles.refresh();
                    if (state == Player.STATE_READY) {
                        updateHeading();
                        if(source != null && item != null) try { history.add(source,item,title); }
                        catch(Exception ignored) { /* A full list must not interrupt playback. */ }
                    }
                    if (state == Player.STATE_ENDED) {
                        saveProgress();
                        if (settings.getBoolean("autoNext",true) && index + 1 < urls.size()) switchEpisode(index + 1);
                        else {status.setText(title + " · " + episodeLabel(index) + " 已播放完");playerView.showController();}
                    }
                }
            });
            loadEpisode();
            if(restoreControls)playerView.showController();else playerView.hideController();
            main.postDelayed(checkpoint, 5000);
        } catch (Exception e) { status.setText("无法播放：" + e.getMessage()); }
    }
    /** 与 TV、详情页一致：综艺的集名是更新日期，优先显示集名，缺失时回退到集数。 */
    private String episodeLabel(int i) {
        String name = episodeNames == null || i < 0 || i >= episodeNames.size() ? "" : episodeNames.get(i);
        name = name == null ? "" : name.trim();
        return name.isEmpty() || "null".equals(name) ? "第 " + (i + 1) + " 集" : name;
    }
    private void updateHeading() {
        status.setText(title);
        episodeCaption.setText(episodeLabel(index) + " / 共 " + urls.size() + " 集");
        refreshEpisodeGrid();
        previous.setEnabled(index > 0); next.setEnabled(index + 1 < urls.size());
        if(speed!=null)speed.setText("倍速 " + speedLabel(settings.getFloat("speed", 1)));
    }
    /** 1.0 显示成 1×，1.25 保留两位小数。 */
    private static String speedLabel(float value) {
        return (Math.abs(value-Math.round(value))<0.01f ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT,"%.2f",value).replaceAll("0$","")) + "×";
    }
    private void loadEpisode() throws Exception {
        String url = urls.get(index); LocalRepository.validate(url);
        updateHeading();
        MediaItem.Builder media = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(media.build()); player.seekTo(position); player.prepare(); player.setPlayWhenReady(resume);
    }
    private void switchEpisode(int target) {
        if (target < 0 || target >= urls.size() || player == null) return;
        saveProgress(); index = target; position = 0; resume = true;
        try { loadEpisode(); } catch (Exception e) { status.setText("无法播放：" + e.getMessage()); }
    }
    private void chooseSpeed() {
        holdControls();
        float[] values = {.75f, 1, 1.25f, 1.5f, 2}; String[] labels = {"0.75×", "1×", "1.25×", "1.5×", "2×"};
        dialog = new AlertDialog.Builder(this).setTitle("播放速度").setItems(labels, (d, which) -> {
            settings.edit().putFloat("speed", values[which]).apply(); if (player != null) player.setPlaybackSpeed(values[which]); updateHeading();
        }).setOnDismissListener(d->resumeControls()).show();
    }
    private void applySystemBars() {
        if(stage==null)return;
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),stage);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE)bars.hide(WindowInsetsCompat.Type.systemBars());
        else bars.show(WindowInsetsCompat.Type.systemBars());
    }
    private void holdControls(){playerView.setControllerShowTimeoutMs(0);playerView.showController();}
    private void resumeControls(){if(playerView!=null){playerView.setControllerShowTimeoutMs(3000);playerView.showController();}}
    /** 字幕避让底部控制条；控制条隐藏时字幕回到画面底部。 */
    private void adjustControllerInsets() {
        if(playerView==null)return;
        View timeline=playerView.findViewById(R.id.player_timeline);
        int bar=timeline!=null&&timeline.getHeight()>0?timeline.getHeight()+dp(8):dp(120);
        View subtitles=playerView.getSubtitleView();
        if(subtitles!=null&&subtitles.getLayoutParams() instanceof FrameLayout.LayoutParams){
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)subtitles.getLayoutParams();int margin=controlsVisible?bar:0;
            if(p.bottomMargin!=margin){p.bottomMargin=margin;subtitles.setLayoutParams(p);}
        }
    }
    private void showMore(){
        holdControls();String[] options={player!=null&&player.getPlayerError()!=null?"重试播放":"从头播放", "自动下一集 · "+(settings.getBoolean("autoNext",true)?"开启":"关闭")};
        dialog=new AlertDialog.Builder(this).setTitle("更多播放设置").setItems(options,(d,which)->{
            if(which==0&&player!=null){if(player.getPlayerError()!=null)player.prepare();else player.seekTo(0);player.play();}
            else if(which==1){boolean on=!settings.getBoolean("autoNext",true);settings.edit().putBoolean("autoNext",on).apply();if(autoNext!=null)autoNext.setChecked(on);}
        }).setOnDismissListener(d->resumeControls()).show();
    }
    private void chooseEpisode(){
        holdControls();String[] labels=new String[urls.size()];for(int i=0;i<labels.length;i++)labels[i]=episodeLabel(i);
        TextView heading=label("选集\n"+episodeLabel(index)+" / 共 "+urls.size()+" 集",16);
        heading.setPadding(dp(24),dp(18),dp(24),dp(8));
        dialog=new AlertDialog.Builder(this).setCustomTitle(heading).setSingleChoiceItems(labels,index,(d,which)->{switchEpisode(which);d.dismiss();}).setNegativeButton("关闭",null).setOnDismissListener(d->resumeControls()).show();
    }
    private void refreshEpisodeGrid(){
        if(episodeGrid==null)return;episodeGrid.removeAllViews();int columns=getResources().getConfiguration().fontScale>1.3f?3:4;
        for(int i=0;i<urls.size();i+=columns){LinearLayout row=new LinearLayout(this);episodeGrid.addView(row);for(int j=0;j<columns;j++){
            int target=i+j;if(target>=urls.size()){row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(48),1));continue;}
            String label=episodeLabel(target);
            Button chip=PlayerStyle.action(this,label,"",false);
            // action() 会给带文字的按钮加 24dp 空图标占位，文字会被顶到底部，这里清掉。
            chip.setCompoundDrawables(null,null,null,null);chip.setCompoundDrawablePadding(0);
            chip.setTextSize(label.length()>6?12:13);chip.setPadding(dp(4),0,dp(4),0);chip.setSelected(target==index);
            GradientDrawable idle=PlayerStyle.surface(0xff16181b,10,this);idle.setStroke(dp(1),0xff2a2e35);android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();GradientDrawable picked=PlayerStyle.surface(0x1fe8a33d,10,this);picked.setStroke(dp(1),0x80e8a33d);bg.addState(new int[]{android.R.attr.state_selected},picked);bg.addState(new int[]{},idle);chip.setBackground(bg);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1);cp.setMargins(0,dp(4),dp(j==columns-1?0:6),dp(4));row.addView(chip,cp);chip.setOnClickListener(v->switchEpisode(target));
        }}
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applySystemBars();}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        if(playerView!=null&&(controlsVisible||playerView.isControllerFullyVisible())){
            playerView.hideController();controlsVisible=false;
            if(landscape)topChrome.setVisibility(View.GONE);return;
        }
        if(entryOrientation==Configuration.ORIENTATION_PORTRAIT && getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE && getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else super.onBackPressed();
    }
    private void saveProgress() {
        if (player != null) history.save(source, item, title, index, player.getCurrentPosition(), player.getDuration(), episodeLabel(index));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putFloat("windowBrightness",getWindow().getAttributes().screenBrightness);
        state.putInt("entryOrientation",entryOrientation);
        state.putInt("index", index);
        state.putBoolean("controlsVisible",controlsVisible);
        state.putLong("position", player == null ? position : player.getCurrentPosition());
        state.putBoolean("resume", player == null ? resume : player.getPlayWhenReady()); super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { saveProgress(); super.onPause(); }
    @Override protected void onStop() {
        if(stage!=null)stage.release();
        main.removeCallbacks(checkpoint); if (dialog != null) dialog.dismiss(); if (subtitles != null) subtitles.close();
        if (player != null) { saveProgress(); position = player.getCurrentPosition(); resume = player.getPlayWhenReady(); player.release(); player = null; }
        super.onStop();
    }
}

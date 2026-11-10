package family.libretv.tv;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.*;

/** One remote focus target per poster; child labels/images never intercept D-pad navigation. */
final class PosterCard extends LinearLayout {
    Runnable onFocused;
    final ImageView image;
    final TextView title, source, metadata, badge, score;
    PosterCard(Context context, int posterHeight) {
        super(context);
        setOrientation(VERTICAL); setPadding(dp(6), dp(6), dp(6), dp(10));
        setFocusable(true); setFocusableInTouchMode(true); setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setBackgroundResource(R.drawable.poster_card);
        FrameLayout art = new FrameLayout(context);
        addView(art, new LayoutParams(-1, posterHeight));
        image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xff1e2126);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) { outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        image.setClipToOutline(true);
        art.addView(image, new FrameLayout.LayoutParams(-1, -1));
        badge = new TextView(context); badge.setTextSize(11); badge.setTextColor(0xfff3f4f6);
        GradientDrawable chip = new GradientDrawable(); chip.setColor(0xcc0e0f11); chip.setCornerRadius(dp(6));
        badge.setBackground(chip); badge.setPadding(dp(7), dp(3), dp(7), dp(3)); badge.setVisibility(GONE);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeParams.setMargins(0, dp(8), dp(8), 0); art.addView(badge, badgeParams);
        score = new TextView(context); score.setTextSize(13); score.setTextColor(0xffe8a33d);
        score.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable scoreChip = new GradientDrawable(); scoreChip.setColor(0xcc0e0f11); scoreChip.setCornerRadius(dp(6));
        score.setBackground(scoreChip); score.setPadding(dp(7), dp(2), dp(7), dp(2)); score.setVisibility(GONE);
        FrameLayout.LayoutParams scoreParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        scoreParams.setMargins(0, 0, dp(8), dp(8)); art.addView(score, scoreParams);
        title = text(17, 0xfff3f4f6); title.setMaxLines(1); title.setMinLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        source = text(13, 0xffe8a33d); source.setMaxLines(1); source.setEllipsize(TextUtils.TruncateAt.END);
        metadata = text(12, 0xff6d737b); metadata.setMaxLines(1); metadata.setEllipsize(TextUtils.TruncateAt.END);
        setOnFocusChangeListener((v, focused) -> {
            animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(120).start();
            setTranslationZ(focused ? dp(8) : 0);
            if (focused && onFocused != null) onFocused.run();
        });
    }
    /** 评分浮层，显示在海报右下角。 */
    void setScore(String value) {
        if (value == null || value.trim().isEmpty()) { score.setVisibility(GONE); return; }
        score.setText(value.trim()); score.setVisibility(VISIBLE);
    }
    /** 名称、副标题与说明，空值直接隐藏，避免卡片出现空白行。 */
    void setLines(String name, String subtitle, String extra) {
        title.setText(name);
        fill(source, subtitle);
        fill(metadata, extra);
    }
    private void fill(TextView view, String value) {
        if (value == null || value.trim().isEmpty()) { view.setVisibility(GONE); return; }
        view.setText(value); view.setVisibility(VISIBLE);
    }
    /** 海报右上角的类型角标，按类型名取一个稳定的颜色。 */
    void setCategory(String value) {
        if (value == null || value.trim().isEmpty()) { badge.setVisibility(GONE); return; }
        String text = value.trim();
        int[] colors = {0xff3aa675, 0xffd9534f, 0xffc2578a, 0xff3d7fc1, 0xffb07d2b, 0xff7a5cc4};
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(colors[Math.abs(text.hashCode()) % colors.length]); chip.setCornerRadius(dp(6));
        badge.setBackground(chip); badge.setText(text); badge.setVisibility(VISIBLE);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(int size, int color) {
        TextView label = new TextView(getContext()); label.setTextSize(size); label.setTextColor(color);
        label.setGravity(Gravity.START); label.setPadding(dp(8), dp(4), dp(8), 0);
        addView(label, new LayoutParams(-1, -2)); return label;
    }
}

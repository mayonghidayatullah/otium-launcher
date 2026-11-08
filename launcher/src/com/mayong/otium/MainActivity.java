package com.mayong.otium;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    private long wallInterval() { return getPreferences(MODE_PRIVATE).getLong("wall_interval", 600000L); }
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel, weather;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = loadDockPref();
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= wallInterval()) nextWallpaper();
        else scheduleWallpaper(wallInterval() - (SystemClock.elapsedRealtime() - wallChangedAt));
        startVideo();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        stopVideo();
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        videoView = new android.widget.VideoView(this);
        if (android.os.Build.VERSION.SDK_INT >= 26) videoView.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE);
        videoView.setVisibility(View.GONE);
        videoView.setOnPreparedListener(mp -> { mp.setLooping(true); mp.setVolume(0f, 0f); });
        videoView.setOnErrorListener((mp, what, extra) -> {
            getPreferences(MODE_PRIVATE).edit().putString("wall_mode", "image").apply();
            stopVideo();
            nextWallpaper();
            android.widget.Toast.makeText(this, T("Video gagal diputar, kembali ke gambar", "Video failed, back to images"), android.widget.Toast.LENGTH_LONG).show();
            return true;
        });
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = listWallpapers();
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(8), dp(14), dp(8), dp(14));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(null);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-2, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.06f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(13, "sans-serif-medium", 0xFFFFFFFF);
        greeting.setLetterSpacing(0.06f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xFFFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(13, "sans-serif-medium", 0xFFFFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.06f);
        left.addView(date);
        weather = text(13, "sans-serif-medium", 0xFFFFFFFF);
        weather.setSingleLine(true);
        weather.setLetterSpacing(0.06f);
        weather.setPadding(0, dp(4), dp(16), 0);
        weather.setShadowLayer(dp(8), 0, dp(2), 0xF2000000);
        left.addView(weather);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(8), 0, dp(2), 0xF2000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill(T("Sumber", "Sources"), "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", "◉");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill(T("Setting", "Settings"), "⚙");
        settings.setOnClickListener(v -> openSettings());
        TextView wallPill = pill("Wallpaper", "✦");
        wallPill.setOnClickListener(v -> showWallpaperMenu(v));
        TextView weatherPill = pill(T("Cuaca", "Weather"), "☁");
        weatherPill.setOnClickListener(v -> showWeatherMenu(v));
        for (TextView p : new TextView[]{wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(110), -2);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        applyShow();
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
    greeting.setText(greetText(h));
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", appLocale()).format(now.getTime()).toUpperCase(appLocale()));
        fetchWeather();
    }

    private long lastWeather = 0;

    private void fetchWeather() {
        if (!prefOn("show_weather")) return;
        final long nowMs = SystemClock.elapsedRealtime();
        if (lastWeather != 0 && nowMs - lastWeather < 30 * 60 * 1000L) return;
        lastWeather = nowMs;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=" + wLat() + "&longitude=" + wLon() + "&current=temperature_2m,weather_code&timezone=auto");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                c.disconnect();
                org.json.JSONObject cur = new org.json.JSONObject(sb.toString()).getJSONObject("current");
                int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                int code = cur.getInt("weather_code");
                String d = code == 0 ? T("CERAH", "CLEAR") : code <= 3 ? T("BERAWAN", "CLOUDY") : code <= 48 ? T("BERKABUT", "FOGGY") : code <= 57 ? T("GERIMIS", "DRIZZLE") : code <= 94 ? T("HUJAN", "RAIN") : T("BADAI PETIR", "THUNDERSTORM");
                final String txt = temp + "\u00b0C  " + d + "  " + wName().toUpperCase();
                runOnUiThread(() -> weather.setText(txt));
            } catch (Exception e) {
                lastWeather = SystemClock.elapsedRealtime() - 25 * 60 * 1000L;
            }
        }).start();
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText("◉  " + name);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(null);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private static final long[] WALL_STEPS = {31536000000L, 30000L, 60000L, 300000L, 600000L};
    private static final String[] WALL_NAMES = {"Mati", "30 detik", "60 detik", "5 menit", "10 menit"};

    private String wallModeName() {
        long cur = wallInterval();
        for (int i = 0; i < WALL_STEPS.length; i++) if (WALL_STEPS[i] == cur) return lang().equals("id") ? WALL_NAMES[i] : WALL_NAMES_EN[i];
        return T("10 menit", "10 min");
    }

    private String[] listWallpapers() throws java.io.IOException {
        List<String> out = new ArrayList<>();
        String[] a = getAssets().list("wallpapers");
        if (a != null) Collections.addAll(out, a);
        java.io.File dir = getExternalFilesDir("wallpapers");
        java.io.File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (java.io.File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f.getAbsolutePath());
            }
        }
        return out.toArray(new String[0]);
    }

    private java.io.InputStream openWall(String name) throws java.io.IOException {
        return name.startsWith("/") ? new java.io.FileInputStream(name) : getAssets().open(name);
    }

    private int sampleFor(String name) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        try (java.io.InputStream in = openWall(name)) { BitmapFactory.decodeStream(in, null, b); } catch (java.io.IOException ignored) { }
        int s = 1;
        while (b.outWidth / (s * 2) >= 1920) s *= 2;
        return s;
    }

    private void selectWallpaper(int i) {
        getPreferences(MODE_PRIVATE).edit().putString("wall_mode", "image").apply();
        stopVideo();
        if (wallpapers.length == 0) return;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", i - 1).commit();
        wallIndex = i - 1;
        nextWallpaper();
    }

    private LinearLayout menuBox() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        return list;
    }

    private void fillMenu(LinearLayout list, List<TextView> rows) {
        for (TextView r : rows) list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
    }

    private void openPopup(View anchor, View content, int w, int h, List<TextView> rows) {
        PopupWindow pw = new PopupWindow(content, w, h, true);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private FrameLayout settingsView;
    private int homeFocusPrev;
    private LinearLayout settingsPanel;
    private final List<TextView> settingsCats = new ArrayList<>();

    private boolean prefOn(String k) { return getPreferences(MODE_PRIVATE).getBoolean(k, true); }
    private void prefSet(String k, boolean v) { getPreferences(MODE_PRIVATE).edit().putBoolean(k, v).apply(); }

    private void applyShow() {
        int g = prefOn("show_greeting") ? View.VISIBLE : View.GONE;
        int c = prefOn("show_clock") ? View.VISIBLE : View.GONE;
        int w = prefOn("show_weather") ? View.VISIBLE : View.GONE;
        greeting.setVisibility(g);
        clock.setVisibility(c);
        ampm.setVisibility(c);
        weather.setVisibility(w);
        date.setVisibility(prefOn("show_date") ? View.VISIBLE : View.GONE);
    }

    private String greetText(int h) {
        String base = h < 5 ? T("SELAMAT MALAM", "GOOD NIGHT") : h < 11 ? T("SELAMAT PAGI", "GOOD MORNING") : h < 15 ? T("SELAMAT SIANG", "GOOD AFTERNOON") : h < 18 ? T("SELAMAT SORE", "GOOD EVENING") : T("SELAMAT MALAM", "GOOD NIGHT");
        String n = getPreferences(MODE_PRIVATE).getString("greet_name", "").trim();
        return n.isEmpty() ? base : base + ", " + n.toUpperCase();
    }

    private GradientDrawable rowBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(color);
        return g;
    }

    private void openSettings() {
        if (settingsView != null) return;
        if (inputsPopup != null) inputsPopup.dismiss();
        ViewGroup content = findViewById(android.R.id.content);
        View home = content.getChildAt(0);
        if (home instanceof ViewGroup) {
            homeFocusPrev = ((ViewGroup) home).getDescendantFocusability();
            ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        FrameLayout sv = new FrameLayout(this);
        sv.setBackgroundColor(0xF2101826);
        sv.setClickable(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(48), dp(32), dp(48), dp(32));
        TextView title = text(22, "sans-serif-medium", Color.WHITE);
        title.setText(T("Setting", "Settings"));
        body.addView(title, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout cols = new LinearLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(16);
        body.addView(cols, cp);
        LinearLayout cats = menuBox();
        cols.addView(cats, new LinearLayout.LayoutParams(dp(210), -2));
        settingsCats.clear();
        String[] names = {T("Tampilan", "Display"), "Wallpaper", "Dock", T("Sumber", "Sources"), T("Sistem", "System")};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(15, "sans-serif-medium", Color.WHITE);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setBackground(rowBg(0));
            t.setOnFocusChangeListener((v, has) -> { if (has) selectCat(idx); });
            cats.addView(t, new LinearLayout.LayoutParams(-1, dp(40)));
            settingsCats.add(t);
        }
        settingsPanel = menuBox();
        android.widget.ScrollView ps = new android.widget.ScrollView(this);
        ps.setFocusable(false);
        ps.addView(settingsPanel, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1f);
        pp.leftMargin = dp(16);
        cols.addView(ps, pp);
        sv.addView(body, new FrameLayout.LayoutParams(-1, -1));
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        settingsView = sv;
        settingsCats.get(0).requestFocus();
    }

    private void closeSettings() {
        if (settingsView == null) return;
        ViewGroup content = findViewById(android.R.id.content);
        content.removeView(settingsView);
        settingsView = null;
        View home = content.getChildAt(0);
        if (home instanceof ViewGroup) ((ViewGroup) home).setDescendantFocusability(homeFocusPrev);
        if (!pills.isEmpty()) pills.get(pills.size() - 1).requestFocus();
        applyShow();
    }

    private void selectCat(int idx) {
        for (int i = 0; i < settingsCats.size(); i++) settingsCats.get(i).setBackground(rowBg(i == idx ? 0x663B9BE0 : 0));
        settingsPanel.removeAllViews();
        List<View> rows = new ArrayList<>();
        if (idx == 0) fillDisplay(rows); else if (idx == 1) fillWall(rows); else if (idx == 2) fillDock(rows); else if (idx == 3) fillSource(rows); else fillSystem(rows);
        for (View r : rows) settingsPanel.addView(r, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private LinearLayout setRow(String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setFocusable(true);
        r.setClickable(true);
        r.setId(View.generateViewId());
        r.setBackground(rowBg(0));
        TextView l = text(14, "sans-serif-medium", Color.WHITE);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = text(14, "sans-serif-medium", 0xFF7CC4FF);
        val.setText(value);
        r.addView(val, new LinearLayout.LayoutParams(-2, -2));
        r.setOnFocusChangeListener((x, has) -> r.setBackground(rowBg(has ? 0x40FFFFFF : 0)));
        return r;
    }

    private LinearLayout toggleRow(String label, String key, Runnable after) {
        LinearLayout r = setRow(label, prefOn(key) ? T("● Hidup", "● On") : T("○ Mati", "○ Off"));
        r.setOnClickListener(v -> {
            boolean nv = !prefOn(key);
            prefSet(key, nv);
            ((TextView) r.getChildAt(1)).setText(nv ? T("● Hidup", "● On") : T("○ Mati", "○ Off"));
            applyShow();
            if (after != null) after.run();
        });
        return r;
    }

    private void fillDisplay(List<View> rows) {
        rows.add(toggleRow(T("Sapaan", "Greeting"), "show_greeting", null));
        String gn = getPreferences(MODE_PRIVATE).getString("greet_name", "").trim();
        LinearLayout nm = setRow(T("Nama di sapaan", "Greeting name"), gn.isEmpty() ? T("Belum diisi", "Not set") : gn);
        nm.setOnClickListener(v -> askName());
        rows.add(nm);
        rows.add(toggleRow(T("Jam", "Clock"), "show_clock", null));
        rows.add(toggleRow(T("Tanggal", "Date"), "show_date", null));
        rows.add(toggleRow(T("Cuaca", "Weather"), "show_weather", () -> { if (prefOn("show_weather")) { lastWeather = 0; fetchWeather(); } }));
        LinearLayout city = setRow(T("Kota cuaca", "Weather city"), wName());
        city.setOnClickListener(v -> showWeatherMenu(v));
        rows.add(city);
        LinearLayout lg = setRow(T("Bahasa", "Language"), langName());
        lg.setOnClickListener(v -> {
            String p = langPref();
            String nx = p.equals("auto") ? "id" : p.equals("id") ? "en" : "auto";
            getPreferences(MODE_PRIVATE).edit().putString("lang", nx).apply();
            relabelSettings();
            lastWeather = 0;
            fetchWeather();
            updateClock();
        });
        rows.add(lg);
    }

    private void askName() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint(T("Nama panggilan (kosongkan untuk menghapus)", "Nickname (leave empty to remove)"));
        et.setText(getPreferences(MODE_PRIVATE).getString("greet_name", ""));
        new android.app.AlertDialog.Builder(this)
            .setTitle(T("Nama di sapaan", "Greeting name"))
            .setView(et)
            .setPositiveButton(T("Simpan", "Save"), (d, w) -> {
                getPreferences(MODE_PRIVATE).edit().putString("greet_name", et.getText().toString().trim()).apply();
                updateClock();
            })
            .setNegativeButton(T("Batal", "Cancel"), null)
            .show();
    }

    private void fillWall(List<View> rows) {
        LinearLayout now = setRow(T("Ganti sekarang", "Change now"), "›");
        now.setOnClickListener(v -> nextWallpaper());
        rows.add(now);
        LinearLayout mode = setRow("Slideshow", wallModeName());
        mode.setOnClickListener(v -> {
            long cur = wallInterval();
            int idx = 0;
            for (int i = 0; i < WALL_STEPS.length; i++) if (WALL_STEPS[i] == cur) idx = i;
            long nv = WALL_STEPS[(idx + 1) % WALL_STEPS.length];
            getPreferences(MODE_PRIVATE).edit().putLong("wall_interval", nv).apply();
            ((TextView) mode.getChildAt(1)).setText(wallModeName());
            scheduleWallpaper(nv);
        });
        rows.add(mode);
        LinearLayout pick = setRow(T("Pilih gambar", "Choose image"), "›");
        pick.setOnClickListener(v -> {
            try { wallpapers = listWallpapers(); } catch (java.io.IOException ignored) { }
            showWallpaperPicker(v);
        });
        rows.add(pick);
        LinearLayout vid = setRow("Video wallpaper", videoOn() ? T("● Hidup", "● On") : T("○ Mati", "○ Off"));
        vid.setOnClickListener(v -> {
            if (videoFile() == null) {
                android.widget.Toast.makeText(this, T("Belum ada video di folder videos", "No video in the videos folder"), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            boolean nv = !videoOn();
            getPreferences(MODE_PRIVATE).edit().putString("wall_mode", nv ? "video" : "image").apply();
            ((TextView) vid.getChildAt(1)).setText(nv ? T("● Hidup", "● On") : T("○ Mati", "○ Off"));
            if (nv) { startVideo(); wallChangedAt = SystemClock.elapsedRealtime(); handler.removeCallbacks(rotateWallpaper); scheduleWallpaper(wallInterval()); } else { stopVideo(); nextWallpaper(); }
        });
        rows.add(vid);
        LinearLayout pv = setRow(T("Pilih video", "Choose video"), "›");
        pv.setOnClickListener(v -> showVideoPicker(v));
        rows.add(pv);
    }

    private void fillSource(List<View> rows) {
        LinearLayout r = setRow(T("Pilih sumber input", "Choose input source"), "›");
        r.setOnClickListener(v -> showInputs(v));
        rows.add(r);
    }

    private String[] configDock() { return getResources().getStringArray(R.array.dock_apps); }

    private String[] loadDockPref() {
        String s = getPreferences(MODE_PRIVATE).getString("dock_list", null);
        if (s == null) return configDock();
        return s.isEmpty() ? new String[0] : s.split(",");
    }

    private List<String> dockList() {
        List<String> l = new ArrayList<>();
        for (String p : dockApps) if (isInstalled(p)) l.add(p);
        return l;
    }

    private void saveDock(List<String> l) {
        getPreferences(MODE_PRIVATE).edit().putString("dock_list", android.text.TextUtils.join(",", l)).apply();
        dockApps = l.toArray(new String[0]);
        buildApps();
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) { return pkg; }
    }

    private void refreshDock(int focusIdx) {
        selectCat(2);
        int n = settingsPanel.getChildCount();
        if (n > 0) settingsPanel.getChildAt(Math.max(0, Math.min(focusIdx, n - 1))).requestFocus();
    }

    private void fillDock(List<View> rows) {
        List<String> l = dockList();
        for (int i = 0; i < l.size(); i++) {
            final int idx = i;
            LinearLayout r = setRow((i + 1) + ".  " + labelOf(l.get(i)), "›");
            r.setOnClickListener(v -> showDockActions(v, idx));
            rows.add(r);
        }
        LinearLayout add = setRow(T("Tambah aplikasi", "Add app"), "+");
        add.setOnClickListener(v -> showAddApp(v));
        rows.add(add);
        LinearLayout reset = setRow(T("Reset ke awal", "Reset to default"), "›");
        reset.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().remove("dock_list").apply();
            dockApps = configDock();
            buildApps();
            refreshDock(0);
        });
        rows.add(reset);
    }

    private void showDockActions(View anchor, int idx) {
        List<String> l = dockList();
        List<TextView> rows = new ArrayList<>();
        if (idx > 0) {
            TextView a = inputRow(T("Geser ke kiri", "Move left"), () -> { });
            a.setOnClickListener(v -> {
                if (inputsPopup != null) inputsPopup.dismiss();
                Collections.swap(l, idx, idx - 1);
                saveDock(l);
                refreshDock(idx - 1);
            });
            rows.add(a);
        }
        if (idx < l.size() - 1) {
            TextView b = inputRow(T("Geser ke kanan", "Move right"), () -> { });
            b.setOnClickListener(v -> {
                if (inputsPopup != null) inputsPopup.dismiss();
                Collections.swap(l, idx, idx + 1);
                saveDock(l);
                refreshDock(idx + 1);
            });
            rows.add(b);
        }
        TextView c = inputRow(T("Hapus dari dock", "Remove from dock"), () -> { });
        c.setOnClickListener(v -> {
            if (inputsPopup != null) inputsPopup.dismiss();
            l.remove(idx);
            saveDock(l);
            refreshDock(idx);
        });
        rows.add(c);
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        openPopup(anchor, box, dp(240), -2, rows);
    }

    private void showAddApp(View anchor) {
        PackageManager pm = getPackageManager();
        List<String> cur = dockList();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String[]> apps = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (hidden.contains(p) || cur.contains(p) || p.equals(getPackageName()) || !seen.add(p)) continue;
            apps.add(new String[]{String.valueOf(ri.loadLabel(pm)), p});
        }
        Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        if (apps.isEmpty()) {
            android.widget.Toast.makeText(this, T("Semua aplikasi sudah ada di dock", "All apps are already in the dock"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<TextView> rows = new ArrayList<>();
        for (String[] a : apps) {
            TextView r = inputRow(a[0], () -> { });
            r.setOnClickListener(v -> {
                if (inputsPopup != null) inputsPopup.dismiss();
                List<String> l = dockList();
                l.add(a[1]);
                saveDock(l);
                refreshDock(l.size() - 1);
            });
            rows.add(r);
        }
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        openPopup(anchor, sv, dp(260), dp(320), rows);
    }

    private void fillSystem(List<View> rows) {
        String[][] items = {{T("Wi-Fi dan jaringan", "Wi-Fi and network"), "wifi"}, {T("Pengaturan Android", "Android settings"), "main"}, {T("Aplikasi", "Apps"), "apps"}, {T("Layar", "Display"), "display"}, {T("Suara", "Sound"), "sound"}};
        for (String[] it : items) {
            LinearLayout r = setRow(it[0], "›");
            r.setOnClickListener(v -> openSystem(it[1]));
            rows.add(r);
        }
    }

    private void openSystem(String k) {
        String a = k.equals("wifi") ? Settings.ACTION_WIFI_SETTINGS : k.equals("apps") ? Settings.ACTION_APPLICATION_SETTINGS : k.equals("display") ? Settings.ACTION_DISPLAY_SETTINGS : k.equals("sound") ? Settings.ACTION_SOUND_SETTINGS : Settings.ACTION_SETTINGS;
        if (!launch(new Intent(a))) launch(new Intent(Settings.ACTION_SETTINGS));
    }

    private String langPref() { return getPreferences(MODE_PRIVATE).getString("lang", "auto"); }

    private String lang() {
        String p = langPref();
        if (!p.equals("auto")) return p;
        String d = Locale.getDefault().getLanguage();
        return (d.equals("in") || d.equals("id")) ? "id" : "en";
    }

    private String T(String id, String en) { return lang().equals("id") ? id : en; }

    private Locale appLocale() { return lang().equals("id") ? new Locale("id", "ID") : Locale.ENGLISH; }

    private String langName() {
        String p = langPref();
        return p.equals("id") ? "Indonesia" : p.equals("en") ? "English" : T("Otomatis", "Auto");
    }

    private static final String[] WALL_NAMES_EN = {"Off", "30 sec", "60 sec", "5 min", "10 min"};

    private void relabelSettings() {
        closeSettings();
        openSettings();
        selectCat(0);
        int n = settingsPanel.getChildCount();
        if (n > 0) settingsPanel.getChildAt(n - 1).requestFocus();
    }

    private android.widget.VideoView videoView;

    private java.io.File videoFileOld() {
        java.io.File dir = getExternalFilesDir("videos");
        java.io.File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return null;
        java.util.Arrays.sort(fs);
        for (java.io.File f : fs) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv")) return f;
        }
        return null;
    }

    private boolean videoOn() {
        return getPreferences(MODE_PRIVATE).getString("wall_mode", "image").equals("video");
    }

    private void startVideo() {
        if (videoView == null) return;
        java.io.File f = videoOn() ? videoFile() : null;
        if (f == null) { stopVideo(); return; }
        try {
            videoView.setVisibility(View.VISIBLE);
            videoView.setVideoPath(f.getAbsolutePath());
            videoView.start();
        } catch (Exception e) { stopVideo(); }
    }

    private void stopVideo() {
        if (videoView == null) return;
        try { videoView.stopPlayback(); } catch (Exception ignored) { }
        videoView.setVisibility(View.GONE);
    }

    private void seedVideos() {
        try {
            android.content.SharedPreferences sp = getPreferences(MODE_PRIVATE);
            if (sp.getBoolean("videos_seeded_v1", false)) return;
            java.io.File dir = getExternalFilesDir("videos");
            if (dir == null) return;
            dir.mkdirs();
            String[] names = getAssets().list("videos");
            if (names != null) {
                for (String n : names) {
                    java.io.File out = new java.io.File(dir, n);
                    if (out.exists()) continue;
                    java.io.File tmp = new java.io.File(dir, n + ".tmp");
                    try (java.io.InputStream in = getAssets().open("videos/" + n);
                         java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                    }
                    tmp.renameTo(out);
                }
            }
            sp.edit().putBoolean("videos_seeded_v1", true).apply();
        } catch (Exception ignored) { }
    }

    private java.io.File[] listVideos() {
        seedVideos();
        java.io.File dir = getExternalFilesDir("videos");
        java.io.File[] fs = dir == null ? null : dir.listFiles();
        List<java.io.File> out = new ArrayList<>();
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (java.io.File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv")) out.add(f);
            }
        }
        return out.toArray(new java.io.File[0]);
    }

    private java.io.File videoFile() {
        java.io.File[] vs = listVideos();
        if (vs.length == 0) return null;
        String sel = getPreferences(MODE_PRIVATE).getString("wall_video", "");
        for (java.io.File f : vs) if (f.getName().equals(sel)) return f;
        return vs[0];
    }

    private void showVideoPicker(View anchor) {
        java.io.File[] vs = listVideos();
        java.io.File cur = videoFile();
        if (vs.length == 0) {
            android.widget.Toast.makeText(this, T("Belum ada video di folder videos", "No video in the videos folder"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        List<TextView> rows = new ArrayList<>();
        for (java.io.File f : vs) {
            boolean on = cur != null && f.getName().equals(cur.getName());
            TextView r = inputRow((on ? "✓  " : "") + prettyName(f.getName()), () -> { });
            r.setOnClickListener(v -> {
                if (inputsPopup != null) inputsPopup.dismiss();
                getPreferences(MODE_PRIVATE).edit().putString("wall_video", f.getName()).putString("wall_mode", "video").apply();
                stopVideo();
                startVideo();
            });
            rows.add(r);
        }
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        openPopup(anchor, sv, dp(260), dp(320), rows);
    }

    private String wName() { return getPreferences(MODE_PRIVATE).getString("w_name", "Jakarta"); }
    private String wLat() { return getPreferences(MODE_PRIVATE).getString("w_lat", "-6.2088"); }
    private String wLon() { return getPreferences(MODE_PRIVATE).getString("w_lon", "106.8456"); }

    private void setCity(String name, String lat, String lon) {
        getPreferences(MODE_PRIVATE).edit().putString("w_name", name).putString("w_lat", lat).putString("w_lon", lon).apply();
        weather.setText("…");
        lastWeather = 0;
        fetchWeather();
    }

    private static final String[][] CITIES = {
        {"Magelang", "-7.4797", "110.2177"}, {"Yogyakarta", "-7.7956", "110.3695"},
        {"Semarang", "-6.9667", "110.4167"}, {"Solo", "-7.5666", "110.8167"},
        {"Jakarta", "-6.2088", "106.8456"}, {"Bandung", "-6.9175", "107.6191"},
        {"Surabaya", "-7.2575", "112.7521"}, {"Malang", "-7.9839", "112.6214"},
        {"Denpasar", "-8.6705", "115.2126"}, {"Medan", "3.5952", "98.6722"},
        {"Makassar", "-5.1477", "119.4327"}, {"Palembang", "-2.9761", "104.7754"},
        {"Balikpapan", "-1.2379", "116.8529"}, {"Singapura", "1.3521", "103.8198"},
        {"Kuala Lumpur", "3.1390", "101.6869"}, {"Tokyo", "35.6762", "139.6503"},
        {"Seoul", "37.5665", "126.9780"}, {"Sydney", "-33.8688", "151.2093"}
    };

    private void showWeatherMenu(View anchor) {
        List<TextView> rows = new ArrayList<>();
        TextView search = inputRow(T("Cari kota / negara…", "Search city / country…"), () -> { });
        search.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); askCity(anchor); });
        TextView list = inputRow(T("Pilih dari daftar…", "Choose from list…"), () -> { });
        list.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); showCityList(anchor); });
        TextView refresh = inputRow(T("Segarkan: ", "Refresh: ") + wName(), () -> { });
        refresh.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); lastWeather = 0; fetchWeather(); });
        rows.add(search);
        rows.add(list);
        rows.add(refresh);
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        openPopup(anchor, box, dp(260), -2, rows);
    }

    private void showCityList(View anchor) {
        List<TextView> rows = new ArrayList<>();
        for (String[] c : CITIES) {
            TextView r = inputRow((c[0].equals(wName()) ? "✓  " : "") + c[0], () -> { });
            r.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); setCity(c[0], c[1], c[2]); });
            rows.add(r);
        }
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        openPopup(anchor, sv, dp(240), dp(320), rows);
    }

    private void askCity(View anchor) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint(T("Nama kota atau negara", "City or country name"));
        new android.app.AlertDialog.Builder(this)
            .setTitle(T("Cari kota atau negara", "Search city or country"))
            .setView(et)
            .setPositiveButton(T("Cari", "Search"), (d, w) -> searchCity(et.getText().toString().trim(), anchor))
            .setNegativeButton(T("Batal", "Cancel"), null)
            .show();
    }

    private void searchCity(String q, View anchor) {
        if (q.isEmpty()) return;
        android.widget.Toast.makeText(this, T("Mencari…", "Searching…"), android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String[]> found = new ArrayList<>();
            try {
                java.net.URL u = new java.net.URL("https://geocoding-api.open-meteo.com/v1/search?count=8&language=id&format=json&name=" + java.net.URLEncoder.encode(q, "UTF-8"));
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                c.disconnect();
                org.json.JSONArray a = new org.json.JSONObject(sb.toString()).optJSONArray("results");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject r = a.getJSONObject(i);
                    String name = r.optString("name");
                    String admin = r.optString("admin1", "");
                    String country = r.optString("country", "");
                    String label = name + (admin.isEmpty() || admin.equals(name) ? "" : ", " + admin) + (country.isEmpty() ? "" : ", " + country);
                    found.add(new String[]{label, name, String.valueOf(r.getDouble("latitude")), String.valueOf(r.getDouble("longitude"))});
                }
            } catch (Exception e) { }
            runOnUiThread(() -> showCityResults(found, anchor));
        }).start();
    }

    private void showCityResults(List<String[]> found, View anchor) {
        if (isDestroyed()) return;
        if (found.isEmpty()) {
            android.widget.Toast.makeText(this, T("Kota tidak ditemukan, atau TV tidak online", "City not found, or TV is offline"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        List<TextView> rows = new ArrayList<>();
        for (String[] f : found) {
            TextView r = inputRow(f[0], () -> { });
            r.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); setCity(f[1], f[2], f[3]); });
            rows.add(r);
        }
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        openPopup(anchor, box, dp(340), -2, rows);
    }

    private void showWallpaperMenu(View anchor) {
        try { wallpapers = listWallpapers(); } catch (java.io.IOException ignored) { }
        List<TextView> rows = new ArrayList<>();
        TextView next = inputRow(T("Ganti sekarang", "Change now"), () -> { });
        next.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); nextWallpaper(); });
        TextView mode = inputRow("Slideshow: " + wallModeName(), () -> { });
        mode.setOnClickListener(v -> {
            long cur = wallInterval();
            int idx = 0;
            for (int i = 0; i < WALL_STEPS.length; i++) if (WALL_STEPS[i] == cur) idx = i;
            long nv = WALL_STEPS[(idx + 1) % WALL_STEPS.length];
            getPreferences(MODE_PRIVATE).edit().putLong("wall_interval", nv).apply();
            ((TextView) v).setText("Slideshow: " + wallModeName());
            scheduleWallpaper(nv);
        });
        TextView pick = inputRow(T("Pilih gambar…", "Choose image…"), () -> { });
        pick.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); showWallpaperPicker(anchor); });
        rows.add(next);
        rows.add(mode);
        rows.add(pick);
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        openPopup(anchor, box, dp(240), -2, rows);
    }

    private String prettyName(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("-?\\d{3,4}x\\d{3,4}.*$", "");
        n = n.replaceAll("^wp\\d+-", "").replaceAll("-desktop-4k-wallpapers$", "");
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("[-_ ]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? "Wallpaper" : sb.toString();
    }

    private void showWallpaperPicker(View anchor) {
        List<TextView> rows = new ArrayList<>();
        for (int i = 0; i < wallpapers.length; i++) {
            final int idx = i;
            String w = wallpapers[i];
            String label = prettyName(w);
            TextView r = inputRow((i == wallIndex ? "✓  " : "") + label, () -> { });
            r.setOnClickListener(v -> { if (inputsPopup != null) inputsPopup.dismiss(); selectWallpaper(idx); });
            rows.add(r);
        }
        if (rows.isEmpty()) return;
        LinearLayout box = menuBox();
        fillMenu(box, rows);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        openPopup(anchor, sv, dp(260), dp(320), rows);
    }

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (videoOn() && videoFile() != null) {
            java.io.File[] vs = listVideos();
            if (vs.length > 1) {
                java.io.File cur = videoFile();
                int ci = 0;
                for (int k = 0; k < vs.length; k++) if (vs[k].getName().equals(cur.getName())) ci = k;
                java.io.File nxt = vs[(ci + 1) % vs.length];
                getPreferences(MODE_PRIVATE).edit().putString("wall_video", nxt.getName()).apply();
                wallChangedAt = SystemClock.elapsedRealtime();
                startVideo();
                scheduleWallpaper(wallInterval());
            }
            return;
        }
        if (wallpapers.length == 0 || (videoOn() && videoFile() != null)) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = wallpapers[wallIndex].startsWith("/") ? wallpapers[wallIndex] : "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = openWall(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                o.inSampleSize = sampleFor(name);
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(wallInterval());
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private Typeface appTf;

    private Typeface appFont(String family) {
        if (appTf == null) {
            try { appTf = Typeface.createFromAsset(getAssets(), "fonts/Nunito-SemiBold.ttf"); }
            catch (Exception e) { appTf = Typeface.create(family, Typeface.BOLD); }
        }
        return appTf;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 1.25f);
        t.setTypeface(appFont(family));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (settingsView != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeSettings();
            return true;
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}

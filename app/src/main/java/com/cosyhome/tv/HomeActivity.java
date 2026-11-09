package com.cosyhome.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.tv.TvView;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ChangedPackages;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cosy Home: a clock over a valley that follows the sun, a dock of six apps, and on Down a
 * frosted panel with every app and the TV's inputs.
 */
public class HomeActivity extends Activity implements SceneView.Listener {

    private static final long MINUTE_MS = 60_000L;
    private static final int GRID_COLUMNS = 6;
    /** Tiles on the clock screen rest a touch translucent, so the focused one stands out. */
    private static final float DOCK_REST_ALPHA = 0.88f;
    /** OLED care: the clock layer moves through these dp offsets, one step a minute. */
    private static final int[][] SHIFT_DP = {{0, 0}, {3, 2}, {5, -1}, {2, -4}, {-2, -3}, {-4, 1}, {-1, 4}};

    private SceneView scene;
    private View homeLayer, appsLayer;
    private TextView clock, date, weather, headerTime, focusedName;
    private LinearLayout dock, inputs;
    private GridLayout grid;
    private GlassPanel appsPanel, inputsPanel;

    private List<String> dockApps, pinnedApps;
    private Set<String> hiddenApps;
    private String liveTvPkg, usbPkg, ambilightAction, place;
    private double lat, lon;
    private boolean located;
    private boolean fahrenheit;
    private float density;

    private boolean appsOpen;
    /** The package sequence the tiles were built at, or -1 before the first build. */
    private int packageSequence = -1;
    private boolean resumed;
    /** The rows need rebuilding at the next resume: something arrived while an app was in front. */
    private boolean rowsStale;
    /** Survives the activity being recreated while an app is open; resets only with the process. */
    private static int lastGrid = 0;
    /** Preview a phase from adb: `am start -n com.famiglia.home/com.cosyhome.tv.HomeActivity --es phase day` ("auto" clears it). */
    private static SceneView.Phase forcedPhase;
    private int shiftStep;
    /** Preview from adb: --es season christmas --es fx snow ("auto" clears either). */
    private static Season forcedSeason;
    private static Season.Fx forcedFx;
    private double snowAtOrBelow = 0;
    private int channelCount = 6;
    private final Map<String, String> inputNames = new HashMap<>();
    private final List<View> gridTiles = new ArrayList<>();
    private static List<Channels.Channel> channels = Collections.emptyList();
    private static final String TAG = "FamigliaHome";
    private static final String ROW_FAVORITES = "favorites", ROW_CHANNELS = "channels", ROW_DEVICES = "devices", ROW_RECENT = "recent";
    private static List<WatchNext.Item> recent = Collections.emptyList();
    /** Apps watched lately that publish no titles, shown after the titles in the same row. */
    private static List<String> recentApps = Collections.emptyList();
    private static String recentKey = "";
    private SharedPreferences prefs;
    private List<String> rows;
    private List<Apps.App> allApps = Collections.emptyList();
    private final List<List<View>> rowTiles = new ArrayList<>();
    /** Each row's label, the top of the row when scrolling a row at a time. */
    private final List<View> rowLabels = new ArrayList<>();
    private RoomyScrollView dockScroll;
    private static int lastRow = 0, lastCol = 0;
    private FrameLayout previewBox;
    private TvView previewView;
    private TextView previewLabel;
    private final List<View> actionChips = new ArrayList<>();
    private static final int LARGE_TILE_DP = 74, SMALL_TILE_DP = 42;
    /** A row's label and the gaps around its tiles. */
    private static final int ROW_EXTRA_DP = 34;
    /**
     * At most this much of the 540 dp screen holds rows, about two of them: the others scroll into
     * view, so the clock and the landscape keep the top half.
     */
    private static final int MAX_ROWS_WINDOW_DP = 262;
    private static final int[] DEVICE_ICONS = {R.drawable.ic_dev_console, R.drawable.ic_dev_decoder,
            R.drawable.ic_dev_soundbar, R.drawable.ic_dev_disc, R.drawable.ic_dev_pc, R.drawable.ic_dev_stick,
            R.drawable.ic_dev_hdmi};
    private static final int[] DEVICE_ICON_NAMES = {R.string.icon_console, R.string.icon_decoder,
            R.string.icon_soundbar, R.string.icon_disc, R.string.icon_pc, R.string.icon_stick, R.string.icon_hdmi};
    private final Runnable[] pendingPreview = new Runnable[1];

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            tick();
            shiftClockLayer();
            // Land just after the next minute boundary so the clock never shows the old minute.
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 200);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(HomeActivity.this, lat, lon, fahrenheit, text -> showWeather(text));
            handler.postDelayed(this, Weather.REFRESH_MS);
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED then ADDED; wait for the new APK instead of dropping the tile.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // Behind an app, leave it to onResume, which asks PackageManager what changed.
            if (!resumed) return;
            loadApps();
            restoreFocus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;

        // Built from cosy.defaults.properties and cosy.properties, see app/build.gradle.
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        dockApps = list(R.string.dock);
        String savedDock = prefs.getString("dock", null);
        if (savedDock != null) dockApps = split(savedDock);
        rows = split(prefs.getString("rows", ROW_RECENT + "," + ROW_FAVORITES + "," + ROW_CHANNELS + "," + ROW_DEVICES));
        // Once, for a TV set up before this order became the standard one.
        if (!prefs.getBoolean("rows_v3", false)) {
            rows = split(ROW_RECENT + "," + ROW_FAVORITES + "," + ROW_CHANNELS + "," + ROW_DEVICES);
            prefs.edit().putBoolean("rows_v3", true).putString("rows", String.join(",", rows)).apply();
        }
        pinnedApps = list(R.string.pinned);
        hiddenApps = new HashSet<>(list(R.string.hidden));
        liveTvPkg = getString(R.string.live_tv_package);
        usbPkg = getString(R.string.usb_package);
        ambilightAction = getString(R.string.ambilight_action);
        place = getString(R.string.location_name);
        fahrenheit = "fahrenheit".equalsIgnoreCase(getString(R.string.weather_temperature_unit));
        try {
            lat = Double.parseDouble(getString(R.string.location_latitude));
            lon = Double.parseDouble(getString(R.string.location_longitude));
            located = true;
        } catch (NumberFormatException e) {
            located = false;   // no location set: fixed phase times and no weather
        }

        // Each on its own, so a bad value keeps its default without losing the other.
        try {
            snowAtOrBelow = Double.parseDouble(getString(R.string.snow_at_or_below));
        } catch (NumberFormatException ignored) {
        }
        try {
            channelCount = Integer.parseInt(getString(R.string.channels_count));
        } catch (NumberFormatException ignored) {
        }
        for (String pair : list(R.string.input_names)) {
            int eq = pair.indexOf('=');
            if (eq > 0) inputNames.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }

        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("input_")) inputNames.put(key.substring(6), prefs.getString(key, ""));
        }

        scene = findViewById(R.id.scene);
        previewBox = findViewById(R.id.previewBox);
        previewLabel = findViewById(R.id.previewLabel);
        findViewById(R.id.customize).setOnClickListener(v -> openCustomize());
        findViewById(R.id.customize).setOnKeyListener((v, keyCode, e) ->
                e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP);
        homeLayer = findViewById(R.id.homeLayer);
        appsLayer = findViewById(R.id.appsLayer);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        headerTime = findViewById(R.id.headerTime);
        focusedName = findViewById(R.id.focusedName);
        dock = findViewById(R.id.dock);
        dockScroll = findViewById(R.id.dockScroll);
        dockScroll.setRowSnapping(true);
        inputs = findViewById(R.id.inputs);
        grid = findViewById(R.id.appsGrid);
        appsPanel = findViewById(R.id.appsPanel);
        inputsPanel = findViewById(R.id.inputsPanel);
        scene.setListener(this);

        findViewById(R.id.back).setOnClickListener(v -> closeApps());
        View ambilight = findViewById(R.id.ambilight);
        if (ambilightAction.isEmpty()
                || getPackageManager().resolveActivity(new Intent(ambilightAction), 0) == null) {
            ambilight.setVisibility(View.GONE);
        }
        ambilight.setOnClickListener(v -> open(new Intent(ambilightAction)));
        findViewById(R.id.settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // The header row is the top of the screen: Up goes nowhere rather than wrapping around.
        View.OnKeyListener topRow = (v, keyCode, e) ->
                e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP;
        findViewById(R.id.back).setOnKeyListener(topRow);
        findViewById(R.id.ambilight).setOnKeyListener(topRow);
        findViewById(R.id.settings).setOnKeyListener(topRow);

        weather.setText(weatherLine(Weather.cached(this, fahrenheit)));
        readPhaseOverride(getIntent());

        Thread channelLoad = new Thread(() -> {
            List<Channels.Channel> found = Channels.first(getApplicationContext(), channelCount);
            handler.post(() -> {
                if (found.isEmpty()) return;
                channels = found;
                if (resumed) {
                    buildRows();
                    restoreFocus();
                } else {
                    rowsStale = true;   // behind an app: onResume adds the row
                }
            });
        }, "home-channels");
        channelLoad.start();

        // Registered once rather than in onResume: there the call waits about 50 ms for the
        // ActivityManager lock, which the system holds while it switches activities.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
    }

    private static void readPhaseOverride(Intent intent) {
        if (intent == null) return;
        String se = intent.getStringExtra("season"), f = intent.getStringExtra("fx");
        if (se != null) {
            try {
                forcedSeason = Season.valueOf(se.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                forcedSeason = null;
            }
        }
        if (f != null) {
            try {
                forcedFx = Season.Fx.valueOf(f.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                forcedFx = null;
            }
        }
        String p = intent.getStringExtra("phase");
        if (p == null) return;
        try {
            forcedPhase = SceneView.Phase.valueOf(p.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            forcedPhase = null;   // "auto" or anything else: follow the sun again
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Between two resumes the package list almost never changes, so keep the tiles unless
        // PackageManager reports a change. It returns null when nothing changed.
        if (packageSequence < 0 || getPackageManager().getChangedPackages(packageSequence) != null) {
            loadApps();
        } else if (rowsStale) {
            buildRows();
        }
        buildInputs();
        refreshRecent();
        minuteTick.run();
        if (located) handler.postDelayed(weatherTick, Weather.untilStale(this, fahrenheit));
        scene.setRunning(true);
        restoreFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
        stopPreview();
        scene.setRunning(false);
    }

    /** Out of sight behind an app: give the scene's 8 MB back while Netflix and friends need it. */
    @Override
    protected void onStop() {
        super.onStop();
        scene.release();
    }

    /** Home pressed while already home: back to the clock with the first dock app focused. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPhaseOverride(intent);
        tick();
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        lastRow = 0;
        lastCol = 0;
        if (appsOpen) closeApps();
        else focusRows();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openCustomize();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (appsOpen) closeApps();
        // On the clock screen Back goes nowhere: this is home.
    }

    // ---- scene, clock and weather -----------------------------------------------------------------

    @Override
    public void onSceneChanged() {
        SceneView.Phase p = scene.phase();
        int glassTint = p == SceneView.Phase.DAWN ? 0x571E1A3C
                : p == SceneView.Phase.DAY ? 0x6B0E222E
                : p == SceneView.Phase.DUSK ? 0x5C1E1028 : 0x6B0A0E1E;
        GlassPanel.setSource(scene.blurred(), glassTint);
        appsPanel.invalidate();
        inputsPanel.invalidate();
        int text = scene.wantsDarkText() ? 0xFF1A2A37
                : p == SceneView.Phase.DAY ? 0xFFF4F7FA
                : p == SceneView.Phase.DAWN ? 0xFFFFF8F0
                : p == SceneView.Phase.DUSK ? 0xFFFFF4EA : 0xFFEEF0F8;
        clock.setTextColor(text);
        date.setTextColor(text);
        weather.setTextColor((text & 0x00FFFFFF) | 0xD9000000);
        // Light type gets a soft shadow so it holds on clouds; dark type on a day sky needs none.
        float blur = scene.wantsDarkText() ? 0f : 12f;
        int shadow = scene.wantsDarkText() ? 0 : 0x59000000;
        clock.setShadowLayer(blur, 0f, 2f, shadow);
        date.setShadowLayer(blur * 0.6f, 0f, 1f, shadow);
        weather.setShadowLayer(blur * 0.6f, 0f, 1f, shadow);
    }

    private void tick() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        String hm = new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", locale).format(now);
        clock.setText(hm);
        headerTime.setText(hm);
        date.setText(new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now));
        scene.update(forcedPhase != null ? forcedPhase : phaseAt(now.getTime()),
                forcedSeason != null ? forcedSeason : Season.at(now.getTime()),
                forcedFx != null ? forcedFx : Weather.fx(this, snowAtOrBelow));
    }

    /**
     * Dawn and dusk straddle the real sunrise and sunset for the configured place, or 07:00 and
     * 19:00 when no place is set.
     */
    private SceneView.Phase phaseAt(long now) {
        long rise, set;
        if (located) {
            long[] sun = Sun.riseSet(now, lat, lon);
            rise = sun[0];
            set = sun[1];
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, 7);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            rise = c.getTimeInMillis();
            set = rise + 12 * 60 * MINUTE_MS;
        }
        if (now >= rise - 40 * MINUTE_MS && now < rise + 50 * MINUTE_MS) return SceneView.Phase.DAWN;
        if (now >= rise + 50 * MINUTE_MS && now < set - 50 * MINUTE_MS) return SceneView.Phase.DAY;
        if (now >= set - 50 * MINUTE_MS && now < set + 40 * MINUTE_MS) return SceneView.Phase.DUSK;
        return SceneView.Phase.NIGHT;
    }

    private void showWeather(String text) {
        weather.setText(weatherLine(text));
        tick();
    }

    private String weatherLine(String reading) {
        if (!located || reading.isEmpty()) return place;
        return place.isEmpty() ? reading : place + " · " + reading;
    }

    private void shiftClockLayer() {
        shiftStep = (shiftStep + 1) % SHIFT_DP.length;
        homeLayer.animate()
                .translationX(SHIFT_DP[shiftStep][0] * density)
                .translationY(SHIFT_DP[shiftStep][1] * density)
                .setDuration(2000).start();
    }

    // ---- tiles ------------------------------------------------------------------------------------

    private void loadApps() {
        // Read before the query, so a package that changes during the build triggers another one.
        ChangedPackages changed = getPackageManager().getChangedPackages(Math.max(packageSequence, 0));
        if (changed != null) packageSequence = changed.getSequenceNumber();
        else if (packageSequence < 0) packageSequence = 0;
        // The dock and the first grid row are on screen at once: their banners are decoded now, the rest
        // on a background thread once the tiles are up.
        List<Apps.App> apps = Apps.load(this, dockApps, pinnedApps, hiddenApps, dockApps.size() + GRID_COLUMNS);
        allApps = apps;
        grid.removeAllViews();
        gridTiles.clear();
        List<View> appTiles = new ArrayList<>();
        for (Apps.App a : apps) appTiles.add(appTile(a));
        addRow(appTiles);
        buildRows();

        // The deferred banners are decoded once the tiles are up, off the main thread: the panel opens
        // without decoding on its first frame, and the tiles are ready before anyone scrolls to them.
        grid.post(() -> {
            List<Apps.LazyBanner> pending = new ArrayList<>();
            for (Apps.App a : apps) {
                if (a.banner instanceof Apps.LazyBanner) pending.add((Apps.LazyBanner) a.banner);
            }
            if (pending.isEmpty()) return;
            Thread t = new Thread(() -> {
                for (Apps.LazyBanner b : pending) b.resolve();
                grid.postInvalidate();
            }, "cosy-banners");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        });
    }

    private View appTile(Apps.App a) {
        Tile t = new Tile(this, a, 1f);
        t.setOnTileFocused(tile -> focusedName.setText(tile.app.label));
        return t;
    }

    /** The rows on the clock screen, in the order chosen in Personalizza. Empty rows are left out. */
    private void buildRows() {
        rowsStale = false;
        dock.removeAllViews();
        rowTiles.clear();
        rowLabels.clear();
        int contentDp = 0, firstTwoDp = 0, shown = 0;
        for (String id : rows) {
            List<View> tiles = new ArrayList<>();
            int label;
            if (ROW_FAVORITES.equals(id)) {
                label = R.string.row_favorites;
                for (String pkg : dockApps) {
                    for (Apps.App a : allApps) if (a.pkg.equals(pkg)) tiles.add(homeAppTile(a));
                }
            } else if (ROW_CHANNELS.equals(id)) {
                label = R.string.row_channels;
                for (Channels.Channel ch : channels) tiles.add(channelTile(ch));
            } else if (ROW_DEVICES.equals(id)) {
                label = R.string.row_devices;
                tiles.addAll(deviceTiles());
            } else if (ROW_RECENT.equals(id)) {
                label = R.string.row_recent;
                for (WatchNext.Item item : recent) tiles.add(recentTile(item));
                for (String pkg : recentApps) {
                    for (Apps.App a : allApps) if (a.pkg.equals(pkg)) tiles.add(homeAppTile(a));
                }
            } else {
                continue;
            }
            if (tiles.isEmpty()) continue;
            // Channels and HDMI are smaller; HDMI keeps the width of one column instead of stretching.
            boolean small = ROW_CHANNELS.equals(id) || ROW_DEVICES.equals(id);
            addHomeRow(label, tiles, small ? SMALL_TILE_DP : LARGE_TILE_DP, ROW_DEVICES.equals(id) ? GRID_COLUMNS : tiles.size());
            int rowDp = (small ? SMALL_TILE_DP : LARGE_TILE_DP) + ROW_EXTRA_DP;
            contentDp += rowDp;
            if (shown++ < 2) firstTwoDp += rowDp;
        }
        addActionChips();
        // As many rows as fit under the clock; the others are reached by scrolling. Keep the clock and
        // the landscape clear of them.
        int n = rowTiles.size();
        // Up to two rows and the chips under them; with more, exactly two rows, the rest a scroll away.
        int windowDp = Math.min(shown <= 2 ? contentDp + 64 : firstTwoDp + 12, MAX_ROWS_WINDOW_DP);
        ViewGroup.LayoutParams slp = dockScroll.getLayoutParams();
        slp.height = dp(windowDp);
        dockScroll.setLayoutParams(slp);
        clock.setTextSize(n <= 1 ? 132f : 100f);
        date.setTextSize(n <= 1 ? 22f : 19f);
        weather.setTextSize(n <= 1 ? 16f : 15f);
        // Just enough to keep the farmhouse on its ridge above the rows; the rest of the hills go under them.
        scene.setLift(Math.max(0f, 2f * windowDp - 360f));
    }

    /** Under the rows: everything else, and the way to change the rows themselves. */
    private void addActionChips() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setClipChildren(false);
        actionChips.clear();
        int[] labels = {R.string.all_apps_hint, R.string.customize};
        Runnable[] actions = {this::openApps, this::openCustomize};
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(this, null, 0, R.style.Chip);
            chip.setText(labels[i]);
            final Runnable action = actions[i];
            final int index = i;
            chip.setOnClickListener(v -> action.run());
            chip.setOnFocusChangeListener((v, focused) -> {
                if (focused) snapToRow(rowTiles.size());
            });
            chip.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        focusTile(rowTiles.size() - 1, lastCol);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return index == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return index == labels.length - 1;
                    default:
                        return false;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            if (i > 0) lp.setMarginStart(dp(10));
            bar.addView(chip, lp);
            actionChips.add(chip);
        }
        dock.addView(bar);
    }

    private View recentTile(WatchNext.Item item) {
        PosterTile t = new PosterTile(this, item, DOCK_REST_ALPHA, () -> openRecent(item));
        View.OnFocusChangeListener inner = t.getOnFocusChangeListener();
        t.setOnFocusChangeListener((v, focused) -> {
            inner.onFocusChange(v, focused);
            if (focused) stopPreview();
        });
        return t;
    }

    /** Straight to the title when the app gave a link, otherwise just the app. */
    private void openRecent(WatchNext.Item item) {
        Intent link = WatchNext.intent(item);
        if (link != null) {
            try {
                startActivity(link);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "recent link: " + e);
            }
        }
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
        if (app != null) open(app);
    }

    /** Off the main thread; redraws the rows only when the list actually changed. */
    private void refreshRecent() {
        Set<String> launchable = new HashSet<>();
        for (Apps.App a : allApps) launchable.add(a.pkg);
        Context app = getApplicationContext();
        new Thread(() -> {
            List<WatchNext.Item> local = WatchNext.latest(app, launchable, GRID_COLUMNS);
            // Straight away with what the other TVs said last time, then again once the NAS answers.
            showRecent(Sync.merge(local, Sync.cached(), launchable, GRID_COLUMNS), launchable, app);
            if (Sync.enabled(app)) {
                showRecent(Sync.merge(local, Sync.exchange(app, local), launchable, GRID_COLUMNS), launchable, app);
            }
        }, "home-recent").start();
    }

    /**
     * Off the main thread: the titles, then the apps used lately that have no title among them, to
     * fill the row. Redraws the rows only when what they show changed.
     */
    private void showRecent(List<WatchNext.Item> items, Set<String> launchable, Context app) {
        Set<String> withTitle = new HashSet<>();
        for (WatchNext.Item i : items) withTitle.add(i.pkg);
        List<String> apps = WatchNext.recentApps(app, launchable, withTitle, GRID_COLUMNS - items.size());
        StringBuilder key = new StringBuilder();
        for (WatchNext.Item i : items) {
            key.append(i.pkg).append(i.title).append(i.detail).append(i.from).append(Math.round(i.progress * 20)).append(';');
        }
        key.append('/').append(String.join(",", apps));
        handler.post(() -> {
            if (key.toString().equals(recentKey)) return;
            recentKey = key.toString();
            recent = items;
            recentApps = apps;
            if (resumed && !appsOpen) {
                buildRows();
                focusRows();
            }
        });
    }

    private TextView sectionLabel(int text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(0xF2FFFFFF);
        h.setShadowLayer(6f, 0f, 1f, 0x80000000);
        h.setTextSize(13f);
        h.setLetterSpacing(0.02f);
        h.setTypeface(getResources().getFont(R.font.outfit));
        h.setFontVariationSettings("'wght' 500");
        return h;
    }

    private void addHomeRow(int label, List<View> tiles, int tileDp, int columns) {
        TextView h = sectionLabel(label);
        rowLabels.add(h);
        dock.addView(h, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(columns);
        row.setClipChildren(false);
        final int r = rowTiles.size();
        for (int i = 0; i < tiles.size(); i++) {
            final int c = i;
            View t = tiles.get(i);
            // Every row fills the width of the screen, however many tiles it has.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(tileDp), 1f);
            if (i > 0) lp.setMarginStart(dp(14));
            final boolean last = i == tiles.size() - 1;
            View.OnFocusChangeListener inner = t.getOnFocusChangeListener();
            t.setOnFocusChangeListener((v, focused) -> {
                if (inner != null) inner.onFocusChange(v, focused);
                if (focused) {
                    lastRow = r;
                    snapToRow(r);
                    lastCol = c;
                }
            });
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (r < rowTiles.size() - 1) focusTile(r + 1, c);
                        else if (!actionChips.isEmpty()) actionChips.get(0).requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (r > 0) focusTile(r - 1, c);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return c == 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
            row.addView(t, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        rlp.bottomMargin = dp(10);
        dock.addView(row, rlp);
        rowTiles.add(tiles);
    }

    /**
     * Scrolls whole rows: the focused row at the bottom of the window with the one above it, or the
     * top of the list for the first row. {@code r} past the last row brings in the chips.
     */
    private void snapToRow(int r) {
        if (rowLabels.isEmpty() || r <= 0) {
            dockScroll.smoothScrollTo(0, 0);
            return;
        }
        View top = rowLabels.get(Math.min(r - 1, rowLabels.size() - 1));
        dockScroll.smoothScrollTo(0, Math.max(0, top.getTop() - dock.getPaddingTop()));
    }

    private void focusTile(int r, int c) {
        if (rowTiles.isEmpty()) return;
        List<View> row = rowTiles.get(Math.max(0, Math.min(r, rowTiles.size() - 1)));
        row.get(Math.max(0, Math.min(c, row.size() - 1))).requestFocus();
    }

    private void focusRows() {
        focusTile(lastRow, lastCol);
    }

    private View homeAppTile(Apps.App a) {
        Tile t = new Tile(this, a, DOCK_REST_ALPHA);
        t.setOnTileFocused(tile -> stopPreview());
        return t;
    }

    private View channelTile(Channels.Channel ch) {
        Uri uri = TvContract.buildChannelUri(ch.id);
        TextTile t = new TextTile(this, ch.number, ch.name, DOCK_REST_ALPHA, () -> {
            stopPreview();
            open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
        });
        t.setOnTileFocused(tile -> schedulePreview(ch, uri));
        return t;
    }

    // ---- the channel preview ------------------------------------------------------------------------

    /** Waits for the focus to settle: a tuner takes a moment, and only one channel can play at a time. */
    private void schedulePreview(Channels.Channel ch, Uri uri) {
        if (pendingPreview[0] != null) handler.removeCallbacks(pendingPreview[0]);
        pendingPreview[0] = () -> startPreview(ch, uri);
        handler.postDelayed(pendingPreview[0], 900);
    }

    private void startPreview(Channels.Channel ch, Uri uri) {
        String input = tunerInput();
        if (input == null) {
            Log.w(TAG, "preview: no tuner input");
            return;
        }
        if (previewView == null) {
            previewView = new TvView(this);
            previewView.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onConnectionFailed(String inputId) {
                    Log.w(TAG, "preview: connection failed " + inputId);
                    stopPreview();
                }

                @Override
                public void onDisconnected(String inputId) {
                    Log.w(TAG, "preview: disconnected " + inputId);
                    stopPreview();
                }

                @Override
                public void onVideoAvailable(String inputId) {
                    Log.i(TAG, "preview: video available");
                }

                @Override
                public void onVideoUnavailable(String inputId, int reason) {
                    Log.i(TAG, "preview: video unavailable, reason " + reason);
                }
            });
            previewBox.addView(previewView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        previewLabel.setText(ch.name);
        previewBox.setVisibility(View.VISIBLE);
        previewView.setStreamVolume(0f);
        Log.i(TAG, "preview: tune " + input + " " + uri);
        previewView.tune(input, uri);
    }

    private void stopPreview() {
        if (pendingPreview[0] != null) handler.removeCallbacks(pendingPreview[0]);
        if (previewView != null) previewView.reset();
        previewBox.setVisibility(View.GONE);
    }

    /** The digital tuner: HW0 on these boards, otherwise the first tuner the TV lists. */
    private String tunerInput() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return null;
        String first = null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_TUNER || !info.getId().contains("com.mediatek.tvinput")) continue;
            if (info.getId().endsWith("/HW0")) return info.getId();
            if (first == null) first = info.getId();
        }
        return first;
    }

    // ---- Personalizza --------------------------------------------------------------------------------

    private AlertDialog.Builder dialog(int title) {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title);
    }

    private void openCustomize() {
        String[] items = {getString(R.string.customize_favorites), getString(R.string.customize_favorites_order),
                getString(R.string.customize_order), getString(R.string.customize_rows),
                getString(R.string.customize_hdmi)};
        dialog(R.string.customize).setItems(items, (d, which) -> {
            if (which == 0) chooseFavorites();
            else if (which == 1) orderFavorites();
            else if (which == 2) orderRows();
            else if (which == 3) chooseRows();
            else chooseInputToName();
        }).show();
    }

    private String rowName(String id) {
        return getString(ROW_FAVORITES.equals(id) ? R.string.row_favorites
                : ROW_CHANNELS.equals(id) ? R.string.row_channels
                : ROW_RECENT.equals(id) ? R.string.row_recent : R.string.row_devices);
    }

    private void saveRows() {
        prefs.edit().putString("rows", String.join(",", rows)).apply();
        lastRow = 0;
        lastCol = 0;
        buildRows();
        if (!appsOpen) focusRows();
    }

    /** Every tick saves at once: on a long list the remote may never reach a button at the bottom. */
    private void chooseRows() {
        String[] all = {ROW_FAVORITES, ROW_CHANNELS, ROW_DEVICES, ROW_RECENT};
        String[] labels = new String[all.length];
        boolean[] on = new boolean[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = rowName(all[i]);
            on[i] = rows.contains(all[i]);
        }
        dialog(R.string.customize_rows)
                .setMultiChoiceItems(labels, on, (d, i, checked) -> {
                    if (checked) {
                        if (!rows.contains(all[i])) rows.add(all[i]);
                    } else {
                        rows.remove(all[i]);
                    }
                    saveRows();
                })
                .setPositiveButton(R.string.done, null).show();
    }

    /** Pressing a row moves it one place up. */
    private void orderRows() {
        String[] labels = new String[rows.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (i + 1) + ".  " + rowName(rows.get(i));
        dialog(R.string.order_hint).setItems(labels, (d, i) -> {
            if (i > 0) rows.add(i - 1, rows.remove(i));
            saveRows();
            orderRows();
        }).setPositiveButton(android.R.string.ok, null).show();
    }

    /** Pressing an app moves it one place up, the same as the rows. */
    private void orderFavorites() {
        List<String> names = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (String pkg : dockApps) {
            for (Apps.App a : allApps) {
                if (a.pkg.equals(pkg)) {
                    names.add((pkgs.size() + 1) + ".  " + a.label);
                    pkgs.add(pkg);
                }
            }
        }
        dialog(R.string.order_hint).setItems(names.toArray(new String[0]), (d, i) -> {
            if (i > 0) pkgs.add(i - 1, pkgs.remove(i));
            // Apps that are hidden or not installed keep their place at the end, for when they return.
            List<String> next = new ArrayList<>(pkgs);
            for (String pkg : dockApps) if (!next.contains(pkg)) next.add(pkg);
            dockApps = next;
            prefs.edit().putString("dock", String.join(",", next)).apply();
            buildRows();
            orderFavorites();
        }).setPositiveButton(android.R.string.ok, null).show();
    }

    /** Whether a package is a tile: installed, launchable and not hidden. */
    private boolean isShown(String pkg) {
        for (Apps.App a : allApps) if (a.pkg.equals(pkg)) return true;
        return false;
    }

    /** The same: a tick saves at once and the row behind the list changes straight away. */
    private void chooseFavorites() {
        String[] labels = new String[allApps.size()];
        boolean[] on = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = allApps.get(i).label;
            on[i] = dockApps.contains(allApps.get(i).pkg);
        }
        dialog(R.string.favorites_title)
                .setMultiChoiceItems(labels, on, (d, i, checked) -> {
                    String pkg = allApps.get(i).pkg;
                    List<String> next = new ArrayList<>(dockApps);
                    // Only the favourites on screen count: hidden or missing apps can't be unticked here.
                    int shown = 0;
                    for (String p : next) if (isShown(p)) shown++;
                    if (checked && shown >= GRID_COLUMNS) {
                        ((AlertDialog) d).getListView().setItemChecked(i, false);
                        Toast.makeText(this, R.string.favorites_limit, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (checked) next.add(pkg);
                    else next.remove(pkg);
                    dockApps = next;
                    prefs.edit().putString("dock", String.join(",", next)).apply();
                    buildRows();
                })
                .setPositiveButton(R.string.done, null).show();
    }

    private void chooseInputToName() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return;
        List<TvInputInfo> hdmi = hdmiInputs(tim);
        String[] labels = new String[hdmi.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = inputLabel(hdmi.get(i), i);
        dialog(R.string.customize_hdmi).setItems(labels, (d, i) -> nameInput(hdmi.get(i), labels[i])).show();
    }

    private void nameInput(TvInputInfo info, String current) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(inputNames.containsKey(portKey(info.getId())) ? current : "");
        edit.setHint(R.string.hdmi_name_hint);
        String key = portKey(info.getId());
        dialog(R.string.customize_hdmi).setView(edit)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        inputNames.remove(key);
                        prefs.edit().remove("input_" + key).apply();
                    } else {
                        inputNames.put(key, name);
                        prefs.edit().putString("input_" + key, name).apply();
                    }
                    buildInputs();
                    buildRows();
                    restoreFocus();
                    chooseIcon(key);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseIcon(String key) {
        String[] labels = new String[DEVICE_ICON_NAMES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(DEVICE_ICON_NAMES[i]);
        dialog(R.string.choose_icon).setItems(labels, (d, i) -> {
            prefs.edit().putInt("icon_" + key, i).apply();
            buildRows();
            restoreFocus();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String inputLabel(TvInputInfo info, int index) {
        String given = inputNames.get(portKey(info.getId()));
        if (given != null) return given;
        CharSequence l = info.loadLabel(this);
        String label = l == null || l.length() == 0 ? "HDMI " + (index + 1) : l.toString();
        return label.toUpperCase(Locale.ROOT).contains("HDMI") ? label : "HDMI " + label;
    }

    /** Adds tiles from the first column; the ends of each row hold focus instead of wrapping. */
    private void addRow(List<View> tiles) {
        int cellW = dp(120), cellH = dp(68), colGap = dp(16), rowGap = dp(14);
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            final int col = i % GRID_COLUMNS, index = gridTiles.size();
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(col));
            lp.width = cellW;
            lp.height = cellH;
            lp.setMargins(0, 0, col == GRID_COLUMNS - 1 ? 0 : colGap, rowGap);
            final boolean lastInRow = col == GRID_COLUMNS - 1 || i == tiles.size() - 1;
            t.setOnFocusChangeListener(chain(t.getOnFocusChangeListener(), index));
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && col == 0)
                        || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && lastInRow);
            });
            grid.addView(t, lp);
            gridTiles.add(t);
        }
    }

    private View.OnFocusChangeListener chain(View.OnFocusChangeListener inner, int index) {
        return (v, focused) -> {
            if (inner != null) inner.onFocusChange(v, focused);
            if (focused) lastGrid = index;
        };
    }

    /** Every HDMI port, under the name you gave it. */
    private List<View> deviceTiles() {
        List<View> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> hdmi = hdmiInputs(tim);
        for (int i = 0; i < hdmi.size(); i++) {
            TvInputInfo info = hdmi.get(i);
            final String id = info.getId();
            String name = inputLabel(info, i);
            TextTile t = new TextTile(this, name, deviceIcon(portKey(id), name), DOCK_REST_ALPHA, () -> openInput(id));
            t.setOnTileFocused(tile -> stopPreview());
            out.add(t);
        }
        return out;
    }

    /** The icon you picked, or one guessed from the name. */
    private int deviceIcon(String key, String name) {
        int chosen = prefs.getInt("icon_" + key, -1);
        if (chosen >= 0 && chosen < DEVICE_ICONS.length) return DEVICE_ICONS[chosen];
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches(".*(wii|playstation|ps[345]|xbox|switch|nintendo|console|gioc).*")) return R.drawable.ic_dev_console;
        if (n.matches(".*(decoder|sky|tim|box|dazn|satellit).*")) return R.drawable.ic_dev_decoder;
        if (n.matches(".*(sonos|soundbar|audio|casse|ampli|home cinema).*")) return R.drawable.ic_dev_soundbar;
        if (n.matches(".*(blu.?ray|dvd|lettore|disco).*")) return R.drawable.ic_dev_disc;
        if (n.matches(".*(pc|computer|mac|laptop|notebook).*")) return R.drawable.ic_dev_pc;
        if (n.matches(".*(chromecast|fire|stick|chiavetta|roku|apple tv|shield).*")) return R.drawable.ic_dev_stick;
        return R.drawable.ic_dev_hdmi;
    }

    private static List<TvInputInfo> hdmiInputs(TvInputManager tim) {
        List<TvInputInfo> hdmi = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
        }
        Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));
        return hdmi;
    }

    /** "HW5" from an input id ending in /HW5: the same on every TV of a board. */
    private static String portKey(String inputId) {
        return inputId.substring(inputId.lastIndexOf('/') + 1);
    }

    private void focusGrid(int index) {
        if (gridTiles.isEmpty()) {
            findViewById(R.id.back).requestFocus();
            return;
        }
        gridTiles.get(Math.max(0, Math.min(index, gridTiles.size() - 1))).requestFocus();
    }

    private void restoreFocus() {
        if (appsOpen) focusGrid(lastGrid);
        else focusRows();
    }

    // ---- the apps panel ---------------------------------------------------------------------------

    private void openApps() {
        if (appsOpen) return;
        appsOpen = true;
        appsLayer.setAlpha(0f);
        appsLayer.setTranslationY(dp(16));
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.animate().alpha(1f).translationY(0f).setDuration(220).start();
        homeLayer.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> homeLayer.setVisibility(View.INVISIBLE)).start();
        stopPreview();
        lastGrid = 0;
        findViewById(R.id.appsScroll).scrollTo(0, 0);
        focusGrid(lastGrid);
    }

    private void closeApps() {
        if (!appsOpen) return;
        appsOpen = false;
        homeLayer.setVisibility(View.VISIBLE);
        homeLayer.animate().alpha(1f).setDuration(220).start();
        appsLayer.animate().alpha(0f).translationY(dp(16)).setDuration(160)
                .withEndAction(() -> appsLayer.setVisibility(View.GONE)).start();
        focusRows();
    }

    // ---- inputs -----------------------------------------------------------------------------------

    /** What the input chips were last built from, so a resume with the same inputs keeps them. */
    private List<String> inputsKey = Collections.emptyList();

    /**
     * Live TV, the HDMI ports in order, and USB media. Checked on every resume and rebuilt only
     * when they change: each chip costs about 4 ms to create.
     */
    private void buildInputs() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<String> key = new ArrayList<>();

        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            labels.add(getString(R.string.live_tv));
            actions.add(() -> open(liveTv));
            key.add(String.valueOf(liveTv.getComponent()));
        }

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> hdmi = hdmiInputs(tim);
            for (int i = 0; i < hdmi.size(); i++) {
                TvInputInfo info = hdmi.get(i);
                final String id = info.getId();
                String label = inputLabel(info, i);
                labels.add(label);
                actions.add(() -> openInput(id));
                key.add(id);
            }
        }

        Intent usb = getPackageManager().getLeanbackLaunchIntentForPackage(usbPkg);
        if (usb != null) {
            labels.add(getString(R.string.usb));
            actions.add(() -> open(usb));
            key.add(String.valueOf(usb.getComponent()));
        }

        key.addAll(labels);
        if (key.equals(inputsKey)) return;
        inputsKey = key;

        while (inputs.getChildCount() > 1) inputs.removeViewAt(1);
        for (int i = 0; i < labels.size(); i++) addInputChip(labels.get(i), actions.get(i));

        // Bottom of the screen: Down stays put.
        for (int i = 1; i < inputs.getChildCount(); i++) {
            inputs.getChildAt(i).setOnKeyListener((v, keyCode, e) ->
                    e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
        }
    }

    private void addInputChip(String label, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setText(label);
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMarginStart(dp(8));
        inputs.addView(chip, lp);
    }

    /** The standard way to switch to an HDMI port: VIEW its passthrough channel, which Live TV handles. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> list(int resId) {
        return split(getString(resId));
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}

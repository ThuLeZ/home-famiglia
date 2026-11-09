package com.cosyhome.tv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.robolectric.Shadows.shadowOf;

/**
 * Not a test of behaviour: draws the Home as a 1080p TV would and saves it as PNG, so the look can be
 * checked on a computer. Run with ./gradlew :app:testDebugUnitTest, then open app/build/previews.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "it-rIT-w960dp-h540dp-land-xhdpi")
public class PreviewTest {

    private static final String[][] SCENES = {
            {"spring", "day", "clear"}, {"summer", "dusk", "clear"}, {"autumn", "dawn", "rain"},
            {"winter", "night", "snow"}, {"christmas", "night", "snow"}, {"christmas", "day", "clear"},
            {"halloween", "night", "clear"},
    };

    @Test
    public void home() throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ITALY);
        PreviewData.install(org.robolectric.RuntimeEnvironment.getApplication());
        for (String[] s : SCENES) {
            Intent i = new Intent().putExtra("season", s[0]).putExtra("phase", s[1]).putExtra("fx", s[2]);
            ActivityController<HomeActivity> c = start(i);
            save(c.get().getWindow().getDecorView(), "home-" + s[0] + "-" + s[1] + "-" + s[2]);
            c.pause().stop().destroy();
        }
    }

    /** The channel row focused, scrolled into view. */
    @Test
    public void channels() throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ITALY);
        PreviewData.install(org.robolectric.RuntimeEnvironment.getApplication());
        Intent i = new Intent().putExtra("season", "summer").putExtra("phase", "dusk").putExtra("fx", "clear");
        ActivityController<HomeActivity> c = start(i);
        java.lang.reflect.Method focus = HomeActivity.class.getDeclaredMethod("focusTile", int.class, int.class);
        focus.setAccessible(true);
        focus.invoke(c.get(), 2, 1);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        save(c.get().getWindow().getDecorView(), "home-channels-focused");
        c.pause().stop().destroy();
    }

    /** The landscape alone, without the rows over it. */
    @Test
    public void scenes() throws IOException {
        for (String[] s : SCENES) {
            SceneView v = new SceneView(org.robolectric.RuntimeEnvironment.getApplication(), null);
            v.update(SceneView.Phase.valueOf(s[1].toUpperCase(java.util.Locale.ROOT)),
                    Season.valueOf(s[0].toUpperCase(java.util.Locale.ROOT)),
                    Season.Fx.valueOf(s[2].toUpperCase(java.util.Locale.ROOT)));
            save(v, "scene-" + s[0] + "-" + s[1] + "-" + s[2]);
        }
    }

    @Test
    public void apps() throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ITALY);
        PreviewData.install(org.robolectric.RuntimeEnvironment.getApplication());
        Intent i = new Intent().putExtra("season", "autumn").putExtra("phase", "dusk").putExtra("fx", "clear");
        ActivityController<HomeActivity> c = start(i);
        java.lang.reflect.Method open = HomeActivity.class.getDeclaredMethod("openApps");
        open.setAccessible(true);
        open.invoke(c.get());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        save(c.get().getWindow().getDecorView(), "apps-autumn-dusk");
        c.pause().stop().destroy();
    }

    /** A TV has no touch screen: out of touch mode, focus and its ring show as they do there. */
    private static ActivityController<HomeActivity> start(Intent i) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        ActivityController<HomeActivity> c = Robolectric.buildActivity(HomeActivity.class, i).setup();
        c.get().getWindow().getDecorView().measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        c.get().getWindow().getDecorView().layout(0, 0, 1920, 1080);
        shadowOf(Looper.getMainLooper()).idle();
        return c;
    }

    private static void save(View root, String name) throws IOException {
        int w = 1920, h = 1080;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        shadowOf(Looper.getMainLooper()).idle();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File dir = new File(System.getProperty("previews.dir", "build/previews"));
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}

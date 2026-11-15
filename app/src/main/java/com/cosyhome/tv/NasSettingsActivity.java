package com.cosyhome.tv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Condivisione tra le TV: the NAS folder where the TVs of the house share "Ultimi contenuti".
 * Opened from Personalizza. Nothing changes until Salva; Prova connessione tries the fields as typed.
 */
public class NasSettingsActivity extends Activity {

    private static final int OK = 0xFF5BC98A, FAILED = 0xFFE8A646, IDLE = 0xFF8A94A0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText host, user, password, dir, tvName;
    private TextView on, status, detail;
    private View dot;
    private boolean enabled;
    private boolean testing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nas);
        host = findViewById(R.id.nasHost);
        user = findViewById(R.id.nasUser);
        password = findViewById(R.id.nasPassword);
        dir = findViewById(R.id.nasDir);
        tvName = findViewById(R.id.nasTvName);
        on = findViewById(R.id.nasOn);
        status = findViewById(R.id.nasStatus);
        detail = findViewById(R.id.nasStatusDetail);
        dot = findViewById(R.id.nasDot);

        Sync.Config c = Sync.config(this);
        enabled = c.on;
        host.setText(c.host);
        user.setText(c.user);
        password.setText(c.password);
        dir.setText(c.dir);
        tvName.setText(c.tvName);
        tvName.setHint(Sync.deviceName(this));

        on.setOnClickListener(v -> {
            enabled = !enabled;
            showToggle();
        });
        findViewById(R.id.nasTest).setOnClickListener(v -> test());
        findViewById(R.id.nasSave).setOnClickListener(v -> save());
        findViewById(R.id.nasCancel).setOnClickListener(v -> finish());
        showToggle();
        showStatus();
        on.requestFocus();
    }

    private Sync.Config typed() {
        return new Sync.Config(enabled, host.getText().toString(), user.getText().toString(),
                password.getText().toString(), dir.getText().toString(), tvName.getText().toString());
    }

    private void showToggle() {
        on.setText(enabled ? R.string.nas_on : R.string.nas_off);
        on.setCompoundDrawablesRelativeWithIntrinsicBounds(enabled ? R.drawable.ic_check : R.drawable.ic_circle, 0, 0, 0);
        float fields = enabled ? 1f : 0.45f;
        for (View v : new View[]{host, user, password, dir, tvName}) v.setAlpha(fields);
    }

    /** The last visit to the NAS, as the Home made it. */
    private void showStatus() {
        Sync.Config c = Sync.config(this);
        Sync.Status s = Sync.status(this);
        if (!c.usable()) {
            show(IDLE, getString(R.string.nas_status_off), "");
        } else if (s.at == 0) {
            show(IDLE, getString(R.string.nas_status_never), getString(R.string.nas_status_never_detail));
        } else if (s.error == null) {
            show(OK, getString(R.string.nas_status_ok, time(s.at)), others(s.tvs));
        } else {
            show(FAILED, getString(R.string.nas_status_failed, time(s.at)), s.error);
        }
    }

    private void test() {
        if (testing) return;
        Sync.Config c = typed();
        if (c.host.isEmpty()) {
            show(FAILED, getString(R.string.nas_need_host), "");
            host.requestFocus();
            return;
        }
        testing = true;
        show(IDLE, getString(R.string.nas_testing), "");
        new Thread(() -> {
            List<String> tvs = new ArrayList<>();
            String error = Sync.test(getApplicationContext(), c, tvs);
            handler.post(() -> {
                testing = false;
                if (isFinishing()) return;
                if (error == null) show(OK, getString(R.string.nas_test_ok), others(tvs));
                else show(FAILED, error, "");
            });
        }, "nas-test").start();
    }

    private void save() {
        Sync.save(this, typed());
        Toast.makeText(this, R.string.nas_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private String others(List<String> tvs) {
        return tvs.isEmpty() ? getString(R.string.nas_test_ok_none)
                : getString(R.string.nas_test_ok_tvs, String.join(", ", tvs));
    }

    private String time(long at) {
        return DateFormat.getTimeFormat(this).format(at);
    }

    private void show(int color, String line, String more) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        status.setText(line);
        detail.setText(more);
        detail.setVisibility(more.isEmpty() ? View.GONE : View.VISIBLE);
    }
}

package com.fallpoint.pocketsculpt;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private SculptSurfaceView sculptView;
    private Button addButton;
    private Button subtractButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(13, 15, 18));
        getWindow().setNavigationBarColor(Color.rgb(13, 15, 18));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(19, 22, 26));

        sculptView = new SculptSurfaceView(this);
        root.addView(sculptView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(buildTopBar(), topBarParams());
        root.addView(buildBottomPanel(), bottomPanelParams());

        setContentView(root);
        selectMode(BrushMode.ADD);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundResource(R.drawable.panel_bg);

        TextView title = new TextView(this);
        title.setText("CABrush AVS 0.1.1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button reset = actionButton("Reset", v -> sculptView.resetMesh());
        bar.addView(reset);
        return bar;
    }

    private View buildBottomPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(8));
        panel.setBackgroundResource(R.drawable.panel_bg);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);

        addButton = toolButton("Clay +", v -> selectMode(BrushMode.ADD));
        subtractButton = toolButton("Clay -", v -> selectMode(BrushMode.SUBTRACT));

        tools.addView(addButton, toolParams());
        tools.addView(spacer(8));
        tools.addView(subtractButton, toolParams());
        panel.addView(tools);

        panel.addView(sliderRow(
                "Size", 1, 100, 38,
                value -> sculptView.setBrushRadius(0.05f + value * 0.0055f)
        ));

        panel.addView(sliderRow(
                "Strength", 1, 100, 30,
                value -> sculptView.setBrushStrength(0.001f + value * 0.00045f)
        ));

        TextView help = new TextView(this);
        help.setText("AVS volume clay  •  2 fingers orbit / pinch zoom");
        help.setTextColor(Color.rgb(185, 192, 199));
        help.setTextSize(12f);
        help.setGravity(Gravity.CENTER);
        panel.addView(help, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)
        ));

        return panel;
    }

    private LinearLayout sliderRow(String label, int min, int max, int progress, IntChange listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(dp(72), dp(36)));

        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(progress - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                listener.onChange(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        listener.onChange(progress);
        row.addView(slider, new LinearLayout.LayoutParams(0, dp(36), 1f));
        return row;
    }

    private void selectMode(BrushMode mode) {
        sculptView.setBrushMode(mode);
        styleTool(addButton, mode == BrushMode.ADD);
        styleTool(subtractButton, mode == BrushMode.SUBTRACT);
    }

    private void styleTool(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.button_active_bg : R.drawable.button_bg);
        button.setTextColor(active ? Color.rgb(14, 17, 20) : Color.WHITE);
    }

    private Button toolButton(String text, View.OnClickListener listener) {
        Button button = actionButton(text, listener);
        button.setTextSize(13f);
        return button;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackgroundResource(R.drawable.button_bg);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
        ));
        return button;
    }

    private View spacer(int widthDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return view;
    }

    private LinearLayout.LayoutParams toolParams() {
        return new LinearLayout.LayoutParams(0, dp(44), 1f);
    }

    private FrameLayout.LayoutParams topBarParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.TOP
        );
        p.setMargins(dp(10), dp(10), dp(10), 0);
        return p;
    }

    private FrameLayout.LayoutParams bottomPanelParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(174), Gravity.BOTTOM
        );
        p.setMargins(dp(10), 0, dp(10), dp(10));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        sculptView.onResume();
    }

    @Override protected void onPause() {
        sculptView.onPause();
        super.onPause();
    }

    private interface IntChange {
        void onChange(int value);
    }
}

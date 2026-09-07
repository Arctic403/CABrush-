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
    private Button smoothButton;
    private Button symmetryButton;

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
        bar.setBackgroundResource(com.fallpoint.pocketsculpt.R.drawable.panel_bg);

        TextView title = new TextView(this);
        title.setText("PocketSculpt  V1.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, titleParams);

        bar.addView(actionButton("Undo", v -> sculptView.undo()));
        bar.addView(spacer(6));
        bar.addView(actionButton("Redo", v -> sculptView.redo()));
        bar.addView(spacer(6));
        bar.addView(actionButton("Reset", v -> sculptView.resetMesh()));
        return bar;
    }

    private View buildBottomPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackgroundResource(com.fallpoint.pocketsculpt.R.drawable.panel_bg);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);

        addButton = toolButton("Clay +", v -> selectMode(BrushMode.ADD));
        subtractButton = toolButton("Clay -", v -> selectMode(BrushMode.SUBTRACT));
        smoothButton = toolButton("Smooth", v -> selectMode(BrushMode.SMOOTH));
        symmetryButton = toolButton("Sym X", v -> toggleSymmetry());

        tools.addView(addButton, toolParams());
        tools.addView(spacer(6));
        tools.addView(subtractButton, toolParams());
        tools.addView(spacer(6));
        tools.addView(smoothButton, toolParams());
        tools.addView(spacer(6));
        tools.addView(symmetryButton, toolParams());
        panel.addView(tools);

        LinearLayout sizeRow = sliderRow("Size", 10, 100, 38, value -> sculptView.setBrushRadius(0.08f + value * 0.0065f));
        panel.addView(sizeRow);

        LinearLayout strengthRow = sliderRow("Strength", 1, 100, 28, value -> sculptView.setBrushStrength(0.0025f + value * 0.00085f));
        panel.addView(strengthRow);

        TextView help = new TextView(this);
        help.setText("1 finger: sculpt   •   2 fingers: orbit / pinch zoom");
        help.setTextColor(Color.rgb(185, 192, 199));
        help.setTextSize(12f);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, dp(3), 0, 0);
        panel.addView(help, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));

        return panel;
    }

    private LinearLayout sliderRow(String label, int min, int max, int progress, IntChange listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        row.addView(text, new LinearLayout.LayoutParams(dp(66), dp(36)));
        text.setGravity(Gravity.CENTER_VERTICAL);

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
        styleTool(smoothButton, mode == BrushMode.SMOOTH);
    }

    private void toggleSymmetry() {
        boolean enabled = sculptView.toggleSymmetry();
        styleTool(symmetryButton, enabled);
    }

    private void styleTool(Button button, boolean active) {
        if (button == null) return;
        button.setBackgroundResource(active ? R.drawable.button_active_bg : R.drawable.button_bg);
        button.setTextColor(active ? Color.rgb(14, 17, 20) : Color.WHITE);
    }

    private Button toolButton(String text, View.OnClickListener listener) {
        Button button = actionButton(text, listener);
        button.setTextSize(12f);
        button.setAllCaps(false);
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
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackgroundResource(R.drawable.button_bg);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return button;
    }

    private View spacer(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return view;
    }

    private LinearLayout.LayoutParams toolParams() {
        return new LinearLayout.LayoutParams(0, dp(42), 1f);
    }

    private FrameLayout.LayoutParams topBarParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62),
                Gravity.TOP
        );
        p.setMargins(dp(10), dp(10), dp(10), 0);
        return p;
    }

    private FrameLayout.LayoutParams bottomPanelParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190),
                Gravity.BOTTOM
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

    private interface IntChange { void onChange(int value); }
}

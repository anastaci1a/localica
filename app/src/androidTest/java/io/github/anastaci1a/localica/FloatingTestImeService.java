package io.github.anastaci1a.localica;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Real synthetic floating IME for API36 instrumentation only, not a production keyboard.
 * Standalone test-APK process: Android/Java dependencies ONLY, no Kotlin or target APK.
 * No host-insets injection, editor text reads, auto-commits, or app Back dispatch.
 */
public final class FloatingTestImeService extends InputMethodService {
    public static final String TAG = "LocalicaFloatingIme";
    public static final String HIDE_SUFFIX = ".floating.HIDE";
    public static final String SNAPSHOT_SUFFIX = ".floating.SNAPSHOT";
    public static final String CARD_DESCRIPTION = "Localica synthetic floating IME";

    private View root;
    private View card;
    private Button switchButton;
    private boolean switchRequested;
    private boolean receiverRegistered;
    private long inputGeneration;
    private long showGeneration;
    private long drawCount;
    private long lastDrawUptime = -1L;
    private long lastLoggedDrawGeneration = -1L;
    private int lastRequestedTop = -1;
    private String lastInsetsSignature = "";
    private String lastLayoutSignature = "";
    private int logBudget = 256;
    private boolean inputActive;

    // Exported in the test APK for shell control only. Exact -p scope is mandatory.
    // No command extras can mutate editor contents.
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!getPackageName().equals(intent.getPackage())) return;
            String action = intent.getAction();
            if ((getPackageName() + HIDE_SUFFIX).equals(action)) {
                emit("hideRequested");
                requestHideSelf(0);
            } else if ((getPackageName() + SNAPSHOT_SUFFIX).equals(action)) {
                emit("snapshot");
            }
        }
    };

    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(getPackageName() + HIDE_SUFFIX);
        filter.addAction(getPackageName() + SNAPSHOT_SUFFIX);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commands, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(commands, filter);
        }
        receiverRegistered = true;
        emit("created");
    }

    @Override public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        // Deterministic fixture even on emulator hosts with a hardware keyboard.
        return true;
    }

    @Override public void onConfigureWindow(Window win, boolean isFullscreen,
            boolean isCandidatesOnly) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly);
        // Genuine TYPE_INPUT_METHOD window, never an application overlay.
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        hideImeCaptionBar(win);
    }

    @Override public View onCreateInputView() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setContentDescription(CARD_DESCRIPTION);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(28, 69, 84));
        background.setCornerRadius(dp(18));
        panel.setBackground(background);

        TextView heading = new TextView(this);
        heading.setText("Synthetic floating IME");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(16f);
        panel.addView(heading);

        Button insert = new Button(this);
        insert.setText("Insert x");
        insert.setContentDescription("Floating fixture insert x");
        insert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                // Acquire only at explicit click; no cached connection or text reads.
                EditorInfo editor = getCurrentInputEditorInfo();
                String host = editor == null ? null : editor.packageName;
                if ("io.github.anastaci1a.localica".equals(host)
                        || "io.github.anastaci1a.localica.debug".equals(host)) {
                    InputConnection connection = getCurrentInputConnection();
                    boolean committed = connection != null && connection.commitText("x", 1);
                    emit("explicitCommit accepted=" + committed);
                } else {
                    emit("explicitCommit refusedHost");
                }
            }
        });
        panel.addView(insert);

        Button hide = new Button(this);
        hide.setText("Hide IME");
        hide.setContentDescription("Floating fixture hide IME");
        hide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                emit("hideButton");
                requestHideSelf(0);
            }
        });
        panel.addView(hide);

        switchButton = new Button(this);
        switchButton.setText("Switch IME");
        switchButton.setVisibility(switchRequested ? View.VISIBLE : View.GONE);
        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                switchToNextInputMethod(false);
            }
        });
        panel.addView(switchButton);
        card = panel;

        FrameLayout container = new FrameLayout(this) {
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                drawCount++;
                lastDrawUptime = SystemClock.uptimeMillis();
                if (isShown() && panel.isShown() && lastLoggedDrawGeneration != showGeneration) {
                    lastLoggedDrawGeneration = showGeneration;
                    emit("inputViewDrawn");
                }
            }
        };
        // Keep real geometry unchanged from the Kotlin fixture. Framework can
        // replace LayoutParams with WRAP_CONTENT; minimum height still fills its
        // available space, clamped by parent measurement.
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);
        container.setPadding(0, 0, 0, dp(72));
        container.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.max(1, Math.min(dp(280), getMaxWidth() - dp(32))),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        cardParams.rightMargin = dp(16);
        container.addView(panel, cardParams);
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                String signature = left + "," + top + "," + right + "," + bottom
                        + "/" + panel.getWidth() + "," + panel.getHeight();
                if (!signature.equals(lastLayoutSignature)) {
                    lastLayoutSignature = signature;
                    emit("inputViewLayout");
                }
            }
        });
        root = container;
        return container;
    }

    @Override public void onComputeInsets(Insets outInsets) {
        Window nativeWindow = nativeWindow();
        View decor = nativeWindow == null ? null : nativeWindow.getDecorView();
        int height = decor == null ? 0 : decor.getHeight();
        if (height <= 0) {
            super.onComputeInsets(outInsets);
            return;
        }
        // Public geometry contract only. Framework still owns IME visibility.
        outInsets.contentTopInsets = height;
        outInsets.visibleTopInsets = height;
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
        outInsets.touchableRegion.setEmpty();
        View panel = card;
        Rect rect = new Rect();
        if (panel != null && panel.isShown() && panel.getWidth() > 0 && panel.getHeight() > 0) {
            int[] location = new int[2];
            panel.getLocationInWindow(location);
            rect.set(location[0], location[1], location[0] + panel.getWidth(),
                    location[1] + panel.getHeight());
            outInsets.touchableRegion.set(rect);
        }
        lastRequestedTop = height;
        String signature = height + "/" + rect;
        if (!signature.equals(lastInsetsSignature)) {
            lastInsetsSignature = signature;
            emit("computedInsets touch=" + rect);
        }
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = true;
        inputGeneration++;
        emit("startInput restarting=" + restarting);
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        Window nativeWindow = nativeWindow();
        if (nativeWindow != null) hideImeCaptionBar(nativeWindow);
        emit("startInputView restarting=" + restarting);
    }

    @Override public void onWindowShown() {
        super.onWindowShown();
        showGeneration++;
        Window nativeWindow = nativeWindow();
        if (nativeWindow != null) hideImeCaptionBar(nativeWindow);
        if (root != null) root.invalidate();
        emit("windowShown");
    }

    @Override public void onWindowHidden() {
        super.onWindowHidden();
        emit("windowHidden");
    }

    @Override public void onFinishInput() {
        super.onFinishInput();
        inputActive = false;
        emit("finishInput");
    }

    // Public API36 callback: hiding the IME caption may delegate the switcher.
    @Override public void onCustomImeSwitcherButtonRequestedVisible(boolean visible) {
        super.onCustomImeSwitcherButtonRequestedVisible(visible);
        switchRequested = visible;
        if (switchButton != null) switchButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        emit("switcherRequested visible=" + visible);
    }

    private Window nativeWindow() {
        Dialog dialog = getWindow();
        return dialog == null ? null : dialog.getWindow();
    }

    private void hideImeCaptionBar(Window win) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = win.getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.captionBar());
        }
    }

    private int dp(int value) {
        // All fixture dp arguments are positive: same rounding as Kotlin roundToInt.
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void emit(String event) {
        if (logBudget-- <= 0) return;
        Dialog dialog = getWindow();
        Window nativeWindow = dialog == null ? null : dialog.getWindow();
        View decor = nativeWindow == null ? null : nativeWindow.getDecorView();
        Boolean captionVisible = null;
        if (Build.VERSION.SDK_INT >= 30 && decor != null) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) captionVisible = insets.isVisible(WindowInsets.Type.captionBar());
        }
        // Same witnesses and names as the Kotlin fixture; no host-inset claims or text.
        Log.i(TAG, "event=" + event + " uptime=" + SystemClock.uptimeMillis()
                + " pid=" + Process.myPid()
                + " inputGeneration=" + inputGeneration + " showGeneration=" + showGeneration
                + " inputActive=" + inputActive + " inputViewShown=" + isInputViewShown()
                + " rootShown=" + (root == null ? null : root.isShown())
                + " dialogShowing=" + (dialog == null ? null : dialog.isShowing())
                + " decorAttached=" + (decor == null ? null : decor.isAttachedToWindow())
                + " decorShown=" + (decor == null ? null : decor.isShown())
                + " decorHeight=" + (decor == null ? null : decor.getHeight())
                + " windowType=" + (nativeWindow == null ? null : nativeWindow.getAttributes().type)
                + " windowToken=" + (decor == null || decor.getWindowToken() == null
                        ? null : decor.getWindowToken().hashCode())
                + " cardShown=" + (card == null ? null : card.isShown())
                + " cardWidth=" + (card == null ? null : card.getWidth())
                + " cardHeight=" + (card == null ? null : card.getHeight())
                + " drawCount=" + drawCount + " drawShowGeneration=" + lastLoggedDrawGeneration
                + " lastDrawUptime=" + lastDrawUptime + " requestedTop=" + lastRequestedTop
                + " captionVisible=" + captionVisible);
    }

    @Override public void onDestroy() {
        emit("destroyed");
        if (receiverRegistered) {
            unregisterReceiver(commands);
            receiverRegistered = false;
        }
        root = null;
        card = null;
        switchButton = null;
        super.onDestroy();
    }
}

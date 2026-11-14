package com.automaker.nav;

import android.app.Activity;
import android.app.Application;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.InputCallback;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.signin.InputSignInMethod;
import androidx.car.app.model.signin.SignInTemplate;
import androidx.car.app.navigation.NavigationManager;
import androidx.car.app.navigation.NavigationManagerCallback;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PanModeListener;
import androidx.car.app.validation.HostValidator;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class NavigationCarService extends CarAppService {
    static {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Method getRuntimeMethod = (Method) getRuntime.invoke(runtimeClass, "getRuntime", new Class<?>[0]);
            Method exempt = (Method) getRuntime.invoke(runtimeClass, "setHiddenApiExemptions", (Object) new Class[]{String[].class});
            Object runtime = getRuntimeMethod.invoke(null);
            exempt.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    static void deliverCarTouch(MotionEvent event) {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        if (mirror != null) mirror.carTouch(event);
    }

    static void deliverMappedTouch(int action, float x, float y) {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        if (mirror != null) mirror.acceptTouch(action, x, y);
    }

    static int ownDisplay() {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        return mirror == null ? Display.INVALID_DISPLAY : mirror.displayId;
    }

    static int screenWidth() {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        return mirror == null ? 1 : Math.max(mirror.surfaceWidth, 1);
    }

    static int screenHeight() {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        return mirror == null ? 1 : Math.max(mirror.surfaceHeight, 1);
    }

    static boolean sessionActive() {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        return mirror != null && mirror.displayId != Display.INVALID_DISPLAY;
    }

    static String shellCapture(String command) {
        try {
            return CarMirror.shell(command);
        } catch (Throwable error) {
            Log.i("AutoMakerNav", "Touch window lookup failed");
            return "";
        }
    }

    static void releaseTouchCapture(String packageName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                CarMirror.disableTouchBridge(packageName);
            }
        }, "AutoMakerTouch").start();
    }

    static void placeTouchLayer(int displayId, Rect bounds) {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        if (mirror != null) mirror.placeTouchLayer(displayId, bounds);
    }

    static void requestKeyboard(String text, int inputType, boolean password, boolean force) {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        if (mirror != null) mirror.offerKeyboard(text, inputType, password, force);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getApplication().registerActivityLifecycleCallbacks(new ScreenTracker());
    }

    @Override
    public void onDestroy() {
        CarMirror mirror = CarMirror.active == null ? null : CarMirror.active.get();
        if (mirror != null) mirror.detach();
        super.onDestroy();
    }

    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @Override
    public Session onCreateSession(SessionInfo sessionInfo) {
        return new Session() {
            @Override
            public Screen onCreateScreen(Intent intent) {
                return new MirrorScreen(getCarContext());
            }
        };
    }

    private static final class MirrorScreen extends Screen implements SurfaceCallback {
        private final CarMirror mirror = new CarMirror();
        private boolean started;
        private boolean keyboardOpen;
        private boolean parkedForKeyboard;

        MirrorScreen(CarContext context) {
            super(context);
            context.getCarService(AppManager.class).setSurfaceCallback(this);
            mirror.setKeyboardClient(new CarMirror.KeyboardClient() {
                @Override
                public void openKeyboard(String text, int inputType, boolean password) {
                    showKeyboard(text, inputType, password);
                }
            });
            claimNavigation(false);
        }

        @Override
        public Template onGetTemplate() {
            if (keyboardOpen) {
                keyboardOpen = false;
                mirror.suppressKeyboard();
                if (parkedForKeyboard) mirror.hold();
            }
            if (!started) claimNavigation(false);
            return new NavigationTemplate.Builder()
                    .setActionStrip(emptyActionStrip())
                    .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                    .setPanModeListener(new PanModeListener() {
                        @Override
                        public void onPanModeChanged(boolean inPanMode) {
                            Log.i("AutoMakerNav", "Pan mode " + inPanMode);
                        }
                    })
                    .build();
        }

        private void showKeyboard(String text, int inputType, boolean password) {
            if (keyboardOpen) return;
            keyboardOpen = true;
            parkedForKeyboard = false;
            try {
                getScreenManager().push(new KeyboardScreen(getCarContext(), text, inputType, password, new KeyboardScreen.Typer() {
                    @Override
                    public void typed(String value) {
                        mirror.applyTypedText(value, false, null);
                    }

                    @Override
                    public void submitted(String value) {
                        mirror.applyTypedText(value, true, new Runnable() {
                            @Override
                            public void run() {
                                finishTyping();
                            }
                        });
                    }
                }));
                Log.i("AutoMakerNav", "Car keyboard open");
            } catch (RuntimeException error) {
                keyboardOpen = false;
                Log.i("AutoMakerNav", "The car keyboard did not open", error);
            }
        }

        private void finishTyping() {
            if (!keyboardOpen) return;
            keyboardOpen = false;
            mirror.suppressKeyboard();
            try {
                if (getScreenManager().getStackSize() > 1) getScreenManager().pop();
            } catch (RuntimeException error) {
                Log.i("AutoMakerNav", "The car keyboard stayed open", error);
            }
            if (parkedForKeyboard) mirror.hold();
        }

        private static ActionStrip emptyActionStrip() {
            try {
                java.lang.reflect.Constructor<ActionStrip> constructor = ActionStrip.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Throwable error) {
                Log.i("AutoMakerNav", "The action strip could not be cleared");
                return new ActionStrip.Builder().addAction(Action.PAN).build();
            }
        }

        @Override
        public void onSurfaceAvailable(SurfaceContainer container) {
            Surface surface = container.getSurface();
            if (surface == null) return;
            parkedForKeyboard = false;
            mirror.attach(getCarContext(), surface, container.getWidth(), container.getHeight(), container.getDpi());
        }

        @Override
        public void onSurfaceDestroyed(SurfaceContainer container) {
            if (keyboardOpen) {
                parkedForKeyboard = true;
                mirror.park();
                return;
            }
            mirror.hold();
        }

        @Override
        public void onClick(float x, float y) {
            mirror.tap(x, y);
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
            mirror.scroll(distanceX, distanceY);
        }

        @Override
        public void onFling(float velocityX, float velocityY) {
            mirror.fling(velocityX, velocityY);
        }

        private void claimNavigation(boolean refresh) {
            if (started) return;
            started = true;
            Log.i("AutoMakerNav", "Navigation canvas claimed");
            if (refresh) invalidate();
        }
    }

    private static final class KeyboardScreen extends Screen {
        interface Typer {
            void typed(String value);
            void submitted(String value);
        }

        private final String seed;
        private final int inputType;
        private final boolean password;
        private final Typer typer;
        private boolean sawSeed;

        KeyboardScreen(CarContext context, String text, int inputType, boolean password, Typer typer) {
            super(context);
            String value = text == null ? "" : text;
            seed = value.length() <= 100 ? value : value.substring(0, 100);
            this.inputType = inputType;
            this.password = password;
            this.typer = typer;
        }

        @Override
        public Template onGetTemplate() {
            if (usesSignInKeyboard()) {
                try {
                    return signInTemplate();
                } catch (RuntimeException error) {
                    Log.i("AutoMakerNav", "The car keyboard used text input", error);
                }
            }
            return searchTemplate();
        }

        private Template searchTemplate() {
            return new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
                @Override
                public void onSearchTextChanged(String searchText) {
                    deliver(searchText, false);
                }

                @Override
                public void onSearchSubmitted(String searchText) {
                    deliver(searchText, true);
                }
            })
                    .setHeaderAction(Action.BACK)
                    .setInitialSearchText(seed)
                    .setSearchHint("Type")
                    .setShowKeyboardByDefault(true)
                    .build();
        }

        private Template signInTemplate() {
            InputSignInMethod method = new InputSignInMethod.Builder(new InputCallback() {
                @Override
                public void onInputTextChanged(String text) {
                    deliver(text, false);
                }

                @Override
                public void onInputSubmitted(String text) {
                    deliver(text, true);
                }
            })
                    .setHint("Type")
                    .setDefaultValue(seed)
                    .setInputType(password ? InputSignInMethod.INPUT_TYPE_PASSWORD : InputSignInMethod.INPUT_TYPE_DEFAULT)
                    .setKeyboardType(signInKeyboard())
                    .setShowKeyboardByDefault(true)
                    .build();
            return new SignInTemplate.Builder(method)
                    .setHeaderAction(Action.BACK)
                    .setTitle(password ? "Password" : "Type")
                    .setInstructions("Sends the text to the app.")
                    .build();
        }

        private boolean usesSignInKeyboard() {
            return password || signInKeyboard() != InputSignInMethod.KEYBOARD_DEFAULT;
        }

        private int signInKeyboard() {
            int klass = inputType & InputType.TYPE_MASK_CLASS;
            int variation = inputType & InputType.TYPE_MASK_VARIATION;
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                return InputSignInMethod.KEYBOARD_EMAIL;
            }
            if (klass == InputType.TYPE_CLASS_PHONE) return InputSignInMethod.KEYBOARD_PHONE;
            if (klass == InputType.TYPE_CLASS_NUMBER) return InputSignInMethod.KEYBOARD_NUMBER;
            return InputSignInMethod.KEYBOARD_DEFAULT;
        }

        private void deliver(String value, boolean submit) {
            if (value == null) value = "";
            if (!sawSeed) {
                if (value.length() == 0 && seed.length() > 0 && !submit) return;
                if (value.equals(seed)) {
                    sawSeed = true;
                    if (!submit) return;
                }
            }
            if (submit) typer.submitted(value);
            else typer.typed(value);
        }
    }

    static final class CarMirror {
        private static WeakReference<CarMirror> active;
        private VirtualDisplay display;
        private Presentation badge;
        private Surface host;
        private Context appContext;
        private View touchLayer;
        private WindowManager touchWindows;
        private int touchLayerDisplay = Display.INVALID_DISPLAY;
        private int displayId = Display.INVALID_DISPLAY;
        private int carDisplay = Display.INVALID_DISPLAY;
        private String appPackage = "";
        private String component;
        private boolean asking;
        private int surfaceWidth = 1;
        private int surfaceHeight = 1;
        private static WeakReference<Activity> screen;
        private final Handler main = new Handler(Looper.getMainLooper());
        private int fingerX = -1;
        private int fingerY = -1;
        private boolean fingerDown;
        private boolean fingerPlaced;
        private long downTime;
        private long placedAt;
        private KeyboardClient keyboardClient;
        private boolean keyboardHeld;
        private View keyboardField;
        private WeakReference<View> typedField;
        private static final Set<View> watchedTyping = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
        private final Runnable liftFinger = new Runnable() {
            @Override
            public void run() {
                if (!fingerDown) return;
                sendTouch(MotionEvent.ACTION_UP, fingerX, fingerY);
                fingerDown = false;
                fingerX = -1;
                fingerY = -1;
            }
        };
        private final Runnable releaseSession = new Runnable() {
            @Override
            public void run() {
                detach();
            }
        };

        void attach(Context context, Surface surface, int width, int height, int dpi) {
            main.removeCallbacks(releaseSession);
            appPackage = context.getPackageName();
            appContext = context.getApplicationContext();
            active = new WeakReference<>(this);
            int safeWidth = Math.max(width, 1);
            int safeHeight = Math.max(height, 1);
            int safeDpi = dpi > 0 ? dpi : 160;
            surfaceWidth = safeWidth;
            surfaceHeight = safeHeight;
            host = surface;
            drawIcon(context);
            if (display != null) {
                display.setSurface(surface);
                display.resize(safeWidth, safeHeight, safeDpi);
                showIcon(context);
                openWithShizuku(context);
                return;
            }
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager == null) return;
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            try {
                display = manager.createVirtualDisplay(
                        "AutoMakerNav", safeWidth, safeHeight, safeDpi, surface, flags | (1 << 6));
            } catch (SecurityException error) {
                Log.i("AutoMakerNav", "Touch flag was refused", error);
                display = manager.createVirtualDisplay(
                        "AutoMakerNav", safeWidth, safeHeight, safeDpi, surface, flags);
            }
            if (display == null || display.getDisplay() == null) {
                Log.i("AutoMakerNav", "The car display was not created");
                return;
            }
            displayId = display.getDisplay().getDisplayId();
            Log.i("AutoMakerNav", "Patched app display " + displayId + " flags " + display.getDisplay().getFlags());
            showIcon(context);
            openWithShizuku(context);
        }

        void hold() {
            main.removeCallbacks(releaseSession);
            main.postDelayed(releaseSession, 1500);
        }

        void park() {
            main.removeCallbacks(releaseSession);
        }

        void setKeyboardClient(KeyboardClient client) {
            keyboardClient = client;
        }

        void suppressKeyboard() {
            keyboardHeld = true;
        }

        void offerKeyboard(String text, int inputType, boolean password, boolean force) {
            if (displayId == Display.INVALID_DISPLAY || keyboardClient == null) return;
            if (force) keyboardHeld = false;
            if (keyboardHeld) return;
            captureEditor();
            keyboardClient.openKeyboard(text == null ? "" : text, inputType, password);
        }

        void applyTypedText(final String text, final boolean submit, final Runnable after) {
            final String value = text == null ? "" : text;
            main.post(new Runnable() {
                @Override
                public void run() {
                    writeEditor(value);
                    if (submit) commitEditor();
                    if (after != null) after.run();
                }
            });
        }

        void detach() {
            main.removeCallbacks(releaseSession);
            CarMirror current = active == null ? null : active.get();
            boolean ours = current == this;
            if (ours) active = null;
            if (ours) {
                final String packageName = appPackage;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        disableTouchBridge(packageName);
                    }
                }, "AutoMakerTouch").start();
            }
            host = null;
            removeTouchLayer();
            dismissBadge();
            VirtualDisplay shown = display;
            display = null;
            displayId = Display.INVALID_DISPLAY;
            if (shown != null) shown.release();
        }

        void placeTouchLayer(int sourceDisplay, Rect bounds) {
            final Rect copy = bounds == null ? new Rect() : new Rect(bounds);
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (active == null || active.get() != CarMirror.this) return;
                    if (copy.isEmpty()) {
                        removeTouchLayer();
                        return;
                    }
                    showTouchLayer(sourceDisplay, fitPicture(copy));
                }
            });
        }

        private Rect fitPicture(Rect window) {
            float target = surfaceWidth / (float) Math.max(surfaceHeight, 1);
            float aspect = window.width() / (float) Math.max(window.height(), 1);
            if (Math.abs(aspect - target) < 0.08f) return window;
            Rect fitted = new Rect();
            if (aspect > target) {
                int width = Math.max(Math.round(window.height() * target), 1);
                int left = window.left + (window.width() - width) / 2;
                fitted.set(left, window.top, left + width, window.bottom);
            } else {
                int height = Math.max(Math.round(window.width() / target), 1);
                int top = window.top + (window.height() - height) / 2;
                fitted.set(window.left, top, window.right, top + height);
            }
            return fitted;
        }

        private void showTouchLayer(int sourceDisplay, Rect bounds) {
            if (appContext == null || bounds.width() < 80 || bounds.height() < 80) return;
            try {
                WindowManager windows = touchWindows;
                if (touchLayer == null || touchLayerDisplay != sourceDisplay || windows == null) {
                    removeTouchLayer();
                    DisplayManager manager = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
                    Display shown = manager == null ? null : manager.getDisplay(sourceDisplay);
                    Context windowContext = shown == null ? appContext : appContext.createDisplayContext(shown);
                    windows = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
                    if (windows == null) return;
                    View layer = new View(windowContext);
                    layer.setBackgroundColor(Color.argb(1, 0, 0, 0));
                    layer.setOnTouchListener(new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View view, MotionEvent event) {
                            float x = event.getX() * surfaceWidth / Math.max(view.getWidth(), 1);
                            float y = event.getY() * surfaceHeight / Math.max(view.getHeight(), 1);
                            acceptTouch(event.getActionMasked(), x, y);
                            return true;
                        }
                    });
                    windows.addView(layer, touchParams(bounds));
                    touchLayer = layer;
                    touchWindows = windows;
                    touchLayerDisplay = sourceDisplay;
                    Log.i("AutoMakerNav", "Touch layer display " + sourceDisplay + " " + bounds.left + "," + bounds.top
                            + " " + bounds.width() + "x" + bounds.height());
                    return;
                }
                windows.updateViewLayout(touchLayer, touchParams(bounds));
            } catch (Throwable error) {
                Log.i("AutoMakerNav", "Touch layer was not added", error);
                removeTouchLayer();
            }
        }

        private WindowManager.LayoutParams touchParams(Rect bounds) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    bounds.width(),
                    bounds.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = bounds.left;
            params.y = bounds.top;
            return params;
        }

        private void removeTouchLayer() {
            View layer = touchLayer;
            WindowManager windows = touchWindows;
            touchLayer = null;
            touchWindows = null;
            touchLayerDisplay = Display.INVALID_DISPLAY;
            if (layer == null || windows == null) return;
            try {
                windows.removeView(layer);
            } catch (Throwable ignored) {
            }
        }

        private void drawIcon(Context context) {
            Surface target = host;
            if (target == null || !target.isValid()) return;
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
                Canvas canvas;
                try {
                    canvas = target.lockHardwareCanvas();
                } catch (RuntimeException ignored) {
                    canvas = target.lockCanvas(null);
                }
                canvas.drawColor(Color.BLACK);
                int size = Math.max(Math.min(canvas.getWidth(), canvas.getHeight()) / 3, 1);
                int left = (canvas.getWidth() - size) / 2;
                int top = (canvas.getHeight() - size) / 2;
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(canvas);
                target.unlockCanvasAndPost(canvas);
                Log.i("AutoMakerNav", "App icon drawn");
            } catch (Exception error) {
                Log.i("AutoMakerNav", "The app icon could not be drawn", error);
            }
        }

        private void showIcon(Context context) {
            if (display == null || display.getDisplay() == null) return;
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
                Context displayContext = context.createDisplayContext(display.getDisplay());
                dismissBadge();
                badge = new Presentation(displayContext, display.getDisplay()) {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        ImageView view = new ImageView(getContext());
                        view.setImageDrawable(icon);
                        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        view.setBackgroundColor(Color.BLACK);
                        int pad = Math.max(display.getDisplay().getWidth(), 1) / 5;
                        view.setPadding(pad, pad, pad, pad);
                        view.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        setContentView(view);
                    }
                };
                badge.show();
                Log.i("AutoMakerNav", "App icon shown");
            } catch (Exception error) {
                Log.i("AutoMakerNav", "The app icon could not be shown", error);
            }
        }

        private void dismissBadge() {
            Presentation current = badge;
            badge = null;
            if (current == null) return;
            try {
                current.dismiss();
            } catch (RuntimeException ignored) {
            }
        }

        private void openWithShizuku(Context context) {
            if (displayId == Display.INVALID_DISPLAY) return;
            Intent launch = NavigationActivity.mainIntent(context);
            if (launch == null || launch.getComponent() == null) return;
            component = launch.getComponent().flattenToString();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (rootAvailable()) {
                            Log.i("AutoMakerNav", "Opening with root");
                            runShell();
                            return;
                        }
                        long deadline = SystemClock.uptimeMillis() + 2000;
                        while (!Shizuku.pingBinder() && SystemClock.uptimeMillis() < deadline) {
                            Thread.sleep(100);
                        }
                        if (!Shizuku.pingBinder()) {
                            Log.i("AutoMakerNav", "Shizuku is not running and root is not available");
                            return;
                        }
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    requestShizuku();
                                }
                            });
                            return;
                        }
                        runShell();
                    } catch (Throwable error) {
                        Log.i("AutoMakerNav", "Could not open the patched app", error);
                    }
                }
            }, "AutoMakerLaunch").start();
        }

        private void requestShizuku() {
            if (asking) return;
            asking = true;
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != 5001) return;
                    asking = false;
                    Shizuku.removeRequestPermissionResultListener(this);
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        Log.i("AutoMakerNav", "Shizuku did not allow the patched app");
                        return;
                    }
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                runShell();
                            } catch (Throwable error) {
                                Log.i("AutoMakerNav", "Shizuku could not open the patched app", error);
                            }
                        }
                    }, "AutoMakerLaunch").start();
                }
            });
            Shizuku.requestPermission(5001);
            Log.i("AutoMakerNav", "Asked Shizuku to allow the patched app");
        }

        private void runShell() throws Exception {
            String output = command("am", "start", "--user", "0", "--display", String.valueOf(displayId), "-n", component);
            int task = -1;
            String moved = "";
            for (int attempt = 0; attempt < 3 && task < 0; attempt++) {
                if (attempt > 0) Thread.sleep(400);
                task = rootTaskId(shell("am stack list"), component);
            }
            if (task >= 0) moved = shell("cmd activity display move-stack " + task + " " + displayId);
            if (task >= 0 || output.contains("Starting:")) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        dismissBadge();
                    }
                });
            }
            Log.i("AutoMakerNav", "Privileged launch: " + output + " task " + task + " " + moved);
            carDisplay = carDisplay(shell("dumpsys window windows"), surfaceWidth, surfaceHeight);
            enableTouchBridge(appPackage);
            Log.i("AutoMakerNav", "Car touch display " + carDisplay);
        }

        private int carDisplay(String dump, int width, int height) {
            String[] lines = dump.split("\n");
            int display = -1;
            boolean gearhead = false;
            boolean visible = false;
            boolean sized = false;
            int found = Display.INVALID_DISPLAY;
            for (String line : lines) {
                if (line.contains("Window{")) {
                    if (gearhead && visible && sized && display >= 0) found = display;
                    display = -1;
                    gearhead = false;
                    visible = false;
                    sized = false;
                }
                int marker = line.indexOf("mDisplayId=");
                if (marker >= 0) {
                    int start = marker + "mDisplayId=".length();
                    int end = start;
                    while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                    if (end > start) display = Integer.parseInt(line.substring(start, end));
                }
                if (line.contains("package=com.google.android.projection.gearhead")) gearhead = true;
                if (line.contains("mViewVisibility=0x0")) visible = true;
                if (line.contains("Requested w=" + width + " h=" + height)) sized = true;
            }
            if (gearhead && visible && sized && display >= 0) found = display;
            return found;
        }

        private void enableTouchBridge(String packageName) {
            if (packageName == null || packageName.length() == 0) return;
            try {
                String component = packageName + "/" + TouchBridge.NAME;
                String current = safeSetting(shell("settings get secure enabled_accessibility_services"));
                String without = current.replace(component, "").replace("::", ":");
                if (without.startsWith(":")) without = without.substring(1);
                if (without.endsWith(":")) without = without.substring(0, without.length() - 1);
                if (!without.equals(current)) {
                    shell("settings put secure enabled_accessibility_services '" + without + "'");
                }
                String next = without.length() == 0 ? component : without + ":" + component;
                shell("settings put secure enabled_accessibility_services '" + next + "'");
                shell("settings put secure accessibility_enabled 1");
                try {
                    shell("appops set " + packageName + " SYSTEM_ALERT_WINDOW allow");
                } catch (Exception ignored) {
                    Log.i("AutoMakerNav", "Touch layer permission was not granted");
                }
                Log.i("AutoMakerNav", "Touch bridge enabled");
            } catch (Exception error) {
                Log.i("AutoMakerNav", "Touch bridge was not enabled", error);
            }
        }

        private static void disableTouchBridge(String packageName) {
            if (packageName == null || packageName.length() == 0) return;
            try {
                if (!privilegedReady()) return;
                String component = packageName + "/" + TouchBridge.NAME;
                String current = safeSetting(shell("settings get secure enabled_accessibility_services"));
                if (!current.contains(component)) return;
                String next = current.replace(component, "").replace("::", ":");
                if (next.startsWith(":")) next = next.substring(1);
                if (next.endsWith(":")) next = next.substring(0, next.length() - 1);
                shell("settings put secure enabled_accessibility_services '" + next + "'");
                if (next.length() == 0) shell("settings put secure accessibility_enabled 0");
                Log.i("AutoMakerNav", "Touch bridge removed");
            } catch (Exception error) {
                Log.i("AutoMakerNav", "Touch bridge stayed enabled", error);
            }
        }

        private static String safeSetting(String value) {
            if (value == null) return "";
            String trimmed = value.trim();
            if (trimmed.equals("null")) return "";
            StringBuilder safe = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '.' || c == '/' || c == ':' || c == '_' || c == '$') {
                    safe.append(c);
                }
            }
            return safe.toString();
        }

        private int rootTaskId(String stacks, String name) {
            String[] lines = stacks.split("\n");
            int current = -1;
            int found = -1;
            for (String line : lines) {
                int marker = line.indexOf("RootTask id=");
                if (marker >= 0) {
                    int start = marker + "RootTask id=".length();
                    int end = start;
                    while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                    if (end > start) current = Integer.parseInt(line.substring(start, end));
                }
                if (sameApp(line, name)) found = current;
            }
            return found;
        }

        private static boolean sameApp(String line, String componentName) {
            int slash = componentName.indexOf('/');
            if (slash <= 0) return line.contains(componentName);
            String packageName = componentName.substring(0, slash);
            String className = componentName.substring(slash + 1);
            if (line.contains(packageName + "/")) return true;
            if (className.startsWith(packageName + ".") && line.contains(className.substring(packageName.length()))) return true;
            return line.contains(className);
        }

        private static String shell(String commandText) throws Exception {
            return command("sh", "-c", commandText);
        }

        private static volatile Boolean rootReady;

        private static boolean rootAvailable() {
            Boolean cached = rootReady;
            if (cached != null) return cached;
            boolean found = false;
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                String output = read(process.getInputStream());
                String errors = read(process.getErrorStream());
                int code = process.waitFor();
                found = code == 0 && (output + "\n" + errors).contains("uid=0");
            } catch (Throwable ignored) {
                found = false;
            }
            rootReady = found;
            if (found) Log.i("AutoMakerNav", "Root is available");
            return found;
        }

        private static boolean shizukuReady() {
            try {
                return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean privilegedReady() {
            return rootAvailable() || shizukuReady();
        }

        private static String command(String... args) throws Exception {
            Process process = rootAvailable() ? rootProcess(args) : shizukuProcess(args);
            StringBuilder errors = new StringBuilder();
            Thread error = new Thread(new Runnable() {
                @Override
                public void run() {
                    errors.append(read(process.getErrorStream()));
                }
            });
            error.start();
            String output = read(process.getInputStream());
            error.join();
            process.waitFor();
            return (output + "\n" + errors).trim();
        }

        private static Process shizukuProcess(String... args) throws Exception {
            Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            return (Process) method.invoke(null, (Object) args, null, null);
        }

        private static Process rootProcess(String... args) throws Exception {
            StringBuilder joined = new StringBuilder();
            for (String arg : args) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(shellQuote(arg));
            }
            return Runtime.getRuntime().exec(new String[]{"su", "-c", joined.toString()});
        }

        private static String shellQuote(String value) {
            if (value == null) return "''";
            if (value.length() == 0) return "''";
            boolean plain = true;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isWhitespace(c) || "'\"\\$`;&|()<>".indexOf(c) >= 0) {
                    plain = false;
                    break;
                }
            }
            if (plain) return value;
            return "'" + value.replace("'", "'\\''") + "'";
        }

        private static String read(InputStream stream) {
            StringBuilder output = new StringBuilder();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            } catch (Exception ignored) {
            }
            return output.toString();
        }

        private static int displayOf(MotionEvent event) {
            try {
                Method method = MotionEvent.class.getDeclaredMethod("getDisplayId");
                method.setAccessible(true);
                Object value = method.invoke(event);
                if (value instanceof Integer) return (Integer) value;
            } catch (Throwable ignored) {
            }
            return Integer.MIN_VALUE;
        }

        private static String deviceName(MotionEvent event) {
            InputDevice device = event.getDevice();
            if (device == null || device.getName() == null) return "";
            return device.getName();
        }

        private static boolean phoneDevice(String name) {
            return name.startsWith("sec_") || name.contains("e-pen") || name.contains("hall");
        }

        void carTouch(MotionEvent event) {
            final int action = event.getActionMasked();
            final float x = event.getX();
            final float y = event.getY();
            final int sourceDisplay = displayOf(event);
            final String device = deviceName(event);
            if (action == MotionEvent.ACTION_DOWN) {
                Log.i("AutoMakerNav", "Motion display " + sourceDisplay + " device " + device);
            }
            if (displayId != Display.INVALID_DISPLAY && sourceDisplay == displayId) return;
            boolean unknownDisplay = sourceDisplay == Display.DEFAULT_DISPLAY || sourceDisplay == Integer.MIN_VALUE;
            if (unknownDisplay && phoneDevice(device)) return;
            if (sourceDisplay != Integer.MIN_VALUE && sourceDisplay != Display.DEFAULT_DISPLAY) carDisplay = sourceDisplay;
            acceptTouch(action, x, y);
        }

        void acceptTouch(int action, float x, float y) {
            final int px = clamp(x, surfaceWidth);
            final int py = clamp(y, surfaceHeight);
            main.post(new Runnable() {
                @Override
                public void run() {
                    boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
                    boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || action == MotionEvent.ACTION_POINTER_UP;
                    if (down || (action == MotionEvent.ACTION_MOVE && !fingerDown)) {
                        if (down) Log.i("AutoMakerNav", "Car touch " + px + "," + py);
                        main.removeCallbacks(liftFinger);
                        if (fingerDown) sendTouch(MotionEvent.ACTION_UP, fingerX, fingerY);
                        placeFinger(px, py);
                        fingerDown = sendTouch(MotionEvent.ACTION_DOWN, px, py);
                        if (action == MotionEvent.ACTION_MOVE && fingerDown) sendTouch(MotionEvent.ACTION_MOVE, px, py);
                        return;
                    }
                    if (up) {
                        placeFinger(px, py);
                        if (fingerDown) sendTouch(MotionEvent.ACTION_UP, px, py);
                        fingerDown = false;
                        return;
                    }
                    if (action == MotionEvent.ACTION_MOVE && fingerDown) {
                        placeFinger(px, py);
                        sendTouch(MotionEvent.ACTION_MOVE, px, py);
                    }
                }
            });
        }

        void tap(float x, float y) {
            final int px = clamp(x, surfaceWidth);
            final int py = clamp(y, surfaceHeight);
            Log.i("AutoMakerNav", "Tap " + px + "," + py);
            main.post(new Runnable() {
                @Override
                public void run() {
                    main.removeCallbacks(liftFinger);
                    if (fingerDown) {
                        sendTouch(MotionEvent.ACTION_UP, fingerX, fingerY);
                        fingerDown = false;
                    }
                    placeFinger(px, py);
                    if (!sendTouch(MotionEvent.ACTION_DOWN, px, py)) {
                        inject("input -d " + displayId + " tap " + px + " " + py);
                        return;
                    }
                    sendTouch(MotionEvent.ACTION_UP, px, py);
                }
            });
        }

        void scroll(float distanceX, float distanceY) {
            // The host distance is the previous point minus the current one.
            final float dx = -distanceX;
            final float dy = -distanceY;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!fingerDown) beginDrag();
                    int x2 = clamp(fingerX + dx, surfaceWidth);
                    int y2 = clamp(fingerY + dy, surfaceHeight);
                    if (!fingerDown && !sendTouch(MotionEvent.ACTION_DOWN, fingerX, fingerY)) {
                        inject("input -d " + displayId + " swipe " + fingerX + " " + fingerY + " " + x2 + " " + y2 + " 80");
                        fingerX = x2;
                        fingerY = y2;
                        return;
                    }
                    fingerDown = true;
                    fingerX = x2;
                    fingerY = y2;
                    sendTouch(MotionEvent.ACTION_MOVE, x2, y2);
                    main.removeCallbacks(liftFinger);
                    main.postDelayed(liftFinger, 180);
                }
            });
        }

        void fling(float velocityX, float velocityY) {
            final float vx = velocityX;
            final float vy = velocityY;
            main.post(new Runnable() {
                @Override
                public void run() {
                    main.removeCallbacks(liftFinger);
                    if (!fingerDown) beginDrag();
                    int x1 = fingerX;
                    int y1 = fingerY;
                    if (!fingerDown) {
                        fingerX = clamp(x1 + vx / 15f, surfaceWidth);
                        fingerY = clamp(y1 + vy / 15f, surfaceHeight);
                        if (sendTouch(MotionEvent.ACTION_DOWN, x1, y1)) {
                            sendTouch(MotionEvent.ACTION_MOVE, fingerX, fingerY);
                        }
                    }
                    if (sendTouch(MotionEvent.ACTION_UP, fingerX, fingerY)) {
                        fingerDown = false;
                        fingerX = -1;
                        fingerY = -1;
                        return;
                    }
                    inject("input -d " + displayId + " swipe " + x1 + " " + y1 + " " + fingerX + " " + fingerY + " 120");
                    fingerDown = false;
                    fingerX = -1;
                    fingerY = -1;
                }
            });
        }

        private void placeFinger(int x, int y) {
            fingerX = x;
            fingerY = y;
            fingerPlaced = true;
            placedAt = SystemClock.uptimeMillis();
        }

        private void beginDrag() {
            boolean sameTouch = fingerPlaced && fingerX >= 0 && SystemClock.uptimeMillis() - placedAt < 400;
            if (sameTouch) return;
            fingerX = surfaceWidth / 2;
            fingerY = surfaceHeight / 2;
        }

        private boolean sendTouch(int action, float x, float y) {
            Activity activity = currentScreen();
            if (activity == null) {
                Log.i("AutoMakerNav", "Touch missed the screen");
                return false;
            }
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return false;
            boolean fresh = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
            View target = fresh ? null : (touchView == null ? null : touchView.get());
            java.util.List<View> windows = null;
            if (target == null || !target.isAttachedToWindow()) {
                windows = touchTargets(x, y);
                target = windows.isEmpty() ? decor : windows.get(0);
            }
            if (fresh || touchView == null || touchView.get() == null) touchView = new WeakReference<>(target);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchView = null;
            boolean handled = dispatchTouch(target, action, x, y);
            if (!handled && windows != null) {
                for (int i = 1; i < windows.size() && !handled; i++) handled = dispatchTouch(windows.get(i), action, x, y);
            }
            if (!handled && target != decor) handled = dispatchTouch(decor, action, x, y);
            if (action != MotionEvent.ACTION_MOVE) {
                Log.i("AutoMakerNav", "Touch delivered " + action + " at " + (int) x + "," + (int) y
                        + " " + target.getClass().getSimpleName() + " handled " + handled);
            }
            return true;
        }

        private boolean dispatchTouch(View target, int action, float x, float y) {
            if (target == null) return false;
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) downTime = now;
            MotionEvent event = MotionEvent.obtain(downTime, now, action, x - location[0], y - location[1], 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean handled = target.dispatchTouchEvent(event);
            event.recycle();
            return handled;
        }

        private WeakReference<View> touchView;

        private java.util.List<View> touchTargets(float x, float y) {
            java.util.ArrayList<View> found = new java.util.ArrayList<>();
            try {
                Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
                Object global = globalClass.getMethod("getInstance").invoke(null);
                Field viewsField = globalClass.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object value = viewsField.get(global);
                if (!(value instanceof java.util.List)) return found;
                java.util.List<?> views = (java.util.List<?>) value;
                for (int i = views.size() - 1; i >= 0; i--) {
                    Object item = views.get(i);
                    if (!(item instanceof View)) continue;
                    View view = (View) item;
                    if (!view.isShown() || !view.isAttachedToWindow()) continue;
                    Display shown = view.getDisplay();
                    if (shown == null || shown.getDisplayId() != displayId) continue;
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    float localX = x - location[0];
                    float localY = y - location[1];
                    if (localX < 0 || localY < 0 || localX >= view.getWidth() || localY >= view.getHeight()) continue;
                    found.add(view);
                }
            } catch (Throwable ignored) {
            }
            return found;
        }

        static void remember(Activity activity) {
            if (activity == null || activity.isFinishing()) return;
            if (NavigationActivity.NAME.equals(activity.getClass().getName())) return;
            screen = new WeakReference<>(activity);
            watchTyping(activity);
            Log.i("AutoMakerNav", "Screen ready " + activity.getClass().getName());
        }

        private static void watchTyping(final Activity activity) {
            if (activity.getWindow() == null) return;
            final View decor = activity.getWindow().getDecorView();
            if (decor == null || !watchedTyping.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
                @Override
                public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                    CarMirror mirror = active == null ? null : active.get();
                    if (mirror == null) return;
                    mirror.noteTypingFocus(activity, newFocus);
                }
            });
        }

        private void noteTypingFocus(Activity activity, View focus) {
            if (displayId == Display.INVALID_DISPLAY) return;
            Display shown = activity.getDisplay();
            if (shown == null || shown.getDisplayId() != displayId) return;
            if (focus == null || !focus.onCheckIsTextEditor()) {
                if (focus != null) {
                    keyboardHeld = false;
                    keyboardField = null;
                }
                return;
            }
            typedField = new WeakReference<>(focus);
            if (!(focus instanceof TextView)) return;
            if (focus != keyboardField) keyboardHeld = false;
            keyboardField = focus;
            TextView field = (TextView) focus;
            CharSequence current = field.getText();
            offerKeyboard(current == null ? "" : current.toString(), field.getInputType(), secretField(field), false);
        }

        private void captureEditor() {
            Activity activity = currentScreen();
            if (activity == null || activity.getWindow() == null) return;
            Display shown = activity.getDisplay();
            if (shown == null || shown.getDisplayId() != displayId) return;
            View focus = activity.getCurrentFocus();
            if (focus != null && focus.onCheckIsTextEditor()) typedField = new WeakReference<>(focus);
        }

        private void writeEditor(String value) {
            View field = typedField == null ? null : typedField.get();
            WebView page = webViewOf(field);
            if (page != null) {
                page.evaluateJavascript(fillScript(value), null);
                TouchBridge.writeEditor(displayId, value);
                return;
            }
            if (field instanceof TextView && field.onCheckIsTextEditor()) {
                TextView editor = (TextView) field;
                CharSequence current = editor.getText();
                if (current != null && value.contentEquals(current)) return;
                if (current != null && value.length() == 100 && current.length() > 100 && current.toString().startsWith(value)) return;
                editor.setText(value);
                if (editor instanceof EditText) {
                    EditText edit = (EditText) editor;
                    edit.setSelection(edit.getText().length());
                }
                return;
            }
            TouchBridge.writeEditor(displayId, value);
        }

        private void commitEditor() {
            View field = typedField == null ? null : typedField.get();
            WebView page = webViewOf(field);
            if (page != null) {
                page.evaluateJavascript(
                        "(function(){var e=document.activeElement;if(!e)return false;"
                                + "e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,which:13,bubbles:true}));"
                                + "e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',keyCode:13,which:13,bubbles:true}));"
                                + "return true;})()",
                        null);
                TouchBridge.submitEditor(displayId);
                return;
            }
            if (field instanceof TextView && field.onCheckIsTextEditor()) {
                int action = EditorInfo.IME_ACTION_UNSPECIFIED;
                if (field instanceof EditText) action = ((EditText) field).getImeOptions() & EditorInfo.IME_MASK_ACTION;
                if (action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
                    ((TextView) field).onEditorAction(action);
                    return;
                }
                long now = SystemClock.uptimeMillis();
                field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
                field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
                return;
            }
            TouchBridge.submitEditor(displayId);
        }

        private static boolean secretField(TextView field) {
            int variation = field.getInputType() & InputType.TYPE_MASK_VARIATION;
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return true;
            }
            return field.getTransformationMethod() instanceof PasswordTransformationMethod;
        }

        private static WebView webViewOf(View field) {
            View current = field;
            while (current != null) {
                if (current instanceof WebView) return (WebView) current;
                if (!(current.getParent() instanceof View)) return null;
                current = (View) current.getParent();
            }
            return null;
        }

        private static String fillScript(String value) {
            return "(function(){var e=document.activeElement;if(!e)return false;"
                    + "var tag=e.tagName;if(tag!=='INPUT'&&tag!=='TEXTAREA'&&!e.isContentEditable)return false;"
                    + "if(e.isContentEditable){e.textContent=" + JSONObject.quote(value) + ";}"
                    + "else{e.value=" + JSONObject.quote(value) + ";}"
                    + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "e.dispatchEvent(new Event('change',{bubbles:true}));return true;})()";
        }

        interface KeyboardClient {
            void openKeyboard(String text, int inputType, boolean password);
        }

        private Activity currentScreen() {
            Activity activity = screen == null ? null : screen.get();
            if (activity != null && !activity.isFinishing()) return activity;
            activity = foregroundActivity();
            if (activity != null) screen = new WeakReference<>(activity);
            return activity;
        }

        private int clamp(float value, int limit) {
            return Math.max(0, Math.min(Math.max(limit - 1, 0), Math.round(value)));
        }

        private void inject(final String command) {
            if (displayId == Display.INVALID_DISPLAY) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!privilegedReady()) {
                            Log.i("AutoMakerNav", "Touch was not delivered because root and Shizuku are not allowed");
                            return;
                        }
                        String output = shell(command);
                        if (output.length() > 0) Log.i("AutoMakerNav", "Touch: " + output);
                    } catch (Throwable error) {
                        Log.i("AutoMakerNav", "Touch was not delivered", error);
                    }
                }
            }, "AutoMakerTouch").start();
        }

        private Activity foregroundActivity() {
            try {
                Class<?> threadClass = Class.forName("android.app.ActivityThread");
                Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
                Field activitiesField = threadClass.getDeclaredField("mActivities");
                activitiesField.setAccessible(true);
                Object raw = activitiesField.get(thread);
                if (!(raw instanceof Map)) return null;
                Activity fallback = null;
                for (Object record : ((Map<?, ?>) raw).values()) {
                    if (record == null) continue;
                    Field activityField = record.getClass().getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Object value = activityField.get(record);
                    if (!(value instanceof Activity)) continue;
                    Activity activity = (Activity) value;
                    if (activity.isFinishing() || NavigationActivity.NAME.equals(activity.getClass().getName())) continue;
                    fallback = activity;
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) return activity;
                }
                return fallback;
            } catch (Exception error) {
                Log.i("AutoMakerNav", "Could not find the app screen", error);
                return null;
            }
        }
    }

    private static final class ScreenTracker implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            CarMirror.remember(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            CarMirror.remember(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            CarMirror.remember(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            Activity current = CarMirror.screen == null ? null : CarMirror.screen.get();
            if (current == activity) CarMirror.screen = null;
        }
    }
}

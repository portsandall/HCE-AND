package com.halo.decomp;

import android.media.AudioAttributes;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View;
import android.widget.Toast;

/** Multitouch overlay with direct swipe aiming and full-display layout editing. */
public final class TouchControls extends View implements SensorEventListener {
    private static final int LEFT = TouchLayout.LEFT, LOOK = -5;
    private static final int TOGGLE = -3, EDIT = -4, EXPORT = -6, IMPORT = -7;
    private static final class Button {
        final String label;
        final float radius;
        final int bit, trigger;
        Button(String label, float radius, int bit, int trigger) {
            this.label = label; this.radius = radius;
            this.bit = bit; this.trigger = trigger;
        }
    }
    // Button bits follow SDL_GamepadButton; triggers are SDL axes 4 and 5.
    private final Button[] buttons = {
        new Button("A / Jump", 36, 0, -1),
        new Button("B / Melee", 32, 1, -1),
        new Button("X / Reload", 34, 2, -1),
        new Button("Y / Weapon", 32, 3, -1),
        new Button("Fire", 39, -1, 5),
        new Button("Grenade", 35, -1, 4),
        new Button("Crouch", 32, 7, -1),
        new Button("Zoom", 32, 8, -1),
        new Button("Light", 27, 9, -1),
        new Button("Gren. type", 29, 10, -1),
        new Button("Pause", 28, 6, -1),
        new Button("Back", 28, 4, -1),
        new Button("Up", 25, 11, -1),
        new Button("Down", 25, 12, -1),
        new Button("Left", 25, 13, -1),
        new Button("Right", 25, 14, -1),
        new Button("", 64, -1, -1), // movement stick keeps its saved index
        new Button("Fire", 39, -1, 5)
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseIntArray owners = new SparseIntArray();
    private final SparseArray<float[]> buttonTouches = new SparseArray<>();
    private final int[] axes = new int[6];
    private TouchLayout layout = new TouchLayout();
    private final SharedPreferences preferences;
    private boolean editing;
    private final SensorManager sensors;
    private final Sensor gyroscope;
    private final Vibrator vibrator;
    private final GyroscopeAim gyroAim = new GyroscopeAim();
    private final float[] gyroDelta = new float[2];
    private boolean deviceInputActive, gyroRegistered;
    private int lastAmplitude;
    private long lastVibration;
    private final AudioAttributes rumbleAttributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private static native int nativeRumble();
    private final Runnable rumblePoll = new Runnable() {
        public void run() {
            if (!deviceInputActive) return;
            int amplitude = layout.rumbleEnabled && !editing && !menusActive ? nativeRumble() : 0;
            if (vibrator != null && vibrator.hasVibrator()) {
                if (amplitude == 0) cancelRumble();
                else if (amplitude != lastAmplitude || SystemClock.uptimeMillis()-lastVibration >= 70) {
                    try {
                        vibrator.vibrate(VibrationEffect.createOneShot(110,
                            vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE), rumbleAttributes);
                        lastAmplitude = amplitude; lastVibration = SystemClock.uptimeMillis();
                    } catch (RuntimeException e) { cancelRumble(); }
                }
            }
            postDelayed(this, 16);
        }
    };
    private int lookPointer = -1;
    private float lookX, lookY;
    private float sensitivity;
    private float logicalWidth = 960, logicalHeight = 540;
    private static native void nativeLook(float dx, float dy);
    private static native void nativeLookReset();
    private static native boolean nativeCheatRequest(int id, boolean enabled);
    private static native int nativeCheatStatus(int id);
    private int dragPointer = -1, dragControl = -1;
    private float dragOffsetX, dragOffsetY;
    private float scale = 1, offsetX, offsetY;
    private int insetLeft, insetRight, insetTop, insetBottom;
    private boolean visible = true;

    private static native void nativeState(int lx, int ly, int rx, int ry,
                                          int lt, int rt, int buttons);

    public TouchControls(Context context) {
        super(context);
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        preferences = context.getSharedPreferences("touch-layout-v1", Context.MODE_PRIVATE);
        String configuration = preferences.getString("configuration", null);
        if (configuration != null) {
            try {
                TouchLayout.Configuration saved = TouchLayout.importConfiguration(configuration);
                layout = saved.layout; sensitivity = saved.sensitivity;
            } catch (IllegalArgumentException e) {
                Toast.makeText(context, "Saved layout could not be loaded. Using defaults.", Toast.LENGTH_LONG).show();
            }
        } else {
            for (int i = 0; i < layout.size(); i++)
                if (i != TouchLayout.FIRE_LEFT || preferences.getBoolean("swipe-layout", false))
                    layout.restore(i, preferences.getFloat("x"+i, layout.x(i)),
                                      preferences.getFloat("y"+i, layout.y(i)));
        }
        sensitivity = preferences.getFloat("look-sensitivity", sensitivity > 0 ? sensitivity : 1f);
        if (!Float.isFinite(sensitivity) || sensitivity < 0.25f || sensitivity > 4f) sensitivity = 1f;
        setFocusable(false);
        setContentDescription("Halo touch controller");
        setOnApplyWindowInsetsListener((view, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetRight = insets.getSystemWindowInsetRight();
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                insetLeft = Math.max(insetLeft, insets.getDisplayCutout().getSafeInsetLeft());
                insetRight = Math.max(insetRight, insets.getDisplayCutout().getSafeInsetRight());
                insetTop = Math.max(insetTop, insets.getDisplayCutout().getSafeInsetTop());
                insetBottom = Math.max(insetBottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            layoutControls();
            return insets;
        });
    }

    private void layoutControls() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        reset();
        // Keep circles proportional while allowing placement across the complete display.
        float width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
        scale = Math.max(0.01f, Math.min(width / 960f, height / 540f));
        offsetX = offsetY = 0;
        logicalWidth = width / scale; logicalHeight = height / scale;
        layout.bounds(logicalWidth, logicalHeight);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutControls();
        requestApplyInsets();
    }

    public void reset() {
        dragPointer = dragControl = -1;
        owners.clear();
        buttonTouches.clear();
        lookPointer = -1;
        gyroAim.reset();
        nativeLookReset();
        java.util.Arrays.fill(axes, 0);
        publish();
        invalidate();
    }

    private void publish() {
        if (editing || menusActive) {
            nativeState(0, 0, 0, 0, 0, 0, 0);
            return;
        }
        int bits = 0;
        axes[4] = axes[5] = 0;
        for (int i = 0; i < owners.size(); i++) {
            int control = owners.valueAt(i);
            if (control < 0 || control >= layout.size()) continue;
            Button b = buttons[layout.type(control)];
            if (b.bit >= 0) bits |= 1 << b.bit;
            if (b.trigger >= 0) axes[b.trigger] = 32767;
        }
        nativeState(axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], bits);
    }

    private boolean held(int control) {
        return owners.indexOfValue(control) >= 0;
    }

    private static boolean inside(float x, float y, float cx, float cy, float radius) {
        return (x-cx)*(x-cx) + (y-cy)*(y-cy) <= radius*radius;
    }

    private int hit(float x, float y) {
        if (editing && inside(x, y, optionsX(), toolbarY(), 34)) return EDIT;
        if (editing && inside(x, y, optionsX()-156, toolbarY(), 34)) return EXPORT;
        if (editing && inside(x, y, optionsX()-78, toolbarY(), 34)) return IMPORT;
        if (inside(x, y, logicalWidth/2, toolbarY(), 28)) return TOGGLE;
        if (!visible) return Integer.MIN_VALUE;
        for (int i = 0; i < layout.size(); i++) {
            if (!layout.shown(i) || layout.type(i) == LEFT) continue;
            Button b = buttons[layout.type(i)];
            if (inside(x, y, layout.x(i), layout.y(i), layout.radius(i))) return i;
        }
        if (layout.shown(LEFT) && !held(LEFT) && inside(x, y, layout.x(LEFT), layout.y(LEFT), layout.radius(LEFT)*1.28f)) return LEFT;
        return Integer.MIN_VALUE;
    }

    private void moveStick(int control, float x, float y) {
        int axis = 0;
        float dx = (x - layout.x(control)) / layout.radius(control);
        float dy = (y - layout.y(control)) / layout.radius(control);
        float length = (float)Math.sqrt(dx*dx + dy*dy);
        if (length < 0.12f) { dx = 0; dy = 0; }
        else if (length > 1) { dx /= length; dy /= length; }
        axes[axis] = Math.round(dx * 32767);
        axes[axis+1] = Math.round(dy * 32767);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        int id = event.getPointerId(index);
        float x = (event.getX(index)-offsetX)/scale, y = (event.getY(index)-offsetY)/scale;
        if (editing) return editTouch(event, action, index, id, x, y);
        if (menusActive) {
            if (event.getPointerId(index) == event.getPointerId(0) &&
                    (action == MotionEvent.ACTION_UP ||
                    (action == MotionEvent.ACTION_MOVE && (menuPage == 4 || menuPage == 5 || menuPage == 12))))
                nativeMenuPointer(event.getX(0)/getWidth(), event.getY(0)/getHeight(), true, false);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int control = hit(x, y);
            if (control == EDIT) {
                return true;
            } else if (control == TOGGLE) {
                reset(); visible = !visible; performClick();
            } else if (control != Integer.MIN_VALUE) {
                owners.put(id, control);
                if (control >= 0 && layout.type(control) == LEFT) moveStick(control, x, y);
                else buttonTouches.put(id, new float[]{event.getX(index), event.getY(index)});
            } else if (visible && lookPointer < 0) {
                lookPointer = id; lookX = event.getX(index); lookY = event.getY(index);
                owners.put(id, LOOK);
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int control = owners.get(event.getPointerId(i), Integer.MIN_VALUE);
                if (control >= 0 && layout.type(control) == LEFT)
                    moveStick(control, (event.getX(i)-offsetX)/scale, (event.getY(i)-offsetY)/scale);
                int pointer = event.getPointerId(i);
                float[] origin = buttonTouches.get(pointer);
                if (lookPointer < 0 && origin != null &&
                        Math.hypot(event.getX(i)-origin[0], event.getY(i)-origin[1]) > 10*scale) {
                    // A held action button can also aim, including either Fire button.
                    lookPointer = pointer; lookX = origin[0]; lookY = origin[1];
                }
                if (pointer == lookPointer) {
                    float nx = event.getX(i), ny = event.getY(i);
                    nativeLook((nx-lookX)/scale*sensitivity, (ny-lookY)/scale*sensitivity);
                    lookX = nx; lookY = ny;
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int control = owners.get(id, Integer.MIN_VALUE);
            if (control >= 0 && layout.type(control) == LEFT) {
                int axis = 0;
                axes[axis] = axes[axis+1] = 0;
            }
            if (id == lookPointer) lookPointer = -1;
            owners.delete(id);
            buttonTouches.remove(id);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            reset();
        }
        publish(); invalidate();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private boolean editTouch(MotionEvent event, int action, int index, int id, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (dragPointer < 0) {
                int control = hit(x, y);
                if (control == EDIT) {
                    if (saveLayout()) {
                        reset(); editing = false; menuPage = 2; publishMenu(); updateSensors(); performClick();
                    }
                } else if (control == EXPORT || control == IMPORT) {
                    reset();
                    ((HaloActivity)getContext()).chooseLayoutFile(control == EXPORT, exportLayout());
                } else if (control >= 0) {
                    dragPointer = id; dragControl = control;
                    dragOffsetX = x-layout.x(control); dragOffsetY = y-layout.y(control);
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE && dragPointer >= 0) {
            int pointer = event.findPointerIndex(dragPointer);
            if (pointer >= 0)
                layout.move(dragControl, (event.getX(pointer)-offsetX)/scale-dragOffsetX,
                                        (event.getY(pointer)-offsetY)/scale-dragOffsetY);
        } else if (action == MotionEvent.ACTION_CANCEL ||
                   ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && id == dragPointer)) {
            dragPointer = dragControl = -1;
        }
        publish(); invalidate();
        return true;
    }

    private float toolbarY() { return Math.max(38, insetTop/scale+38); }
    private float optionsX() { return logicalWidth-Math.max(42, insetRight/scale+42); }

    private void editorButton(Canvas canvas) {
        if (editing) circle(canvas, optionsX(), toolbarY(), 34, "Save", false);
        if (editing) {
            circle(canvas, optionsX()-156, toolbarY(), 34, "Export", false);
            circle(canvas, optionsX()-78, toolbarY(), 34, "Import", false);
            paint.setTextSize(14);
            canvas.drawText("Drag controls, then Save and exit", logicalWidth/2, toolbarY()+58, paint);
            paint.setTextSize(9);
            canvas.drawText("and exit", optionsX(), toolbarY()+17, paint);
        }
    }


    private boolean menusActive = true;
    private int menuContext, menuPage, menuOffset, selectedControl, revision, currentFps;
    private static final int MENU_PAGE_SIZE = 4;
    private static native int[] nativeMenuPoll();
    private static native void nativeMenuPointer(float x, float y, boolean click, boolean back);
    private static native void nativeMenuPublish(int revision, int page, boolean editing,
        String title, String[] labels, int[] actions, int[] values);
    private static final String[] CHEATS = {"Invincibility", "Jetpack", "Infinite ammo", "Bump possession",
        "Super jump", "Reflexive damage", "Medusa", "Omnipotent", "Controller cheats", "Bottomless clip",
        "Active camouflage (local player)", "Active camouflage", "All powerups", "All vehicles",
        "All weapons", "Teleport to camera"};
    private final Runnable menuPoll = new Runnable() {
        public void run() {
            if (!deviceInputActive) return;
            int[] state = nativeMenuPoll();
            if (state != null) {
                boolean active = state[0] != 0;
                if (active != menusActive || menuContext != state[1]) {
                    menusActive = active; menuContext = state[1];
                    menuPage = menuOffset = 0; editing = false;
                    reset(); cancelRumble(); updateSensors(); revision++; publishMenu();
                }
                if (state[2] != 0 && state[4] == revision) applyMenuAction(state[2], state[3]);
                currentFps = state[5];
                if (menuPage == 6) publishMenu();
                invalidate();
            }
            postDelayed(this, 16);
        }
    };

    public boolean menuBack() {
        if (!menusActive) return false;
        if (editing) {
            if (saveLayout()) {
                editing = false; menuPage = 2; revision++; publishMenu(); invalidate();
            }
        }
        else nativeMenuPointer(0, 0, false, true);
        return true;
    }

    private void applyMenuAction(int action, int value) {
        if (action >= 3000) {
            int id = action-3000, status = nativeCheatStatus(id);
            if (status != -2) nativeCheatRequest(id, id >= 10 || status != 1);
        } else if (action >= 2000) {
            if (layout.add(action-2000) < 0)
                Toast.makeText(getContext(), "Control limit reached or movement stick already visible.", Toast.LENGTH_SHORT).show();
            else saveLayout();
            menuPage = 7; menuOffset = 0;
        } else if (action >= 1000) {
            selectedControl = action-1000; menuPage = menuPage == 8 ? 12 : 9;
        } else if (action == 90) {
            if (menuPage == 1 || (menuContext == 2 && (menuPage == 2 || menuPage == 3 || menuPage == 6))) menuPage = 0;
            else if (menuPage == 2 || menuPage == 3 || menuPage == 6) menuPage = 1;
            else if (menuPage == 5) menuPage = 3;
            else if (menuPage == 9 || menuPage == 10 || menuPage == 11) menuPage = 7;
            else if (menuPage == 12) menuPage = 8;
            else menuPage = 2;
            menuOffset = 0;
        } else if (action == 91 || action == 92) menuOffset += action == 91 ? MENU_PAGE_SIZE : -MENU_PAGE_SIZE;
        else if (action == 12) { editing = true; visible = true; reset(); updateSensors(); }
        else if (action == 20) { layout.setShown(selectedControl, !layout.shown(selectedControl)); saveLayout(); }
        else if (action == 21) {
            if (layout.duplicate(selectedControl) < 0)
                Toast.makeText(getContext(), "Maximum 64 controls.", Toast.LENGTH_SHORT).show();
            else saveLayout();
        } else if (action == 22) { layout.resetDefaults(); saveLayout(); menuPage = 7; menuOffset = 0; }
        else if (action == 30) { layout.rumbleEnabled = !layout.rumbleEnabled; cancelRumble(); saveLayout(); }
        else if (action == 31) { layout.gyroscopeEnabled = !layout.gyroscopeEnabled; updateSensors(); saveLayout(); }
        else if (action == 32) { layout.fpsCounter = !layout.fpsCounter; saveLayout(); }
        else if (action == 40 || action == 41) {
            float amount = 0.25f+Math.max(0, Math.min(1000, value))*3.75f/1000;
            if (action == 40) sensitivity = amount; else layout.gyroscopeSensitivity = amount;
            saveLayout();
        } else if (action == 42) {
            layout.setSize(selectedControl, 0.5f+Math.max(0, Math.min(1000, value))*1.5f/1000); saveLayout();
        } else { menuPage = action; menuOffset = 0; }
        revision++; publishMenu(); invalidate();
    }

    private void publishMenu() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> actions = new java.util.ArrayList<>(), values = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, Integer> row = (text, id) -> {
            labels.add(text); actions.add(id); values.add(-1);
        };
        String title = "Porting options";
        if (menuPage == 1) {
            row.accept("Overlay settings", 2); row.accept("Hardware", 3); row.accept("Cheats", 6);
        } else if (menuPage == 2) {
            title = "Overlay settings";
            row.accept("Edit buttons layout", 12); row.accept("Hide or add buttons", 7);
            row.accept("Edit buttons size", 8); row.accept("Look sensitivity", 4);
        } else if (menuPage == 3) {
            title = "Hardware";
            row.accept("Rumble: "+(vibrator == null || !vibrator.hasVibrator() ? "unavailable" : layout.rumbleEnabled ? "ON" : "OFF"),
                vibrator == null || !vibrator.hasVibrator() ? 0 : 30);
            row.accept("Gyroscope: "+(gyroscope == null ? "unavailable" : layout.gyroscopeEnabled ? "ON" : "OFF"), gyroscope == null ? 0 : 31);
            row.accept("Gyroscope sensitivity", 5); row.accept("FPS counter: "+(layout.fpsCounter ? "ON" : "OFF"), 32);
        } else if (menuPage == 4 || menuPage == 5 || menuPage == 12) {
            boolean size = menuPage == 12;
            title = size ? "Edit buttons size" : menuPage == 4 ? "Look sensitivity" : "Gyroscope sensitivity";
            float amount = size ? layout.sizeScale(selectedControl) : menuPage == 4 ? sensitivity : layout.gyroscopeSensitivity;
            row.accept(String.format(java.util.Locale.US, size ? "%s: %.0f%%" : "%s: %.2fx",
                size ? controlName(layout.type(selectedControl)) : title, size ? amount*100 : amount), size ? 42 : menuPage == 4 ? 40 : 41);
            values.set(0, Math.round((amount-(size ? 0.5f : 0.25f))/(size ? 1.5f : 3.75f)*1000));
        } else if (menuPage == 6) {
            title = "Cheats";
            for (int i = menuOffset; i < Math.min(CHEATS.length, menuOffset+MENU_PAGE_SIZE); i++) {
                int status = nativeCheatStatus(i);
                row.accept(CHEATS[i]+(status == -2 ? " ..." : status == -1 ? " (unavailable)" : i < 10 ? status == 1 ? " [ON]" : " [OFF]" : " (instant)"), 3000+i);
            }
        } else if (menuPage == 7 || menuPage == 8) {
            title = menuPage == 7 ? "Hide or add buttons" : "Edit buttons size";
            for (int i = menuOffset; i < Math.min(layout.size(), menuOffset+MENU_PAGE_SIZE); i++)
                row.accept(controlName(layout.type(i))+(i >= TouchLayout.BASE_COUNT ? " #"+(i+1) : "")+
                    (menuPage == 7 ? layout.shown(i) ? " [visible]" : " [hidden]" : " "+Math.round(layout.sizeScale(i)*100)+"%"), 1000+i);
        } else if (menuPage == 9) {
            title = controlName(layout.type(selectedControl));
            row.accept(layout.shown(selectedControl) ? "Hide button" : "Show button", 20);
            if (layout.type(selectedControl) != LEFT) row.accept("Duplicate button", 21);
            row.accept("Add button", 10); row.accept("Reset all buttons", 11);
        } else if (menuPage == 10) {
            title = "Add button";
            for (int i = menuOffset; i < Math.min(TouchLayout.BASE_COUNT, menuOffset+MENU_PAGE_SIZE); i++) row.accept(controlName(i), 2000+i);
        } else if (menuPage == 11) {
            title = "Restore defaults and remove copies?"; row.accept("Reset all buttons", 22);
        }
        if (menuPage == 6 || menuPage == 7 || menuPage == 8 || menuPage == 10) {
            int count = menuPage == 6 ? CHEATS.length : menuPage == 10 ? TouchLayout.BASE_COUNT : layout.size();
            if (menuOffset > 0) row.accept("Previous page", 92);
            if (menuOffset+MENU_PAGE_SIZE < count) row.accept("Next page", 91);
        }
        if (menuPage != 0) row.accept("Back", 90);
        int[] ids = new int[actions.size()], progress = new int[values.size()];
        for (int i = 0; i < ids.length; i++) { ids[i] = actions.get(i); progress[i] = values.get(i); }
        // Keep the revision stable for status refreshes so pending taps remain valid.
        nativeMenuPublish(revision, menuPage, editing, title, labels.toArray(new String[0]), ids, progress);
    }

    private boolean saveLayout() {
        boolean saved = preferences.edit().putString("configuration", exportLayout())
            .putFloat("look-sensitivity", sensitivity).putBoolean("swipe-layout", true).commit();
        if (!saved) Toast.makeText(getContext(), "Could not save layout. Try again.", Toast.LENGTH_LONG).show();
        return saved;
    }

    public String exportLayout() { return layout.exportConfiguration(sensitivity); }

    public void importLayout(String text) {
        // Parse and save the entire configuration before replacing the live controls.
        TouchLayout.Configuration imported = TouchLayout.importConfiguration(text);
        imported.layout.bounds(logicalWidth, logicalHeight);
        String normalized = imported.layout.exportConfiguration(imported.sensitivity);
        if (!preferences.edit().putString("configuration", normalized)
                .putFloat("look-sensitivity", imported.sensitivity).putBoolean("swipe-layout", true).commit())
            throw new IllegalArgumentException("Could not save imported layout");
        reset(); layout = imported.layout; sensitivity = imported.sensitivity;
        visible = true; updateSensors(); cancelRumble(); invalidate();
    }

    private String controlName(int type) { return type == LEFT ? "Move stick" : buttons[type].label; }

    public void startDeviceInput() {
        if (deviceInputActive) return;
        deviceInputActive = true;
        gyroAim.reset(); updateSensors(); post(rumblePoll); post(menuPoll);
    }

    public void stopDeviceInput() {
        deviceInputActive = false; removeCallbacks(rumblePoll); removeCallbacks(menuPoll);
        updateSensors(); cancelRumble(); reset();
    }

    private void updateSensors() {
        boolean needed = deviceInputActive && !menusActive && !editing && layout.gyroscopeEnabled && gyroscope != null;
        if (needed && !gyroRegistered) {
            gyroAim.reset();
            gyroRegistered = sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        } else if (!needed && gyroRegistered) {
            sensors.unregisterListener(this); gyroRegistered = false; gyroAim.reset();
        }
    }

    private void cancelRumble() {
        if (lastAmplitude != 0 && vibrator != null) {
            try { vibrator.cancel(); } catch (RuntimeException ignored) {}
        }
        lastAmplitude = 0;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!deviceInputActive || !layout.gyroscopeEnabled || editing || menusActive) {
            gyroAim.reset(); return;
        }
        int rotation = ((WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay().getRotation();
        if (gyroAim.sample(event.timestamp, event.values[0], event.values[1], rotation, gyroDelta))
            // The existing direct-look path accepts logical pixels (0.0022 radians per pixel).
            nativeLook(-gyroDelta[0]/0.0022f*layout.gyroscopeSensitivity, -gyroDelta[1]/0.0022f*layout.gyroscopeSensitivity);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void circle(Canvas canvas, float x, float y, float radius, String label, boolean active) {
        circle(canvas, x, y, radius, label, active, 11);
    }

    private void circle(Canvas canvas, float x, float y, float radius, String label, boolean active, float textSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0x9983d9ff : 0x55304050);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        paint.setColor(active ? 0xffaee7ff : 0xffffffff);
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(textSize);
        canvas.drawText(label, x, y + textSize*0.36f, paint);
    }

    private void stick(Canvas canvas, int axis, float x, float y, String label) {
        circle(canvas, x, y, layout.radius(LEFT), label, false, 11*layout.sizeScale(LEFT));
        circle(canvas, x + axes[axis]/32767f*layout.radius(LEFT), y + axes[axis+1]/32767f*layout.radius(LEFT),
               24*layout.sizeScale(LEFT), "", held(LEFT));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (layout.fpsCounter && !menusActive && !editing) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT); paint.setTextSize(18*scale);
            canvas.drawText("FPS: "+currentFps, insetLeft+12*scale, insetTop+24*scale, paint);
        }
        if (menusActive && !editing) return;
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale);
        if (!editing) circle(canvas, logicalWidth/2, toolbarY(), 28, visible ? "Hide" : "Touch", false);
        if (visible) {
            if (layout.shown(LEFT)) stick(canvas, 0, layout.x(LEFT), layout.y(LEFT), "Move");
            for (int i = 0; i < layout.size(); i++) {
                if (!layout.shown(i) || layout.type(i) == LEFT) continue;
                Button b = buttons[layout.type(i)];
                circle(canvas, layout.x(i), layout.y(i), layout.radius(i), b.label, held(i) || dragControl == i, 11*layout.sizeScale(i));
            }
        }
        editorButton(canvas);
        canvas.restore();
    }
}

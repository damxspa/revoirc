package io.mrarm.irc.config;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.mrarm.irc.setting.ListWithCustomSetting;

public class SettingsHelper implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String COMPACT_MODE_ALWAYS = "always";
    public static final String COMPACT_MODE_NEVER = "never";
    public static final String COMPACT_MODE_AUTO = "auto";

    public static final String SWIPE_DISABLED = "disabled";
    public static final String SWIPE_LEFT_TO_RIGHT = "left_to_right";
    public static final String SWIPE_RIGHT_TO_LEFT = "right_to_left";
    public static final String SWIPE_UP_TO_DOWN = "up_to_down";
    public static final String SWIPE_DOWN_TO_UP = "down_to_up";

    // Thread-safe singleton using AtomicReference
    private static final AtomicReference<SettingsHelper> INSTANCE = new AtomicReference<>();

    private static final Gson mGson = new Gson();

    /**
     * Gets the singleton instance, initializing if necessary.
     * Thread-safe: uses AtomicReference for lock-free initialization.
     * 
     * @param context Application context (must not be null on first call)
     * @return The SettingsHelper instance
     * @throws IllegalStateException if called with null context before initialization
     */
    public static SettingsHelper getInstance(Context context) {
        SettingsHelper instance = INSTANCE.get();
        if (instance != null) {
            return instance;
        }
        
        if (context == null) {
            throw new IllegalStateException("SettingsHelper not initialized: getInstance(null) called before initialization");
        }
        
        // Double-checked locking via AtomicReference compareAndSet
        SettingsHelper newInstance = new SettingsHelper(context.getApplicationContext());
        if (INSTANCE.compareAndSet(null, newInstance)) {
            return newInstance;
        } else {
            // Another thread initialized it first
            return INSTANCE.get();
        }
    }

    /**
     * Gets the singleton instance without initializing.
     * Returns null if not yet initialized.
     * 
     * @return The SettingsHelper instance or null if not initialized
     * @deprecated Use {@link #getInstanceOrThrow()} if you expect the instance to be initialized,
     *             or {@link #getInstance(Context)} to ensure initialization.
     */
    @Deprecated
    public static SettingsHelper getInstanceOrNull() {
        return INSTANCE.get();
    }

    /**
     * Gets the singleton instance, throwing if not initialized.
     * Preferred over getInstanceOrNull() when you expect the instance to exist.
     * 
     * @return The SettingsHelper instance
     * @throws IllegalStateException if not initialized
     */
    public static SettingsHelper getInstanceOrThrow() {
        SettingsHelper instance = INSTANCE.get();
        if (instance == null) {
            throw new IllegalStateException("SettingsHelper not initialized. Call getInstance(Context) first.");
        }
        return instance;
    }

    public static Gson getGson() {
        return mGson;
    }

    public static void deleteSQLiteDatabase(File path) {
        if (path.exists() && !path.delete()) {
            Log.w("SettingsHelper", "Failed to delete database file: " + path.getAbsolutePath());
        }
        File journal = new File(path.getParent(), path.getName() + "-journal");
        if (journal.exists() && !journal.delete()) {
             Log.w("SettingsHelper", "Failed to delete journal file: " + journal.getAbsolutePath());
        }
        File shm = new File(path.getParent(), path.getName() + "-shm");
        if (shm.exists() && !shm.delete()) {
             Log.w("SettingsHelper", "Failed to delete shm file: " + shm.getAbsolutePath());
        }
        File wal = new File(path.getParent(), path.getName() + "-wal");
        if (wal.exists() && !wal.delete()) {
             Log.w("SettingsHelper", "Failed to delete wal file: " + wal.getAbsolutePath());
        }
    }

    private final Context mContext;
    private final SharedPreferences mPreferences;

    private static final Map<String, List<SettingChangeCallback>> sListeners = new HashMap<>();

    public SettingsHelper(Context context) {
        mContext = context;
        mPreferences = context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        mPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    public static SharedPreferences getPreferences() {
        SettingsHelper instance = INSTANCE.get();
        return instance != null ? instance.mPreferences : null;
    }

    public static Context getContext() {
        SettingsHelper instance = INSTANCE.get();
        return instance != null ? instance.mContext : null;
    }

    public List<File> getCustomFiles() {
        List<File> ret = new ArrayList<>();
        String font = mPreferences.getString(ChatSettings.PREF_FONT, "default");
        if (ListWithCustomSetting.isPrefCustomValue(font))
            ret.add(ListWithCustomSetting.getCustomFile(mContext, ChatSettings.PREF_FONT, font));
        return ret;
    }

    public void clear() {
        for (File file : getCustomFiles()) {
            if (file.exists())
                file.delete();
        }
        mPreferences.edit().clear().apply();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        synchronized (sListeners) {
            List<SettingChangeCallback> listeners = sListeners.get(key);
            if (listeners != null) {
                for (SettingChangeCallback l : listeners)
                    l.onSettingChanged(key);
            }
        }
    }

    public static Object getDefaultValue(String key) {
        Object o = AppSettings.getDefaultValue(key);
        if (o != null)
            return o;
        o = ChatSettings.getDefaultValue(key);
        if (o != null)
            return o;
        o = NickAutocompleteSettings.getDefaultValue(key);
        if (o != null)
            return o;
        return null;
    }


    /**
     * Gets a long value from SharedPreferences with robust type coercion.
     * Handles type mismatches that can occur after backup restoration (JSON doesn't
     * differentiate between number types, and strings may be stored as numbers).
     *
     * @param prefs SharedPreferences instance
     * @param key Preference key
     * @param def Default value if key not found or conversion fails
     * @return The long value or default
     */
    static long getLong(SharedPreferences prefs, String key, long def) {
        if (!prefs.contains(key)) {
            return def;
        }
        
        try {
            // Fast path - try direct getLong
            return prefs.getLong(key, def);
        } catch (ClassCastException e) {
            // Type mismatch - try other types
            Log.w("SettingsHelper", "Type coercion for key '" + key + "': " + e.getMessage());
        }
        
        // Try int (common after JSON restore where longs become ints)
        try {
            return prefs.getInt(key, (int) def);
        } catch (ClassCastException ignored) {}
        
        // Try float
        try {
            return (long) prefs.getFloat(key, def);
        } catch (ClassCastException ignored) {}
        
        // Try boolean (false=0, true=1)
        try {
            return prefs.getBoolean(key, false) ? 1L : 0L;
        } catch (ClassCastException ignored) {}
        
        // Try string parsing
        try {
            String stringValue = prefs.getString(key, null);
            if (stringValue != null) {
                return Long.parseLong(stringValue);
            }
        } catch (NumberFormatException | ClassCastException ignored) {}
        
        Log.w("SettingsHelper", "Failed to coerce key '" + key + "' to long, using default: " + def);
        return def;
    }


    public static ListenerHandle changeEvent() {
        return new ListenerHandle();
    }


    public interface SettingChangeCallback {
        void onSettingChanged(String name);
    }

    public static class ListenerHandle {

        private final Map<String, List<SettingChangeCallback>> mCallbacks = new HashMap<>();

        private ListenerHandle() {
        }

        public ListenerHandle cancel() {
            synchronized (sListeners) {
                for (Map.Entry<String, List<SettingChangeCallback>> e : mCallbacks.entrySet()) {
                    List<SettingChangeCallback> globalList = sListeners.get(e.getKey());
                    if (globalList == null)
                        continue;
                    globalList.removeAll(e.getValue());
                }
            }
            mCallbacks.clear();
            return this;
        }

        public ListenerHandle listen(String property, SettingChangeCallback cb) {
            synchronized (sListeners) {
                List<SettingChangeCallback> globalList = sListeners.get(property);
                if (globalList == null) {
                    globalList = new ArrayList<>();
                    sListeners.put(property, globalList);
                }
                globalList.add(cb);
            }
            List<SettingChangeCallback> localList = sListeners.get(property);
            if (localList == null) {
                localList = new ArrayList<>();
                sListeners.put(property, localList);
            }
            localList.add(cb);
            return this;
        }

        public ListenerHandle listen(String property, Runnable cb) {
            listen(property, (c) -> cb.run());
            return this;
        }

        public ListenerHandle listen(String[] properties, SettingChangeCallback cb) {
            for (String property : properties)
                listen(property, cb);
            return this;
        }

        public ListenerHandle listen(String[] properties, Runnable cb) {
            listen(properties, (c) -> cb.run());
            return this;
        }

    }


    private static final HashMap<Class<?>, ClassCallbackInfo> sCallbacks = new HashMap<>();
    private static final WeakHashMap<Object, WeakReference<ListenerHandle>> sRegisteredListeners = new WeakHashMap<>();
    private static final Handler sUiHandler = new Handler(Looper.getMainLooper());

    private static synchronized ClassCallbackInfo resolveCallbacks(Class<?> c) {
        ClassCallbackInfo i = sCallbacks.get(c);
        if (i != null)
            return i;
        i = new ClassCallbackInfo();
        sCallbacks.put(c, i);
        for (Method m : c.getDeclaredMethods()) {
            m.setAccessible(true);
            io.mrarm.irc.config.SettingChangeCallback a =
                    m.getAnnotation(io.mrarm.irc.config.SettingChangeCallback.class);
            if (a != null)
                i.methods.add(new ClassCallbackInfo.MethodInfo(m, a.keys(), false));

            UiSettingChangeCallback a2 =
                    m.getAnnotation(UiSettingChangeCallback.class);
            if (a2 != null)
                i.methods.add(new ClassCallbackInfo.MethodInfo(m, a2.keys(), true));
        }
        return i;
    }

    public static synchronized void registerCallbacks(Object o) {
        if (sRegisteredListeners.containsKey(o))
            return;
        ClassCallbackInfo c = resolveCallbacks(o.getClass());
        ListenerHandle handle = changeEvent();
        for (ClassCallbackInfo.MethodInfo m : c.methods) {
            SettingChangeCallback cb = m.createCallback(o);
            for (String k : m.keys)
                handle.listen(k, cb);
        }
        sRegisteredListeners.put(o, new WeakReference<>(handle));
    }

    public static synchronized void unregisterCallbacks(Object o) {
        WeakReference<ListenerHandle> h = sRegisteredListeners.remove(o);
        if (h != null) {
            ListenerHandle hh = h.get();
            if (hh != null)
                hh.cancel();
        }
    }

    private static class ClassCallbackInfo {

        private static class MethodInfo {
            private final Method method;
            private final String[] keys;
            private final boolean onUi;

            private MethodInfo(Method method, String[] keys, boolean onUi) {
                this.method = method;
                this.keys = keys;
                this.onUi = onUi;
            }

            private SettingChangeCallback createBaseCallback(Object o) {
                if (this.method.getParameterTypes().length > 0) {
                    return (n) -> {
                        try {
                            this.method.invoke(o, n);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
                } else {
                    return (n) -> {
                        try {
                            this.method.invoke(o);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
                }
            }

            private SettingChangeCallback createCallback(Object o) {
                if (onUi) {
                    SettingChangeCallback base = createBaseCallback(o);
                    return (n) -> {
                        if (Looper.getMainLooper().getThread() == Thread.currentThread())
                            base.onSettingChanged(n);
                        else
                            sUiHandler.post(() -> base.onSettingChanged(n));
                    };
                } else {
                    return createBaseCallback(o);
                }
            }
        }

        private final List<MethodInfo> methods = new ArrayList<>();

    }
}
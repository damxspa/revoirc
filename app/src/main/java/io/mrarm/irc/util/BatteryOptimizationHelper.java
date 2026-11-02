package io.mrarm.irc.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.mrarm.irc.R;
import io.mrarm.irc.config.AppSettings;

/**
 * Helper for managing battery optimization settings and low power mode.
 * <p>
 * Note: Using {@link Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} may be subject to
 * Play Store policy restrictions. This app uses it to allow background message reception,
 * which is a core functionality. Ensure you have a valid reason if you use this in your app.
 */
public class BatteryOptimizationHelper {

    public static final String PREF_BATTERY_OPTIMIZATION_LAST_SHOWN = "battery_optimization_dialog_last_shown";
    public static final String PREF_BATTERY_OPTIMIZATION_SHOWN = "battery_optimization_dialog_shown"; // Deprecated
    public static final String PREF_BATTERY_OPTIMIZATION_WAS_ENABLED = "battery_optimization_was_enabled";

    private static final String PREFS_NAME = "battery_optimization_prefs";
    private static final long REPROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    /**
     * Check if the application is being ignored by battery optimizations.
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return false;
        }
        String packageName = context.getPackageName();
        return pm.isIgnoringBatteryOptimizations(packageName);
    }

    /**
     * Check if low power mode is enabled.
     */
    public static boolean isLowPowerModeEnabled() {
        return AppSettings.isLowPowerModeEnabled();
    }

    /**
     * Returns the polling interval in seconds. If low power mode is enabled, returns a longer interval.
     * @return polling interval in seconds
     */
    public static int getPollingInterval() {
        if (isLowPowerModeEnabled()) {
            return 300; // 5 minutes
        } else {
            return 30; // 30 seconds
        }
    }

    /**
     * Shows a dialog to request disabling battery optimizations.
     */
    public static void showBatteryOptimizationDialog(Context context) {
        if (!(context instanceof AppCompatActivity)) {
            return;
        }

        AppCompatActivity activity = (AppCompatActivity) context;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        // Translated text according to the reference image
        builder.setTitle(R.string.battery_optimization_title);
        builder.setMessage(R.string.battery_optimization_message);

        builder.setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss());

        builder.setPositiveButton(R.string.action_configure, (dialog, which) -> launchBatteryOptimizationSettings(activity));

        builder.setCancelable(false);
        builder.show();
    }

    /**
     * Launches the Intent to access battery optimization settings.
     * Supports multiple device manufacturers.
     */
    private static void launchBatteryOptimizationSettings(Context context) {
        String packageName = context.getPackageName();

        // Specific support for OriginOS / Funtouch OS (Vivo) for auto-start and background permissions
        try {
            Intent vivoIntent = new Intent();
            vivoIntent.setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity");
            vivoIntent.putExtra("packagename", packageName);
            if (vivoIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(vivoIntent);
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Intent vivoIntent2 = new Intent();
            vivoIntent2.setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity");
            if (vivoIntent2.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(vivoIntent2);
                return;
            }
        } catch (Exception ignored) {
        }

        // Standard Android Intent (requires API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                batteryIntent.setData(Uri.parse("package:" + packageName));

                if (batteryIntent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(batteryIntent);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // Try to open the app settings directly
        try {
            Intent detailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            detailsIntent.setData(Uri.parse("package:" + packageName));

            if (detailsIntent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(detailsIntent);
                return;
            }
        } catch (Throwable ignored) {
        }

        // General battery settings (requires API 22+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                Intent saverIntent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
                if (saverIntent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(saverIntent);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Checks if the optimization dialog has been shown recently.
     */
    public static boolean hasShownBatteryOptimizationDialogRecently(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastShown = prefs.getLong(PREF_BATTERY_OPTIMIZATION_LAST_SHOWN, 0);
        
        // Backward compatibility: if the old boolean flag was set, but no timestamp exists,
        // we set the timestamp to the current time to avoid immediate re-prompting
        if (lastShown == 0 && prefs.getBoolean(PREF_BATTERY_OPTIMIZATION_SHOWN, false)) {
            setBatteryOptimizationDialogShown(context);
            return true;
        }

        return (System.currentTimeMillis() - lastShown) < REPROMPT_INTERVAL_MS;
    }

    /**
     * Marks that the battery optimization dialog has been shown at the current time.
     */
    public static void setBatteryOptimizationDialogShown(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(PREF_BATTERY_OPTIMIZATION_LAST_SHOWN, System.currentTimeMillis())
                .putBoolean(PREF_BATTERY_OPTIMIZATION_SHOWN, true)
                .commit();
    }

    /**
     * Resolves the full flow: checks permissions and shows the dialog if necessary.
     * This method should be called from MainActivity.
     */
    public static void checkAndRequestBatteryOptimizations(Context context) {
        checkAndRequestBatteryOptimizations(context, false);
    }

    /**
     * Resolves the full flow with an option to force the check.
     */
    public static void checkAndRequestBatteryOptimizations(Context context, boolean force) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean wasEnabled = prefs.getBoolean(PREF_BATTERY_OPTIMIZATION_WAS_ENABLED, false);
        boolean currentlyEnabled = isIgnoringBatteryOptimizations(context);

        // If already granted, mark as "was enabled" and do nothing else
        if (currentlyEnabled) {
            if (!wasEnabled) {
                prefs.edit().putBoolean(PREF_BATTERY_OPTIMIZATION_WAS_ENABLED, true).commit();
            }
            if (force) {
                // If forced and already ignoring, we just show the system settings to let the user see it
                launchBatteryOptimizationSettings(context);
            }
            return;
        }

        // If not granted, but it was previously (RESET)
        if (wasEnabled) {
            // Show the dialog immediately (ignore 7-day cooldown)
            showBatteryOptimizationDialog(context);
            setBatteryOptimizationDialogShown(context);
            // Resetear "was enabled" para que no sea molesto en cada inicio si deciden no activarlo
            prefs.edit().putBoolean(PREF_BATTERY_OPTIMIZATION_WAS_ENABLED, false).commit();
            return;
        }

        // If the dialog was shown recently and it's not a forced check, don't show it again
        if (!force && hasShownBatteryOptimizationDialogRecently(context)) {
            return;
        }

        // Show the dialog
        showBatteryOptimizationDialog(context);
        setBatteryOptimizationDialogShown(context);
    }
}
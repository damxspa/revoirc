package io.mrarm.irc;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.util.theme.ThemeManager;

public class ThemedActivity extends AppCompatActivity implements ThemeManager.ThemeChangeListener {

    private boolean mThemeChanged;
    private int mDarkMode;
    private LocaleListCompat mCurrentLocales;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager helper = ThemeManager.getInstance(this);
        helper.addThemeChangeListener(this);
        mDarkMode = AppCompatDelegate.getDefaultNightMode();
        super.onCreate(savedInstanceState);
        mCurrentLocales = ConfigurationCompat.getLocales(getResources().getConfiguration());

        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        updateStatusBarStyle();
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                    getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightNavigationBars(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusBarStyle();
    }

    public void updateStatusBarStyle() {
        int colorPrimaryDark = StyledAttributesHelper.getColor(this, R.attr.colorPrimaryDark, 0);

        String statusBarStyle = AppSettings.getStatusBarStyle();
        if (statusBarStyle == null)
            statusBarStyle = "dark";

        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        if ("light".equals(statusBarStyle)) {
            getWindow().setStatusBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                controller.setAppearanceLightStatusBars(true);
            }
        } else {
            getWindow().setStatusBarColor(colorPrimaryDark);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                controller.setAppearanceLightStatusBars(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        ThemeManager.getInstance(this).removeThemeChangeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mThemeChanged)
            recreate();
    }

    public boolean hasThemeChanged() {
        return mThemeChanged;
    }

    @Override
    public void setTheme(int resid) {
        ThemeManager helper = ThemeManager.getInstance(this);
        helper.applyThemeToActivity(this);
        super.setTheme(helper.getThemeIdToApply(resid));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        newConfig = new Configuration(newConfig);
        if (AppCompatDelegate.getDefaultNightMode() == mDarkMode) {
            if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
                newConfig.uiMode &= ~(Configuration.UI_MODE_NIGHT_MASK);
                newConfig.uiMode |= Configuration.UI_MODE_NIGHT_YES;
            }
        }
        ThemeManager helper = ThemeManager.getInstance(this);
        helper.applyThemeToActivity(this);
        super.onConfigurationChanged(newConfig);

        LocaleListCompat newLocales = ConfigurationCompat.getLocales(newConfig);
        if (!newLocales.equals(mCurrentLocales)) {
            mCurrentLocales = newLocales;
            recreate();
        }
    }

    @Override
    public void recreate() {
        super.recreate();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onThemeChanged() {
        mThemeChanged = true;
    }

}

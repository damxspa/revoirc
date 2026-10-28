package io.mrarm.irc;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.core.content.FileProvider;
import android.view.MenuItem;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.mrarm.irc.setting.SettingsCategoriesFragment;
import io.mrarm.irc.setting.fragment.CommandSettingsFragment;
import io.mrarm.irc.setting.fragment.InterfaceSettingsFragment;
import io.mrarm.irc.setting.fragment.NamedSettingsFragment;
import io.mrarm.irc.setting.fragment.NotificationSettingsFragment;
import io.mrarm.irc.setting.fragment.ReconnectSettingsFragment;
import io.mrarm.irc.setting.fragment.StorageSettingsFragment;
import io.mrarm.irc.setting.fragment.UserSettingsFragment;
import io.mrarm.irc.setup.BackupActivity;
import io.mrarm.irc.util.ChatExportManager;
import io.mrarm.irc.util.SimpleCounter;

public class SettingsActivity extends ThemedActivity {

    private SimpleCounter mRequestCodeCounter = new SimpleCounter(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        getSupportFragmentManager().addOnBackStackChangedListener(this::updateTitle);
        if (getSupportFragmentManager().findFragmentById(R.id.content_frame) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, CategoriesFragment.newInstance())
                    .commit();
        }
        getSupportFragmentManager().addFragmentOnAttachListener((fragmentManager, fragment) -> updateTitle());
    }

    public SimpleCounter getRequestCodeCounter() {
        return mRequestCodeCounter;
    }

    public void updateTitle() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (fragment == null)
            return;
        if (getSupportActionBar() != null) {
            if (fragment instanceof NamedSettingsFragment)
                getSupportActionBar().setTitle(((NamedSettingsFragment) fragment).getName());
            else
                getSupportActionBar().setTitle(R.string.title_activity_settings);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void setFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .replace(R.id.content_frame, fragment)
                .addToBackStack(null)
                .commit();
    }

    public void exportChatLogs(ChatExportManager.ExportFormat format) {
        exportChatLogs(format, false);
    }

    public void exportChatLogs(ChatExportManager.ExportFormat format, boolean channelsOnly) {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.chat_export_progress));
        progressDialog.setIndeterminate(false);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        progressDialog.show();

        ChatExportManager.exportChatLogs(this, null, format, channelsOnly, new ChatExportManager.ExportCallback() {
            @Override
            public void onExportCompleted(File zipFile) {
                progressDialog.dismiss();
                shareFile(zipFile);
            }

            @Override
            public void onExportFailed(Exception e) {
                progressDialog.dismiss();
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.chat_export_error)
                        .setMessage(e.getMessage())
                        .setPositiveButton(R.string.action_ok, null)
                        .show();
            }

            @Override
            public void onProgressUpdate(int progress, int total) {
                progressDialog.setMax(total);
                progressDialog.setProgress(progress);
            }
        });
    }

    private void shareFile(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)));
    }

    public void showLanguageSelectionDialog() {
        final String[] languages = {
                "Default", "Dansk", "Deutsch", "English", "Español", "Suomi",
                "Français", "Norsk bokmål", "Polski", "Português", "Svenska",
                "简体中文", "繁體中文", "日本語", "한국어"
        };
        final String[] tags = {
                "", "da", "de", "en", "es", "fi", "fr", "nb", "pl", "pt", "sv",
                "zh-CN", "zh-TW", "ja", "ko"
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.pref_header_language)
                .setItems(languages, (dialog, which) -> {
                    if (which == 0) {
                        // Limpia la preferencia y vuelve al sistema
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
                    } else {
                        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(tags[which]);
                        AppCompatDelegate.setApplicationLocales(appLocale);
                    }
                })
                .show();
    }

    public static class CategoriesFragment extends SettingsCategoriesFragment {

        public static CategoriesFragment newInstance() {
            return new CategoriesFragment();
        }

        @Override
        public List<Item> getItems() {
            List<Item> ret = new ArrayList<>();
            ret.add(new Item(R.string.pref_header_user, R.drawable.ic_user, UserSettingsFragment.class));
            ret.add(new Item(R.string.pref_header_reconnect, R.drawable.ic_refresh, ReconnectSettingsFragment.class));
            ret.add(new Item(R.string.pref_header_interface, R.drawable.ic_appearance, InterfaceSettingsFragment.class));
            ret.add(new Item(R.string.pref_header_notifications, R.drawable.ic_notifications, NotificationSettingsFragment.class));
            ret.add(new Item(R.string.pref_header_command_aliases, R.drawable.ic_keyboard, CommandSettingsFragment.class));
            ret.add(new Item(R.string.pref_header_backup, R.drawable.ic_settings_backup, (View v) -> {
                v.getContext().startActivity(new Intent(v.getContext(), BackupActivity.class));
            }));
            ret.add(new Item(R.string.pref_header_language, R.drawable.ic_translate, (View v) -> {
                ((SettingsActivity) v.getContext()).showLanguageSelectionDialog();
            }));
            ret.add(new Item(R.string.pref_title_clear_all_data, R.drawable.ic_delete, (View v) -> {
                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.pref_title_clear_all_data)
                        .setMessage(R.string.pref_clear_all_data_confirm)
                        .setPositiveButton(R.string.action_delete, (DialogInterface di, int i) -> {
                            StorageSettingsAdapter.clearAllAppData(v.getContext());
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }));
            ret.add(new Item(R.string.pref_header_storage, R.drawable.ic_storage, StorageSettingsFragment.class));
            ret.add(new Item(R.string.action_export_logs_txt, R.drawable.ic_history, (View v) -> {
                ((SettingsActivity) v.getContext()).exportChatLogs(ChatExportManager.ExportFormat.TXT);
            }, (View v) -> {
                ((SettingsActivity) v.getContext()).exportChatLogs(ChatExportManager.ExportFormat.TXT, true);
                return true;
            }));
            ret.add(new Item(R.string.action_export_logs_html, R.drawable.ic_history, (View v) -> {
                ((SettingsActivity) v.getContext()).exportChatLogs(ChatExportManager.ExportFormat.HTML);
            }, (View v) -> {
                ((SettingsActivity) v.getContext()).exportChatLogs(ChatExportManager.ExportFormat.HTML, true);
                return true;
            }));
            return ret;
        }

    }





}

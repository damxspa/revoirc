package io.mrarm.irc.setting.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.MenuProvider;
import androidx.core.widget.CompoundButtonCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;

import io.mrarm.chatlib.dto.MessageInfo;
import io.mrarm.chatlib.dto.MessageSenderInfo;
import io.mrarm.irc.MessageFormatSettingsActivity;
import io.mrarm.irc.R;
import io.mrarm.irc.SettingsActivity;
import io.mrarm.irc.ThemeEditorActivity;
import io.mrarm.irc.ThemedActivity;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.config.NickAutocompleteSettings;
import io.mrarm.irc.dialog.MenuBottomSheetDialog;
import io.mrarm.irc.setting.CheckBoxSetting;
import io.mrarm.irc.setting.ClickableSetting;
import io.mrarm.irc.setting.FontSizeSetting;
import io.mrarm.irc.setting.ListSetting;
import io.mrarm.irc.setting.ListWithCustomSetting;
import io.mrarm.irc.setting.RadioButtonSetting;
import io.mrarm.irc.setting.SettingsHeader;
import io.mrarm.irc.setting.SettingsListAdapter;
import io.mrarm.irc.util.EntryRecyclerViewAdapter;
import io.mrarm.irc.util.MessageBuilder;
import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.util.theme.ThemeInfo;
import io.mrarm.irc.util.theme.ThemeManager;
import io.mrarm.irc.util.BatteryOptimizationHelper;

public class InterfaceSettingsFragment extends SettingsListFragment
        implements NamedSettingsFragment {

    private ClickableSetting mMessageFormatItem;
    private MessageInfo mSampleMessage;
    private ClickableSetting mAutocompleteItem;

    private final ActivityResultLauncher<Intent> mThemeEditorLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // Theme editor doesn't return anything specific, just update UI
                recreateAdapter();
            }
    );

    private final ActivityResultLauncher<Intent> mImportThemeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null)
                    return;
                Uri uri = result.getData().getData();
                Activity activity = getActivity();
                if (activity == null)
                    return;
                new Thread(() -> {
                    try (ParcelFileDescriptor desc = activity.getContentResolver().openFileDescriptor(uri, "r")) {
                        if (desc != null) {
                            try (BufferedReader re = new BufferedReader(new FileReader(desc.getFileDescriptor()))) {
                                ThemeManager.getInstance(activity).importTheme(re);
                                activity.runOnUiThread(this::recreateAdapter);
                            }
                        }
                    } catch (Exception e) {
                        Log.w("InterfaceSettings", "Failed to import theme", e);
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, R.string.error_generic, Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
    );

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_settings_interface, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_import_theme) {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    mImportThemeLauncher.launch(intent);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override
    public String getName() {
        return getString(R.string.pref_header_interface);
    }

    @Override
    public SettingsListAdapter createAdapter() {
        SettingsListAdapter a = new SettingsListAdapter(this);
        SharedPreferences prefs = requireContext().getSharedPreferences(
                requireContext().getPackageName() + "_preferences", Context.MODE_PRIVATE);
        a.add(new SettingsHeader(getString(R.string.pref_header_theme)));
        a.add(new ListSetting(getString(R.string.pref_title_status_bar_style),
                new String[] {
                        getString(R.string.pref_value_status_bar_dark),
                        getString(R.string.pref_value_status_bar_light)
                },
                new String[] {
                        "dark",
                        "light"
                })
                .linkSetting(prefs, AppSettings.PREF_STATUS_BAR_STYLE)
                .addListener((EntryRecyclerViewAdapter.Entry entry) -> {
                    Activity activity = getActivity();
                    if (activity instanceof ThemedActivity)
                        ((ThemedActivity) activity).updateStatusBarStyle();
                }));
        a.add(new SettingsHeader(getString(R.string.pref_header_chat)));
        a.add(new ListWithCustomSetting(a, getString(R.string.pref_title_font),
                getResources().getStringArray(R.array.pref_entries_font),
                getResources().getStringArray(R.array.pref_entry_values_font), null,
                ChatSettings.PREF_FONT, ListWithCustomSetting.TYPE_FONT)
                .linkSetting(prefs, ChatSettings.PREF_FONT));
        a.add(new FontSizeSetting(getString(R.string.pref_title_font_size))
                .linkSetting(prefs, ChatSettings.PREF_FONT_SIZE));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_hide_join_part),
                getString(R.string.pref_summary_hide_join_part))
                .linkSetting(prefs, ChatSettings.PREF_HIDE_JOIN_PART_MESSAGES));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_autocorrect),
                getString(R.string.pref_summary_autocorrect))
                .linkSetting(prefs, ChatSettings.PREF_TEXT_AUTOCORRECT_ENABLED));
        a.add(new ListSetting(getString(R.string.pref_title_appbar_compact_mode),
                getResources().getStringArray(R.array.pref_entries_appbar_compact_mode),
                getResources().getStringArray(R.array.pref_entry_values_appbar_compact_mode))
                .linkSetting(prefs, ChatSettings.PREF_APPBAR_COMPACT_MODE));
        mMessageFormatItem = new ClickableSetting(getString(R.string.pref_title_message_format), null)
                .setIntent(new Intent(requireActivity(), MessageFormatSettingsActivity.class));
        a.add(mMessageFormatItem);
        mAutocompleteItem = new ClickableSetting(getString(R.string.pref_title_nick_autocomplete), null)
                .setOnClickListener((View v) -> {
                    if (requireActivity() instanceof SettingsActivity) {
                        ((SettingsActivity) requireActivity()).setFragment(new AutocompletePreferenceFragment());
                    }
                });
        a.add(mAutocompleteItem);
        a.add(new CheckBoxSetting(getString(R.string.pref_title_chat_box_always_multiline),
                getString(R.string.pref_summary_chat_box_always_multiline))
                .linkSetting(prefs, ChatSettings.PREF_SEND_BOX_ALWAYS_MULTILINE));
        a.add(new ListSetting(getString(R.string.pref_title_chat_box_history_swipe_mode),
                getResources().getStringArray(R.array.pref_entries_chat_history_swipe_mode),
                getResources().getStringArray(R.array.pref_entry_values_chat_history_swipe_mode))
                .linkSetting(prefs, ChatSettings.PREF_SEND_BOX_HISTORY_SWIPE_MODE));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_chat_multi_scroll_mode),
                getString(R.string.pref_summary_chat_multi_scroll_mode))
                .linkSetting(prefs, ChatSettings.PREF_ONLY_MULTI_SELECT_MODE));

        a.add(new SettingsHeader(getString(R.string.pref_header_misc)));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_drawer_always_show_server),
                getString(R.string.pref_summary_drawer_always_show_server))
                .linkSetting(prefs, AppSettings.PREF_DRAWER_ALWAYS_SHOW_SERVER));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_close_private_quit),
                getString(R.string.pref_summary_close_private_quit))
                .linkSetting(prefs, AppSettings.PREF_CLOSE_PRIVATE_QUIT));
        a.add(new CheckBoxSetting(getString(R.string.pref_title_notify_private_quit),
                getString(R.string.pref_summary_notify_private_quit))
                .linkSetting(prefs, AppSettings.PREF_NOTIFY_PRIVATE_QUIT));
        try {
            a.add(new ListSetting(getString(R.string.pref_title_private_message_delay),
                    new String[]{
                            getString(R.string.pref_value_delay_disabled),
                            getString(R.string.pref_value_delay_5s),
                            getString(R.string.pref_value_delay_10s),
                            getString(R.string.pref_value_delay_30s),
                            getString(R.string.pref_value_delay_60s)
                    },
                    new String[]{
                            "0",
                            "5",
                            "10",
                            "30",
                            "60"
                    })
                    .linkSetting(prefs, AppSettings.PREF_PRIVATE_MESSAGE_DELAY));
        } catch (Exception e) {
            Log.e("InterfaceSettings", "Error adding message delay setting", e);
        }
        a.add(new CheckBoxSetting(getString(R.string.pref_title_chat_show_dcc_send),
                getString(R.string.pref_summary_chat_show_dcc_send))
                .linkSetting(prefs, ChatSettings.PREF_SHOW_DCC_SEND)
                .addListener((EntryRecyclerViewAdapter.Entry entry) -> {
                    boolean checked = ((CheckBoxSetting) entry).isChecked();
                    if (checked)
                        showDCCWarning((CheckBoxSetting) entry);
                }));
        a.add(new ClickableSetting(getString(R.string.pref_title_check_battery_optimization),
                getString(R.string.pref_summary_check_battery_optimization))
                .setOnClickListener((View v) -> BatteryOptimizationHelper.checkAndRequestBatteryOptimizations(requireContext())));

        MessageSenderInfo testSender = new MessageSenderInfo(
                getString(R.string.message_example_sender), "", "", null, null);
        Date date = MessageFormatSettingsActivity.getSampleMessageTime();
        mSampleMessage = new MessageInfo(testSender, date,
                getString(R.string.message_example_message), MessageInfo.MessageType.NORMAL);
        return a;
    }


    @Override
    public void onStart() {
        super.onStart();
        try {
            Activity activity = requireActivity();
            if (activity instanceof ThemedActivity && ((ThemedActivity) activity).hasThemeChanged()) {
                activity.recreate();
            }
        } catch (Exception e) {
            Log.w("InterfaceSettings", "Error checking theme change", e);
        }
    }

    private int[] getBaseThemeColors(int resId) {
        int[] colors = new int[3];
        StyledAttributesHelper attrs = StyledAttributesHelper.obtainStyledAttributes(requireContext(),
                resId, new int[] { R.attr.colorPrimary, R.attr.colorPrimaryDark,
                        R.attr.colorAccent });
        colors[0] = attrs.getColor(R.attr.colorPrimary, 0);
        colors[1] = attrs.getColor(R.attr.colorPrimaryDark, 0);
        colors[2] = attrs.getColor(R.attr.colorAccent, 0);
        attrs.recycle();
        return colors;
    }

    private void createThemeList(SettingsListAdapter a) {
        ThemeManager themeManager = ThemeManager.getInstance(requireContext());
        RadioButtonSetting.Group themeGroup = new RadioButtonSetting.Group();
        SettingsListAdapter.SettingChangedListener recreateCb =
                (EntryRecyclerViewAdapter.Entry e) -> {
                    if (getActivity() != null && ((CheckBoxSetting) e).isChecked())
                        getActivity().recreate();
                };
        for (ThemeManager.BaseTheme theme : themeManager.getBaseThemes()) {
            int themeResId = theme.getThemeResId();
            a.add(new ThemeOptionSetting(getString(theme.getNameResId()),
                    themeGroup, getBaseThemeColors(themeResId))
                    .linkBaseTheme(this, theme).addListener(recreateCb));
        }
        for (ThemeInfo theme : themeManager.getCustomThemes()) {
            int[] colors = getBaseThemeColors(theme.baseThemeInfo.getThemeResId());
            Integer primaryColor = theme.colors.get(ThemeInfo.COLOR_PRIMARY);
            if (primaryColor != null)
                colors[0] = primaryColor;
            Integer primaryDarkColor = theme.colors.get(ThemeInfo.COLOR_PRIMARY_DARK);
            if (primaryDarkColor != null)
                colors[1] = primaryDarkColor;
            Integer accentColor = theme.colors.get(ThemeInfo.COLOR_ACCENT);
            if (accentColor != null)
                colors[2] = accentColor;
            a.add(new ThemeOptionSetting(theme.name, themeGroup, colors)
                    .linkCustomTheme(this, theme).addListener(recreateCb));
        }
    }

    private void openThemeEditor(ThemeInfo theme) {
        Intent intent = new Intent(requireContext(), ThemeEditorActivity.class);
        intent.putExtra(ThemeEditorActivity.ARG_THEME_UUID, theme.uuid.toString());
        mThemeEditorLauncher.launch(intent);
    }

    private ThemeInfo createNewTheme() {
        ThemeManager themeManager = ThemeManager.getInstance(requireContext());
        ThemeInfo currentCustomTheme = themeManager.getCurrentCustomTheme();
        if (currentCustomTheme != null) {
            return createNewTheme(currentCustomTheme);
        } else {
            ThemeManager.ThemeResInfo themeRes = themeManager.getCurrentTheme();
            ThemeInfo newTheme = new ThemeInfo();
            if (themeRes instanceof ThemeManager.BaseTheme) {
                ThemeManager.BaseTheme baseTheme = (ThemeManager.BaseTheme) themeRes;
                newTheme.base = baseTheme.getId();
            } else {
                newTheme.base = themeManager.getFallbackTheme().getId();
            }
            newTheme.name = getString(R.string.theme_custom_default_name);
            initNewTheme(newTheme);
            return newTheme;
        }
    }

    private ThemeInfo createNewTheme(ThemeInfo theme) {
        ThemeInfo newTheme = new ThemeInfo();
        newTheme.copyFrom(theme);
        newTheme.name = getString(R.string.value_copy, theme.name);
        initNewTheme(newTheme);
        return newTheme;
    }

    private ThemeInfo createNewTheme(ThemeManager.BaseTheme baseTheme) {
        ThemeInfo newTheme = new ThemeInfo();
        newTheme.base = baseTheme.getId();
        newTheme.name = getString(R.string.theme_custom_default_name);
        initNewTheme(newTheme);
        return newTheme;
    }

    private void initNewTheme(ThemeInfo newTheme) {
        Context context = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                // Create a deep copy of the theme to avoid modifying the original
                // which might be in use as the current theme
                ThemeInfo themeToSave = new ThemeInfo();
                themeToSave.copyFrom(newTheme);
                // Deep copy the colors map to prevent shared mutable state
                // Ensure colors map is never null
                if (newTheme.colors != null) {
                    themeToSave.colors = new HashMap<>(newTheme.colors);
                } else {
                    themeToSave.colors = new HashMap<>();
                }
                ThemeManager.getInstance(context).saveTheme(themeToSave);
            } catch (IOException e) {
                Log.w("InterfaceSettings", "Failed to save new theme", e);
            } catch (NullPointerException e) {
                Log.w("InterfaceSettings", "Failed to save new theme due to null context", e);
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mMessageFormatItem != null) {
            mMessageFormatItem.setDescription(MessageBuilder.getInstance(requireActivity())
                    .buildMessage(mSampleMessage));
        }

        StringBuilder builder = new StringBuilder();
        if (NickAutocompleteSettings.isButtonVisible())
            appendString(builder, getString(R.string.pref_title_nick_autocomplete_show_button));
        if (NickAutocompleteSettings.isDoubleTapEnabled())
            appendString(builder, getString(R.string.pref_title_nick_autocomplete_double_tap));
        if (NickAutocompleteSettings.areSuggestionsEnabled())
            appendString(builder, getString(R.string.pref_title_nick_autocomplete_suggestions));
        if (NickAutocompleteSettings.areAtSuggestionsEnabled())
            appendString(builder, getString(R.string.pref_title_nick_autocomplete_at_suggestions));
        if (NickAutocompleteSettings.areChannelSuggestionsEnabled())
            appendString(builder, getString(R.string.pref_title_channel_autocomplete_suggestions));

        if (mAutocompleteItem != null) {
            mAutocompleteItem.setDescription(builder.toString());
        }
    }

    private void appendString(StringBuilder builder, String str) {
        if (str == null || str.isEmpty())
            return;
        if (builder.length() > 0) {
            builder.append(getString(R.string.text_comma));
            builder.append(str);
        } else {
            builder.append(str);
        }
    }


    private void showDCCWarning(CheckBoxSetting setting) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dcc_enable_send_warning_title)
                .setMessage(R.string.dcc_enable_send_warning_body)
                .setPositiveButton(R.string.dcc_approve_download_enable_anyway, null)
                .setNegativeButton(R.string.action_cancel, (di, w) -> setting.setChecked(false))
                .setOnCancelListener(di -> setting.setChecked(false))
                .show();
    }

    public static final class ThemeOptionSetting extends RadioButtonSetting {

        private static final int sHolder = SettingsListAdapter.registerViewHolder(Holder.class,
                R.layout.settings_theme_option);

        private InterfaceSettingsFragment fragment;
        private final int[] overrideColors;
        private ThemeManager.BaseTheme linkedBaseTheme;
        private ThemeInfo linkedCustomTheme;

        public ThemeOptionSetting(String name, Group group,
                                  int[] overrideColors) {
            super(name, group);
            this.overrideColors = overrideColors;
        }

        @Override
        public void setChecked(boolean checked) {
            super.setChecked(checked);
            if (checked) {
                if (linkedBaseTheme != null)
                    ThemeManager.getInstance(fragment.requireContext()).setTheme(linkedBaseTheme);
                if (linkedCustomTheme != null)
                    ThemeManager.getInstance(fragment.requireContext()).setTheme(linkedCustomTheme);
            }
        }

        public ThemeOptionSetting linkBaseTheme(InterfaceSettingsFragment fragment,
                                                ThemeManager.BaseTheme theme) {
            this.fragment = fragment;
            this.linkedBaseTheme = theme;
            ThemeManager themeManager = ThemeManager.getInstance(fragment.requireContext());
            setChecked(themeManager.getCurrentTheme() == theme ||
                    (themeManager.getCurrentTheme() == null &&
                            themeManager.getFallbackTheme() == theme));
            return this;
        }

        public ThemeOptionSetting linkCustomTheme(InterfaceSettingsFragment fragment,
                                                  ThemeInfo theme) {
            this.fragment = fragment;
            this.linkedCustomTheme = theme;
            setChecked(ThemeManager.getInstance(fragment.requireContext()).getCurrentCustomTheme() == theme);
            return this;
        }

        @Override
        public int getViewHolder() {
            return sHolder;
        }

        public static class Holder extends RadioButtonSetting.Holder
                implements View.OnLongClickListener {

            private final ColorStateList mDefaultButtonTintList;

            public Holder(View itemView, SettingsListAdapter adapter) {
                super(itemView, adapter);
                mDefaultButtonTintList = CompoundButtonCompat.getButtonTintList(mCheckBox);
                itemView.setOnLongClickListener(this);
            }

            @Override
            public void bind(CheckBoxSetting entry) {
                super.bind(entry);
                int[] overrideColors = ((ThemeOptionSetting) entry).overrideColors;
                int bgColor = StyledAttributesHelper.getColor(mCheckBox.getContext(),
                        android.R.attr.colorBackground, 0);
                boolean darkBg = ColorUtils.calculateLuminance(bgColor) < 0.4;
                int overrideColor = getOverrideColor(overrideColors, darkBg);
                if (overrideColor != 0)
                    CompoundButtonCompat.setButtonTintList(mCheckBox,
                            ColorStateList.valueOf(overrideColor));
                else
                    CompoundButtonCompat.setButtonTintList(mCheckBox, mDefaultButtonTintList);
            }

            private int getOverrideColor(int[] colors, boolean darkBg) {
                for (int c : colors) {
                    if ((!darkBg && ColorUtils.calculateLuminance(c) < 0.75)
                            || (darkBg && ColorUtils.calculateLuminance(c) > 0.25)) {
                        return c;
                    }
                }
                return colors[0];
            }

            @Override
            public void onClick(View v) {
                ThemeOptionSetting themeEntry = (ThemeOptionSetting) getEntry();
                if (getEntry().isChecked() && themeEntry.linkedCustomTheme != null) {
                    themeEntry.fragment.openThemeEditor(themeEntry.linkedCustomTheme);
                    return;
                }
                super.onClick(v);
            }


            @Override
            public boolean onLongClick(View v) {
                ThemeOptionSetting themeEntry = (ThemeOptionSetting) getEntry();
                if (themeEntry.fragment.getActivity() == null)
                    return false;
                MenuBottomSheetDialog menu = new MenuBottomSheetDialog(v.getContext());
                menu.addItem(R.string.action_copy, R.drawable.ic_content_copy,
                        (MenuBottomSheetDialog.Item i) -> {
                            if (themeEntry.fragment.getActivity() == null)
                                return false;
                            ThemeInfo newTheme;
                            if (themeEntry.linkedCustomTheme != null)
                                newTheme = themeEntry.fragment.createNewTheme(
                                        themeEntry.linkedCustomTheme);
                            else
                                newTheme = themeEntry.fragment.createNewTheme(
                                        themeEntry.linkedBaseTheme);
                            ThemeManager.getInstance(themeEntry.fragment.requireContext()).setTheme(newTheme);
                            themeEntry.fragment.openThemeEditor(newTheme);
                            return true;
                        });
                if (themeEntry.linkedCustomTheme != null) {
                    menu.addItem(R.string.action_edit, R.drawable.ic_edit,
                            (MenuBottomSheetDialog.Item i) -> {
                                themeEntry.fragment.openThemeEditor(themeEntry.linkedCustomTheme);
                                return true;
                            });
                    menu.addItem(R.string.action_delete, R.drawable.ic_delete,
                            (MenuBottomSheetDialog.Item i) -> {
                                if (themeEntry.fragment.getActivity() == null)
                                    return false;
                                ThemeManager.getInstance(themeEntry.fragment.requireContext())
                                        .deleteTheme(themeEntry.linkedCustomTheme);
                                getEntry().getOwner().remove(getEntry().getIndex());
                                themeEntry.fragment.requireActivity().recreate();
                                return true;
                            });
                }
                menu.show();
                return true;
            }
        }

    }

}
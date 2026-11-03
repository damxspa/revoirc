package io.mrarm.irc.config;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TemplateManager {

    public static final String TEMPLATES_PATH = "templates.json";
    private static volatile TemplateManager sInstance;

    private final Context mContext;
    private List<String> mUserTemplates;
    private String mActiveTemplate;

    public static TemplateManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TemplateManager.class) {
                if (sInstance == null) {
                    sInstance = new TemplateManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public TemplateManager(Context context) {
        mContext = context.getApplicationContext();
        mUserTemplates = new ArrayList<>();
        mActiveTemplate = null;
        loadUserSettings();
    }

    public void loadUserSettings(Reader reader) {
        UserTemplateSettings settings = SettingsHelper.getGson().fromJson(reader, UserTemplateSettings.class);
        if (settings != null) {
            if (settings.userTemplates != null) {
                mUserTemplates = settings.userTemplates;
            }
            mActiveTemplate = settings.activeTemplate;
        }
    }

    public void loadUserSettings() {
        File file = new File(mContext.getFilesDir(), TEMPLATES_PATH);
        if (!file.exists())
            return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            loadUserSettings(reader);
        } catch (Exception ignored) {
        }
    }

    public void saveUserSettings(Writer writer) {
        UserTemplateSettings settings = new UserTemplateSettings();
        settings.userTemplates = mUserTemplates;
        settings.activeTemplate = mActiveTemplate;
        SettingsHelper.getGson().toJson(settings, writer);
    }

    public boolean saveUserSettings() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(
                new File(mContext.getFilesDir(), TEMPLATES_PATH)))) {
            saveUserSettings(writer);
            return true;
        } catch (IOException ignored) {
        }
        return false;
    }

    public List<String> getUserTemplates() {
        return mUserTemplates;
    }

    public String getActiveTemplate() {
        return mActiveTemplate;
    }

    public void setActiveTemplate(String template) {
        mActiveTemplate = template;
        saveUserSettings();
    }

    public void addTemplate(String template) {
        mUserTemplates.add(template);
        saveUserSettings();
    }

    public void updateTemplate(int index, String template) {
        if (index >= 0 && index < mUserTemplates.size()) {
            String old = mUserTemplates.get(index);
            mUserTemplates.set(index, template);
            if (Objects.equals(old, mActiveTemplate)) {
                mActiveTemplate = template;
            }
            saveUserSettings();
        }
    }

    public void removeTemplate(int index) {
        if (index >= 0 && index < mUserTemplates.size()) {
            String removed = mUserTemplates.remove(index);
            if (Objects.equals(removed, mActiveTemplate)) {
                mActiveTemplate = null;
            }
            saveUserSettings();
        }
    }

    private static class UserTemplateSettings {
        List<String> userTemplates;
        String activeTemplate;
    }
}

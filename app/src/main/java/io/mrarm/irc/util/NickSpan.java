package io.mrarm.irc.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.fragment.app.Fragment;

import io.mrarm.irc.MainActivity;
import io.mrarm.irc.chat.ChatFragment;
import io.mrarm.irc.config.TemplateManager;

public class NickSpan extends ClickableSpan {

    private final String mNick;

    public NickSpan(String nick) {
        mNick = nick;
    }

    private MainActivity findActivity(Context ctx) {
        if (ctx instanceof MainActivity)
            return (MainActivity) ctx;
        if (ctx instanceof ContextWrapper)
            return findActivity(((ContextWrapper) ctx).getBaseContext());
        return null;
    }

    @Override
    public void onClick(View widget) {
        MainActivity activity = findActivity(widget.getContext());
        if (activity == null)
            return;
        Fragment fragment = activity.getCurrentFragment();
        if (fragment instanceof ChatFragment) {
            ChatFragment chatFragment = (ChatFragment) fragment;
            String template = TemplateManager.getInstance(widget.getContext()).getActiveTemplate();
            String command;
            if (template != null && !template.trim().isEmpty()) {
                command = "/msg " + mNick + " " + template;
            } else {
                command = "/msg " + mNick + " ";
            }
            chatFragment.getSendMessageHelper().setMessageText(command);
            chatFragment.getSendMessageHelper().setSelection(command.length());
        }
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        // No changes to draw state, keep original color and no underline
    }
}

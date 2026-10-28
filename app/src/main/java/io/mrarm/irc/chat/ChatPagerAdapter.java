package io.mrarm.irc.chat;

import android.content.Context;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.R;
import io.mrarm.irc.ServerConnectionInfo;

public class ChatPagerAdapter extends FragmentPagerAdapter {

    private Context context;
    private ServerConnectionInfo connectionInfo;
    private List<String> channels;
    private Map<String, Long> channelIds = new HashMap<>();
    private long nextChannelId = 1;

    public ChatPagerAdapter(Context context, FragmentManager fm, ServerConnectionInfo connectionInfo) {
        super(fm);
        this.context = context;
        this.connectionInfo = connectionInfo;
        updateChannelList();
    }

    public ChatPagerAdapter(Context context, FragmentManager fm, ServerConnectionInfo connectionInfo, Bundle bundle) {
        super(fm);
        this.context = context;
        this.connectionInfo = connectionInfo;
        if (bundle != null)
            onRestoreInstanceState(bundle);
        else
            updateChannelList();
    }

    public void onSaveInstanceState(Bundle outBundle) {
        String[] keys = new String[channelIds.size()];
        long[] values = new long[channelIds.size()];
        Iterator<Map.Entry<String, Long>> it = channelIds.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            keys[i] = e.getKey();
            values[i] = e.getValue();
            ++i;
        }
        outBundle.putStringArray("channel_ids_keys", keys);
        outBundle.putLongArray("channel_ids_values", values);
    }

    public void onRestoreInstanceState(Bundle bundle) {
        String[] keys = bundle.getStringArray("channel_ids_keys");
        long[] values = bundle.getLongArray("channel_ids_values");
        if (keys != null && values != null && keys.length == values.length) {
            for (int i = keys.length - 1; i >= 0; --i) {
                channelIds.put(keys[i], values[i]);
                nextChannelId = Math.max(nextChannelId, values[i] + 1);
            }
        }
        updateChannelList();
    }

    public void updateChannelList() {
        List<String> allChannels = connectionInfo.getChannels();
        channels = new ArrayList<>(allChannels);
        Iterator<Map.Entry<String, Long>> it = channelIds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            boolean found = false;
            for (String channel : channels) {
                if (channel.equalsIgnoreCase(entry.getKey())) {
                    found = true;
                    break;
                }
            }
            if (!found)
                it.remove();
        }
        for (String channel : channels) {
            boolean found = false;
            for (String key : channelIds.keySet()) {
                if (key.equalsIgnoreCase(channel)) {
                    found = true;
                    break;
                }
            }
            if (!found)
                channelIds.put(channel, nextChannelId++);
        }
        notifyDataSetChanged();
    }

    @Override
    public Fragment getItem(int position) {
        if (position == 0)
            return ChatMessagesFragment.newStatusInstance(connectionInfo);
        return ChatMessagesFragment.newInstance(connectionInfo, channels.get(position - 1));
    }

    @Override
    public int getItemPosition(Object object) {
        if (object instanceof ChatMessagesFragment) {
            ChatMessagesFragment fragment = (ChatMessagesFragment) object;
            if (fragment.isServerStatus())
                return POSITION_UNCHANGED;
            int iof = findChannel(fragment.getChannelName());
            if (iof == 0)
                return POSITION_NONE;
            return iof;
        }
        return super.getItemPosition(object);
    }

    @Override
    public long getItemId(int position) {
        if (position == 0)
            return 0;
        String channel = getChannel(position);
        for (Map.Entry<String, Long> entry : channelIds.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(channel))
                return entry.getValue();
        }
        return 0;
    }

    @Override
    public int getCount() {
        if (channels == null)
            return 1;
        return channels.size() + 1;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if (position == 0)
            return context.getString(R.string.tab_server);
        return channels.get(position - 1);
    }

    public String getChannel(int position) {
        if (position == 0)
            return null;
        return channels.get(position - 1);
    }

    public int findChannel(String channel) {
        if (channel == null)
            return 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).equalsIgnoreCase(channel))
                return i + 1;
        }
        return 0;
    }

}

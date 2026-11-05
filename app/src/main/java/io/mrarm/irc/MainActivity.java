package io.mrarm.irc;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.AlertDialog;

import android.os.Bundle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.dto.NickWithPrefix;
import io.mrarm.chatlib.dto.WhoisInfo;
import io.mrarm.chatlib.irc.ServerConnectionApi;
import io.mrarm.chatlib.irc.dcc.DCCServer;
import io.mrarm.chatlib.irc.dcc.DCCUtils;
import io.mrarm.irc.chat.ChannelInfoAdapter;
import io.mrarm.irc.chat.ChatFragment;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.dialog.UserBottomSheetDialog;

import io.mrarm.irc.drawer.DrawerHelper;
import io.mrarm.irc.util.ChatExportManager;
import io.mrarm.irc.util.BatteryOptimizationHelper;
import io.mrarm.irc.util.NightModeRecreateHelper;
import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.util.WarningHelper;
import io.mrarm.irc.view.ChipsEditText;
import io.mrarm.irc.view.LockableDrawerLayout;

public class MainActivity extends ThemedActivity implements IRCApplication.ExitCallback {

    public static final String ARG_SERVER_UUID = "server_uuid";
    public static final String ARG_CHANNEL_NAME = "channel";
    public static final String ARG_MESSAGE_ID = "message_id";
    public static final String ARG_MANAGE_SERVERS = "manage_servers";

    private static final int REQUEST_CODE_PICK_FILE_DCC = 100;
    private static final int REQUEST_CODE_DCC_FOLDER_PERMISSION = 101;
    private static final int REQUEST_CODE_DCC_STORAGE_PERMISSION = 102;
    private static final int REQUEST_CODE_NOTIFICATION_PERMISSION = 103;

    private final NightModeRecreateHelper mNightModeHelper = new NightModeRecreateHelper(this);
    private LockableDrawerLayout mDrawerLayout;
    private DrawerHelper mDrawerHelper;
    private Toolbar mToolbar;
    private boolean mBackReturnToServerList;
    private Dialog mCurrentDialog;
    private ChannelInfoAdapter mChannelInfoAdapter;
    private boolean mAppExiting;
    private final DCCManager.ActivityDialogHandler mDCCDialogHandler =
            new DCCManager.ActivityDialogHandler(this, REQUEST_CODE_DCC_FOLDER_PERMISSION,
                    REQUEST_CODE_DCC_STORAGE_PERMISSION);

    public static Intent getLaunchIntent(Context context, ServerConnectionInfo server, String channel, String messageId) {
        Intent intent = new Intent(context, MainActivity.class);
        if (server != null)
            intent.putExtra(ARG_SERVER_UUID, server.getUUID().toString());
        if (channel != null)
            intent.putExtra(ARG_CHANNEL_NAME, channel);
        if (messageId != null)
            intent.putExtra(ARG_MESSAGE_ID, messageId);
        return intent;
    }

    public static Intent getLaunchIntent(Context context, ServerConnectionInfo server, String channel) {
        return getLaunchIntent(context, server, channel, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ServerConnectionManager.getInstance(this);
        WarningHelper.setAppContext(getApplicationContext());

        mAppExiting = false;
        ((IRCApplication) getApplication()).addExitCallback(this);

        super.onCreate(savedInstanceState);
        setTitle("");
        setContentView(R.layout.activity_main);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mCurrentDialog != null && mCurrentDialog.isShowing()) {
                    dismissFragmentDialog();
                } else if (mBackReturnToServerList) {
                    openManageServers();
                } else if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                } else if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
                    mDrawerLayout.closeDrawer(GravityCompat.END);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        mDrawerLayout = findViewById(R.id.drawer_layout);

        mDrawerHelper = new DrawerHelper(this);
        mDrawerHelper.registerListeners();

        mDrawerHelper.setChannelClickListener((ServerConnectionInfo server, String channel) -> {
            mDrawerLayout.closeDrawers();
            Fragment f = getCurrentFragment();
            if (f instanceof ChatFragment && ((ChatFragment) f).getConnectionInfo() == server)
                ((ChatFragment) f).setCurrentChannel(channel, null);
            else
                openServer(server, channel);
        });
        mDrawerHelper.getManageServersItem().setOnClickListener((View v) -> {
            mDrawerLayout.closeDrawers();
            openManageServers();
        });

        if (AppSettings.isDrawerPinned())
            mDrawerLayout.setLocked(true);

        mChannelInfoAdapter = new ChannelInfoAdapter();
        RecyclerView membersRecyclerView = findViewById(R.id.members_list);
        membersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        membersRecyclerView.setAdapter(mChannelInfoAdapter);
        setChannelInfoDrawerVisible(false);

        // Only skip handleIntent if we're restoring state and intent doesn't have override data
        Intent intent = getIntent();
        if (savedInstanceState != null && 
                savedInstanceState.getString(ARG_SERVER_UUID) != null &&
                !intent.hasExtra(ARG_SERVER_UUID) &&
                !intent.hasExtra(ARG_CHANNEL_NAME) &&
                !intent.hasExtra(ARG_MESSAGE_ID) &&
                !intent.hasExtra(ARG_MANAGE_SERVERS) &&
                !(Intent.ACTION_SEND.equals(intent.getAction()) && 
                  "text/plain".equals(intent.getType()))) {
            return;
        }

        handleIntent(intent);

        // Request notification permission for Android 13+
        checkAndRequestNotificationPermission();
    }

    private void handleIntent(Intent intent) {
        String serverUUID = intent.getStringExtra(ARG_SERVER_UUID);
        ServerConnectionInfo server = null;
        if (serverUUID != null) {
            try {
                server = ServerConnectionManager.getInstance(this).getConnection(
                        UUID.fromString(serverUUID));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (server != null) {
            ChatFragment fragment = openServer(server, intent.getStringExtra(ARG_CHANNEL_NAME),
                    intent.getStringExtra(ARG_MESSAGE_ID), false);
            if (Intent.ACTION_SEND.equals(intent.getAction()) &&
                    "text/plain".equals(intent.getType())) {
                setFragmentShareText(fragment, intent.getStringExtra(Intent.EXTRA_TEXT));
            }
        } else if (intent.getBooleanExtra(ARG_MANAGE_SERVERS, false) ||
                getCurrentFragment() == null) {
            openManageServers();
        } else {
            mBackReturnToServerList = false;
        }
    }

    private void setFragmentShareText(ChatFragment fragment, String text) {
        if (fragment.getSendMessageHelper() != null) {
            fragment.getSendMessageHelper().setMessageText(text);
        } else {
            Bundle bundle = fragment.getArguments();
            if (bundle == null)
                bundle = new Bundle();
            bundle.putString(ChatFragment.ARG_SEND_MESSAGE_TEXT, text);
            if (!fragment.isAdded())
                fragment.setArguments(bundle);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ViewCompat.requestApplyInsets(mDrawerLayout);
        if (mToolbar != null) {
            StyledAttributesHelper ta = StyledAttributesHelper.obtainStyledAttributes(this,
                    new int[] { R.attr.actionBarSize });
            int actionBarSize = ta.getDimensionPixelSize(R.attr.actionBarSize, 0);
            ta.recycle();
            ViewGroup.LayoutParams lp = mToolbar.getLayoutParams();
            if (lp != null) {
                lp.height = actionBarSize;
                mToolbar.setLayoutParams(lp);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Fragment current = getCurrentFragment();
        if (current instanceof ChatFragment) {
            ChatFragment chat = (ChatFragment) current;
            if (chat.getConnectionInfo() != null && chat.getConnectionInfo().getUUID() != null) {
                outState.putString(ARG_SERVER_UUID, chat.getConnectionInfo().getUUID().toString());
                outState.putString(ARG_CHANNEL_NAME, chat.getCurrentChannel());
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        String serverUUID = savedInstanceState.getString(ARG_SERVER_UUID);
        if (serverUUID != null) {
            try {
                ServerConnectionInfo server = ServerConnectionManager.getInstance(this).getConnection(UUID.fromString(serverUUID));
                if (server != null) {
                    Fragment f = getCurrentFragment();
                    String channel = savedInstanceState.getString(ARG_CHANNEL_NAME);
                    if (f instanceof ChatFragment && ((ChatFragment) f).getConnectionInfo() == server) {
                        mDrawerHelper.setSelectedChannel(server, channel);
                    } else {
                        openServer(server, channel);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mNightModeHelper.onStart();
        // Check and request battery optimization permissions
        BatteryOptimizationHelper.checkAndRequestBatteryOptimizations(this);
    }

    @Override
    protected void onDestroy() {
        ((IRCApplication) getApplication()).removeExitCallback(this);
        mDrawerHelper.unregisterListeners();
        dismissFragmentDialog();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WarningHelper.setActivity(this);
        mDCCDialogHandler.onResume();
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment instanceof ChatFragment) {
            ChatFragment chatFragment = (ChatFragment) currentFragment;
            ServerConnectionInfo connectionInfo = chatFragment.getConnectionInfo();
            if (connectionInfo != null && !ServerConnectionManager.getInstance(this).hasConnection(connectionInfo.getUUID())) {
                openManageServers();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WarningHelper.setActivity(null);
        mDCCDialogHandler.onPause();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        mToolbar = toolbar;
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("");
        }
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    public void addActionBarDrawerToggle(Toolbar toolbar) {
        new LockableDrawerLayout.ActionBarDrawerToggle(
                toolbar, mDrawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        // mDrawerLayout.addDrawerListener(toggle);
    }

    public ChatFragment openServer(ServerConnectionInfo server, String channel, String messageId,
                                   boolean fromServerList) {
        dismissFragmentDialog();
        setChannelInfoDrawerVisible(false);
        ChatFragment fragment;
        if (getCurrentFragment() instanceof ChatFragment &&
                ((ChatFragment) getCurrentFragment()).getConnectionInfo() == server) {
            fragment = (ChatFragment) getCurrentFragment();
            fragment.setCurrentChannel(channel, messageId);
        } else {
            fragment = ChatFragment.newInstance(server, channel, messageId);
            getSupportFragmentManager().beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .replace(R.id.content_frame, fragment)
                    .commit();
        }
        mDrawerHelper.setSelectedChannel(server, channel);
        if (fromServerList)
            mBackReturnToServerList = true;
        return fragment;
    }

    public ChatFragment openServer(ServerConnectionInfo server, String channel) {
        return openServer(server, channel, null, false);
    }

    public void openManageServers() {
        dismissFragmentDialog();
        setChannelInfoDrawerVisible(false);
        getSupportFragmentManager().beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .replace(R.id.content_frame, ServerListFragment.newInstance())
                .commit();
        mDrawerHelper.setSelectedMenuItem(mDrawerHelper.getManageServersItem());
        mBackReturnToServerList = false;
    }

    public DrawerHelper getDrawerHelper() {
        return mDrawerHelper;
    }

    public Fragment getCurrentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.content_frame);
    }

    public void setFragmentDialog(Dialog dialog) {
        if (mCurrentDialog != null) {
            mCurrentDialog.setOnDismissListener(null);
            mCurrentDialog.dismiss();
        }
        mCurrentDialog = dialog;
        mCurrentDialog.setOnDismissListener((DialogInterface di) -> {
            if (mCurrentDialog == dialog)
                mCurrentDialog = null;
        });
    }

    public void dismissFragmentDialog() {
        if (mCurrentDialog != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (mCurrentDialog.getWindow() != null) {
                manager.hideSoftInputFromWindow(mCurrentDialog.getWindow().getDecorView()
                        .getApplicationWindowToken(), 0);
            }

            mCurrentDialog.setOnDismissListener(null);
            mCurrentDialog.dismiss();
            mCurrentDialog = null;
        }
    }

    public void setCurrentChannelInfo(ServerConnectionInfo server, String topic, String topicSetBy,
                                      Date topicSetOn, List<NickWithPrefix> members) {
        if (mChannelInfoAdapter == null)
            return;
        mChannelInfoAdapter.setData(server, topic, topicSetBy, topicSetOn, members);
        setChannelInfoDrawerVisible(topic != null || (members != null && !members.isEmpty()));
    }

    public void setChannelInfoDrawerVisible(boolean visible) {
        if (!AppSettings.isDrawerPinned()) {
            if (visible) {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END);
            } else {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END);
                mDrawerLayout.closeDrawer(GravityCompat.END);
            }
        } else {
            // When pinned, don't change lock mode (assumed to be locked for swipe prevention)
            // but we can still open/close programmatically
            if (visible) {
                mDrawerLayout.openDrawer(GravityCompat.END);
            } else {
                mDrawerLayout.closeDrawer(GravityCompat.END);
            }
        }
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (getCurrentFragment() instanceof ChatFragment) {
            getMenuInflater().inflate(R.menu.menu_chat, menu);
        } else if (getCurrentFragment() instanceof ServerListFragment) {
            getMenuInflater().inflate(R.menu.menu_server_list, menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean hasChanges = false;
        Fragment current = getCurrentFragment();
        if (current instanceof ChatFragment) {
            ChatFragment fragment = (ChatFragment) current;
            ServerConnectionInfo connInfo = fragment.getConnectionInfo();
            if (connInfo == null) {
                return super.onPrepareOptionsMenu(menu);
            }
            String actionCloseDirect = !isFinishing() ? getString(R.string.action_close_direct) : null;
            String actionPartChannel = !isFinishing() ? getString(R.string.action_part_channel) : null;
            Object apiObj = connInfo.getApiInstance();
            ServerConnectionApi api = (apiObj instanceof ServerConnectionApi) ? (ServerConnectionApi) apiObj : null;
            boolean connected = connInfo.isConnected();
            MenuItem reconnectItem = menu.findItem(R.id.action_reconnect);
            boolean wasConnected = reconnectItem != null && !reconnectItem.isVisible();
            if (connected != wasConnected) {
                if (connected) {
                    if (menu.findItem(R.id.action_reconnect) != null) menu.findItem(R.id.action_reconnect).setVisible(false);
                    if (menu.findItem(R.id.action_close) != null) menu.findItem(R.id.action_close).setVisible(false);
                    if (menu.findItem(R.id.action_disconnect) != null) menu.findItem(R.id.action_disconnect).setVisible(true);
                    if (menu.findItem(R.id.action_disconnect_and_close) != null) menu.findItem(R.id.action_disconnect_and_close).setVisible(true);
                } else {
                    if (menu.findItem(R.id.action_reconnect) != null) menu.findItem(R.id.action_reconnect).setVisible(true);
                    if (menu.findItem(R.id.action_close) != null) menu.findItem(R.id.action_close).setVisible(true);
                    if (menu.findItem(R.id.action_disconnect) != null) menu.findItem(R.id.action_disconnect).setVisible(false);
                    if (menu.findItem(R.id.action_disconnect_and_close) != null) menu.findItem(R.id.action_disconnect_and_close).setVisible(false);
                }
                hasChanges = true;
            }
            if (menu.findItem(R.id.action_members) != null) {
                menu.findItem(R.id.action_members).setVisible(
                        mDrawerLayout.getDrawerLockMode(GravityCompat.END) != DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }
            if (fragment.getSendMessageHelper() != null && menu.findItem(R.id.action_format) != null) {
                boolean hasSelection = fragment.getSendMessageHelper().hasSendMessageTextSelection();
                if (hasSelection != menu.findItem(R.id.action_format).isVisible()) {
                    menu.findItem(R.id.action_format).setVisible(hasSelection);
                    hasChanges = true;
                }
            }
            MenuItem partItem = menu.findItem(R.id.action_part_channel);
            boolean inDirectChat = false;
            String currentChannel = fragment.getCurrentChannel();
            if (partItem != null) {
                if (currentChannel == null) {
                    if (partItem.isVisible())
                        hasChanges = true;
                    partItem.setVisible(false);
                } else if (!currentChannel.isEmpty() && api != null && api.getServerConnectionData() != null
                        && api.getServerConnectionData().getSupportList() != null
                        && !api.getServerConnectionData().getSupportList().getSupportedChannelTypes().contains(currentChannel.charAt(0))) {
                    if (partItem.isVisible() || (actionCloseDirect != null && !Objects.equals(partItem.getTitle(), actionCloseDirect)))
                        hasChanges = true;
                    partItem.setVisible(true);
                    if (actionCloseDirect != null) {
                        partItem.setTitle(actionCloseDirect);
                    }
                    inDirectChat = true;
                } else {
                    if (partItem.isVisible() || (actionPartChannel != null && !Objects.equals(partItem.getTitle(), actionPartChannel)))
                        hasChanges = true;
                    partItem.setVisible(true);
                    if (actionPartChannel != null) {
                        partItem.setTitle(actionPartChannel);
                    }
                }
            }

            if (menu.findItem(R.id.action_check_connection) != null) {
                menu.findItem(R.id.action_check_connection).setVisible(connected && inDirectChat);
            }
            MenuItem dccSendItem = menu.findItem(R.id.action_dcc_send);
            if (dccSendItem != null) {
                boolean wasDccSendVisible = dccSendItem.isVisible();
                boolean dccSendVisible = ChatSettings.isDccSendVisible() && connected && inDirectChat;
                if (dccSendVisible != wasDccSendVisible) {
                    dccSendItem.setVisible(dccSendVisible);
                    hasChanges = true;
                }
            }
        }
        return super.onPrepareOptionsMenu(menu) | hasChanges;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        Fragment f = getCurrentFragment();
        if (id == R.id.action_join_channel) {
            View v = LayoutInflater.from(this).inflate(R.layout.dialog_chip_edit_text, null);
            ChipsEditText editText = v.findViewById(R.id.chip_edit_text);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.action_join_channel)
                    .setView(v)
                    .setPositiveButton(R.string.action_ok, (DialogInterface d, int which) -> {
                        editText.clearFocus();
                        String[] channels = editText.getItems();
                        if (channels.length == 0)
                            return;
                        Fragment current = getCurrentFragment();
                        if (current instanceof ChatFragment) {
                            ChatFragment currentChat = (ChatFragment) current;
                            if (currentChat.getConnectionInfo() != null && currentChat.getConnectionInfo().getApiInstance() != null) {
                                ChatApi api = currentChat.getConnectionInfo().getApiInstance();
                                currentChat.setAutoOpenChannel(channels[0]);
                                api.joinChannels(Arrays.asList(channels), null, null);
                            }
                        }
                    })
                    .setNeutralButton(R.string.title_activity_channel_list, (DialogInterface d, int which) -> {
                        Fragment current = getCurrentFragment();
                        if (current instanceof ChatFragment) {
                            ServerConnectionInfo info = ((ChatFragment) current).getConnectionInfo();
                            if (info != null) {
                                Intent intent = new Intent(this, ChannelListActivity.class);
                                intent.putExtra(ChannelListActivity.ARG_SERVER_UUID, info.getUUID().toString());
                                startActivity(intent);
                            }
                        }
                    })
                    .create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            }
            setFragmentDialog(dialog);
        } else if (id == R.id.action_clear_history) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.action_clear_history)
                    .setMessage(R.string.message_clear_history_confirm)
                    .setPositiveButton(R.string.action_ok, (DialogInterface d, int which) -> {
                        Fragment fragment = getCurrentFragment();
                        if (fragment instanceof ChatFragment)
                            ((ChatFragment) fragment).clearHistory();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            setFragmentDialog(dialog);
        } else if (id == R.id.action_part_channel) {
            if (f instanceof ChatFragment) {
                ChatFragment chatFragment = (ChatFragment) f;
                if (chatFragment.getConnectionInfo() != null && chatFragment.getConnectionInfo().getApiInstance() != null) {
                    ChatApi api = chatFragment.getConnectionInfo().getApiInstance();
                    String channel = chatFragment.getCurrentChannel();
                    if (channel != null)
                        api.leaveChannel(channel, AppSettings.getDefaultPartMessage(), null, null);
                }
            }
        } else if (id == R.id.action_dcc_send) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_PICK_FILE_DCC);
        } else if (id == R.id.action_members) {
            mDrawerLayout.openDrawer(GravityCompat.END);
        } else if (id == R.id.action_ignore_list) {
            if (f instanceof ChatFragment) {
                ServerConnectionInfo info = ((ChatFragment) f).getConnectionInfo();
                if (info != null) {
                    Intent intent = new Intent(this, IgnoreListActivity.class);
                    intent.putExtra(IgnoreListActivity.ARG_SERVER_UUID, info.getUUID().toString());
                    startActivity(intent);
                }
            }
        } else if (id == R.id.action_disconnect) {
            if (f instanceof ChatFragment) {
                ServerConnectionInfo info = ((ChatFragment) f).getConnectionInfo();
                if (info != null)
                    info.disconnect();
            }
        } else if (id == R.id.action_disconnect_and_close || id == R.id.action_close) {
            if (f instanceof ChatFragment) {
                ServerConnectionInfo info = ((ChatFragment) f).getConnectionInfo();
                if (info != null) {
                    info.disconnect();
                    ServerConnectionManager.getInstance(this).removeConnection(info);
                }
            }
            openManageServers();
        } else if (id == R.id.action_reconnect) {
            if (f instanceof ChatFragment) {
                ServerConnectionInfo info = ((ChatFragment) f).getConnectionInfo();
                if (info != null)
                    info.connect();
            }
        } else if (id == R.id.action_format) {
            if (f instanceof ChatFragment) {
                ChatFragment chatFragment = (ChatFragment) f;
                if (chatFragment.getSendMessageHelper() != null)
                    chatFragment.getSendMessageHelper().setFormatBarVisible(true);
            }
        } else if (id == R.id.action_check_connection) {
            if (f instanceof ChatFragment) {
                ChatFragment chat = (ChatFragment) f;
                String nick = chat.getCurrentChannel();
                if (nick != null && chat.getConnectionInfo() != null && chat.getConnectionInfo().getApiInstance() != null) {
                    UserBottomSheetDialog dialog = new UserBottomSheetDialog(this);
                    dialog.setConnection(chat.getConnectionInfo());
                    dialog.requestData(nick, chat.getConnectionInfo().getApiInstance());
                    setFragmentDialog(dialog.show());
                }
            }
        } else if (id == R.id.action_dcc_transfers) {
            startActivity(new Intent(this, DCCActivity.class));
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.action_exit) {
            ((IRCApplication) getApplication()).requestExit();
        } else if (id == android.R.id.home) {
            if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                mDrawerLayout.closeDrawer(GravityCompat.START);
            } else {
                mDrawerLayout.openDrawer(GravityCompat.START);
            }
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_FILE_DCC && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            String name = null;
            long size = -1;
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    name = DCCUtils.escapeFilename(cursor.getString(nameIndex));
                    size = cursor.isNull(sizeIndex) ? -1 : cursor.getLong(sizeIndex);
                }
            }
            if (name == null)
                name = "file";

            Fragment currentFragment = getCurrentFragment();
            if (!(currentFragment instanceof ChatFragment)) {
                Toast.makeText(this, R.string.error_file_open, Toast.LENGTH_SHORT).show();
                return;
            }
            ChatFragment chatFragment = (ChatFragment) currentFragment;
            ServerConnectionInfo connInfo = chatFragment.getConnectionInfo();
            if (connInfo == null) {
                Toast.makeText(this, R.string.error_file_open, Toast.LENGTH_SHORT).show();
                return;
            }
            String channel = chatFragment.getCurrentChannel();
            try (ParcelFileDescriptor desc = getContentResolver().openFileDescriptor(uri, "r")) {
                if (desc == null)
                    throw new IOException();
                if (size == -1)
                    size = desc.getStatSize();

                DCCServer.FileChannelFactory fileFactory = () -> {
                    ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                    if (fd == null)
                        throw new IOException();
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(fd.getFileDescriptor()) {
                            @Override
                            public void close() throws IOException {
                                try {
                                    super.close();
                                } finally {
                                    fd.close();
                                }
                            }
                        };
                        return fis.getChannel().position(0);
                    } catch (IOException | RuntimeException e) {
                        if (fis != null) {
                            try {
                                fis.close();
                            } catch (IOException ignored) {
                            }
                        } else {
                            fd.close();
                        }
                        throw e;
                    }
                };
                DCCManager.getInstance(this).startUpload(connInfo, channel, fileFactory, name, size);
            } catch (IOException e) {
                Toast.makeText(this, R.string.error_file_open, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        mDCCDialogHandler.onActivityResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        mDCCDialogHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onAppExiting() {
        mAppExiting = true;
        if (getCurrentFragment() instanceof ServerListFragment)
            ((ServerListFragment) getCurrentFragment()).getAdapter().unregisterListeners();
        getDrawerHelper().unregisterListeners();
    }

    public boolean isAppExiting() {
        return mAppExiting;
    }

    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_CODE_NOTIFICATION_PERMISSION);
            }
        }
    }
}

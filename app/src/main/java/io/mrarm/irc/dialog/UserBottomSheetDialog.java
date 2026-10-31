package io.mrarm.irc.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.core.content.ContextCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import coil.request.ImageRequest;
import coil.transform.CircleCropTransformation;
import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.dto.WhoisInfo;
import io.mrarm.irc.ImageActivity;
import io.mrarm.irc.MainActivity;
import io.mrarm.irc.R;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.util.AdvancedDividerItemDecoration;

public class UserBottomSheetDialog {

    private Context mContext;
    private StatusBarColorBottomSheetDialog mDialog;
    private RecyclerView mRecyclerView;
    private ItemAdapter mAdapter;

    private ServerConnectionInfo mConnection;
    private String mNick;
    private String mUser;
    private String mRealName;
    private String mAccount;
    private boolean mAway;
    private List<Pair<String, String>> mEntries = new ArrayList<>();

    private HeaderHelper mHeader;

    public UserBottomSheetDialog(Context context) {
        mContext = context;
    }

    public void setConnection(ServerConnectionInfo connection) {
        mConnection = connection;
    }

    public void requestData(String nick, ChatApi connection) {
        setUser(nick, null, null, null, false);
        connection.sendWhois(nick, (WhoisInfo info) -> {
            mRecyclerView.post(() -> {
                if (mHeader != null)
                    mHeader.setStatus(R.string.user_connected);
                setData(info);
            });
        }, (Exception e) -> {
            mRecyclerView.post(() -> {
                if (mHeader != null)
                    mHeader.setStatus(R.string.user_disconnected);
            });
        });
    }

    public void setData(WhoisInfo info) {
        mEntries.clear();
        setUser(info.getNick(), info.getUser(), info.getRealName(), info.getLoggedInAsAccount(), (info.getAwayMessage() != null));
        if (info.getAwayMessage() != null)
            addEntry(R.string.user_away, info.getAwayMessage());
        addEntry(R.string.user_hostname, info.getHost());
        if (info.getServer() != null)
            addEntry(R.string.user_server, mContext.getString(R.string.user_server_format, info.getServer(), info.getServerInfo()));
        if (info.getChannels() != null) {
            StringBuilder b = new StringBuilder();
            for (WhoisInfo.ChannelWithNickPrefixes channel : info.getChannels()) {
                if (b.length() > 0)
                    b.append(mContext.getString(R.string.text_comma));
                if (channel.getPrefixes() != null)
                    b.append(channel.getPrefixes());
                b.append(channel.getChannel());
            }
            addEntry(R.string.user_channels, b.toString());
        }
        if (info.getIdleSeconds() > 0)
            addEntry(R.string.user_idle, formatTime(info.getIdleSeconds()));
        if (info.getLoggedInAsAccount() != null)
            addEntry(R.string.user_account, info.getLoggedInAsAccount());
        if (info.isOperator())
            addEntry(R.string.user_server_op, mContext.getString(R.string.user_server_op_desc));
        if (info.isConnectionSecure())
            addEntry(R.string.user_secure, mContext.getString(R.string.user_secure_desc));
        if (mAdapter != null)
            mAdapter.notifyDataSetChanged();
    }

    private String formatTime(int seconds) {
        if (seconds >= TimeUnit.DAYS.toSeconds(2L)) {
            int days = (int) TimeUnit.SECONDS.toDays(seconds);
            return mContext.getResources().getQuantityString(R.plurals.time_days, days, days);
        }
        if (seconds >= TimeUnit.HOURS.toSeconds(2L)) {
            int days = (int) TimeUnit.SECONDS.toHours(seconds);
            return mContext.getResources().getQuantityString(R.plurals.time_hours, days, days);
        }
        if (seconds >= TimeUnit.MINUTES.toSeconds(2L)) {
            int days = (int) TimeUnit.SECONDS.toMinutes(seconds);
            return mContext.getResources().getQuantityString(R.plurals.time_minutes, days, days);
        }
        return mContext.getResources().getQuantityString(R.plurals.time_seconds, seconds, seconds);
    }

    public void setUser(String nick, String user, String realName, String account, boolean away) {
        mNick = nick;
        mUser = user;
        mRealName = realName;
        mAccount = account;
        mAway = away;
        updateDialogStatusBarColor();
        if (mHeader != null)
            mHeader.bind();
    }

    public void addEntry(int titleId, String value) {
        addEntry(mContext.getString(titleId), value);
    }

    public void addEntry(String title, String value) {
        mEntries.add(new Pair<>(title, value));
        if (mAdapter != null)
            mAdapter.notifyItemInserted(mEntries.size() - 1);
    }

    private void create() {
        View view = LayoutInflater.from(mContext).inflate(R.layout.dialog_bottom_user, null);

        mRecyclerView = view.findViewById(R.id.list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.addItemDecoration(new AdvancedDividerItemDecoration(mContext));

        mHeader = new HeaderHelper(view.findViewById(R.id.header));
        mHeader.bind();

        // Store references to listeners for cleanup
        final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                mHeader.updateScrollY();
            }
        };
        
        mRecyclerView.addOnScrollListener(scrollListener);

        mAdapter = new ItemAdapter();
        mRecyclerView.setAdapter(mAdapter);

        view.findViewById(R.id.message_button).setOnClickListener((View v) -> {
            PopupMenu menu = new PopupMenu(mContext, v);
            menu.getMenu().add(0, 1, 0, mContext.getString(R.string.action_message_user));
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    List<String> l = new ArrayList<>();
                    l.add(mNick);
                    mConnection.getApiInstance().joinChannels(l, (Void vo) -> {
                        view.post(() -> {
                            if (mContext instanceof MainActivity)
                                ((MainActivity) mContext).openServer(mConnection, mNick);
                            mDialog.cancel();
                        });
                    }, (Exception e) -> {
                        // Handle error in joining channels
                        view.post(() -> {
                            Toast.makeText(mContext, R.string.error_generic, Toast.LENGTH_SHORT).show();
                        });
                    });
                    return true;
                }
                return false;
            });
            menu.show();
        });

        mDialog = new StatusBarColorBottomSheetDialog(mContext);
        mDialog.setCancelable(true);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setContentView(view);
        mDialog.getOnBackPressedDispatcher().addCallback(mDialog, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                mDialog.cancel();
            }
        });
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                mDialog.cancel();
                return true;
            }
            return false;
        });
        int compatMaxHeight = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_height_compact_activate);
        if (mDialog.getWindow() != null) {
            // Store layout change listener for cleanup
            final View.OnLayoutChangeListener layoutChangeListener = (View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                if (bottom - top == oldBottom - oldTop)
                    return;
                view.post(() -> {
                    View bottomSheet = mDialog.findViewById(R.id.design_bottom_sheet);
                    if (bottomSheet == null) return;
                    BottomSheetBehavior behaviour = BottomSheetBehavior.from(bottomSheet);
                    view.setMinimumHeight(bottom - top);
                    mHeader.setCompactMode(behaviour.getPeekHeight() < compatMaxHeight);
                });
            };
            mDialog.getWindow().getDecorView().addOnLayoutChangeListener(layoutChangeListener);
            
            // Set dismiss listener to cleanup
            mDialog.setOnDismissListener(dialog -> {
                if (mDialog.getWindow() != null) {
                    mDialog.getWindow().getDecorView().removeOnLayoutChangeListener(layoutChangeListener);
                }
                mRecyclerView.removeOnScrollListener(scrollListener);
            });
        }
        updateDialogStatusBarColor();
    }

    private void updateDialogStatusBarColor() {
        if (mDialog == null)
            return;
        if (mAway)
            mDialog.setStatusBarColor(ContextCompat.getColor(mContext, R.color.userAwayColorPrimaryDark));
        else
            mDialog.setStatusBarColor(ContextCompat.getColor(mContext, R.color.colorPrimaryDark));
    }

    public BottomSheetDialog show() {
        if (mDialog == null)
            create();
        mDialog.show();
        return mDialog;
    }

    private class ItemAdapter extends RecyclerView.Adapter {

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.dialog_bottom_user_entry, parent, false);
            return new EntryHolder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((EntryHolder) holder).bind(mEntries.get(position));
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        private class EntryHolder extends RecyclerView.ViewHolder {
            private TextView mTitle;
            private TextView mValue;

            public EntryHolder(View itemView) {
                super(itemView);
                mTitle = itemView.findViewById(R.id.title);
                mValue = itemView.findViewById(R.id.value);
                itemView.setOnLongClickListener((View v) -> {
                    copyValueToClipboard(v.getContext(), mTitle.getText(), mValue.getText());
                    return true;
                });
            }

            public void bind(Pair<String, String> entry) {
                mTitle.setText(entry.first);
                mValue.setText(entry.second);
            }
        }

    }

    private class HeaderHelper {
        private View mContainer;
        private TextView mName;
        private TextView mNick;
        private TextView mUser;
        private TextView mStatus;
        private ImageView mAvatar;
        private View mMessageButton;
        private View mRightInfoContainer;
        private TextView mSendMessageNew;
        private TextView mStatusNew;
        private int mBottomMargin;
        private int mNameBottomMargin;
        private int mTargetNameBottomMargin;
        private int mMaxHeight;
        private int mMinHeight;
        private int mElevation;
        private boolean mCompactMode = false;

        public HeaderHelper(View itemView) {
            mContainer = itemView;
            mName = itemView.findViewById(R.id.name);
            mNick = itemView.findViewById(R.id.nick);
            mUser = itemView.findViewById(R.id.user);
            mStatus = itemView.findViewById(R.id.status);
            mAvatar = itemView.findViewById(R.id.avatar);
            mMessageButton = itemView.findViewById(R.id.message_button);
            mRightInfoContainer = itemView.findViewById(R.id.right_info_container);
            mSendMessageNew = itemView.findViewById(R.id.send_message_new);
            mStatusNew = itemView.findViewById(R.id.status_new);
            mAvatar.setImageTintList(AppCompatResources.getColorStateList(mContext, R.color.abc_primary_text_material_dark));
            setCompactMode(false, true);
            mElevation = mContext.getResources().getDimensionPixelSize(R.dimen.abc_action_bar_elevation_material);

            mNick.setOnLongClickListener(createLongClickListener(mContext, R.string.user_nick));
            mUser.setOnLongClickListener(createLongClickListener(mContext, R.string.server_user));
            mName.setOnLongClickListener(createLongClickListener(mContext, R.string.server_realname));
        }

        public void bind() {
            if (mAway) {
                mName.setText(mContext.getString(R.string.user_title_away, mRealName));
                mContainer.setBackgroundResource(R.color.userAwayColorPrimary);
            } else {
                mName.setText(UserBottomSheetDialog.this.mRealName);
                mContainer.setBackgroundResource(R.color.colorPrimary);
            }
            mNick.setText(UserBottomSheetDialog.this.mNick);
            mUser.setText(UserBottomSheetDialog.this.mUser);

            mName.setVisibility(View.VISIBLE);
            mMessageButton.setVisibility(View.VISIBLE);
            mStatus.setVisibility(View.VISIBLE);
            mRightInfoContainer.setVisibility(View.GONE);

            if (mAvatar != null) {
                mAvatar.setVisibility(View.GONE);
                mAvatar.setOnClickListener(null);
                ViewGroup.LayoutParams params = mAvatar.getLayoutParams();
                if (params instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams avatarParams = (RelativeLayout.LayoutParams) params;
                    avatarParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                    avatarParams.removeRule(RelativeLayout.CENTER_IN_PARENT);
                    avatarParams.removeRule(RelativeLayout.ALIGN_PARENT_LEFT);
                    avatarParams.removeRule(RelativeLayout.ALIGN_PARENT_START);
                    avatarParams.width = (int) (128 * mContext.getResources().getDisplayMetrics().density);
                    avatarParams.height = avatarParams.width;
                    avatarParams.topMargin = (int) (16 * mContext.getResources().getDisplayMetrics().density);
                    avatarParams.leftMargin = (int) (16 * mContext.getResources().getDisplayMetrics().density);
                    avatarParams.setMarginStart(avatarParams.leftMargin);
                    mAvatar.setLayoutParams(avatarParams);
                }
            }
            mNameBottomMargin = mContext.getResources().getDimensionPixelSize(mCompactMode ?
                    R.dimen.dialog_bottom_user_header_name_bottom_margin_compact :
                    R.dimen.dialog_bottom_user_header_name_bottom_margin);
            updateScrollY();
        }

        public void setStatus(int resId) {
            mStatus.setText(resId);
            mStatusNew.setText(resId);
        }

        public void setScrollY(int y) {
            int cy = Math.min(y, mMaxHeight - mMinHeight);
            if (y == -1) {
                cy = mMaxHeight - mMinHeight;
                mNick.setVisibility(View.GONE);
                mUser.setVisibility(View.GONE);
                if (mRightInfoContainer != null)
                    mRightInfoContainer.setVisibility(View.GONE);
            } else {
                mNick.setVisibility(View.VISIBLE);
                mUser.setVisibility(View.VISIBLE);
                
                // Safe casting of LayoutParams
                ViewGroup.LayoutParams nickParams = mNick.getLayoutParams();
                if (nickParams instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) nickParams;
                    params.bottomMargin = mBottomMargin - y;
                    mNick.setLayoutParams(params);
                }
                
                ViewGroup.LayoutParams userParams = mUser.getLayoutParams();
                if (userParams instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) userParams;
                    params.bottomMargin = mBottomMargin - y;
                    mUser.setLayoutParams(params);
                }
            }
            if (mAvatar != null)
                mAvatar.setAlpha(1.0f - (float) cy / (mMaxHeight - mMinHeight));
            if (mRightInfoContainer != null)
                mRightInfoContainer.setAlpha(1.0f - (float) cy / (mMaxHeight - mMinHeight));
            ViewCompat.setElevation(mContainer, cy == (mMaxHeight - mMinHeight) ? mElevation : 0);
            
            // Safe casting of LayoutParams
            ViewGroup.LayoutParams nameParams = mName.getLayoutParams();
            if (nameParams instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) nameParams;
                params.bottomMargin = Math.max(mNameBottomMargin - cy, mTargetNameBottomMargin);
                mName.setLayoutParams(params);
            }
            
            ViewGroup.LayoutParams containerParams = mContainer.getLayoutParams();
            if (containerParams instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) containerParams;
                int newH = mMaxHeight - cy;
                if (newH != params.height) {
                    params.height = mMaxHeight - cy;
                    mContainer.setLayoutParams(params);
                }
            }
        }

        public void updateScrollY() {
            if (mRecyclerView == null || mRecyclerView.getChildCount() == 0) {
                setScrollY(0);
                return;
            }
            View v = mRecyclerView.getChildAt(0);
            if (mRecyclerView.getChildAdapterPosition(v) != 0) {
                setScrollY(-1);
                return;
            }
            setScrollY(mRecyclerView.getPaddingTop() - v.getTop());
        }

        private void setCompactMode(boolean compactMode, boolean force) {
            if (mCompactMode == compactMode && !force)
                return;
            mCompactMode = compactMode;
            if (compactMode) {
                mMaxHeight = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_height_compact);
                mMinHeight = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_min_height_compact);
                mBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_bottom_margin_compact);
                mNameBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_name_bottom_margin_compact);
                mTargetNameBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_name_bottom_margin_target_compact);
            } else {
                mMaxHeight = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_height);
                mMinHeight = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_min_height);
                mBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_bottom_margin);
                mNameBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_name_bottom_margin);
                mTargetNameBottomMargin = mContext.getResources().getDimensionPixelSize(R.dimen.dialog_bottom_user_header_name_bottom_margin_target);
            }
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mContainer.getLayoutParams();
            params.height = mMaxHeight;
            mContainer.setLayoutParams(params);
            mRecyclerView.setPadding(0, mMaxHeight, 0, 0);
            RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
            if (layoutManager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(0, 0);
            }
            setScrollY(0);
        }

        public void setCompactMode(boolean compactMode) {
            setCompactMode(compactMode, false);
        }

    }

    private static void copyValueToClipboard(Context context, CharSequence key, CharSequence value) {
        ClipboardManager clipboard = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);
        // Handle null key safely
        String safeKey = (key != null) ? key.toString() : "";
        ClipData clip = ClipData.newPlainText(safeKey, value);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, context.getString(R.string.user_info_copied, safeKey),
                Toast.LENGTH_SHORT).show();
    }

    private static View.OnLongClickListener createLongClickListener(Context context, int resId) {
        return (View v) -> {
            copyValueToClipboard(context, context.getString(resId), ((TextView) v).getText());
            return true;
        };
    }

}
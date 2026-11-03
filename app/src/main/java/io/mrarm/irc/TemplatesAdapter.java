package io.mrarm.irc;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.TextView;
import java.util.Objects;

import io.mrarm.irc.config.TemplateManager;
import io.mrarm.irc.dialog.MenuBottomSheetDialog;
import io.mrarm.irc.util.AdvancedDividerItemDecoration;

public class TemplatesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_USER_ITEM = 2;

    private TemplateManager mManager;
    private RecyclerView mRecyclerView;

    public TemplatesAdapter(Context context) {
        mManager = TemplateManager.getInstance(context);
    }

    public ItemDecoration createItemDecoration(Context context) {
        return new ItemDecoration(context);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int type) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        if (type == TYPE_HEADER)
            return new HeaderHolder(inflater.inflate(R.layout.settings_list_header, viewGroup, false));
        View view = inflater.inflate(R.layout.settings_template_item, viewGroup, false);
        if (type == TYPE_USER_ITEM)
            return new UserItemHolder(view, this);
        return new ItemHolder(view, this);
    }

    @Override
    public int getItemViewType(int position) {
        int userTemplates = mManager.getUserTemplates().size();
        if (position == 0 || (userTemplates > 0 && position == userTemplates + 1))
            return TYPE_HEADER;
        if (userTemplates > 0 && position < userTemplates + 1)
            return TYPE_USER_ITEM;
        return TYPE_ITEM;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder itemHolder, int pos) {
        int type = itemHolder.getItemViewType();
        int userTemplates = mManager.getUserTemplates().size();
        if (type == TYPE_HEADER) {
            ((HeaderHolder) itemHolder).bind(pos > 0 || userTemplates == 0 ? R.string.value_default : R.string.value_custom);
        } else if (type == TYPE_USER_ITEM) {
            int index = pos - 1;
            String template = mManager.getUserTemplates().get(index);
            boolean isActive = Objects.equals(template, mManager.getActiveTemplate());
            ((UserItemHolder) itemHolder).bind(template, index, isActive);
        } else {
            boolean isActive = (mManager.getActiveTemplate() == null || mManager.getActiveTemplate().isEmpty());
            ((ItemHolder) itemHolder).bind(R.string.template_no_template, isActive);
        }
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        if (recyclerView == mRecyclerView)
            mRecyclerView = null;
    }

    @Override
    public int getItemCount() {
        int userTemplates = mManager.getUserTemplates().size();
        return (userTemplates > 0 ? 1 + userTemplates : 0) + 1 + 1;
    }

    public static class ItemHolder extends RecyclerView.ViewHolder {

        protected CheckedTextView mText;
        protected TemplatesAdapter mAdapter;

        public ItemHolder(View view, TemplatesAdapter adapter) {
            super(view);
            mText = view.findViewById(R.id.text);
            mAdapter = adapter;
            view.setOnClickListener((View v) -> {
                mAdapter.mManager.setActiveTemplate(null);
                mAdapter.notifyDataSetChanged();
            });
        }

        public void bind(int textResId, boolean isActive) {
            mText.setText(textResId);
            mText.setChecked(isActive);
        }

    }

    public static class UserItemHolder extends RecyclerView.ViewHolder {

        protected CheckedTextView mText;
        protected TemplatesAdapter mAdapter;
        protected String mTemplateText;
        protected int mIndex;

        public UserItemHolder(View view, TemplatesAdapter adapter) {
            super(view);
            mText = view.findViewById(R.id.text);
            mAdapter = adapter;

            view.setOnClickListener((View v) -> {
                mAdapter.mManager.setActiveTemplate(mTemplateText);
                mAdapter.notifyDataSetChanged();
            });

            view.setOnLongClickListener((View v) -> {
                Context ctx = v.getContext();
                MenuBottomSheetDialog dialog = new MenuBottomSheetDialog(ctx);
                dialog.addItem(R.string.action_edit, R.drawable.ic_edit, (MenuBottomSheetDialog.Item item) -> {
                    Intent intent = new Intent(ctx, EditTemplateActivity.class);
                    intent.putExtra(EditTemplateActivity.ARG_TEMPLATE_INDEX, mIndex);
                    intent.putExtra(EditTemplateActivity.ARG_TEMPLATE_TEXT, mTemplateText);
                    ctx.startActivity(intent);
                    return true;
                });
                dialog.addItem(R.string.action_delete, R.drawable.ic_delete, (MenuBottomSheetDialog.Item item) -> {
                    new AlertDialog.Builder(ctx)
                            .setTitle(R.string.action_delete_confirm_title)
                            .setMessage(ctx.getString(R.string.action_delete_confirm_body, mTemplateText))
                            .setPositiveButton(R.string.action_delete, (DialogInterface di2, int which2) -> {
                                mAdapter.mManager.removeTemplate(mIndex);
                                mAdapter.notifyDataSetChanged();
                            })
                            .setNegativeButton(R.string.action_cancel, null)
                            .show();
                    return true;
                });
                dialog.show();
                return true;
            });
        }

        public void bind(String template, int index, boolean isActive) {
            mTemplateText = template;
            mIndex = index;
            mText.setText(template);
            mText.setChecked(isActive);
        }

    }

    public static class HeaderHolder extends RecyclerView.ViewHolder {

        protected TextView mText;

        public HeaderHolder(View view) {
            super(view);
            mText = view.findViewById(R.id.title);
        }

        public void bind(int textId) {
            mText.setText(textId);
        }

    }

    public class ItemDecoration extends AdvancedDividerItemDecoration {

        public ItemDecoration(Context context) {
            super(context);
        }

        @Override
        public boolean hasDivider(RecyclerView parent, View view) {
            return mRecyclerView.getChildViewHolder(view).getItemViewType() != TYPE_HEADER;
        }

    }

}

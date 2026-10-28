package io.mrarm.irc.setting.fragment;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import io.mrarm.irc.NotificationRulesAdapter;
import io.mrarm.irc.R;
import io.mrarm.irc.config.NotificationRuleManager;

public class NotificationSettingsFragment extends Fragment implements NamedSettingsFragment {

    private NotificationRulesAdapter mAdapter;

    @Override
    public String getName() {
        return getString(R.string.pref_header_notifications);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.simple_list_with_fab, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.items);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new NotificationRulesAdapter(requireContext());
        recyclerView.setAdapter(mAdapter);
        recyclerView.addItemDecoration(mAdapter.createItemDecoration(requireContext()));
        mAdapter.enableDragDrop(recyclerView);

        View addButton = view.findViewById(R.id.add);
        addButton.setVisibility(View.GONE);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onDestroyView() {
        if (mAdapter != null && mAdapter.hasUnsavedChanges()) {
            NotificationRuleManager.saveUserRuleSettings(requireContext());
        }
        mAdapter = null;
        super.onDestroyView();
    }

}
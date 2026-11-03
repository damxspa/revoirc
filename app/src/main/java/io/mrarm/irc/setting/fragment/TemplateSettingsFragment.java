package io.mrarm.irc.setting.fragment;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import io.mrarm.irc.EditTemplateActivity;
import io.mrarm.irc.R;
import io.mrarm.irc.TemplatesAdapter;

public class TemplateSettingsFragment extends Fragment implements NamedSettingsFragment {

    private TemplatesAdapter mAdapter;

    @Override
    public String getName() {
        return getString(R.string.pref_header_templates);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.simple_list_with_fab, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.items);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity());
        recyclerView.setLayoutManager(layoutManager);
        mAdapter = new TemplatesAdapter(getActivity());
        recyclerView.setAdapter(mAdapter);
        recyclerView.addItemDecoration(mAdapter.createItemDecoration(getActivity()));

        View addButton = view.findViewById(R.id.add);
        addButton.setOnClickListener((View v) -> {
            startActivity(new Intent(getActivity(), EditTemplateActivity.class));
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        mAdapter.notifyDataSetChanged();
    }

}

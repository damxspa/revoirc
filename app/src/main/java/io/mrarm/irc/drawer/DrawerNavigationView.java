package io.mrarm.irc.drawer;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import io.mrarm.irc.R;

public class DrawerNavigationView extends NavigationView {

    private RecyclerView mNavList;

    public DrawerNavigationView(@NonNull Context context) {
        this(context, null);
    }

    public DrawerNavigationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DrawerNavigationView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (mNavList == null)
                mNavList = findViewById(R.id.nav_list);
            if (mNavList != null) {
                mNavList.setPadding(systemBars.left,
                        0,
                        systemBars.right,
                        systemBars.bottom);
                if (mNavList.getAdapter() instanceof DrawerMenuListAdapter) {
                    ((DrawerMenuListAdapter) mNavList.getAdapter()).setHeaderPaddingTop(systemBars.top);
                }
            }
            return insets;
        });
    }

}

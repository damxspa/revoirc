package io.mrarm.irc.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

public class InsetNavigationView extends NavigationView {

    private View mView;
    private int basePaddingTop;
    private int basePaddingBottom;

    public InsetNavigationView(@NonNull Context context) {
        this(context, null);
    }

    public InsetNavigationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public InsetNavigationView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (mView != null) {
                mView.setPadding(systemBars.left,
                        systemBars.top + basePaddingTop,
                        systemBars.right,
                        systemBars.bottom + basePaddingBottom);
            }
            return insets;
        });
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof RecyclerView) {
            mView = child;
            basePaddingTop = mView.getPaddingTop();
            basePaddingBottom = mView.getPaddingBottom();
        }
    }

}

package io.mrarm.irc;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import io.mrarm.irc.config.TemplateManager;

public class EditTemplateActivity extends ThemedActivity {

    public static final String ARG_TEMPLATE_INDEX = "template_index";
    public static final String ARG_TEMPLATE_TEXT = "template_text";

    private EditText mText;
    private int mEditingIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_template);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mText = findViewById(R.id.text);

        if (getIntent().hasExtra(ARG_TEMPLATE_INDEX)) {
            mEditingIndex = getIntent().getIntExtra(ARG_TEMPLATE_INDEX, -1);
            String text = getIntent().getStringExtra(ARG_TEMPLATE_TEXT);
            if (text != null) {
                mText.setText(text);
                mText.setSelection(text.length());
            }
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.title_activity_edit_template);
            }
        } else {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.title_activity_add_template);
            }
        }
    }

    public boolean save() {
        String val = mText.getText().toString().trim();
        if (val.isEmpty()) {
            Toast.makeText(this, R.string.edit_template_error_empty, Toast.LENGTH_SHORT).show();
            return false;
        }

        TemplateManager manager = TemplateManager.getInstance(this);
        if (mEditingIndex >= 0) {
            manager.updateTemplate(mEditingIndex, val);
        } else {
            manager.addTemplate(val);
            manager.setActiveTemplate(val);
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_edit_only_done, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_done || id == android.R.id.home) {
            if (id == R.id.action_done) {
                if (!save())
                    return true;
            }

            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null && getWindow().getDecorView() != null) {
                manager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

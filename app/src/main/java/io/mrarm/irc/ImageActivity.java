package io.mrarm.irc;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.chrisbanes.photoview.PhotoView;

import coil.request.ImageRequest;
import io.mrarm.irc.R;

public class ImageActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        PhotoView photoView = findViewById(R.id.photo_view);
        ProgressBar progressBar = findViewById(R.id.progress_bar);

        ImageRequest request = new ImageRequest.Builder(this)
                .data(url)
                .target(new coil.target.Target() {
                    @Override
                    public void onStart(android.graphics.drawable.Drawable placeholder) {
                        progressBar.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onSuccess(android.graphics.drawable.Drawable result) {
                        progressBar.setVisibility(View.GONE);
                        photoView.setImageDrawable(result);
                    }

                    @Override
                    public void onError(android.graphics.drawable.Drawable error) {
                    }
                })
                .listener(new ImageRequest.Listener() {
                    @Override
                    public void onError(ImageRequest request, coil.request.ErrorResult result) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(ImageActivity.this, R.string.image_load_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .build();
        coil.Coil.imageLoader(this).enqueue(request);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
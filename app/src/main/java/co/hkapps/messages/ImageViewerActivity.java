package co.hkapps.messages;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

/** Full-screen picture or GIF. Tap to close. */
public class ImageViewerActivity extends Activity {

    static final String EXTRA_TYPE = "type";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);
        ImageView image = findViewById(R.id.image);
        int edge = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        if (getIntent().getData() != null) ImageLoader.load(image, getIntent().getData(), edge, false);
        image.setOnClickListener(v -> finish());
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}

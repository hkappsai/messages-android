package co.hkapps.messages;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one row per SMS conversation. */
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Sms> conversations = new ArrayList<>();
    private ConversationAdapter adapter;
    private View emptyView;
    private TextView emptyText;
    private Button grantButton;
    private boolean openSettingsOnGrant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        ListView list = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        emptyText = findViewById(R.id.empty_text);
        grantButton = findViewById(R.id.grant);

        adapter = new ConversationAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Sms sms = conversations.get(position);
            Intent intent = new Intent(this, ThreadActivity.class);
            intent.putExtra(ThreadActivity.EXTRA_THREAD_ID, sms.threadId);
            intent.putExtra(ThreadActivity.EXTRA_TITLE, sms.displayName);
            startActivity(intent);
        });

        grantButton.setOnClickListener(v -> {
            if (openSettingsOnGrant) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.READ_SMS,
                        Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSIONS);
            }
        });

        if (!hasSmsPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload every time so new messages and settings changes show up.
        if (hasSmsPermission()) load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasSmsPermission()) {
            load();
        } else {
            // When the user picked "Don't ask again", only the Settings screen can grant it.
            openSettingsOnGrant = !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS);
            emptyText.setText(R.string.permission_needed);
            grantButton.setVisibility(View.VISIBLE);
        }
    }

    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void load() {
        grantButton.setVisibility(View.GONE);
        if (conversations.isEmpty()) emptyText.setText(R.string.loading);

        executor.execute(() -> {
            List<Sms> result = new SmsReader(this).loadConversations();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                conversations.clear();
                conversations.addAll(result);
                adapter.notifyDataSetChanged();
                if (result.isEmpty()) emptyText.setText(R.string.no_messages);
            });
        });
    }

    private class ConversationAdapter extends BaseAdapter {
        @Override public int getCount() { return conversations.size(); }
        @Override public Sms getItem(int position) { return conversations.get(position); }
        @Override public long getItemId(int position) { return conversations.get(position).threadId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.item_conversation, parent, false);
            Sms sms = getItem(position);

            TextView avatar = view.findViewById(R.id.avatar);
            TextView name = view.findViewById(R.id.name);
            TextView date = view.findViewById(R.id.date);
            TextView snippet = view.findViewById(R.id.snippet);

            avatar.setText(Format.initial(sms.displayName));
            name.setText(sms.displayName);
            date.setText(Format.shortDate(parent.getContext(), sms.date));
            snippet.setText(sms.incoming ? sms.body : "You: " + sms.body);

            // Bold the row when it holds unread messages.
            int style = sms.read ? Typeface.NORMAL : Typeface.BOLD;
            snippet.setTypeface(null, style);
            date.setTypeface(null, style);
            return view;
        }
    }
}

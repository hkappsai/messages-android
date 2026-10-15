package co.hkapps.messages;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;

/** Checks and requests the "default SMS app" role. */
public final class DefaultSms {

    public static final int REQUEST_CODE = 42;

    private DefaultSms() {}

    public static boolean isDefault(Context context) {
        return context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context));
    }

    public static void request(Activity activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = activity.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                activity.startActivityForResult(
                        roles.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_CODE);
                return;
            }
        }
        Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.getPackageName());
        activity.startActivityForResult(intent, REQUEST_CODE);
    }

    /** Explains why the role is needed, then asks for it. */
    public static void prompt(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.set_default)
                .setMessage("Android only lets the default SMS app send texts, receive new ones, "
                        + "mark them read or delete them.")
                .setPositiveButton(R.string.set_default, (d, w) -> request(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

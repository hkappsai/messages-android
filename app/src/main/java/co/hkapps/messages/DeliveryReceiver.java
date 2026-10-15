package co.hkapps.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** SMS delivery reports: marks a sent text "Delivered" (or failed) once the carrier confirms. */
public class DeliveryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri row = intent.getData();
        byte[] pdu = intent.getByteArrayExtra("pdu");
        if (row == null || !"content".equals(row.getScheme()) || pdu == null) return;
        String format = intent.getStringExtra("format");
        SmsMessage report = SmsMessage.createFromPdu(pdu, format);
        if (report == null) return;

        // GSM TP-Status: 0x00-0x1F done, 0x20-0x3F still trying, 0x40 and up failed.
        // CDMA reports use the same scale through getStatus().
        int status = report.getStatus();
        int value = status >= 0x40 ? Telephony.Sms.STATUS_FAILED
                : status >= 0x20 ? Telephony.Sms.STATUS_PENDING
                : Telephony.Sms.STATUS_COMPLETE;
        DebugLog.log(context, "delivery report " + status + " for " + row);
        SmsWriter.setStatus(context, row, value);
    }
}

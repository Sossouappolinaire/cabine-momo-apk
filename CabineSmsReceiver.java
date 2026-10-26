package com.cabine.momo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Reçoit les SMS. Seuls les messages MoMo / forfait / crédit (et le code de connexion
 * de l'application) sont gardés : les SMS personnels sont ignorés.
 */
public class CabineSmsReceiver extends BroadcastReceiver {

    private static final Pattern KEEP = Pattern.compile(
            "(?i)(solde|transfert|d[ée]p[ôo]t|retrait|forfait|cr[ée]dit|recharge|cabine momo)");

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;

        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) body.append(m.getMessageBody());
        String text = body.toString();
        if (!KEEP.matcher(text).find()) return;

        JSONObject it = CabineSmsStore.add(ctx, msgs[0].getOriginatingAddress(), text, msgs[0].getTimestampMillis());
        MainActivity.notifySms(it);
    }
}

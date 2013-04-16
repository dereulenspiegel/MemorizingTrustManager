package de.duenndns.ssl;

import java.security.cert.X509Certificate;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public class InteractionReceiver extends BroadcastReceiver {

	private Activity activity;

	public InteractionReceiver(Activity activity) {
		this.activity = activity;
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d("InteractionReceiver", "Received intent for decision");
		String app = intent
				.getStringExtra(MemorizingTrustManager.DECISION_INTENT_APP);
		X509Certificate cert = (X509Certificate) intent
				.getSerializableExtra(MemorizingTrustManager.DECISION_INTENT_CERT);
		int decisionId = intent.getIntExtra(
				MemorizingTrustManager.DECISION_INTENT_ID, 1);
		SSLDecisionDialogFragment dialog = SSLDecisionDialogFragment.build(app,
				decisionId, cert);
		dialog.show(activity.getFragmentManager(), "SSLDecision");
		this.abortBroadcast();
	}

	public static InteractionReceiver registerReceiver(Activity activity) {
		InteractionReceiver receiver = new InteractionReceiver(activity);

		IntentFilter filter = new IntentFilter(
				MemorizingTrustManager.DECISION_INTENT_REQUEST);
		filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
		Log.d("InteractionReceiver",
				"Registered with action " + filter.getAction(0));
		activity.registerReceiver(receiver, filter);
		return receiver;
	}

	public void unregister() {
		Log.d("InteractionReceiver", "Unregistering receiver");
		activity.unregisterReceiver(this);
	}

}

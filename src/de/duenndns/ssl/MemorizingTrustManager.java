/* MemorizingTrustManager - a TrustManager which asks the user about invalid
 *  certificates and memorizes their decision.
 *
 * Copyright (c) 2010 Georg Lukas <georg@op-co.de>
 *
 * MemorizingTrustManager.java contains the actual trust manager and interface
 * code to create a MemorizingActivity and obtain the results.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.duenndns.ssl;

import java.io.File;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.HashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

/**
 * A X509 trust manager implementation which asks the user about invalid
 * certificates and memorizes their decision.
 * <p>
 * The certificate validity is checked using the system default X509
 * TrustManager, creating a query Dialog if the check fails.
 * <p>
 * <b>WARNING:</b> This only works if a dedicated thread is used for opening
 * sockets!
 */
public class MemorizingTrustManager implements X509TrustManager {
	final static String TAG = MemorizingTrustManager.class.getSimpleName();
	public final static String DECISION_INTENT_REQUEST = "de.duenndns.ssl.DECISION";
	public final static String DECISION_INTENT_RESPONSE = "de.duenndns.ssl.RESPONSE";
	public final static String DECISION_INTENT_APP = DECISION_INTENT_REQUEST
			+ ".app";
	public final static String DECISION_INTENT_ID = DECISION_INTENT_REQUEST
			+ ".decisionId";
	public final static String DECISION_INTENT_CERT = DECISION_INTENT_REQUEST
			+ ".cert";
	public final static String DECISION_INTENT_CHOICE = DECISION_INTENT_REQUEST
			+ ".decisionChoice";

	public static String KEYSTORE_DIR = "KeyStore";
	public static String KEYSTORE_FILE = "KeyStore.bks";

	private static MemorizingTrustManager instance;

	private Context master;
	private static int decisionId = 0;
	private static HashMap<Integer, MTMDecision> openDecisions = new HashMap<Integer, MTMDecision>();

	Handler masterHandler;
	private File keyStoreFile;
	private KeyStore appKeyStore;
	private X509TrustManager defaultTrustManager;
	private X509TrustManager appTrustManager;

	private boolean useCertificatePinning = false;

	/**
	 * Creates an instance of the MemorizingTrustManager class.
	 * 
	 * You need to supply the application context. This has to be one of: -
	 * Application - Activity - Service
	 * 
	 * The context is used for file management, to display the dialog /
	 * notification and for obtaining translated strings.
	 * 
	 * @param m
	 *            Context for the application.
	 */
	private MemorizingTrustManager(Context m) {
		master = m;
		masterHandler = new Handler();

		Application app;
		if (m instanceof Application) {
			app = (Application) m;
		} else if (m instanceof Service) {
			app = ((Service) m).getApplication();
		} else if (m instanceof Activity) {
			app = ((Activity) m).getApplication();
		} else
			throw new ClassCastException(
					"MemorizingTrustManager context must be either Activity or Service!");

		File dir = app.getDir(KEYSTORE_DIR, Context.MODE_PRIVATE);
		keyStoreFile = new File(dir + File.separator + KEYSTORE_FILE);

		appKeyStore = loadAppKeyStore();
		defaultTrustManager = getTrustManager(null);
		appTrustManager = getTrustManager(appKeyStore);
	}

	public void setCertificatePinning(boolean doPin) {
		useCertificatePinning = doPin;
	}
	
	public boolean isUsingCertificatePinning(){
		return useCertificatePinning;
	}

	/**
	 * Returns a X509TrustManager list containing a new instance of
	 * TrustManagerFactory.
	 * 
	 * This function is meant for convenience only. You can use it as follows to
	 * integrate TrustManagerFactory for HTTPS sockets:
	 * 
	 * <pre>
	 * SSLContext sc = SSLContext.getInstance(&quot;TLS&quot;);
	 * sc.init(null, MemorizingTrustManager.getInstanceList(this),
	 * 		new java.security.SecureRandom());
	 * HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
	 * </pre>
	 * 
	 * @param c
	 *            Activity or Service to show the Dialog / Notification
	 */
	public static X509TrustManager[] getInstanceList(Application c) {
		return new X509TrustManager[] { getInstance(c) };
	}

	public static MemorizingTrustManager getInstance(Application app) {
		if (instance == null) {
			instance = new MemorizingTrustManager(app);
		}
		return instance;
	}

	/**
	 * Changes the path for the KeyStore file.
	 * 
	 * The actual filename relative to the app's directory will be
	 * <code>app_<i>dirname</i>/<i>filename</i></code>.
	 * 
	 * @param dirname
	 *            directory to store the KeyStore.
	 * @param filename
	 *            file name for the KeyStore.
	 */
	public static void setKeyStoreFile(String dirname, String filename) {
		KEYSTORE_DIR = dirname;
		KEYSTORE_FILE = filename;
	}

	X509TrustManager getTrustManager(KeyStore ks) {
		try {
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
			tmf.init(ks);
			for (TrustManager t : tmf.getTrustManagers()) {
				if (t instanceof X509TrustManager) {
					return (X509TrustManager) t;
				}
			}
		} catch (Exception e) {
			// Here, we are covering up errors. It might be more useful
			// however to throw them out of the constructor so the
			// embedding app knows something went wrong.
			Log.e(TAG, "getTrustManager(" + ks + ")", e);
		}
		return null;
	}

	KeyStore loadAppKeyStore() {
		KeyStore ks;
		try {
			ks = KeyStore.getInstance(KeyStore.getDefaultType());
		} catch (KeyStoreException e) {
			Log.e(TAG, "getAppKeyStore()", e);
			return null;
		}
		try {
			ks.load(null, null);
			ks.load(new java.io.FileInputStream(keyStoreFile),
					"MTM".toCharArray());
		} catch (java.io.FileNotFoundException e) {
			Log.i(TAG, "getAppKeyStore(" + keyStoreFile
					+ ") - file does not exist");
		} catch (Exception e) {
			Log.e(TAG, "getAppKeyStore(" + keyStoreFile + ")", e);
		}
		return ks;
	}

	void storeCert(X509Certificate[] chain) {
		// add all certs from chain to appKeyStore
		try {
			for (X509Certificate c : chain)
				appKeyStore.setCertificateEntry(c.getSubjectDN().toString(), c);
		} catch (KeyStoreException e) {
			Log.e(TAG, "storeCert(" + chain + ")", e);
			return;
		}

		// reload appTrustManager
		appTrustManager = getTrustManager(appKeyStore);

		// store KeyStore to file
		try {
			java.io.FileOutputStream fos = new java.io.FileOutputStream(
					keyStoreFile);
			appKeyStore.store(fos, "MTM".toCharArray());
			fos.close();
		} catch (Exception e) {
			Log.e(TAG, "storeCert(" + keyStoreFile + ")", e);
		}
	}

	// if the certificate is stored in the app key store, it is considered
	// "known"
	private boolean isCertKnown(X509Certificate cert) {
		try {
			return appKeyStore.getCertificateAlias(cert) != null;
		} catch (KeyStoreException e) {
			return false;
		}
	}

	private boolean isExpiredException(Throwable e) {
		do {
			if (e instanceof CertificateExpiredException)
				return true;
			e = e.getCause();
		} while (e != null);
		return false;
	}

	public void checkCertTrusted(X509Certificate[] chain, String authType,
			boolean isServer) throws CertificateException {
		Log.d(TAG, "checkCertTrusted(" + chain + ", " + authType + ", "
				+ isServer + ")");
		if (useCertificatePinning && !isCertKnown(chain[0])) {
			throw new CertificateException();
		} else if (useCertificatePinning) {
			return;
		}
		try {
			Log.d(TAG, "checkCertTrusted: trying appTrustManager");
			if (isServer)
				appTrustManager.checkServerTrusted(chain, authType);
			else
				appTrustManager.checkClientTrusted(chain, authType);
		} catch (CertificateException ae) {
			// if the cert is stored in our appTrustManager, we ignore
			// expiredness
			if (isExpiredException(ae)) {
				Log.i(TAG,
						"checkCertTrusted: accepting expired certificate from keystore");
				return;
			}
			if (isCertKnown(chain[0])) {
				Log.i(TAG,
						"checkCertTrusted: accepting cert already stored in keystore");
				return;
			}
			try {
				Log.d(TAG, "checkCertTrusted: trying defaultTrustManager");
				if (isServer)
					defaultTrustManager.checkServerTrusted(chain, authType);
				else
					defaultTrustManager.checkClientTrusted(chain, authType);
			} catch (CertificateException e) {
				interact(chain, authType, e);
			}
		} catch (Exception e) {
			Log.e(TAG, "Error while checking the certificate", e);
		}
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType)
			throws CertificateException {
		checkCertTrusted(chain, authType, false);
	}

	public void checkServerTrusted(X509Certificate[] chain, String authType)
			throws CertificateException {
		checkCertTrusted(chain, authType, true);
	}

	public X509Certificate[] getAcceptedIssuers() {
		Log.d(TAG, "getAcceptedIssuers()");
		return defaultTrustManager.getAcceptedIssuers();
	}

	private int createDecisionId(MTMDecision d) {
		int myId;
		synchronized (openDecisions) {
			myId = decisionId;
			openDecisions.put(myId, d);
			decisionId += 1;
		}
		return myId;
	}

	private static String hexString(byte[] data) {
		StringBuffer si = new StringBuffer();
		for (int i = 0; i < data.length; i++) {
			si.append(String.format("%02x", data[i]));
			if (i < data.length - 1)
				si.append(":");
		}
		return si.toString();
	}

	public static String certHash(final X509Certificate cert, String digest) {
		try {
			MessageDigest md = MessageDigest.getInstance(digest);
			md.update(cert.getEncoded());
			return hexString(md.digest());
		} catch (java.security.cert.CertificateEncodingException e) {
			return e.getMessage();
		} catch (java.security.NoSuchAlgorithmException e) {
			return e.getMessage();
		}
	}

	private String certChainMessage(final X509Certificate[] chain,
			CertificateException cause) {
		Throwable e = cause;
		Log.d(TAG, "certChainMessage for " + e);
		StringBuffer si = new StringBuffer();
		if (e.getCause() != null) {
			e = e.getCause();
			si.append(e.getLocalizedMessage());
			// si.append("\n");
		}
		for (X509Certificate c : chain) {
			si.append("\n\n");
			si.append(c.getSubjectDN().toString());
			si.append("\nMD5: ");
			si.append(certHash(c, "MD5"));
			si.append("\nSHA1: ");
			si.append(certHash(c, "SHA-1"));
			si.append("\nSigned by: ");
			si.append(c.getIssuerDN().toString());
		}
		return si.toString();
	}

	void interact(final X509Certificate[] chain, String authType,
			CertificateException cause) throws CertificateException {
		/* prepare the MTMDecision blocker object */
		MTMDecision choice = new MTMDecision();
		final int myId = createDecisionId(choice);
		final String certMessage = certChainMessage(chain, cause);

		BroadcastReceiver decisionReceiver = new BroadcastReceiver() {
			public void onReceive(Context ctx, Intent i) {
				interactResult(i);
			}
		};
		master.registerReceiver(decisionReceiver, new IntentFilter(
				DECISION_INTENT_RESPONSE));
		masterHandler.post(new Runnable() {
			public void run() {
				Intent ni = new Intent(DECISION_INTENT_REQUEST);
				ni.putExtra(DECISION_INTENT_APP, master.getPackageName());
				ni.putExtra(DECISION_INTENT_ID, myId);
				ni.putExtra(DECISION_INTENT_CERT, chain[0]);
				ni.setPackage(master.getPackageName());
				Log.d(TAG, "Sending broadcast to get a decision with action: "
						+ ni.getAction());
				try {
					master.sendOrderedBroadcast(ni, null);
				} catch (Exception e) {
					Log.e(TAG, "startActivity: " + e, e);
				}
			}
		});

		Log.d(TAG, "openDecisions: " + openDecisions);
		Log.d(TAG, "waiting on " + myId);
		try {
			synchronized (choice) {
				choice.wait();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		master.unregisterReceiver(decisionReceiver);
		Log.d(TAG, "finished wait on " + myId + ": " + choice.state);
		switch (choice.state) {
		case MTMDecision.DECISION_ALWAYS:
			storeCert(chain);
		case MTMDecision.DECISION_ONCE:
			break;
		default:
			throw (cause);
		}
	}

	public static void interactResult(Intent i) {
		int decisionId = i.getIntExtra(DECISION_INTENT_ID,
				MTMDecision.DECISION_INVALID);
		int choice = i.getIntExtra(DECISION_INTENT_CHOICE,
				MTMDecision.DECISION_INVALID);
		Log.d(TAG, "interactResult: " + decisionId + " chose " + choice);
		Log.d(TAG, "openDecisions: " + openDecisions);

		MTMDecision d;
		synchronized (openDecisions) {
			d = openDecisions.get(decisionId);
			openDecisions.remove(decisionId);
		}
		if (d == null) {
			Log.e(TAG,
					"interactResult: aborting due to stale decision reference!");
			return;
		}
		synchronized (d) {
			d.state = choice;
			d.notify();
		}
	}

	public static void registerTrustManager(Application app)
			throws TrustManagerException {
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, MemorizingTrustManager.getInstanceList(app),
					new java.security.SecureRandom());
			SSLContext.setDefault(sc);
			HttpsURLConnection
					.setDefaultSSLSocketFactory(sc.getSocketFactory());
		} catch (Exception e) {
			throw new TrustManagerException("Can't set up TrustStore", e);
		}
	}

}

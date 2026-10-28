package io.mrarm.irc.config;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.mrarm.irc.util.ColoredTextBuilder;

public class ServerCertificateManager {

    private static final String TAG = "CertificateManager";

    // Use ConcurrentHashMap for thread-safe access
    private static final Map<String, ServerCertificateManager> sInstances = new ConcurrentHashMap<>();
    // ReferenceQueue for deterministic cleanup of garbage-collected instances
    private static final ReferenceQueue<ServerCertificateManager> sReferenceQueue = new ReferenceQueue<>();
    // PhantomReferences to track GC'd instances
    private static final Map<PhantomReference<ServerCertificateManager>, String> sPhantomRefs = new ConcurrentHashMap<>();

    public static ServerCertificateManager get(File file) {
        // Clean up any GC'd instances first
        cleanupStaleReferences();
        
        String path = file.getAbsolutePath();
        ServerCertificateManager instance = sInstances.get(path);
        if (instance != null) {
            return instance;
        }
        
        ServerCertificateManager ret = new ServerCertificateManager(file);
        // Manual putIfAbsent for API 21 compatibility
        synchronized (sInstances) {
            instance = sInstances.get(path);
            if (instance != null) {
                ret.close();
                return instance;
            }
            sInstances.put(path, ret);
            return ret;
        }
    }

    public static ServerCertificateManager get(Context context, UUID serverUUID) {
        return get(ServerConfigManager.getInstance(context).getServerSSLCertsFile(serverUUID));
    }

    /**
     * Explicitly releases a certificate manager instance.
     * Should be called when the manager is no longer needed.
     */
    public static void release(File file) {
        String path = file.getAbsolutePath();
        ServerCertificateManager instance = sInstances.remove(path);
        if (instance != null) {
            instance.close();
            // Remove phantom reference - manual iteration for API 21 compatibility
            for (Map.Entry<PhantomReference<ServerCertificateManager>, String> entry : sPhantomRefs.entrySet()) {
                if (path.equals(entry.getValue())) {
                    sPhantomRefs.remove(entry.getKey());
                    break;
                }
            }
        }
    }

    /**
     * Releases all certificate manager instances and clears the cache.
     * Should be called on application shutdown.
     */
    public static void shutdownAll() {
        for (ServerCertificateManager instance : sInstances.values()) {
            instance.close();
        }
        sInstances.clear();
        sPhantomRefs.clear();
        // Drain reference queue
        Reference<?> ref;
        while ((ref = sReferenceQueue.poll()) != null) {
            ref.clear();
        }
    }

    /**
     * Cleans up stale references from the cache.
     * Call periodically or before get() to prevent memory leaks.
     */
    @SuppressWarnings("unchecked")
    private static void cleanupStaleReferences() {
        Reference<? extends ServerCertificateManager> ref;
        while ((ref = sReferenceQueue.poll()) != null) {
            PhantomReference<ServerCertificateManager> phantomRef = (PhantomReference<ServerCertificateManager>) ref;
            String path = sPhantomRefs.remove(phantomRef);
            if (path != null) {
                ServerCertificateManager instance = sInstances.remove(path);
                if (instance != null) {
                    instance.close();
                }
            }
            phantomRef.clear();
        }
    }

    private final File mKeyStoreFile;
    private KeyStore mKeyStore;
    private X509TrustManager mKeyStoreTrustManager;
    // PhantomReference to this instance for cleanup when GC'd
    private final PhantomReference<ServerCertificateManager> mPhantomRef;

    private ServerCertificateManager(File keyStoreFile) {
        mKeyStoreFile = keyStoreFile;
        // Create phantom reference for GC tracking
        mPhantomRef = new PhantomReference<>(this, sReferenceQueue);
        sPhantomRefs.put(mPhantomRef, keyStoreFile.getAbsolutePath());
        
        if (keyStoreFile != null && keyStoreFile.exists()) { // keyStoreFile is never null in practice, but defensive check
            try {
                loadKeyStore(new FileInputStream(mKeyStoreFile));
            } catch (Exception e) {
                Log.w(TAG, "Failed to load keystore", e);
                mKeyStore = null;
            }
        }
    }

    /**
     * Explicitly closes this certificate manager and releases resources.
     */
    public void close() {
        synchronized (this) {
            mKeyStore = null;
            mKeyStoreTrustManager = null;
        }
        // Clear phantom reference
        mPhantomRef.clear();
        sPhantomRefs.remove(mPhantomRef);
    }

    public void loadKeyStore(InputStream stream) throws KeyStoreException, IOException,
            CertificateException, NoSuchAlgorithmException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream cannot be null");
        }
        synchronized (this) {
            mKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            mKeyStore.load(stream, null);
            // Invalidate cached trust manager since keystore changed
            mKeyStoreTrustManager = null;
        }
    }

    private void createKeyStoreIfNull() throws KeyStoreException, IOException, CertificateException,
            NoSuchAlgorithmException {
        synchronized (this) {
            if (mKeyStore == null) {
                mKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                mKeyStore.load(null, null);
                mKeyStoreTrustManager = null;
            }
        }
    }

    public void saveKeyStore(OutputStream stream) throws KeyStoreException, IOException,
            CertificateException, NoSuchAlgorithmException {
        synchronized (this) {
            createKeyStoreIfNull();
            mKeyStore.store(stream, null);
        }
    }

    public void saveKeyStore() throws KeyStoreException, IOException, CertificateException,
            NoSuchAlgorithmException {
        saveKeyStore(new FileOutputStream(mKeyStoreFile));
    }

    public void addCertificateException(X509Certificate certificate) {
        synchronized (this) {
            try {
                createKeyStoreIfNull();
                mKeyStore.setCertificateEntry("cert-" + UUID.randomUUID(), certificate);
                if (mKeyStoreFile != null)
                    saveKeyStore();
            } catch (Exception e) {
                Log.e(TAG, "Failed to add certificate exception", e);
            }
        }
    }

    public void removeCertificate(String alias) {
        synchronized (this) {
            if (mKeyStore == null)
                return;
            try {
                mKeyStore.deleteEntry(alias);
                if (mKeyStoreFile != null)
                    saveKeyStore();
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove certificate", e);
            }
        }
    }

    public List<String> getCertificateAliases() {
        synchronized (this) {
            if (mKeyStore == null)
                return null;
            try {
                return Collections.list(mKeyStore.aliases());
            } catch (KeyStoreException e) {
                return null;
            }
        }
    }

    public X509Certificate getCertificate(String alias) {
        synchronized (this) {
            try {
                return (X509Certificate) mKeyStore.getCertificate(alias);
            } catch (KeyStoreException e) {
                return null;
            }
        }
    }

    public boolean hasCertificate(Certificate certificate) throws KeyStoreException {
        synchronized (this) {
            if (mKeyStore == null) return false;
            return mKeyStore.getCertificateAlias(certificate) != null;
        }
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        synchronized (this) {
            if (mKeyStoreTrustManager == null && mKeyStore != null)
                mKeyStoreTrustManager = createKeyStoreTrustManager(mKeyStore);
            if (mKeyStoreTrustManager == null)
                    throw new CertificateException("Key store is null");
            mKeyStoreTrustManager.checkServerTrusted(chain, authType);
        }
    }

    public static SpannableString buildCertOverviewString(X509Certificate cert) {
        String sha1Fingerprint;
        try {
            StringBuilder builder = new StringBuilder();
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(cert.getEncoded());
            for (byte b : bytes)
                builder.append(String.format("%02x ", b));
            sha1Fingerprint = builder.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new RuntimeException(e);
        }

        ColoredTextBuilder builder = new ColoredTextBuilder();
        builder.append("Subject: ", new StyleSpan(Typeface.BOLD));
        builder.append(cert.getSubjectX500Principal().getName().replace(",", ",\u200B"));
        builder.append("\nApplies to: ", new StyleSpan(Typeface.BOLD));
        builder.append(buildCertAppliesToString(cert));
        builder.append("\nIssuer: ", new StyleSpan(Typeface.BOLD));
        builder.append(cert.getIssuerDN().toString().replace(",", ",\u200B"));
        builder.append("\nSHA1 fingerprint:\n", new StyleSpan(Typeface.BOLD));
        builder.append(sha1Fingerprint);
        return SpannableString.valueOf(builder.getSpannable());
    }

    public static String buildCertAppliesToString(X509Certificate cert) {
        List<String> elements = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> altName : altNames) {
                    Integer altNameType = (Integer) altName.get(0);
                    if (altNameType != 2 && altNameType != 7) // dns or ip
                        continue;
                    elements.add((String) altName.get(1));
                }
            }
        } catch (CertificateParsingException ignored) {
        }

        if (elements.isEmpty())
            return "none";
        return TextUtils.join(",", elements.toArray());
    }

    public static X509TrustManager createKeyStoreTrustManager(KeyStore keyStore) {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager)
                    return (X509TrustManager) manager;
            }
        } catch (NoSuchAlgorithmException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
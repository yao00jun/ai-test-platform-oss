package com.aitest.ai;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Opt-in TLS relaxation for company-internal model gateways whose certificate is self-signed or issued by a
 * private CA that the JDK does not know. Certificate chain and hostname checks are both skipped, so the switch
 * is only meant for intranet addresses; the UI says so explicitly.
 */
final class ModelTls {
    private ModelTls() { }
    static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
    static final HostnameVerifier ANY_HOST = (hostname, session) -> true;
    static SSLSocketFactory trustAllSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("TLS context unavailable", failure); }
    }
}

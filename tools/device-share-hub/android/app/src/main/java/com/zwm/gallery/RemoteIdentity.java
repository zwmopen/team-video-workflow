package com.zwm.gallery;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

/** Device-local keys for remote relay authentication; private keys never leave Android Keystore. */
final class RemoteIdentity {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String SIGNING_ALIAS = "album.remote.signing.v1";
    private static final String AGREEMENT_ALIAS = "album.remote.agreement.v1";

    private RemoteIdentity() { }

    static void ensure(Context context) throws Exception {
        KeyStore store = keyStore();
        ensureKey(store, SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
        ensureKey(store, AGREEMENT_ALIAS, KeyProperties.PURPOSE_AGREE_KEY);
    }

    static JSONObject publicKeys(Context context) throws Exception {
        ensure(context);
        KeyStore store = keyStore();
        ECPublicKey signing = (ECPublicKey) store.getCertificate(SIGNING_ALIAS).getPublicKey();
        ECPublicKey agreement = (ECPublicKey) store.getCertificate(AGREEMENT_ALIAS).getPublicKey();
        return new JSONObject()
                .put("signingPublicKey", RemoteProtocol.publicJwk(signing))
                .put("agreementPublicKey", RemoteProtocol.publicJwk(agreement));
    }

    static String sign(Context context, Object value) throws Exception {
        ensure(context);
        PrivateKey privateKey = (PrivateKey) keyStore().getKey(SIGNING_ALIAS, null);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(RemoteProtocol.canonicalBytes(value));
        return RemoteProtocol.base64Url(RemoteProtocol.derSignatureToRaw(signature.sign()));
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private static void ensureKey(KeyStore store, String alias, int purposes) throws Exception {
        if (store.containsAlias(alias)) return;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias, purposes)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false);
        if ((purposes & KeyProperties.PURPOSE_SIGN) != 0) {
            builder.setDigests(KeyProperties.DIGEST_SHA256);
        }
        generator.initialize(builder.build());
        generator.generateKeyPair();
    }
}

package com.zwm.gallery;

import android.os.SystemClock;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Resolves the production relay over authenticated HTTPS when local DNS is unreliable. */
final class RelayDns implements Dns {
    private static final String HOST = HttpUrl.get(RemoteRelayProfile.DEFAULT_ENDPOINT).host();
    private final OkHttpClient resolver = new OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .dns(host -> "cloudflare-dns.com".equals(host)
                    ? java.util.Arrays.asList(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                    : Dns.SYSTEM.lookup(host))
            .followRedirects(false)
            .build();
    private List<InetAddress> cached = Collections.emptyList();
    private long expiresAt;

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (!HOST.equalsIgnoreCase(hostname)) return Dns.SYSTEM.lookup(hostname);
        List<InetAddress> resolved = resolveProductionHost();
        if (!resolved.isEmpty()) return resolved;
        return Dns.SYSTEM.lookup(hostname);
    }

    private synchronized List<InetAddress> resolveProductionHost() {
        long now = SystemClock.elapsedRealtime();
        if (now < expiresAt) return cached;
        HttpUrl url = HttpUrl.get("https://cloudflare-dns.com/dns-query").newBuilder()
                .addQueryParameter("name", HOST)
                .addQueryParameter("type", "A")
                .build();
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/dns-json").build();
        cached = Collections.emptyList();
        expiresAt = now + 15_000;
        try (Response response = resolver.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                android.util.Log.w("RelayDns", "HTTPS DNS returned " + response.code());
                return cached;
            }
            JSONObject result = new JSONObject(response.body().string());
            if (result.optInt("Status", -1) != 0) return cached;
            JSONArray answers = result.optJSONArray("Answer");
            if (answers == null) return cached;
            List<InetAddress> addresses = new ArrayList<>();
            long ttl = 120;
            for (int i = 0; i < answers.length(); i++) {
                JSONObject answer = answers.optJSONObject(i);
                if (answer == null || answer.optInt("type") != 1) continue;
                String addr = answer.optString("data");
                if (!addr.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) continue;
                addresses.add(InetAddress.getByName(addr));
                ttl = Math.min(ttl, Math.max(15, answer.optLong("TTL", 120)));
            }
            cached = Collections.unmodifiableList(addresses);
            if (!cached.isEmpty()) expiresAt = now + ttl * 1000;
        } catch (Exception ignored) {
            android.util.Log.w("RelayDns", "HTTPS DNS unavailable: " + ignored.getClass().getSimpleName());
        }
        return cached;
    }
}

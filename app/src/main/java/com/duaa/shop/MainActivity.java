package com.duaa.shop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        java.net.CookieHandler.setDefault(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL));
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeo = callback;
                    pendingOrigin = origin;
                    if (Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
                    }
                }
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasLocation() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 7 && pendingGeo != null) {
            pendingGeo.invoke(pendingOrigin, hasLocation(), false);
            pendingGeo = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void deliver(final String id, final boolean ok, final String body) {
        final String js;
        try {
            js = "window.__nativeDone(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(body) + ")";
        } catch (Exception e) {
            return;
        }
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    class Bridge {
        /** GET a page over HTTPS from the phone (no browser CORS limits). Result arrives via window.__nativeDone. */
        @JavascriptInterface
        public void fetch(final String id, final String url) { request(id, url, null, null, null); }

        /** POST form data (used for D4D's "View More" search). Cookies are kept between calls. */
        @JavascriptInterface
        public void post(final String id, final String url, final String body, final String csrf, final String referer) {
            request(id, url, body, csrf, referer);
        }

        private void request(final String id, final String url, final String body, final String csrf, final String referer) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol())) throw new Exception("https only");
                    String host = u.getHost();
                    if (!(host.endsWith("d4donline.com") || host.endsWith("clicflyer.com"))) throw new Exception("host not allowed");
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(25000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept-Language", "en");
                    if (body != null) {
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                        c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
                        c.setRequestProperty("Origin", "https://d4donline.com");
                        if (csrf != null) c.setRequestProperty("X-CSRF-Token", csrf);
                        if (referer != null) c.setRequestProperty("Referer", referer);
                        byte[] out = body.getBytes(StandardCharsets.UTF_8);
                        c.getOutputStream().write(out);
                        c.getOutputStream().close();
                    }
                    int code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        r.close();
                    }
                    deliver(id, code < 400, code < 400 ? sb.toString() : ("HTTP " + code));
                } catch (Exception e) {
                    deliver(id, false, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url != null && url.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        }
    }
}

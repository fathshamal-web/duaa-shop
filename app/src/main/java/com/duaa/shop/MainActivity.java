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
        /** Fetch a page over HTTPS from the phone (no browser CORS limits). Result arrives via window.__nativeDone. */
        @JavascriptInterface
        public void fetch(final String id, final String url) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol())) throw new Exception("https only");
                    String host = u.getHost();
                    if (!(host.endsWith("d4donline.com") || host.endsWith("clicflyer.com"))) throw new Exception("host not allowed");
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept-Language", "en");
                    int code = c.getResponseCode();
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    r.close();
                    deliver(id, code < 400, sb.toString());
                } catch (Exception e) {
                    deliver(id, false, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        /** Diagnostic fetch: returns JSON {code, len, finalUrl, head} so the user can report what a site sends back. */
        @JavascriptInterface
        public void probe(final String id, final String url) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                JSONObject o = new JSONObject();
                try {
                    URL u = new URL(url);
                    if (!"https".equals(u.getProtocol()) || !(u.getHost().endsWith("clicflyer.com") || u.getHost().endsWith("d4donline.com")))
                        throw new Exception("host not allowed");
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
                    c.setRequestProperty("Accept-Language", "en");
                    int code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    if (in != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                        r.close();
                    }
                    String body = sb.toString();
                    o.put("code", code);
                    o.put("len", body.length());
                    o.put("finalUrl", c.getURL().toString());
                    o.put("body", body.length() > 400000 ? body.substring(0, 400000) : body);
                    deliver(id, true, o.toString());
                } catch (Exception e) {
                    deliver(id, false, String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Duaa Shop test", text));
                android.widget.Toast.makeText(MainActivity.this, "Copied. Paste it to Claude.", android.widget.Toast.LENGTH_SHORT).show();
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

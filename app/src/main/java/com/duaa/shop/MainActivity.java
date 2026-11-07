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
    private String pendingLocId;
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
        android.content.IntentFilter f = new android.content.IntentFilter(ACTION_INSTALL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installReceiver, f);
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
        if (requestCode == 8 && pendingLocId != null) {
            String id = pendingLocId;
            pendingLocId = null;
            if (hasLocation()) locate(id);
            else deliver(id, false, "denied");
        }
    }

    /** Get the phone's position with Android's own location service and return {"lat":..,"lon":..}. */
    @SuppressWarnings("MissingPermission")
    private void locate(final String id) {
        final android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { deliver(id, false, "unavailable"); return; }
        boolean net = false, gps = false;
        try { net = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        try { gps = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        if (!net && !gps) { deliver(id, false, "off"); return; }
        android.location.Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {}
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 15 * 60 * 1000) { sendLoc(id, best); return; }
        final android.location.Location fallback = best;
        final boolean[] done = {false};
        final String provider = net ? android.location.LocationManager.NETWORK_PROVIDER : android.location.LocationManager.GPS_PROVIDER;
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final android.location.LocationListener listener = new android.location.LocationListener() {
            @Override public void onLocationChanged(android.location.Location l) {
                if (done[0]) return; done[0] = true; lm.removeUpdates(this); sendLoc(id, l);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            lm.requestLocationUpdates(provider, 0, 0, listener, getMainLooper());
            if (net && gps) lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 0, 0, listener, getMainLooper());
        } catch (Exception e) { deliver(id, false, String.valueOf(e.getMessage())); return; }
        h.postDelayed(() -> {
            if (done[0]) return; done[0] = true; lm.removeUpdates(listener);
            if (fallback != null) sendLoc(id, fallback); else deliver(id, false, "timeout");
        }, 20000);
    }

    private void sendLoc(String id, android.location.Location l) {
        deliver(id, true, "{\"lat\":" + l.getLatitude() + ",\"lon\":" + l.getLongitude() + "}");
    }

    // ---------- Self-update from GitHub Releases ----------
    private static final String REL = "https://github.com/fathshamal-web/duaa-shop/releases/download/latest/";
    private static final String ACTION_INSTALL = "com.duaa.shop.INSTALL_STATUS";
    private String installId;
    private final android.content.BroadcastReceiver installReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent intent) {
            int status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(confirm); }
                return;
            }
            String id = installId; installId = null;
            if (id == null) return;
            if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) deliver(id, true, "ok");
            else deliver(id, false, String.valueOf(intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)) + " (" + status + ")");
        }
    };

    private int myVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private HttpURLConnection openGithub(String url) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = null;
        for (int i = 0; i < 6; i++) {
            if (!"https".equals(u.getProtocol())) throw new Exception("https only");
            String h = u.getHost();
            if (!(h.equals("github.com") || h.endsWith(".githubusercontent.com"))) throw new Exception("host not allowed: " + h);
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "DuaaShop-updater");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { String loc = c.getHeaderField("Location"); c.disconnect(); u = new URL(u, loc); continue; }
            if (code >= 400) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("too many redirects");
    }

    private void doInstall(final String id) {
        pool.execute(() -> {
            HttpURLConnection c = null;
            android.content.pm.PackageInstaller.Session session = null;
            try {
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams params =
                        new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName());
                int sid = pi.createSession(params);
                session = pi.openSession(sid);
                c = openGithub(REL + "DuaaShop.apk");
                long len = c.getContentLengthLong();
                try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream out = session.openWrite("DuaaShop.apk", 0, len)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent i = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                int flags = PendingIntent_FLAGS();
                android.app.PendingIntent pIntent = android.app.PendingIntent.getBroadcast(this, sid, i, flags);
                installId = id;
                session.commit(pIntent.getIntentSender());
                session.close();
            } catch (Exception e) {
                if (session != null) session.abandon();
                deliver(id, false, String.valueOf(e.getMessage()));
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private static int PendingIntent_FLAGS() {
        int f = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) f |= android.app.PendingIntent.FLAG_MUTABLE;
        return f;
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) {}
        super.onDestroy();
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
        public int version() { return myVersion(); }

        /** Reads version.json from the latest GitHub release: {"versionCode":..,"versionName":"..","notes":".."} */
        @JavascriptInterface
        public void checkUpdate(final String id) {
            pool.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = openGithub(REL + "version.json");
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    deliver(id, true, sb.toString());
                } catch (Exception e) {
                    deliver(id, false, String.valueOf(e.getMessage()));
                } finally { if (c != null) c.disconnect(); }
            });
        }

        @JavascriptInterface
        public void installUpdate(final String id) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                    deliver(id, false, "need_permission");
                    return;
                }
                doInstall(id);
            });
        }

        @JavascriptInterface
        public void getLocation(final String id) {
            runOnUiThread(() -> {
                if (hasLocation()) locate(id);
                else if (Build.VERSION.SDK_INT >= 23) {
                    pendingLocId = id;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 8);
                } else deliver(id, false, "denied");
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url != null && url.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        }
    }
}

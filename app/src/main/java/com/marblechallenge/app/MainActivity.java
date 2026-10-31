package com.marblechallenge.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MarbleUpdater";
    private static final String BASE_URL = "https://0ahmedegypt0-collab.github.io/Marble-Challenge/";
    private static final String ORIGIN_URL = "file:///android_asset/game.html";
    private WebView webView;
    private final ExecutorService updater = Executors.newSingleThreadExecutor();

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        immersive();
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF080316);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        // Keep the same file:// base URL so existing WebView localStorage remains available.
        File cachedGame = new File(getFilesDir(), "game-update.html");
        if (cachedGame.isFile() && cachedGame.length() > 1000) {
            webView.loadDataWithBaseURL(ORIGIN_URL, readFile(cachedGame), "text/html", "UTF-8", null);
        } else {
            webView.loadUrl(ORIGIN_URL);
        }

        // Download in the background. The verified update is used on the next app launch,
        // never by reloading a game while the player may be in the middle of a level.
        updater.execute(() -> checkForUpdate());
    }

    private void checkForUpdate() {
        HttpURLConnection connection = null;
        try {
            String manifestText = downloadText(BASE_URL + "version.json");
            JSONObject manifest = new JSONObject(manifestText);
            String remoteVersion = manifest.getString("version");
            String expectedHash = manifest.getString("sha256").toLowerCase();
            if (remoteVersion.isEmpty() || expectedHash.length() != 64) return;

            File versionFile = new File(getFilesDir(), "game-update-version.txt");
            String installedVersion = versionFile.isFile() ? readFile(versionFile).trim() : "";
            if (remoteVersion.equals(installedVersion)) return;

            byte[] gameBytes = downloadBytes(BASE_URL + "game.html");
            String actualHash = sha256(gameBytes);
            if (!actualHash.equals(expectedHash)) {
                Log.w(TAG, "Update checksum mismatch; keeping current game.");
                return;
            }
            String html = new String(gameBytes, "UTF-8");
            if (html.length() < 1000 || !html.toLowerCase().contains("</html>")) return;

            File target = new File(getFilesDir(), "game-update.html");
            File temp = new File(getFilesDir(), "game-update.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(gameBytes);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) return;
            if (!temp.renameTo(target)) return;

            File versionTemp = new File(getFilesDir(), "game-update-version.tmp");
            try (FileOutputStream out = new FileOutputStream(versionTemp)) {
                out.write(remoteVersion.getBytes("UTF-8"));
                out.getFD().sync();
            }
            File versionTarget = new File(getFilesDir(), "game-update-version.txt");
            if (versionTarget.exists()) versionTarget.delete();
            versionTemp.renameTo(versionTarget);
            Log.i(TAG, "Verified game update saved for next launch: " + remoteVersion);
        } catch (Exception e) {
            Log.w(TAG, "Update check failed; offline/local game remains available.", e);
        }
    }

    private String downloadText(String address) throws Exception {
        return new String(downloadBytes(address), "UTF-8");
    }

    private byte[] downloadBytes(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "MarbleChallenge-Android");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > 8 * 1024 * 1024) throw new IllegalStateException("Update is too large");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private String readFile(File file) {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "Could not read cached game.", e);
            return "";
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        updater.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}

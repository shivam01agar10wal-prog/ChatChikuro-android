package com.chatchikuro.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.view.WindowManager;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.widget.ProgressBar;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.content.ContentValues;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private String fcmTokenForWebView = "";

    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    private static final String WEBSITE_URL =
            "https://chatchikuro-4o.edgeone.dev/";

    private ValueCallback<Uri[]> fileUploadCallback;
    private Uri cameraImageUri;

    private boolean isOfflinePageShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * ============================================================
         * WINDOW / EDGE-TO-EDGE FIX
         * ============================================================
         */

        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        if (webView != null) {
            webView.setFitsSystemWindows(true);
        }

        setupWebView();

        /*
         * ============================================================
         * SERVICE WORKER
         * ============================================================
         */

        if (Build.VERSION.SDK_INT >= 24) {

            ServiceWorkerController swController =
                    ServiceWorkerController.getInstance();

            swController.setServiceWorkerClient(
                    new ServiceWorkerClient() {

                        @Override
                        public WebResourceResponse shouldInterceptRequest(
                                WebResourceRequest request
                        ) {
                            return null;
                        }
                    }
            );
        }

        /*
         * ============================================================
         * NOTIFICATION PERMISSION
         * ============================================================
         */

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.POST_NOTIFICATIONS
                        },
                        1001
                );
            }
        }

        /*
         * ============================================================
         * FCM TOKEN
         * ============================================================
         */

        try {

            com.google.firebase.messaging.FirebaseMessaging
                    .getInstance()
                    .getToken()
                    .addOnCompleteListener(task -> {

                        if (task.isSuccessful()
                                && task.getResult() != null) {

                            final String fcmToken = task.getResult();

                            fcmTokenForWebView = fcmToken;

                            android.util.Log.d(
                                    "FCM_TOKEN",
                                    "Token received: " + fcmToken
                            );

                            runOnUiThread(() -> {

                                if (webView != null
                                        && !isOfflinePageShown) {

                                    webView.evaluateJavascript(
                                            "window.FCM_TOKEN = '"
                                                    + escapeJavaScript(fcmToken)
                                                    + "'; window.dispatchEvent(new Event('fcm_token_ready')); console.log('FCM Token set');",
                                            null
                                    );
                                }
                            });

                            new Thread(() -> {

                                try {

                                    String endpoint =
                                            "https://chatchikuro-4o.edgeone.dev/fcm_token.php";

                                    android.util.Log.d(
                                            "FCM_TOKEN",
                                            "Sending token to: " + endpoint
                                    );

                                    java.net.URL tokenUrl =
                                            new java.net.URL(endpoint);

                                    java.net.HttpURLConnection conn =
                                            (java.net.HttpURLConnection)
                                                    tokenUrl.openConnection();

                                    conn.setRequestMethod("POST");

                                    conn.setRequestProperty(
                                            "Content-Type",
                                            "application/json"
                                    );

                                    conn.setRequestProperty(
                                            "User-Agent",
                                            "ChatChikuro/1.0"
                                    );

                                    conn.setDoOutput(true);
                                    conn.setConnectTimeout(15000);
                                    conn.setReadTimeout(15000);

                                    String body =
                                            "{\"token\":\""
                                                    + fcmToken
                                                    + "\"}";

                                    try (
                                            java.io.OutputStream os =
                                                    conn.getOutputStream()
                                    ) {

                                        os.write(
                                                body.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8
                                                )
                                        );
                                    }

                                    int status =
                                            conn.getResponseCode();

                                    android.util.Log.d(
                                            "FCM_TOKEN",
                                            "POST response: HTTP "
                                                    + status
                                    );

                                    conn.disconnect();

                                    /*
                                     * GET fallback
                                     */

                                    if (status < 200 || status >= 300) {

                                        String t =
                                                java.net.URLEncoder.encode(
                                                        fcmToken,
                                                        "UTF-8"
                                                );

                                        java.net.URL fallbackUrl =
                                                new java.net.URL(
                                                        endpoint
                                                                + "?token="
                                                                + t
                                                );

                                        java.net.HttpURLConnection
                                                fallbackConn =
                                                (java.net.HttpURLConnection)
                                                        fallbackUrl
                                                                .openConnection();

                                        fallbackConn.setRequestMethod("GET");

                                        fallbackConn.setRequestProperty(
                                                "User-Agent",
                                                "ChatChikuro/1.0"
                                        );

                                        fallbackConn.setConnectTimeout(15000);
                                        fallbackConn.setReadTimeout(15000);

                                        int fbStatus =
                                                fallbackConn.getResponseCode();

                                        android.util.Log.d(
                                                "FCM_TOKEN",
                                                "GET fallback response: HTTP "
                                                        + fbStatus
                                        );

                                        fallbackConn.disconnect();
                                    }

                                } catch (Exception e) {

                                    android.util.Log.e(
                                            "FCM_TOKEN",
                                            "Error sending token: "
                                                    + e.getMessage(),
                                            e
                                    );
                                }

                            }).start();

                        } else {

                            android.util.Log.e(
                                    "FCM_TOKEN",
                                    "Failed to get token: "
                                            + (
                                            task.getException() != null
                                                    ? task.getException()
                                                    .getMessage()
                                                    : "unknown error"
                                    )
                            );
                        }
                    });

        } catch (Exception e) {

            android.util.Log.e(
                    "FCM_TOKEN",
                    "Firebase init error: "
                            + e.getMessage(),
                    e
            );
        }

        /*
         * ============================================================
         * BACKGROUND NOTIFICATION WORKER
         * ============================================================
         */

        PeriodicWorkRequest workRequest =
                new PeriodicWorkRequest.Builder(
                        NotificationWorker.class,
                        15,
                        TimeUnit.MINUTES
                ).build();

        WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                        "notification_check",
                        ExistingPeriodicWorkPolicy.KEEP,
                        workRequest
                );

        /*
         * ============================================================
         * RUNTIME PERMISSIONS
         * ============================================================
         */

        java.util.List<String> permissionsNeeded =
                new java.util.ArrayList<>();

        String[] requiredPerms = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
        };

        for (String perm : requiredPerms) {

            if (ContextCompat.checkSelfPermission(
                    this,
                    perm
            ) != PackageManager.PERMISSION_GRANTED) {

                permissionsNeeded.add(perm);
            }
        }

        if (!permissionsNeeded.isEmpty()) {

            ActivityCompat.requestPermissions(
                    this,
                    permissionsNeeded.toArray(new String[0]),
                    2001
            );
        }

        /*
         * ============================================================
         * DEEP LINK
         * ============================================================
         */

        handleIntent(getIntent());

        /*
         * ============================================================
         * INITIAL LOAD
         * ============================================================
         */

        loadWebsiteOrOffline();
    }

    /*
     * ================================================================
     * WEBVIEW SETUP
     * ================================================================
     */

    private void setupWebView() {

        /*
         * Android bridge used by the offline page Retry button.
         */

        webView.addJavascriptInterface(
                new Object() {

                    @android.webkit.JavascriptInterface
                    public void retry() {

                        runOnUiThread(() -> {

                            if (isNetworkAvailable()) {

                                loadWebsite();

                            } else {

                                Toast.makeText(
                                        MainActivity.this,
                                        "Still no internet connection",
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        });
                    }
                },
                "AndroidRetry"
        );

        WebSettings webSettings =
                webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);

        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(false);

        webSettings.setDefaultTextEncodingName("utf-8");

        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webSettings.setLoadsImagesAutomatically(true);

        webSettings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        );

        webSettings.setCacheMode(
                WebSettings.LOAD_DEFAULT
        );

        webSettings.setDatabaseEnabled(true);

        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        webSettings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance()
                .setAcceptCookie(true);

        webSettings.setGeolocationEnabled(true);

        /*
         * Force dark mode following website/system setting.
         */

        if (WebViewFeature.isFeatureSupported(
                WebViewFeature.FORCE_DARK
        )) {

            WebSettingsCompat.setForceDark(
                    webView.getSettings(),
                    WebSettingsCompat.FORCE_DARK_AUTO
            );
        }

        if (WebViewFeature.isFeatureSupported(
                WebViewFeature.FORCE_DARK_STRATEGY
        )) {

            WebSettingsCompat.setForceDarkStrategy(
                    webView.getSettings(),
                    WebSettingsCompat
                            .DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
            );
        }

        /*
         * ============================================================
         * WEBVIEW CLIENT
         * ============================================================
         */

        webView.setWebViewClient(
                new WebViewClient() {

                    @Override
                    public void onPageStarted(
                            WebView view,
                            String url,
                            Bitmap favicon
                    ) {

                        super.onPageStarted(
                                view,
                                url,
                                favicon
                        );

                        if (progressBar != null) {

                            progressBar.setVisibility(
                                    View.VISIBLE
                            );
                        }
                    }

                    /*
                     * Android WebView network error.
                     */

                    @Override
                    public void onReceivedError(
                            WebView view,
                            WebResourceRequest request,
                            WebResourceError error
                    ) {

                        super.onReceivedError(
                                view,
                                request,
                                error
                        );

                        if (request != null
                                && request.isForMainFrame()) {

                            showOfflinePage();
                        }
                    }

                    /*
                     * Older Android versions.
                     */

                    @Override
                    public void onReceivedError(
                            WebView view,
                            int errorCode,
                            String description,
                            String failingUrl
                    ) {

                        super.onReceivedError(
                                view,
                                errorCode,
                                description,
                                failingUrl
                        );

                        showOfflinePage();
                    }

                    @Override
                    public void onPageFinished(
                            WebView view,
                            String url
                    ) {

                        super.onPageFinished(
                                view,
                                url
                        );

                        /*
                         * Don't treat offline HTML as website.
                         */

                        if (isOfflinePageShown) {

                            if (progressBar != null) {

                                progressBar.setVisibility(
                                        View.GONE
                                );
                            }

                            return;
                        }

                        if (progressBar != null) {

                            progressBar.setVisibility(
                                    View.GONE
                            );
                        }

                        /*
                         * Save cookies.
                         */

                        String cookies =
                                CookieManager.getInstance()
                                        .getCookie(WEBSITE_URL);

                        if (cookies != null
                                && !cookies.isEmpty()) {

                            getSharedPreferences(
                                    "bp_prefs",
                                    MODE_PRIVATE
                            )
                                    .edit()
                                    .putString(
                                            "session_cookies",
                                            cookies
                                    )
                                    .putString(
                                            "website_url",
                                            WEBSITE_URL
                                    )
                                    .apply();
                        }

                        /*
                         * Re-inject FCM token.
                         */

                        if (fcmTokenForWebView != null
                                && !fcmTokenForWebView.isEmpty()) {

                            view.evaluateJavascript(
                                    "window.FCM_TOKEN = '"
                                            + escapeJavaScript(
                                            fcmTokenForWebView
                                    )
                                            + "'; window.dispatchEvent(new Event('fcm_token_ready'));",
                                    null
                            );
                        }
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            String url
                    ) {

                        return handleWebViewUrl(
                                view,
                                url
                        );
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {

                        if (Build.VERSION.SDK_INT
                                >= Build.VERSION_CODES.LOLLIPOP
                                && request != null
                                && request.getUrl() != null) {

                            return handleWebViewUrl(
                                    view,
                                    request.getUrl().toString()
                            );
                        }

                        return false;
                    }
                }
        );

        /*
         * ============================================================
         * WEB CHROME CLIENT
         * ============================================================
         */

        webView.setWebChromeClient(
                new WebChromeClient() {

                    @Override
                    public void onProgressChanged(
                            WebView view,
                            int newProgress
                    ) {

                        if (progressBar != null) {

                            progressBar.setProgress(
                                    newProgress
                            );
                        }
                    }

                    @Override
                    public void onPermissionRequest(
                            PermissionRequest request
                    ) {

                        request.grant(
                                request.getResources()
                        );
                    }

                    @Override
                    public void onGeolocationPermissionsShowPrompt(
                            String origin,
                            GeolocationPermissions.Callback callback
                    ) {

                        if (
                                Build.VERSION.SDK_INT
                                        < Build.VERSION_CODES.M
                                        ||
                                        ContextCompat.checkSelfPermission(
                                                MainActivity.this,
                                                Manifest.permission.ACCESS_FINE_LOCATION
                                        )
                                                == PackageManager.PERMISSION_GRANTED
                                        ||
                                        ContextCompat.checkSelfPermission(
                                                MainActivity.this,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                                == PackageManager.PERMISSION_GRANTED
                        ) {

                            callback.invoke(
                                    origin,
                                    true,
                                    false
                            );

                        } else {

                            pendingGeoOrigin = origin;
                            pendingGeoCallback = callback;

                            ActivityCompat.requestPermissions(
                                    MainActivity.this,
                                    new String[]{
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                    },
                                    2002
                            );
                        }
                    }

                    @Override
                    public boolean onShowFileChooser(
                            WebView webView,
                            ValueCallback<Uri[]> filePathCallback,
                            FileChooserParams fileChooserParams
                    ) {

                        if (fileUploadCallback != null) {

                            fileUploadCallback
                                    .onReceiveValue(null);
                        }

                        fileUploadCallback =
                                filePathCallback;

                        /*
                         * Camera
                         */

                        Intent cameraIntent =
                                new Intent(
                                        MediaStore.ACTION_IMAGE_CAPTURE
                                );

                        ContentValues values =
                                new ContentValues();

                        values.put(
                                MediaStore.Images.Media.TITLE,
                                "camera_photo"
                        );

                        cameraImageUri =
                                getContentResolver()
                                        .insert(
                                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                values
                                        );

                        cameraIntent.putExtra(
                                MediaStore.EXTRA_OUTPUT,
                                cameraImageUri
                        );

                        /*
                         * File chooser
                         */

                        Intent fileIntent =
                                new Intent(
                                        Intent.ACTION_GET_CONTENT
                                );

                        fileIntent.addCategory(
                                Intent.CATEGORY_OPENABLE
                        );

                        fileIntent.setType("*/*");

                        /*
                         * Combined chooser
                         */

                        Intent chooserIntent =
                                Intent.createChooser(
                                        fileIntent,
                                        "Select file"
                                );

                        chooserIntent.putExtra(
                                Intent.EXTRA_INITIAL_INTENTS,
                                new Intent[]{
                                        cameraIntent
                                }
                        );

                        fileUploadLauncher.launch(
                                chooserIntent
                        );

                        return true;
                    }
                }
        );

        /*
         * ============================================================
         * DOWNLOADS
         * ============================================================
         */

        webView.setDownloadListener(
                new DownloadListener() {

                    @Override
                    public void onDownloadStart(
                            String url,
                            String userAgent,
                            String contentDisposition,
                            String mimeType,
                            long contentLength
                    ) {

                        DownloadManager.Request request =
                                new DownloadManager.Request(
                                        Uri.parse(url)
                                );

                        request.setMimeType(mimeType);

                        String cookies =
                                CookieManager.getInstance()
                                        .getCookie(url);

                        if (cookies != null) {

                            request.addRequestHeader(
                                    "cookie",
                                    cookies
                            );
                        }

                        request.addRequestHeader(
                                "User-Agent",
                                userAgent
                        );

                        request.setDescription(
                                "Downloading file..."
                        );

                        String fileName =
                                URLUtil.guessFileName(
                                        url,
                                        contentDisposition,
                                        mimeType
                                );

                        request.setTitle(fileName);

                        request.allowScanningByMediaScanner();

                        request.setNotificationVisibility(
                                DownloadManager.Request
                                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        );

                        request.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                fileName
                        );

                        DownloadManager dm =
                                (DownloadManager)
                                        getSystemService(
                                                DOWNLOAD_SERVICE
                                        );

                        if (dm != null) {

                            dm.enqueue(request);
                        }

                        Toast.makeText(
                                getApplicationContext(),
                                "Downloading File",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    /*
     * ================================================================
     * WEBSITE / OFFLINE SYSTEM
     * ================================================================
     */

    private void loadWebsiteOrOffline() {

        if (isNetworkAvailable()) {

            loadWebsite();

        } else {

            showOfflinePage();
        }
    }

    private void loadWebsite() {

        isOfflinePageShown = false;

        if (progressBar != null) {

            progressBar.setVisibility(
                    View.VISIBLE
            );
        }

        webView.loadUrl(WEBSITE_URL);
    }

    private void showOfflinePage() {

        isOfflinePageShown = true;

        if (progressBar != null) {

            progressBar.setVisibility(
                    View.GONE
            );
        }

        String offlineHtml =
                "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +

                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no\">" +

                "<title>Device Offline</title>" +

                "<style>" +

                ":root {" +
                "--background-color: #ffffff;" +
                "--primary-color: #111111;" +
                "--secondary-color: #6e6e73;" +
                "--button-bg: #111111;" +
                "--button-text: #ffffff;" +
                "--icon-bg: #f2f2f7;" +
                "--border-color: rgba(0, 0, 0, 0.08);" +
                "}" +

                "[data-theme=\"dark\"] {" +
                "--background-color: #000000;" +
                "--primary-color: #f5f5f7;" +
                "--secondary-color: #98989d;" +
                "--button-bg: #ffffff;" +
                "--button-text: #000000;" +
                "--icon-bg: #1c1c1e;" +
                "--border-color: rgba(255, 255, 255, 0.1);" +
                "}" +

                "[data-theme=\"light\"] {" +
                "--background-color: #ffffff;" +
                "--primary-color: #111111;" +
                "--secondary-color: #6e6e73;" +
                "--button-bg: #111111;" +
                "--button-text: #ffffff;" +
                "--icon-bg: #f2f2f7;" +
                "--border-color: rgba(0, 0, 0, 0.08);" +
                "}" +

                "*" +
                "{" +
                "box-sizing: border-box;" +
                "-webkit-tap-highlight-color: transparent;" +
                "}" +

                "html,body {" +
                "width: 100%;" +
                "height: 100%;" +
                "margin: 0;" +
                "background: var(--background-color) !important;" +
                "color: var(--primary-color);" +
                "font-family: -apple-system, BlinkMacSystemFont, \"SF Pro Display\", \"SF Pro Text\", \"Helvetica Neue\", Arial, sans-serif;" +
                "transition: background-color 0.25s ease, color 0.25s ease;" +
                "}" +

                "body {" +
                "display: flex;" +
                "align-items: center;" +
                "justify-content: center;" +
                "overflow: hidden;" +
                "}" +

                "div.container {" +
                "width: min(88%, 420px);" +
                "position: relative;" +
                "top: 0;" +
                "text-align: center;" +
                "animation: fadeUp 0.45s ease-out;" +
                "}" +

                "#logo {" +
                "width: 92px;" +
                "height: 92px;" +
                "margin: 0 auto 28px;" +
                "display: flex;" +
                "align-items: center;" +
                "justify-content: center;" +
                "background: var(--icon-bg);" +
                "border: 1px solid var(--border-color);" +
                "border-radius: 24px;" +
                "box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06), inset 0 1px 0 rgba(255, 255, 255, 0.12);" +
                "}" +

                "#logo svg {" +
                "width: 58px;" +
                "height: auto;" +
                "}" +

                "#logo > svg > g > path {" +
                "fill: var(--primary-color);" +
                "}" +

                "#message {" +
                "display: block;" +
                "}" +

                "#message p {" +
                "margin: 0;" +
                "color: var(--secondary-color);" +
                "font-size: 16px;" +
                "line-height: 1.55;" +
                "font-weight: 400;" +
                "letter-spacing: -0.01em;" +
                "}" +

                "#message p::first-line {" +
                "color: var(--primary-color);" +
                "font-weight: 600;" +
                "}" +

                "#retryButton {" +
                "appearance: none;" +
                "-webkit-appearance: none;" +
                "border: 0;" +
                "outline: none;" +
                "margin-top: 28px;" +
                "min-width: 132px;" +
                "height: 46px;" +
                "padding: 0 24px;" +
                "border-radius: 14px;" +
                "background: var(--button-bg);" +
                "color: var(--button-text);" +
                "font-family: inherit;" +
                "font-size: 15px;" +
                "font-weight: 600;" +
                "letter-spacing: -0.01em;" +
                "cursor: pointer;" +
                "box-shadow: 0 5px 18px rgba(0, 0, 0, 0.12);" +
                "transition: transform 0.15s ease, opacity 0.15s ease, box-shadow 0.15s ease;" +
                "}" +

                "#retryButton:active {" +
                "transform: scale(0.96);" +
                "opacity: 0.82;" +
                "}" +

                "@media (prefers-reduced-motion: reduce) {" +
                "div.container { animation: none; }" +
                "#retryButton { transition: none; }" +
                "}" +

                "@keyframes fadeUp {" +
                "from { opacity: 0; transform: translateY(12px); }" +
                "to { opacity: 1; transform: translateY(0); }" +
                "}" +

                "</style>" +
                "</head>" +

                "<body>" +

                "<div class=\"container\">" +

                "<div id=\"logo\">" +

                "<svg width=\"120\" height=\"96\" viewBox=\"0 0 120 96\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">" +

                "<g clip-path=\"url(#clip0_5510_497)\">" +

                "<path d=\"M118.275 87.9562L7.27647 0.958312C6.45147 0.313687 5.47272 0 4.50522 0C3.17022 0 1.84459 0.592125 0.959779 1.72294C-0.575284 3.68062 -0.234596 6.51 1.72253 8.04187L112.554 94.8731C114.523 96.4112 117.348 96.0596 118.871 94.1085C120.581 92.325 120.225 89.4937 118.275 87.9562Z\" fill=\"black\"/>" +

                "<path opacity=\"0.4\" d=\"M36 53.8313C32.6869 53.8313 30 56.5163 30 59.6625V89.4937C30 92.8069 32.6869 95.4937 36 95.4937C39.3131 95.4937 42 92.8069 42 89.4937V59.6625C42 56.6813 39.3187 53.8313 36 53.8313ZM54 90C54 93.3131 56.6869 96 60 96C63.3131 96 66 93.3131 66 90V69.8625L54 60.4575V90ZM12 71.8313C8.68687 71.8313 6 74.5163 6 77.6625V89.4937C6 92.8069 8.68687 95.4937 12 95.4937C15.3131 95.4937 18 92.8069 18 89.4937V77.6625C18 74.6813 15.3131 71.8313 12 71.8313ZM60 36C57.9937 36 56.325 37.0313 55.2375 38.55L66 46.9875V42C66 38.6812 63.3187 36 60 36ZM107.831 0C104.518 0 101.831 2.68687 101.831 6V75.2063L113.831 84.6113V6C113.831 2.68687 111.319 0 107.831 0ZM78 90C78 93.3131 80.6869 96 84 96C87.3131 96 90 93.3131 90 90V88.6684L78 79.2634V90ZM84 18C80.6869 18 78 20.6869 78 24V56.3813L90 65.7862V24C90 20.6812 87.3187 18 84 18Z\" fill=\"black\"/>" +

                "</g>" +

                "<defs>" +

                "<clipPath id=\"clip0_5510_497\">" +
                "<rect width=\"120\" height=\"96\" fill=\"white\"/>" +
                "</clipPath>" +

                "</defs>" +

                "</svg>" +

                "</div>" +

                "<span id=\"message\">" +
                "<p>No internet connection<br>Check your connection and try again</p>" +
                "</span>" +

                "<button id=\"retryButton\" type=\"button\" onclick=\"AndroidRetry.retry()\">" +
                "Retry" +
                "</button>" +

                "</div>" +

                "<script>" +

                "var message = document.getElementById('message');" +
                "var retryButton = document.getElementById('retryButton');" +

                "if (['ko', 'ko-kr', 'ko-kp'].indexOf(navigator.language.toLowerCase()) > -1) {" +

                "message.innerHTML = '<p>인터넷 연결이 끊겼습니다.<br/>연결하고 다시 시도하시길 바랍니다.</p>';" +
                "retryButton.innerText = '괜찮아';" +

                "}" +

                "function updateDarkMode() {" +

                "if (window.matchMedia('(prefers-color-scheme: dark)').matches) {" +
                "document.documentElement.setAttribute('data-theme', 'dark');" +
                "} else {" +
                "document.documentElement.setAttribute('data-theme', 'light');" +
                "}" +

                "}" +

                "updateDarkMode();" +

                "if (window.matchMedia) {" +

                "window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', updateDarkMode);" +

                "}" +

                "</script>" +

                "</body>" +
                "</html>";

        webView.loadDataWithBaseURL(
                null,
                offlineHtml,
                "text/html",
                "UTF-8",
                null
        );
    }

    /*
     * ================================================================
     * NETWORK CHECK
     * ================================================================
     */

    private boolean isNetworkAvailable() {

        ConnectivityManager connectivityManager =
                (ConnectivityManager)
                        getSystemService(
                                Context.CONNECTIVITY_SERVICE
                        );

        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            android.net.Network network =
                    connectivityManager.getActiveNetwork();

            if (network == null) {
                return false;
            }

            android.net.NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(
                            network
                    );

            if (capabilities == null) {
                return false;
            }

            return capabilities.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            );

        } else {

            NetworkInfo activeNetworkInfo =
                    connectivityManager
                            .getActiveNetworkInfo();

            return activeNetworkInfo != null
                    && activeNetworkInfo.isConnected();
        }
    }

    /*
     * ================================================================
     * JAVASCRIPT ESCAPE
     * ================================================================
     */

    private String escapeJavaScript(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("</", "<\\/");
    }

    /*
     * ================================================================
     * URL HANDLING
     * ================================================================
     */

    private boolean handleWebViewUrl(
            WebView view,
            String url
    ) {

        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String lower = url.toLowerCase();

        if (
                lower.startsWith("tel:")
                        || lower.startsWith("mailto:")
                        || lower.startsWith("sms:")
                        || lower.startsWith("smsto:")
                        || lower.startsWith("whatsapp:")
                        || lower.startsWith("market:")
                        || lower.startsWith("intent:")
        ) {

            try {

                Intent intent =
                        Intent.parseUri(
                                url,
                                Intent.URI_INTENT_SCHEME
                        );

                startActivity(intent);

                return true;

            } catch (Exception ignored) {

                try {

                    startActivity(
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(url)
                            )
                    );

                    return true;

                } catch (Exception ignoredAgain) {

                    return true;
                }
            }
        }

        if (
                !lower.startsWith("http://")
                        && !lower.startsWith("https://")
        ) {

            return true;
        }

        try {

            java.net.URL baseUrl =
                    new java.net.URL(WEBSITE_URL);

            java.net.URL targetUrl =
                    new java.net.URL(url);

            if (
                    targetUrl.getHost() != null
                            && !targetUrl.getHost()
                            .equalsIgnoreCase(
                                    baseUrl.getHost()
                            )
            ) {

                Intent browserIntent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                        );

                startActivity(browserIntent);

                return true;
            }

        } catch (Exception e) {
            // Ignore and load in WebView
        }

        view.loadUrl(url);

        return true;
    }

    /*
     * ================================================================
     * DEEP LINKS
     * ================================================================
     */

    private void handleIntent(Intent intent) {

        if (
                intent != null
                        && intent.getData() != null
        ) {

            String deepUrl =
                    intent.getData().toString();

            if (deepUrl.startsWith("http")) {

                if (isNetworkAvailable()) {

                    webView.loadUrl(deepUrl);

                } else {

                    showOfflinePage();
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {

        super.onNewIntent(intent);

        setIntent(intent);

        handleIntent(intent);
    }

    /*
     * ================================================================
     * FILE UPLOAD RESULT
     * ================================================================
     */

    private final ActivityResultLauncher<Intent>
            fileUploadLauncher =
            registerForActivityResult(
                    new ActivityResultContracts
                            .StartActivityForResult(),
                    result -> {

                        if (fileUploadCallback == null) {
                            return;
                        }

                        if (
                                result.getResultCode()
                                        == RESULT_OK
                                        && result.getData() != null
                                        && result.getData().getData() != null
                        ) {

                            fileUploadCallback.onReceiveValue(
                                    new Uri[]{
                                            result.getData()
                                                    .getData()
                                    }
                            );

                        } else if (
                                result.getResultCode()
                                        == RESULT_OK
                                        && cameraImageUri != null
                        ) {

                            fileUploadCallback.onReceiveValue(
                                    new Uri[]{
                                            cameraImageUri
                                    }
                            );

                        } else {

                            fileUploadCallback
                                    .onReceiveValue(null);
                        }

                        fileUploadCallback = null;
                    }
            );

    /*
     * ================================================================
     * PAUSE / RESUME
     * ================================================================
     */

    @Override
    protected void onPause() {

        super.onPause();

        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (webView != null) {

            webView.onResume();

            /*
             * If the app was opened offline and connection has
             * returned while it was in background, reload website.
             */

            if (isOfflinePageShown
                    && isNetworkAvailable()) {

                loadWebsite();
            }
        }
    }

    /*
     * ================================================================
     * PERMISSIONS
     * ================================================================
     */

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == 2002) {

            boolean granted = false;

            if (grantResults != null) {

                for (int result : grantResults) {

                    if (
                            result
                                    == PackageManager.PERMISSION_GRANTED
                    ) {

                        granted = true;
                        break;
                    }
                }
            }

            if (pendingGeoCallback != null) {

                pendingGeoCallback.invoke(
                        pendingGeoOrigin,
                        granted,
                        false
                );

                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
        }
    }

    /*
     * ================================================================
     * DESTROY
     * ================================================================
     */

    @Override
    protected void onDestroy() {

        if (webView != null) {

            webView.stopLoading();

            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);

            webView.destroy();

            webView = null;
        }

        super.onDestroy();
    }
}

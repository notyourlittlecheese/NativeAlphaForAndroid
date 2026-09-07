package com.cylonid.nativealpha;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ShareCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.cylonid.nativealpha.databinding.DialogHttpAuthBinding;
import com.cylonid.nativealpha.activities.LocalizedAppCompatActivity;
import com.cylonid.nativealpha.helper.AdblockLifecycleHelper;
import com.cylonid.nativealpha.helper.AdblockProviderApiHelper;
import com.cylonid.nativealpha.helper.BiometricPromptHelper;
import com.cylonid.nativealpha.helper.HttpAuthCredentialStore;
import com.cylonid.nativealpha.helper.HttpAuthCredentials;
import com.cylonid.nativealpha.helper.IconPopupMenuHelper;
import com.cylonid.nativealpha.model.AdblockConfig;
import com.cylonid.nativealpha.model.DataManager;
import com.cylonid.nativealpha.model.SandboxManager;
import com.cylonid.nativealpha.model.WebApp;
import com.cylonid.nativealpha.util.Const;
import com.cylonid.nativealpha.util.DateUtils;
import com.cylonid.nativealpha.util.EntryPointUtils;
import com.cylonid.nativealpha.util.LocaleUtils;
import com.cylonid.nativealpha.util.NotificationUtils;
import com.cylonid.nativealpha.util.Utility;
import com.cylonid.nativealpha.util.WebViewLauncher;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.json.JSONObject;

import io.github.edsuns.adfilter.AdFilter;
import io.github.edsuns.adfilter.Filter;
import pub.devrel.easypermissions.EasyPermissions;

import static com.cylonid.nativealpha.util.Const.CODE_OPEN_FILE;

public class WebViewActivity extends LocalizedAppCompatActivity implements EasyPermissions.PermissionCallbacks {

    //Constants for touchlistener
    private static final int NONE = 0;
    private static final int SWIPE = 1;
    private static final int TRESHOLD = 100;
    private static final long GENERATED_DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L;
    int webappID = -1;
    private WebView wv;
    private ProgressBar progressBar;
    private boolean currently_reloading = true;
    private GeolocationPermissions.Callback mGeoPermissionRequestCallback = null;
    private String mGeoPermissionRequestOrigin = null;
    private WebDownload dl_request_internal = null;
    private String pendingBlobDownloadToken = null;
    private final Map<String, PendingGeneratedDownload> pendingGeneratedDownloads = new HashMap<>();
    private Map<String, String> CUSTOM_HEADERS;
    protected ValueCallback<Uri[]> filePathCallback;

    private boolean quitOnNextBackpress = false;
    private Handler reload_handler = null;
    private WebApp webapp = null;
    private String urlOnFirstPageload = "";
    private boolean fallbackToDefaultLongClickBehaviour = false;
    private PopupMenu mPopupMenu = null;

    private AdFilter adFilter;

    private AdblockProviderApiHelper adblockProviderApiHelper;
    private AdblockLifecycleHelper adblockLifecycleHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adblockLifecycleHelper = new AdblockLifecycleHelper(this);
        adblockLifecycleHelper.trySyncOperation(() -> adFilter = AdFilter.Companion.get(getApplicationContext()));

        adblockProviderApiHelper = new AdblockProviderApiHelper(adFilter);
        webappID = getIntent().getIntExtra(Const.INTENT_WEBAPPID, -1);
        EntryPointUtils.entryPointReached(this);
        webapp = DataManager.getInstance().getWebApp(webappID);
        if (webapp == null) {
            // Toast is shown in getWebApp method
            finish();
        } else {
            if(webapp.isBiometricProtection()) {
                new BiometricPromptHelper(WebViewActivity.this).showPrompt(() -> setupWebView(), () -> finish(), getString(R.string.bioprompt_restricted_webapp));
            }
            setupWebView();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupWebView() {

        String processName = Application.getProcessName();
        String packageName = this.getPackageName();

        boolean hasSandboxing = SandboxManager.getInstance() != null;
        // Sandboxed Web App is openend in main process using an old shortcut
        if (packageName.equals(processName) && webapp.isUseContainer() && hasSandboxing) {
            WebViewLauncher.startWebViewInNewProcess(webapp, this);
        }

        if (!packageName.equals(processName) && hasSandboxing) {
            if (SandboxManager.getInstance().isSandboxUsedByAnotherApp(webapp)) {
                SandboxManager.getInstance().unregisterWebAppFromSandbox(webapp.getContainerId());
                WebViewLauncher.startWebViewInNewProcess(webapp, this);
            }
            try {
                SandboxManager.getInstance().registerWebAppToSandbox(webapp);
                WebView.setDataDirectorySuffix(webapp.getContainerId() + webapp.getAlphanumericBaseUrl() + "_" + webapp.getID());
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }

        setContentView(R.layout.full_webview);
        configureImeInsets();

        if(webapp.isKeepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String url = webapp.getBaseUrl();

        wv = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        List<AdblockConfig> adblockConfigs = DataManager.getInstance().getSettings().getGlobalWebApp().getAdBlockSettings();
        if (webapp.isUseAdblock() && !adblockConfigs.isEmpty()) {
            wv.setVisibility(View.GONE);
            wv = findViewById(R.id.adblockwebview);
            wv.setVisibility(View.VISIBLE);

            adFilter.setupWebView(wv);
            adblockLifecycleHelper.beforeAdblockOperation(() -> adblockProviderApiHelper.synchronizeAdblockProviderWithSettings(adblockConfigs));

            adFilter.getViewModel().getOnDirty().observe(this, none -> wv.clearCache(false)
            );

            adFilter.getViewModel().getEnabledFilterCount().observe(this, count -> {
                if (count == adblockConfigs.size()) {
                    adblockLifecycleHelper.afterAdblockOperation();
                }
            });
        }

        String fieldName = Stream.of(WebViewActivity.class.getDeclaredFields()).filter(f -> f.getType() == WebView.class).findFirst().orElseThrow(null).getName();
        String uaString = wv.getSettings().getUserAgentString().replace("; " + fieldName, "");
        wv.getSettings().setUserAgentString(uaString);
        if (webapp.isUseCustomUserAgent()) {
            if(webapp.getUserAgent() != null && !webapp.getUserAgent().equals("")) {
                wv.getSettings().setUserAgentString(webapp.getUserAgent().replace("\0", "").replace("\n", "").replace("\r", ""));
            }
        }

        if (webapp.isShowFullscreen()) {
            this.hideSystemBars();
        } else if(DataManager.getInstance().getSettings().getAlwaysShowSoftwareButtons()) {
            this.showSystemBars();
        }
        wv.setWebViewClient(new CustomBrowser());
        wv.getSettings().setSafeBrowsingEnabled(false);
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setDatabaseEnabled(true);
        wv.getSettings().setAllowFileAccess(true);
        wv.getSettings().setBlockNetworkLoads(false);
//        wv.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        this.setDarkModeIfNeeded();

        wv.getSettings().setJavaScriptEnabled(webapp.isAllowJs());

        CookieManager.getInstance().setAcceptCookie(webapp.isAllowCookies());
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, webapp.isAllowThirdPartyCookies());

        if (webapp.isBlockImages())
            wv.getSettings().setBlockNetworkImage(true);

        if (webapp.isRequestDesktop()) {
            wv.getSettings().setUserAgentString(Const.DESKTOP_USER_AGENT);
            wv.getSettings().setUseWideViewPort(true);
            wv.getSettings().setLoadWithOverviewMode(true);

            wv.getSettings().setSupportZoom(true);
            wv.getSettings().setBuiltInZoomControls(true);
            wv.getSettings().setDisplayZoomControls(false);

            wv.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
            wv.setScrollbarFadingEnabled(false);

        }
        if(webapp.isEnableZooming()) {
            wv.getSettings().setSupportZoom(true);
            wv.getSettings().setBuiltInZoomControls(true);
        }

        CUSTOM_HEADERS = initCustomHeaders(webapp.isSendSavedataRequest());
        loadURL(wv, url);
        wv.setWebChromeClient(new CustomWebChromeClient());
        wv.addJavascriptInterface(new DownloadJavascriptInterface(), "NativeAlphaDownloader");
        wv.setOnLongClickListener(view -> {
            if(webapp.getAlwaysUseFallbackContextMenu()) return false;
            if(fallbackToDefaultLongClickBehaviour) {
                fallbackToDefaultLongClickBehaviour = false;
                return false;
            }
            showWebViewPopupMenu();
            return true;
        });


        wv.setDownloadListener((dl_url, userAgent, contentDisposition, mimeType, contentLength) -> {

            if(dl_url == null || dl_url.equals("")) return;
            if(dl_url.startsWith("data:")) {
                downloadDataUrl(dl_url, contentDisposition, mimeType);
                return;
            }
            if(dl_url.startsWith("blob:")) {
                downloadBlobUrl(dl_url, contentDisposition, mimeType);
                return;
            }
            WebDownload download = new WebDownload(dl_url, userAgent, contentDisposition, mimeType);
            if(!download.isSupported()) {
                openDownloadExternally(dl_url);
                return;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                String[] perms = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
                if (!EasyPermissions.hasPermissions(WebViewActivity.this, perms)) {
                    dl_request_internal = download;
                    EasyPermissions.requestPermissions(WebViewActivity.this, getString(R.string.permission_storage_rationale), Const.PERMISSION_RC_STORAGE, perms);
                    return;
                }
            }

            startInternalDownload(download);
        });
        wv.setOnTouchListener(new View.OnTouchListener() {
            private int mode = NONE;
            private float startX;
            private float stopX;
            private float startY;
            private float stopY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                WebApp webapp = DataManager.getInstance().getWebApp(webappID);
                if (webapp.isRequestDesktop())
                    return false;

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_POINTER_DOWN:
                        // This happens when you touch the screen with two fingers
                        mode = SWIPE;
                        // You can also use event.getY(1) or the average of the two
                        startX = event.getX(0);
                        startY = event.getY(0);
                        return true;

                    case MotionEvent.ACTION_POINTER_UP:
                        // This happens when you release the second finger
                        mode = NONE;
                        if (Math.abs(startX - stopX) > TRESHOLD) {
                            if (startX > stopX) {
                                if (event.getPointerCount() == 3 && DataManager.getInstance().getSettings().isThreeFingerMultitouch()) {
                                    WebViewLauncher.startWebView(DataManager.getInstance().getPredecessor(webappID), WebViewActivity.this);
                                    finish();
                                } else if (DataManager.getInstance().getSettings().isTwoFingerMultitouch()) {
                                    if (wv.canGoForward())
                                        wv.goForward();
                                }
                            } else {
                                if (event.getPointerCount() == 3 && DataManager.getInstance().getSettings().isThreeFingerMultitouch()) {
                                    WebViewLauncher.startWebView(DataManager.getInstance().getSuccessor(webappID), WebViewActivity.this);
                                    finish();
                                } else if (DataManager.getInstance().getSettings().isTwoFingerMultitouch())
                                    onBackPressed();

                            }
                            return true;
                        }
                        if (DataManager.getInstance().getSettings().isMultitouchReload() && Math.abs(startY - stopY) > TRESHOLD) {
                            if (stopY > startY) {
                                currently_reloading = true;
                                wv.reload();
                            }
                            return true;
                        }
                    case MotionEvent.ACTION_MOVE:
                        if (mode == SWIPE) {
                            stopX = event.getX(0);
                            stopY = event.getY(0);
                        }
                        return false;
                }
                return false;
            }
        });
    }

    @SuppressLint("RequiresFeature")
    private void setDarkModeIfNeeded() {
        if (!BuildConfig.FLAVOR.contains("extended")) {
            return;
        }

        boolean needsForcedDarkMode = webapp.isUseTimespanDarkMode() &&
                DateUtils.isInInterval(DateUtils.convertStringToCalendar(webapp.getTimespanDarkModeBegin()), Calendar.getInstance(), DateUtils.convertStringToCalendar(webapp.getTimespanDarkModeEnd()))
                || (!webapp.isUseTimespanDarkMode() && webapp.isForceDarkMode());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean isForceDarkSupported = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK);
            boolean isForceDarkStrategySupported = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY);
            boolean isAlgorithmicDarkeningSupported = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING);

            if (needsForcedDarkMode) {
                wv.setBackgroundColor(Color.BLACK);
                wv.setForceDarkAllowed(true);
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(wv.getSettings(), WebSettingsCompat.FORCE_DARK_ON);
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(wv.getSettings(), WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.getSettings(), true);
                }
            } else {
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                wv.setBackgroundColor(Color.WHITE);

                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(wv.getSettings(), WebSettingsCompat.FORCE_DARK_OFF);
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(wv.getSettings(), WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.getSettings(), false);
                }
            }
        }

    }

    @SuppressLint("NonConstantResourceId")
    private void showWebViewPopupMenu() {
        View center = findViewById(R.id.anchorCenterScreen);
        mPopupMenu = IconPopupMenuHelper.getMenu(center, R.menu.wv_context_menu, WebViewActivity.this);

        String currentUrl = wv.getUrl();
        String title = "";
        if (currentUrl != null) {
            title = currentUrl.length() < 32 ? currentUrl : currentUrl.substring(0, 32) + "...";
        }
        SpannableString spanStringWebAppTitle = new SpannableString(title);

        // The item is disabled because it has no click action, but we want to override the disabled style (text color)
        int colorOnSurface = MaterialColors.getColor(center, R.attr.colorOnSurface, Color.BLACK);
        ForegroundColorSpan foregroundColorSpan = new ForegroundColorSpan(colorOnSurface);
        spanStringWebAppTitle.setSpan(foregroundColorSpan, 0,     spanStringWebAppTitle.length(), 0);

        spanStringWebAppTitle.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, spanStringWebAppTitle.length(), 0);
        mPopupMenu.getMenu().getItem(0).setTitle(spanStringWebAppTitle);

        for (int i = 0; i < mPopupMenu.getMenu().size(); i++) {
            MenuItem item = mPopupMenu.getMenu().getItem(i);
            SpannableString spanString = new SpannableString(item.getTitle());
            spanString.setSpan(foregroundColorSpan, 0, spanString.length(),0);
            item.setTitle(spanString);
        }
        if(wv.canGoForward()) mPopupMenu.getMenu().getItem(2).setVisible(true);
        if(BuildConfig.DEBUG) {
            mPopupMenu.getMenu().getItem(6).setVisible(true);
        }
        mPopupMenu.setOnMenuItemClickListener(menuItem -> {
            switch(menuItem.getItemId()) {
                case R.id.cmItemForward:
                    wv.goForward();
                    return true;
                case R.id.cmItemBack:
                    onBackPressed();
                    return true;
                case R.id.cmItemReload:
                    wv.reload();
                    return true;
                case R.id.cmItemCopyUrl:
                    ClipboardManager clipboard =  getSystemService(ClipboardManager.class);
                    ClipData clip = ClipData.newPlainText("URL", wv.getUrl());
                    clipboard.setPrimaryClip(clip);
                    return true;
                case R.id.cmItemShareUrl:
                    new ShareCompat.IntentBuilder(WebViewActivity.this)
                            .setType("text/plain")
                            .setChooserTitle("Share URL")
                            .setText(wv.getUrl())
                            .startChooser();
                    return true;
                case R.id.cmItemCloseWebApp:
                    finishAndRemoveTask();
                    return true;
                case R.id.cmFallbackContextmenuTemp:
                    fallbackToDefaultLongClickBehaviour = true;
                    return true;
                case R.id.cmFallbackContextmenuPermanent:
                    webapp.setAlwaysUseFallbackContextMenu(true);
                    DataManager.getInstance().replaceWebApp(webapp);
                    fallbackToDefaultLongClickBehaviour = true;
                    NotificationUtils.showToast(this, getString(R.string.use_standard_context_menu_permanently));
                    return true;
                case R.id.cmMainMenu:
                    Intent intent = new Intent(this, MainActivity.class);
                    startActivity(intent);
                    return true;
                case R.id.cmShowAdblockProviders:
                    StringBuilder message = new StringBuilder();
                    for(Map.Entry<String, Filter> entry :  Objects.requireNonNull(AdFilter.Companion.get().getViewModel().getFilters().getValue()).entrySet()) {
                        Filter filter = entry.getValue();
                        message.append(filter.getUrl()).append(" has downloaded: ").append(filter.hasDownloaded()).append("\n\n");
                }
                    NotificationUtils.showToast(this, message.toString());
                    return true;

            }
            return false;
        });

        mPopupMenu.show();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.setDarkModeIfNeeded();
    }

    @Override
    public void onBackPressed() {
        WebApp webapp = DataManager.getInstance().getWebApp(webappID);

        if(wv.canGoBack()) {
            wv.goBack();
            return;
        }

        if(quitOnNextBackpress) {
            quitOnNextBackpress = false;
            moveTaskToBack(true);
            return;
        }

        loadURL(wv, webapp.getBaseUrl());
        quitOnNextBackpress = true;

    }

    @Override
    protected void onResume() {
        super.onResume();
        int new_id = getIntent().getIntExtra(Const.INTENT_WEBAPPID, -1);

        if (new_id != webappID) {
            WebApp new_webapp = DataManager.getInstance().getWebApp(new_id);
            WebViewLauncher.startWebViewInNewProcess(new_webapp, this);
        }

        wv.onResume();
        wv.resumeTimers();
        this.setDarkModeIfNeeded();

        
        if(webapp.isBiometricProtection()) {
            View fullActivityView = findViewById(R.id.webviewActivity);
            fullActivityView.setVisibility(View.GONE);
            new BiometricPromptHelper(WebViewActivity.this).showPrompt(() -> fullActivityView.setVisibility(View.VISIBLE), () -> finish(), getString(R.string.bioprompt_restricted_webapp));
        }
        if (webapp.isAutoreload()) {
            reload_handler = new Handler();
            reload();
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        wv.evaluateJavascript("document.querySelectorAll('audio').forEach(x => x.pause());document.querySelectorAll('video').forEach(x => x.pause());", null);
        wv.onPause();
        wv.pauseTimers();
        if(mPopupMenu != null) mPopupMenu.dismiss();

        if (webapp.isClearCache() || DataManager.getInstance().getSettings().isClearCache())
            wv.clearCache(true);

        if (reload_handler != null) {
            reload_handler.removeCallbacksAndMessages(null);
            Log.d("CLEANUP", "Stopped reload handler");
        }
    }

    private void reload() {
        reload_handler.postDelayed(() -> {
            currently_reloading = true;
            wv.reload();
            reload();
        }, webapp.getTimeAutoreload() * 1000L);
    }

    public WebView getWebView() {
        return wv;
    }

    private Map<String, String> initCustomHeaders(boolean save_data) {
        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("DNT", "1");
        extraHeaders.put("X-REQUESTED-WITH", "");
        if (save_data)
            extraHeaders.put("Save-Data", "on");
        return Collections.unmodifiableMap(extraHeaders);
    }

    private void loadURL(final WebView view, final String url) {
        final WebApp webApp = DataManager.getInstance().getWebApp(webappID);
        if (url.contains("http://") && !webApp.isAllowHttp()) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(WebViewActivity.this);

            builder.setTitle(getString(R.string.no_https_dialog_title));
            builder.setMessage(getString(R.string.no_https_dialog_msg));
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(getString(R.string.no_https_dialog_accept), (dialog, id) -> {
                webApp.setAllowHttp(true);
                webApp.setOverrideGlobalSettings(true);
                DataManager.getInstance().saveWebAppData();
                view.loadUrl(url, CUSTOM_HEADERS);
            });
            builder.setNegativeButton(getString(android.R.string.cancel), (dialog, id) -> finish());
            final AlertDialog dialog = builder.create();
            dialog.show();
        } else
            view.loadUrl(url, CUSTOM_HEADERS);

    }
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if(controller != null) {
                controller.show(WindowInsets.Type.statusBars());
                controller.hide(WindowInsets.Type.navigationBars());

                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void showSystemBars() {

        if(webapp.isShowFullscreen()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }
    @FunctionalInterface
    interface PermissionGrantedCallback {
        void execute();
    }

    private void enablePermissionBoolOnWebApp(PermissionGrantedCallback successCallback) {
        webapp.setOverrideGlobalSettings(true);
        successCallback.execute();
        DataManager.getInstance().replaceWebApp(webapp);
        wv.reload();
    }

    private void configureImeInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        View webViewContainer = findViewById(R.id.webviewActivity);
        int initialLeft = webViewContainer.getPaddingLeft();
        int initialTop = webViewContainer.getPaddingTop();
        int initialRight = webViewContainer.getPaddingRight();
        int initialBottom = webViewContainer.getPaddingBottom();

        webViewContainer.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets imeInsets = windowInsets.getInsets(WindowInsets.Type.ime());
            Insets navigationInsets = windowInsets.getInsets(WindowInsets.Type.navigationBars());
            int keyboardInset = Math.max(0, imeInsets.bottom - navigationInsets.bottom);
            view.setPadding(initialLeft, initialTop, initialRight, initialBottom + keyboardInset);
            return windowInsets;
        });
        webViewContainer.requestApplyInsets();
    }

    private void startInternalDownload(WebDownload download) {
        NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
        new Thread(() -> {
            try {
                downloadToDownloads(download);
                runOnUiThread(() -> NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download_complete), Snackbar.LENGTH_SHORT));
            } catch (Exception e) {
                Log.e("NativeAlpha", "Download failed", e);
                runOnUiThread(() -> NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG));
            }
        }).start();
    }

    private void downloadBlobUrl(String blobUrl, String contentDisposition, String mimeType) {
        downloadBlobUrl(blobUrl, null, contentDisposition, mimeType);
    }

    private void downloadBlobUrl(String blobUrl, String requestedFileName, String contentDisposition, String mimeType) {
        String fileName = resolveDownloadFileName(requestedFileName, wv.getUrl(), contentDisposition, mimeType);
        String token = UUID.randomUUID().toString();
        pendingBlobDownloadToken = token;
        String script = "(function(){"
                + "function saveDataUrl(dataUrl,fileName,mimeType){"
                + "var chunkSize=262144;"
                + "if(dataUrl.length<=chunkSize){NativeAlphaDownloader.saveBlob(" + JSONObject.quote(token) + ",dataUrl,fileName,mimeType);return;}"
                + "var session=NativeAlphaDownloader.beginBlobDownload(" + JSONObject.quote(token) + ",fileName,mimeType);"
                + "if(!session){NativeAlphaDownloader.downloadFailed(" + JSONObject.quote(token) + ");return;}"
                + "for(var i=0;i<dataUrl.length;i+=chunkSize){NativeAlphaDownloader.appendGeneratedDownloadChunk(session,dataUrl.substring(i,i+chunkSize));}"
                + "NativeAlphaDownloader.finishGeneratedDownload(session);"
                + "}"
                + "fetch(" + JSONObject.quote(blobUrl) + ").then(function(response){return response.blob();}).then(function(blob){"
                + "var reader=new FileReader();"
                + "reader.onloadend=function(){saveDataUrl(reader.result," + JSONObject.quote(fileName) + ",blob.type||" + JSONObject.quote(mimeType == null ? "" : mimeType) + ");};"
                + "reader.onerror=function(){NativeAlphaDownloader.downloadFailed(" + JSONObject.quote(token) + ");};"
                + "reader.readAsDataURL(blob);"
                + "}).catch(function(){NativeAlphaDownloader.downloadFailed(" + JSONObject.quote(token) + ");});"
                + "})();";
        NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
        wv.evaluateJavascript(script, null);
    }

    private void downloadDataUrl(String dataUrl, String contentDisposition, String mimeType) {
        downloadDataUrl(dataUrl, null, contentDisposition, mimeType);
    }

    private void downloadDataUrl(String dataUrl, String requestedFileName, String contentDisposition, String mimeType) {
        String fileName = resolveDownloadFileName(requestedFileName, wv.getUrl(), contentDisposition, mimeType);
        saveDataUrlInBackground(dataUrl, fileName, mimeType);
    }

    private void downloadFromJavascript(String url, String requestedFileName, String mimeType) {
        if(url == null || url.equals("")) return;
        if(url.startsWith("data:")) {
            downloadDataUrl(url, requestedFileName, null, mimeType);
            return;
        }
        if(url.startsWith("blob:")) {
            downloadBlobUrl(url, requestedFileName, null, mimeType);
            return;
        }

        WebDownload download = new WebDownload(url, wv.getSettings().getUserAgentString(), null, mimeType, requestedFileName);
        if(download.isSupported()) {
            startInternalDownload(download);
        } else {
            openDownloadExternally(url);
        }
    }

    private void injectDownloadLinkHandler() {
        String script = """
                (function() {
                  if (window.__nativeAlphaDownloadHookInstalled) return;
                  window.__nativeAlphaDownloadHookInstalled = true;
                  var objectUrlBlobs = {};

                  if (window.URL && URL.createObjectURL && URL.revokeObjectURL) {
                    var originalCreateObjectURL = URL.createObjectURL.bind(URL);
                    var originalRevokeObjectURL = URL.revokeObjectURL.bind(URL);
                    function cleanupObjectUrlBlobs() {
                      var keys = Object.keys(objectUrlBlobs);
                      var now = Date.now();
                      keys.forEach(function(key) {
                        if (objectUrlBlobs[key].expiresAt <= now) delete objectUrlBlobs[key];
                      });
                      keys = Object.keys(objectUrlBlobs);
                      while (keys.length > 32) {
                        delete objectUrlBlobs[keys.shift()];
                      }
                    }
                    URL.createObjectURL = function(object) {
                      var url = originalCreateObjectURL(object);
                      if (object instanceof Blob) {
                        cleanupObjectUrlBlobs();
                        objectUrlBlobs[url] = {
                          blob: object,
                          expiresAt: Date.now() + 300000
                        };
                        setTimeout(function() {
                          delete objectUrlBlobs[url];
                        }, 300000);
                      }
                      return url;
                    };
                    URL.revokeObjectURL = function(url) {
                      setTimeout(function() {
                        delete objectUrlBlobs[url];
                        originalRevokeObjectURL(url);
                      }, 30000);
                    };
                  }

                  function getDownloadAnchor(target) {
                    if (!target) return null;
                    if (target.tagName === 'A' && target.hasAttribute('download')) return target;
                    if (target.closest) return target.closest('a[download]');
                    return null;
                  }

                  function saveBlob(blob, filename, mimeType) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                      saveDataUrl(reader.result, filename, blob.type || mimeType || '');
                    };
                    reader.onerror = function() {
                      NativeAlphaDownloader.downloadLinkFailed();
                    };
                    reader.readAsDataURL(blob);
                  }

                  function saveDataUrl(dataUrl, filename, mimeType) {
                    var chunkSize = 262144;
                    if (dataUrl.length <= chunkSize) {
                      NativeAlphaDownloader.saveGeneratedDownload(dataUrl, filename, mimeType);
                      return;
                    }
                    var session = NativeAlphaDownloader.beginGeneratedDownload(filename, mimeType);
                    if (!session) {
                      NativeAlphaDownloader.downloadLinkFailed();
                      return;
                    }
                    for (var i = 0; i < dataUrl.length; i += chunkSize) {
                      NativeAlphaDownloader.appendGeneratedDownloadChunk(session, dataUrl.substring(i, i + chunkSize));
                    }
                    NativeAlphaDownloader.finishGeneratedDownload(session);
                  }

                  function handleDownloadAnchor(anchor) {
                    if (!anchor || !anchor.hasAttribute('download')) return false;
                    var href = anchor.href || anchor.getAttribute('href') || '';
                    if (!href) return false;
                    var filename = anchor.getAttribute('download') || '';
                    var mimeType = anchor.type || '';
                    if (/^blob:/i.test(href)) {
                      if (objectUrlBlobs[href]) {
                        saveBlob(objectUrlBlobs[href].blob, filename, mimeType);
                        return true;
                      }
                      fetch(href).then(function(response) {
                        return response.blob();
                      }).then(function(blob) {
                        saveBlob(blob, filename, mimeType);
                      }).catch(function() {
                        NativeAlphaDownloader.downloadLinkFailed();
                      });
                      return true;
                    }
                    if (/^(data:|https?:)/i.test(href)) {
                      NativeAlphaDownloader.downloadLink(href, filename, mimeType);
                      return true;
                    }
                    return false;
                  }

                  document.addEventListener('click', function(event) {
                    var anchor = getDownloadAnchor(event.target);
                    if (handleDownloadAnchor(anchor)) {
                      event.preventDefault();
                      event.stopImmediatePropagation();
                    }
                  }, true);

                  var originalClick = HTMLAnchorElement.prototype.click;
                  HTMLAnchorElement.prototype.click = function() {
                    if (handleDownloadAnchor(this)) return;
                    return originalClick.apply(this, arguments);
                  };
                })();
                """;
        wv.evaluateJavascript(script, null);
    }

    private void saveDataUrlInBackground(String dataUrl, String fileName, String mimeType) {
        NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
        new Thread(() -> {
            try {
                saveDataUrlToDownloads(dataUrl, fileName, mimeType);
                runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_complete), Snackbar.LENGTH_SHORT));
            } catch (Exception e) {
                Log.e("NativeAlpha", "Data URL download failed", e);
                runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG));
            }
        }).start();
    }

    private void saveDataUrlToDownloads(String dataUrl, String fileName, String mimeType) throws IOException {
        int commaIndex = dataUrl.indexOf(',');
        if(!dataUrl.startsWith("data:") || commaIndex < 0) {
            throw new IOException("Unexpected data URL");
        }

        String metadata = dataUrl.substring(5, commaIndex);
        if(mimeType == null || mimeType.equals("")) {
            int semicolonIndex = metadata.indexOf(';');
            mimeType = semicolonIndex >= 0 ? metadata.substring(0, semicolonIndex) : metadata;
        }
        if(mimeType == null || mimeType.equals("")) {
            mimeType = "application/octet-stream";
        }

        byte[] data;
        String payload = dataUrl.substring(commaIndex + 1);
        if(metadata.toLowerCase().contains(";base64")) {
            data = Base64.getDecoder().decode(payload);
        } else {
            data = Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveBytesToMediaStore(data, fileName, mimeType);
        } else {
            saveBytesToLegacyDownloads(data, fileName);
        }
    }

    private void saveBytesToMediaStore(byte[] data, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if(uri == null) throw new IOException("Could not create download entry");

        try (OutputStream output = resolver.openOutputStream(uri)) {
            if(output == null) throw new IOException("Could not open download output stream");
            output.write(data);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private void saveBytesToLegacyDownloads(byte[] data, String fileName) throws IOException {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if(!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Could not create downloads directory");
        }
        File destination = new File(downloadsDir, fileName);
        try (OutputStream output = new FileOutputStream(destination)) {
            output.write(data);
        }
    }

    private String sanitizeDownloadFileName(String fileName) {
        if(fileName == null || fileName.trim().equals("")) {
            return "download";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }

    private String resolveDownloadFileName(String requestedFileName, String sourceUrl, String contentDisposition, String mimeType) {
        if(requestedFileName != null && !requestedFileName.trim().equals("")) {
            return sanitizeDownloadFileName(requestedFileName);
        }
        return sanitizeDownloadFileName(Utility.getFileNameFromDownload(sourceUrl, contentDisposition, mimeType));
    }

    private void downloadToDownloads(WebDownload download) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(download.url).openConnection();
        connection.setInstanceFollowRedirects(true);
        if(download.userAgent != null && !download.userAgent.equals("")) {
            connection.setRequestProperty("User-Agent", download.userAgent);
        }
        String cookie = CookieManager.getInstance().getCookie(download.url);
        if(cookie != null && !cookie.equals("")) {
            connection.setRequestProperty("Cookie", cookie);
        }
        HttpAuthCredentials credentials = HttpAuthCredentialStore.INSTANCE.getForHost(this, new URL(download.url).getHost());
        if(credentials != null) {
            String auth = credentials.getUsername() + ":" + credentials.getPassword();
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + encoded);
        }
        connection.connect();

        int responseCode = connection.getResponseCode();
        if(responseCode < 200 || responseCode >= 300) {
            throw new IOException("Unexpected HTTP " + responseCode);
        }

        String fileName = resolveDownloadFileName(download.fileName, download.url, download.contentDisposition, download.mimeType);
        String mimeType = download.mimeType;
        if(mimeType == null || mimeType.equals("")) {
            mimeType = connection.getContentType();
        }
        if(mimeType == null || mimeType.equals("")) {
            mimeType = "application/octet-stream";
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadToMediaStore(connection, fileName, mimeType);
        } else {
            downloadToLegacyDownloads(connection, fileName);
        }
        connection.disconnect();
    }

    private void downloadToMediaStore(HttpURLConnection connection, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if(uri == null) throw new IOException("Could not create download entry");

        try (InputStream input = connection.getInputStream(); OutputStream output = resolver.openOutputStream(uri)) {
            if(output == null) throw new IOException("Could not open download output stream");
            copyStream(input, output);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    private void downloadToLegacyDownloads(HttpURLConnection connection, String fileName) throws IOException {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if(!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Could not create downloads directory");
        }
        File destination = new File(downloadsDir, fileName);
        try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(destination)) {
            copyStream(input, output);
        }
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void openDownloadExternally(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG);
        }
    }

    private synchronized boolean consumeBlobDownloadToken(String token) {
        if(pendingBlobDownloadToken == null || !pendingBlobDownloadToken.equals(token)) {
            return false;
        }
        pendingBlobDownloadToken = null;
        return true;
    }

    private String beginGeneratedDownloadSession(String fileName, String mimeType) {
        String sessionId = UUID.randomUUID().toString();
        synchronized (pendingGeneratedDownloads) {
            cleanupGeneratedDownloadSessionsLocked();
            pendingGeneratedDownloads.put(sessionId, new PendingGeneratedDownload(fileName, mimeType));
        }
        return sessionId;
    }

    private boolean appendGeneratedDownloadChunk(String sessionId, String chunk) {
        synchronized (pendingGeneratedDownloads) {
            cleanupGeneratedDownloadSessionsLocked();
            PendingGeneratedDownload download = pendingGeneratedDownloads.get(sessionId);
            if(download == null) return false;
            download.dataUrl.append(chunk == null ? "" : chunk);
            return true;
        }
    }

    private PendingGeneratedDownload finishGeneratedDownloadSession(String sessionId) {
        synchronized (pendingGeneratedDownloads) {
            return pendingGeneratedDownloads.remove(sessionId);
        }
    }

    private void cleanupGeneratedDownloadSessionsLocked() {
        long cutoff = System.currentTimeMillis() - GENERATED_DOWNLOAD_TIMEOUT_MS;
        Iterator<Map.Entry<String, PendingGeneratedDownload>> iterator = pendingGeneratedDownloads.entrySet().iterator();
        while (iterator.hasNext()) {
            if(iterator.next().getValue().createdAt < cutoff) {
                iterator.remove();
            }
        }
    }

    private static class PendingGeneratedDownload {
        final String fileName;
        final String mimeType;
        final StringBuilder dataUrl = new StringBuilder();
        final long createdAt = System.currentTimeMillis();

        PendingGeneratedDownload(String fileName, String mimeType) {
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }

    private static class WebDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final String fileName;

        WebDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this(url, userAgent, contentDisposition, mimeType, null);
        }

        WebDownload(String url, String userAgent, String contentDisposition, String mimeType, String fileName) {
            this.url = normalizeUrl(url);
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }

        boolean isSupported() {
            return url.startsWith("http://") || url.startsWith("https://");
        }

        private static String normalizeUrl(String url) {
            return url == null ? "" : url;
        }
    }

    private class DownloadJavascriptInterface {
        @JavascriptInterface
        public String beginGeneratedDownload(String fileName, String mimeType) {
            return beginGeneratedDownloadSession(fileName, mimeType);
        }

        @JavascriptInterface
        public String beginBlobDownload(String token, String fileName, String mimeType) {
            if(!consumeBlobDownloadToken(token)) return "";
            return beginGeneratedDownloadSession(fileName, mimeType);
        }

        @JavascriptInterface
        public boolean appendGeneratedDownloadChunk(String sessionId, String chunk) {
            return WebViewActivity.this.appendGeneratedDownloadChunk(sessionId, chunk);
        }

        @JavascriptInterface
        public void finishGeneratedDownload(String sessionId) {
            PendingGeneratedDownload download = finishGeneratedDownloadSession(sessionId);
            if(download == null) {
                downloadLinkFailed();
                return;
            }
            runOnUiThread(() -> downloadDataUrl(download.dataUrl.toString(), download.fileName, null, download.mimeType));
        }

        @JavascriptInterface
        public void downloadLink(String url, String fileName, String mimeType) {
            runOnUiThread(() -> downloadFromJavascript(url, fileName, mimeType));
        }

        @JavascriptInterface
        public void saveGeneratedDownload(String dataUrl, String fileName, String mimeType) {
            runOnUiThread(() -> downloadDataUrl(dataUrl, fileName, null, mimeType));
        }

        @JavascriptInterface
        public void downloadLinkFailed() {
            runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG));
        }

        @JavascriptInterface
        public void saveBlob(String token, String dataUrl, String fileName, String mimeType) {
            if(!consumeBlobDownloadToken(token)) return;
            new Thread(() -> {
                try {
                    saveDataUrlToDownloads(dataUrl, fileName, mimeType);
                    runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_complete), Snackbar.LENGTH_SHORT));
                } catch (Exception e) {
                    Log.e("NativeAlpha", "Blob download failed", e);
                    runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG));
                }
            }).start();
        }

        @JavascriptInterface
        public void downloadFailed(String token) {
            if(!consumeBlobDownloadToken(token)) return;
            runOnUiThread(() -> NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download_failed), Snackbar.LENGTH_LONG));
        }
    }

    @Override
    public void onPermissionsGranted(int requestCode, @NonNull List<String> list) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            enablePermissionBoolOnWebApp(() -> webapp.setAllowLocationAccess(true));
            this.handleGeoPermissionCallback(true);
        }
        if (requestCode == Const.PERMISSION_CAMERA) {
            enablePermissionBoolOnWebApp(() -> webapp.setCameraPermission(true));
        }
        if (requestCode == Const.PERMISSION_RC_STORAGE) {
            if (dl_request_internal != null) {
                startInternalDownload(dl_request_internal);
                dl_request_internal = null;
            }
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> list) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            this.handleGeoPermissionCallback(false);
        }
    }

    private void handleGeoPermissionCallback(boolean allow) {
        if (mGeoPermissionRequestCallback != null) {
            mGeoPermissionRequestCallback.invoke(mGeoPermissionRequestOrigin, allow, false);
            mGeoPermissionRequestCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent intent) {

        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != CODE_OPEN_FILE) {
            return;
        }

        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) {
            return;
        }

        callback.onReceiveValue(parseFileChooserResult(resultCode, intent));
    }

    @Nullable
    private Uri[] parseFileChooserResult(int resultCode, @Nullable Intent intent) {
        if (resultCode != RESULT_OK) {
            return null;
        }

        if (intent == null) {
            return WebChromeClient.FileChooserParams.parseResult(resultCode, null);
        }

        List<Uri> uris = new ArrayList<>();
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                addFileChooserUri(uris, clipData.getItemAt(i).getUri(), intent);
            }
        }
        addFileChooserUri(uris, intent.getData(), intent);

        if (!uris.isEmpty()) {
            return uris.toArray(new Uri[0]);
        }

        return WebChromeClient.FileChooserParams.parseResult(resultCode, intent);
    }

    private void addFileChooserUri(@NonNull List<Uri> uris, @Nullable Uri uri, @NonNull Intent sourceIntent) {
        if (uri == null || uris.contains(uri)) {
            return;
        }

        int flags = sourceIntent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                && (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }

        uris.add(uri);
    }


    private class CustomWebChromeClient extends android.webkit.WebChromeClient {
        private View mCustomView;
        private WebChromeClient.CustomViewCallback mCustomViewCallback;
        private int mOriginalOrientation;
        private int mOriginalSystemUiVisibility;

        private void handlePermissionRequest(String resId,
                                             boolean currentState,
                                             String[] androidPermissions,
                                             int requestCode,
                                             List<String> permissionsToGrant,
                                             String[] webkitPermission,
                                             PermissionGrantedCallback successCallback) {
            boolean androidPermissionsMissing = !EasyPermissions.hasPermissions(WebViewActivity.this, androidPermissions);
            if (currentState && androidPermissionsMissing) {
                ActivityCompat.requestPermissions(WebViewActivity.this, androidPermissions, requestCode);
                return;
            }
            if (currentState && !androidPermissionsMissing) {
                permissionsToGrant.addAll(Arrays.asList(webkitPermission));
                handleGeoPermissionCallback(true);
                return;
            }

            new AlertDialog.Builder(WebViewActivity.this).setTitle(getPermissionRequestStringResource("dialog_permission_", resId, "_title"))
                    .setMessage(getPermissionRequestStringResource("dialog_permission_", resId, "_txt"))
                    .setPositiveButton(android.R.string.yes, (dialog, id) -> {
                        enablePermissionBoolOnWebApp(successCallback);
                        handleGeoPermissionCallback(true);
                        permissionsToGrant.addAll(Arrays.asList(webkitPermission));
                        if (androidPermissionsMissing) {
                            ActivityCompat.requestPermissions(WebViewActivity.this, androidPermissions, requestCode);
                        }
                    }).setNegativeButton(android.R.string.no, (dialog, id) -> handleGeoPermissionCallback(false)).create().show();
        }

        private String getPermissionRequestStringResource(String prefix, String variable, String suffix) {
            return getString(WebViewActivity.this.getResources().getIdentifier(prefix + variable + suffix, "string", WebViewActivity.this.getPackageName()));
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView, ValueCallback<Uri[]> pFilePathCallback,
                WebChromeClient.FileChooserParams fileChooserParams) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = pFilePathCallback;
            try {
                Intent intent = fileChooserParams.createIntent();
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
                startActivityForResult(intent, CODE_OPEN_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                pFilePathCallback.onReceiveValue(null);
                NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.no_filemanager), Snackbar.LENGTH_LONG);
                e.printStackTrace();
            }
            return true;
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            final Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawARGB(0, 0, 0, 0);
            return bitmap;
        }

        public void onHideCustomView() {
            ((FrameLayout) getWindow().getDecorView()).removeView(this.mCustomView);
            this.mCustomView = null;
            getWindow().getDecorView().setSystemUiVisibility(this.mOriginalSystemUiVisibility);
            setRequestedOrientation(this.mOriginalOrientation);
            this.mCustomViewCallback.onCustomViewHidden();
            this.mCustomViewCallback = null;
            showSystemBars();
        }

        public void onShowCustomView(View pView, WebChromeClient.CustomViewCallback pViewCallback) {
            if (this.mCustomView != null) {
                onHideCustomView();
                return;
            }
            this.mCustomView = pView;
            this.mOriginalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            this.mOriginalOrientation = getRequestedOrientation();
            this.mCustomViewCallback = pViewCallback;
            ((FrameLayout) getWindow().getDecorView()).addView(this.mCustomView, new FrameLayout.LayoutParams(-1, -1));
            hideSystemBars();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> permissionsToGrant = new ArrayList<>();

            boolean containsDrmRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
            boolean containsCameraRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            boolean containsMicrophoneRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);

            if (containsDrmRequest) {
                this.handlePermissionRequest("drm", webapp.isDrmAllowed(), new String[]{}, -1, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID}, () -> webapp.setDrmAllowed(true));
            }
            if (containsCameraRequest) {
                this.handlePermissionRequest("camera", webapp.isCameraPermission(), new String[]{Manifest.permission.CAMERA}, Const.PERMISSION_CAMERA, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, () -> webapp.setCameraPermission(true));
            }

            if (containsMicrophoneRequest) {
                this.handlePermissionRequest("microphone", webapp.isMicrophonePermission(), new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS}, Const.PERMISSION_AUDIO, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}, () -> webapp.setMicrophonePermission(true));
            }

            request.grant(permissionsToGrant.toArray(new String[0]));
        }


        public void onProgressChanged(WebView view, int progress) {

            if (DataManager.getInstance().getSettings().isShowProgressbar() || currently_reloading) {
                if (progressBar.getVisibility() == ProgressBar.GONE && progress < 100) {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                }

                progressBar.setProgress(progress);

                if (progress == 100) {
                    progressBar.setVisibility(ProgressBar.GONE);
                    currently_reloading = false;
                }
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(final String origin,
                                                       final GeolocationPermissions.Callback callback) {
            mGeoPermissionRequestCallback = callback;
            mGeoPermissionRequestOrigin = origin;
            this.handlePermissionRequest("location", webapp.isAllowLocationAccess(), new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, Const.PERMISSION_RC_LOCATION, Arrays.asList(new String[]{}), new String[]{}, () -> webapp.setAllowLocationAccess(true));

        }
    }

    private void showHttpAuthDialog(final HttpAuthHandler handler, String host, String realm) {
        HttpAuthCredentials credentials = HttpAuthCredentialStore.INSTANCE.get(this, host, realm);
        if(credentials != null) {
            new BiometricPromptHelper(WebViewActivity.this).showPrompt(
                    () -> handler.proceed(credentials.getUsername(), credentials.getPassword()),
                    handler::cancel,
                    getString(R.string.bioprompt_use_saved_http_auth_credentials)
            );
            return;
        }

        DialogHttpAuthBinding localBinding = DialogHttpAuthBinding.inflate(LayoutInflater.from(this));
        new AlertDialog.Builder(this)
                .setView(localBinding.getRoot())
                .setTitle(getString(R.string.http_auth_title))
                .setMessage(getString(R.string.enter_http_auth_credentials, realm, host))
                .setPositiveButton(getString(R.string.ok), (dialog, whichButton) -> {
                    String username = localBinding.username.getText().toString();
                    String password = localBinding.password.getText().toString();
                    if(localBinding.saveCredentials.isChecked()) {
                        HttpAuthCredentialStore.INSTANCE.save(this, host, realm, username, password);
                    }

                    handler.proceed(username, password);

                })
                .setNegativeButton(getString(R.string.cancel), (dialog, whichButton) -> handler.cancel())
                .show();
    }

    private void injectVisibilityHandler(WebView view) {
        view.evaluateJavascript("""
                if(!window.__nativeAlphaVisibilityHookInstalled) {
                  window.__nativeAlphaVisibilityHookInstalled = true;
                  document.addEventListener("visibilitychange", function(event) {
                    event.stopImmediatePropagation();
                  }, true);
                }
                """, null);
    }

    private class CustomBrowser extends WebViewClient {

        private AdFilter adFilter = AdFilter.Companion.get();

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            showHttpAuthDialog(handler, host, realm);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if(url.equals("about:blank")) {
                String langExtension = LocaleUtils.getFileEnding();
                wv.loadUrl("file:///android_asset/errorSite/error_" + langExtension + ".html");
            }
            injectVisibilityHandler(view);
            injectDownloadLinkHandler();
            super.onPageFinished(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            adFilter.performScript(view, url);
            super.onPageStarted(view, url, favicon);
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if(urlOnFirstPageload.equals("")) urlOnFirstPageload = request.getUrl().toString();

            if(webapp.isUseAdblock()) {
                return (adFilter.shouldIntercept(view, request)).getResourceResponse();
            }
            if (webapp.isBlockThirdPartyRequests()) {
                Uri uri = request.getUrl();
                Uri webapp_uri = Uri.parse(webapp.getBaseUrl());

                if(uri.getHost() != null) {
                    if (!uri.getHost().endsWith(webapp_uri.getHost())) {
                        return new WebResourceResponse("text/plain", "utf-8", null);
                    }
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {

            //This option is hidden in "expert settings"
            if (webapp.isIgnoreSslErrors()) {
                handler.proceed();
                return;
            }

            final AlertDialog.Builder builder = new AlertDialog.Builder(WebViewActivity.this);

            String message = getString(R.string.ssl_error_msg_line1) + " ";
            switch (error.getPrimaryError()) {
                case SslError.SSL_UNTRUSTED:
                    message += getString(R.string.ssl_error_unknown_authority) + "\n";
                    break;
                case SslError.SSL_EXPIRED:
                    message += getString(R.string.ssl_error_expired) + "\n";
                    break;
                case SslError.SSL_IDMISMATCH:
                    message += getString(R.string.ssl_error_id_mismatch) + "\n";
                    break;
                case SslError.SSL_NOTYETVALID:
                    message += getString(R.string.ssl_error_notyetvalid) + "\n";
                    break;
            }
            message += getString(R.string.ssl_error_msg_line2) + "\n";

            builder.setTitle(getString(R.string.ssl_error_title));
            builder.setMessage(message);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(getString(android.R.string.cancel), (dialog, id) -> handler.cancel());
            builder.setNegativeButton(getString(R.string.load_anyway), (dialog, id) -> handler.proceed());
            final AlertDialog dialog = builder.create();
            dialog.show();
//            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setPadding(5, 5, 5, 5);
//            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setBackgroundColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_orange_light));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_red_dark));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_green_dark));
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            super.onLoadResource(view, url);

           if (DataManager.getInstance().getWebApp(webappID).isRequestDesktop())
               view.evaluateJavascript("""
                        if(!window.__nativeAlphaDesktopViewportApplied) {
                          window.__nativeAlphaDesktopViewportApplied = true;
                        var needsForcedWidth = document.documentElement.clientWidth < 1200;
                        if(needsForcedWidth) {
                            var viewport = document.querySelector('meta[name=\"viewport\"]');
                            if(!viewport) {
                              viewport = document.createElement('meta');
                              viewport.setAttribute('name', 'viewport');
                              document.head.appendChild(viewport);
                            }
                            viewport.setAttribute('content', 'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                          }
                        }
                       """, null);
            injectVisibilityHandler(view);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            runOnUiThread(() -> setDarkModeIfNeeded());
            String url = request.getUrl().toString();
            WebApp webapp = DataManager.getInstance().getWebApp(webappID);

            if (url.startsWith("data:")) {
                downloadDataUrl(url, null, null);
                return true;
            }
            if (url.startsWith("blob:")) {
                downloadBlobUrl(url, null, null);
                return true;
            }
            if (url.startsWith("tel:")) {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
                startActivity(intent);
                return true;
            }
            if (url.startsWith("mailto:")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }

            if (webapp.isOpenUrlExternal()) {
                String base_url = webapp.getBaseUrl();
                Uri uri = Uri.parse(base_url);
                String host = uri.getHost();
                if (!url.contains(host)) {
                    view.getContext().startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
            }
            loadURL(view, url);
            return true;
        }
    }
}



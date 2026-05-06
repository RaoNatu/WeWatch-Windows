package com.wewatch.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.wewatch.android.net.WeWatchSocket;
import com.wewatch.android.net.WeWatchSocketListener;
import com.wewatch.android.sync.PlaybackStatus;
import com.wewatch.android.vlc.VlcRemoteClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_OPEN_MEDIA = 2001;
    private static final int REQUEST_INSTALL_PERMISSION = 2002;
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final double SEEK_DETECTION_THRESHOLD = 2.5;
    private static final double AUTO_SYNC_DRIFT_THRESHOLD = 4.0;
    private static final int AUTO_SYNC_DRIFT_STREAK = 2;
    private static final long AUTO_SYNC_SEEK_COOLDOWN = 12000;
    private static final long AUTO_SYNC_STATE_COOLDOWN = 3000;
    private static final double MAX_LATENCY_COMPENSATION = 6.0;
    private static final long HOST_RECONNECT_MAX_DELAY = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> events = new ArrayList<>();

    private ScrollView rootScroll;
    private View contentContainer;
    private View videoShell;
    private View videoLayout;
    private TextView videoPlaceholder;
    private TextView connectionBadge;
    private TextView serverStatusChip;
    private TextView vlcStatusChip;
    private TextView sessionSummary;
    private TextView versionText;
    private TextView mediaTitle;
    private TextView mediaMeta;
    private TextView sessionStats;
    private TextView peopleText;
    private TextView eventLog;
    private TextView updateStatusText;
    private TextView footerText;
    private TextView footerVersionText;
    private SeekBar seekBar;
    private EditText nameInput;
    private EditText hostInput;
    private EditText portInput;
    private EditText vlcUrlInput;
    private EditText vlcSecretInput;
    private Switch autoFollowSwitch;
    private Button joinButton;
    private Button disconnectButton;
    private Button openMediaButton;
    private Button openVlcButton;
    private Button connectVlcButton;
    private Button checkUpdateButton;
    private Button downloadUpdateButton;
    private FrameLayout themeToggleButton;
    private View themeToggleKnob;
    private TextView themeSunIcon;
    private TextView themeMoonIcon;
    private TextView vlcStatusText;
    private TextView audioTrackLabel;
    private TextView subtitleTrackLabel;
    private Spinner audioTrackSpinner;
    private Spinner subtitleTrackSpinner;
    private View subtitleDelayRow;
    private ImageButton rewindButton;
    private ImageButton playButton;
    private ImageButton pauseButton;
    private ImageButton forwardButton;
    private ImageButton syncButton;

    private final ExecutorService vlcExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final VlcRemoteClient vlcRemoteClient = new VlcRemoteClient();
    private UpdateChecker.Release pendingUpdate = null;
    private long updateDownloadId = -1;
    private boolean updateReceiverRegistered = false;
    private VlcRemoteClient.Status vlcStatus = null;
    private WeWatchSocket socket;
    private String socketUrl = "";
    private boolean connected = false;
    private boolean connecting = false;
    private boolean expectedClose = false;
    private boolean hostReconnectEnabled = false;
    private boolean hostReconnectPending = false;
    private int hostReconnectAttempt = 0;
    private int hostReconnectGeneration = 0;
    private String pendingHostFailure = "";
    private boolean userSeeking = false;
    private Uri mediaUri = null;
    private String mediaName = "No media";
    private boolean mediaPrepared = false;
    private boolean mediaLoadFailed = false;
    private long latency = -1;
    private long suppressLocalBroadcastUntil = 0;
    private long suppressStatusEventsUntil = 0;
    private long lastAutoSyncAt = 0;
    private long lastAutoSeekAt = 0;
    private int driftStreak = 0;
    private PlaybackStatus currentStatus = PlaybackStatus.idle(-1);
    private PlaybackStatus lastHostStatus = null;
    private String lastHostStamp = "";
    private long lastHostStatusReceivedAt = 0;
    private PlaybackStatus pendingSync = null;
    private boolean pendingSyncForce = false;
    private boolean darkTheme = true;
    private volatile boolean vlcBridgeConnected = false;
    private volatile boolean vlcBridgeConnecting = false;
    private volatile boolean vlcPollInFlight = false;
    private boolean keepAliveServiceRunning = false;
    private long lastVlcStatusAt = 0;

    private final BroadcastReceiver updateDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }

            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (downloadId == updateDownloadId) {
                installDownloadedUpdate();
            }
        }
    };

    private int bgColor;
    private int surfaceColor;
    private int surfaceAltColor;
    private int strokeColor;
    private int textColor;
    private int mutedColor;
    private int primaryColor;
    private int controlColor;
    private int accentColor;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateLocalStatus();
            updatePlaybackUi();
            updateStats();
            publishStatus();
            maybeAutoSync();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable pingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isSocketOpen()) {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("type", "ping");
                    payload.put("sentAt", System.currentTimeMillis());
                    sendJson(payload);
                } catch (JSONException ignored) {
                }
            }
            handler.postDelayed(this, 2000);
        }
    };

    private final Runnable vlcPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollVlcBridge();
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();
        setupAdaptiveLayout();
        restoreSettings();
        setupPlayer();
        setupInsets();
        setupInputScrolling();
        bindActions();
        applyTheme(darkTheme);
        setupUpdates();
        updateConnectionUi();
        updateVlcBridgeUi();
        updateLocalStatus();
        updatePlaybackUi();
        updateStats();

        handler.post(tickRunnable);
        handler.post(pingRunnable);
        handler.post(vlcPollRunnable);
        handler.postDelayed(() -> checkForUpdates(false), 2500);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setupAdaptiveLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hostReconnectEnabled && !connected && !connecting && !hostReconnectPending && socketUrl != null && !socketUrl.isEmpty()) {
            scheduleHostReconnect("App resumed");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        expectedClose = true;
        hostReconnectEnabled = false;
        hostReconnectPending = false;
        hostReconnectGeneration++;
        WeWatchSocket previousSocket = socket;
        socket = null;
        if (previousSocket != null) {
            previousSocket.close();
        }
        stopKeepAliveService();
        unregisterUpdateReceiver();
        vlcExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            if (canInstallUpdatePackages() && pendingUpdate != null) {
                beginUpdateDownload();
            } else {
                setUpdateStatus("Install permission is required for APK updates", false);
            }
            return;
        }

        if (requestCode == REQUEST_OPEN_MEDIA && resultCode == RESULT_OK && data != null && data.getData() != null) {
            openSelectedMedia(data);
        }
    }

    private void bindViews() {
        rootScroll = findViewById(R.id.rootScroll);
        contentContainer = findViewById(R.id.contentContainer);
        videoShell = findViewById(R.id.videoShell);
        videoLayout = findViewById(R.id.videoLayout);
        videoPlaceholder = findViewById(R.id.videoPlaceholder);
        connectionBadge = findViewById(R.id.connectionBadge);
        serverStatusChip = findViewById(R.id.serverStatusChip);
        vlcStatusChip = findViewById(R.id.vlcStatusChip);
        sessionSummary = findViewById(R.id.sessionSummary);
        versionText = findViewById(R.id.versionText);
        mediaTitle = findViewById(R.id.mediaTitle);
        mediaMeta = findViewById(R.id.mediaMeta);
        sessionStats = findViewById(R.id.sessionStats);
        peopleText = findViewById(R.id.peopleText);
        eventLog = findViewById(R.id.eventLog);
        updateStatusText = findViewById(R.id.updateStatusText);
        footerText = findViewById(R.id.footerText);
        seekBar = findViewById(R.id.seekBar);
        nameInput = findViewById(R.id.nameInput);
        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        vlcUrlInput = findViewById(R.id.vlcUrlInput);
        vlcSecretInput = findViewById(R.id.vlcSecretInput);
        autoFollowSwitch = findViewById(R.id.autoFollowSwitch);
        joinButton = findViewById(R.id.joinButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        openMediaButton = findViewById(R.id.openMediaButton);
        openVlcButton = findViewById(R.id.openVlcButton);
        connectVlcButton = findViewById(R.id.connectVlcButton);
        checkUpdateButton = findViewById(R.id.checkUpdateButton);
        downloadUpdateButton = findViewById(R.id.downloadUpdateButton);
        themeToggleButton = findViewById(R.id.themeToggleButton);
        themeToggleKnob = findViewById(R.id.themeToggleKnob);
        themeSunIcon = findViewById(R.id.themeSunIcon);
        themeMoonIcon = findViewById(R.id.themeMoonIcon);
        vlcStatusText = findViewById(R.id.vlcStatusText);
        audioTrackLabel = findViewById(R.id.audioTrackLabel);
        subtitleTrackLabel = findViewById(R.id.subtitleTrackLabel);
        audioTrackSpinner = findViewById(R.id.audioTrackSpinner);
        subtitleTrackSpinner = findViewById(R.id.subtitleTrackSpinner);
        subtitleDelayRow = findViewById(R.id.subtitleDelayRow);
        rewindButton = findViewById(R.id.rewindButton);
        playButton = findViewById(R.id.playButton);
        pauseButton = findViewById(R.id.pauseButton);
        forwardButton = findViewById(R.id.forwardButton);
        syncButton = findViewById(R.id.syncButton);
    }

    private void setupAdaptiveLayout() {
        if (contentContainer == null || videoShell == null) {
            return;
        }

        Configuration configuration = getResources().getConfiguration();
        int screenWidthDp = configuration.screenWidthDp;
        int smallestDp = configuration.smallestScreenWidthDp;
        boolean tablet = smallestDp >= 600 && screenWidthDp > 0;

        ViewGroup.LayoutParams layoutParams = contentContainer.getLayoutParams();
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams) layoutParams;
            if (tablet) {
                int maxWidthDp = smallestDp >= 840 ? 980 : 860;
                int sideRoomDp = smallestDp >= 840 ? 96 : 48;
                int targetDp = Math.max(560, Math.min(maxWidthDp, screenWidthDp - sideRoomDp));
                frameParams.width = dp(targetDp);
                frameParams.gravity = Gravity.CENTER_HORIZONTAL;
            } else {
                frameParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                frameParams.gravity = Gravity.NO_GRAVITY;
            }
            contentContainer.setLayoutParams(frameParams);
        }

        ViewGroup.LayoutParams videoParams = videoShell.getLayoutParams();
        videoParams.height = dp(tablet ? (smallestDp >= 840 ? 380 : 320) : 230);
        videoShell.setLayoutParams(videoParams);
    }

    private void restoreSettings() {
        SharedPreferences prefs = getSharedPreferences("wewatch", MODE_PRIVATE);
        nameInput.setText(prefs.getString("name", ""));
        hostInput.setText(prefs.getString("host", ""));
        portInput.setText(prefs.getString("port", "3000"));
        vlcUrlInput.setText(prefs.getString("vlcUrl", "http://127.0.0.1:8080"));
        vlcSecretInput.setText(prefs.getString("vlcSecret", ""));
        autoFollowSwitch.setChecked(prefs.getBoolean("autoFollow", true));
        darkTheme = prefs.getBoolean("darkTheme", true);
        vlcRemoteClient.configure(vlcUrlInput.getText().toString(), vlcSecretInput.getText().toString());
    }

    private void saveSettings() {
        getSharedPreferences("wewatch", MODE_PRIVATE)
                .edit()
                .putString("name", nameInput.getText().toString().trim())
                .putString("host", hostInput.getText().toString().trim())
                .putString("port", portInput.getText().toString().trim())
                .putString("vlcUrl", vlcUrlInput.getText().toString().trim())
                .putString("vlcSecret", vlcSecretInput.getText().toString().trim())
                .putBoolean("autoFollow", autoFollowSwitch.isChecked())
                .putBoolean("darkTheme", darkTheme)
                .apply();
    }

    private void setupPlayer() {
        videoLayout.setVisibility(View.GONE);
        videoPlaceholder.setText("Playback opens in VLC");
        videoPlaceholder.setVisibility(View.VISIBLE);
        if (audioTrackLabel != null) audioTrackLabel.setVisibility(View.GONE);
        if (audioTrackSpinner != null) audioTrackSpinner.setVisibility(View.GONE);
        if (subtitleTrackLabel != null) subtitleTrackLabel.setVisibility(View.GONE);
        if (subtitleTrackSpinner != null) subtitleTrackSpinner.setVisibility(View.GONE);
        if (subtitleDelayRow != null) subtitleDelayRow.setVisibility(View.GONE);
    }

    private void setupInsets() {
        rootScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            }

            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootScroll.requestApplyInsets();
        }
    }

    private void setupInputScrolling() {
        View.OnFocusChangeListener listener = (view, hasFocus) -> {
            if (!hasFocus) {
                return;
            }

            handler.postDelayed(() -> {
                int[] root = new int[2];
                int[] child = new int[2];
                rootScroll.getLocationOnScreen(root);
                view.getLocationOnScreen(child);
                int target = rootScroll.getScrollY() + child[1] - root[1] - dp(96);
                rootScroll.smoothScrollTo(0, Math.max(0, target));
            }, 220);
        };

        nameInput.setOnFocusChangeListener(listener);
        hostInput.setOnFocusChangeListener(listener);
        portInput.setOnFocusChangeListener(listener);
        vlcUrlInput.setOnFocusChangeListener(listener);
        vlcSecretInput.setOnFocusChangeListener(listener);
    }

    private void bindActions() {
        openMediaButton.setOnClickListener(view -> pickMedia());
        openVlcButton.setOnClickListener(view -> openCurrentMediaInVlc());
        connectVlcButton.setOnClickListener(view -> connectVlcBridge());
        checkUpdateButton.setOnClickListener(view -> checkForUpdates(true));
        downloadUpdateButton.setOnClickListener(view -> beginUpdateDownload());
        joinButton.setOnClickListener(view -> connectToHost());
        disconnectButton.setOnClickListener(view -> disconnectFromHost());
        autoFollowSwitch.setOnCheckedChangeListener((button, checked) -> saveSettings());
        themeToggleButton.setOnClickListener(view -> {
            applyTheme(!darkTheme);
            saveSettings();
        });

        rewindButton.setOnClickListener(view -> seekBySeconds(-10));
        forwardButton.setOnClickListener(view -> seekBySeconds(10));

        playButton.setOnClickListener(view -> {
            if (!ensureMediaReady()) return;
            suppressObservedChanges();
            playMedia();
            updateLocalStatus();
            publishStatus();
            sendAction("play", actionWithTime(currentStatus.time));
        });

        pauseButton.setOnClickListener(view -> {
            if (!ensureMediaReady()) return;
            suppressObservedChanges();
            pauseMedia();
            updateLocalStatus();
            publishStatus();
            sendAction("pause", actionWithTime(currentStatus.time));
        });

        syncButton.setOnClickListener(view -> {
            if (lastHostStatus == null) {
                log("No Windows host status yet");
                return;
            }
            applyHostSync(lastHostStatus, "Synced", true);
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    mediaMeta.setText(formatTime(progress) + " / " + formatTime(currentStatus.length));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (!ensureMediaReady()) return;
                int target = bar.getProgress();
                suppressObservedChanges();
                seekToMs(target * 1000L);
                updateLocalStatus();
                publishStatus();
                JSONObject action = actionWithTime(target);
                try {
                    action.put("state", currentStatus.state);
                } catch (JSONException ignored) {
                }
                sendAction("seek", action);
            }
        });
    }

    private void pickMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OPEN_MEDIA);
        } catch (Exception error) {
            log("No media picker is available");
        }
    }

    private void openSelectedMedia(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }

        mediaUri = uri;
        mediaName = queryDisplayName(uri);
        mediaPrepared = true;
        mediaLoadFailed = false;
        pendingSync = null;
        videoPlaceholder.setText("Playback opens in VLC");
        videoPlaceholder.setVisibility(View.VISIBLE);
        mediaTitle.setText(mediaName);
        mediaMeta.setText("00:00 / 00:00");
        seekBar.setProgress(0);
        seekBar.setMax(1);
        openMediaInVlc(uri);
        log("Opened in VLC: " + mediaName);
        suppressObservedChanges();
        sendAction("file", actionWithFile(mediaName));
        updateLocalStatus();
        publishStatus();
    }

    private void openCurrentMediaInVlc() {
        openVlcApp();
    }

    private void openVlcApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("org.videolan.vlc");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("vlc://"));
            fallback.setPackage("org.videolan.vlc");
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
        } catch (Exception error) {
            log("Could not open VLC: " + cleanError(error));
        }
    }

    private void openMediaInVlc(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null || type.trim().isEmpty()) {
            type = "video/*";
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, type);
        intent.setPackage("org.videolan.vlc");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
        } catch (Exception firstError) {
            try {
                intent.setPackage(null);
                startActivity(Intent.createChooser(intent, "Open with VLC"));
            } catch (Exception secondError) {
                mediaLoadFailed = true;
                log("Could not open VLC: " + cleanError(secondError));
            }
        }

    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? "Selected media" : path;
    }

    private void connectVlcBridge() {
        saveSettings();
        String url = vlcUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            log("Enter the VLC Remote URL");
            return;
        }

        vlcRemoteClient.configure(url, vlcSecretInput.getText().toString().trim());
        vlcBridgeConnecting = true;
        vlcBridgeConnected = false;
        updateVlcBridgeUi();
        log("Connecting VLC bridge");

        vlcExecutor.execute(() -> {
            try {
                VlcRemoteClient.Status status = vlcRemoteClient.connect();
                runOnUiThread(() -> {
                    vlcBridgeConnecting = false;
                    vlcBridgeConnected = true;
                    lastVlcStatusAt = System.currentTimeMillis();
                    handleVlcStatus(status);
                    updateVlcBridgeUi();
                    log("VLC bridge connected");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    vlcBridgeConnecting = false;
                    vlcBridgeConnected = false;
                    updateVlcBridgeUi();
                    log("VLC bridge failed: " + cleanError(error));
                });
            }
        });
    }

    private void pollVlcBridge() {
        if (!vlcBridgeConnected || vlcPollInFlight) {
            return;
        }

        vlcPollInFlight = true;
        vlcExecutor.execute(() -> {
            try {
                VlcRemoteClient.Status status = vlcRemoteClient.poll();
                runOnUiThread(() -> {
                    handleVlcStatus(status);
                    updateVlcBridgeUi();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isNetworkTimeout(error)) {
                        updateVlcBridgeUi();
                        return;
                    }
                    if (System.currentTimeMillis() - lastVlcStatusAt > 7000) {
                        vlcBridgeConnected = false;
                        updateVlcBridgeUi();
                        log("VLC bridge disconnected: " + cleanError(error));
                    }
                });
            } finally {
                vlcPollInFlight = false;
            }
        });
    }

    private void handleVlcStatus(VlcRemoteClient.Status status) {
        PlaybackStatus previousStatus = currentStatus;
        boolean receivedStatus = status != null;
        if (status != null) {
            vlcStatus = status;
            lastVlcStatusAt = System.currentTimeMillis();
            mediaPrepared = status.available || mediaUri != null;
            mediaLoadFailed = false;
            if (status.available && !status.filename.trim().isEmpty()) {
                mediaName = status.filename;
            }
        }

        if (pendingSync != null && hasPlayableMedia()) {
            PlaybackStatus sync = pendingSync;
            boolean force = pendingSyncForce;
            pendingSync = null;
            handler.postDelayed(() -> applyHostSync(sync, "Host sync", force), 150);
        }

        updateLocalStatus();
        if (receivedStatus) {
            detectLocalVlcChange(previousStatus, currentStatus);
        }
        updatePlaybackUi();
        updateStats();
        publishStatus();
    }

    private void updateVlcBridgeUi() {
        connectVlcButton.setEnabled(!vlcBridgeConnecting);
        if (vlcBridgeConnected) {
            connectVlcButton.setText("Reconnect VLC");
            vlcStatusChip.setText("VLC connected");
            styleStatusChip(vlcStatusChip, "live");
            String status = vlcRemoteClient.getModeLabel() + " connected";
            if (vlcStatus != null && vlcStatus.available) {
                status += " - " + vlcStatus.state + " " + formatTime(vlcStatus.projectedTimeMs() / 1000.0);
            }
            vlcStatusText.setText(status);
        } else if (vlcBridgeConnecting) {
            connectVlcButton.setText("Connecting");
            vlcStatusChip.setText("VLC connecting");
            styleStatusChip(vlcStatusChip, "warn");
            vlcStatusText.setText("VLC bridge connecting");
        } else {
            connectVlcButton.setText("Connect VLC");
            vlcStatusChip.setText("VLC offline");
            styleStatusChip(vlcStatusChip, "idle");
            vlcStatusText.setText("VLC bridge offline");
        }
        updateKeepAliveService();
    }

    private void connectToHost() {
        saveSettings();
        String endpoint = buildEndpoint();
        if (endpoint == null) {
            log("Enter the Windows PC IP address");
            return;
        }

        hostReconnectEnabled = true;
        hostReconnectPending = false;
        hostReconnectAttempt = 0;
        hostReconnectGeneration++;
        startHostSocket(endpoint, true, hostReconnectGeneration);
    }

    private void startHostSocket(String endpoint, boolean manual, int generation) {
        expectedClose = true;
        WeWatchSocket previousSocket = socket;
        socket = null;
        if (previousSocket != null) {
            previousSocket.close();
        }

        connecting = true;
        connected = false;
        expectedClose = false;
        socketUrl = endpoint;
        updateConnectionUi();
        log((manual ? "Connecting to " : "Reconnecting to ") + endpoint);

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException error) {
            connecting = false;
            hostReconnectEnabled = false;
            hostReconnectPending = false;
            updateConnectionUi();
            log("Invalid server address");
            return;
        }

        final WeWatchSocket[] socketHolder = new WeWatchSocket[1];
        final WeWatchSocket nextSocket = new WeWatchSocket(uri, new WeWatchSocketListener() {
            @Override
            public void onOpen() {
                runOnUiThread(() -> {
                    if (socket != socketHolder[0]) return;
                    connecting = false;
                    connected = true;
                    expectedClose = false;
                    hostReconnectPending = false;
                    hostReconnectAttempt = 0;
                    pendingHostFailure = "";
                    updateConnectionUi();
                    log(manual ? "Connected" : "Reconnected");
                    sendHello();
                    publishStatus();
                });
            }

            @Override
            public void onMessage(String message) {
                runOnUiThread(() -> {
                    if (socket == socketHolder[0]) {
                        handleSocketMessage(message);
                    }
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> {
                    if (socket != socketHolder[0]) return;
                    boolean shouldLog = connected && !expectedClose;
                    boolean shouldReconnect = hostReconnectEnabled && !expectedClose && generation == hostReconnectGeneration;
                    String failure = pendingHostFailure;
                    pendingHostFailure = "";
                    socket = null;
                    connected = false;
                    connecting = false;
                    updateConnectionUi();
                    if (shouldReconnect) {
                        scheduleHostReconnect(failure);
                    } else if (shouldLog) {
                        log("Disconnected");
                    }
                });
            }

            @Override
            public void onFailure(Exception error) {
                runOnUiThread(() -> {
                    if (socket != socketHolder[0]) return;
                    boolean shouldReconnect = hostReconnectEnabled && !expectedClose && generation == hostReconnectGeneration;
                    pendingHostFailure = cleanError(error);
                    connecting = false;
                    connected = false;
                    updateConnectionUi();
                    if (!shouldReconnect) {
                        log("Connection failed: " + pendingHostFailure);
                        pendingHostFailure = "";
                    }
                });
            }
        });

        socketHolder[0] = nextSocket;
        socket = nextSocket;
        nextSocket.connect();
    }

    private void scheduleHostReconnect(String reason) {
        if (!hostReconnectEnabled || expectedClose || socketUrl == null || socketUrl.isEmpty() || hostReconnectPending) {
            return;
        }

        hostReconnectAttempt += 1;
        int cappedAttempt = Math.min(hostReconnectAttempt, 5);
        long delay = Math.min(HOST_RECONNECT_MAX_DELAY, 1000L << Math.max(0, cappedAttempt - 1));
        int generation = hostReconnectGeneration;
        hostReconnectPending = true;
        connecting = true;
        connected = false;
        updateConnectionUi();

        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            log("Windows connection interrupted; reconnecting in " + (delay / 1000) + "s");
        } else {
            log("Windows connection interrupted; reconnecting in " + (delay / 1000) + "s (" + cleanReason + ")");
        }

        handler.postDelayed(() -> {
            if (!hostReconnectEnabled || expectedClose || generation != hostReconnectGeneration) {
                hostReconnectPending = false;
                return;
            }
            hostReconnectPending = false;
            if (!connected) {
                startHostSocket(socketUrl, false, generation);
            }
        }, delay);
    }

    private String buildEndpoint() {
        String host = hostInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        if (port.isEmpty()) {
            port = "3000";
        }
        if (host.isEmpty()) {
            return null;
        }

        host = host.replaceFirst("(?i)^wss?://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon < host.length() - 1) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
            portInput.setText(port);
        }
        hostInput.setText(host);
        return "ws://" + host + ":" + port;
    }

    private void disconnectFromHost() {
        expectedClose = true;
        hostReconnectEnabled = false;
        hostReconnectPending = false;
        hostReconnectAttempt = 0;
        hostReconnectGeneration++;
        pendingHostFailure = "";
        WeWatchSocket previousSocket = socket;
        socket = null;
        if (previousSocket != null) {
            previousSocket.close();
        }
        connected = false;
        connecting = false;
        lastHostStatus = null;
        lastHostStamp = "";
        updateConnectionUi();
        updateStats();
        log("Disconnected");
    }

    private void setupUpdates() {
        versionText.setText("Version " + getCurrentVersionName());
        setUpdateStatus("Updates ready", false);
        downloadUpdateButton.setVisibility(View.GONE);
        registerUpdateReceiver();
    }

    private String getCurrentVersionName() {
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return versionName == null || versionName.trim().isEmpty() ? "1.0.0" : versionName;
        } catch (Exception ignored) {
            return "1.0.0";
        }
    }

    private void registerUpdateReceiver() {
        if (updateReceiverRegistered) {
            return;
        }

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateDownloadReceiver, filter);
        }
        updateReceiverRegistered = true;
    }

    private void unregisterUpdateReceiver() {
        if (!updateReceiverRegistered) {
            return;
        }

        try {
            unregisterReceiver(updateDownloadReceiver);
        } catch (Exception ignored) {
        }
        updateReceiverRegistered = false;
    }

    private void setUpdateStatus(String message, boolean busy) {
        updateStatusText.setText(message);
        styleUpdateStatus(busy ? "warn" : pendingUpdate != null && pendingUpdate.hasApk() ? "live" : "idle");
        checkUpdateButton.setEnabled(!busy);
        if (busy) {
            downloadUpdateButton.setEnabled(false);
        } else {
            downloadUpdateButton.setEnabled(pendingUpdate != null && pendingUpdate.hasApk());
        }
    }

    private void checkForUpdates(boolean manual) {
        setUpdateStatus("Checking for updates...", true);
        if (manual) {
            log("Checking GitHub for updates");
        }

        updateExecutor.execute(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.checkLatest(getCurrentVersionName());
                runOnUiThread(() -> handleUpdateCheckResult(release, manual));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setUpdateStatus("Update check failed", false);
                    if (manual) {
                        log("Update check failed: " + cleanError(error));
                    }
                });
            }
        });
    }

    private void handleUpdateCheckResult(UpdateChecker.Release release, boolean manual) {
        if (release != null && release.noPublishedRelease) {
            pendingUpdate = null;
            downloadUpdateButton.setVisibility(View.GONE);
            setUpdateStatus("No published updates yet", false);
            if (manual) {
                log("No published updates found on GitHub");
            }
            return;
        }

        if (release == null || !release.newer) {
            pendingUpdate = null;
            downloadUpdateButton.setVisibility(View.GONE);
            setUpdateStatus("No new versions found", false);
            if (manual) {
                log("No new versions found");
            }
            return;
        }

        pendingUpdate = release;
        if (!release.hasApk()) {
            downloadUpdateButton.setVisibility(View.GONE);
            setUpdateStatus("v" + release.version + " is available on GitHub", false);
            if (manual) {
                log("Release v" + release.version + " has no Android APK attached");
            }
            showReleaseOnlyDialog(release);
            return;
        }

        downloadUpdateButton.setText("Download v" + release.version);
        downloadUpdateButton.setVisibility(View.VISIBLE);
        setUpdateStatus("v" + release.version + " is available", false);
        showUpdateAvailableDialog(release);
    }

    private void showUpdateAvailableDialog(UpdateChecker.Release release) {
        if (isFinishing()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("WeWatch v" + release.version + " is ready. Current version: " + getCurrentVersionName() + ".")
                .setPositiveButton("Download", (dialog, which) -> beginUpdateDownload())
                .setNegativeButton("Later", null)
                .setNeutralButton("Release page", (dialog, which) -> openUrl(release.releaseUrl))
                .show();
    }

    private void showReleaseOnlyDialog(UpdateChecker.Release release) {
        if (isFinishing()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("WeWatch v" + release.version + " is on GitHub, but no Android APK is attached.")
                .setPositiveButton("Open GitHub", (dialog, which) -> openUrl(release.releaseUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private void beginUpdateDownload() {
        if (pendingUpdate == null || !pendingUpdate.hasApk()) {
            log("No Android update APK is available");
            return;
        }

        if (!canInstallUpdatePackages()) {
            showInstallPermissionDialog();
            return;
        }

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            openUrl(pendingUpdate.apkUrl);
            return;
        }

        try {
            Uri updateUri = Uri.parse(pendingUpdate.apkUrl);
            String fileName = pendingUpdate.apkName == null || pendingUpdate.apkName.trim().isEmpty()
                    ? "WeWatch-Android-" + pendingUpdate.version + ".apk"
                    : pendingUpdate.apkName.replaceAll("[^A-Za-z0-9._-]", "_");

            DownloadManager.Request request = new DownloadManager.Request(updateUri);
            request.setTitle("WeWatch " + pendingUpdate.version);
            request.setDescription("Downloading Android update");
            request.setMimeType(APK_MIME_TYPE);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

            updateDownloadId = manager.enqueue(request);
            downloadUpdateButton.setEnabled(false);
            setUpdateStatus("Downloading v" + pendingUpdate.version + "...", true);
            log("Downloading update v" + pendingUpdate.version);
        } catch (Exception error) {
            setUpdateStatus("Could not start update download", false);
            log("Update download failed: " + cleanError(error));
            openUrl(pendingUpdate.apkUrl);
        }
    }

    private void installDownloadedUpdate() {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(updateDownloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                setUpdateStatus("Update download missing", false);
                return;
            }

            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int status = statusIndex >= 0 ? cursor.getInt(statusIndex) : DownloadManager.STATUS_FAILED;
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                setUpdateStatus("Update download failed", false);
                log("Update download did not finish");
                return;
            }
        }

        if (!canInstallUpdatePackages()) {
            showInstallPermissionDialog();
            return;
        }

        Uri apkUri = manager.getUriForDownloadedFile(updateDownloadId);
        if (apkUri == null) {
            setUpdateStatus("Open the GitHub APK to finish updating", false);
            openUrl(pendingUpdate != null ? pendingUpdate.apkUrl : "");
            return;
        }

        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, APK_MIME_TYPE);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
            setUpdateStatus("Installer opened", false);
            downloadUpdateButton.setVisibility(View.GONE);
            log("Android installer opened");
        } catch (Exception error) {
            setUpdateStatus("Open the GitHub APK to finish updating", false);
            log("Could not open installer: " + cleanError(error));
            openUrl(pendingUpdate != null ? pendingUpdate.apkUrl : "");
        }
    }

    private boolean canInstallUpdatePackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void showInstallPermissionDialog() {
        setUpdateStatus("Install permission is required for APK updates", false);
        if (isFinishing()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Allow app updates")
                .setMessage("Android needs permission before WeWatch can install a downloaded APK.")
                .setPositiveButton("Open settings", (dialog, which) -> openInstallSettings())
                .setNegativeButton("Later", null)
                .show();
    }

    private void openInstallSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_INSTALL_PERMISSION);
        } catch (Exception error) {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                log("Could not open Android install settings");
            }
        }
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception error) {
            log("Could not open link: " + cleanError(error));
        }
    }

    private void applyTheme(boolean dark) {
        darkTheme = dark;
        if (darkTheme) {
            bgColor = Color.rgb(11, 15, 20);
            surfaceColor = Color.rgb(20, 26, 34);
            surfaceAltColor = Color.rgb(25, 33, 43);
            strokeColor = Color.rgb(43, 52, 66);
            textColor = Color.rgb(244, 247, 251);
            mutedColor = Color.rgb(155, 168, 183);
            primaryColor = Color.rgb(66, 214, 164);
            controlColor = Color.rgb(32, 42, 54);
            accentColor = Color.rgb(255, 184, 107);
        } else {
            bgColor = Color.rgb(246, 248, 250);
            surfaceColor = Color.WHITE;
            surfaceAltColor = Color.rgb(238, 242, 246);
            strokeColor = Color.rgb(208, 217, 226);
            textColor = Color.rgb(22, 28, 36);
            mutedColor = Color.rgb(94, 107, 122);
            primaryColor = Color.rgb(18, 166, 126);
            controlColor = Color.rgb(232, 238, 244);
            accentColor = Color.rgb(178, 108, 34);
        }

        Window window = getWindow();
        window.setStatusBarColor(bgColor);
        window.setNavigationBarColor(bgColor);

        int systemUi = rootScroll.getSystemUiVisibility();
        if (darkTheme) {
            systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUi &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            systemUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        rootScroll.setSystemUiVisibility(systemUi);
        rootScroll.setBackgroundColor(bgColor);
        themeToggleButton.setContentDescription(darkTheme ? "Switch to light theme" : "Switch to dark theme");

        styleTextTree(rootScroll);
        stylePanel(R.id.mediaPanel);
        stylePanel(R.id.connectionPanel);
        stylePanel(R.id.sessionPanel);
        stylePanel(R.id.logPanel);
        stylePanel(R.id.updatePanel);
        styleInput(nameInput);
        styleInput(hostInput);
        styleInput(portInput);
        styleInput(vlcUrlInput);
        styleInput(vlcSecretInput);
        styleSecondaryButton(openMediaButton);
        styleSecondaryButton(openVlcButton);
        styleSecondaryButton(connectVlcButton);
        styleSecondaryButton(checkUpdateButton);
        styleSecondaryButton(disconnectButton);
        styleThemeToggle();
        stylePrimaryButton(joinButton);
        stylePrimaryButton(downloadUpdateButton);
        styleIconButton(rewindButton, false);
        styleIconButton(playButton, false);
        styleIconButton(pauseButton, false);
        styleIconButton(forwardButton, false);
        styleIconButton(syncButton, true);
        styleSeekBar();
        updateConnectionUi();
        updateVlcBridgeUi();
        styleUpdateStatus(pendingUpdate != null && pendingUpdate.hasApk() ? "live" : "idle");
    }

    private void stylePanel(int id) {
        View view = findViewById(id);
        if (view != null) {
            view.setBackground(rounded(surfaceColor, strokeColor, dp(8)));
        }
    }

    private void styleInput(EditText input) {
        input.setBackground(rounded(surfaceAltColor, strokeColor, dp(8)));
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setTextColor(textColor);
        input.setHintTextColor(mutedColor);
    }

    private void stylePrimaryButton(Button button) {
        button.setBackground(rounded(primaryColor, primaryColor, dp(8)));
        button.setTextColor(darkTheme ? bgColor : Color.WHITE);
    }

    private void styleSecondaryButton(Button button) {
        button.setBackground(rounded(controlColor, strokeColor, dp(8)));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setTextColor(textColor);
    }

    private void styleThemeToggle() {
        themeToggleButton.setBackground(rounded(darkTheme ? controlColor : Color.WHITE, strokeColor, dp(22)));
        themeToggleButton.setPadding(0, 0, 0, 0);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) themeToggleKnob.getLayoutParams();
        params.width = dp(30);
        params.height = dp(30);
        params.gravity = Gravity.CENTER_VERTICAL;
        int knobMargin = darkTheme ? dp(40) : dp(4);
        params.leftMargin = knobMargin;
        params.setMarginStart(knobMargin);
        params.rightMargin = 0;
        params.setMarginEnd(0);
        themeToggleKnob.setLayoutParams(params);
        themeToggleKnob.setBackground(rounded(primaryColor, primaryColor, dp(15)));

        themeSunIcon.setTextColor(darkTheme ? mutedColor : Color.WHITE);
        themeMoonIcon.setTextColor(darkTheme ? bgColor : mutedColor);
    }

    private void styleIconButton(ImageButton button, boolean primary) {
        button.setBackground(rounded(primary ? primaryColor : controlColor, primary ? primaryColor : strokeColor, dp(8)));
        button.setColorFilter(primary && !darkTheme ? Color.WHITE : textColor);
    }

    private void styleConnectionBadge(String state) {
        styleStatusChip(connectionBadge, state);
    }

    private void styleStatusChip(TextView chip, String state) {
        int fill = controlColor;
        int stroke = strokeColor;
        if ("live".equals(state)) {
            fill = darkTheme ? Color.rgb(23, 58, 49) : Color.rgb(218, 248, 238);
            stroke = primaryColor;
        } else if ("warn".equals(state)) {
            fill = darkTheme ? Color.rgb(61, 44, 23) : Color.rgb(255, 239, 216);
            stroke = accentColor;
        }

        chip.setBackground(rounded(fill, stroke, dp(8)));
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setTextColor(textColor);
    }

    private void styleUpdateStatus(String state) {
        if (updateStatusText == null) {
            return;
        }

        styleStatusChip(updateStatusText, state);
        updateStatusText.setTextColor("idle".equals(state) ? mutedColor : textColor);
    }

    private void styleSeekBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(primaryColor));
            seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(primaryColor));
            seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(strokeColor));
        }
    }

    private void styleTextTree(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(text == sessionSummary || text == versionText || text == mediaMeta || text == vlcStatusText || text == peopleText || text == eventLog || text == updateStatusText || text == footerText || text == footerVersionText
                    ? mutedColor
                    : textColor);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleTextTree(group.getChildAt(i));
            }
        }
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void updateConnectionUi() {
        disconnectButton.setEnabled(connected || connecting);
        joinButton.setEnabled(!connecting);

        if (connected) {
            connectionBadge.setText("Connected");
            serverStatusChip.setText("Server connected");
            styleConnectionBadge("live");
            styleStatusChip(serverStatusChip, "live");
            sessionSummary.setText(socketUrl);
        } else if (connecting) {
            connectionBadge.setText(hostReconnectAttempt > 0 ? "Reconnecting" : "Connecting");
            serverStatusChip.setText(hostReconnectAttempt > 0 ? "Server reconnecting" : "Server connecting");
            styleConnectionBadge("warn");
            styleStatusChip(serverStatusChip, "warn");
            sessionSummary.setText(socketUrl);
        } else {
            connectionBadge.setText("Offline");
            serverStatusChip.setText("Server offline");
            styleConnectionBadge("idle");
            styleStatusChip(serverStatusChip, "idle");
            sessionSummary.setText("Offline");
            peopleText.setText("No people connected");
        }
        updateKeepAliveService();
    }

    private void updateKeepAliveService() {
        boolean shouldRun = connected || connecting || hostReconnectEnabled || vlcBridgeConnected || vlcBridgeConnecting;
        if (shouldRun == keepAliveServiceRunning) {
            return;
        }

        Intent intent = new Intent(this, SyncKeepAliveService.class);
        try {
            if (shouldRun) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
                keepAliveServiceRunning = true;
            } else {
                stopService(intent);
                keepAliveServiceRunning = false;
            }
        } catch (Exception error) {
            keepAliveServiceRunning = false;
            if (eventLog != null) {
                log("Background sync service failed: " + cleanError(error));
            }
        }
    }

    private void stopKeepAliveService() {
        try {
            stopService(new Intent(this, SyncKeepAliveService.class));
        } catch (Exception ignored) {
        }
        keepAliveServiceRunning = false;
    }

    private boolean isSocketOpen() {
        return socket != null && socket.isOpen();
    }

    private void sendHello() {
        JSONObject hello = new JSONObject();
        try {
            hello.put("type", "hello");
            hello.put("name", getDisplayName());
            hello.put("role", "member");
            sendJson(hello);
        } catch (JSONException ignored) {
        }
    }

    private String getDisplayName() {
        String name = nameInput.getText().toString().trim();
        return name.isEmpty() ? "Android" : name;
    }

    private void sendJson(JSONObject payload) {
        if (isSocketOpen()) {
            socket.sendText(payload.toString());
        }
    }

    private void handleSocketMessage(String message) {
        try {
            JSONObject data = new JSONObject(message);
            String type = data.optString("type", "");

            if ("pong".equals(type)) {
                latency = Math.max(0, System.currentTimeMillis() - data.optLong("sentAt", System.currentTimeMillis()));
                updateLocalStatus();
                publishStatus();
                updateStats();
                return;
            }

            if ("clients".equals(type)) {
                JSONArray clients = data.optJSONArray("clients");
                updateHostStatus(clients);
                renderPeople(clients);
                updateStats();
                maybeAutoSync();
                return;
            }

            if ("event".equals(type)) {
                JSONObject event = data.optJSONObject("event");
                log(data.optString("message", event != null ? event.optString("message", "Session event") : "Session event"));
                return;
            }

            if ("sync".equals(type)) {
                PlaybackStatus status = PlaybackStatus.fromJson(data.optJSONObject("status"));
                lastHostStatus = status;
                lastHostStatusReceivedAt = System.currentTimeMillis();
                applyHostSync(status, "Host sync", true);
                return;
            }

            if ("control".equals(type)) {
                applyRemoteControl(data.optJSONObject("action"), data.optString("name", "Someone"));
            }
        } catch (JSONException error) {
            log("Invalid session message");
        }
    }

    private void updateHostStatus(JSONArray clients) {
        if (clients == null) {
            return;
        }

        for (int i = 0; i < clients.length(); i++) {
            JSONObject client = clients.optJSONObject(i);
            if (client == null || !"host".equals(client.optString("role"))) {
                continue;
            }

            PlaybackStatus next = PlaybackStatus.fromJson(client.optJSONObject("status"));
            String stamp = next.stamp();
            if (!stamp.equals(lastHostStamp)) {
                lastHostStatusReceivedAt = System.currentTimeMillis();
                lastHostStamp = stamp;
            }
            lastHostStatus = next;
            return;
        }
    }

    private void renderPeople(JSONArray clients) {
        if (clients == null || clients.length() == 0) {
            peopleText.setText("No people connected");
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < clients.length(); i++) {
            JSONObject client = clients.optJSONObject(i);
            if (client == null) continue;

            PlaybackStatus status = PlaybackStatus.fromJson(client.optJSONObject("status"));
            String name = client.optString("name", "User");
            String role = "host".equals(client.optString("role")) ? "Host" : "Member";
            builder.append(name)
                    .append(" - ")
                    .append(role)
                    .append(": ")
                    .append(status.state)
                    .append(" ")
                    .append(formatTime(status.time))
                    .append(" / ")
                    .append(formatTime(status.length));

            if (!"No media".equals(status.filename)) {
                builder.append("\n").append(status.filename);
            }
            if (i < clients.length() - 1) {
                builder.append("\n\n");
            }
        }

        peopleText.setText(builder.toString());
    }

    private void publishStatus() {
        if (!isSocketOpen() || currentStatus == null) {
            return;
        }

        try {
            currentStatus.latency = latency;
            JSONObject payload = new JSONObject();
            payload.put("type", "status");
            payload.put("status", currentStatus.toJson());
            payload.put("suppressEvents", System.currentTimeMillis() < suppressStatusEventsUntil);
            sendJson(payload);
        } catch (JSONException ignored) {
        }
    }

    private void sendAction(String kind, JSONObject action) {
        if (!isSocketOpen()) {
            return;
        }

        try {
            action.put("kind", kind);
            JSONObject payload = new JSONObject();
            payload.put("type", "action");
            payload.put("action", action);
            sendJson(payload);
        } catch (JSONException ignored) {
        }
    }

    private JSONObject actionWithTime(double time) {
        return actionWithTime(time, "android");
    }

    private JSONObject actionWithTime(double time, String source) {
        JSONObject action = new JSONObject();
        try {
            action.put("time", time);
            action.put("source", source);
        } catch (JSONException ignored) {
        }
        return action;
    }

    private JSONObject actionWithFile(String filename) {
        return actionWithFile(filename, currentStatus != null ? currentStatus.time : 0, "android");
    }

    private JSONObject actionWithFile(String filename, double time, String source) {
        JSONObject action = new JSONObject();
        try {
            action.put("filename", filename);
            action.put("time", time);
            action.put("source", source);
        } catch (JSONException ignored) {
        }
        return action;
    }

    private void updateLocalStatus() {
        PlaybackStatus status = new PlaybackStatus();
        status.latency = latency;
        status.updatedAt = System.currentTimeMillis();
        status.filename = mediaName;

        if (!hasPlayableMedia()) {
            currentStatus = PlaybackStatus.idle(latency);
            return;
        }

        if (vlcStatus != null && vlcStatus.available) {
            long durationMs = Math.max(0, getPlayerLength());
            long currentMs = Math.max(0, getPlayerTime());
            status.filename = firstNonEmpty(vlcStatus.filename, mediaName);
            status.length = durationMs / 1000.0;
            status.time = currentMs / 1000.0;
            status.position = status.length > 0 ? clamp(status.time / status.length, 0, 1) : 0;
            status.state = vlcStatus.state;
            currentStatus = status;
            return;
        }

        if (!mediaPrepared) {
            status.state = "idle";
            status.time = 0;
            status.length = 0;
            status.position = 0;
            currentStatus = status;
            return;
        }

        status.length = 0;
        status.time = 0;
        status.position = 0;
        status.state = "paused";
        currentStatus = status;
    }

    private void updatePlaybackUi() {
        if (!hasPlayableMedia()) {
            mediaTitle.setText("No media");
            mediaMeta.setText("00:00 / 00:00");
            videoPlaceholder.setText("No media");
            videoPlaceholder.setVisibility(View.VISIBLE);
            seekBar.setMax(1);
            seekBar.setProgress(0);
            return;
        }

        mediaTitle.setText(firstNonEmpty(vlcStatus != null ? vlcStatus.title : "", mediaName));
        mediaMeta.setText(formatTime(currentStatus.time) + " / " + formatTime(currentStatus.length));
        videoPlaceholder.setText("Playing in VLC app");
        videoPlaceholder.setVisibility(View.VISIBLE);
        if (!userSeeking) {
            int max = Math.max(1, (int) Math.floor(currentStatus.length));
            seekBar.setMax(max);
            seekBar.setProgress(Math.min(max, (int) Math.floor(currentStatus.time)));
        }
    }

    private void playMedia() {
        if (vlcStatus != null) {
            vlcStatus.playing = true;
            vlcStatus.state = "playing";
            vlcStatus.receivedAt = System.currentTimeMillis();
        }
        runVlcCommand("play", () -> vlcRemoteClient.play());
    }

    private void pauseMedia() {
        if (vlcStatus != null) {
            vlcStatus.timeMs = getPlayerTime();
            vlcStatus.playing = false;
            vlcStatus.state = "paused";
            vlcStatus.receivedAt = System.currentTimeMillis();
        }
        runVlcCommand("pause", () -> vlcRemoteClient.pause());
    }

    private void seekToMs(long targetMs) {
        long length = getPlayerLength();
        long clamped = length > 0 ? Math.min(length, Math.max(0, targetMs)) : Math.max(0, targetMs);
        if (vlcStatus != null) {
            vlcStatus.timeMs = clamped;
            vlcStatus.receivedAt = System.currentTimeMillis();
        }
        runVlcCommand("seek", () -> vlcRemoteClient.seek(clamped));
    }

    private void seekBySeconds(int seconds) {
        if (!ensureMediaReady()) return;

        long targetMs = getPlayerTime() + (seconds * 1000L);
        suppressObservedChanges();
        seekToMs(targetMs);
        updateLocalStatus();
        updatePlaybackUi();
        publishStatus();

        JSONObject action = actionWithTime(currentStatus.time);
        try {
            action.put("state", currentStatus.state);
        } catch (JSONException ignored) {
        }
        sendAction("seek", action);
    }

    private boolean isPlaying() {
        return vlcStatus != null && vlcStatus.playing;
    }

    private long getPlayerTime() {
        return vlcStatus == null ? 0 : Math.max(0, vlcStatus.projectedTimeMs());
    }

    private long getPlayerLength() {
        return vlcStatus == null ? 0 : Math.max(0, vlcStatus.lengthMs);
    }

    private boolean ensureMediaReady() {
        if (!vlcBridgeConnected) {
            log("Connect the VLC bridge first");
            return false;
        }
        if (mediaLoadFailed) {
            log("VLC could not open this media. Choose it again.");
            return false;
        }
        if (!hasPlayableMedia()) {
            log("Open media in VLC first");
            return false;
        }
        return true;
    }

    private void runVlcCommand(String label, VlcCommand command) {
        if (!vlcBridgeConnected) {
            return;
        }

        vlcExecutor.execute(() -> {
            try {
                command.run();
                VlcRemoteClient.Status status = vlcRemoteClient.poll();
                runOnUiThread(() -> handleVlcStatus(status));
            } catch (Exception error) {
                runOnUiThread(() -> log("VLC " + label + " failed: " + cleanError(error)));
            }
        });
    }

    private void applyRemoteControl(JSONObject action, String actorName) {
        if (action == null) {
            return;
        }

        String kind = action.optString("kind", "");
        if ("file".equals(kind)) {
            log(actorName + " selected " + action.optString("filename", "a file"));
            return;
        }
        if ("sync".equals(kind)) {
            if (lastHostStatus != null) {
                applyHostSync(lastHostStatus, "Synced", true);
            }
            return;
        }

        if (!ensureMediaReady()) {
            return;
        }

        suppressObservedChanges();
        boolean hasTime = action.has("time") && !action.isNull("time");
        int targetMs = Math.max(0, (int) Math.floor(action.optDouble("time", currentStatus.time) * 1000));

        if (hasTime && ("play".equals(kind) || "pause".equals(kind))) {
            seekToMs(targetMs);
        }

        if ("play".equals(kind)) {
            playMedia();
        } else if ("pause".equals(kind)) {
            pauseMedia();
        } else if ("seek".equals(kind)) {
            seekToMs(targetMs);
            String state = action.optString("state", currentStatus.state);
            if ("playing".equals(state)) {
                playMedia();
            } else if ("paused".equals(state) || "stopped".equals(state)) {
                pauseMedia();
            }
        } else {
            return;
        }

        handler.postDelayed(() -> {
            updateLocalStatus();
            publishStatus();
            updatePlaybackUi();
            updateStats();
        }, 250);
    }

    private void applyHostSync(PlaybackStatus status, String label, boolean force) {
        applyHostSync(status, label, force, Double.NaN, true);
    }

    private void applyHostSync(PlaybackStatus status, String label, boolean force, double targetOverride, boolean allowSeek) {
        if (status == null || "offline".equals(status.state)) {
            log("No Windows host status yet");
            return;
        }

        if (!hasPlayableMedia()) {
            log("Open the matching media in VLC");
            return;
        }

        if (!vlcBridgeConnected) {
            log("Connect the VLC bridge first");
            return;
        }

        if (!mediaPrepared && vlcStatus == null) {
            pendingSync = status;
            pendingSyncForce = force;
            log("Sync queued until media is ready");
            return;
        }

        double projected = Double.isNaN(targetOverride) ? getProjectedHostTime(status) : targetOverride;
        int targetMs = Math.max(0, (int) Math.floor(projected * 1000));
        double localTime = getProjectedLocalTime();
        double drift = localTime - (targetMs / 1000.0);
        boolean shouldSeek = allowSeek && (force || Math.abs(drift) > 1.25);
        boolean stateMismatch = !currentStatus.state.equals(status.state);

        suppressObservedChanges();
        if (shouldSeek) {
            seekToMs(targetMs);
            lastAutoSeekAt = System.currentTimeMillis();
        }

        if (stateMismatch && "playing".equals(status.state)) {
            playMedia();
        } else if (stateMismatch && ("paused".equals(status.state) || "stopped".equals(status.state))) {
            pauseMedia();
        }

        lastAutoSyncAt = System.currentTimeMillis();
        log(label + " at " + formatTime(targetMs / 1000.0));
        handler.postDelayed(() -> {
            updateLocalStatus();
            publishStatus();
            updatePlaybackUi();
            updateStats();
        }, 250);
    }

    private void maybeAutoSync() {
        if (!connected || !autoFollowSwitch.isChecked() || lastHostStatus == null || currentStatus == null) {
            driftStreak = 0;
            return;
        }
        if (!hasPlayableMedia() || !mediaPrepared) {
            driftStreak = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAutoSyncAt < AUTO_SYNC_STATE_COOLDOWN) {
            return;
        }

        double expectedHostTime = getProjectedHostTime(lastHostStatus);
        double localTime = getProjectedLocalTime();
        double drift = localTime - expectedHostTime;
        double absDrift = Math.abs(drift);
        boolean stateMismatch = !currentStatus.state.equals(lastHostStatus.state)
                && !"idle".equals(lastHostStatus.state)
                && !"offline".equals(lastHostStatus.state);

        if (stateMismatch) {
            driftStreak = 0;
            applyHostSync(lastHostStatus, "Auto state synced", false, expectedHostTime, absDrift > AUTO_SYNC_DRIFT_THRESHOLD);
            return;
        }

        if (absDrift < AUTO_SYNC_DRIFT_THRESHOLD) {
            driftStreak = 0;
            return;
        }

        driftStreak += 1;
        if (driftStreak < AUTO_SYNC_DRIFT_STREAK || now - lastAutoSeekAt < AUTO_SYNC_SEEK_COOLDOWN) {
            return;
        }

        driftStreak = 0;
        applyHostSync(lastHostStatus, "Auto timeline synced", false, expectedHostTime, true);
    }

    private void suppressObservedChanges() {
        long until = System.currentTimeMillis() + 2500;
        suppressLocalBroadcastUntil = Math.max(suppressLocalBroadcastUntil, until);
        suppressStatusEventsUntil = Math.max(suppressStatusEventsUntil, until);
    }

    private void detectLocalVlcChange(PlaybackStatus previous, PlaybackStatus next) {
        if (!isSocketOpen() || System.currentTimeMillis() < suppressLocalBroadcastUntil) {
            return;
        }
        if (previous == null || next == null || "offline".equals(previous.state) || "offline".equals(next.state)) {
            return;
        }

        boolean hasMedia = next.filename != null
                && !next.filename.trim().isEmpty()
                && !"No media".equals(next.filename);

        if (hasMedia && !sameText(previous.filename, next.filename)) {
            sendAction("file", actionWithFile(next.filename, next.time, "vlc"));
            return;
        }

        if (!hasMedia || !sameText(previous.filename, next.filename)) {
            return;
        }

        if (!sameText(previous.state, next.state)) {
            if ("playing".equals(next.state)) {
                sendAction("play", actionWithTime(next.time, "vlc"));
            } else if ("paused".equals(next.state) || "stopped".equals(next.state) || "idle".equals(next.state)) {
                sendAction("pause", actionWithTime(next.time, "vlc"));
            }
            return;
        }

        double elapsed = Math.max(0, (next.updatedAt - previous.updatedAt) / 1000.0);
        double expectedDelta = "playing".equals(previous.state) ? elapsed : 0;
        double actualDelta = next.time - previous.time;
        double jump = actualDelta - expectedDelta;

        if (Math.abs(jump) > SEEK_DETECTION_THRESHOLD) {
            JSONObject action = actionWithTime(next.time, "vlc");
            try {
                action.put("state", next.state);
            } catch (JSONException ignored) {
            }
            sendAction("seek", action);
        }
    }

    private double getProjectedLocalTime() {
        if (currentStatus == null) {
            return 0;
        }

        if (!"playing".equals(currentStatus.state)) {
            return currentStatus.time;
        }

        double age = Math.max(0, (System.currentTimeMillis() - currentStatus.updatedAt) / 1000.0);
        return currentStatus.time + age;
    }

    private double getProjectedHostTime(PlaybackStatus status) {
        if (status == null) {
            return 0;
        }

        if (!"playing".equals(status.state)) {
            return status.time;
        }

        double receiveAge = lastHostStatusReceivedAt > 0
                ? Math.max(0, (System.currentTimeMillis() - lastHostStatusReceivedAt) / 1000.0)
                : 0;
        return status.time + getEstimatedTransitSeconds(status) + receiveAge;
    }

    private double getEstimatedTransitSeconds(PlaybackStatus hostStatus) {
        long hostLatency = hostStatus != null ? Math.max(0, hostStatus.latency) : 0;
        long localLatency = Math.max(0, latency);
        return clamp((hostLatency + localLatency) / 2000.0, 0, MAX_LATENCY_COMPENSATION);
    }

    private void updateStats() {
        String latencyText = latency >= 0 ? latency + "ms" : "--";
        String driftText = "--";
        if (lastHostStatus != null && currentStatus != null && mediaPrepared) {
            driftText = formatDrift(getProjectedLocalTime() - getProjectedHostTime(lastHostStatus));
        }
        sessionStats.setText("Latency " + latencyText + "  |  Drift " + driftText);
    }

    private void log(String message) {
        String time = DateFormat.format("HH:mm:ss", new Date()).toString();
        events.add(0, time + "  " + message);
        while (events.size() > 8) {
            events.remove(events.size() - 1);
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < events.size(); i++) {
            builder.append(events.get(i));
            if (i < events.size() - 1) {
                builder.append('\n');
            }
        }
        eventLog.setText(builder.toString());
    }

    private String formatTime(double value) {
        int total = Math.max(0, (int) Math.floor(value));
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private String formatDrift(double value) {
        double drift = Math.abs(value) < 0.005 ? 0 : value;
        if (drift != 0 && Math.abs(drift) < 0.1) {
            return drift < 0 ? "-<0.10s" : "<0.10s";
        }
        return String.format(Locale.US, "%.2fs", drift);
    }

    private double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private boolean hasPlayableMedia() {
        return mediaUri != null || (vlcStatus != null && vlcStatus.available);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean sameText(String left, String right) {
        String safeLeft = left == null ? "" : left.trim();
        String safeRight = right == null ? "" : right.trim();
        return safeLeft.equals(safeRight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String cleanError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private boolean isNetworkTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private interface VlcCommand {
        void run() throws Exception;
    }

}

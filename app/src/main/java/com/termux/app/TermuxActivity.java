package com.termux.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.compose.ui.platform.ComposeView;

import com.termux.R;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.fleet.AgentFleetComposer;
import com.termux.app.fleet.AgentFleetContract;
import com.termux.app.fleet.AgentFleetDisplayDensityStore;
import com.termux.app.fleet.NativeSessionController;
import com.termux.app.fleet.NativeSessionHost;
import com.termux.app.fleet.TerminalScrollbackController;
import com.termux.app.fleet.DrawerSessionSurface;
import com.termux.app.fleet.DrawerSessionStore;
import com.termux.app.fleet.FleetSession;
import com.termux.app.fleet.FleetRuntime;
import com.termux.app.fleet.AgentFleetSessionResumeController;
import com.termux.app.fleet.UnifiedTerminalDrawerController;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.packages.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;
import com.termux.app.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.app.utils.CrashUtils;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.ComponentActivity;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;
import java.io.File;
import java.util.UUID;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends ComponentActivity implements ServiceConnection, NativeSessionHost {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionClient mTermuxTerminalSessionClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    private UnifiedTerminalDrawerController mUnifiedDrawerController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean isOnResumeAfterOnCreate = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;



    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final int REQUEST_AGENT_FLEET_IMAGES = 8401;
    private static final int REQUEST_AGENT_FLEET_CAMERA = 8402;
    private Uri mAgentFleetCameraUri;
    private NativeSessionController mAgentFleetNativeSession;
    private TerminalScrollbackController mAgentFleetTerminalScrollback;
    private AgentFleetSessionResumeController mAgentFleetSessionResume;
    private FleetRuntime mAgentFleetResumeRuntime;
    private boolean mShouldRestoreAgentFleetSession;

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {

        Logger.logDebug(LOG_TAG, "onCreate");
        isOnResumeAfterOnCreate = true;

        // Check if a crash happened on last run of the app and show a
        // notification with the crash details if it did
        CrashUtils.notifyAppCrashOnLastRun(this, LOG_TAG);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load termux shared properties
        mProperties = new TermuxAppSharedProperties(this);

        setActivityTheme();

        super.onCreate(savedInstanceState);

        mShouldRestoreAgentFleetSession = savedInstanceState != null && managedSessionId(getIntent()) != null;

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        configureUnifiedDrawer();
        mUnifiedDrawerController = new UnifiedTerminalDrawerController(this,
            findViewById(R.id.left_drawer));

        setTerminalToolbarView(savedInstanceState);

        mAgentFleetNativeSession = new NativeSessionController(this,
            findViewById(R.id.agent_fleet_native_session), true,
            findViewById(R.id.agent_fleet_terminal_chrome));
        findViewById(R.id.agent_fleet_native_return).setOnClickListener(v -> {
            if (mAgentFleetNativeSession != null) mAgentFleetNativeSession.showNative();
        });
        mAgentFleetTerminalScrollback = new TerminalScrollbackController(this);
        mAgentFleetTerminalScrollback.setTerminalView(mTerminalView);
        configureAgentFleetSessionResume();
        updateAgentFleetInputMode(getIntent());

        registerForContextMenu(mTerminalView);

        // Start the {@link TermuxService} and make it run regardless of who is bound to it
        Intent serviceIntent = new Intent(this, TermuxService.class);
        startService(serviceIntent);

        // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
        // callback if it succeeds.
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();

        if (mAgentFleetNativeSession != null)
            mAgentFleetNativeSession.onStart();

        if (mAgentFleetTerminalScrollback != null)
            mAgentFleetTerminalScrollback.onStart();

        if (mUnifiedDrawerController != null)
            mUnifiedDrawerController.onStart();

        restoreAgentFleetSessionIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (mAgentFleetSessionResume != null) mAgentFleetSessionResume.onBackground();
        mShouldRestoreAgentFleetSession = false;
        setIntent(intent);
        updateAgentFleetInputMode(intent);
        selectAgentFleetTarget(intent, 0);
    }

    private void updateAgentFleetInputMode(Intent intent) {
        ComposeView composer = findViewById(R.id.agent_fleet_composer);
        AgentFleetComposer.bind(this, composer,
            intent != null && intent.getBooleanExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT, false));
        if (mAgentFleetTerminalScrollback != null)
            mAgentFleetTerminalScrollback.bind(intent);
        if (mAgentFleetNativeSession != null)
            mAgentFleetNativeSession.bind(intent);
    }

    public boolean sendAgentFleetComposerText(String text) {
        return sendAgentFleetComposerText(text, true);
    }

    @NonNull
    @Override
    public Context getNativeContext() {
        return this;
    }

    @Override
    public boolean getNativeInlineComposer() {
        return false;
    }

    public boolean sendAgentFleetComposerText(String text, boolean appendEnter) {
        if (text == null || text.length() > 32768 || text.indexOf('\0') >= 0 || (!appendEnter && text.isEmpty()))
            return false;
        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) return false;
        if (!text.isEmpty()) session.getEmulator().paste(text);
        if (appendEnter) session.write("\r");
        return true;
    }

    public boolean sendAgentFleetControlC() {
        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) return false;
        session.write("\u0003");
        return true;
    }

    public boolean isAgentFleetManagedSession() {
        return mAgentFleetNativeSession != null && mAgentFleetNativeSession.isManagedSession();
    }

    /** Close only this local terminal tab. A remote tmux session remains alive. */
    public void closeAgentFleetSessionTab() {
        TerminalSession session = getCurrentSession();
        TermuxService service = getTermuxService();
        if (session == null || service == null) return;
        String sessionId = getIntent() == null ? null :
            getIntent().getStringExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID);
        if (sessionId != null) {
            clearManagedSessionTarget();
            service.finishAgentFleetWorkspaceSession(sessionId);
        }
        else session.finishIfRunning();
    }

    /** Switch this activity to an existing service-owned workspace attachment. */
    public void activateAgentFleetSession(FleetSession session, DrawerSessionSurface surface) {
        if (session == null || surface == null || mTermuxService == null) return;
        Intent target = new Intent(this, TermuxActivity.class);
        target.putExtra(AgentFleetContract.EXTRA_COMPOSE_INPUT,
            session.getTool().equals("codex") || session.getTool().equals("claude") || session.getTool().equals("copilot"));
        target.putExtra(AgentFleetContract.EXTRA_NATIVE_SESSION, true);
        target.putExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID, session.getId());
        target.putExtra(AgentFleetContract.EXTRA_HOST_ID, session.getHostId());
        target.putExtra(AgentFleetContract.EXTRA_PROJECT, session.getProject());
        target.putExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION, session.getInternalName());
        target.putExtra(AgentFleetContract.EXTRA_SESSION_NAME, session.getName());
        target.putExtra(AgentFleetContract.EXTRA_INITIAL_SURFACE,
            surface == DrawerSessionSurface.Terminal ? AgentFleetContract.SURFACE_TERMINAL : AgentFleetContract.SURFACE_NATIVE);
        setIntent(target);
        updateAgentFleetInputMode(target);
        selectAgentFleetTarget(target, 0);
        getDrawer().closeDrawers();
    }

    /** Leave Agent Fleet presentation state before selecting a classic local shell. */
    public void activateClassicSession(TerminalSession session) {
        if (session == null || mTermuxTerminalSessionClient == null) return;
        Intent target = new Intent(this, TermuxActivity.class);
        setIntent(target);
        updateAgentFleetInputMode(target);
        mTermuxTerminalSessionClient.setCurrentSession(session);
        getDrawer().closeDrawers();
    }

    private void selectAgentFleetTarget(Intent intent, int attempt) {
        if (intent == null || mTermuxActivityRootView == null) return;
        String sessionId = intent.getStringExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID);
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._: -]{1,180}")) return;
        if (mTermuxService != null) {
            TermuxSession target = mTermuxService.getAgentFleetWorkspaceSession(sessionId);
            if (target != null && mTermuxService.selectAgentFleetWorkspaceSession(sessionId)) {
                mTermuxTerminalSessionClient.setCurrentSession(target.getTerminalSession());
                if (mIsVisible && mAgentFleetSessionResume != null)
                    mAgentFleetSessionResume.onForeground(sessionId);
                return;
            }
        }
        if (attempt >= 50) return;
        mTermuxActivityRootView.postDelayed(() -> {
            Intent current = getIntent();
            if (current != null && sessionId.equals(current.getStringExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID)))
                selectAgentFleetTarget(current, attempt + 1);
        }, attempt == 0 ? 40 : 80);
    }

    public void showAgentFleetPendingQuestion() {
        if (mAgentFleetNativeSession != null)
            mAgentFleetNativeSession.showPendingQuestion();
    }

    public boolean sendAgentFleetKey(String key) {
        TerminalSession session = getCurrentSession();
        if (session == null || !session.isRunning()) return false;
        String sequence = agentFleetKeySequence(key);
        if (sequence == null) return false;
        session.write(sequence);
        return true;
    }

    static String agentFleetKeySequence(String key) {
        if ("TAB".equals(key)) return "\t";
        if ("SHIFT_TAB".equals(key)) return "\033[Z";
        if ("UP".equals(key)) return "\033[A";
        if ("DOWN".equals(key)) return "\033[B";
        return null;
    }

    public void setAgentFleetNativeView(boolean nativeAvailable, boolean nativeView, boolean automaticTerminal, boolean aiComposer) {
        boolean terminalWasHidden = mTerminalView != null && mTerminalView.getAlpha() == 0f;
        if (mTerminalView != null) {
            mTerminalView.setAlpha(nativeView ? 0f : 1f);
            mTerminalView.setEnabled(!nativeView);
            mTerminalView.setImportantForAccessibility(nativeView
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (terminalWasHidden && !nativeView) mTerminalView.onScreenUpdated();
        }

        View composer = findViewById(R.id.agent_fleet_composer);
        if (composer != null)
            composer.setVisibility(aiComposer && !automaticTerminal ? View.VISIBLE : View.GONE);

        View toolbar = getTerminalToolbarViewPager();
        if (toolbar != null)
            toolbar.setVisibility(!nativeView && mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);

        View returnButton = findViewById(R.id.agent_fleet_native_return);
        if (returnButton != null)
            returnButton.setVisibility(View.GONE);

        View terminalChrome = findViewById(R.id.agent_fleet_terminal_chrome);
        if (terminalChrome != null) {
            terminalChrome.setVisibility(nativeAvailable && !nativeView ? View.VISIBLE : View.GONE);
            terminalChrome.post(() -> {
                if (mTerminalView == null) return;
                int top = terminalChrome.getVisibility() == View.VISIBLE
                    ? getResources().getDimensionPixelSize(R.dimen.agent_fleet_terminal_chrome_height) : 0;
                if (mTerminalView.getPaddingTop() != top) {
                    mTerminalView.setPadding(mTerminalView.getPaddingLeft(), top,
                        mTerminalView.getPaddingRight(), mTerminalView.getPaddingBottom());
                    mTerminalView.updateSize();
                    if (!nativeView) mTerminalView.onScreenUpdated();
                }
            });
        }

        if (mAgentFleetTerminalScrollback != null)
            mAgentFleetTerminalScrollback.setTerminalVisible(!nativeView);

        if (mTerminalView != null) mTerminalView.post(() -> {
            mTerminalView.updateSize();
            if (!nativeView) mTerminalView.onScreenUpdated();
        });
    }

    /** Reconnect an unexpectedly failed visible fleet transport without reviving an explicitly closed tab. */
    public void onAgentFleetManagedSessionFinished(TerminalSession finishedSession) {
        if (!mIsVisible || finishedSession == null || finishedSession != getCurrentSession()) return;
        int exitStatus = finishedSession.getExitStatus();
        if (exitStatus == 0 || exitStatus == 130) return;
        String sessionId = managedSessionId(getIntent());
        if (sessionId != null && mAgentFleetSessionResume != null)
            mAgentFleetSessionResume.onAttachmentEnded(sessionId);
    }

    public void onAgentFleetTerminalScreenChanged(TerminalSession changedSession) {
        if (changedSession == null || changedSession != getCurrentSession() || changedSession.getEmulator() == null) return;
        boolean alternate = changedSession.getEmulator().isAlternateBufferActive();
        if (mAgentFleetNativeSession != null)
            mAgentFleetNativeSession.onTerminalScreenChanged(alternate);
        if (mAgentFleetTerminalScrollback != null)
            mAgentFleetTerminalScrollback.onTerminalScreenChanged(alternate);
    }

    public void onAgentFleetTerminalTextChanged(TerminalSession changedSession) {
        if (mAgentFleetTerminalScrollback != null && changedSession != null && changedSession == getCurrentSession())
            mAgentFleetTerminalScrollback.onTerminalActivity();
        if (mAgentFleetNativeSession == null || changedSession == null ||
            !mAgentFleetNativeSession.wantsLocalTerminalText() ||
            changedSession != getCurrentSession() || changedSession.getEmulator() == null) return;
        String transcript = changedSession.getEmulator().getScreen().getTranscriptTextWithoutJoinedLines();
        if (transcript.length() > 131072) transcript = transcript.substring(transcript.length() - 131072);
        mAgentFleetNativeSession.onLocalTerminalTextChanged(transcript);
    }

    public void onAgentFleetWorkingDirectoryChanged(TerminalSession changedSession, String path) {
        if (mAgentFleetNativeSession != null && changedSession == getCurrentSession())
            mAgentFleetNativeSession.onWorkingDirectoryChanged(path);
    }

    public void onAgentFleetShellIntegrationEvent(TerminalSession changedSession, String marker, String data) {
        if (mAgentFleetNativeSession != null && changedSession == getCurrentSession())
            mAgentFleetNativeSession.onShellIntegrationEvent(marker, data);
    }

    public void pickAgentFleetImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_AGENT_FLEET_IMAGES);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No image picker is available", Toast.LENGTH_LONG).show();
        }
    }

    public void pickAgentFleetCamera() {
        File directory = new File(getCacheDir(), "agent-fleet-camera");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "Camera staging is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        File[] staleFiles = directory.listFiles();
        if (staleFiles != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (File file : staleFiles) if (file.lastModified() < cutoff) file.delete();
        }
        File output = new File(directory, UUID.randomUUID() + ".jpg");
        mAgentFleetCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".agentfleet.images", output);
        Intent intent = new Intent("android.media.action.IMAGE_CAPTURE");
        intent.putExtra("output", mAgentFleetCameraUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_AGENT_FLEET_CAMERA);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No camera is available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AGENT_FLEET_IMAGES && resultCode == RESULT_OK && data != null)
            AgentFleetComposer.handleImageResult(this, data);
        if (requestCode == REQUEST_AGENT_FLEET_CAMERA && resultCode == RESULT_OK && mAgentFleetCameraUri != null)
            AgentFleetComposer.handleCapturedImage(this, mAgentFleetCameraUri);
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        isOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        mShouldRestoreAgentFleetSession = managedSessionId(getIntent()) != null;
        if (mAgentFleetSessionResume != null) mAgentFleetSessionResume.onBackground();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiever();
        getDrawer().closeDrawers();

        if (mAgentFleetNativeSession != null)
            mAgentFleetNativeSession.onStop();

        if (mAgentFleetTerminalScrollback != null)
            mAgentFleetTerminalScrollback.onStop();

        if (mUnifiedDrawerController != null)
            mUnifiedDrawerController.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mAgentFleetNativeSession != null) {
            mAgentFleetNativeSession.close();
            mAgentFleetNativeSession = null;
        }

        if (mAgentFleetTerminalScrollback != null) {
            mAgentFleetTerminalScrollback.close();
            mAgentFleetTerminalScrollback = null;
        }

        if (mAgentFleetSessionResume != null) {
            mAgentFleetSessionResume.close();
            mAgentFleetSessionResume = null;
        }
        if (mAgentFleetResumeRuntime != null) {
            mAgentFleetResumeRuntime.shutdown();
            mAgentFleetResumeRuntime = null;
        }

        if (mUnifiedDrawerController != null) {
            mUnifiedDrawerController.close();
            mUnifiedDrawerController = null;
        }

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {

        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        mTermuxService.reconcileAgentFleetWorkspaceSessions();

        if (mUnifiedDrawerController != null)
            mUnifiedDrawerController.attachService(mTermuxService);

        if (mTermuxService.isTermuxSessionsEmpty() && !mShouldRestoreAgentFleetSession) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        Bundle bundle = getIntent().getExtras();
                        boolean launchFailsafe = false;
                        if (bundle != null) {
                            launchFailsafe = bundle.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            Intent i = getIntent();
            if (i != null && Intent.ACTION_RUN.equals(i.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = i.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionClient.setCurrentSession(mTermuxTerminalSessionClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionClient);
        selectAgentFleetTarget(getIntent(), 0);
        restoreAgentFleetSessionIfNeeded();
    }

    private void configureAgentFleetSessionResume() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final DrawerSessionStore sessions = new DrawerSessionStore(getApplicationContext());
        mAgentFleetResumeRuntime = new FleetRuntime(getApplicationContext());
        mAgentFleetSessionResume = new AgentFleetSessionResumeController(
            sessions::sessionFor,
            new AgentFleetSessionResumeController.AttachmentHost() {
                @Override
                public boolean hasRunningAttachment(String sessionId) {
                    return mTermuxService != null && mTermuxService.getAgentFleetWorkspaceSession(sessionId) != null;
                }

                @Override
                public void startAttachment(FleetSession session) {
                    mAgentFleetResumeRuntime.startWorkspaceSession(session);
                }

                @Override
                public boolean selectAttachment(String sessionId) {
                    return mTermuxService != null && mTermuxService.selectAgentFleetWorkspaceSession(sessionId);
                }
            },
            new AgentFleetSessionResumeController.Scheduler() {
                @Override
                public void postDelayed(Runnable runnable, long delayMillis) {
                    handler.postDelayed(runnable, delayMillis);
                }

                @Override
                public void cancelAll() {
                    handler.removeCallbacksAndMessages(null);
                }
            },
            new AgentFleetSessionResumeController.Listener() {
                @Override
                public void onSelected(String sessionId) {
                    Intent current = getIntent();
                    if (!sessionId.equals(managedSessionId(current))) return;
                    mShouldRestoreAgentFleetSession = false;
                    selectAgentFleetTarget(current, 0);
                    if (mTerminalView != null) mTerminalView.post(() -> {
                        mTerminalView.updateSize();
                        mTerminalView.onScreenUpdated();
                    });
                }

                @Override
                public void onError(String message) {
                    if (mIsVisible) showToast(message, true);
                }
            }
        );
    }

    private void restoreAgentFleetSessionIfNeeded() {
        if (!mIsVisible || !mShouldRestoreAgentFleetSession || mTermuxService == null || mAgentFleetSessionResume == null)
            return;
        mAgentFleetSessionResume.onForeground(managedSessionId(getIntent()));
    }

    @Nullable
    private static String managedSessionId(@Nullable Intent intent) {
        if (intent == null) return null;
        String value = intent.getStringExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID);
        return value != null && value.matches("[A-Za-z0-9._: -]{1,180}") ? value : null;
    }

    private void clearManagedSessionTarget() {
        Intent current = getIntent();
        if (current != null) {
            current.removeExtra(AgentFleetContract.EXTRA_WORKSPACE_SESSION_ID);
            current.removeExtra(AgentFleetContract.EXTRA_HOST_ID);
            current.removeExtra(AgentFleetContract.EXTRA_PROJECT);
            current.removeExtra(AgentFleetContract.EXTRA_INTERNAL_SESSION);
            current.removeExtra(AgentFleetContract.EXTRA_SESSION_NAME);
        }
        mShouldRestoreAgentFleetSession = false;
        if (mAgentFleetSessionResume != null) mAgentFleetSessionResume.onBackground();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }





    private void setActivityTheme() {
        if (mProperties.isUsingBlackUI()) {
            this.setTheme(R.style.Theme_Termux_Black);
        } else {
            this.setTheme(R.style.Theme_Termux);
        }
    }

    private void configureUnifiedDrawer() {
        View drawer = findViewById(R.id.left_drawer);
        if (drawer == null) return;
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int desired = Math.round(screenWidth * 0.88f);
        int maximum = Math.round(380f * density);
        ViewGroup.LayoutParams params = drawer.getLayoutParams();
        params.width = Math.min(desired, maximum);
        drawer.setLayoutParams(params);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionClient = new TermuxTerminalSessionClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onCreate();
    }

    private void setTerminalToolbarView(Bundle savedInstanceState) {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        int heightDp = AgentFleetDisplayDensityStore.INSTANCE.load(this).getTerminalShortcutHeightDp();
        layoutParams.height = Math.round(heightDp * getResources().getDisplayMetrics().density);
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView =  findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }




    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            super.onBackPressed();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install, (dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)))).setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    public boolean ensureStoragePermissionGranted() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission not granted, requesting permission.");
            PermissionUtils.requestPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.");
            TermuxInstaller.setupStorageSymlinks(this);
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.");
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        if (mUnifiedDrawerController != null)
            mUnifiedDrawerController.notifySessionsChanged();
    }

    public int getClassicTermuxSessionIndex(TerminalSession session) {
        return mTermuxService == null ? -1 : mTermuxService.getClassicTermuxSessionIndex(session);
    }

    public void scrollDrawerToSession(TerminalSession session) {
        if (mUnifiedDrawerController != null)
            mUnifiedDrawerController.scrollToSession();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return isOnResumeAfterOnCreate;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionClient;
    }

    public boolean isAgentFleetNativeViewVisible() {
        return mAgentFleetNativeSession != null && mAgentFleetNativeSession.isNativeViewVisible();
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiever() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceieverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceieverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        if (ensureStoragePermissionGranted())
                            TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling();
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling() {
        if (mProperties!= null) {
            mProperties.loadTermuxPropertiesFromDisk();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mProperties.getExtraKeysInfo());
            }
        }

        setMargins();
        setTerminalToolbarHeight();

        ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager != null)
            terminalToolbarViewPager.setVisibility(mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);

        if (mTermuxTerminalSessionClient != null)
            mTermuxTerminalSessionClient.onReload();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReload();

        if (mTermuxService != null)
            mTermuxService.setTerminalTranscriptRows();

        // To change the activity and drawer theme, activity needs to be recreated.
        // But this will destroy the activity, and will call the onCreate() again.
        // We need to investigate if enabling this is wise, since all stored variables and
        // views will be destroyed and bindService() will be called again. Extra keys input
        // text will we restored since that has already been implemented. Terminal sessions
        // and transcripts are also already preserved. Theme does change properly too.
        // TermuxActivity.this.recreate();
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        context.startActivity(newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}

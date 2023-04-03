package activate.privacy.realtime;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import activate.privacy.Constants;
import activate.privacy.MainActivity;
import activate.privacy.PSApplication;
import activate.privacy.R;
import activate.privacy.devicemanager.ScreenReceiver;
import activate.privacy.scanner.ScanningService;
import activate.privacy.utilities.BlockingUtilities;
import activate.privacy.utilities.CameraBlockingUtilities;

public class AppInstalledService extends Service {

    public static boolean serviceRunning;

    public static final int ON = 1;
    public static final int OFF = 0;

    public static volatile int SCREEN_STATUS;
    public static volatile String CURRENT_APP_5S;
    public static volatile String CURRENT_APP = "";
    public static volatile String LAST_APP = "";

    private AppInstalledReceiver receiver;
    private FileContentObserver fileSystemContentObserver;
    private PathFileObserver fileObserver;
    private BroadcastReceiver screenReceiver;
    //    private Timer timer;
    private static Handler handlerCheckBlock;
    private static Runnable runnableCheckBlock;
    private static Handler handlerCurrentApp;
    private static Runnable runnableCurrentApp;

    private static final int CHECK_BLOCK_IN_MILIS = 5000;
    private static final int CHECK_CURRENT_APP_IN_MILIS = 150;

    private static final int INITIAL_CALL_STATE = -100;
    private int lastCallState;
    private PhoneStateListener phoneStateListener;
    private static AudioManager audioManager;
    private static int lastMode;
    private static final int INITIAL_VALUE_LAST_MODE = -100;
    private static boolean checkModeEnabled = false;

    private static ActivityManager activityManager;

    public AppInstalledService() {
    }

    private static final String TAG = AppInstalledService.class.getSimpleName();

    @Override
    public void onCreate() {
        SharedPreferences prefs = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(Constants.APP_STATE.ACTIVE_PROTECTION, Constants.DEFAULTS.AP_ENABLED);
        Log.e(TAG, "onCreate: called");
        if (!Constants.ADS.ADS_ENABLED && (!PSApplication.isAppActive() || !enabled)) {
            stopSelf();
            return;
        }
        Intent nextIntent = new Intent(this, MainActivity.class);
        nextIntent.setAction(Intent.ACTION_MAIN);
        nextIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pNextIntent = PendingIntent.getActivity(this, 0,
                nextIntent, PendingIntent.FLAG_IMMUTABLE);

        // Android O requires a Notification Channel.
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel mChannel = new NotificationChannel(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE + "", name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setSound(null, null);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            mChannel.setShowBadge(false);
            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(mChannel);
        }

        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_ID.FOREGROUND_SERVICE + "")
                .setContentTitle(getString(R.string.app_name_short))
                .setTicker(getString(R.string.app_name_short))
                .setContentText(getString(R.string.shield_av_rt_service))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pNextIntent)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE, notification);

        IntentFilter screenfilter = new IntentFilter();
        screenfilter.addAction(Intent.ACTION_SCREEN_ON);
        screenfilter.addAction(Intent.ACTION_SCREEN_OFF);

        screenReceiver = new ScreenReceiver();
        registerReceiver(screenReceiver, screenfilter);

        serviceRunning = true;
    }

    public static void startExecutors() {
        audioManager = (AudioManager) PSApplication.getContext().getSystemService(AUDIO_SERVICE);
        activityManager = (ActivityManager) PSApplication.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        final Lock l = new ReentrantLock();
        handlerCurrentApp = new Handler();
        runnableCurrentApp = new Runnable() {
            @Override
            public void run() {
                l.lock();
                try {
                    Log.e(TAG, "run: From handlerCurrentApp at " + CHECK_CURRENT_APP_IN_MILIS + "ms");
                    CURRENT_APP = BlockingUtilities.getRunningPackage(PSApplication.getContext());
                    Log.e(TAG, "run: From handlerCurrentApp | current app is : " + CURRENT_APP);
                    Log.e(TAG, "run: From handlerCurrentApp | last app is : " + LAST_APP);
                    boolean currentAppInWhiteList = BlockingUtilities.inWhiteList(CURRENT_APP, false);
                    if (!CURRENT_APP.equalsIgnoreCase(LAST_APP) || currentAppInWhiteList) {
                        if (!CURRENT_APP.equalsIgnoreCase(LAST_APP)) {
                            BlockingUtilities.setCameraAndMicBlocker();
                        }
                        if (!CURRENT_APP.equalsIgnoreCase("")) {
                            LAST_APP = CURRENT_APP;
                        }
                        Log.e(TAG, "run: " + LAST_APP + " in white list : " + currentAppInWhiteList);
                        if (currentAppInWhiteList && BlockingUtilities.BLOCKING_ON) {
                            if (!BlockingUtilities.camBlockRunning) {
                                BlockingUtilities.setCameraAndMicBlocker();
                            } else {
                                Log.e(TAG, "run: killing : " + LAST_APP);
                                activityManager.killBackgroundProcesses(LAST_APP);
                                Log.e(TAG, "run: killed : " + LAST_APP);

                                BlockingUtilities.setCameraAndMicBlockerAndRestartPackage(LAST_APP);
                            }
                        }
                    }

                    if (checkModeEnabled) {
                        checkMode();
                    }

                } finally {
                    l.unlock();
                }
                handlerCurrentApp.postDelayed(runnableCurrentApp, CHECK_CURRENT_APP_IN_MILIS);
            }
        };
        handlerCurrentApp.postDelayed(runnableCurrentApp, 0);

        handlerCheckBlock = new Handler();
        runnableCheckBlock = new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "run: From handlerCheckBlock at " + CHECK_BLOCK_IN_MILIS + "ms");
                CURRENT_APP_5S = BlockingUtilities.getRunningPackage(PSApplication.getContext());
//                Log.e(TAG, "Current application : " + CURRENT_APP_5S);
                if (!BlockingUtilities.inWhiteList(CURRENT_APP_5S, true)) {
                    BlockingUtilities.setCameraAndMicBlocker();
                }
                handlerCheckBlock.postDelayed(runnableCheckBlock, CHECK_BLOCK_IN_MILIS);
            }
        };
        handlerCheckBlock.postDelayed(runnableCheckBlock, 0);
    }

    public static void stopExecutors() {
        Log.e(TAG,"stopExecutros to release camera");
        if (handlerCheckBlock != null) {
            handlerCheckBlock.removeCallbacks(runnableCheckBlock);
        }
        if (handlerCurrentApp != null) {
            handlerCurrentApp.removeCallbacks(runnableCurrentApp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (CameraBlockingUtilities.getInstance() != null) {
                CameraBlockingUtilities.getInstance().stopCamera();
            }
        }

    }

    private void startRealtime() {
        Log.e(TAG,"startRealtime()");
        receiver = new AppInstalledReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_INSTALL");
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addDataScheme("package");
        registerReceiver(receiver, filter);

        Handler handler = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Log.e(TAG, "handleMessage: " + msg.toString());
                Intent intent = new Intent(AppInstalledService.this, ScanningService.class);
                intent.putExtra(ScanningService.SCAN_TYPE, ScanningService.REAL_TIME_SCAN);
                intent.putExtra(ScanningService.SCAN_DATA, "Scan MediaStore");
                intent.putExtra(ScanningService.SCAN_DATA_TYPE, ScanningService.NEW_FILE);
                startService(intent);
            }
        };

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {

            fileSystemContentObserver = new FileContentObserver(handler);
            getContentResolver().registerContentObserver(
                    MediaStore.Files.getContentUri("external"),
                    true,
                    fileSystemContentObserver
            );
        } else {
            fileObserver = new PathFileObserver(Environment.getExternalStorageDirectory().getAbsolutePath(), handler);
            fileObserver.startWatching();
        }
    }

    public void stopRealtime() {
        Log.e(TAG,"stopRealtime()");
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (fileSystemContentObserver != null)
                getContentResolver().unregisterContentObserver(fileSystemContentObserver);
        } else {
            if (fileObserver != null)
                fileObserver.stopWatching();
        }
    }

    private void startCallListening() {
        Log.e(TAG,"startCallListener");
        lastCallState = INITIAL_CALL_STATE;
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                super.onCallStateChanged(state, phoneNumber);
                if (lastCallState == INITIAL_CALL_STATE) {
                    lastCallState = state;
                    return;
                }
                if (lastCallState != state) {
                    Log.e(TAG,"lastCallState");
                    SharedPreferences prefs = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
                    boolean micBlockerEnabled = prefs.getBoolean(Constants.APP_STATE.MIC_BLOCK_ENABLED, false);
                    if (lastCallState == 2 && state == 0 && micBlockerEnabled) {
                        BlockingUtilities.onCallEnded();
                    } else if (state == 2 && micBlockerEnabled) {
                        BlockingUtilities.onCallStarted();
                    }
                    lastCallState = state;
                }
            }
        };
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
            Log.e(TAG,"startCallListener Build.VERSION.SDK_INT >= Build.VERSION_CODES.S");
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
                callStateListenerRegistered = true;
            }
        }else{
            Log.e(TAG,"startCallListener Build.VERSION.SDK_INT < Build.VERSION_CODES.S");
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }

    }
    private void registerCallStateListener() {
        if (!callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
                    callStateListenerRegistered = true;
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                callStateListenerRegistered = true;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static abstract class CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        abstract public void onCallStateChanged(int state);
    }

    private boolean callStateListenerRegistered = false;

    private CallStateListener callStateListener = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
            new CallStateListener() {
                @Override
                public void onCallStateChanged(int state) {
                    // Handle call state change
                    Log.i(TAG,"Mikrofon blokiran");
                }
            }
            : null;


    private void stopCallListening() {
        if (phoneStateListener != null) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    private static void checkMode() {
        if (audioManager == null) {
            return;
        }
        int mode = audioManager.getMode();
        if (lastMode != mode && lastMode != INITIAL_VALUE_LAST_MODE) {
            onAudioModeChanged(mode);
        }
        lastMode = mode;
    }

    private static void onAudioModeChanged(int mode) {
        if (mode == AudioManager.MODE_NORMAL) {
            BlockingUtilities.onCallEnded();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean realtimeEnabled = prefs.getBoolean(Constants.APP_STATE.AV_REALTIME_ENABLED, Constants.DEFAULTS.AV_REALTIME_ENABLED_DEFAULT);
        boolean camBlockerEnabled = prefs.getBoolean(Constants.APP_STATE.CAM_BLOCK_ENABLED, false);
        boolean micBlockerEnabled = prefs.getBoolean(Constants.APP_STATE.MIC_BLOCK_ENABLED, false);
        if (realtimeEnabled) {
            startRealtime();
        } else {
            stopRealtime();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q){
            if (camBlockerEnabled || micBlockerEnabled) {
                Log.e(TAG,"verzija ispod 30, izvrsava se kod");
                startExecutors();
            } else {
                stopExecutors();
            }
        }

        if (camBlockerEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.e(TAG,"Build.VERSION_CODES.Q je verzija, izvrsava se kod");
            CameraBlockingUtilities.createInstance().startCamera();
        }
        if (micBlockerEnabled) {
            startCallListening();
            checkModeEnabled = true;
        } else {
            stopCallListening();
            checkModeEnabled = false;
        }
        String message = "";
        String localeString = prefs.getString(Constants.APP_STATE.SAVED_LOCALE, Constants.DEFAULTS.DEFAULT_LOCALE);
        Locale locale = new Locale(localeString);

        if (realtimeEnabled && !camBlockerEnabled && !micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.shield_av_rt_service));
        } else if (camBlockerEnabled && !realtimeEnabled && !micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.camera_blocker_enabled));
        } else if (!camBlockerEnabled && !realtimeEnabled && micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.microphone_blocker_enabled));
        } else if (camBlockerEnabled && !realtimeEnabled && micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.Camera_Microphone_Blockers_are_Enabled));
        } else if (camBlockerEnabled && realtimeEnabled && !micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.Camera_Blocker_Real_Time_Protection_are_Enabled));
        } else if (!camBlockerEnabled && realtimeEnabled && micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.Microphone_Blocker_Real_Time_Protection_are_Enabled));
        } else if (camBlockerEnabled && realtimeEnabled && micBlockerEnabled) {
            message = (getLocaleStringResource(locale, R.string.Camera_Microphone_Blockers_Real_Time_Protection_are_Enabled));
        } else if (!camBlockerEnabled && !realtimeEnabled && !micBlockerEnabled) {
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        Intent nextIntent = new Intent(this, MainActivity.class);
        nextIntent.setAction(Intent.ACTION_MAIN);
        nextIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pNextIntent = PendingIntent.getActivity(this, 0,
                nextIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel mChannel = new NotificationChannel(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE + "", name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setSound(null, null);
            mChannel.enableLights(false);
            mChannel.enableVibration(false);
            mChannel.setShowBadge(false);
            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(mChannel);
        }

        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_ID.FOREGROUND_SERVICE + "")
                .setContentTitle(getString(R.string.app_name_short))
                .setTicker(getString(R.string.app_name_short))
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pNextIntent)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIFICATION_ID.FOREGROUND_SERVICE, notification);
        return Service.START_NOT_STICKY;
    }

    public static String getLocaleStringResource(Locale requestedLocale, int resourceId) {
        String result;
        Context context = PSApplication.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) { // use latest api
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(requestedLocale);
            result = context.createConfigurationContext(config).getText(resourceId).toString();
        } else { // support older android versions
            Resources resources = context.getResources();
            Configuration conf = resources.getConfiguration();
            Locale savedLocale = conf.locale;
            conf.locale = requestedLocale;
            resources.updateConfiguration(conf, null);

            // retrieve resources from desired locale
            result = resources.getString(resourceId);

            // restore original locale
            conf.locale = savedLocale;
            resources.updateConfiguration(conf, null);
        }

        return result;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onDestroy() {

        SharedPreferences prefs = PSApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(Constants.APP_STATE.ACTIVE_PROTECTION, Constants.DEFAULTS.AP_ENABLED);

        if (!enabled) {
            if (screenReceiver != null)
                unregisterReceiver(screenReceiver);
            if (receiver != null)
                unregisterReceiver(receiver);
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (fileSystemContentObserver != null)
                    getContentResolver().unregisterContentObserver(fileSystemContentObserver);
            } else {
                if (fileObserver != null)
                    fileObserver.stopWatching();
            }

            stopExecutors();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CameraBlockingUtilities.getInstance() != null) {
                CameraBlockingUtilities.getInstance().destroy();
            }

            stopCallListening();

            serviceRunning = false;
        } else {
            //// dodato 17.03. ako uzrokuje problem skloniti!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, AppInstalledService.class));
            }else{
                startService(new Intent(this, AppInstalledService.class));
            }
        }
    }

}

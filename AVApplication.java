package mobileshield.antivirus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import androidx.multidex.MultiDexApplication;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;
import mobileshield.antivirus.alarm.TrialExpiredAlarmReceiver;
import mobileshield.antivirus.model.ActiveOption;
import mobileshield.antivirus.utilities.AESUtilities;
import mobileshield.antivirus.utilities.Utilities;
import mobileshield.avengine.Engine;
import mobileshield.avengine.IEngine;

public class AVApplication extends MultiDexApplication {

    private static final String TAG = AVApplication.class.getSimpleName();

    static private Context appContext;
    static private Handler applicationHandler;
    public static ArrayList<BaseFragment> fragmentsStack = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        appContext = getApplicationContext();
        applicationHandler = new Handler(appContext.getMainLooper());

//        MobileAds.initialize(appContext, new OnInitializationCompleteListener() {
//            @Override
//            public void onInitializationComplete(@NonNull InitializationStatus initializationStatus) {
//
//            }
//        });

        ViewPump.init(ViewPump.builder().addInterceptor(new CalligraphyInterceptor(
                new CalligraphyConfig.Builder()
                        .setDefaultFontPath("fonts/Swis.ttf")
                        .setFontAttrId(R.attr.fontPath)
                        .build()))
                .build());

        Utilities.uiLooperThread.postRunnable(() -> {
            IEngine engine = Engine.getInstance();
            engine.setLabelID(Constants.DEFAULTS.LABEL_ID);
            engine.initialize(getContext());
        });

    }

    public static Context getContext() {
        return appContext;
    }

    public static Handler getApplicationHandler() {
        return applicationHandler;
    }


    public static BaseFragment getCurrentFragment() {
        if (fragmentsStack.size() > 0) {
            return fragmentsStack.get(AVApplication.fragmentsStack.size() - 1);
        }
        return null;
    }

    public static String getUnzipDirectory() {
        return appContext.getCacheDir().getAbsolutePath() + "/unzipapkdir";
    }


    public static String getDbDirectory() {
//        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        return appContext.getFilesDir().getAbsolutePath();
    }

    public static String getHiddenDir() {
        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/." + appContext.getPackageName();
    }


    public static String getAndroidIDFile() {
        return getHiddenDir() + "/id.txt";
    }

    public static void saveAndroidID(String androidID) {
        File hiddenDir = new File(getHiddenDir());
        hiddenDir.mkdirs();
        try {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(new File(getAndroidIDFile())));
            outputStreamWriter.write(AESUtilities.encrypt(androidID));
            outputStreamWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveAviraKey() {

        String fileName = "HBEDV.KEY";
        File folder = new File(Environment.getExternalStorageDirectory(), ".mobileshield.antivirus");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File file = new File(folder, fileName);

        try {
            URL downloadUrl = new URL("https://sa.xverify.info/avrk/android/9000/HBEDV.KEY");
            HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            // Proverite da li je zahtev uspešan
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                // obrada greške
                return;
            }

            // Preuzimanje fajla
            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            output.close();
            input.close();
        } catch (IOException e) {
            // obrada izuzetka
            e.printStackTrace();
        }
    }

    public static String getAndroidID() {
        String androidID = "";
        File idFile = new File(getAndroidIDFile());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && idFile.exists()) {
            Log.e(TAG, "getAndroidID: reading from file");
            try {
                InputStream inputStream;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    androidID = Settings.Secure.getString(AVApplication.getContext().getContentResolver(), Settings.Secure.ANDROID_ID);
                    return androidID;
                } else {
                    androidID = Settings.Secure.getString(AVApplication.getContext().getContentResolver(), Settings.Secure.ANDROID_ID);
                    return androidID;
                }
//                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
//                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
//                String receiveString = "";
//                StringBuilder stringBuilder = new StringBuilder();
//                while ((receiveString = bufferedReader.readLine()) != null) {
//                    stringBuilder.append(receiveString);
//                }
//                inputStream.close();
//                androidID = stringBuilder.toString();
            } catch (Exception e) {
                e.printStackTrace();
            }
            String ret = null;
            try {
                ret = AESUtilities.decrypt(androidID);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return ret;
        } else {
            Log.e(TAG, "getAndroidID: reading from settings");
            androidID = Settings.Secure.getString(AVApplication.getContext().getContentResolver(), Settings.Secure.ANDROID_ID);
            return androidID;
        }
    }

    public static String getKeyFile() {
        return getHiddenDir() + "/key.txt";
    }

    public static void saveKey(String key) {
//        File hiddenDir = new File(getHiddenDir());
//        hiddenDir.mkdirs();
        try {
            SharedPreferences prefs = AVApplication.getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(Constants.APP_STATE.ACTIVE_CODE, key);
            editor.apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getKey() {
        String key = "";
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        key = prefs.getString(Constants.APP_STATE.ACTIVE_CODE, "");
        if (!key.isEmpty()) {
            return key;
        }
//        try {
//            InputStream inputStream = new FileInputStream(getKeyFile());
//            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
//            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
//            String receiveString = "";
//            StringBuilder stringBuilder = new StringBuilder();
//            while ((receiveString = bufferedReader.readLine()) != null) {
//                stringBuilder.append(receiveString);
//            }
//            inputStream.close();
//            key = stringBuilder.toString();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
        String ret = null;
        try {
            ret = AESUtilities.decrypt(key);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ret;
    }

    public static boolean isAppActive() {
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        int option = prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, ActiveOption.ACTIVATION_OPTION_NONE);
        if (option == ActiveOption.ACTIVATION_OPTION_NONE) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean isAppPro() {
        Log.e("MenuProvider", "isAppPro: ");
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        int option = prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, ActiveOption.ACTIVATION_OPTION_NONE);
        if (option == ActiveOption.ACTIVATION_OPTION_NONE || option == ActiveOption.ACTIVATION_OPTION_TRIAL) {
            Log.e("MenuProvider", "isAppPro: false");
            Log.e("MenuProvider", "isAppPro: false");
            return false;
        } else {
            Log.e("MenuProvider", "isAppPro: true");
            return true;
        }
    }

    public static boolean isAppActivatedWithKey() {
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        int option = prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, ActiveOption.ACTIVATION_OPTION_NONE);
        if (option == ActiveOption.ACTIVATION_OPTION_CODE) {
            return true;
        } else {
            return false;
        }
    }

    public static void saveActivationOption(ActiveOption returnOption) {
        SharedPreferences prefs = getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        int current = prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, ActiveOption.ACTIVATION_OPTION_NONE);

        if (current != ActiveOption.ACTIVATION_OPTION_SUBSCRIPTION) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, returnOption.getOption());
            editor.putInt(Constants.APP_STATE.CURRENT_ACTIVE_MONTHS_LEFT, returnOption.getDaysLeft());
            editor.putLong(Constants.APP_STATE.CURRENT_ACTIVE_SECONDS_SINCE_FIRST_RUN, returnOption.getSecondsSinceFirstRun());
            editor.apply();
        }
    }

    public static ActiveOption getActivationOption() {

        ActiveOption option = new ActiveOption();

        SharedPreferences prefs = getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        option.setOption(prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_OPTION, ActiveOption.ACTIVATION_OPTION_NONE));
        option.setDaysLeft(prefs.getInt(Constants.APP_STATE.CURRENT_ACTIVE_MONTHS_LEFT, 0));
        option.setSecondsSinceFirstRun(prefs.getLong(Constants.APP_STATE.CURRENT_ACTIVE_SECONDS_SINCE_FIRST_RUN, -1));

        return option;
    }


    public static void setTrialExpiredAlarm(boolean expired) {

        SharedPreferences prefs = getContext().getSharedPreferences(Constants.APP_STATE.STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE);
        boolean trialExpiredSet = prefs.getBoolean(Constants.APP_STATE.TRIAL_EXPIRED_SET, false);

        AlarmManager alarmMgr = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(getContext(), TrialExpiredAlarmReceiver.class);
        PendingIntent alarmIntent = PendingIntent.getBroadcast(getContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);

        if (expired && !trialExpiredSet) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), Constants.DEFAULTS.TRIAL_EXPIRED_ALARM_PERIOD, alarmIntent);
            Log.e(TAG, "setTrialExpiredAlarm: set");

            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(Constants.APP_STATE.TRIAL_EXPIRED_SET, true);
            editor.apply();

        } else {
            alarmMgr.cancel(alarmIntent);
            Log.e(TAG, "setTrialExpiredAlarm: unset");

            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(Constants.APP_STATE.TRIAL_EXPIRED_SET, false);
            editor.apply();
        }
    }

}

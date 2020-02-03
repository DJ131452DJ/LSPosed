package org.meowcat.edxposed.manager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.meowcat.edxposed.manager.receivers.PackageChangeReceiver;
import org.meowcat.edxposed.manager.util.ModuleUtil;
import org.meowcat.edxposed.manager.util.NotificationUtil;
import org.meowcat.edxposed.manager.util.RepoLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import de.robv.android.xposed.installer.util.InstallZipUtil;

public class XposedApp extends de.robv.android.xposed.installer.XposedApp implements Application.ActivityLifecycleCallbacks {
    public static final String TAG = "EdXposedManager";
    @SuppressLint("SdCardPath")
    private static final String BASE_DIR_LEGACY = "/data/data/" + BuildConfig.APPLICATION_ID + "/";
    public static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/" + BuildConfig.APPLICATION_ID + "/" : BASE_DIR_LEGACY;
    public static final String ENABLED_MODULES_LIST_FILE = (Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/" + BuildConfig.APPLICATION_ID + "/" : BASE_DIR_LEGACY) + "conf/enabled_modules.list";
    public static int WRITE_EXTERNAL_PERMISSION = 69;
    @SuppressLint("StaticFieldLeak")
    private static XposedApp mInstance = null;
    private static Thread mUiThread;
    private static Handler mMainHandler;
    private SharedPreferences mPref;
    private Activity mCurrentActivity = null;
    private boolean mIsUiLoaded = false;

    public static XposedApp getInstance() {
        return mInstance;
    }

    public static InstallZipUtil.XposedProp getXposedProp() {
        return de.robv.android.xposed.installer.XposedApp.getInstance().mXposedProp;
    }

    public static void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mMainHandler.post(action);
        } else {
            action.run();
        }
    }

    public static File createFolder() {
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/EdXposedManager/");

        if (!dir.exists()) dir.mkdir();

        return dir;
    }

//    public static void postOnUiThread(Runnable action) {
//        mMainHandler.post(action);
//    }

    public static Integer getXposedVersion() {
        return getActiveXposedVersion();
    }

    public static SharedPreferences getPreferences() {
        return mInstance.mPref;
    }

    public static int getColor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", MODE_PRIVATE);
        int defaultColor = context.getResources().getColor(R.color.colorPrimary);

        return prefs.getInt("colors", defaultColor);
    }

    public static String getDownloadPath() {
        return getPreferences().getString("download_location", Environment.getExternalStorageDirectory() + "/Download/EdXposedManager/");
    }

    public static void mkdirAndChmod(String dir, int permissions) {
        dir = BASE_DIR + dir;
        //noinspection ResultOfMethodCallIgnored
        new File(dir).mkdir();
        FileUtils.setPermissions(dir, permissions, -1, -1);
    }

    public void onCreate() {
        super.onCreate();
        mInstance = this;
        mUiThread = Thread.currentThread();
        mMainHandler = new Handler();

        mPref = PreferenceManager.getDefaultSharedPreferences(this);

        de.robv.android.xposed.installer.XposedApp.getInstance().reloadXposedProp();
        createDirectories();
        delete(new File(Environment.getExternalStorageDirectory() + "/Download/EdXposedManager/.temp"));
        NotificationUtil.init();
        registerReceivers();

        registerActivityLifecycleCallbacks(this);

        @SuppressLint("SimpleDateFormat") DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
        Date date = new Date();

        if (!Objects.requireNonNull(mPref.getString("date", "")).equals(dateFormat.format(date))) {
            mPref.edit().putString("date", dateFormat.format(date)).apply();

            try {
                Log.i(TAG, String.format("EdXposedManager - %s - %s", BuildConfig.VERSION_CODE, getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        if (mPref.getBoolean("force_english", false)) {
            Resources res = getResources();
            DisplayMetrics dm = res.getDisplayMetrics();
            android.content.res.Configuration conf = res.getConfiguration();
            conf.locale = Locale.ENGLISH;
            res.updateConfiguration(conf, dm);
        }

        RepoLoader.getInstance().triggerFirstLoadIfNecessary();
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(new PackageChangeReceiver(), filter);

        PendingIntent.getBroadcast(this, 0,
                new Intent(this, PackageChangeReceiver.class), 0);
    }

    private void delete(File file) {
        if (file != null) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) for (File f : file.listFiles()) delete(f);
            }
            file.delete();
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    @SuppressLint({"PrivateApi", "NewApi"})
    private void createDirectories() {
        //FileUtils.setPermissions(BASE_DIR, 00777, -1, -1);
        mkdirAndChmod("conf", 00777);
        mkdirAndChmod("log", 00777);

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                @SuppressLint("SoonBlockedPrivateApi") Method deleteDir = FileUtils.class.getDeclaredMethod("deleteContentsAndDir", File.class);
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "bin"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "conf"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "log"));
            } catch (ReflectiveOperationException e) {
                Log.w(de.robv.android.xposed.installer.XposedApp.TAG, "Failed to delete obsolete directories", e);
            }
        }
    }

    public void updateProgressIndicator(final SwipeRefreshLayout refreshLayout) {
        final boolean isLoading = RepoLoader.getInstance().isLoading() || ModuleUtil.getInstance().isLoading();
        runOnUiThread(() -> {
            synchronized (XposedApp.this) {
                if (mCurrentActivity != null) {
                    mCurrentActivity.setProgressBarIndeterminateVisibility(isLoading);
                    if (refreshLayout != null)
                        refreshLayout.setRefreshing(isLoading);
                }
            }
        });
    }

    @Override
    public synchronized void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
        if (mIsUiLoaded)
            return;

        RepoLoader.getInstance().triggerFirstLoadIfNecessary();
        mIsUiLoaded = true;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {

    }

    @Override
    public synchronized void onActivityResumed(@NonNull Activity activity) {
        mCurrentActivity = activity;
        updateProgressIndicator(null);
    }

    @Override
    public synchronized void onActivityPaused(Activity activity) {
        activity.setProgressBarIndeterminateVisibility(false);
        mCurrentActivity = null;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {

    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {

    }
}

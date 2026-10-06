package com.halo.decomp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/** Shared, user-accessible storage used by the Android port. */
final class StoragePaths {
    static final String DIRECTORY_NAME = "YAHCEP";

    private StoragePaths() {
    }

    static File dataRoot() {
        return new File(Environment.getExternalStorageDirectory(), DIRECTORY_NAME);
    }

    static boolean hasSharedStorageAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30)
            return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    static void requestSharedStorageAccess(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            activity.requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, requestCode);
        }
    }
}

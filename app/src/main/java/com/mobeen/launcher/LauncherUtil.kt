package com.mobeen.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object LauncherUtil {

    fun isDefaultLauncher(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    fun requestDefaultLauncher(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1001)
                    return
                }
            }
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
            }
        }
    }
}

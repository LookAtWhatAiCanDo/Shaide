package com.smartfoo.android.core.platform

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.smartfoo.android.core.FooReflection
import com.smartfoo.android.core.FooString
import com.smartfoo.android.core.logging.FooLog
import java.util.Locale

@Suppress("unused")
object FooPlatformUtils {
    private val TAG = FooLog.TAG(FooPlatformUtils::class)

    @JvmStatic
    fun getPackageManager(context: Context): PackageManager = context.packageManager

    @JvmStatic
    fun getPackageName(context: Context): String = context.packageName

    /**
     * As of Android 11 (API 30) requires the following in AndroidManifest.xml:
     * ```
     * <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
     * tools:ignore="QueryAllPackagesPermission" >
     * ```
     * See:
     *  * [Package visibility in Android 11](https://medium.com/androiddevelopers/package-visibility-in-android-11-cc857f221cd9)
     * "In rare cases, your app might need to query or interact with all installed apps on a device, independent of the components they contain. To allow your app to see all other installed apps, Android 11 introduces the QUERY_ALL_PACKAGES permission. In an upcoming Google Play policy update, look for guidelines for apps that need the QUERY_ALL_PACKAGES permission."
     *  * [Package visibility filtering on Android](https://developer.android.com/training/package-visibility)
     *
     */
    @JvmOverloads
    @JvmStatic
    fun getApplicationInfo(
        context: Context,
        packageName: String? = null,
    ): ApplicationInfo? {
        val packageName = packageName ?: getPackageName(context)
        return try {
            getPackageManager(context).getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * As of Android 11 (API 30) requires the following in AndroidManifest.xml:
     * ```
     * <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
     * tools:ignore="QueryAllPackagesPermission" >
     * ```
     * See:
     *  * [Package visibility in Android 11](https://medium.com/androiddevelopers/package-visibility-in-android-11-cc857f221cd9)
     * "In rare cases, your app might need to query or interact with all installed apps on a device, independent of the components they contain.
     * To allow your app to see all other installed apps, Android 11 introduces the QUERY_ALL_PACKAGES permission.
     * In an upcoming Google Play policy update, look for guidelines for apps that need the QUERY_ALL_PACKAGES permission."
     *  * [Package visibility filtering on Android](https://developer.android.com/training/package-visibility)
     */
    @JvmOverloads
    @JvmStatic
    fun getApplicationName(
        context: Context,
        packageName: String? = null,
    ): String? {
        val packageName = packageName ?: getPackageName(context)
        val ai = getApplicationInfo(context, packageName) ?: return null
        return getPackageManager(context).getApplicationLabel(ai).toString()
    }

    /**
     * As of Android 11 (API 30) requires the following in AndroidManifest.xml:
     * ```
     * <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
     * tools:ignore="QueryAllPackagesPermission" >
     * ```
     * See:
     *  * [Package visibility in Android 11](https://medium.com/androiddevelopers/package-visibility-in-android-11-cc857f221cd9)
     * "In rare cases, your app might need to query or interact with all installed apps on a device, independent of the components they contain. To allow your app to see all other installed apps, Android 11 introduces the QUERY_ALL_PACKAGES permission. In an upcoming Google Play policy update, look for guidelines for apps that need the QUERY_ALL_PACKAGES permission."
     *  * [Package visibility filtering on Android](https://developer.android.com/training/package-visibility)
     *
     */
    @JvmOverloads
    @JvmStatic
    fun getApplicationUserId(
        context: Context,
        packageName: String? = null,
    ): Int? {
        val packageName = packageName ?: getPackageName(context)
        val ai = getApplicationInfo(context, packageName) ?: return null
        return ai.uid
    }

    /**
     * @param context context
     * @return PackageInfo of the context's package name, or null if one does not exist (should never happen)
     */
    @JvmStatic
    fun getPackageInfo(
        context: Context,
        packageName: String? = null,
    ): PackageInfo? {
        val packageName = packageName ?: getPackageName(context)
        return try {
            getPackageManager(context).getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * @param context      context
     * @param defaultValue defaultValue
     * @return the context's package's versionName, or defaultValue if one does not exist
     */
    @JvmStatic
    fun getVersionName(
        context: Context,
        defaultValue: String?,
    ): String? {
        val packageInfo = getPackageInfo(context)
        return if (packageInfo != null) {
            packageInfo.versionName
        } else {
            defaultValue
        }
    }

    /**
     * @param context      context
     * @param defaultValue defaultValue
     * @return the context's package's versionCode, or defaultValue if one does not exist
     */
    @JvmStatic
    fun getVersionCode(
        context: Context,
        defaultValue: Long,
    ): Long {
        val packageInfo = getPackageInfo(context)
        return packageInfo?.longVersionCode ?: defaultValue
    }

    @JvmStatic
    val deviceName: String
        get() {
            val manufacturer = FooString.capitalize(Build.MANUFACTURER)
            val deviceModel = FooString.capitalize(Build.MODEL)
            val deviceName =
                if (deviceModel.startsWith(manufacturer)) {
                    deviceModel
                } else {
                    "$manufacturer - $deviceModel"
                }

            return deviceName
        }

    @JvmStatic
    val osVersion: String
        get() {
            val builder = StringBuilder()

            builder.append("Android ").append(Build.VERSION.RELEASE)

            val fields =
                FooReflection.getClass(Build.VERSION_CODES::class).getFields()
            for (field in fields) {
                val fieldName = field.getName()

                var fieldValue = -1
                try {
                    fieldValue = field.getInt(Any())
                } catch (e: IllegalArgumentException) {
                    // ignore
                } catch (e: IllegalAccessException) {
                } catch (e: NullPointerException) {
                }

                if (fieldValue == Build.VERSION.SDK_INT) {
                    if (!FooString.isNullOrEmpty(fieldName)) {
                        builder.append(' ').append(fieldName)
                    }
                    builder.append(" (API level ").append(fieldValue).append(')')
                    break
                }
            }

            return builder.toString()
        }

    @JvmStatic
    fun hasSystemFeature(
        context: Context,
        name: String,
    ) = getPackageManager(context).hasSystemFeature(name)

    @JvmStatic
    fun hasSystemFeatureAutomotive(context: Context) = hasSystemFeature(context, PackageManager.FEATURE_AUTOMOTIVE)

    @JvmStatic
    fun hasSystemFeatureTelephony(context: Context) = hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY)

    @JvmStatic
    fun hasSystemFeatureTelevision(context: Context) = hasSystemFeature(context, PackageManager.FEATURE_LEANBACK)

    @JvmStatic
    fun hasSystemFeatureWatch(context: Context) = hasSystemFeature(context, PackageManager.FEATURE_WATCH)

    /**
     * @param context context
     * @return null if the package does not exist or has no meta-data
     */
    @JvmStatic
    fun getMetaData(context: Context): Bundle? {
        var metaDataBundle: Bundle? = null

        val packageName = getPackageName(context)
        val packageManager = getPackageManager(context)
        val applicationInfo =
            try {
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

        if (applicationInfo != null) {
            metaDataBundle = applicationInfo.metaData
        }

        return metaDataBundle
    }

    @JvmStatic
    fun getMetaDataString(
        context: Context,
        key: String?,
        defaultValue: String?,
    ): String? {
        var value: String? = null
        val metaDataBundle = getMetaData(context)
        if (metaDataBundle != null) {
            value = metaDataBundle.getString(key, defaultValue)
        }
        return value
    }

    @JvmStatic
    fun getMetaDataInt(
        context: Context,
        key: String?,
        defaultValue: Int,
    ): Int {
        var value = defaultValue
        val metaDataBundle = getMetaData(context)
        if (metaDataBundle != null) {
            value = metaDataBundle.getInt(key, defaultValue)
        }
        return value
    }

    @JvmStatic
    fun getMetaDataBoolean(
        context: Context,
        key: String?,
        defaultValue: Boolean,
    ): Boolean {
        var value = defaultValue
        val metaDataBundle = getMetaData(context)
        if (metaDataBundle != null) {
            value = metaDataBundle.getBoolean(key, defaultValue)
        }
        return value
    }

    /**
     * More useful than [android.content.Intent.toString] that only prints "(has extras)" if there are extras.
     */
    @JvmStatic
    fun toString(intent: Intent?): String {
        if (intent == null) return "null"
        return StringBuilder()
            .append(intent)
            .append(", extras=")
            .append(toString(intent.extras))
            .toString()
    }

    /**
     * May be unnecessary; [android.os.Bundle]`.toString` output seems almost acceptable nowadays.
     */
    @JvmStatic
    fun toString(bundle: Bundle?, skipZeroFalseNullValues: Boolean = true): String {
        if (bundle == null) return "null"

        val keys = bundle.keySet()
        val it = keys.iterator()

        val parts = mutableListOf<String>()
        while (it.hasNext()) {
            val key = it.next()

            @Suppress("KDocUnresolvedReference")
            var value =
                try {
                    /**
                     * [android.os.BaseBundle.get] calls hidden method [android.os.BaseBundle.getValue].
                     * `android.os.BaseBundle#getValue(java.lang.String)` says:
                     * "Deprecated: Use `getValue(String, Class, Class[])`. This method should only be used in other deprecated APIs."
                     * That first sentence does not help this method that dynamically enumerates the Bundle entries without awareness/concern of any types.
                     * That second sentence tells me they probably won't be getting rid of android.os.BaseBundle#get(java.lang.String) any time soon.
                     * So marking deprecated `android.os.BaseBundle#get(java.lang.String)` as safe to call for a while... until it isn't.
                     */
                    @Suppress("DEPRECATION")
                    bundle.get(key)
                } catch (e: RuntimeException) {
                    // Known issue if a Bundle (Parcelable) incorrectly implements writeToParcel
                    "[Error retrieving \"$key\" value: ${e.message}]"
                }
            if (skipZeroFalseNullValues && (value == 0 || value == false || value == null)) {
                continue
            }

            val sb = StringBuilder()
            sb.append(FooString.quote(key)).append('=')

            if (key.lowercase(Locale.getDefault()).contains("password")) {
                value = "*REDACTED*"
            }

            when (value) {
                is Bundle -> {
                    sb.append(toString(value))
                }

                is Intent -> {
                    sb.append(toString(value))
                }

                else -> {
                    sb.append(FooString.quote(value))
                }
            }

            parts.add(sb.toString())
        }
        return parts.joinToString(", ", "{ ", " }")
    }

    @JvmOverloads
    @JvmStatic
    fun startActivity(
        context: Context,
        activityClass: Class<*>,
        bundle: Bundle? = null,
    ) {
        startActivity(context, Intent(context, activityClass), bundle)
    }

    @JvmOverloads
    @JvmStatic
    fun startActivity(
        context: Context,
        intent: Intent,
        bundle: Bundle? = null,
    ) {
        /*
        if (context is Application) {
          // TODO Use Application.ActivityLifecycleCallbacks (like in AlfredAI) to actually test for background or not
          /*
          // Background startActivity requires FLAG_ACTIVITY_NEW_TASK
          if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0)
          {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          }
          */
        }
        */
        context.startActivity(intent, bundle)
    }

    /**
     * I originally just wanted to be able to change the System Development Debug Layout property.
     * I thought that I could duplicate what com.android.settings.DevelopmentSettings does:
     * https://cs.android.com/android/platform/superproject/+/android-7.1.2_r39:packages/apps/Settings/src/com/android/settings/DevelopmentSettings.java
     * Currently (2025/03) that code has moved to under:
     * https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/development/
     *
     * ie: Use Reflection to set the SystemProperty and then pokeSystemProperties
     *
     * After several hours of work I learned that the SystemProperties are ACL protected to only allow the Google
     * Signed Settings app to change them.
     * http://stackoverflow.com/a/11136242 -&gt; http://stackoverflow.com/a/11123609/252308
     *
     * Rather than continue to try to get this to work (if it is even possible),
     * I have chosen to just launch the SettingsActivity DevelopmentSettings fragment.
     *
     * Other references for my wasted efforts:
     * https://github.com/android/platform_packages_apps_settings/blob/master/src/com/android/settings/DevelopmentSettings.java#L1588
     * https://github.com/android/platform_frameworks_base/blob/master/core/java/android/os/SystemProperties.java#L122
     * https://github.com/android/platform_frameworks_base/blob/master/core/java/android/view/View.java#L706
     * https://github.com/Androguide/CMDProcessorLibrary/blob/master/CMDProcessorLibrary/src/com/androguide/cmdprocessor/SystemPropertiesReflection.java
     *
     * @param context context
     */
    @JvmStatic
    fun showDevelopmentSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // DevelopmentSettings appears to not have any arguments. :(
        // https://github.com/android/platform_packages_apps_settings/blob/master/src/com/android/settings/DevelopmentSettings.java
        startActivity(context, intent)
    }

    /**
     * This has a sometimes annoying side effect of Back not being able to exit the Activity.
     * Even Android's built-in Notification Tile "Wireless debug settings" exhibits the same problem.
     * When this happens just do what you always do to close an Activity:
     *   swipe up from the bottom and then swipe the Activity up to close it.
     *
     * https://stackoverflow.com/a/74859391/252308
     *
     * https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/development/AdbWirelessDialog.java
     * https://cs.android.com/android/platform/superproject/+/android-14.0.0_r61:packages/apps/Settings/src/com/android/settings/development/AdbWirelessDialog.java
     * https://cs.android.com/android/platform/superproject/+/android-14.0.0_r61:packages/apps/Settings/src/com/android/settings/development/AdbWirelessDialogController.java
     *
     * @param context
     */
    @JvmStatic
    fun showAdbWirelessSettings(context: Context) {
        /*

        Hints of how Android Settings -> Development Settings does something similar:
        https://cs.android.com/android/platform/superproject/+/android-15.0.0_r23:packages/apps/Settings/AndroidManifest.xml?q=DevelopmentSettingsActivity

        ```
        <activity
            android:name="Settings$DevelopmentSettingsActivity"
            android:label="@string/development_settings_title"
            android:icon="@drawable/ic_settings_development"
            android:exported="true">
            <intent-filter android:priority="1">
                <action android:name="android.settings.APPLICATION_DEVELOPMENT_SETTINGS" />
                <action android:name="com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS" />
                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES"/>
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <meta-data android:name="com.android.settings.FRAGMENT_CLASS"
                       android:value="com.android.settings.development.DevelopmentSettingsDashboardFragment" />
            <meta-data android:name="com.android.settings.HIGHLIGHT_MENU_KEY"
                       android:value="@string/menu_key_system"/>
            <meta-data android:name="com.android.settings.PRIMARY_PROFILE_CONTROLLED"
                       android:value="true" />
        </activity>
        ```

        `Settings$DevelopmentSettingsActivity` is a stub activity that
        https://cs.android.com/android/platform/superproject/+/android-15.0.0_r23:packages/apps/Settings/src/com/android/settings/Settings.java
        reads the meta-data to redirect to DevelopmentSettingsDashboardFragment:
        https://cs.android.com/android/platform/superproject/+/android-15.0.0_r23:packages/apps/Settings/src/com/android/settings/development/DevelopmentSettingsDashboardFragment.java

         */

        val pkg = "com.android.settings"
        val cls = $$"com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging"
        val componentName = ComponentName(pkg, cls)

        // "android.service.quicksettings.action.QS_TILE_PREFERENCES"
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
        startActivity(context, intent)
    }

    @JvmStatic
    fun showGooglePlay(
        context: Context,
        packageName: String?,
    ) {
        try {
            val uri = "market://details?id=$packageName".toUri()
            startActivity(context, Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            val uri = "https://play.google.com/store/apps/details?id=$packageName".toUri()
            startActivity(context, Intent(Intent.ACTION_VIEW, uri))
        }
    }

    /**
     * Adds [android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP] and [android.content.Intent.FLAG_ACTIVITY_NEW_TASK] to the intent.
     *
     * From https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_CLEAR_TOP
     * "If set, and the activity being launched is already running in the current task, then
     * instead of launching a new instance of that activity, all of the other activities on
     * top of it will be closed and this Intent will be delivered to the (now on top) old
     * activity as a new Intent."
     * ...
     * "This launch mode can also be used to good effect in conjunction with FLAG_ACTIVITY_NEW_TASK:
     * if used to start the root activity of a task, it will bring any currently running
     * instance of that task to the foreground, and then clear it to its root state. This is
     * especially useful, for example, when launching an activity from the notification manager."
     */
    @JvmStatic
    fun Intent.fromNotificationManager(): Intent {
        return this
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @JvmStatic
    fun intentShowAppSettings(context: Context) =
        intentShowAppSettings(context.packageName)

    @JvmStatic
    fun intentShowAppSettings(packageName: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri())

    @JvmOverloads
    @JvmStatic
    fun showAppSettings(
        context: Context,
        packageName: String = context.packageName) {
        startActivity(context, intentShowAppSettings(packageName))
    }

    @JvmStatic
    fun showBatterySettings(context: Context) {
        val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        val resolveInfo = context.packageManager.resolveActivity(intent, 0)
        if (resolveInfo != null) {
            startActivity(context, intent)
        }
    }

    @JvmStatic
    private fun intentAppNotificationSettings(context: Context) =
        intentAppNotificationSettings(context.packageName)

    @JvmStatic
    private fun intentAppNotificationSettings(packageName: String) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }

    @JvmOverloads
    @JvmStatic
    fun showAppNotificationSettings(
        context: Context,
        packageName: String = context.packageName) {
        startActivity(context, intentAppNotificationSettings(packageName))
    }

    @JvmStatic
    fun intentNotificationListenerSettings() =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * Non-hidden duplicate of [Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS]
     */
    const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    @JvmStatic
    fun intentAccessibilityDetailsSettings(componentName: ComponentName) =
        Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)

    @JvmStatic
    fun intentAccessibilityDetailsSettings(context: Context, serviceClass: Class<out AccessibilityService>) =
        intentAccessibilityDetailsSettings(ComponentName(context, serviceClass))

    @JvmStatic
    fun showAccessibilityDetailsSettings(
        context: Context,
        componentName: ComponentName) {
        startActivity(context, intentAccessibilityDetailsSettings(componentName))
    }

    @JvmStatic
    fun showAccessibilityDetailsSettings(
        context: Context,
        serviceClass: Class<out AccessibilityService>) =
        showAccessibilityDetailsSettings(context, ComponentName(context, serviceClass))
}

package org.schabi.newpipe

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    /**
     * Method to compare the current and latest available app version.
     * If a newer version is available, we show the update notification.
     *
     * @param versionName    Name of new version
     * @param apkLocationUrl Url with the new apk
     * @param versionCode    Code of new version
     */
    private fun compareAppVersionAndShowNotification(
        versionName: String,
        apkLocationUrl: String?,
        versionCode: Int
    ) {
        if (BuildConfig.VERSION_CODE >= versionCode) {
            if (inputData.getBoolean(IS_MANUAL, false)) {
                // Show toast stating that the app is up-to-date if the update check was manual.
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(
                        applicationContext,
                        R.string.app_update_unavailable_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }

        // A pending intent to open the apk location url in the browser.
        val intent = Intent(Intent.ACTION_VIEW, apkLocationUrl?.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntentCompat.getActivity(
            applicationContext,
            0,
            intent,
            0,
            false
        )
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(
                applicationContext.getString(R.string.app_update_available_notification_title)
            )
            .setContentText(
                applicationContext.getString(
                    R.string.app_update_available_notification_text,
                    versionName
                )
            )

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(2000, notificationBuilder.build())
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        val response = DownloaderImpl.getInstance().get(BuildConfig.UPDATE_VERSION_URL)
        handleResponse(response)
    }

    private fun handleResponse(response: Response) {
        try {
            val published = JsonParser.`object`().from(response.responseBody())
            val versionName = published.getString("version") ?: return
            val versionCode = published.getInt("version_code")
            val apkLocationUrl = published.getString("apk")
            compareAppVersionAndShowNotification(versionName, apkLocationUrl, versionCode)
        } catch (e: JsonParserException) {
            if (DEBUG) {
                Log.w(TAG, "Could not read published version from ${BuildConfig.UPDATE_VERSION_URL}", e)
            }
        }
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch NewPipe API: probably network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val IS_MANUAL = "isManual"
        private const val UNIQUE_WORK_NAME = "newpipe-published-version-check"

        /**
         * Fetch [BuildConfig.UPDATE_VERSION_URL] and notify when the published APK
         * has a higher version code than the running app.
         */
        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}

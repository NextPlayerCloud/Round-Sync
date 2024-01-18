package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcel
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Log2File
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.prototypes.WorkerNotification
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import de.felixnuesse.extract.extensions.tag
import de.felixnuesse.extract.notifications.implementations.DownloadWorkerNotification
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import kotlin.random.Random
import android.util.Log


class EphemeralWorker (private var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {

    companion object {
        const val EPHEMERAL_TYPE = "TASK_EPHEMERAL_TYPE"
        const val REMOTE_ID = "REMOTE_ID"
        const val REMOTE_TYPE = "REMOTE_TYPE"
        const val DOWNLOAD_TARGETPATH = "DOWNLOAD_TARGETPATH"
        const val DOWNLOAD_SOURCE = "DOWNLOAD_SOURCE"
    }

    internal enum class FAILURE_REASON {
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED, CANCELLED, NO_TASK
    }

    // Objects
    private var mNotificationManager: WorkerNotification? = null
    private val mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)


    private var log2File: Log2File? = null


    // States
    private val sIsLoggingEnabled = mPreferences.getBoolean(getString(R.string.pref_key_logs), false)
    private var sConnectivityChanged = false

    private var sRcloneProcess: Process? = null
    private val statusObject = StatusObject(mContext)
    private var failureReason = FAILURE_REASON.NO_FAILURE
    private var endNotificationAlreadyPosted = false
    private var silentRun = false
    private val ongoingNotificationID = Random.nextInt()


    private var mTitle: String = mContext.getString(R.string.sync_service_notification_startingsync)


    override fun doWork(): Result {

        prepareNotifications()
        registerBroadcastReceivers()

        updateForegroundNotification(mNotificationManager?.updateNotification(
            mTitle,
            mTitle,
            ArrayList(),
            0,
            ongoingNotificationID
        ))

        if (inputData.keyValueMap.containsKey(EPHEMERAL_TYPE)){
            val type = Type.valueOf(inputData.getString(EPHEMERAL_TYPE) ?: "")
            mNotificationManager = prepareNotificationManager(type)

            if (mNotificationManager == null){
                log("Warning: No valid Notifications are available")
            }

            val remoteItem = RemoteItem(inputData.getString(REMOTE_ID), inputData.getString(REMOTE_TYPE))

            when(type){
                Type.DOWNLOAD -> {
                    val target = inputData.getString(DOWNLOAD_TARGETPATH)
                    val sourceParcelByteArray = inputData.getByteArray(DOWNLOAD_SOURCE)
                    if(sourceParcelByteArray == null){
                        log("No valid target was passed!")
                        return Result.failure()
                    }

                    val parcel = Parcel.obtain()
                    parcel.unmarshall(sourceParcelByteArray, 0, sourceParcelByteArray.size)
                    parcel.setDataPosition(0)

                    sRcloneProcess = Rclone(mContext).downloadFile(
                        remoteItem,
                        FileItem.CREATOR.createFromParcel(parcel),
                        target
                    )
                }
                Type.UPLOAD -> TODO()
                Type.MOVE -> TODO()
                Type.DELETE -> TODO()
            }

            mNotificationManager?.setCancelId(id)
            if(preconditionsMet()) {
                handleSync(mTitle)
            } else {
                log("Preconditions are not met!")
                return Result.failure()
            }
            postSync()

            // Indicate whether the work finished successfully with the Result
            return Result.success()
        }
        log("Critical: No valid ephemeral type passed!")
        return Result.failure()
    }

    override fun onStopped() {
        super.onStopped()
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_sync_cancelled))
        SyncLog.info(mContext, mTitle, statusObject.toString())
        failureReason = FAILURE_REASON.CANCELLED
        finishWork()
    }

    private fun finishWork() {
        sRcloneProcess?.destroy()
        mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
        postSync()
    }

    fun prepareNotificationManager(type: Type): WorkerNotification? {
        return when(type){
            Type.DOWNLOAD -> DownloadWorkerNotification(mContext)
            else -> null
        }
    }

    private fun handleSync(title: String) {
        if (sRcloneProcess != null) {
            val localProcessReference = sRcloneProcess!!
            try {
                val reader = BufferedReader(InputStreamReader(localProcessReference.errorStream))
                val iterator = reader.lineSequence().iterator()
                while(iterator.hasNext()) {
                    val line = iterator.next()
                    try {
                        val logline = JSONObject(line)
                        //todo: migrate this to StatusObject, so that we can handle everything properly.
                        if (logline.getString("level") == "error") {
                            if (sIsLoggingEnabled) {
                                log2File?.log(line)
                            }
                            statusObject.parseLoglineToStatusObject(logline)
                        } else if (logline.getString("level") == "warning") {
                            statusObject.parseLoglineToStatusObject(logline)
                        }

                        updateForegroundNotification(mNotificationManager?.updateNotification(
                            title,
                            statusObject.notificationContent,
                            statusObject.notificationBigText,
                            statusObject.notificationPercent,
                            ongoingNotificationID
                        ))
                    } catch (e: JSONException) {
                        FLog.e(tag(), "Error: the offending line: $line")
                        //FLog.e(TAG, "onHandleIntent: error reading json", e)
                    }
                }
            } catch (e: InterruptedIOException) {
                FLog.e(tag(), "onHandleIntent: I/O interrupted, stream closed", e)
            } catch (e: IOException) {
                FLog.e(tag(), "onHandleIntent: error reading stdout", e)
            }
            try {
                localProcessReference.waitFor()
            } catch (e: InterruptedException) {
                FLog.e(tag(), "onHandleIntent: error waiting for process", e)
            }
        } else {
            log("Sync: No Rclone Process!")
        }
        mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
    }

    private fun postSync() {
        if (endNotificationAlreadyPosted) {
            return
        }
        if (silentRun) {
            return
        }

        val notificationId = System.currentTimeMillis().toInt()

        var content = mContext.getString(R.string.operation_failed_unknown, mTitle)
        when (failureReason) {
            FAILURE_REASON.NO_FAILURE -> {
                showSuccessNotification(notificationId)
                return
            }
            FAILURE_REASON.CANCELLED -> {
                showCancelledNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.NO_TASK -> {
                content = getString(R.string.operation_failed_notask)
            }
            FAILURE_REASON.CONNECTIVITY_CHANGED -> {
                content = mContext.getString(R.string.operation_failed_data_change, mTitle)
            }
            FAILURE_REASON.NO_UNMETERED -> {
                content = mContext.getString(R.string.operation_failed_no_unmetered, mTitle)
            }
            FAILURE_REASON.NO_CONNECTION -> {
                content = mContext.getString(R.string.operation_failed_no_connection, mTitle)
            }
            FAILURE_REASON.RCLONE_ERROR -> {
                content = mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            }
        }
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
    }

    private fun showCancelledNotification(notificationId: Int) {
        val content = mContext.getString(R.string.operation_failed_cancelled)
        SyncLog.info(mContext, mTitle, content)
        mNotificationManager?.showCancelledNotification(
            mTitle,
            content,
            notificationId,
            0
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        //Todo: Show sync-errors in notification. Also see line 169
        var message = mContext.resources.getQuantityString(
            R.plurals.operation_success_description,
            statusObject.getTotalTransfers(),
            mTitle,
            statusObject.getTotalSize(),
            statusObject.getTotalTransfers()
        )
        if (statusObject.getTotalTransfers() == 0) {
            message = mContext.resources.getString(R.string.operation_success_description_zero)
        }
        if (statusObject.getDeletions() > 0) {
            message += """
                        
                        ${
                mContext.getString(
                    R.string.operation_success_description_deletions_prefix,
                    statusObject.getDeletions()
                )
            }
                        """.trimIndent()
        }

        mNotificationManager?.showSuccessNotification(
            mTitle,
            message,
            notificationId
        )

        message += """
                        
        Est. Speed: ${statusObject.getEstimatedAverageSpeed()}
        Avg. Speed: ${statusObject.getLastItemAverageSpeed()}
                        """.trimIndent()
        SyncLog.info(mContext, mContext.getString(R.string.operation_success, mTitle), message)
    }

    private fun showFailNotification(notificationId: Int, content: String, wasCancelled: Boolean = false) {
        var text = content
        //Todo: check if we should also add errors on success
        statusObject.printErrors()
        val errors = statusObject.getAllErrorMessages()
        if (errors.isNotEmpty()) {
            text += """
                        
                        
                        
                        ${statusObject.getAllErrorMessages()}
                        """.trimIndent()
        }

        var notifyTitle = mContext.getString(R.string.operation_failed)
        if (wasCancelled) {
            notifyTitle = mContext.getString(R.string.operation_failed_cancelled)
        }
        SyncLog.error(mContext, notifyTitle, "$mTitle: $text")
        mNotificationManager?.showFailedNotification(
            mTitle,
            text,
            notificationId,
           0
        )
    }

    private fun preconditionsMet(): Boolean {
        val connection = WifiConnectivitiyUtil.dataConnection(this.applicationContext)
        if (connection === WifiConnectivitiyUtil.Connection.METERED) {
            failureReason = FAILURE_REASON.NO_UNMETERED
            return false
        } else if (connection === WifiConnectivitiyUtil.Connection.DISCONNECTED || connection === WifiConnectivitiyUtil.Connection.NOT_AVAILABLE) {
            failureReason = FAILURE_REASON.NO_CONNECTION
            return false
        }

        return true
    }

    private fun prepareNotifications() {

        GenericSyncNotification(mContext).setNotificationChannel(
            mNotificationManager?.CHANNEL_ID?:"",
            getString(R.string.sync_service_notification_channel_title),
            getString(R.string.sync_service_notification_channel_description)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            mNotificationManager?.CHANNEL_SUCCESS_ID?:"",
            getString(R.string.sync_service_notification_channel_success_title),
            getString(R.string.sync_service_notification_channel_success_description)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            mNotificationManager?.CHANNEL_FAIL_ID?:"",
            getString(R.string.sync_service_notification_channel_fail_title),
            getString(R.string.sync_service_notification_channel_fail_description)
        )

    }

    private fun sendUploadFinishedBroadcast(remote: String, path: String?) {
        val intent = Intent()
        intent.action = getString(R.string.background_service_broadcast)
        intent.putExtra(getString(R.string.background_service_broadcast_data_remote), remote)
        intent.putExtra(getString(R.string.background_service_broadcast_data_path), path)
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun updateForegroundNotification(notification: Notification?) {
        notification?.let {
            setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }
    }


    private fun log(message: String) {
        Log.e("tag()", "EphemeralWorker: $message")
    }

    private fun getString(@StringRes resId: Int): String {
        return mContext.getString(resId)
    }

    private fun registerBroadcastReceivers() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
        mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                sConnectivityChanged = true
                failureReason = FAILURE_REASON.CONNECTIVITY_CHANGED
                finishWork()
            }
        }

}

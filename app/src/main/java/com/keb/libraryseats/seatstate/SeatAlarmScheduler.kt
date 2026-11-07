package com.keb.libraryseats.seatstate

import com.keb.libraryseats.MainActivity
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.keb.libraryseats.domain.Reservation
import com.keb.libraryseats.domain.SeatService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SeatAlarmScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val prefs = this.context.getSharedPreferences("nfc_seat_alarms", Context.MODE_PRIVATE)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun notificationsEnabled(): Boolean {
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!notifications.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun sync(active: List<Reservation>, force: Boolean = false) {
        createChannel()
        val now = System.currentTimeMillis()
        val currentUser = LocalSeatStore(context).user()?.first
        for (seatId in SeatService.SEAT_IDS) {
            val reservation = active.firstOrNull { it.seatId == seatId && it.expiresAt > now }
            if (reservation == null) {
                if (!prefs.contains("scheduled_$seatId") && !prefs.contains("notified_$seatId")) continue
                cancelAlarms(seatId)
                dismissSeat(seatId)
                prefs.edit().remove("scheduled_$seatId").remove("notified_$seatId").apply()
                continue
            }
            val generation = generation(reservation)
            val scheduleKey = "$generation:${canScheduleExact()}"
            if (force || prefs.getString("scheduled_$seatId", null) != scheduleKey) {
                cancelAlarms(seatId)
                if (prefs.getString("notified_$seatId", null) != generation) dismissSeat(seatId)
                try {
                    if (reservation.reminderAt > now) schedule(reservation.reminderAt, alarmIntent(ACTION_REMIND, reservation))
                    schedule(reservation.expiresAt, alarmIntent(ACTION_EXPIRE, reservation))
                    prefs.edit().putString("scheduled_$seatId", scheduleKey).apply()
                } catch (error: RuntimeException) {
                    cancelAlarms(seatId)
                    prefs.edit().remove("scheduled_$seatId").apply()
                    Log.e(TAG, "Could not schedule seat alarms", error)
                }
            }
            if (reservation.studentId != currentUser) {
                dismissSeat(seatId)
                prefs.edit().remove("notified_$seatId").apply()
            } else if (now >= reservation.reminderAt) {
                remind(reservation)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun remind(reservation: Reservation) {
        val now = System.currentTimeMillis()
        if (now < reservation.reminderAt || now >= reservation.expiresAt) return
        if (LocalSeatStore(context).user()?.first != reservation.studentId) return
        val token = generation(reservation)
        if (prefs.getString("notified_${reservation.seatId}", null) == token || !notificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RETAG
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SEAT_ID, reservation.seatId)
            putExtra(EXTRA_STUDENT_ID, reservation.studentId)
            putExtra(EXTRA_VERIFIED_AT, reservation.lastVerifiedAt)
        }
        val pending = PendingIntent.getActivity(context, notificationId(reservation.seatId), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deadline = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(reservation.expiresAt))
        val message = "${reservation.seatId} 좌석을 $deadline 까지 다시 태깅해 주세요. 재태깅하지 않으면 자동 퇴실됩니다."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NFC 재태깅 필요")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .addAction(android.R.drawable.ic_menu_view, "NFC 태깅", pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(reservation.expiresAt - now)
            .build()
        try {
            notifications.notify(notificationId(reservation.seatId), notification)
            prefs.edit().putString("notified_${reservation.seatId}", token).apply()
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked", error)
        }
    }

    fun dismissSeat(seatId: String) { notifications.cancel(notificationId(seatId)) }

    private fun schedule(at: Long, intent: PendingIntent) {
        if (canScheduleExact()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                return
            } catch (error: SecurityException) {
                Log.w(TAG, "Exact alarm permission changed; using an inexact alarm", error)
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    private fun alarmIntent(action: String, reservation: Reservation): PendingIntent =
        PendingIntent.getBroadcast(context, alarmId(action, reservation.seatId),
            Intent(context, SeatAlarmReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SEAT_ID, reservation.seatId)
                putExtra(EXTRA_STUDENT_ID, reservation.studentId)
                putExtra(EXTRA_VERIFIED_AT, reservation.lastVerifiedAt)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAlarms(seatId: String) {
        for (action in listOf(ACTION_REMIND, ACTION_EXPIRE)) {
            val pending = PendingIntent.getBroadcast(context, alarmId(action, seatId),
                Intent(context, SeatAlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms.cancel(pending); pending.cancel() }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "좌석 재태깅 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "인증 30분 후 재태깅을 안내합니다. 5분 안에 태깅하지 않으면 자동 퇴실됩니다."
            })
        }
    }

    private fun generation(r: Reservation) = "${r.studentId}:${r.lastVerifiedAt}:${r.expiresAt}"
    private fun notificationId(seatId: String) = 7000 + SeatService.SEAT_IDS.indexOf(seatId)
    private fun alarmId(action: String, seatId: String) = notificationId(seatId) * 10 + if (action == ACTION_REMIND) 1 else 2

    companion object {
        private const val TAG = "SeatAlarmScheduler"
        const val CHANNEL_ID = "seat_retag"
        const val ACTION_RETAG = "com.keb.libraryseats.RETAG"
        const val ACTION_REMIND = "com.keb.libraryseats.SEAT_REMIND"
        const val ACTION_EXPIRE = "com.keb.libraryseats.SEAT_EXPIRE"
        const val EXTRA_SEAT_ID = "seat_id"
        const val EXTRA_STUDENT_ID = "student_id"
        const val EXTRA_VERIFIED_AT = "verified_at"
    }
}

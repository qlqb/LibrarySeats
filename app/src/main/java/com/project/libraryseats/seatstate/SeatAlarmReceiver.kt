package com.project.libraryseats.seatstate

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.project.libraryseats.data.FirestoreSeatRepository

class SeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repository = FirestoreSeatRepository()
        val seatId = intent.getStringExtra(SeatAlarmScheduler.EXTRA_SEAT_ID)
        val uid = intent.getStringExtra(SeatAlarmScheduler.EXTRA_FIREBASE_UID)
        val verifiedAt = intent.getLongExtra(SeatAlarmScheduler.EXTRA_VERIFIED_AT, Long.MIN_VALUE)
        when (intent.action) {
            SeatAlarmScheduler.ACTION_EXPIRE -> {
                if (seatId.isNullOrBlank() || uid.isNullOrBlank()) {
                    pending.finish()
                    return
                }
                repository.expire(seatId, uid, verifiedAt)
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful) Log.e("SeatAlarmReceiver", "Firestore expiry failed", task.exception)
                        pending.finish()
                    }
            }
            SeatAlarmScheduler.ACTION_REMIND -> {
                if (seatId.isNullOrBlank()) {
                    pending.finish()
                    return
                }
                repository.getReservation(seatId).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val reservation = task.result
                        if (reservation != null && reservation.lastVerifiedAt == verifiedAt &&
                            (uid.isNullOrBlank() || uid == LocalSeatStore(context).firebaseUid())) {
                            SeatAlarmScheduler(context).remind(reservation)
                        }
                    } else Log.e("SeatAlarmReceiver", "Firestore reminder lookup failed", task.exception)
                    pending.finish()
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                repository.getAllActive().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        SeatAlarmScheduler(context).sync(task.result.orEmpty(), force = true)
                    } else Log.e("SeatAlarmReceiver", "Firestore alarm restore failed", task.exception)
                    pending.finish()
                }
            }
            else -> pending.finish()
        }
    }
}

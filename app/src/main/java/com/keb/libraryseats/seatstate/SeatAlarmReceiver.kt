package com.keb.libraryseats.seatstate

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.keb.libraryseats.domain.SeatService

class SeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reset = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!reset && intent.action != SeatAlarmScheduler.ACTION_REMIND && intent.action != SeatAlarmScheduler.ACTION_EXPIRE) return
        try {
            val store = LocalSeatStore(context)
            val stored = store.load()
            val active = SeatService(stored).active()
            if (active != stored) store.save(active)
            val scheduler = SeatAlarmScheduler(context)
            if (intent.action == SeatAlarmScheduler.ACTION_REMIND) {
                val seatId = intent.getStringExtra(SeatAlarmScheduler.EXTRA_SEAT_ID)
                val studentId = intent.getStringExtra(SeatAlarmScheduler.EXTRA_STUDENT_ID)
                val verifiedAt = intent.getLongExtra(SeatAlarmScheduler.EXTRA_VERIFIED_AT, Long.MIN_VALUE)
                val current = active.firstOrNull {
                    it.seatId == seatId && it.studentId == studentId && it.lastVerifiedAt == verifiedAt
                }
                if (current != null) scheduler.remind(current)
            }
            scheduler.sync(active, force = reset)
        } catch (error: Exception) {
            Log.e("SeatAlarmReceiver", "Could not reconcile seat deadline", error)
        }
    }
}

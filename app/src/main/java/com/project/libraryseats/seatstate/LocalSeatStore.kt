package com.project.libraryseats.seatstate

import android.content.Context
import android.util.AtomicFile
import com.project.libraryseats.domain.Reservation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalSeatStore(context: Context) {
    private val prefs = context.getSharedPreferences("dmu_seat_demo", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.noBackupFilesDir, "nfc_test_reservations.json"))
    fun load(): List<Reservation> {
        val raw = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } else "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            Reservation(value.getString("seatId"), value.getString("studentId"),
                value.getString("studentName"), value.getLong("checkedInAt"),
                value.getLong("lastVerifiedAt"), value.getLong("expiresAt"), value.getString("tagUid"))
        }
    }
    fun save(reservations: List<Reservation>) {
        val data = JSONArray()
        reservations.forEach { r -> data.put(JSONObject().apply {
            put("seatId", r.seatId); put("studentId", r.studentId); put("studentName", r.studentName)
            put("checkedInAt", r.checkedInAt); put("lastVerifiedAt", r.lastVerifiedAt)
            put("expiresAt", r.expiresAt); put("tagUid", r.tagUid)
        }) }
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw IllegalStateException("좌석 정보를 저장하지 못했습니다. 저장 공간을 확인해 주세요.", error)
        }
    }
    fun user(): Pair<String, String>? {
        val id = prefs.getString("studentId", null) ?: return null
        return id to (prefs.getString("studentName", null) ?: return null)
    }
    fun login(id: String, name: String) { prefs.edit().putString("studentId", id).putString("studentName", name).apply() }
    fun logout() { prefs.edit().remove("studentId").remove("studentName").remove("firebaseUid").apply() }
    fun setFirebaseUid(uid: String) { prefs.edit().putString("firebaseUid", uid).apply() }
    fun firebaseUid(): String? = prefs.getString("firebaseUid", null)

    fun wasReminderShown(reservation: Reservation): Boolean =
        prefs.getString("reminder_${reservation.seatId}", null) == reminderKey(reservation)

    fun markReminderShown(reservation: Reservation) {
        prefs.edit().putString("reminder_${reservation.seatId}", reminderKey(reservation)).apply()
    }

    private fun reminderKey(reservation: Reservation) =
        "${reservation.studentId}:${reservation.lastVerifiedAt}"
}

package com.project.libraryseats.data

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.project.libraryseats.domain.Reservation
import com.project.libraryseats.domain.SeatService

class FirestoreSeatRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val seats = db.collection(SEATS)
    private val users = db.collection(ACTIVE_USERS)

    fun observe(
        onChange: (List<Reservation>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration = seats.addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val reservations = snapshot?.documents.orEmpty().mapNotNull { doc ->
            runCatching {
                val uid = doc.getString("firebaseUid") ?: return@runCatching null
                Reservation(
                    seatId = doc.id,
                    studentId = doc.getString("studentId") ?: return@runCatching null,
                    studentName = doc.getString("studentName").orEmpty(),
                    checkedInAt = doc.getLong("checkedInAt") ?: return@runCatching null,
                    lastVerifiedAt = doc.getLong("lastVerifiedAt") ?: return@runCatching null,
                    expiresAt = doc.getLong("expiresAt") ?: return@runCatching null,
                    tagUid = doc.getString("tagUid") ?: return@runCatching null
                )
            }.getOrNull()
        }.filter { it.expiresAt > System.currentTimeMillis() }
        onChange(reservations)
    }

    fun reserve(
        firebaseUid: String,
        studentId: String,
        studentName: String,
        selectedSeatId: String,
        scannedSeatId: String,
        tagUid: String,
        expectedVerification: Long?
    ): Task<Reservation> {
        val seatRef = seats.document(selectedSeatId)
        val userRef = users.document(firebaseUid)
        return db.runTransaction { tx ->
            // Complete every read before issuing any transaction write.
            val seatSnapshot = tx.get(seatRef)
            val userSnapshot = tx.get(userRef)
            val now = System.currentTimeMillis()
            val existing = seatSnapshot.toReservation()
            val currentUserSeat = userSnapshot.getString("seatId")

            val previousRef = if (!currentUserSeat.isNullOrBlank() &&
                currentUserSeat != selectedSeatId) seats.document(currentUserSeat) else null
            val previousSnapshot = previousRef?.let { tx.get(it) }
            val previousExpiry = previousSnapshot?.getLong("expiresAt") ?: 0L
            val previousOwnedByCurrentUser = previousSnapshot?.getString("firebaseUid") == firebaseUid

            if (previousRef != null && previousOwnedByCurrentUser && previousExpiry > now) {
                throw IllegalStateException("이미 $currentUserSeat 좌석을 이용 중입니다. 이용 종료 후 다른 좌석을 선택해 주세요.")
            }

            val activeExisting = existing?.takeIf { it.expiresAt > now }
            if (activeExisting != null && activeExisting.studentId != studentId) {
                throw IllegalStateException("$selectedSeatId 좌석은 다른 학생이 이용 중입니다.")
            }

            val service = SeatService(activeExisting?.let(::listOf).orEmpty())
            val result = service.authenticate(
                studentId, studentName, selectedSeatId, scannedSeatId, tagUid,
                expectedVerification
            )

            // 모든 트랜잭션 읽기가 완료된 후에만 쓰기를 적용
            if (previousRef != null && previousOwnedByCurrentUser) tx.delete(previousRef)
            tx.set(seatRef, reservationMap(result, firebaseUid))
            tx.set(userRef, mapOf(
                "studentId" to studentId,
                "seatId" to selectedSeatId,
                "lastVerifiedAt" to result.lastVerifiedAt,
                "expiresAt" to result.expiresAt
            ))
            result
        }
    }

    fun checkout(firebaseUid: String): Task<Void> {
        val userRef = users.document(firebaseUid)
        return db.runTransaction { tx ->
            val userSnapshot = tx.get(userRef)
            val seatId = userSnapshot.getString("seatId")
                ?: throw IllegalStateException("현재 이용 중인 좌석이 없습니다.")
            val seatRef = seats.document(seatId)
            val seatSnapshot = tx.get(seatRef)
            if (seatSnapshot.exists() && seatSnapshot.getString("firebaseUid") == firebaseUid) {
                tx.delete(seatRef)
            }
            tx.delete(userRef)
            null
        }
    }

    /** 이 알람을 예약한 정확한 예약 생성만 유효하게 함. */
    fun expire(seatId: String, firebaseUid: String, verifiedAt: Long): Task<Boolean> {
        val seatRef = seats.document(seatId)
        val userRef = users.document(firebaseUid)
        return db.runTransaction { tx ->
            val seatSnapshot = tx.get(seatRef)
            val userSnapshot = tx.get(userRef)
            val currentVerifiedAt = seatSnapshot.getLong("lastVerifiedAt")
            val expiry = seatSnapshot.getLong("expiresAt") ?: Long.MAX_VALUE
            if (seatSnapshot.getString("firebaseUid") == firebaseUid &&
                currentVerifiedAt == verifiedAt && expiry <= System.currentTimeMillis()) {
                tx.delete(seatRef)
                if (userSnapshot.getString("seatId") == seatId &&
                    userSnapshot.getLong("lastVerifiedAt") == verifiedAt) tx.delete(userRef)
                true
            } else false
        }
    }



    fun getAllActive(): com.google.android.gms.tasks.Task<List<Reservation>> =
        seats.get().continueWith { task ->
            task.result?.documents.orEmpty().mapNotNull { it.toReservation() }
                .filter { it.expiresAt > System.currentTimeMillis() }
        }

    fun getReservation(seatId: String): com.google.android.gms.tasks.Task<Reservation?> =
        seats.document(seatId).get().continueWith { task ->
            val doc = task.result ?: return@continueWith null
            doc.toReservation()?.takeIf { it.expiresAt > System.currentTimeMillis() }
        }


    private fun com.google.firebase.firestore.DocumentSnapshot.toReservation(): Reservation? {
        if (!exists()) return null
        return Reservation(
            seatId = id,
            studentId = getString("studentId") ?: return null,
            studentName = getString("studentName").orEmpty(),
            checkedInAt = getLong("checkedInAt") ?: return null,
            lastVerifiedAt = getLong("lastVerifiedAt") ?: return null,
            expiresAt = getLong("expiresAt") ?: return null,
            tagUid = getString("tagUid") ?: return null
        )
    }

    private fun reservationMap(r: Reservation, firebaseUid: String) = mapOf(
        "firebaseUid" to firebaseUid,
        "studentId" to r.studentId,
        "studentName" to r.studentName,
        "checkedInAt" to r.checkedInAt,
        "lastVerifiedAt" to r.lastVerifiedAt,
        "expiresAt" to r.expiresAt,
        "tagUid" to r.tagUid
    )

    companion object {
        const val SEATS = "seats"
        const val ACTIVE_USERS = "activeUsers"
    }
}

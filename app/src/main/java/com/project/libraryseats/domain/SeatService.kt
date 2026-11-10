package com.project.libraryseats.domain

data class Reservation(
    val seatId: String,
    val studentId: String,
    val studentName: String,
    val checkedInAt: Long,
    val lastVerifiedAt: Long,
    val expiresAt: Long,
    val tagUid: String,
) {
    val reminderAt: Long get() = lastVerifiedAt + SeatService.REMINDER_MILLIS

    fun needsRetag(now: Long): Boolean = now >= reminderAt && now < expiresAt
}

class SeatService(
    initial: List<Reservation> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reservations = linkedMapOf<String, Reservation>()

    init {
        val now = clock()
        initial.forEach { reservation ->
            validateSeat(reservation.seatId)
            require(reservation.studentId.isNotBlank()) { "저장된 사용자 정보가 없습니다." }
            require(reservation.tagUid.isNotBlank()) { "NFC 태그 정보가 없습니다." }
            require(reservation.checkedInAt >= 0 &&
                reservation.checkedInAt <= reservation.lastVerifiedAt &&
                reservation.lastVerifiedAt < reservation.expiresAt &&
                reservation.lastVerifiedAt <= Long.MAX_VALUE - LEASE_MILLIS) {
                "저장된 좌석 이용 시간이 올바르지 않습니다."
            }
            val current = reservation.copy(expiresAt = reservation.lastVerifiedAt + LEASE_MILLIS)
            if (current.expiresAt > now) {
                require(!reservations.containsKey(current.seatId)) {
                    "저장된 좌석 정보에 중복 좌석이 있습니다."
                }
                require(reservations.values.none { it.studentId == current.studentId }) {
                    "한 학생의 좌석 정보가 중복되어 있습니다."
                }
                reservations[current.seatId] = current
            }
        }
    }

    @Synchronized
    fun active(): List<Reservation> {
        expire(clock())
        return reservations.values.sortedBy { it.seatId }
    }

    @Synchronized
    fun authenticate(
        studentId: String,
        studentName: String,
        selectedSeatId: String,
        scannedSeatId: String,
        tagUid: String,
        expectedVerification: Long? = null,
    ): Reservation {
        require(studentId.isNotBlank()) { "사용자 정보가 없습니다." }
        validateSeat(selectedSeatId)
        validateSeat(scannedSeatId)
        require(tagUid.isNotBlank()) { "NFC 태그 정보가 없습니다. 다시 태깅해 주세요." }
        require(selectedSeatId == scannedSeatId) { //좌석과 스티커 번호 같은지
            "선택한 좌석은 $selectedSeatId 입니다. 해당 좌석의 NFC 스티커를 태깅해 주세요."
        }
        val now = clock()
        require(now >= 0 && now <= Long.MAX_VALUE - LEASE_MILLIS) {
            "휴대폰의 날짜와 시간을 확인해 주세요."
        }
        expire(now)
        val studentLease = reservations.values.find { it.studentId == studentId }
        check(studentLease == null || studentLease.seatId == selectedSeatId) {
            "이미 ${studentLease?.seatId} 좌석을 이용 중입니다. 이용 종료 후 다른 좌석을 선택해 주세요."
        }
        val existing = reservations[selectedSeatId]
        check(expectedVerification == null ||
            (existing != null && existing.studentId == studentId &&
                existing.lastVerifiedAt == expectedVerification)) {
            "이용 시간이 만료되었거나 좌석 상태가 변경되었습니다. 좌석을 다시 선택해 주세요."
        }
        check(existing == null || existing.studentId == studentId) {
            "$selectedSeatId 좌석은 다른 학생이 이용 중입니다."
        }
        check(existing == null || now >= existing.lastVerifiedAt) {
            "휴대폰 시간이 이전 인증 시간보다 이릅니다. 날짜와 시간을 확인해 주세요."
        }
        val reservation = Reservation(
            seatId = selectedSeatId,
            studentId = studentId,
            studentName = studentName.trim(),
            checkedInAt = existing?.checkedInAt ?: now,
            lastVerifiedAt = now,
            expiresAt = now + LEASE_MILLIS,
            tagUid = tagUid.trim(),
        )
        reservations[selectedSeatId] = reservation
        return reservation
    }

    @Synchronized
    fun checkout(studentId: String) {
        expire(clock())
        val reservation = reservations.values.find { it.studentId == studentId }
        checkNotNull(reservation) { "현재 이용 중인 좌석이 없습니다." }
        reservations.remove(reservation.seatId)
    }

    private fun expire(now: Long) {
        reservations.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun validateSeat(seatId: String) {
        require(seatId in SEAT_IDS) { "유효한 좌석 번호가 아닙니다." }
    }

    companion object {
        val SEAT_IDS: List<String> = (1..101).map { "A-${it.toString().padStart(2, '0')}" }
        const val REMINDER_MILLIS: Long = 30L * 60L * 1000L
        const val GRACE_MILLIS: Long = 5L * 60L * 1000L
        const val LEASE_MILLIS: Long = REMINDER_MILLIS + GRACE_MILLIS
    }
}

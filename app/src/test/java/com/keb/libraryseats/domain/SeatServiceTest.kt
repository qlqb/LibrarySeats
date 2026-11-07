package com.keb.libraryseats.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatServiceTest {
    private var now = 1_000_000L
    private fun service(initial: List<Reservation> = emptyList()) = SeatService(initial) { now }
    private fun reserve(
        service: SeatService,
        student: String = "20260001",
        selected: String = "A-01",
        scanned: String = selected,
        name: String = "홍길동",
        uid: String = "04AABBCC",
        expectedVerification: Long? = null,
    ) = service.authenticate(student, name, selected, scanned, uid, expectedVerification)

    @Test fun firstTagSchedulesThirtyMinuteReminderAndFiveMinuteGrace() {
        val service = service()
        val result = reserve(service)
        assertEquals(now, result.checkedInAt)
        assertEquals(now, result.lastVerifiedAt)
        assertEquals(now + 30 * 60 * 1000, result.reminderAt)
        assertEquals(now + 35 * 60 * 1000, result.expiresAt)
        assertEquals(listOf(result), service.active())
    }

    @Test fun wrongSeatNeverCreatesReservation() {
        val service = service()
        assertThrows(IllegalArgumentException::class.java) { reserve(service, scanned = "A-02") }
        assertTrue(service.active().isEmpty())
    }

    @Test fun anotherStudentCannotTakeOccupiedSeat() {
        val service = service()
        val original = reserve(service)
        assertThrows(IllegalStateException::class.java) { reserve(service, student = "20260002") }
        assertEquals(listOf(original), service.active())
    }

    @Test fun oneStudentCannotReserveTwoSeats() {
        val service = service()
        val original = reserve(service)
        assertThrows(IllegalStateException::class.java) { reserve(service, selected = "A-02") }
        assertEquals(listOf(original), service.active())
    }

    @Test fun retagExtendsFromNowAndPreservesEntryTime() {
        val service = service()
        val original = reserve(service)
        now += 32 * 60 * 1000
        val renewed = reserve(service)
        assertEquals(original.checkedInAt, renewed.checkedInAt)
        assertEquals(now, renewed.lastVerifiedAt)
        assertEquals(now + SeatService.REMINDER_MILLIS, renewed.reminderAt)
        assertEquals(now + SeatService.LEASE_MILLIS, renewed.expiresAt)
        assertFalse(renewed.needsRetag(now))
        assertEquals(1, service.active().size)
    }

    @Test fun reminderBecomesDueAtThirtyMinutesAndNotBefore() {
        val lease = reserve(service())
        assertFalse(lease.needsRetag(lease.reminderAt - 1))
        assertTrue(lease.needsRetag(lease.reminderAt))
        assertTrue(lease.needsRetag(lease.expiresAt - 1))
        assertFalse(lease.needsRetag(lease.expiresAt))
    }

    @Test fun seatRemainsOccupiedForWholeFiveMinuteGrace() {
        val service = service()
        val lease = reserve(service)
        now = lease.reminderAt
        assertEquals(listOf(lease), service.active())
        assertThrows(IllegalStateException::class.java) { reserve(service, student = "20260002") }
        now = lease.expiresAt - 1
        assertEquals(listOf(lease), service.active())
        assertThrows(IllegalStateException::class.java) { reserve(service, student = "20260002") }
        now++
        assertTrue(service.active().isEmpty())
    }

    @Test fun previousDeadlineDoesNotExpireRenewedSeat() {
        val service = service()
        val initial = reserve(service)
        now = initial.reminderAt + 2 * 60 * 1000
        val renewed = reserve(service)
        now = initial.expiresAt
        assertEquals(listOf(renewed), service.active())
        assertFalse(renewed.needsRetag(now))
        now = renewed.reminderAt
        assertTrue(service.active().single().needsRetag(now))
    }

    @Test fun pendingRenewalAtExactExpiryCannotCreateNewEntry() {
        val service = service()
        val original = reserve(service)
        now = original.expiresAt
        assertThrows(IllegalStateException::class.java) {
            reserve(service, expectedVerification = original.lastVerifiedAt)
        }
        assertTrue(service.active().isEmpty())
    }

    @Test fun staleRenewalGenerationCannotModifyNewerReservation() {
        val service = service()
        val original = reserve(service)
        now = original.reminderAt
        val renewed = reserve(service, expectedVerification = original.lastVerifiedAt)
        now++
        assertThrows(IllegalStateException::class.java) {
            reserve(service, expectedVerification = original.lastVerifiedAt)
        }
        assertEquals(listOf(renewed), service.active())
    }

    @Test fun matchingPendingRenewalSucceedsUntilEndOfGrace() {
        val service = service()
        val original = reserve(service)
        now = original.expiresAt - 1
        val renewed = reserve(service, expectedVerification = original.lastVerifiedAt)
        assertEquals(original.checkedInAt, renewed.checkedInAt)
        assertEquals(now, renewed.lastVerifiedAt)
        assertEquals(now + SeatService.REMINDER_MILLIS, renewed.reminderAt)
        assertEquals(now + SeatService.LEASE_MILLIS, renewed.expiresAt)
        assertEquals(listOf(renewed), service.active())
    }

    @Test fun restoreMigratesOldFiftyMinuteLeaseWithoutResettingAuthentication() {
        val original = reserve(service())
        val legacy = original.copy(expiresAt = original.lastVerifiedAt + 50 * 60 * 1000)
        now = original.reminderAt + 60 * 1000
        val restored = service(listOf(legacy)).active().single()
        assertEquals(original, restored)
        assertTrue(restored.needsRetag(now))
        now = original.expiresAt
        assertTrue(service(listOf(legacy)).active().isEmpty())
    }

    @Test fun restoreRejectsTimestampsThatOverflowNewDeadline() {
        val original = reserve(service())
        val corrupt = original.copy(
            lastVerifiedAt = Long.MAX_VALUE - SeatService.LEASE_MILLIS + 1,
            expiresAt = Long.MAX_VALUE,
        )
        assertThrows(IllegalArgumentException::class.java) { service(listOf(corrupt)) }
    }

    @Test fun leaseIsActiveUntilExactExpiryBoundary() {
        val service = service()
        val original = reserve(service)
        now = original.expiresAt - 1
        assertEquals(1, service.active().size)
        now++
        assertTrue(service.active().isEmpty())
    }

    @Test fun expiredSeatCanBeReservedByAnotherStudentWithoutRefresh() {
        val service = service()
        val original = reserve(service)
        now = original.expiresAt
        val replacement = reserve(service, student = "20260002")
        assertEquals("20260002", replacement.studentId)
        assertEquals(now, replacement.checkedInAt)
        assertEquals(listOf(replacement), service.active())
    }

    @Test fun ownerRetagAfterExpiryIsNewEntry() {
        val service = service()
        val original = reserve(service)
        now = original.expiresAt
        assertNotEquals(original.checkedInAt, reserve(service).checkedInAt)
    }

    @Test fun checkoutRemovesOnlyCurrentStudentSeat() {
        val service = service()
        reserve(service)
        val other = reserve(service, student = "20260002", selected = "A-02")
        service.checkout("20260001")
        assertEquals(listOf(other), service.active())
    }

    @Test fun unknownStudentCannotCheckoutSomeoneElse() {
        val service = service()
        val original = reserve(service)
        assertThrows(IllegalStateException::class.java) { service.checkout("20260002") }
        assertEquals(listOf(original), service.active())
    }

    @Test fun restorePreservesOwnershipAndDropsExpiredLeases() {
        val source = service()
        val old = reserve(source)
        now += 10
        val current = reserve(source, student = "20260002", selected = "A-02")
        now = old.expiresAt
        val restored = service(listOf(old, current))
        assertEquals(listOf(current), restored.active())
        assertThrows(IllegalStateException::class.java) { reserve(restored, selected = "A-02") }
    }

    @Test fun restoreRejectsDuplicateSeatsAndDuplicateStudents() {
        val original = reserve(service())
        assertThrows(IllegalArgumentException::class.java) {
            service(listOf(original, original.copy(studentId = "20260002")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service(listOf(original, original.copy(seatId = "A-02")))
        }
    }

    @Test fun unsupportedSeatIsRejected() {
        val service = service()
        listOf("A-00", "A-102", "A-1", "", "a-01").forEach { seat ->
            assertThrows(IllegalArgumentException::class.java) { reserve(service, selected = seat) }
        }
        assertTrue(service.active().isEmpty())
    }

    @Test fun userIdWithoutStudentNumberFormatCanAuthenticateRestoreAndCheckout() {
        val userId = "campus-user-123"
        val reservation = reserve(service(), student = userId, name = "")
        val restored = service(listOf(reservation))
        assertEquals(listOf(reservation), restored.active())
        restored.checkout(userId)
        assertTrue(restored.active().isEmpty())
    }

    @Test fun emptyIdentityOrTagIsRejected() {
        val service = service()
        assertThrows(IllegalArgumentException::class.java) { reserve(service, student = "  ") }
        assertThrows(IllegalArgumentException::class.java) { reserve(service, uid = "  ") }
        assertTrue(service.active().isEmpty())
    }

    @Test fun callerCannotMutateReturnedSnapshotToReleaseSeat() {
        val service = service()
        reserve(service)
        val snapshot = service.active().toMutableList()
        snapshot.clear()
        assertFalse(service.active().isEmpty())
    }

    @Test fun clockRollbackDoesNotCreateInconsistentRenewal() {
        val service = service()
        val original = reserve(service)
        now--
        assertThrows(IllegalStateException::class.java) { reserve(service) }
        assertEquals(listOf(original), service.active())
    }
}

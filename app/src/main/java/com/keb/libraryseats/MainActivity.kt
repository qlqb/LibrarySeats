package com.keb.libraryseats

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.keb.libraryseats.databinding.ActivityMainBinding
import com.keb.libraryseats.domain.Reservation
import com.keb.libraryseats.domain.SeatService
import com.keb.libraryseats.nfc.NfcSeatManager
import com.keb.libraryseats.seatstate.LocalSeatStore
import com.keb.libraryseats.seatstate.SeatAlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LocalSeatStore
    private lateinit var alarms: SeatAlarmScheduler
    private lateinit var nfc: NfcSeatManager
    private var active: List<Reservation> = emptyList()
    private var storageError: String? = null
    private var selectedSeat: String? = null
    private var renewalAt: Long? = null
    private var scanDialog: AlertDialog? = null
    private var reminderDialog: AlertDialog? = null
    private val seatViews = linkedMapOf<Int, View>()
    private val handler = Handler(Looper.getMainLooper())
    private val demoStudent = "20260001"
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            showReminder()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "DMU 도서관 좌석"
        store = LocalSeatStore(this)
        if (store.user() == null) store.login(demoStudent, "실습 사용자")
        alarms = SeatAlarmScheduler(this)
        nfc = NfcSeatManager(this,
            onRead = { seat, uid -> onTagRead(seat, uid) },
            onError = { error ->
                cancelScan()
                message("NFC 확인", "$error\n다시 버튼을 누르고 스티커를 떼었다가 대주세요.")
            })
        for (seatId in SeatService.SEAT_IDS) {
            val number = seatNumber(seatId)
            val id = resources.getIdentifier("seatBox$number", "id", packageName)
            val seat = findViewById<View>(id) ?: continue //못찾으면 건너뜀
            seatViews[number] = seat
            seat.setOnClickListener {
                beginAuthentication(seatId, mine()?.takeIf { it.seatId == seatId }?.lastVerifiedAt)
            } //lastVerifiedAt 마지막 인증 성공 시간
        }
        binding.nfcScan.setOnClickListener {
            val current = mine()
            if (current != null) beginAuthentication(current.seatId, current.lastVerifiedAt)
        }
        binding.EndOfUse.setOnClickListener {
            val current = mine() ?: return@setOnClickListener
            AlertDialog.Builder(this).setTitle("${seatNumber(current.seatId)}번 좌석 퇴실")
                .setMessage("이용을 종료하시겠습니까?")
                .setNegativeButton("닫기", null)
                .setPositiveButton("이용 종료") { _, _ ->
                    changeReservation { it.checkout(studentId()) }
                        .onSuccess { message("퇴실 완료", "좌석이 이용 가능 상태로 변경되었습니다.") }
                        .onFailure { message("퇴실 확인", it.message ?: "다시 시도해 주세요.") }
                }.show()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
        }
    }

    override fun onResume() {
        super.onResume()
        nfc.resume()
        refresh()
        if (storageError == null) alarms.sync(active, force = true)
        handleNotification()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        reminderDialog?.dismiss()
        reminderDialog = null
        cancelScan()
        nfc.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nfc.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun studentId() = store.user()?.first ?: demoStudent
    private fun mine() = active.firstOrNull { it.studentId == studentId() }
    private fun seatNumber(id: String) = id.substringAfter('-').toInt()
    private fun time(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(at))

    private fun refresh() {
        val previous = mine()
        try {
            val saved = store.load()
            val current = SeatService(saved).active()
            if (saved != current) store.save(current)
            active = current
            storageError = null
            alarms.sync(active)
            if (previous != null && previous.expiresAt <= System.currentTimeMillis() && mine() == null) {
                if (selectedSeat == previous.seatId) cancelScan()
                reminderDialog?.dismiss()
                reminderDialog = null
                message("자동 퇴실", "5분 안에 재태깅하지 않아 ${seatNumber(previous.seatId)}번 좌석 이용이 종료되었습니다.")
            }
        } catch (error: Exception) {
            storageError = error.message ?: "좌석 정보를 불러오지 못했습니다."
        }
        render()
    }

    private fun render() {
        val current = mine()
        for ((number, view) in seatViews) {
            val occupied = active.any { seatNumber(it.seatId) == number }
            view.setBackgroundColor(if (occupied) getColor(R.color.use_not_available) else Color.TRANSPARENT)
        }
        binding.textclock.text = current?.let {
            val format = SimpleDateFormat("HH:mm", Locale.KOREA)
            format.timeZone = TimeZone.getTimeZone("Asia/Seoul")
            format.format(Date(it.lastVerifiedAt))
        }
        binding.textclock.visibility = if (current == null) View.GONE else View.VISIBLE
        binding.nfcScan.visibility = if (current == null) View.GONE else View.VISIBLE
        binding.EndOfUse.visibility = if (current == null) View.GONE else View.VISIBLE
    }

    private fun beginAuthentication(seatId: String, expectedVerification: Long? = null) {
        refresh() //expectedVerification 이전 인증 시간
        storageError?.let { message("저장 오류", it); return }
        val current = mine()
        if (expectedVerification != null && (current?.seatId != seatId || current.lastVerifiedAt != expectedVerification)) {
            message("이용 시간 종료", "좌석 상태가 변경되었습니다. 좌석을 다시 선택해 주세요.")
            return
        }
        if (current != null && current.seatId != seatId) {
            message("이용 중인 좌석", "현재 좌석을 이용 종료한 후 다른 좌석을 선택해 주세요.")
            return
        }
        if (active.any { it.seatId == seatId && it.studentId != studentId() }) {
            message("사용 중인 좌석", "다른 이용자가 사용 중입니다.")
            return
        }
        if (!checkNfc()) return
        cancelScan()
        selectedSeat = seatId
        renewalAt = current?.lastVerifiedAt // 마지막 인증 시간
        showScan("${seatNumber(seatId)}번 좌석", "NFC를 인식해 주세요.\n이 좌석의 $seatId 스티커에 휴대폰 뒷면을 대주세요.")
        nfc.startReading()
    }

    private fun onTagRead(scannedSeat: String, uid: String) {
        val expected = selectedSeat //고른번호
        val expectedVerification = renewalAt
        cancelScan()
        if (expected == null) return
        changeReservation { service ->
            service.authenticate(studentId(), store.user()?.second ?: "실습 사용자", expected,
                scannedSeat, uid, expectedVerification)
        }.onSuccess {
            message("NFC 인증 완료", "${seatNumber(expected)}번 좌석 이용이 시작되거나 갱신되었습니다.\n30분 후 알림이 나오면 5분 안에 다시 태깅해 주세요.")
        }.onFailure { message("좌석 인증 실패", it.message ?: "다시 태깅해 주세요.") }
    }

    private fun <T> changeReservation(action: (SeatService) -> T): Result<T> {
        return runCatching {
            val service = SeatService(store.load())
            val result = action(service)
            store.save(service.active())
            result
        }.also { refresh() }
    }
    // 이용기록 저장

    private fun checkNfc(): Boolean {
        if (!nfc.available) {
            message("NFC 미지원", "NFC 지원 휴대폰에서 이용해 주세요.")
            return false
        }
        if (!nfc.enabled) {
            AlertDialog.Builder(this).setTitle("NFC 켜기").setMessage("휴대폰에서 NFC 기본 모드를 켜주세요.")
                .setNegativeButton("닫기", null).setPositiveButton("설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }.show()
            return false
        }
        return true
    }

    private fun showScan(title: String, text: String) {
        scanDialog = AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setNegativeButton("취소") { _, _ -> cancelScan() }
            .setOnCancelListener { cancelScan() }.create().also {
                it.setCanceledOnTouchOutside(false)
                it.show()
            }
    }

    private fun cancelScan() {
        nfc.stop()
        selectedSeat = null
        renewalAt = null
        scanDialog?.dismiss()
        scanDialog = null
    }

    private fun showReminder() {
        val current = mine() ?: return
        if (storageError != null || !current.needsRetag(System.currentTimeMillis())) return
        alarms.remind(current)
        if (scanDialog != null || reminderDialog != null || store.wasReminderShown(current)) return
        store.markReminderShown(current)
        reminderDialog = AlertDialog.Builder(this).setTitle("NFC 재태깅 알림")
            .setMessage("30분이 지났습니다. ${time(current.expiresAt)}까지 재태깅하지 않으면 자동 퇴실됩니다.")
            .setNegativeButton("닫기", null)
            .setPositiveButton("NFC 태깅") { _, _ -> beginAuthentication(current.seatId, current.lastVerifiedAt) }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (reminderDialog === dialog) reminderDialog = null }
                dialog.show()
            }
    }

    private fun handleNotification() {
        if (intent.action != SeatAlarmScheduler.ACTION_RETAG) return
        val seat = intent.getStringExtra(SeatAlarmScheduler.EXTRA_SEAT_ID)
        val student = intent.getStringExtra(SeatAlarmScheduler.EXTRA_STUDENT_ID)
        val verification = intent.getLongExtra(SeatAlarmScheduler.EXTRA_VERIFIED_AT, -1L)
        intent.action = null
        val current = mine()
        if (current == null || current.seatId != seat || current.studentId != student || current.lastVerifiedAt != verification) {
            message("좌석 상태 확인", "이 알림의 이용 시간이 끝났거나 좌석 상태가 변경되었습니다.")
            return
        }
        store.markReminderShown(current)
        beginAuthentication(current.seatId, current.lastVerifiedAt)
    }

    private fun message(title: String, text: String) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("확인", null).show()
        }
    }
}

package com.project.libraryseats.nfc

import android.app.Activity
import android.nfc.FormatException
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class NfcSeatManager(
    private val activity: Activity,
    private val onRead: (seatId: String, tagUid: String) -> Unit,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var generation = 0L
    private var reading = false
    private var resumed = false
    private var closed = false
    private var busy = false
    private var lastUid = ""
    private var lastScanAt = 0L

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun startReading() {
        synchronized(lock) {
            if (closed) return
            generation++  //읽기 요청 번호 (예전 요청에서 늦게 도착한 결과 구분)
            reading = true
            busy = false
            lastUid = ""
            lastScanAt = 0L
        }
        refreshReader()
    }

    fun resume() {
        synchronized(lock) {
            if (closed) return
            resumed = true
        }
        refreshReader()
    }

    fun pause() {
        synchronized(lock) {
            resumed = false
            generation++
            busy = false
        }
        refreshReader()
    }

    fun stop() {
        synchronized(lock) {
            generation++
            reading = false
            busy = false
        }
        refreshReader()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            resumed = false
            reading = false
            generation++
            busy = false
        }
        refreshReader()
        io.shutdownNow()
    }

    private fun refreshReader() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshReader() }
            return
        }
        val (token, shouldRead) = synchronized(lock) { generation to (resumed && !closed && reading) }
        if (adapter == null) {
            if (shouldRead) deliverError(token, "이 휴대폰은 NFC를 지원하지 않습니다. NFC 지원 안드로이드 휴대폰을 사용해 주세요.")
            return
        }
        try {
            adapter.disableReaderMode(activity)
            if (!shouldRead) return
            if (!adapter.isEnabled) {
                deliverError(token, "NFC가 꺼져 있습니다. 휴대폰 설정에서 NFC를 켜고 다시 태깅해 주세요.")
                return
            }
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            adapter.enableReaderMode(activity, { tag -> discover(tag, token) }, flags,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
        } catch (_: SecurityException) {
            deliverError(token, "NFC 접근 권한을 확인해 주세요. 앱에 NFC 권한이 필요합니다.")
        } catch (_: IllegalStateException) {
            deliverError(token, "NFC를 시작하지 못했습니다. 앱 화면으로 돌아와 다시 시도해 주세요.")
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) {
        token == generation && resumed && !closed && reading
    }

    private fun discover(tag: Tag, token: Long) {
        val uid = tag.id.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        synchronized(lock) {
            if (!isCurrent(token) || busy) return
            val now = SystemClock.elapsedRealtime()
            if (uid == lastUid && now - lastScanAt < 1200L) return
            lastUid = uid
            lastScanAt = now
            busy = true
        }
        try {
            io.execute {
                if (!isCurrent(token)) return@execute
                try {
                    val seatId = read(tag)
                    deliverSuccess(token) { onRead(seatId, uid) }
                } catch (_: TagLostException) {
                    deliverError(token, "스티커에서 휴대폰이 떨어졌습니다. 완료될 때까지 가까이 대고 다시 시도해 주세요.")
                } catch (_: FormatException) {
                    deliverError(token, "NFC 데이터 형식을 읽을 수 없습니다. 다른 스티커로 다시 시도해 주세요.")
                } catch (e: IllegalArgumentException) {
                    deliverError(token, e.message ?: "올바른 좌석 스티커가 아닙니다.")
                } catch (_: IOException) {
                    deliverError(token, "NFC 통신에 실패했습니다. 스티커를 떼었다가 다시 대 주세요.")
                } catch (_: SecurityException) {
                    deliverError(token, "NFC 태그 연결이 만료되었습니다. 스티커를 떼었다가 다시 대 주세요.")
                } catch (_: IllegalStateException) {
                    deliverError(token, "NFC 연결이 종료되었습니다. 스티커를 떼었다가 다시 시도해 주세요.")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun read(tag: Tag): String {
        val ndef = Ndef.get(tag) // 스티커에 Ndef형식으로 접근 가능한지 확인
            ?: throw IllegalArgumentException("좌석 정보가 없거나 지원하지 않는 스티커입니다. 해당 좌석에 등록된 스티커를 대 주세요.")
        try {
            ndef.connect()
            val payload = ndef.ndefMessage?.records?.firstOrNull()?.payload // 저장된 내용 가져옴
                ?: throw IllegalArgumentException("빈 nfc입니다. 해당 좌석에 등록된 스티커를 대 주세요.")
            return payload.toString(Charsets.US_ASCII).removePrefix("dmu-seat:v1:")
            //스티커에서 좌석번호 꺼냄
        } finally {
            try { ndef.close() } catch (_: IOException) { }
        }
    }

    private fun deliverSuccess(token: Long, callback: () -> Unit) {
        main.post {
            synchronized(lock) {
                if (!isCurrent(token)) return@post
                reading = false
                generation++
                busy = false
            }
            refreshReader()
            if (!activity.isFinishing && !activity.isDestroyed) callback()
        }
    }

    private fun deliverError(token: Long, message: String) {
        main.post {
            synchronized(lock) {
                if (!isCurrent(token)) return@post
                busy = false
            }
            if (!activity.isFinishing && !activity.isDestroyed) onError(message)
        }
    }
}

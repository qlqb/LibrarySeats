package com.project.libraryseats

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : AppCompatActivity() {

    private val channelId = "seat_notification"
    private var seatListener: ListenerRegistration? = null

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                showNotification()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        createNotificationChannel()

        findViewById<Button>(R.id.notificationButton).setOnClickListener {
            scheduleNotification()
        }

        findViewById<Button>(R.id.nfcNotificationButton).setOnClickListener {
            showNfcNotification()
        }

        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                val uid = it.user?.uid ?: return@addOnSuccessListener

                Log.d("FIREBASE_AUTH", "익명 로그인 성공: $uid")

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w("FCM_TOKEN", "토큰 가져오기 실패", task.exception)
                        return@addOnCompleteListener
                    }

                    val token = task.result
                    Log.d("FCM_TOKEN", token)

                    val userData = hashMapOf(
                        "studentId" to "student001",
                        "fcmToken" to token
                    )

                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Log.d("FIRESTORE", "FCM 토큰 저장 성공")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FIRESTORE", "FCM 토큰 저장 실패", e)
                        }
                }
            }
            .addOnFailureListener {
                Log.e("FIREBASE_AUTH", "익명 로그인 실패", it)
            }


        FirebaseFirestore.getInstance()
            .collection("seats")
            .document("A-01")
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val status = document.getString("status")
                    val userId = document.getString("userId")

                    Log.d("SEAT_TEST", "좌석: A-01")
                    Log.d("SEAT_TEST", "상태: $status")
                    Log.d("SEAT_TEST", "사용자 UID: $userId")
                } else {
                    Log.d("SEAT_TEST", "좌석 정보가 없습니다.")
                }
            }
            .addOnFailureListener { e ->
                Log.e("SEAT_TEST", "좌석 정보 읽기 실패", e)
            }


        seatListener = FirebaseFirestore.getInstance()
            .collection("seats")
            .document("A-01")
            .addSnapshotListener { document, error ->
                if (error != null) {
                    Log.e("NFC_EVENT", "좌석 감지 실패", error)
                    return@addSnapshotListener
                }

                if (document != null && document.exists()) {
                    val status = document.getString("status")
                    val userId = document.getString("userId")
                    val nfcEvent = document.getBoolean("nfcEvent") ?: false
                    val currentUid = FirebaseAuth.getInstance().currentUser?.uid

                    Log.d("NFC_EVENT", "좌석 상태: $status, NFC 이벤트: $nfcEvent")

                    val prefs = getSharedPreferences("nfc_notification", MODE_PRIVATE)
                    val eventHandled = prefs.getBoolean("A-01_handled", false)

                    if (!nfcEvent) {
                        prefs.edit().putBoolean("A-01_handled", false).apply()
                    }

                    if (status == "occupied" &&
                        nfcEvent &&
                        userId == currentUid &&
                        !eventHandled
                    ) {
                        showNfcNotification()
                        prefs.edit().putBoolean("A-01_handled", true).apply()
                        Log.d("NFC_EVENT", "NFC 알림 표시 완료")
                    }
                }
            }

            }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "도서관 좌석 알림",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("도서관 좌석 알림")
            .setContentText("좌석 이용 시간이 곧 만료됩니다. NFC 재인증을 진행해 주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(1, notification)
        }
    }

    private fun showNfcNotification() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("도서관 좌석 이용 확인")
            .setContentText("다른 학생이 현재 이용 중인 좌석을 NFC 태깅했습니다. 계속 이용 중인지 확인해 주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(2, notification)
        }
    }

    private fun cancelNotification() {
        val intent = Intent(this, NotificationReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun scheduleNotification() {
        val intent = Intent(this, NotificationReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 30 * 60 * 1000L,
            pendingIntent
        )
    }

    override fun onDestroy() {
        seatListener?.remove()
        super.onDestroy()
    }
}

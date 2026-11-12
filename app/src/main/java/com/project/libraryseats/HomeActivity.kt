package com.project.libraryseats

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.project.libraryseats.seatstate.LocalSeatStore
import android.util.Log

/** 로그인 후 화면 (추후 열람실 좌석 화면으로 확장) */
class HomeActivity : AppCompatActivity() {

    private val auth = Firebase.auth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = auth.currentUser

        // 로그인 안 했거나 메일 인증 전이면 들어올 수 없음
        if (user == null || !user.isEmailVerified) {
            goLogin()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_home)
        applySystemBarInsets(findViewById(R.id.main), findViewById(R.id.content))

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)
        val tvStudentId = findViewById<TextView>(R.id.tvStudentId)
        tvWelcome.text = getString(R.string.welcome, user.displayName ?: user.email)
        tvStudentId.text = user.email

        var studentId: String? = null
        var studentName: String? = user.displayName
        val btnOpenSeats = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenSeats)
        btnOpenSeats.isEnabled = false

        Firebase.firestore.collection(AuthRules.USERS_COLLECTION)
            .document(user.uid)
            .get()
            .addOnSuccessListener { doc ->

                Log.d("HOME_DEBUG", "문서 존재 여부: ${doc.exists()}")
                Log.d("HOME_DEBUG", "로그인 UID: ${user.uid}")
                Log.d("HOME_DEBUG", "문서 데이터: ${doc.data}")

                studentName = doc.getString("name") ?: user.displayName
                studentId = doc.getString("studentId")

                btnOpenSeats.isEnabled =
                    !studentId.isNullOrBlank() &&
                            !studentName.isNullOrBlank()

                Log.d("HOME_DEBUG", "좌석 버튼 활성화: ${btnOpenSeats.isEnabled}")
            }
            .addOnFailureListener { e ->

                Log.e("HOME_DEBUG", "Firestore 사용자 정보 조회 실패", e)

                tvStudentId.text = "사용자 정보를 불러오지 못했습니다."
            }

        btnOpenSeats.setOnClickListener {
            val id = studentId
            val name = studentName
            if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                startActivity(Intent(this, SeatActivity::class.java).apply {
                    putExtra(SeatActivity.EXTRA_STUDENT_ID, id)
                    putExtra(SeatActivity.EXTRA_STUDENT_NAME, name)
                })
            }
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            LocalSeatStore(this).logout()
            auth.signOut()
            goLogin()
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

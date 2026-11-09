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

        // Firestore에 저장한 학번/이름 불러오기
        Firebase.firestore.collection(AuthRules.USERS_COLLECTION).document(user.uid).get()
            .addOnSuccessListener { doc ->
                doc.getString("name")?.let { tvWelcome.text = getString(R.string.welcome, it) }
                doc.getString("studentId")?.let { tvStudentId.text = getString(R.string.student_info, it) }
            }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            auth.signOut()
            goLogin()
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

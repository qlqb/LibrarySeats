package com.project.libraryseats

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/** 학생 인증(로그인) 화면: 학교 메일 + 비밀번호 */
class MainActivity : AppCompatActivity() {

    private val auth = Firebase.auth

    private lateinit var etEmailId: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 이미 로그인된 상태면 인증 여부에 따라 바로 이동
        auth.currentUser?.let { user ->
            goTo(if (user.isEmailVerified) HomeActivity::class.java else VerifyEmailActivity::class.java)
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.main), findViewById(R.id.content))

        etEmailId = findViewById(R.id.etEmailId)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)

        findViewById<TextView>(R.id.tvEmailDomain).text = "@${AuthRules.EMAIL_DOMAIN}"

        btnLogin.setOnClickListener { attemptLogin() }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin(); true
            } else false
        }
        findViewById<Button>(R.id.btnGoSignUp).setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun attemptLogin() {
        val emailId = AuthRules.normalizeEmailId(etEmailId.text.toString())
        val password = etPassword.text.toString()

        etEmailId.error = null
        etPassword.error = null

        when {
            !AuthRules.isValidEmailId(emailId) -> {
                etEmailId.error = getString(R.string.error_email_id)
                etEmailId.requestFocus()
                return
            }
            password.isEmpty() -> {
                etPassword.error = getString(R.string.error_password)
                etPassword.requestFocus()
                return
            }
        }

        hideKeyboard()
        setLoading(true)
        auth.signInWithEmailAndPassword(AuthRules.toEmail(emailId), password)
            .addOnSuccessListener { result ->
                setLoading(false)
                val user = result.user ?: return@addOnSuccessListener
                if (user.isEmailVerified) {
                    Toast.makeText(this, getString(R.string.welcome, user.displayName ?: emailId), Toast.LENGTH_SHORT).show()
                    goTo(HomeActivity::class.java)
                } else {
                    // 가입은 했지만 메일 인증을 안 한 경우
                    goTo(VerifyEmailActivity::class.java)
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, authErrorMessage(e), Toast.LENGTH_LONG).show()
            }
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled = !loading
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target))
        finish() // 뒤로가기 시 로그인 화면으로 돌아오지 않도록
    }
}

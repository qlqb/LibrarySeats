package com.project.libraryseats

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

/** 이메일 인증 대기 화면: 메일 링크를 누른 뒤 '인증 완료'를 누르면 확인 후 홈으로 */
class VerifyEmailActivity : AppCompatActivity() {

    private val auth = Firebase.auth

    private lateinit var btnVerified: Button
    private lateinit var btnResend: Button
    private lateinit var progress: ProgressBar
    private var cooldown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = auth.currentUser
        if (user == null) {
            goTo(MainActivity::class.java)
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_verify_email)
        applySystemBarInsets(findViewById(R.id.main), findViewById(R.id.content))

        btnVerified = findViewById(R.id.btnVerified)
        btnResend = findViewById(R.id.btnResend)
        progress = findViewById(R.id.progress)

        findViewById<TextView>(R.id.tvMessage).text = getString(R.string.verify_message, user.email)

        btnVerified.setOnClickListener { checkVerified(showNotYet = true) }
        btnResend.setOnClickListener { resend() }
        findViewById<Button>(R.id.btnOtherAccount).setOnClickListener {
            auth.signOut()
            goTo(MainActivity::class.java)
        }

        // 방금 메일을 보냈으니 바로 재발송은 잠시 막는다
        startCooldown()
    }

    override fun onResume() {
        super.onResume()
        // 메일 앱에서 링크를 누르고 돌아오면 자동으로 확인
        if (auth.currentUser != null && ::btnVerified.isInitialized) checkVerified(showNotYet = false)
    }

    private fun checkVerified(showNotYet: Boolean) {
        val user = auth.currentUser ?: return goTo(MainActivity::class.java)
        setLoading(true)
        user.reload().addOnCompleteListener { task ->
            setLoading(false)
            when {
                !task.isSuccessful ->
                    if (showNotYet) Toast.makeText(this, authErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                auth.currentUser?.isEmailVerified == true -> {
                    Toast.makeText(this, R.string.verify_done, Toast.LENGTH_SHORT).show()
                    goTo(HomeActivity::class.java)
                }
                showNotYet -> Toast.makeText(this, R.string.verify_not_yet, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resend() {
        val user = auth.currentUser ?: return goTo(MainActivity::class.java)
        setLoading(true)
        auth.setLanguageCode("ko")
        user.sendEmailVerification().addOnCompleteListener { task ->
            setLoading(false)
            if (task.isSuccessful) {
                Toast.makeText(this, R.string.verify_sent, Toast.LENGTH_SHORT).show()
                startCooldown()
            } else {
                Toast.makeText(this, authErrorMessage(task.exception), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCooldown() {
        cooldown?.cancel()
        btnResend.isEnabled = false
        cooldown = object : CountDownTimer(RESEND_COOLDOWN_MS, 1000) {
            override fun onTick(msLeft: Long) {
                btnResend.text = getString(R.string.btn_resend_wait, (msLeft / 1000 + 1).toInt())
            }

            override fun onFinish() {
                btnResend.text = getString(R.string.btn_resend)
                btnResend.isEnabled = true
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        btnVerified.isEnabled = !loading
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onDestroy() {
        cooldown?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val RESEND_COOLDOWN_MS = 60_000L
    }
}

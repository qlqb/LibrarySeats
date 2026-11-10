package com.project.libraryseats

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore

/** 회원가입 화면: 학교 메일(@dongyang.ac.kr 고정)로 계정을 만들고 인증 메일을 보낸다 */
class SignUpActivity : AppCompatActivity() {

    private val auth = Firebase.auth

    private lateinit var etStudentId: EditText
    private lateinit var etName: EditText
    private lateinit var etEmailId: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var btnSignUp: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_signup)
        applySystemBarInsets(findViewById(R.id.main), findViewById(R.id.content))

        etStudentId = findViewById(R.id.etStudentId)
        etName = findViewById(R.id.etName)
        etEmailId = findViewById(R.id.etEmailId)
        etPassword = findViewById(R.id.etPassword)
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm)
        btnSignUp = findViewById(R.id.btnSignUp)
        progress = findViewById(R.id.progress)

        findViewById<TextView>(R.id.tvEmailDomain).text = "@${AuthRules.EMAIL_DOMAIN}"

        btnSignUp.setOnClickListener { attemptSignUp() }
        etPasswordConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSignUp(); true
            } else false
        }
        findViewById<Button>(R.id.btnGoLogin).setOnClickListener { finish() }
    }

    private fun attemptSignUp() {
        val studentId = etStudentId.text.toString().trim()
        val name = etName.text.toString().trim()
        val emailId = AuthRules.normalizeEmailId(etEmailId.text.toString())
        val password = etPassword.text.toString()
        val passwordConfirm = etPasswordConfirm.text.toString()

        listOf(etStudentId, etName, etEmailId, etPassword, etPasswordConfirm).forEach { it.error = null }

        val invalid: Pair<EditText, Int>? = when {
            !AuthRules.isValidStudentId(studentId) -> etStudentId to R.string.error_student_id
            !AuthRules.isValidName(name) -> etName to R.string.error_name
            !AuthRules.isValidEmailId(emailId) -> etEmailId to R.string.error_email_id
            !AuthRules.isValidPassword(password) -> etPassword to R.string.error_password
            password != passwordConfirm -> etPasswordConfirm to R.string.error_password_confirm
            else -> null
        }
        if (invalid != null) {
            invalid.first.error = getString(invalid.second)
            invalid.first.requestFocus()
            return
        }

        hideKeyboard()
        setLoading(true)
        val email = AuthRules.toEmail(emailId)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                saveProfile(user, studentId, name, email)
                sendVerificationAndContinue(user)
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, authErrorMessage(e), Toast.LENGTH_LONG).show()
            }
    }

    /** 이름은 Firebase 계정 프로필에, 학번 등은 Firestore users/{uid}에 저장 */
    private fun saveProfile(user: FirebaseUser, studentId: String, name: String, email: String) {
        user.updateProfile(userProfileChangeRequest { displayName = name })

        val profile = mapOf(
            "studentId" to studentId,
            "name" to name,
            "email" to email,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        Firebase.firestore.collection(AuthRules.USERS_COLLECTION).document(user.uid)
            .set(profile)
            .addOnFailureListener { e -> Log.w(TAG, "프로필 저장 실패", e) }
    }

    private fun sendVerificationAndContinue(user: FirebaseUser) {
        auth.setLanguageCode("ko") // 인증 메일을 한국어로
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                setLoading(false)
                if (!task.isSuccessful) {
                    // 계정은 만들어졌으니 인증 화면에서 다시 보내기를 누르면 된다
                    Toast.makeText(this, authErrorMessage(task.exception), Toast.LENGTH_LONG).show()
                }
                startActivity(
                    Intent(this, VerifyEmailActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
    }

    private fun setLoading(loading: Boolean) {
        btnSignUp.isEnabled = !loading
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "SignUpActivity"
    }
}

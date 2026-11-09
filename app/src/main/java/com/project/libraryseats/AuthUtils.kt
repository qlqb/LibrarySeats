package com.project.libraryseats

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

/** 회원가입/로그인 공통 규칙 */
object AuthRules {
    /** 회원가입·로그인 이메일의 고정 도메인. 바꾸려면 여기만 수정하세요. */
    const val EMAIL_DOMAIN = "dongyang.ac.kr"
    const val MIN_PASSWORD_LENGTH = 8

    /** Firestore 사용자 정보 컬렉션 */
    const val USERS_COLLECTION = "users"

    fun toEmail(emailId: String): String = "${emailId.trim().lowercase()}@$EMAIL_DOMAIN"

    /** 사용자가 실수로 '@...'까지 입력해도 아이디 부분만 쓴다 */
    fun normalizeEmailId(input: String): String = input.trim().substringBefore('@')

    fun isValidEmailId(id: String): Boolean = id.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))
    fun isValidStudentId(id: String): Boolean = id.matches(Regex("^\\d{8}$"))
    fun isValidName(name: String): Boolean = name.isNotBlank()
    fun isValidPassword(pw: String): Boolean = pw.length >= MIN_PASSWORD_LENGTH
}

/** Firebase 예외를 사용자에게 보여줄 한국어 메시지로 바꾼다 */
fun Context.authErrorMessage(e: Exception?): String = when (e) {
    is FirebaseAuthUserCollisionException -> getString(R.string.error_email_in_use)
    is FirebaseAuthWeakPasswordException -> getString(R.string.error_weak_password)
    is FirebaseAuthInvalidCredentialsException,
    is FirebaseAuthInvalidUserException -> getString(R.string.error_wrong_credentials)
    is FirebaseTooManyRequestsException -> getString(R.string.error_too_many_requests)
    is FirebaseNetworkException -> getString(R.string.error_network)
    else -> getString(R.string.error_unknown, e?.localizedMessage ?: "-")
}

fun Activity.hideKeyboard() {
    val imm = getSystemService(InputMethodManager::class.java)
    currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
}

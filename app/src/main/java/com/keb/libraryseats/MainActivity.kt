package com.keb.libraryseats

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import android.view.View
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.keb.libraryseats.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isSeatOccupied = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        title = "DMU 도서관 좌석"
        //선택 좌석 값 저장 변수
        var selectedSeatNum = 0
        // 선택 좌석 저장 변수
        var selectedSeat: View? = null
        // 좌석 문자열 배열 변수
        var seatNum = Array<String>(101){""}



        //좌석 for문
        for (i in 0..100) {
            seatNum[i] = "seatBox${i + 1}"
            val viewId = "seatBox${i + 1}"
            val seatId = resources.getIdentifier(viewId, "id", packageName)
            val seat = findViewById<View>(seatId)

            // 좌석 클릭 람다식
            seat.setOnClickListener {

                // 시간 코드
                val now = System.currentTimeMillis()
                val sdfTime = SimpleDateFormat("HH:mm", Locale.KOREA)
                sdfTime.timeZone = TimeZone.getTimeZone("Asia/Seoul")
                val currentTime = sdfTime.format(Date(now)).toString()

                // AlertDialog
                //this: 현재 객체를 의미.
                //this@MainActivity: 현재 코드가 실행되는 MainActivity 객체를 명확하게 지정하는 표현.
                val nfcDlg = AlertDialog.Builder(this@MainActivity)

                nfcDlg.setTitle("${i+1}번 좌석")
                nfcDlg.setMessage("NFC를 인식해 주세요")
                nfcDlg.setNegativeButton("닫기", null)

                //임시 nfc 성공 버튼
                nfcDlg.setPositiveButton("임시 nfc 성공 버튼",{_,_-> // 매개변수 사용 안 해서 _ 로 대체
                    toast("${i + 1}번 좌석 인식 완료")
                    binding.textclock.text = currentTime
                    binding.textclock.visibility = View.VISIBLE
                    binding.nfcScan.visibility = View.VISIBLE
                    binding.EndOfUse.visibility = View.VISIBLE
                    seat.setBackgroundColor(getColor(R.color.use_not_available))

                    // 선택 좌석 이용 처리 된 후 모든 좌석 클릭 비활성화
                    for(j in 1..101) {
                        val viewId = "seatBox$j"
                        val seatId = resources.getIdentifier(viewId, "id", packageName)
                        val allSeats = findViewById<View>(seatId)
                        allSeats.isEnabled = false
                    }//for
                    selectedSeatNum = i+1
                    selectedSeat = seat
                }) //nfcDlg.setPositiveButton
                nfcDlg.show()
            }//seat.setOnClickListener
        }//for


        // 이용 종료 버튼 클릭 이벤트
        binding.EndOfUse.setOnClickListener {
            val nfcDlg = AlertDialog.Builder(this@MainActivity)

            nfcDlg.setTitle("${selectedSeatNum}번 좌석")
            nfcDlg.setMessage("퇴실하시겠습니까?")
            nfcDlg.setNegativeButton("닫기", null)
            nfcDlg.setPositiveButton("이용 종료",{_,_->
                binding.textclock.visibility = View.GONE
                binding.nfcScan.visibility = View.GONE
                binding.EndOfUse.visibility = View.GONE
                selectedSeat?.setBackgroundColor(Color.TRANSPARENT)
                for(j in 0..100) {
                    val viewId = "seatBox${j+1}"
                    val seatId = resources.getIdentifier(viewId, "id", packageName)
                    val allSeats = findViewById<View>(seatId)
                    allSeats.isEnabled = true
                }//for
                toast("퇴실 처리 완료")
            })//nfcDlg.setPositiveButton
            nfcDlg.show()

        }//binding.EndOfUse.seOnClickListener

        // 이용된 후 nfc 인식 버튼(아직 미작업)








        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
            }
    }
    // 토스트 함수
    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

}
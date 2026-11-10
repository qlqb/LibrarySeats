package com.project.libraryseats

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 상태바 영역은 root(남색)가, 하단 내비게이션바/키보드 영역은 content(흰색)가 여백을 갖게 한다.
 */
fun applySystemBarInsets(root: View, content: View) {
    val contentBottom = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
        content.updatePadding(bottom = contentBottom + maxOf(bars.bottom, ime.bottom))
        insets
    }
}

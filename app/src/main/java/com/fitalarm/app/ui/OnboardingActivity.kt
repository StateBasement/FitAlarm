package com.fitalarm.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fitalarm.app.R

/**
 * 첫 실행 화면: 운동 안전 안내, 알람의 한계, 개인정보 요약을 보여주고 이용약관 동의를 받음.
 * 약관 내용이 크게 바뀌면 TERMS_VERSION 을 올려 다시 동의를 받습니다.
 */
class OnboardingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        setupEdgeToEdge()
        setContentView(build())
    }

    private fun build(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }
        val content = vbox { setPadding(dp(20), dp(28), dp(20), dp(16)) }

        content.addView(FrameLayout(this).apply {
            background = oval(Palette.ACCENT_SOFT)
            addView(icon(R.drawable.ic_sun, Palette.ACCENT, 32), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }, lp(dp(64), dp(64)))
        content.addView(label("시작하기 전에", 28f, Palette.TEXT, Fonts.BOLD), lp().margins(t = dp(16)))
        content.addView(label("안전하게 쓰실 수 있도록 꼭 알아두실 내용이에요.", 15f, Palette.TEXT_DIM), lp().margins(t = dp(6), b = dp(18)))

        content.addView(section(
            R.drawable.ic_flame, Palette.WARM, "운동 안전",
            listOf(
                "건강 상태에 맞게 운동 종류와 횟수를 정하세요.",
                "심장·관절 질환이 있거나 임신·부상 중이라면 의사와 상의한 뒤 사용하세요.",
                "통증이나 어지러움이 느껴지면 바로 멈추세요. 힘든 날은 운동 미션 없는 알람을 쓰세요.",
                "주변에 부딪힐 물건이 없는지, 바닥이 미끄럽지 않은지 확인하세요.",
                "이 앱은 의료 조언이나 운동 처방을 하지 않아요.",
            ),
        ), lp())
        content.addView(section(
            R.drawable.ic_alarm, Palette.ACCENT, "알람 안내",
            listOf(
                "폰이 꺼져 있거나 배터리 절전·방해 금지 설정, 권한을 허용하지 않은 경우 알람이 울리지 않거나 늦을 수 있어요.",
                "시험·출국처럼 중요한 일정에는 다른 알람도 함께 맞춰 두세요.",
            ),
        ), lp().margins(t = dp(12)))
        content.addView(section(
            R.drawable.ic_check_circle, Palette.ON_SKY, "개인정보",
            listOf(
                "회원가입이 없고, 카메라 영상과 운동 기록은 이 기기 안에서만 처리돼요.",
                "개발자는 개인정보를 수집하지 않아요. 동작 인식 기능(Google ML Kit)이 기기 정보 등 진단 정보를 Google로 보내요.",
            ),
        ), lp().margins(t = dp(12)))

        content.addView(hbox {
            gravity = Gravity.CENTER
            addView(link("이용약관 전체 보기", TERMS_URL))
            addView(label("·", 14f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(l = dp(8), r = dp(8)))
            addView(link("개인정보처리방침", SettingsPage.PRIVACY_POLICY_URL))
        }, lp().margins(t = dp(16)))

        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))

        val bar = vbox { setPadding(dp(20), dp(10), dp(20), dp(14)) }
        bar.addView(label("아래 버튼을 누르면 위 내용과 이용약관에 동의하는 것으로 봐요.", 12.5f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }, lp().margins(b = dp(10)))
        bar.addView(pillButton("동의하고 시작하기", filled = true, heightDp = 56) { accept() }, lp())
        root.addView(bar, lp())

        root.applySystemBarPadding()
        return root
    }

    private fun section(iconRes: Int, tint: Int, title: String, items: List<String>): LinearLayout = card {
        addView(hbox {
            addView(icon(iconRes, tint, 20), lp(dp(20), dp(20)).margins(r = dp(8)))
            addView(label(title, 16f, Palette.TEXT, Fonts.BOLD))
        }, lp().margins(t = dp(2), b = dp(8)))
        for (t in items) {
            addView(hbox {
                gravity = Gravity.TOP
                addView(label("•", 14f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(r = dp(8)))
                addView(label(t, 14f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.3f) }, lp(0, WRAP, 1f))
            }, lp().margins(b = dp(6)))
        }
    }

    private fun link(text: String, url: String): View = label(text, 14f, Palette.ACCENT, Fonts.MEDIUM).apply {
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = rippleOnly(10)
        isClickable = true
        setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                toast("브라우저를 열 수 없어요. $url")
            }
        }
    }

    private fun accept() {
        markAccepted(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        /** 약관을 크게 바꾸면 올려서 다시 동의를 받음 */
        const val TERMS_VERSION = 1
        const val TERMS_URL = "https://github.com/StateBasement/FitAlarm/blob/main/docs/terms-of-service.md"
        private const val KEY_TERMS_VERSION = "terms_version"
        private const val KEY_TERMS_AT = "terms_accepted_at"

        fun isAccepted(ctx: Context): Boolean =
            ctx.getSharedPreferences(MainActivity.UI_PREFS, MODE_PRIVATE).getInt(KEY_TERMS_VERSION, 0) >= TERMS_VERSION

        private fun markAccepted(ctx: Context) {
            ctx.getSharedPreferences(MainActivity.UI_PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_TERMS_VERSION, TERMS_VERSION)
                .putLong(KEY_TERMS_AT, System.currentTimeMillis())
                .apply()
        }
    }
}

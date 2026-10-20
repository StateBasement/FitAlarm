package com.fitalarm.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.fitalarm.app.alarm.AlarmScheduler
import com.fitalarm.app.core.Alarm
import com.fitalarm.app.core.AlarmMath
import com.fitalarm.app.core.Days
import com.fitalarm.app.core.ExerciseType
import com.fitalarm.app.core.RoutineCodec
import com.fitalarm.app.core.RoutineStep
import com.fitalarm.app.core.VibrationPattern
import com.fitalarm.app.core.describe
import com.fitalarm.app.data.AlarmRepository
import com.fitalarm.app.defaultVibrator
import java.time.ZonedDateTime
import java.util.Locale

/** 알람 추가/편집 화면: 시간, 반복 요일, 이름, 알람음, 볼륨, 진동, 다시 알림, 울림 시간, 운동 미션 */
class AlarmEditActivity : Activity() {

    private lateinit var draft: Alarm
    private var isNew = true
    private val use24 by lazy { DateFormat.is24HourFormat(this) }

    // 운동 루틴: 선택한 순서 + 운동별 횟수
    private val order = ArrayList<ExerciseType>()
    private val reps = HashMap<ExerciseType, Int>()

    private var previewPlayer: MediaPlayer? = null

    // ---- 뷰 ----
    private lateinit var ampmPicker: NumberPicker
    private lateinit var hourPicker: NumberPicker
    private lateinit var minutePicker: NumberPicker
    private lateinit var remainingText: TextView
    private val dayViews = ArrayList<TextView>(7)
    private lateinit var labelEdit: EditText
    private lateinit var soundValue: TextView
    private lateinit var previewButton: TextView
    private lateinit var volumeValue: TextView
    private lateinit var vibrationRows: List<View>
    private lateinit var vibrationPatternValue: TextView
    private lateinit var snoozeRows: List<View>
    private lateinit var snoozeIntervalValue: TextView
    private lateinit var snoozeLimitValue: TextView
    private lateinit var ringValue: TextView
    private lateinit var missionBody: LinearLayout
    private lateinit var routineSummary: TextView
    private val exerciseRows = LinkedHashMap<ExerciseType, ExerciseRow>()

    private class ExerciseRow(
        val badge: TextView,
        val stepper: View,
        val countText: TextView,
        val addHint: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        val id = intent.getIntExtra(EXTRA_ALARM_ID, 0)
        val existing = if (id > 0) AlarmRepository.get(this, id) else null
        isNew = existing == null
        draft = existing ?: Alarm(id = 0, hour = 7, minute = 0)
        draft.routine.forEach {
            order.add(it.type)
            reps[it.type] = it.reps
        }
        setContentView(buildUi())
        renderAll()
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }

    // ================================================================== 화면 구성

    private fun buildUi(): View {
        val root = vbox { setBackgroundColor(Palette.BG) }

        val header = hbox { setPadding(dp(20), dp(12), dp(12), dp(4)) }
        header.addView(label(if (isNew) "알람 추가" else "알람 편집", 22f, Palette.TEXT, Fonts.BOLD))
        header.addView(spacer())
        if (!isNew) {
            header.addView(label("삭제", 15f, Palette.DANGER, Fonts.MEDIUM).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rippleOnly(20)
                setOnClickListener { confirmDelete() }
            })
        }
        root.addView(header, lp())

        val content = vbox { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        content.addView(buildTimeCard(), cardLp())
        content.addView(buildRepeatCard(), cardLp())
        content.addView(buildSoundCard(), cardLp())
        content.addView(buildVibrationCard(), cardLp())
        content.addView(buildSnoozeCard(), cardLp())
        content.addView(buildMissionCard(), cardLp())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, lp(MATCH, 0, 1f))

        val bar = hbox { setPadding(dp(16), dp(10), dp(16), dp(12)) }
        bar.addView(pillButton("취소", filled = false) { finish() }, lp(0, WRAP, 1f).margins(r = dp(6)))
        bar.addView(pillButton("저장", filled = true) { save() }, lp(0, WRAP, 1f).margins(l = dp(6)))
        root.addView(bar, lp())

        root.applySystemBarPadding(includeIme = true)
        return root
    }

    private fun cardLp() = lp().margins(b = dp(12))

    private fun card(title: String?, block: LinearLayout.() -> Unit): LinearLayout = vbox {
        setPadding(dp(18), dp(16), dp(18), dp(12))
        background = rounded(Palette.SURFACE, dpf(22))
        if (title != null) addView(label(title, 13f, Palette.TEXT_DIM, Fonts.MEDIUM), lp().margins(b = dp(6)))
        block()
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Palette.OUTLINE)
        layoutParams = lp(MATCH, dp(1)).margins(t = dp(4), b = dp(4))
    }

    /** 제목(+설명) 왼쪽, 값 오른쪽, 탭하면 onClick */
    private fun valueRow(title: String, subtitle: String? = null, onClick: () -> Unit): Pair<View, TextView> {
        val value = label("", 15f, Palette.ACCENT, Fonts.MEDIUM)
        val row = hbox {
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = rippleOnly(12)
            isClickable = true
            setOnClickListener { onClick() }
            addView(vbox {
                addView(label(title, 16f))
                if (subtitle != null) addView(label(subtitle, 12.5f, Palette.TEXT_DIM), lp().margins(t = dp(4)))
            }, lp(0, WRAP, 1f))
            addView(value, lp(WRAP, WRAP).margins(l = dp(12)))
        }
        return row to value
    }

    private fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        return hbox {
            setPadding(dp(4), dp(10), 0, dp(10))
            background = rippleOnly(12)
            isClickable = true
            setOnClickListener { sw.toggle() }
            addView(vbox {
                addView(label(title, 16f))
                if (subtitle != null) {
                    addView(label(subtitle, 12.5f, Palette.TEXT_DIM).apply { setLineSpacing(0f, 1.2f) }, lp().margins(t = dp(4)))
                }
            }, lp(0, WRAP, 1f))
            addView(sw, lp(WRAP, WRAP).margins(l = dp(12)))
        }
    }

    // ------------------------------------------------------------------ 시간

    private fun buildTimeCard(): View = card(null) {
        gravity = Gravity.CENTER_HORIZONTAL
        val pickers = hbox { gravity = Gravity.CENTER }

        ampmPicker = NumberPicker(this@AlarmEditActivity).apply {
            minValue = 0
            maxValue = 1
            displayedValues = arrayOf("오전", "오후")
            wrapSelectorWheel = false
        }
        hourPicker = NumberPicker(this@AlarmEditActivity).apply {
            if (use24) {
                minValue = 0
                maxValue = 23
                displayedValues = Array(24) { String.format(Locale.ROOT, "%02d", it) }
            } else {
                minValue = 1
                maxValue = 12
                displayedValues = Array(12) { (it + 1).toString() }
            }
            wrapSelectorWheel = true
        }
        minutePicker = NumberPicker(this@AlarmEditActivity).apply {
            minValue = 0
            maxValue = 59
            displayedValues = Array(60) { String.format(Locale.ROOT, "%02d", it) }
            wrapSelectorWheel = true
        }
        listOf(ampmPicker, hourPicker, minutePicker).forEach { p ->
            p.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS   // 탭해도 키보드가 뜨지 않게
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                p.textColor = Palette.TEXT
                p.textSize = dpf(30)
            }
            p.setOnValueChangedListener { _, _, _ -> onTimeChanged() }
        }

        if (!use24) pickers.addView(ampmPicker, lp(WRAP, WRAP).margins(r = dp(18)))
        pickers.addView(hourPicker, lp(WRAP, WRAP))
        pickers.addView(label(":", 30f, Palette.TEXT_DIM, Fonts.LIGHT), lp(WRAP, WRAP).margins(l = dp(10), r = dp(10)))
        pickers.addView(minutePicker, lp(WRAP, WRAP))
        addView(pickers, lp(WRAP, WRAP))

        remainingText = label("", 14f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }
        addView(remainingText, lp().margins(t = dp(8), b = dp(6)))
    }

    private fun setPickersFromDraft() {
        if (use24) {
            hourPicker.value = draft.hour
        } else {
            ampmPicker.value = if (draft.hour < 12) 0 else 1
            hourPicker.value = if (draft.hour % 12 == 0) 12 else draft.hour % 12
        }
        minutePicker.value = draft.minute
    }

    private fun onTimeChanged() {
        val hour24 = if (use24) hourPicker.value
        else (hourPicker.value % 12) + if (ampmPicker.value == 1) 12 else 0
        draft = draft.copy(hour = hour24, minute = minutePicker.value)
        updateRemaining()
    }

    private fun updateRemaining() {
        val now = ZonedDateTime.now()
        val next = AlarmMath.nextOccurrence(draft.hour, draft.minute, draft.days, now)
        remainingText.text = AlarmMath.describeRemaining(
            now.toInstant().toEpochMilli(), next.toInstant().toEpochMilli()
        ) + " 후에 울립니다"
    }

    // ------------------------------------------------------------------ 반복 요일 + 이름

    private fun buildRepeatCard(): View = card("반복") {
        val row = hbox()
        Days.SHORT_LABELS.forEachIndexed { i, s ->
            val cell = FrameLayout(this@AlarmEditActivity)
            val v = label(s, 15f, Palette.TEXT_DIM, Fonts.MEDIUM).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    draft = draft.copy(days = Days.toggle(draft.days, i))
                    renderDays()
                }
            }
            dayViews.add(v)
            cell.addView(v, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            row.addView(cell, lp(0, dp(44), 1f))
        }
        addView(row, lp())

        val presets = hbox()
        listOf("매일" to Days.EVERYDAY, "주중" to Days.WEEKDAYS, "주말" to Days.WEEKEND, "반복 안 함" to Days.NONE)
            .forEach { (name, mask) ->
                presets.addView(label(name, 13f, Palette.TEXT_DIM, Fonts.MEDIUM).apply {
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = ripple(rounded(Palette.SURFACE_HI, dpf(14)))
                    setOnClickListener {
                        draft = draft.copy(days = mask)
                        renderDays()
                    }
                }, lp(WRAP, WRAP).margins(r = dp(8)))
            }
        addView(presets, lp().margins(t = dp(10), b = dp(12)))

        labelEdit = EditText(this@AlarmEditActivity).apply {
            setText(draft.label)
            hint = "알람 이름 (선택)"
            setHintTextColor(Palette.TEXT_FAINT)
            setTextColor(Palette.TEXT)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Palette.SURFACE_HI, dpf(14))
        }
        addView(labelEdit, lp().margins(b = dp(6)))
    }

    private fun renderDays() {
        dayViews.forEachIndexed { i, v ->
            val on = Days.isSet(draft.days, i)
            v.background = if (on) oval(Palette.ACCENT) else oval(Palette.SURFACE_HI)
            v.setTextColor(if (on) Palette.ON_ACCENT else Palette.TEXT_DIM)
        }
        updateRemaining()
    }

    // ------------------------------------------------------------------ 소리

    private fun buildSoundCard(): View = card("소리") {
        val (soundRow, sv) = valueRow("알람음") { showSoundPicker() }
        soundValue = sv
        addView(soundRow, lp())

        val volumeHeader = hbox {
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(label("볼륨", 16f), lp(0, WRAP, 1f))
            volumeValue = label("", 15f, Palette.ACCENT, Fonts.MEDIUM)
            addView(volumeValue)
        }
        addView(volumeHeader, lp())
        val seek = SeekBar(this@AlarmEditActivity).apply {
            min = 5
            max = 100
            progress = draft.volume.coerceIn(5, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    draft = draft.copy(volume = value)
                    volumeValue.text = "$value%"
                    previewPlayer?.setVolume(value / 100f, value / 100f)
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        addView(seek, lp().margins(t = dp(6), b = dp(6)))

        addView(switchRow("점점 크게", "30초에 걸쳐 작은 소리에서 설정한 볼륨까지 커집니다", draft.crescendo) {
            draft = draft.copy(crescendo = it)
        }, lp())

        previewButton = pillButton("▶  미리 듣기", filled = false, heightDp = 44) { togglePreview() }
        addView(previewButton, lp().margins(t = dp(8), b = dp(4)))
    }

    private fun renderSound() {
        soundValue.text = if (draft.silent) "무음 (진동만)" else draft.soundTitle
        volumeValue.text = "${draft.volume}%"
    }

    private fun showSoundPicker() {
        stopPreview()
        val items = arrayOf("기본 알람음", "알람음 목록에서 선택", "내 음악 파일 선택", "무음 (진동만)")
        AlertDialog.Builder(this)
            .setTitle("알람음")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> draft = draft.copy(soundUri = null, soundTitle = Alarm.DEFAULT_SOUND_TITLE, silent = false)
                    1 -> pickSystemRingtone()
                    2 -> pickMusicFile()
                    3 -> draft = draft.copy(silent = true)
                }
                renderSound()
            }
            .show()
    }

    private fun pickSystemRingtone() {
        val existing: Uri? = draft.soundUri?.let { Uri.parse(it) }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "알람음 선택")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        }
        try {
            startActivityForResult(intent, REQ_RINGTONE)
        } catch (e: Exception) {
            toast("알람음 목록을 열 수 없습니다")
        }
    }

    private fun pickMusicFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        try {
            startActivityForResult(intent, REQ_MUSIC)
        } catch (e: Exception) {
            toast("파일 선택기를 열 수 없습니다")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_RINGTONE -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                draft = if (uri == null || uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) {
                    draft.copy(soundUri = null, soundTitle = Alarm.DEFAULT_SOUND_TITLE, silent = false)
                } else {
                    val title = try {
                        RingtoneManager.getRingtone(this, uri)?.getTitle(this)
                    } catch (e: Exception) {
                        null
                    } ?: "알람음"
                    draft.copy(soundUri = uri.toString(), soundTitle = title, silent = false)
                }
            }
            REQ_MUSIC -> {
                val uri = data.data ?: return
                try {
                    // 재부팅 후에도 이 파일을 읽을 수 있도록 영구 읽기 권한 보관
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // 일부 제공자는 영구 권한을 지원하지 않음 → 재생 실패 시 기본 알람음으로 대체됨
                }
                draft = draft.copy(soundUri = uri.toString(), soundTitle = displayName(uri) ?: "내 음악", silent = false)
            }
        }
        renderSound()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (e: Exception) {
        null
    }

    private fun togglePreview() {
        if (previewPlayer != null) {
            stopPreview()
            return
        }
        if (draft.silent) {
            toast("무음(진동만)으로 설정되어 있어요")
            return
        }
        val uri = draft.soundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            val v = draft.volume / 100f
            mp.setVolume(v, v)
            mp.start()
            previewPlayer = mp
            previewButton.text = "■  정지"
        } catch (e: Exception) {
            mp.release()
            toast("이 알람음을 재생할 수 없습니다")
        }
    }

    private fun stopPreview() {
        previewPlayer?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // 무시
            }
            it.release()
        }
        previewPlayer = null
        if (::previewButton.isInitialized) previewButton.text = "▶  미리 듣기"
    }

    // ------------------------------------------------------------------ 진동

    private fun buildVibrationCard(): View = card("진동") {
        val vibSwitch = switchRow("진동", null, draft.vibrate) {
            draft = draft.copy(vibrate = it)
            renderVibration()
        }
        addView(vibSwitch, lp())
        val (patternRow, pv) = valueRow("진동 패턴") { showPatternPicker() }
        vibrationPatternValue = pv
        addView(patternRow, lp())
        vibrationRows = listOf(patternRow)
    }

    private fun renderVibration() {
        vibrationPatternValue.text = draft.vibrationPattern.title
        vibrationRows.forEach {
            it.isEnabled = draft.vibrate
            it.alpha = if (draft.vibrate) 1f else 0.4f
        }
    }

    private fun showPatternPicker() {
        val patterns = VibrationPattern.entries
        AlertDialog.Builder(this)
            .setTitle("진동 패턴")
            .setSingleChoiceItems(patterns.map { it.title }.toTypedArray(), patterns.indexOf(draft.vibrationPattern)) { d, which ->
                draft = draft.copy(vibrationPattern = patterns[which])
                previewVibration(patterns[which])
                renderVibration()
                d.dismiss()
            }
            .show()
    }

    private fun previewVibration(p: VibrationPattern) {
        val v = defaultVibrator()
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(p.timings, -1))
    }

    // ------------------------------------------------------------------ 다시 알림 / 울림 시간

    private fun buildSnoozeCard(): View = card("다시 알림") {
        addView(switchRow("다시 알림", "알람을 잠시 미뤘다가 다시 울리게 합니다", draft.snoozeEnabled) {
            draft = draft.copy(snoozeEnabled = it)
            renderSnooze()
        }, lp())
        val (intervalRow, iv) = valueRow("반복 주기") {
            chooseFrom("다시 알림 주기", Alarm.SNOOZE_MINUTE_OPTIONS, draft.snoozeMinutes, { "${it}분" }) {
                draft = draft.copy(snoozeMinutes = it)
                renderSnooze()
            }
        }
        snoozeIntervalValue = iv
        addView(intervalRow, lp())
        val (limitRow, lv) = valueRow("반복 횟수") {
            chooseFrom("다시 알림 횟수", Alarm.SNOOZE_LIMIT_OPTIONS, draft.snoozeLimit, { Alarm.describeSnoozeLimit(it) }) {
                draft = draft.copy(snoozeLimit = it)
                renderSnooze()
            }
        }
        snoozeLimitValue = lv
        addView(limitRow, lp())
        snoozeRows = listOf(intervalRow, limitRow)

        addView(divider())
        val (ringRow, rv) = valueRow("울림 지속 시간", "이 시간 안에 끄지 않으면 자동으로 다시 알림 (남은 횟수가 없으면 종료)") {
            chooseFrom("울림 지속 시간", Alarm.RING_MINUTE_OPTIONS, draft.ringMinutes, { Alarm.describeRingMinutes(it) }) {
                draft = draft.copy(ringMinutes = it)
                renderSnooze()
            }
        }
        ringValue = rv
        addView(ringRow, lp())
    }

    private fun renderSnooze() {
        snoozeIntervalValue.text = "${draft.snoozeMinutes}분"
        snoozeLimitValue.text = Alarm.describeSnoozeLimit(draft.snoozeLimit)
        ringValue.text = Alarm.describeRingMinutes(draft.ringMinutes)
        snoozeRows.forEach {
            it.isEnabled = draft.snoozeEnabled
            it.alpha = if (draft.snoozeEnabled) 1f else 0.4f
        }
    }

    private fun <T> chooseFrom(title: String, options: List<T>, current: T, describe: (T) -> String, onPick: (T) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.map(describe).toTypedArray(), options.indexOf(current)) { d, which ->
                onPick(options[which])
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ 운동 미션

    private fun buildMissionCard(): View = card("운동 미션") {
        addView(switchRow(
            "운동해야 알람 끄기",
            "알람이 울리면 카메라가 동작을 인식하고, 목표 횟수를 채워야 알람이 꺼집니다",
            draft.missionEnabled,
        ) { on ->
            draft = draft.copy(missionEnabled = on)
            if (on) ensureCameraPermission()
            renderMission()
        }, lp())

        missionBody = vbox()
        missionBody.addView(
            label("고른 순서대로 진행돼요. 횟수는 − / + 로 조절하고, 길게 누르면 5회씩 바뀝니다.", 12.5f, Palette.TEXT_DIM).apply {
                setLineSpacing(0f, 1.2f)
            },
            lp().margins(t = dp(4), b = dp(6)),
        )
        for (type in ExerciseType.entries) {
            missionBody.addView(buildExerciseRow(type), lp())
        }
        routineSummary = label("", 14f, Palette.TEXT, Fonts.MEDIUM).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Palette.ACCENT_SOFT, dpf(14))
            setLineSpacing(0f, 1.2f)
        }
        missionBody.addView(routineSummary, lp().margins(t = dp(10)))
        missionBody.addView(pillButton("이 루틴 미리 연습하기", filled = false, heightDp = 44) { practiceRoutine() },
            lp().margins(t = dp(10), b = dp(4)))
        addView(missionBody, lp())
    }

    private fun buildExerciseRow(type: ExerciseType): View {
        val badge = label("", 13f, Palette.ON_ACCENT, Fonts.BOLD).apply { gravity = Gravity.CENTER }
        val countText = label("", 17f, Palette.TEXT, Fonts.MEDIUM).apply {
            gravity = Gravity.CENTER
            minWidth = dp(44)
        }
        val minus = stepButton("−") { changeReps(type, -1) }.apply {
            setOnLongClickListener { changeReps(type, -5); true }
        }
        val plus = stepButton("+") { changeReps(type, +1) }.apply {
            setOnLongClickListener { changeReps(type, +5); true }
        }
        val stepper = hbox {
            addView(minus, lp(dp(36), dp(36)))
            addView(countText, lp(WRAP, WRAP))
            addView(plus, lp(dp(36), dp(36)))
        }
        val addHint = label("추가", 13f, Palette.TEXT_FAINT, Fonts.MEDIUM)

        val row = hbox {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = rippleOnly(14)
            isClickable = true
            setOnClickListener { toggleExercise(type) }
            addView(badge, lp(dp(28), dp(28)).margins(r = dp(12)))
            addView(vbox {
                addView(label(type.title, 16f))
                addView(label(type.motionGuide, 12f, Palette.TEXT_DIM), lp().margins(t = dp(3)))
            }, lp(0, WRAP, 1f))
            addView(stepper, lp(WRAP, WRAP).margins(l = dp(8)))
            addView(addHint, lp(WRAP, WRAP).margins(l = dp(8), r = dp(8)))
        }
        exerciseRows[type] = ExerciseRow(badge, stepper, countText, addHint)
        return row
    }

    private fun stepButton(text: String, onClick: () -> Unit): TextView =
        label(text, 18f, Palette.TEXT, Fonts.MEDIUM).apply {
            gravity = Gravity.CENTER
            background = ripple(oval(Palette.SURFACE_HI), mask = oval(Color.WHITE))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toggleExercise(type: ExerciseType) {
        if (order.contains(type)) order.remove(type) else {
            order.add(type)
            if (!reps.containsKey(type)) reps[type] = type.defaultReps
        }
        renderMission()
    }

    private fun changeReps(type: ExerciseType, delta: Int) {
        val now = reps[type] ?: type.defaultReps
        reps[type] = (now + delta).coerceIn(RoutineStep.MIN_REPS, RoutineStep.MAX_REPS)
        renderMission()
    }

    private fun currentRoutine(): List<RoutineStep> = order.map { RoutineStep(it, reps[it] ?: it.defaultReps) }

    private fun renderMission() {
        missionBody.visibility = if (draft.missionEnabled) View.VISIBLE else View.GONE
        for ((type, row) in exerciseRows) {
            val idx = order.indexOf(type)
            val selected = idx >= 0
            row.badge.text = if (selected) (idx + 1).toString() else ""
            row.badge.background = if (selected) oval(Palette.ACCENT) else oval(Color.TRANSPARENT, Palette.OUTLINE, dp(1.5f))
            row.stepper.visibility = if (selected) View.VISIBLE else View.GONE
            row.addHint.visibility = if (selected) View.GONE else View.VISIBLE
            row.countText.text = "${reps[type] ?: type.defaultReps}회"
        }
        val routine = currentRoutine()
        routineSummary.text = if (routine.isEmpty()) "운동을 하나 이상 선택하세요"
        else "오늘의 루틴\n" + routine.describe() + "  (총 ${routine.sumOf { it.reps }}회)"
    }

    private fun practiceRoutine() {
        val routine = currentRoutine()
        if (routine.isEmpty()) {
            toast("먼저 운동을 선택하세요")
            return
        }
        startActivity(
            Intent(this, MissionActivity::class.java)
                .putExtra(MissionActivity.EXTRA_PRACTICE_ROUTINE, RoutineCodec.encode(routine))
        )
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences(MainActivity.UI_PREFS, MODE_PRIVATE).edit()
                .putBoolean(MainActivity.KEY_CAMERA_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("카메라 권한이 없으면 운동 미션을 사용할 수 없어요")
        }
    }

    // ------------------------------------------------------------------ 저장 / 삭제

    private fun renderAll() {
        setPickersFromDraft()
        renderDays()
        renderSound()
        renderVibration()
        renderSnooze()
        renderMission()
    }

    private fun save() {
        onTimeChanged()
        val routine = currentRoutine()
        if (draft.missionEnabled && routine.isEmpty()) {
            toast("운동 미션에 사용할 운동을 하나 이상 선택해 주세요")
            return
        }
        val toSave = draft.copy(
            label = labelEdit.text.toString().trim(),
            routine = routine,
            enabled = true,
            snoozeCount = 0,
            snoozedUntil = 0L,
        )
        val saved = AlarmRepository.upsert(this, toSave)
        val at = AlarmScheduler(this).schedule(saved)
        if (at != null) {
            toast("알람이 " + AlarmMath.describeRemaining(System.currentTimeMillis(), at) + " 후에 울립니다")
        }
        if (saved.hasMission && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("카메라 권한을 허용해야 운동 미션이 동작해요")
        }
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("알람 삭제")
            .setMessage("이 알람을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                AlarmScheduler(this).cancel(draft.id)
                AlarmRepository.delete(this, draft.id)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        private const val REQ_RINGTONE = 11
        private const val REQ_MUSIC = 12
        private const val REQ_CAMERA = 13
    }
}

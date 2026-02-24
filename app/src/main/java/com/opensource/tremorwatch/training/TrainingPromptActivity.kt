package com.opensource.tremorwatch.training

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.opensource.tremorwatch.shared.models.FeedbackLabel
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch-side Activity that asks the user "Was that a tremor?"
 *
 * Lifecycle hardening (v2.0):
 * - [P5] Nullable vibrationManager (no lateinit crash)
 * - [P6] AtomicBoolean guard prevents double-fire from timer vs. button race
 * - [P7] onDestroy auto-timeout if user swiped away without responding
 * - [P9] Modern screen wake APIs (setTurnScreenOn/setShowWhenLocked)
 */
class TrainingPromptActivity : Activity() {

    private var sampleId: String? = null
    private var timer: CountDownTimer? = null
    // [P5] Nullable instead of lateinit — prevents UninitializedPropertyAccessException
    // if onCreate exits early (null SAMPLE_ID) and onDestroy calls cancel()
    private var vibrationManager: VibrationPromptManager? = null
    // [P6] AtomicBoolean guard — prevents double-fire from button tap racing timer finish.
    // Both respond() and onFinish() use compareAndSet to ensure only one executes.
    private val handled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [P9] Modern screen wake APIs (API 27+, all supported Wear OS 3+ devices)
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sampleId = intent.getStringExtra("SAMPLE_ID")
        if (sampleId == null) {
            Timber.e("TrainingPromptActivity launched without SAMPLE_ID")
            finish()
            return
        }

        vibrationManager = VibrationPromptManager(this).also { it.prompt() }

        buildUI()
        startTimeout()
    }

    private fun buildUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            gravity = android.view.Gravity.CENTER
        }

        val question = TextView(this).apply {
            text = "Was that a tremor?"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(question)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        val yesBtn = Button(this).apply {
            text = "✓ Yes"
            setOnClickListener { respond(FeedbackLabel.YES_TREMOR) }
        }
        val noBtn = Button(this).apply {
            text = "✗ No"
            setOnClickListener { respond(FeedbackLabel.NO_ACTIVE) }
        }
        val skipBtn = Button(this).apply {
            text = "— Skip"
            setOnClickListener { respond(FeedbackLabel.IGNORE) }
        }

        buttonRow.addView(yesBtn)
        buttonRow.addView(noBtn)
        buttonRow.addView(skipBtn)
        layout.addView(buttonRow)

        setContentView(layout)
    }

    // [P6] AtomicBoolean ensures only ONE of respond() or onFinish() executes
    private fun respond(label: FeedbackLabel) {
        if (!handled.compareAndSet(false, true)) return
        timer?.cancel()
        timer = null
        (application as? TrainingAwareApplication)?.trainingManager
            ?.onUserFeedback(requireNotNull(sampleId), label)
        finish()
    }

    private fun startTimeout() {
        timer = object : CountDownTimer(TrainingManager.PROMPT_TIMEOUT_MS, 1000L) {
            override fun onTick(ms: Long) { /* Could update UI countdown */ }
            override fun onFinish() {
                // [P6] Guard against post-onDestroy handler message delivery
                if (!handled.compareAndSet(false, true)) return
                (application as? TrainingAwareApplication)?.trainingManager
                    ?.onPromptTimeout(requireNotNull(sampleId))
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        // [P7] If user swiped away without responding, treat as timeout
        if (handled.compareAndSet(false, true) && sampleId != null) {
            (application as? TrainingAwareApplication)?.trainingManager
                ?.onPromptTimeout(sampleId!!)
        }
        timer?.cancel()
        timer = null  // Release handler reference
        vibrationManager?.cancel()
        vibrationManager = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

/** Interface for Application class to provide TrainingManager access. */
interface TrainingAwareApplication {
    val trainingManager: TrainingManager?
}

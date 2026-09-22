package team.maodie.aimbot.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import team.maodie.aimbot.R
import team.maodie.aimbot.inference.JniCallBack
import team.maodie.aimbot.manager.ConfigManager
import team.maodie.aimbot.manager.LicenseManager
import team.maodie.aimbot.manager.LicenseManager.LicenseInfo
import team.maodie.aimbot.manager.LicenseManager.VerifyResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Authorization code verification entry point.
 *
 * Startup flow:
 *   1. Check SharedPreferences for an unexpired code → if valid, jump to MainActivity
 *   2. Otherwise show the input screen, let the user enter a code and tap Verify
 *   3. Success → write to SharedPreferences + jump to MainActivity
 *   4. Failure → show a specific error in the status card (length / CRC / expired, etc.)
 *
 * The app only accepts launcher launches and does not expose a deep link to prevent bypass.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var codeInputLayout: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var pasteBtn: MaterialButton
    private lateinit var verifyBtn: MaterialButton
    private lateinit var getCodeBtn: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Re-decode and re-validate the saved code on each launch; never trust cached expiry fields.
        when (val r = LicenseManager.verifySaved(this)) {
            is VerifyResult.Success -> {
                // Auto-verify success — same path as manual login, show status card + delayed jump.
                setContentView(R.layout.activity_login)
                bindViews()
                wireListeners()
                showAutoVerifySuccess(r.info)
                return
            }
            is VerifyResult.Failure -> {
                // Saved code expired or invalid — clear it and continue with the input flow.
                LicenseManager.clearSaved(this)
            }
        }

        setContentView(R.layout.activity_login)
        bindViews()
        wireListeners()

        // Show a failure reason coming back from MainActivity (for example, “authorization verification failed: code expired”).
        intent?.getStringExtra(EXTRA_REASON)?.let { reason ->
            if (reason.isNotEmpty()) showStatus(reason, isError = true)
        }
    }

    /**
     * Auto-verify success UI: status card displays expiry + remaining time, input is disabled,
     * then a background silent QNN HTP compile starts for all .tflite models before jumping to MainActivity.
     * This matches the manual-success experience.
     */
    private fun showAutoVerifySuccess(info: LicenseInfo) {
        val serverNow = LicenseManager.getServerTimeSec()
            .takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000L)
        android.util.Log.d(
            "LicenseDebug",
            "auto-verify OK startTs=${info.startTs} hours=${info.hours} expiryTs=${info.expiryTs} " +
                "now(server)=$serverNow now(local)=${System.currentTimeMillis() / 1000L}"
        )
        val expiryFmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            .format(Date(info.expiryTs * 1000))
        val remaining = formatRemaining(info.expiryTs - serverNow)
        android.util.Log.d("LicenseDebug", "showRemaining='$remaining' expiryFmt='$expiryFmt'")

        showStatus(
            getString(R.string.login_success_format, expiryFmt, remaining),
            isError = false
        )
        verifyBtn.isEnabled = false
        codeInput.isEnabled = false
        startPrewarmInBackground()
        launchMainAndFinish()
    }

    private fun bindViews() {
        codeInputLayout = findViewById(R.id.codeInputLayout)
        codeInput = findViewById(R.id.codeInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        verifyBtn = findViewById(R.id.verifyBtn)
        getCodeBtn = findViewById(R.id.getCodeBtn)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
    }

    private fun wireListeners() {
        codeInput.addTextChangedListener(CodeFormattingWatcher())
        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onVerifyClicked()
                true
            } else {
                false
            }
        }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        verifyBtn.setOnClickListener { onVerifyClicked() }
        getCodeBtn.setOnClickListener { openGetCodeUrl() }
    }

    /**
     * Real-time formatting for authorization code input:
     *   1. Strip all non [0-9A-Za-z] characters (spaces, newlines, clipboard leftovers, etc.)
     *   2. Trim to 25 characters
     *   3. Group every 5 characters with a hyphen while typing for readability
     *   4. Move the cursor to the end
     */
    private inner class CodeFormattingWatcher : TextWatcher {
        private var selfChange = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (selfChange || s == null) return

            val raw = s.toString()
            val clean = raw.filter { it.isLetterOrDigit() }
                .take(CODE_MAX_CHARS)
            val formatted = clean.chunked(CODE_GROUP_SIZE).joinToString("-")

            if (formatted == raw) {
                codeInputLayout.error = null
                if (statusCard.visibility == View.VISIBLE) statusCard.visibility = View.GONE
                return
            }

            val cursorAtEnd = formatted.length
            selfChange = true
            s.replace(0, raw.length, formatted)
            codeInput.setSelection(cursorAtEnd.coerceIn(0, formatted.length))
            selfChange = false

            codeInputLayout.error = null
            if (statusCard.visibility == View.VISIBLE) statusCard.visibility = View.GONE
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip() || cm.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            showStatus("Clipboard is empty or contains non-text content", isError = true)
            return
        }
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isEmpty()) {
            showStatus("Clipboard is empty", isError = true)
            return
        }
        codeInput.setText(text)
        // setSelection is handled by TextWatcher
        codeInputLayout.error = null
    }

    private fun onVerifyClicked() {
        val code = codeInput.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            codeInputLayout.error = getString(R.string.login_error_empty)
            return
        }
        codeInputLayout.error = null

        when (val result = LicenseManager.verifyAndPersist(this, code)) {
            is VerifyResult.Success -> onVerifySuccess(result.info)
            is VerifyResult.Failure -> showStatus(result.error, isError = true)
        }
    }

    private fun onVerifySuccess(info: LicenseInfo) {
        val serverNow = LicenseManager.getServerTimeSec()
            .takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000L)
        val expiryFmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            .format(Date(info.expiryTs * 1000))
        val remaining = formatRemaining(info.expiryTs - serverNow)

        showStatus(
            getString(R.string.login_success_format, expiryFmt, remaining),
            isError = false
        )
        verifyBtn.isEnabled = false
        codeInput.isEnabled = false
        startPrewarmInBackground()
        launchMainAndFinish()
    }

    private fun launchMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openGetCodeUrl() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.login_get_code_url)))
            )
        } catch (_: Exception) {
            showStatus("No browser found", isError = true)
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        statusText.text = msg
        if (isError) {
            statusCard.setCardBackgroundColor(
                MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorErrorContainer)
            )
            statusText.setTextColor(
                MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnErrorContainer)
            )
        } else {
            statusCard.setCardBackgroundColor(
                MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorTertiaryContainer)
            )
            statusText.setTextColor(
                MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnTertiaryContainer)
            )
        }
        statusCard.visibility = View.VISIBLE
    }

    private fun formatRemaining(remainingSec: Long): String {
        if (remainingSec <= 0) return "Expired"
        val d = remainingSec / 86400
        val h = (remainingSec % 86400) / 3600
        val m = (remainingSec % 3600) / 60
        return when {
            d > 0 -> "${d} d ${h} h"
            h > 0 -> "${h} h ${m} m"
            m > 0 -> "${m} m"
            else -> "${remainingSec} s"
        }
    }

    // ========== QNN HTP prewarm (silent) ==========
    // Kick off a background single-thread executor that visits every .tflite
    // model in models.json so QNN HTP gets a chance to compile + cache each
    // graph. Fire-and-forget: we don't block the UI, don't show an overlay,
    // and the user lands on MainActivity immediately.
    private var prewarmExecutor: ExecutorService? = null

    private fun startPrewarmInBackground() {
        val models = listTfliteModelNames()
        for (name in models) {
            copyModelAssetToFilesIfNeeded(name)
        }

        val ordered = ConfigManager.getConfig().modelIndex
            .coerceIn(0, models.size - 1)
            .let { models.getOrNull(it) }
            ?.let { p -> listOf(p) + models.filter { it != p } }
            ?: models

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "QnnPrewarm").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        prewarmExecutor = executor
        executor.execute {
            for (name in ordered) {
                val path = File(filesDir, name).absolutePath
                if (!File(path).exists()) {
                    Log.w("QnnPrewarm", "skip $name (not in filesDir)")
                    continue
                }
                val ok = JniCallBack.prewarmQnn(path)
                Log.i("QnnPrewarm", "prewarm $name -> $ok")
            }
            executor.shutdown()
            prewarmExecutor = null
        }
    }

    /** Returns the filenames of every .tflite entry in assets/models.json. */
    private fun listTfliteModelNames(): List<String> {
        return try {
            val json = assets.open("models.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("models")
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).getString("filename")
                if (name.endsWith(".tflite")) out.add(name)
            }
            out
        } catch (e: Exception) {
            Log.w("QnnPrewarm", "failed to parse models.json: ${e.message}")
            emptyList()
        }
    }

    /**
     * Replicates MainActivity.loadModel's asset → filesDir copy on first launch.
     * Native init needs a real on-disk path, not an asset FD.
     */
    private fun copyModelAssetToFilesIfNeeded(filename: String) {
        val modelFile = File(filesDir, filename)
        if (modelFile.exists()) return
        try {
            modelFile.parentFile?.mkdirs()
            assets.open(filename).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w("QnnPrewarm", "copy failed for $filename: ${e.message}")
        }
    }

    companion object {
        private const val CODE_MAX_CHARS = 25
        private const val CODE_GROUP_SIZE = 5
        const val EXTRA_REASON = "extra_reason"
    }
}

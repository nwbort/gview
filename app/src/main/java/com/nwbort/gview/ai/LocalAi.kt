package com.nwbort.gview.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * A language model that runs on the phone itself, when the phone has one.
 * Nothing leaves the device.
 */
interface LocalAi {
    enum class Status {
        /** This phone has no on-device model (most phones, and every emulator). */
        UNAVAILABLE,
        /** Supported, but the model isn't on the phone yet: the first use downloads it. */
        DOWNLOADABLE,
        AVAILABLE,
    }

    sealed interface Step {
        data object Downloading : Step
        /** Everything written so far; it grows as the model goes. */
        data class Text(val text: String) : Step
    }

    suspend fun status(): Status

    /** Downloads the model first if it needs to, then writes a reply to [prompt]. */
    fun write(prompt: String): Flow<Step>

    /** TEMPORARY: a plain-text report of why [status] came out the way it did. Blank if there's nothing to say. */
    suspend fun diagnose(context: Context): String = ""
}

/**
 * Gemini Nano through ML Kit's Prompt API, which talks to Android's AICore.
 * Pixel 8 and later, Galaxy S24 and later and a few others have it.
 */
class GeminiNano : LocalAi {
    // Made on first use, so a phone without AICore never touches it beyond the status check.
    private val model by lazy { Generation.getClient() }

    override suspend fun status(): LocalAi.Status = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> LocalAi.Status.AVAILABLE
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> LocalAi.Status.DOWNLOADABLE
            else -> LocalAi.Status.UNAVAILABLE
        }
    } catch (e: Throwable) {
        LocalAi.Status.UNAVAILABLE
    }

    // TEMPORARY debugging for phones that should have Gemini Nano but show no summary card.
    override suspend fun diagnose(context: Context): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build: ${Build.DISPLAY}")
        for (pkg in listOf("com.google.android.aicore", "com.google.android.as", "com.google.android.as.oss", "com.google.android.gms")) {
            val info = try {
                val p = context.packageManager.getPackageInfo(pkg, 0)
                val enabled = p.applicationInfo?.enabled ?: "?"
                "${p.versionName} (${p.longVersionCode}), enabled=$enabled"
            } catch (e: PackageManager.NameNotFoundException) {
                "not installed / not visible"
            } catch (e: Throwable) {
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
            appendLine("$pkg: $info")
        }
        appendLine("Mapped status: ${status()}")
        step("prompt checkStatus") { statusName(model.checkStatus()) }
        step("baseModelName") { model.getBaseModelName() }
        step("availableFeatures") { model.getAvailableFeatures().toString() }
        step("tokenLimit") { model.getTokenLimit().toString() }
        // The narrower task APIs: older Nano models (Pixel 8) may offer these even without the Prompt API.
        step("summarization") {
            val c = Summarization.getClient(
                SummarizerOptions.builder(context).setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS).setLanguage(SummarizerOptions.Language.ENGLISH).build()
            )
            try { "${statusName(c.checkFeatureStatus().await())}, model ${runCatching { c.baseModelName.await() }.getOrElse { describe(it) }}" } finally { c.close() }
        }
        step("rewriting") {
            val c = Rewriting.getClient(
                RewriterOptions.builder(context).setOutputType(RewriterOptions.OutputType.SHORTEN).setLanguage(RewriterOptions.Language.ENGLISH).build()
            )
            try { "${statusName(c.checkFeatureStatus().await())}, model ${runCatching { c.baseModelName.await() }.getOrElse { describe(it) }}" } finally { c.close() }
        }
        step("proofreading") {
            val c = Proofreading.getClient(
                ProofreaderOptions.builder(context).setInputType(ProofreaderOptions.InputType.KEYBOARD).setLanguage(ProofreaderOptions.Language.ENGLISH).build()
            )
            try { "${statusName(c.checkFeatureStatus().await())}, model ${runCatching { c.baseModelName.await() }.getOrElse { describe(it) }}" } finally { c.close() }
        }
    }.trimEnd()

    private suspend fun StringBuilder.step(name: String, block: suspend () -> String) {
        val result = try { block() } catch (e: Throwable) { describe(e) }
        appendLine("$name: $result")
    }

    private fun describe(e: Throwable): String = buildString {
        append("THREW ${e.javaClass.name}: ${e.message}")
        if (e is GenAiException) append(" [errorCode=${errorName(e.errorCode)}, aiCoreErrorCode=${e.aiCoreErrorCode}, aiCoreErrorType=${e.aiCoreErrorType}]")
        frames(e)
        var cause = e.cause
        while (cause != null && cause !== cause.cause) {
            append("\n  caused by ${cause.javaClass.name}: ${cause.message}")
            frames(cause)
            cause = cause.cause
        }
    }

    private fun StringBuilder.frames(t: Throwable) {
        t.stackTrace.take(12).forEach { append("\n    at $it") }
    }

    private fun statusName(raw: Int) = when (raw) {
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        else -> "unknown"
    } + " ($raw)"

    private fun errorName(code: Int) = when (code) {
        GenAiException.ErrorCode.UNKNOWN -> "UNKNOWN"
        GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
        GenAiException.ErrorCode.BUSY -> "BUSY"
        GenAiException.ErrorCode.NOT_SUPPORTED -> "NOT_SUPPORTED"
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICORE_INCOMPATIBLE"
        else -> "code $code"
    } + " ($code)"

    override fun write(prompt: String): Flow<LocalAi.Step> = flow {
        if (status() != LocalAi.Status.AVAILABLE) {
            emit(LocalAi.Step.Downloading)
            val done = model.download().first { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
            if (done is DownloadStatus.DownloadFailed) throw done.e
        }
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0.3f
            topK = 16
            maxOutputTokens = 320
        }
        var text = ""
        model.generateContentStream(request).collect { chunk ->
            text += chunk.candidates.firstOrNull()?.text.orEmpty()
            emit(LocalAi.Step.Text(text))
        }
    }
}

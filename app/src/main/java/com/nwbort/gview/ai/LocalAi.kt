package com.nwbort.gview.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
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

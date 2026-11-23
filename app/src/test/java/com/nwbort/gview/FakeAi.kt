package com.nwbort.gview

import com.nwbort.gview.ai.LocalAi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Stands in for Gemini Nano: "downloads", then writes a canned summary a few words at a time. */
class FakeAi : LocalAi {
    val prompts = mutableListOf<String>()
    private var downloaded = false

    override suspend fun status() = if (downloaded) LocalAi.Status.AVAILABLE else LocalAi.Status.DOWNLOADABLE

    override fun write(prompt: String): Flow<LocalAi.Step> = flow {
        prompts += prompt
        if (!downloaded) {
            emit(LocalAi.Step.Downloading)
            downloaded = true
        }
        var text = ""
        TEXT.split(" ").forEach { w ->
            text += "$w "
            emit(LocalAi.Step.Text(text))
        }
    }

    companion object {
        const val TEXT = "A steady long run at an easy aerobic effort. You held your pace evenly through the middle kilometres " +
            "and your heart rate stayed in zone 2 for most of it, with only a little drift late on. " +
            "It's a touch longer than your other runs this month, so a good step up in endurance."
    }
}

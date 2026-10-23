package it.dogior.hadEnough.subtitles

import android.view.View

internal interface AssPlayerHost {
    fun rootView(): View?
    fun subtitleOffsetMs(): Long
    fun subtitleRequest(id: String): AssSubtitleRequest?
}

internal class AssSubtitleRequest(val url: String, val headers: Map<String, String>)

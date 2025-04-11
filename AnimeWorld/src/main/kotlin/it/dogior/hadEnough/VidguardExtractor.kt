package it.dogior.hadEnough

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON.stringify
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

class VidguardExtractor : ExtractorApi() {
    override val mainUrl = "https://listeamed.net/"
    override val name = "VidGuard"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url).document
        val script = response.selectFirst("script:containsData(eval)")?.data() ?: return
        Log.d("AnimeWorld:VidGuard", script)
        val decodedScript = runJS2(script)
        val json = tryParseJson<SvgObject>(decodedScript) ?: return
        val playlistUrl = sigDecode(json.stream)

        callback.invoke(
            newExtractorLink(
                "VidGuard",
                "VidGuard",
                playlistUrl,
                type = ExtractorLinkType.M3U8
            ){
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val t = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, t)
    }

    private fun runJS2(hideMyHtmlContent: String): String {
        var result = ""
        val r = object : Runnable {
            override fun run() {
                val rhino = Context.enter()
                rhino.initSafeStandardObjects()
                rhino.optimizationLevel = -1
                val scope: Scriptable = rhino.initSafeStandardObjects()
                scope.put("window", scope, scope)
                try {
                    rhino.evaluateString(
                        scope,
                        hideMyHtmlContent,
                        "JavaScript",
                        1,
                        null,
                    )
                    val svgObject = scope.get("svg", scope)
                    result = if (svgObject is NativeObject) {
                        stringify(Context.getCurrentContext(), scope, svgObject, null, null)
                            .toString()
                    } else {
                        Context.toString(svgObject)
                    }
                } catch (e: Error) {
                    Log.i("Error", e.toString())
                } finally {
                    Context.exit()
                }
            }
        }
        val t = Thread(
            ThreadGroup("A"),
            r,
            "thread_rhino",
            3000000
        ) // StackSize 3Mb: Run in a thread because rhino requires more stack size for large scripts.
        t.start()
        t.join()
        t.interrupt()
        return result
    }


    data class SvgObject(
        val stream: String,
        val hash: String,
    )
}
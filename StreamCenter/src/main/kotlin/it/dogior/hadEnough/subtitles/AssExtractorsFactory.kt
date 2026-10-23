package it.dogior.hadEnough.subtitles

import android.net.Uri
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser

internal class AssExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val sink: AssEmbeddedSink,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory =
        apply { delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled) }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory =
        apply { delegate.setSubtitleParserFactory(subtitleParserFactory) }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
        apply { delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> {
        if (sink.isReleased) return extractors
        return Array(extractors.size) { index ->
            val extractor = extractors[index]
            if (MatroskaAssTracks.isMatroska(extractor)) AssMatroskaExtractor(extractor, sink) else extractor
        }
    }
}

internal class AssProgressiveExtractorFactory(
    private val delegate: ProgressiveMediaExtractor.Factory,
    private val sink: AssEmbeddedSink,
) : ProgressiveMediaExtractor.Factory {
    override fun createProgressiveMediaExtractor(playerId: PlayerId): ProgressiveMediaExtractor =
        delegate.createProgressiveMediaExtractor(playerId).also { extractor ->
            if (extractor is BundledExtractorsAdapter) Media3AssGraph.patchAdapter(extractor, sink)
        }
}

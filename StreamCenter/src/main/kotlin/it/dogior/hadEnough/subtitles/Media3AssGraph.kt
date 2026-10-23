package it.dogior.hadEnough.subtitles

import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import java.util.Collections
import java.util.IdentityHashMap

internal object Media3AssGraph {
    private const val MAX_DEPTH = 24
    private val WALKABLE = setOf(
        "ExoPlayerImpl",
        "ExoPlayerImplInternal",
        "MediaPeriodQueue",
        "MediaPeriodHolder",
        "MediaSourceList",
        "MediaSourceHolder",
        "MediaSourceHolderSnapshot",
        "MediaSourceAndListener",
    )

    class Result(var lateExtractors: Int = 0)

    fun patch(player: Any, sink: AssEmbeddedSink): Result {
        val result = Result()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        walk(player, sink, visited, result, 0)
        return result
    }

    fun patchAdapter(adapter: BundledExtractorsAdapter, sink: AssEmbeddedSink): Boolean {
        val extractor = Reflect.get(adapter, "extractor") as? Extractor
        if (extractor == null) {
            val factory = Reflect.get(adapter, "extractorsFactory") as? ExtractorsFactory ?: return false
            if (factory is AssExtractorsFactory) return false
            Reflect.set(adapter, "extractorsFactory", AssExtractorsFactory(factory, sink))
            return false
        }
        if (extractor is AssMatroskaExtractor || !MatroskaAssTracks.isMatroska(extractor)) return false
        MatroskaAssTracks.patch(extractor, sink)
        return true
    }

    private fun patchSource(source: ProgressiveMediaSource, sink: AssEmbeddedSink) {
        val factory = Reflect.get(source, "progressiveMediaExtractorFactory") as? ProgressiveMediaExtractor.Factory ?: return
        if (factory is AssProgressiveExtractorFactory) return
        Reflect.set(source, "progressiveMediaExtractorFactory", AssProgressiveExtractorFactory(factory, sink))
    }

    private fun walk(node: Any, sink: AssEmbeddedSink, visited: MutableSet<Any>, result: Result, depth: Int) {
        if (depth > MAX_DEPTH || !visited.add(node)) return
        when (node) {
            is ProgressiveMediaSource -> patchSource(node, sink)
            is BundledExtractorsAdapter -> {
                if (patchAdapter(node, sink)) result.lateExtractors++
                return
            }
        }
        Reflect.instanceFields(node.javaClass).forEach { field ->
            val value = runCatching { field.get(node) }.getOrNull() ?: return@forEach
            visitValue(value, sink, visited, result, depth + 1)
        }
    }

    private fun visitValue(value: Any, sink: AssEmbeddedSink, visited: MutableSet<Any>, result: Result, depth: Int) {
        when (value) {
            is Array<*> -> value.forEach { item -> item?.let { visitValue(it, sink, visited, result, depth) } }
            is Collection<*> -> runCatching { value.toList() }.getOrNull()?.forEach { item ->
                item?.let { visitValue(it, sink, visited, result, depth) }
            }
            is Map<*, *> -> runCatching { value.values.toList() }.getOrNull()?.forEach { item ->
                item?.let { visitValue(it, sink, visited, result, depth) }
            }
            else -> if (isWalkable(value)) walk(value, sink, visited, result, depth)
        }
    }

    private fun isWalkable(value: Any): Boolean =
        value is MediaSource || value is MediaPeriod || value is ProgressiveMediaExtractor ||
            value.javaClass.simpleName in WALKABLE
}

package it.dogior.hadEnough.subtitles

import android.view.View
import android.view.ViewGroup

internal fun <T : View> View.findFirstView(type: Class<T>): T? = breadthFirst().filterIsInstance(type).firstOrNull()

internal fun <T : View> View.findAllViews(type: Class<T>): List<T> = breadthFirst().filterIsInstance(type).toList()

private fun View.breadthFirst(): Sequence<View> = sequence {
    val pending = ArrayDeque<View>().apply { add(this@breadthFirst) }
    while (pending.isNotEmpty()) {
        val view = pending.removeFirst()
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
    }
}

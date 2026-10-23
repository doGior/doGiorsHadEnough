package it.dogior.hadEnough.subtitles

import android.graphics.Bitmap

internal class AssImage(val x: Int, val y: Int, val color: Int, val bitmap: Bitmap)

internal class AssFrame(val images: Array<AssImage>?, val changed: Boolean)

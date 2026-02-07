package com.opensource.tremorwatch.complications

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.opensource.tremorwatch.R
import com.opensource.tremorwatch.RatingActivity

class RatingComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        listener.onComplicationData(buildComplicationData(request.complicationType))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildComplicationData(type)
    }

    private fun buildComplicationData(type: ComplicationType): ComplicationData? {
        val tapIntent = Intent(this, RatingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra("source", "MANUAL")
        }
        val tapAction = PendingIntent.getActivity(
            this,
            2001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = Icon.createWithResource(this, R.drawable.ic_complication_rating)
        val contentDescription = PlainComplicationText.Builder("Rate tremor").build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Rate").build(),
                    contentDescription = contentDescription
                )
                    .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(icon).build(),
                    contentDescription = contentDescription
                )
                    .setTapAction(tapAction)
                    .build()
            }
            else -> NoDataComplicationData()
        }
    }
}

package com.pupus.phone.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pupus.phone.R
import com.pupus.phone.voice.VoiceBus
import com.pupus.phone.voice.VoiceLauncher

/**
 * "Hablar" button, state and last reply. A PaneTemplate whose two rows keep their titles, so a
 * state change is a refresh of the same template and not a new step of the host's task quota.
 * The button runs the same flow as the floating bubble ([VoiceLauncher.tap]).
 */
class TalkScreen(carContext: CarContext) : Screen(carContext) {
    private val voiceListener = VoiceBus.Listener { _, _ -> invalidate() }
    private val detailsListener = VoiceBus.DetailsListener { _, _ -> invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                VoiceBus.addListener(voiceListener)
                VoiceBus.addDetailsListener(detailsListener)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                VoiceBus.removeListener(voiceListener)
                VoiceBus.removeDetailsListener(detailsListener)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val view = CarVoiceTexts.of(VoiceBus.state, VoiceBus.isBridgeConnected, VoiceBus.reply)
        val talk = Action.Builder()
            .setTitle(carContext.getString(view.actionRes))
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener { VoiceLauncher.tap(carContext) }
            .build()
        val pane = Pane.Builder()
            .addRow(row(R.string.car_row_state, carContext.getString(view.stateRes)))
            .addRow(row(R.string.car_row_reply, view.reply ?: carContext.getString(R.string.car_no_reply)))
            .addAction(talk)
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(titleRes: Int, text: String): Row =
        Row.Builder().setTitle(carContext.getString(titleRes)).addText(text).build()
}

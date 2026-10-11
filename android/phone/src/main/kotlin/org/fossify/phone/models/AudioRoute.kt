package org.fossify.phone.models

import android.telecom.CallAudioState
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fossify.phone.R

enum class AudioRoute(val route: Int, @StringRes val stringRes: Int, @DrawableRes val iconRes: Int) {
    SPEAKER(CallAudioState.ROUTE_SPEAKER, R.string.numi_phone_audio_route_speaker, R.drawable.numi_phone_ic_volume_up_vector),
    EARPIECE(CallAudioState.ROUTE_EARPIECE, R.string.numi_phone_audio_route_earpiece, R.drawable.numi_phone_ic_volume_down_vector),
    BLUETOOTH(CallAudioState.ROUTE_BLUETOOTH, R.string.numi_phone_audio_route_bluetooth, R.drawable.numi_phone_ic_bluetooth_audio_vector),
    WIRED_HEADSET(CallAudioState.ROUTE_WIRED_HEADSET, R.string.numi_phone_audio_route_wired_headset, R.drawable.numi_phone_ic_headset_vector),
    WIRED_OR_EARPIECE(CallAudioState.ROUTE_WIRED_OR_EARPIECE, R.string.numi_phone_audio_route_wired_or_earpiece, R.drawable.numi_phone_ic_volume_down_vector);

    companion object {
        fun fromRoute(route: Int?) = values().firstOrNull { it.route == route }
    }
}

package org.fossify.phone.activities

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.phone.R

open class SimpleActivity : BaseSimpleActivity() {
    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.numi_phone_ic_launcher_red,
        R.mipmap.numi_phone_ic_launcher_pink,
        R.mipmap.numi_phone_ic_launcher_purple,
        R.mipmap.numi_phone_ic_launcher_deep_purple,
        R.mipmap.numi_phone_ic_launcher_indigo,
        R.mipmap.numi_phone_ic_launcher_blue,
        R.mipmap.numi_phone_ic_launcher_light_blue,
        R.mipmap.numi_phone_ic_launcher_cyan,
        R.mipmap.numi_phone_ic_launcher_teal,
        R.mipmap.numi_phone_ic_launcher,
        R.mipmap.numi_phone_ic_launcher_light_green,
        R.mipmap.numi_phone_ic_launcher_lime,
        R.mipmap.numi_phone_ic_launcher_yellow,
        R.mipmap.numi_phone_ic_launcher_amber,
        R.mipmap.numi_phone_ic_launcher_orange,
        R.mipmap.numi_phone_ic_launcher_deep_orange,
        R.mipmap.numi_phone_ic_launcher_brown,
        R.mipmap.numi_phone_ic_launcher_blue_grey,
        R.mipmap.numi_phone_ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.numi_phone_app_launcher_name)

    override fun getRepositoryName() = "Phone"
}

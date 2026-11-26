package app.naviamp.domain.app

import app.naviamp.domain.network.NaviampApplicationId
import app.naviamp.domain.network.NaviampClientName

const val NaviampDevelopmentApplicationId = "$NaviampApplicationId.development"
const val NaviampDefaultDesktopDeviceName = "$NaviampClientName Desktop"
const val NaviampDefaultAndroidDeviceName = "Android $NaviampClientName"

/** Storage profiles never reuse the upstream app's directories or each other's settings. */
fun naviampApplicationProfileId(development: Boolean): String =
    if (development) NaviampDevelopmentApplicationId else NaviampApplicationId

/** jpackage names native launchers by package and executable; Flatpak supplies its own desktop ID. */
fun naviampLinuxDesktopEntryId(flatpakApplicationId: String?): String =
    flatpakApplicationId ?: "$NaviampApplicationId-$NaviampClientName"

package dev.pampa.mycloudgallery.feature.integrations.share

import android.content.Context
import android.text.format.Formatter
import dev.pampa.mycloudgallery.feature.integrations.R

// Fase 5B (P11, D26, L41): il testo che l'utente legge quando una condivisione si ferma. Le frasi stanno in `strings_integrations_share.xml`; qui si
// sceglie quale e con quali numeri (solo i tetti: mai il nome di un file).

/** La frase per l'utente di una fermata di [ShareStop]. */
fun shareStopText(context: Context, stop: ShareStop): String = when (stop) {
  ShareStop.NOTHING -> context.getString(R.string.integrations_share_stop_nothing)
  ShareStop.TOO_MANY -> context.getString(R.string.integrations_share_stop_too_many, ShareLimits.MAX_ITEMS)
  ShareStop.TOO_BIG -> context.getString(R.string.integrations_share_stop_too_big, Formatter.formatShortFileSize(context, ShareLimits.MAX_TOTAL_BYTES))
  ShareStop.NO_SPACE -> context.getString(R.string.integrations_share_stop_no_space)
  ShareStop.UNAVAILABLE -> context.getString(R.string.integrations_share_stop_unavailable)
  ShareStop.NAS_UNREACHABLE -> context.getString(R.string.integrations_share_stop_nas_unreachable)
  ShareStop.CANNOT_REMOVE_LOCATION -> context.getString(R.string.integrations_share_stop_cannot_remove_location)
  ShareStop.LOCATION_REMAINS -> context.getString(R.string.integrations_share_stop_location_remains)
  ShareStop.FAILED -> context.getString(R.string.integrations_share_stop_failed)
}

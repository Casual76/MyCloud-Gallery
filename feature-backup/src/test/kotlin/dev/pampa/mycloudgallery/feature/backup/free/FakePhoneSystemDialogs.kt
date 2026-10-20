package dev.pampa.mycloudgallery.feature.backup.free

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.TrashBatch

/** I dialoghi del sistema finti: danno un `IntentSender` vero (di un `PendingIntent`) o dicono che il dialogo non c'e'; ricordano cosa e' stato chiesto. */
internal class FakePhoneSystemDialogs(private val context: Context, override var available: Boolean = true) : PhoneSystemDialogs {

  /** Il telefono non ha il dialogo: le richieste rispondono null. */
  var missing: Boolean = false

  /**
   * VE-2, parita' con il vero di prima: il sistema rifiuta la richiesta con un'eccezione (un `files/<id>` dato a `createWriteRequest`). Prima il
   * finto non lanciava mai e nascondeva che nessuno la intercettava.
   */
  var failure: RuntimeException? = null

  val removeRequests: MutableList<TrashBatch> = ArrayList()
  val writeRequests: MutableList<List<PhoneRef>> = ArrayList()

  override fun removeRequest(batch: TrashBatch): IntentSender? {
    failure?.let { throw it }
    if (missing) return null
    removeRequests += batch
    return sender()
  }

  override fun writeRequest(rows: List<PhoneRef>): IntentSender? {
    failure?.let { throw it }
    if (missing) return null
    writeRequests += rows
    return sender()
  }

  private fun sender(): IntentSender =
    PendingIntent.getActivity(context, 0, Intent("dev.pampa.mycloudgallery.test.DIALOGO"), PendingIntent.FLAG_IMMUTABLE).intentSender
}

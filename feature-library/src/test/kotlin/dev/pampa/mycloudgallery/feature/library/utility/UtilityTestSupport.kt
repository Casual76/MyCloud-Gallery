package dev.pampa.mycloudgallery.feature.library.utility

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.MediaRef

/** Un `IntentSender` vero (Robolectric) da restituire come richiesta del dialogo di sistema. */
fun testIntentSender(): IntentSender =
  PendingIntent.getBroadcast(ApplicationProvider.getApplicationContext<Application>(), 0, Intent("prova"), PendingIntent.FLAG_IMMUTABLE).intentSender

/**
 * Il cestino di sistema finto che restituisce una richiesta vera: il fake della libreria registra le richieste ma restituisce sempre null, e per i
 * Doppioni null vuol dire "il sistema non puo'" (un errore). Registra comunque su [inner], cosi' i test leggono `trashRequests` e `deleteRequests`.
 */
class SenderIntents(val inner: FakePhoneMediaIntents, private val sender: IntentSender) : PhoneMediaIntents by inner {
  override fun trashRequest(refs: List<MediaRef.Phone>, trash: Boolean): IntentSender? {
    inner.trashRequest(refs, trash)
    return if (inner.canTrash && refs.isNotEmpty()) sender else null
  }
}

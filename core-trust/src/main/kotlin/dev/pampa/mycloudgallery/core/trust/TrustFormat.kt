package dev.pampa.mycloudgallery.core.trust

import kotlinx.serialization.Serializable

/**
 * Segnaposto della Fase 1.1 per il formato dei dati sul NAS (piano 1.4): ogni dato scritto sul NAS
 * dichiara la versione del formato, cosi' un'app vecchia non rompe i dati di una nuova e chiede di
 * aggiornarsi invece di interpretarli male.
 *
 * Specifica, firme, registro dei dispositivi e lettura difensiva arrivano con la 1.4.
 */
object TrustFormat {

  /** La versione del formato che questa build sa scrivere e leggere. */
  const val CURRENT_VERSION: Int = 1

  /** Un dato con una versione piu' nuova (o senza senso) NON si legge: si chiede di aggiornare l'app. */
  fun isReadable(version: Int): Boolean = version in 1..CURRENT_VERSION
}

/** L'intestazione che apre ogni dato firmato sul NAS. Serve anche a provare il plugin serialization. */
@Serializable
data class FormatHeader(val formatVersion: Int)

package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.trust.TrustLimits

// I tetti dei dati personali (docs/fase4-progetto.md §2.9, §4.3 "lettura difensiva"). Quelli del formato (segmento, corpo, testa,
// ora nel futuro, una testa ogni 5 minuti) stanno in `TrustLimits`; qui solo cio' che e' proprio di questo modulo.

object PersonalLimits {
  /** Le modifiche si accorpano per 30 secondi, con l'app aperta e il NAS raggiungibile (§2.9 "Quando"). Lo applica il lavoro di :app. */
  const val COALESCE_MILLIS: Long = 30_000L

  /**
   * Le voci che si accettano da UN dispositivo in un giro: lo stato di un account (due voci per media al massimo, piu' album e
   * impostazioni) sta molto sotto; oltre, una testa ostile che elenca migliaia di segmenti pieni non fa leggere gigabyte.
   */
  const val MAX_ENTRIES_PER_DEVICE_ROUND: Int = TrustLimits.MAX_RECORDS_PER_DEVICE_SHARE

  /** I byte di segmenti che si scaricano da UN dispositivo in un giro (una voce ben fatta pesa ~150 byte, il tetto e' 4 KiB). */
  const val MAX_BYTES_PER_DEVICE_ROUND: Long = 64L * 1024 * 1024

  /** I byte di segmenti di tutti i dispositivi in un giro. */
  const val MAX_BYTES_PER_ROUND: Long = 256L * 1024 * 1024

  /** Le voci dello stato unito che si pubblicano: lo stesso tetto che si accetta in lettura. */
  const val MAX_PUBLISHED_ENTRIES: Int = MAX_ENTRIES_PER_DEVICE_ROUND

  /**
   * Un segmento pubblicato ha al massimo queste voci (la meta' del tetto del formato): le voci si dividono per i primi bit della
   * chiave, cosi' un segmento che non cambia resta identico (stesso nome, "esiste gia'") e una modifica ne riscrive uno solo.
   */
  const val TARGET_SEGMENT_ENTRIES: Int = TrustLimits.MAX_SEGMENT_ENTRIES / 2

  /** I bit della chiave che dividono le voci in segmenti: al massimo 2^12 segmenti, sotto il tetto di 8192 della testa. */
  const val MAX_BUCKET_BITS: Int = 12

  /** Il tetto di una voce `Text` (esclusioni: un percorso di share). */
  const val MAX_TEXT: Int = PersonalValue.MAX_TEXT
}

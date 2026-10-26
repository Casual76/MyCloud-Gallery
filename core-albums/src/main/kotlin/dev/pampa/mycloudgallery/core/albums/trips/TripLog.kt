package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.AlbumDiag
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

// I LOG DI VIAGGI ED EVENTI (docs/fase7-progetto.md D37, §2.3 "Privacy"): **solo numeri e codici**, mai coordinate, casa, luoghi, titoli, id di
// media o date (dicono dove si era e quando). E' l'unico punto di trips/ che scrive un log (TripPrivacyTest cerca ogni altra scrittura, e
// registra tutto cio' che esce in una rilevazione su una storia con coordinate e nomi di luoghi). Di un'eccezione si scrive solo il nome della
// classe: il messaggio puo' contenere un percorso, un luogo o un id.

internal object TripLog {
  private const val TAG = "Trips"

  fun detected(trips: Int, events: Int, homeKnown: Boolean, millis: Long) {
    GalleryLog.i(TAG) { "rilevazione: $trips viaggi, $events eventi, casa stimata=$homeKnown, $millis ms" }
  }

  /** Nessuna casa stimabile (E-AL16): il codice della Diagnostica `TRIP_NO_GPS`. */
  fun noGps() {
    GalleryLog.i(TAG) { "${AlbumDiag.TRIP_NO_GPS.id}: nessuna casa stimabile, solo eventi" }
  }

  fun interrupted() {
    GalleryLog.i(TAG) { "rilevazione interrotta: il tempo a disposizione e' finito, le proposte restano quelle di prima" }
  }

  fun shown(proposals: Int) {
    GalleryLog.d(TAG) { "proposte ricontrollate: $proposals" }
  }

  fun failed(error: Throwable) {
    GalleryLog.w(TAG) { "operazione non riuscita (${error.javaClass.simpleName})" }
  }
}

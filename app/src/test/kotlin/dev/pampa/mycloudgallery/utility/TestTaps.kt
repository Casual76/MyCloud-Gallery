package dev.pampa.mycloudgallery.utility

import dev.pampa.mycloudgallery.feature.library.trash.UserTap

// R18 (revisione di Fase 7): la prova del tocco ([UserTap]) non si fabbrica fuori da `feature-library`: il costruttore e' `internal` e i falsi pubblici
// (`fakeSingleTap`, `fakeDoubleTap`) che stavano nel set `main` non ci sono piu'. I test di `:app` che provano le porte vere (che ricevono la prova da un foglio)
// se la fanno da soli, qui, in `src/test`: il codice di produzione non vede questo file.

internal object TestTaps {

  private val constructor = UserTap::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).also { it.isAccessible = true }

  /** La prova di [taps] tocchi, come la darebbe un foglio dopo i suoi tocchi. */
  fun taps(count: Int): UserTap = constructor.newInstance(count)

  /** Un tocco su un pulsante. */
  fun single(): UserTap = taps(1)

  /** I due tocchi distinti di "Elimina subito". */
  fun double(): UserTap = taps(2)
}

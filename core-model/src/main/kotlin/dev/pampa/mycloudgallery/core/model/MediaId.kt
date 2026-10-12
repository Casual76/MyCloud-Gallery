package dev.pampa.mycloudgallery.core.model

/**
 * L'identita' stabile di un media (docs: piano 1.3), indipendente da dove si trova: un media puo'
 * cambiare `_ID` in MediaStore, percorso o share senza smettere di essere lo stesso media.
 *
 * Segnaposto della Fase 1.1: la forma vera (impronte multiple, posizioni, versioni) arriva con 1.3.
 */
@JvmInline
value class MediaId(val value: String) {

  init {
    require(value.isNotBlank()) { "L'identita' di un media non puo' essere vuota." }
  }

  override fun toString(): String = value
}

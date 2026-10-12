package dev.pampa.mycloudgallery.core.model

/**
 * Marca un dato **sensibile** (piano §7, "Beni da proteggere"): la posizione GPS rivela dove si
 * abita e dove si va. Chi lo porta non lo mette nei log, nella Diagnostica esportata o nei messaggi
 * d'errore; i tipi marcati nascondono il valore nel `toString()`.
 *
 * E' un'annotazione di documentazione verificabile (la Diagnostica della Fase 2.7 potra' cercarla per
 * riflessione), non una protezione crittografica: il database resta nello spazio privato dell'app.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.FIELD,
  AnnotationTarget.VALUE_PARAMETER,
)
annotation class Sensitive(val reason: String)

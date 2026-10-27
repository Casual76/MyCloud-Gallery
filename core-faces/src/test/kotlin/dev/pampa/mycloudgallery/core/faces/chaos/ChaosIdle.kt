package dev.pampa.mycloudgallery.core.faces.chaos

/**
 * L'INVARIANTE 11 (docs/prova-500.md Parte F, §F1): a lavoro finito, un dispositivo il cui processo muore senza un colpo e riparte, riprende i lavori dal diario e
 * non cambia niente. L'impronta e' stretta: i file dei volti e i temporanei sul NAS (i nomi, ordinati), e per ogni dispositivo il diario della cancellazione, le
 * generazioni su disco, la voce dei dati personali, l'outbox e l'archivio della generazione corrente. Il confronto sta qui, non in `ChaosRound`, per poterlo
 * provare da solo (`FacesChaosVerifierTest`).
 */
internal object ChaosIdle {

  suspend fun fingerprint(world: ChaosWorld): List<String> = buildList {
    add("nas: " + world.nas.paths().map { it.value }.filter { it.startsWith(".mycloudgallery/faces/") || it.startsWith(".mycloudgallery/tmp/") }.sorted().joinToString(","))
    for (device in world.devices) {
      val g = device.generation()
      add(
        "${device.name}: diario ${device.journal.stored}; generazioni su disco ${device.local.gens.keys.map { it.base32 }}; " +
          "voce ${device.personal.dated?.atNas}/${device.personal.lastPublished?.atNas}; outbox ${if (g == null) null else device.local.publishStore.load(g)}; " +
          "archivio ${if (g == null) null else device.local.raw(g)}",
      )
    }
  }

  /** Rosso (invariante 11) se le due impronte non sono uguali; dice quali righe sono cambiate (mai contenuti: nomi di file e numeri). */
  fun compare(verifier: ChaosVerifier, phase: String, before: List<String>, after: List<String>) {
    if (before == after) return
    verifier.add(Invariant.IDLE, phase, "una morte senza colpo seguita dalla ripresa ha cambiato il mondo: ${diff(before, after)}")
  }

  private fun diff(before: List<String>, after: List<String>): String =
    before.indices.filter { before[it] != after.getOrNull(it) }.joinToString(" | ") { "prima: ${before[it].take(160)} dopo: ${after.getOrNull(it)?.take(160)}" }
}

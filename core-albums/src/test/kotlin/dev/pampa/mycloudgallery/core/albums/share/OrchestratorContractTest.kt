package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.ReferenceWorld
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunnerContract
import dev.pampa.mycloudgallery.core.albums.testing.ShareRunnerPorts

/** Le porte dell'orchestratore vero sopra le stesse porte finte dell'esecutore di riferimento. */
fun ShareRunnerPorts.forOrchestrator(): ShareOrchestratorPorts = ShareOrchestratorPorts(
  diary, facts, shared, allowed, mover, uploader, tickets, publisher, safeCopies, origins, family, gate, clocks,
)

/** P4 (riga del §4.4, P0 "Cosa trova pronto"): l'orchestratore vero passa la suite di contratto di [ShareRunner] sulle porte finte. */
class OrchestratorContractTest : ShareRunnerContract() {
  override fun world(): ReferenceWorld = ReferenceWorld()

  override fun runner(w: ReferenceWorld): ShareRunner = ShareOrchestrator(w.ports().forOrchestrator(), newNonce = { w.nextNonce() })
}

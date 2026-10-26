package dev.pampa.mycloudgallery.core.security.faces

import dev.pampa.mycloudgallery.core.model.FaceGeneration

// LA CHIAVE DEI VOLTI (docs/fase8-progetto.md §1.2 "core-security", D14, D29, C20 riscritta, E-F19, E-F35). Contratto di P0;
// l'implementazione sul Keystore (`FacesKeyStore`, sopra il `SecretKeyStore` delle credenziali) e' di P6, la distruzione nell'azzeramento
// (`DeviceSelfWipe`, fase 3 con le credenziali) di P13. core-faces la vede SOLO da `store/` ed `erase/` (Phase8ArchitectureTest).
//
// **Sul dispositivo** vettori, centroidi e nomi si cifrano AES-256-GCM con una **chiave dati** casuale, avvolta da una chiave del Keystore
// **per generazione** (alias `mcg-faces-<G>`, StrongBox se c'e', senza sblocco ne' biometria: il calcolo gira di notte). La cancellazione
// distrugge **per prima** la chiave del Keystore, cosi' ogni resto (pagine libere di SQLite, WAL, blocchi della flash) diventa
// illeggibile. Sul NAS i dati restano in chiaro (L94): una chiave del Keystore e' di un dispositivo.

/**
 * La chiave dati di una generazione, aperta (D14): cifra e decifra **dentro il processo**; il materiale non esce mai (nessun getter dei
 * byte). [close] la azzera; dopo, [seal] e [open] lanciano `IllegalStateException`.
 */
interface FacesDataKey : AutoCloseable {
  /** AES-256-GCM: un nonce nuovo per ogni chiamata, [aad] legato al dato (tabella, colonna, id) cosi' un valore non si sposta di riga. */
  fun seal(plain: ByteArray, aad: ByteArray): ByteArray

  /** Il dato in chiaro, o null se non e' autentico (altra chiave, altra riga, byte alterati). Mai un'eccezione per un dato ostile. */
  fun open(sealed: ByteArray, aad: ByteArray): ByteArray?

  override fun close()
}

/**
 * Le chiavi dei volti per generazione (D14). Tutte idempotenti; nessuna lancia per un caso previsto.
 * - [create]: crea la chiave di [generation] se non c'e' (se c'e', la tiene: rifarla renderebbe illeggibili i dati); false = Keystore non
 *   disponibile (chi salva non salva in chiaro: aspetta).
 * - [open]: la chiave dati aperta, o null se non c'e' o non si puo' usare (chiave sparita o invalidata dopo un ripristino o un
 *   aggiornamento del sistema: E-F19, `FACES_KEY_LOST`; i dati si cancellano e si riscaricano o ricalcolano).
 * - [destroy]: distrugge la chiave di [generation] (il primo passo di ogni cancellazione locale: D29 passo 6, esclusione D4, azzeramento);
 *   true anche se non c'era. Dopo, nessun [open] di quella generazione riesce piu'.
 * - [destroyAll]: tutte le chiavi `mcg-faces-*` (azzeramento del dispositivo, E-F35).
 */
interface FacesKeys {
  suspend fun create(generation: FaceGeneration): Boolean

  suspend fun open(generation: FaceGeneration): FacesDataKey?

  suspend fun destroy(generation: FaceGeneration): Boolean

  suspend fun destroyAll(): Boolean
}

/** I nomi delle chiavi dei volti nel Keystore (D14, §6.4): `mcg-faces-<G>`, la generazione in base32. */
object FacesKeyAliases {
  const val PREFIX: String = "mcg-faces-"

  fun aliasOf(generation: FaceGeneration): String = PREFIX + generation.base32

  /** La generazione di un alias dei volti (per [FacesKeys.destroyAll]), o null se l'alias non e' dei volti. */
  fun generationOf(alias: String): FaceGeneration? = if (alias.startsWith(PREFIX)) FaceGeneration.parse(alias.removePrefix(PREFIX)) else null
}

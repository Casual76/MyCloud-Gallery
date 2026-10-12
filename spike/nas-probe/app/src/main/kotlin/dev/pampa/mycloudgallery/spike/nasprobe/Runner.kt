package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context

/**
 * Orchestra i test nell'ordine del piano. Ogni test e' una funzione separata (NetworkTest.kt, SmbTests.kt,
 * WakeOnLan.kt); qui si decide solo cosa lanciare e quando fermarsi.
 *
 * Regola ferrea: un solo tentativo di autenticazione per connessione. Se fallisce si interrompe tutto.
 */
class Runner(
    private val ctx: Context,
    private val settings: ProbeSettings,
    private val onResult: (TestResult) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    val results = ArrayList<TestResult>()

    private fun publish(r: TestResult) {
        results += r
        onResult(r)
    }

    private fun skipFrom(firstId: String, reason: String) {
        var on = false
        for ((id, title) in PLAN) {
            if (id == firstId) on = true
            if (on) publish(skippedResult(id, title, reason))
        }
    }

    suspend fun runNetworkOnly() {
        onStatus("Rete: analisi in corso...")
        publish(testNetwork(ctx, settings))
    }

    /**
     * Inventario in sola lettura: connessione con un solo tentativo di autenticazione, poi per ogni voce
     * dell'elenco un percorso ricorsivo che produce solo statistiche aggregate.
     */
    suspend fun runInventory() {
        val entries = settings.inventoryEntries
        val missing = buildList {
            if (settings.host.isBlank()) add("host")
            if (settings.user.isBlank()) add("utente")
            if (settings.password.isEmpty()) add("password")
            if (entries.isEmpty()) add("elenco share dell'inventario")
        }
        if (missing.isNotEmpty()) {
            publish(skippedResult("INV", "Inventario (sola lettura)", "campi mancanti: ${missing.joinToString(", ")}"))
            return
        }

        val env = SmbEnv(ctx, settings, OwnedFiles())
        onStatus("Inventario: connessione e autenticazione (un solo tentativo)")
        val out = testConnectAuth(
            env, "INV-0", "Inventario: connessione e autenticazione", settings.requireEncryption,
            shareName = null, directoryLeasing = false,
        )
        publish(out.result)
        val conn = out.conn
        if (conn == null) {
            publish(
                skippedResult(
                    "INV-1", "Inventario (sola lettura)",
                    if (out.authFailed) "interrotto: autenticazione fallita, nessun nuovo tentativo"
                    else "interrotto: connessione non riuscita",
                ),
            )
            return
        }

        val accs = ArrayList<InventoryAcc>()
        var interrupted = false
        try {
            for ((i, entry) in entries.withIndex()) {
                val acc = InventoryAcc(i + 1, entry.share, entry.folder.isNotEmpty())
                accs += acc
                val label = "Inventario ${i + 1}/${entries.size}"
                onStatus("$label: avvio")
                try {
                    inventoryShare(conn, entry, acc, settings.sensitiveTokens, label, onStatus)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    interrupted = true
                    publish(acc.result(interrupted = true)) // statistiche parziali fino a qui
                    publish(inventoryTotals(accs, interrupted = true))
                    throw c
                }
                publish(acc.result(interrupted = false))
                if (acc.fatal) break
            }
            publish(inventoryTotals(accs, interrupted))
        } finally {
            conn.close()
        }
    }

    suspend fun runWakeOnLan() {
        publish(testWakeOnLan(ctx, settings, onStatus))
    }

    suspend fun runAll() {
        onStatus("Test 1: rete")
        publish(testNetwork(ctx, settings))

        // Campi obbligatori: meglio fermarsi che tentare un'autenticazione destinata a fallire.
        val missing = buildList {
            if (settings.host.isBlank()) add("host")
            if (settings.share.isBlank()) add("share")
            if (settings.user.isBlank()) add("utente")
            if (settings.password.isEmpty()) add("password")
        }
        if (missing.isNotEmpty()) {
            skipFrom("2", "campi mancanti: ${missing.joinToString(", ")}")
            return
        }

        val env = SmbEnv(ctx, settings, OwnedFiles())

        onStatus("Test 2: connessione e autenticazione (un solo tentativo)")
        val first = testConnectAuth(env, "2", "Connessione e autenticazione", settings.requireEncryption)
        publish(first.result)
        val conn = first.conn
        if (conn == null || conn.share == null) {
            conn?.close()
            skipFrom(
                "3",
                if (first.authFailed) "interrotto: autenticazione fallita, nessun nuovo tentativo"
                else "interrotto: connessione o share non disponibili",
            )
            return
        }

        var readFirst: TestResult? = null
        var writeFirst: TestResult? = null
        var planner: ReadPlanner? = null
        try {
            onStatus("Test 3: listing ricorsivo")
            val listing = testListing(env, conn)
            publish(listing.result)
            if (listing.result.status == Status.FAIL) {
                skipFrom("4", "listing fallito: cartella non leggibile")
                return
            }
            planner = ReadPlanner(listing.files)

            onStatus("Test 4: lettura sequenziale (1 e 4 flussi)")
            readFirst = testSequentialRead(env, conn, planner, "4", "Lettura sequenziale")
            publish(readFirst)

            onStatus("Test 5: accesso casuale")
            publish(testRandomAccess(env, conn, listing.files))

            onStatus("Test 6: scrittura, rinomina, rilettura, cancellazione")
            writeFirst = testWrite(env, conn, "6", "Scrittura")
            publish(writeFirst)

            onStatus("Test 7: copia lato server")
            publish(testServerSideCopy(env, conn))
        } finally {
            conn.close()
        }

        if (settings.requireEncryption) {
            publish(
                skippedResult(
                    "8", "Ripetizione con cifratura",
                    "la cifratura era gia' richiesta nel giro principale: per il confronto lascia l'interruttore spento",
                ),
            )
            return
        }
        runEncryptedRepeat(env, planner!!, readFirst, writeFirst)
    }

    /** Test 8: ripete 2, 4 e 6 con cifratura richiesta e confronta le velocita'. */
    private suspend fun runEncryptedRepeat(env: SmbEnv, planner: ReadPlanner, readFirst: TestResult?, writeFirst: TestResult?) {
        onStatus("Test 8: ripeto 2, 4 e 6 con cifratura richiesta")
        val out = testConnectAuth(env, "8a", "Connessione e autenticazione (cifratura richiesta)", encrypt = true)
        publish(out.result)
        val c = out.conn
        if (c == null || c.share == null) {
            c?.close()
            publish(skippedResult("8b", "Lettura sequenziale (cifratura)", "connessione cifrata non riuscita"))
            publish(skippedResult("8c", "Scrittura (cifratura)", "connessione cifrata non riuscita"))
            return
        }
        try {
            if (out.result.data["cifraturaAttiva"] != true) {
                val why = "il server non supporta o non ha negoziato la cifratura"
                publish(skippedResult("8b", "Lettura sequenziale (cifratura)", why))
                publish(skippedResult("8c", "Scrittura (cifratura)", why))
                return
            }
            onStatus("Test 8b: lettura sequenziale con cifratura")
            val readEnc = testSequentialRead(env, c, planner, "8b", "Lettura sequenziale (cifratura)")
            publish(readEnc)
            onStatus("Test 8c: scrittura con cifratura")
            val writeEnc = testWrite(env, c, "8c", "Scrittura (cifratura)")
            publish(writeEnc)
            publish(compare(readFirst, readEnc, writeFirst, writeEnc))
        } finally {
            c.close()
        }
    }

    private fun compare(readSigned: TestResult?, readEnc: TestResult, writeSigned: TestResult?, writeEnc: TestResult): TestResult {
        val b = ResultBuilder("8d", "Confronto sola firma / cifratura")
        fun ratio(signed: Double?, enc: Double?) = if (signed != null && enc != null && signed > 0) r2(enc / signed) else null
        fun row(signed: Double?, enc: Double?) = linkedMapOf<String, Any?>(
            "firmaMBs" to signed,
            "cifraturaMBs" to enc,
            "cifraturaSuFirma" to ratio(signed, enc),
        )
        b.put("lettura1Flusso", row(readSigned.dig("unFlusso", "MBs"), readEnc.dig("unFlusso", "MBs")))
        b.put("lettura4Flussi", row(readSigned.dig("quattroFlussi", "MBs"), readEnc.dig("quattroFlussi", "MBs")))
        b.put("scrittura", row(writeSigned.dig("scritturaMBs"), writeEnc.dig("scritturaMBs")))
        b.put("rilettura", row(writeSigned.dig("riletturaMBs"), writeEnc.dig("riletturaMBs")))
        b.put("nota", "rapporto < 1 = la cifratura rallenta; con fileGiaLettiInPrecedenza=true i valori di lettura possono essere influenzati dalla cache del NAS")
        return b.ok()
    }

    companion object {
        val PLAN = listOf(
            "2" to "Connessione e autenticazione",
            "3" to "Listing ricorsivo",
            "4" to "Lettura sequenziale",
            "5" to "Accesso casuale",
            "6" to "Scrittura",
            "7" to "Copia lato server",
            "8" to "Ripetizione con cifratura",
        )
    }
}

/** Legge un numero annidato nei dati di un risultato (es. dig("unFlusso", "MBs")). */
private fun TestResult?.dig(vararg keys: String): Double? {
    var cur: Any? = this?.data ?: return null
    for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
    return (cur as? Number)?.toDouble()
}

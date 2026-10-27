# Regole R8 dell'app. Quelle dei moduli e delle librerie (Hilt, Room, Compose, engine) viaggiano con i
# moduli: qui va solo cio' che riguarda l'app.

# --- Nomi e tracce ------------------------------------------------------------------------------------
# R8 e' attivo (taglia il codice morto, ottimizza, restringe le risorse) ma NON rinomina: come in
# FluidWeather, perche' l'app si prova su un telefono senza computer attaccato e la diagnostica in app
# (piano 2.7, esportazione manuale) deve poter mostrare una traccia leggibile. Offuscare non protegge
# nulla che qui valga la pena proteggere: la sicurezza sta nelle firme e nel Keystore, non nei nomi.
# Per riattivare l'offuscamento basta togliere queste due righe (e conservare mapping.txt di ogni release).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# --- Log delle librerie silenziati in release ----------------------------------------------------------
# Le librerie (SMB, Room, WorkManager...) possono scrivere su logcat con android.util.Log; in release
# quelle chiamate spariscono dal bytecode (docs/sicurezza.md, C22). Si tengono solo gli errori (e): un
# crash deve restare diagnosticabile. Il logger della galleria (GalleryLog) non passa da qui: in release
# non ha alcun sink.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static boolean isLoggable(...) return false;
}

# --- Fase 5A (P7, D12): il cancello del backup ----------------------------------------------------------------------
# "Backup solo sulle share di prova" e' la regola piu' importante del backup: il cancello (`AppBackupGate.stateOf`) chiama sempre la
# decisione per share (`FeatureFlags.backupMayWriteTo`) e risponde `TEST_ONLY` se la share non e' di prova. R8 non deve ne' togliere il
# cancello ne' fonderlo in un'altra classe (la decisione la tiene core-common/consumer-rules.pro: un membro tenuto non si inlinea): tools/check.ps1 (passo "D12 nella release") legge il
# bytecode della release (dexdump) e prova che `stateOf` chiama `backupMayWriteTo` e risponde TEST_ONLY. Il campo e la decisione li tiene
# core-common/consumer-rules.pro.
# R2 (revisione 5A): lo stesso `stateOf` chiama anche `backupMayWriteAs` (in release un account di prova non scrive in nessuna share, perche'
# la recinzione del telefono di D13 c'e' solo in debug) e risponde TEST_ONLY: il passo del dexdump lo prova nello stesso modo.
-keep class dev.pampa.mycloudgallery.backup.AppBackupGate {
    public *** stateOf(java.lang.String);
}

# --- Fase 5B (LA-5 della verifica funzionale): il cancello dei file in arrivo ------------------------------------------------------------
# "Salva sul NAS" scrive nella share di destinazione (anche quella comune): il suo cancello (`IncomingGate.stateOf`) applica D12 (share non di
# prova: `TEST_ONLY`) e R2 (release con un account di prova: `TEST_ONLY`) come quello del backup. R8 non deve ne' toglierlo ne' fonderlo in
# un'altra classe: tools/check.ps1 (stesso passo "D12 e R2 nella release") legge il bytecode della release e prova che `stateOf` chiama
# `backupMayWriteTo` e `backupMayWriteAs`, risponde TEST_ONLY e che qualche classe del backup lo istanzia. Le decisioni le tiene
# core-common/consumer-rules.pro.
-keep class dev.pampa.mycloudgallery.backup.IncomingGate {
    public *** stateOf(java.lang.String);
}

# --- Fase 7 (R20 della revisione di sicurezza): i cancelli degli album condivisi -----------------------------------------------------------
# "Album condivisi solo sulle share di prova" (D30) vale nella release finche' l'utente non da' il consenso (Q7): il cancello delle scritture
# (`AppAlbumWriteGate.blockFor`) chiama la decisione per share (`FeatureFlags.sharedAlbumsMayUse`, tenuta da core-common/consumer-rules.pro) e
# risponde `TEST_SHARES_ONLY`; quello della lettura (`FlagsAlbumReadGate.mayRead`) chiama la stessa decisione. R8 non deve ne' toglierli ne'
# fonderli ne' inlinearli: tools/check.ps1 (passo "D30 nella release") legge il bytecode della release e prova le due chiamate, la risposta,
# chi li istanzia e chi li chiama.
-keep class dev.pampa.mycloudgallery.albums.AppAlbumWriteGate {
    public *** blockFor(java.lang.String, kotlin.coroutines.Continuation);
}
-keep class dev.pampa.mycloudgallery.albums.FlagsAlbumReadGate {
    public boolean mayRead(java.lang.String);
}

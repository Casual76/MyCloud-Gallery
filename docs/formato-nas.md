# Formato dei dati condivisi sul NAS e modello di fiducia

| | |
|---|---|
| **Stato** | Fase 1.4, 12/10/2026, **aggiornato dopo la revisione di sicurezza e la sua verifica** (`docs/review/1.4-core-trust.md`, due giri) e **esteso nella Fase 2 (pacchetto P3)**: configurazione di famiglia completa (§5.3), segnalazione "perso" (tipo 7, §5.9), stato del dispositivo (tipo 8, §5.10), rifondazione dopo un reset (§4.10), residui N2, R2, R3, R4 della revisione chiusi (§2.3, §4.4). Formato **versione 1**. Nessun dato e' ancora stato scritto sul NAS. L'uso da parte dell'app: `docs/famiglia-dispositivi.md`. |
| **Codice** | modulo `:core-trust` (`dev.pampa.mycloudgallery.core.trust`); la mappa sezione → file → test e' al §14. |
| **Sigle** | `E-xx` = casi limite del piano; `C1`…`C23`, `L1`…`L17`, `S1`…`S12` = `docs/sicurezza.md`; `A1`…`I10` = voci della revisione. |
| **Regola** | Ogni costante citata qui esiste identica nel codice (`TrustFormat`, `TrustLimits`). Cambiarne una vuol dire cambiare questo documento. |

---

## 0. Il problema in una pagina

In famiglia non c'e' un server. Il NAS non esegue codice nostro: e' solo un disco condiviso via SMB. I
telefoni e i profili del tablet scrivono e leggono file nelle share (aree private e area comune), e ogni
dispositivo si costruisce da quei file la propria replica locale. Quindi:

1. **Chi scrive conta, non dove scrive.** Chiunque abbia accesso SMB in scrittura (un PC con un virus, un
   apparecchio della rete se le cartelle sono "pubbliche", un familiare) puo' mettere file nelle cartelle
   dell'app. Un dato vale solo se e' **firmato** da una chiave che la famiglia ha approvato.
2. **La radice di fiducia e' una chiave, non il NAS.** La *chiave di famiglia* firma il *registro*: l'elenco
   dei dispositivi approvati. Ogni dispositivo firma i propri dati con la *sua* chiave, chiusa nel Keystore.
3. **Il NAS non puo' farci tornare indietro.** Il registro ha un contatore che cresce e puo' solo diventare
   piu' severo verso i dispositivi gia' noti; ogni dispositivo ricorda l'ultimo visto, e un dispositivo nuovo
   riceve dal QR il minimo accettabile.
4. **Leggere non deve mai far male.** Tutto cio' che arriva dal NAS si legge con tetti di dimensione, in una
   forma unica (canonica, firme comprese), con percorsi validati; e nessun dato letto puo' da solo far
   cancellare o spostare un file.
5. **Semplice di proposito.** Un algoritmo di firma, un algoritmo di cifratura, un formato di busta, nessuna
   negoziazione, nessuna sovrascrittura: ogni file si pubblica una volta con un nome nuovo.

---

## 1. Attori e chiavi

| Chi | Chiave | Dove vive | Che cosa firma |
|---|---|---|---|
| **Famiglia** (l'admin) | *chiave di famiglia*, ECDSA P-256 | Sul dispositivo admin, cifrata da una chiave AES del Keystore usabile solo a telefono sbloccato (§4.2). Nel kit di recupero, cifrata col codice di recupero (§4.8). | Solo il **registro di famiglia** (configurazione + dispositivi). |
| **Ogni dispositivo** (ogni profilo Android e' un dispositivo) | *chiave del dispositivo*, ECDSA P-256 | Keystore Android, StrongBox se c'e' (altrimenti TEE). **Non estraibile.** | La propria richiesta di registrazione, le proprie teste di dati (indice, dati personali), prenotazioni, record del cestino, album condivisi, segnalazioni. |
| **Il NAS** | nessuna | – | Nulla. Fornisce solo file e l'**ora di riferimento** (§6). |

Un dispositivo non approvato (in attesa) **legge** ma nessuno accetta cio' che scrive (E-S14).

---

## 2. Struttura sul NAS

### 2.1 La cartella dell'app

Ogni share usata dall'app contiene **una** cartella `.mycloudgallery/`:

- **nascosta**: il punto iniziale la nasconde su Linux e, con l'opzione di Samba `hide dot files` (attiva di
  serie; da verificare sul firmware WD, domanda aperta 5 della revisione), anche ai client Windows;
- **nessun file ha un'estensione**: Twonky, Plex, DLNA e Foto di Windows scelgono i file per estensione, quindi
  non vedono nulla (E-I13);
- **i file del formato iniziano con una firma propria** (`MCGS`, `MCGB`, `MCGK`), mai con i byte di
  un'immagine: le miniature stanno *dentro* i segmenti. **Eccezione dichiarata** (I6): i file nel cestino
  (`trash/<id>/files/`) sono le foto e i video cestinati, rinominati senza estensione, e conservano i loro
  byte; li nascondono solo il punto iniziale della cartella e l'assenza di estensione. Se in Fase 4.5 si
  scopre che un servizio del NAS riconosce i media dal contenuto, quei file si avvolgono in un contenitore.

L'indice di una share sta **dentro quella share** (stessi permessi dei file, C19). Il registro di famiglia
sta solo nell'**area comune**, perche' tutti devono leggerlo.

### 2.2 L'albero

```
<share>/.mycloudgallery/
├── family/                               solo nell'area comune
│   ├── registry/<versione>               [1] registro di famiglia (chiave di famiglia)
│   ├── requests/<id dispositivo>         [2] richieste di registrazione (auto-firmate)
│   └── reports/<id dispositivo>/<vers.>  [7] segnalazioni "perso" (§5.9; copia anche nell'area privata)
├── devices/<id dispositivo>/
│   ├── status/<versione>                 [8] stato del dispositivo (§5.10), solo nell'area comune
│   ├── index/head/<versione>             [3] testa dell'indice di questa share
│   ├── index/seg/<impronta>              segmenti (contenitori MCGB), nome = impronta
│   ├── personal/head/<versione>          [3] dati personali: SOLO nell'area privata del proprietario
│   └── personal/seg/<impronta>
├── leases/<impronta risorsa>/<gruppo>/<generazione>   [4] prenotazioni, gruppi di 256 generazioni
├── trash/<id dispositivo>/
│   ├── rec/<id elemento>                 [5] record firmato del cestino
│   └── files/<id elemento>               il file cestinato, rinominato senza estensione
├── albums/<id album>/<id dispositivo>/head/<versione>   [6] album condivisi (Fase 7), area comune
└── tmp/                                  temporanei della pubblicazione atomica

<area privata dell'admin>/.mycloudgallery/recovery/<id famiglia>/<impronta della chiave>   kit di recupero (§4.8)
<area privata di un account>/.mycloudgallery/family/reports/<id dispositivo>/<vers.>   [7] copia della segnalazione (prova di possesso, §5.9)
```

`[n]` e' il tipo del file firmato (§5.1). Ogni dispositivo scrive **solo sotto i propri id** (piu' le
richieste e le prenotazioni); l'admin scrive il registro.

### 2.3 Come si scrive e come si legge (l'interfaccia `NasFileSystem`)

`core-trust` vede una share attraverso tre operazioni, e **solo tre**:

| Operazione | Contratto |
|---|---|
| `list(dir, limite)` | Nomi dei figli, al massimo `limite`, con l'indicazione "troncato". Cartella assente = vuota. L'ordine non e' garantito. |
| `read(path, maxBytes)` | Il file intero se non supera `maxBytes`, altrimenti "troppo grande". Mai allocare piu' di `maxBytes + 1` byte, qualunque dimensione dichiari il server. |
| `publishExclusive(path, bytes)` | Crea il file **solo se il nome non esiste** (anche con maiuscole diverse: Samba non le distingue per i client Windows); per chi legge e' atomico. Su SMB (Fase 2.1): temporaneo in `tmp/` aperto con `FILE_CREATE` (in Samba `O_CREAT\|O_EXCL`, atomico), scrittura, flush, verifica della lunghezza, `rename(path, replaceIfExist = false)`; collisione = "esiste gia'". Le cartelle mancanti si creano trattando "esiste gia'" come successo. |

Non ci sono cancellazione, spostamento o sovrascrittura (un test lo verifica, §9). Conseguenze:

- **Serie numerate, lette per nome.** I dati che cambiano (registro, teste, album) sono serie di file
  `<versione>` di 20 cifre con zeri a sinistra, da 1 a `MAX_VERSION` (10^9). Chi legge **non si fida
  dell'elenco della cartella**: legge direttamente `v+1`, `v+2`, … fino al primo nome assente (revisione M1:
  creare migliaia di nomi non nasconde piu' una versione). L'elenco serve solo come indizio per le versioni
  oltre un buco (una versione cancellata), e un elenco troncato e' segnalato.
- **Il numero lo sceglie chi pubblica: il primo nome libero.** Un nome gia' occupato da un file non valido
  (spazzatura messa da chi ha accesso SMB) si scavalca e si riporta in Diagnostica; quindi le versioni
  **possono avere buchi** (revisione A2: prima un nome occupato bloccava per sempre la pubblicazione di una
  revoca). Chi legge scavalca allo stesso modo i nomi non validi. Si scavalcano al massimo
  `MAX_JUNK_RUN` (32) nomi non validi di fila: oltre, chi legge si ferma per quel giro (limite al lavoro che
  uno scrittore ostile puo' imporre, B5) e chi pubblica riceve l'elenco esatto dei file da cancellare.
- **Due scrittori sullo stesso numero: vince il primo.** L'altro trova al suo posto una versione valida piu'
  nuova e deve risincronizzarsi (due admin che approvano insieme non si sovrascrivono). Prima di firmare al
  primo nome libero si leggono **per nome** i `MAX_JUNK_RUN` (32) nomi successivi e poi gli indizi dell'elenco
  **oltre** quel nome, con lo stesso tetto di 32 file non validi di fila (R4): una versione valida dopo un buco
  vuol dire che la base e' vecchia, e non si pubblica nel buco (verifica N2). Se l'elenco era troncato
  (inondazione) o gli indizi si sono fermati per la spazzatura, non si puo' escludere una versione nascosta
  oltre un buco piu' largo di 32 nomi: si pubblica (il ramo dell'admin riceve la modifica) ma **non in
  silenzio** (`CommitResult.Published.unverifiedBeyond`, avviso all'admin e in Diagnostica).
- **Contenuti per impronta.** I segmenti si chiamano con lo SHA-256 del loro contenuto: immutabili, senza
  conflitti.
- **Pulizia dei propri file vecchi** (teste superate, segmenti non piu' elencati, generazioni di
  prenotazione superate, kit della chiave ruotata) arriva in Fase 4.1/2.4 con un'interfaccia separata, che
  permette a ciascuno di cancellare **solo cio' che ha scritto lui** (si riconosce dal firmatario e dal
  posto), dopo un periodo di grazia, come azione locale.

### 2.4 Nomi

| Nome | Forma | Validazione in lettura |
|---|---|---|
| id (dispositivo, famiglia, elemento del cestino, album) | 128 bit in base32 (§3.1): 26 caratteri `[0-9a-z]` | decodifica stretta di 16 byte |
| id di un dispositivo | primi 128 bit di SHA-256(chiave pubblica): **auto-certificante** | deve coincidere con la chiave |
| versione / generazione / gruppo | 20 cifre ASCII, valore tra 1 e 10^9 | esattamente 20 cifre, entro il tetto |
| impronta | SHA-256 in base32: 52 caratteri | decodifica stretta di 32 byte |
| impronta di una chiave (kit) | 26 caratteri base32 (§4.1) | uguale all'impronta attesa |

Ogni nome letto da un elenco che non rispetta la sua forma si **ignora** (e si conta in Diagnostica): un
attaccante puo' riempire una cartella di nomi strani, ma non farli interpretare.

---

## 3. Codifiche

### 3.1 Base32

L'unica codifica binario-testo del formato: Base32 di Crockford, **minuscolo, senza riempimento**, alfabeto
`0123456789abcdefghjkmnpqrstvwxyz` (senza `i l o u`). La decodifica e' **stretta**: lunghezza esatta per il
numero di byte e bit di coda a zero, cosi' ogni valore ha una sola scrittura. Solo per cio' che una persona
digita (codice di recupero, codice di approvazione, impronta) si accettano maiuscole, spazi, trattini e gli
alias `O→0`, `I/L→1`, e poi si passa comunque dalla decodifica stretta.

### 3.2 JSON canonico

Il contenuto dei file firmati e' un sottoinsieme di JSON con **una sola forma possibile** per ogni valore:

- UTF-8 valido, senza BOM; **nessuno spazio** fuori dalle stringhe; niente dopo la fine;
- tipi: oggetto, array, stringa, intero a 64 bit, booleano. **Niente `null`**, niente numeri con virgola o
  esponente, niente `-0`, niente zeri iniziali;
- chiavi: lettera ASCII seguita da lettere o cifre ASCII (max 32 caratteri), **in ordine crescente**,
  **mai ripetute**;
- stringhe: si scrivono con escape solo `"`, la barra rovesciata e i caratteri di controllo (le forme corte
  per backspace, form feed, a capo, ritorno, tabulazione; la forma a sei caratteri con `00` e due cifre
  esadecimali minuscole per gli altri sotto U+0020); tutto il resto letterale; un surrogato spaiato e' un
  errore.

Chi verifica: (1) controlla la firma sui **byte**; (2) legge con il parser stretto e con i tetti (§7);
(3) **riserializza e pretende gli stessi byte**. Cosi' due programmi (o due versioni dell'app) non possono
mai leggere lo stesso file firmato in due modi, e un dispositivo compromesso non puo' sfruttare differenze
tra parser.

I campi di ogni oggetto sono fissati dal tipo (§5): **un campo sconosciuto e' un errore**, non si ignora.
Una novita' nel formato aumenta la versione (§10).

Il parser e' scritto nel modulo (`CanonicalJson`) invece di usare `kotlinx.serialization`: serve il
controllo di profondita', duplicati, numeri e forma canonica, e nessuna libreria generica lo garantisce.

---

## 4. Modello crittografico

### 4.1 Algoritmi (nessuna negoziazione)

| Uso | Algoritmo | Perche' |
|---|---|---|
| Firme | **ECDSA P-256 con SHA-256**, firma DER **in forma canonica** (sotto) | Keystore e StrongBox lo supportano su tutti i dispositivi da Android 6; minSdk e' 29. **Ed25519** nel Keystore arriva solo da Android 13 (API 33) e StrongBox non lo supporta: con Ed25519 le chiavi dei dispositivi Android 10-12 starebbero fuori dall'hardware. |
| Cifratura (kit, cassaforte) | **AES-256-GCM**, tag di 128 bit | Standard, autenticata, nel Keystore e in JCA. |
| Derivazione dal codice di recupero | **HKDF-SHA256** | Il codice ha 128 bit casuali: una derivazione lenta (PBKDF2, scrypt) non aggiungerebbe nulla. |
| Impronte | **SHA-256** | – |

Tutto con JCA e il Keystore di Android: **nessuna libreria crittografica aggiunta**. Un algoritmo diverso
vorrebbe dire una nuova versione del formato, mai un campo "algoritmo" da negoziare.

**Forma canonica delle firme** (revisione A3). ECDSA ammette due firme valide per lo stesso messaggio,
`(r, s)` e `(r, n − s)`, e la seconda si ottiene dalla prima **senza chiave**. Per questo:

- una firma e' accettata solo come `SEQUENCE { INTEGER r, INTEGER s }` in DER minimo (lunghezze corte, interi
  positivi senza zeri superflui), con `1 ≤ r < n` e `1 ≤ s ≤ n/2` ("s basso"); ogni altra forma e' una firma
  non valida (`EcdsaDer`);
- chi firma normalizza sempre (il Keystore non garantisce `s` basso: il test sull'S25 lo verifica);
- in piu', l'**identita' di un file firmato non dipende dai byte della firma** (§5.1): anche se un giorno una
  seconda firma valida passasse, sarebbe lo stesso dato e non un'"equivocazione".

**Chiavi pubbliche.** Si accettano solo come SubjectPublicKeyInfo P-256 con punto non compresso: 91 byte, i
primi 27 fissi, e con un punto della curva (coordinate minori di p e `y² = x³ − 3x + b mod p`, controllato
da noi e non dal provider: JVM dei test e Conscrypt del telefono decidono allo stesso modo, e ogni chiave ha
una sola codifica, B3).

L'**impronta** di una chiave (da mostrare, da mettere nel QR, da confrontare) sono i primi 26 caratteri
base32 di SHA-256(chiave): 130 bit. Dove la chiave intera e' nota (registro gia' accettato) si confronta la
chiave intera, non l'impronta (I7).

### 4.2 La chiave di famiglia

- Generata dall'app dell'admin all'onboarding (piano 2.2, passo 12), in software (JCA), e **subito** cifrata
  in due posti: la *cassaforte* sul dispositivo admin e il **kit di recupero** (§4.8).
- La **cassaforte**: AES-256-GCM con una chiave del Keystore, StrongBox se c'e', non estraibile e
  **utilizzabile solo a telefono sbloccato** (`setUnlockedDeviceRequired`, revisione M3: la chiave di famiglia
  serve solo in operazioni interattive, quindi un telefono rubato acceso e bloccato, o uno strumento forense,
  non la decifra). "Solo sbloccato" non vale nulla su un telefono senza blocco schermo sicuro: la cassaforte
  **rifiuta di lavorare** se il dispositivo non ha PIN, sequenza o password (`isDeviceSecure`, verifica M3 e
  domanda 9), quindi **il dispositivo admin deve avere un blocco schermo sicuro** (l'onboarding lo chiede
  prima di creare la chiave di famiglia; per gli altri dispositivi resta l'avviso di E-S3). Il file sta nello
  spazio privato dell'app, escluso da backup e trasferimenti. Chiedere in piu' l'impronta o il PIN a ogni uso
  e' la decisione 2 del §13: protegge anche dal malware che agisce a telefono sbloccato.
- E' **estraibile per costruzione**: deve poter stare nel kit e su un secondo dispositivo admin. In chiaro
  esiste solo in memoria, durante un'operazione dell'admin.
- Firma **solo** il registro di famiglia. Non firma dati dell'indice: per quelli il dispositivo admin usa la
  propria chiave di dispositivo, come tutti.
- La sua impronta e' la radice di fiducia che il QR porta ai dispositivi nuovi (§4.7).

### 4.3 Le chiavi dei dispositivi

- Una per dispositivo, e una per **ogni profilo** Android del tablet (ogni profilo ha il suo Keystore).
- Generata nel Keystore (`PURPOSE_SIGN`, P-256, SHA-256), **StrongBox se c'e'**, altrimenti TEE. Il test
  sull'S25 verifica che la chiave privata non sia esportabile e che, se il telefono ha StrongBox, stia li'.
- **Utilizzabile senza sblocco**: i lavori notturni (indice, prenotazioni) firmano a telefono bloccato, come
  per le credenziali del NAS (`sicurezza.md` §8). Prezzo dichiarato in §12. Il test sul telefono verifica
  questa politica (e quella opposta della cassaforte).
- **L'id del dispositivo e' derivato dalla chiave** (§2.4): non si puo' rivendicare l'id di un altro senza la
  sua chiave, e un dispositivo reinstallato o ritrovato ha una chiave nuova, quindi un id nuovo (E-O7, E-O8,
  E-S2). Non esiste "riapprovare" una chiave revocata.
- Distruggibile (`destroyDeviceKey`) quando il dispositivo si scopre segnato come perso (Fase 2.4).

### 4.4 Il registro di famiglia

Un unico documento firmato dalla chiave di famiglia con **configurazione + dispositivi** e un contatore
`version` che cresce a ogni modifica (un solo contatore: niente incoerenze tra "configurazione N" e
"registro M"). Si pubblica in `family/registry/<version>` al primo nome libero (§2.3).

**Accettazione** (`RegistryVerifier.accept`), in quest'ordine:

1. busta integra (§5.1) e di tipo registro, firma in forma canonica;
2. se il contenuto firmato e' identico a quello gia' accettato, e' la stessa versione (nessun errore);
3. la chiave che firma e' **quella attesa**: la chiave intera del registro gia' accettato (o il successore
   annunciato, §4.10), oppure, per un dispositivo nuovo, quella con l'impronta dell'ancora (§4.7);
4. contenuto canonico e ben formato (§5.3), famiglia giusta;
5. **anti-rollback**: versione piu' bassa dell'ultima accettata → `ROLLBACK`; stessa versione con contenuto
   firmato diverso → `EQUIVOCATION`; dispositivo nuovo: versione almeno quella del QR (`minRegistryVersion`);
6. **anti-regressione** (revisione A1): rispetto al registro gia' fidato, il nuovo puo' solo diventare **piu'
   severo** verso i dispositivi che quello conosce. Ogni dispositivo noto resta presente, con la stessa
   chiave e lo stesso account del NAS, con uno stato uguale o piu' severo (§4.6); un dispositivo che resta
   dismesso puo' solo perdere teste congelate; l'ora di emissione non torna indietro. Possono cambiare la
   configurazione, i nomi, il segno di admin, e possono arrivare dispositivi nuovi. Altrimenti `REGRESSION`.
   **Unica eccezione** (verifica della revisione, R3): l'account di un dispositivo puo' cambiare solo se diventa
   (o e') revocato o perso, perche' di un dispositivo cosi' nessun dato vale e l'account non da' potere; senza
   l'eccezione un dispositivo approvato in due rami con due account non si poteva piu' revocare. La regola e'
   transitiva, quindi vale anche quando si saltano versioni;
7. il nome del file deve coincidere con la versione dichiarata.

Senza il punto 6 bastava che un admin lavorasse su una base vecchia (il secondo dispositivo admin rimasto
indietro, o un admin appena recuperato, e chiunque con accesso SMB poteva cancellare la versione con la
revoca) perche' la versione successiva **riapprovasse in silenzio** un dispositivo revocato. Ora quella
versione e' rifiutata da tutti i dispositivi che hanno visto la revoca, con un allarme.

`EQUIVOCATION` e `REGRESSION` di un registro **firmato dalla famiglia** producono un **ramo**
(`RegistryFork`): allarme in Diagnostica (`tamperingSuspected`) e, all'admin, l'elenco dei dispositivi che
compaiono solo li' e l'avviso se il ramo ha una configurazione diversa (`configDiffers`, domanda 10).

**Un registro "contiene" un ramo** (`dominates`) se nessun dispositivo del ramo manca o e' meno severo (stessa
chiave, stesso account, stato uguale o piu' severo, teste congelate non in piu'), e il ramo non contiene una
rotazione. Un registro che contiene un ramo e' accettato anche dai dispositivi rimasti su quel ramo. E' la
regola che rende sempre possibile **uscire** da qualunque numero di rami, di qualunque lunghezza (verifica
N1: prima un ramo di due o piu' versioni bloccava ogni pubblicazione, revoche comprese). L'admin, sulla
**stessa modifica** che voleva pubblicare (per esempio una revoca):

- se riconosce i rami (tipicamente il secondo admin rimasto indietro), li **unisce tutti**
  (`FamilyAuthority.mergeForks`): ogni dispositivo con lo stato piu' severo, le teste congelate in comune, la
  configurazione dell'admin (se il ramo ne aveva un'altra, la schermata lo mostra e l'admin sceglie);
- se non li riconosce (la chiave di famiglia l'ha usata qualcun altro), li **rifiuta**
  (`FamilyAuthority.rejectForks`): come l'unione, ma i dispositivi che esistono solo nei rami entrano
  **revocati**; poi **reset della famiglia** (§4.10).

In entrambi i casi la versione pubblicata contiene i rami, quindi la accettano tutti i dispositivi, la
modifica dell'admin esce e nessuna revoca va persa. Nessuna unione avviene da sola.

Un dispositivo che nei due rami ha **account diversi** esce dall'unione (o dal rifiuto) almeno revocato, con
l'account del ramo dell'admin e senza segno di admin (R3). Un ramo che contiene un **annuncio di rotazione**
non si puo' "contenere": i dispositivi che l'hanno visto si fidano ormai di un'altra chiave e vanno comunque
riconfigurati (reset). Unione e rifiuto ne prendono solo gli stati piu' severi dei dispositivi gia' noti (quelli
che esistono solo li' entrano revocati) e la pubblicazione lo scavalca (`RegistryDraft.acknowledgedForks`): cosi'
almeno il ramo dell'admin riceve la sua modifica, per esempio una revoca (R2; prima l'admin restava bloccato fino
al reset). I rami che lo stato
finale di un dispositivo contiene gia' sono **storia**: chi risale le versioni li attraversa senza allarme e
senza contarli come spazzatura (N5).

**Lettura dal NAS** (`RegistrySync`): per nome dalla versione successiva a quella nota (§2.3), poi gli indizi
dell'elenco, e infine il controllo della **versione fidata**: se il suo file e' sparito dal NAS lo si
**ripubblica** (`healed`), cosi' cancellare la versione con una revoca non basta a nasconderla a chi non l'ha
ancora vista; se al suo posto c'e' un altro contenuto firmato dalla famiglia, e' un'equivocazione (allarme e
ramo); se al suo posto c'e' un file non valido, e' una **sostituzione** (`trustedVersionReplaced`, allarme):
sostituirla con spazzatura ne ritarda l'arrivo a chi non l'ha ancora vista fino alla ripubblicazione
dell'admin, che l'allarme fa partire (`FamilyAuthority.reissue`: lo stesso contenuto con un numero nuovo;
verifica N3). **Lo stato locale non e' mai sostituito da qualcosa di piu' vecchio, non verificato o che torna
indietro**: nel caso peggiore si continua con l'ultimo registro accettato e la Diagnostica lo dice
(`newerUnverified`, `listingTruncated`, `incomplete`, `needsAppUpdate`, `tamperingSuspected`).

**Pubblicazione** (`FamilyAuthority.commit`): l'admin prepara una modifica sulla base del registro fidato,
poi controlla il nome della base (un altro contenuto firmato li' e' un ramo) e legge per nome `v+1`, `v+2`,
…: versione valida piu' nuova → `NewerExists` (risincronizzarsi e rifare la modifica: un admin non firma mai
su una base che sa vecchia); registro di un'app piu' nuova → `NeedsAppUpdate`; ramo che la modifica contiene
gia' → si scavalca; spazzatura → si scavalca e si riporta; troppa spazzatura di fila → `Blocked` con
l'elenco esatto dei file da cancellare. Al primo nome libero legge per nome i 32 nomi successivi e poi gli
indizi dell'elenco oltre quel nome, con il tetto alla spazzatura (N2, R4); se non ha potuto controllare tutto lo
dice (`unverifiedBeyond`). Se restano rami che la modifica non contiene (e che l'admin non ha gia' trattato, R2)
→ `Fork` con **tutti** i rami trovati; altrimenti firma con l'ora di emissione non inferiore a quella di ogni
ramo scavalcato e pubblica.

**Memoria locale**: ogni dispositivo salva nello spazio privato i byte dell'ultimo registro accettato e
l'impronta della chiave che lo ha firmato; al riavvio li riverifica (`RegistryVerifier.restore`).

**Le voci non si tolgono mai**: un dispositivo revocato, perso o dismesso resta nel registro (la regola del
punto 6 lo richiede, e "sconosciuto" non deve mai poter tornare "approvato"). Il tetto e' 256 dispositivi.

**Configurazione** (estesa nella Fase 2, §5.3): IP letterale del NAS (`nasHost`), nome, GUID, prefissi ULA di
casa, alimentazione, MAC per il WoL, area comune, esclusioni, preset e qualita' di default. Indirizzo del NAS e
identita' (E-R7, revisione M5): una firma su un file copiabile **non prova chi lo sta servendo**, quindi "il
NAS a quell'indirizzo serve un registro firmato" non e' un criterio. L'identita' del server sta nella sessione
SMB 3.1.1 con firma obbligatoria (la chiave di sessione dimostra che il server conosce la password
dell'account) su una rete riconosciuta o in VPN (C2); un indirizzo nuovo si accetta solo se arriva da una
configurazione firmata dall'admin, oppure con la conferma esplicita dell'utente; mai dalla sola scoperta in
rete.

### 4.5 Registrazione e approvazione (piano 2.3)

1. Il dispositivo nuovo crea la sua chiave nel Keystore e pubblica `family/requests/<suo id>`: una
   **richiesta firmata da se stessa** (famiglia, id, chiave, nome, modello, account del NAS, ora). La firma
   prova solo che chi la scrive possiede quella chiave.
2. L'admin vede le richieste valide (firma giusta, id = chiave, nome del file = id, famiglia giusta; le
   altre si scartano e si contano). Se la cartella e' stata riempita di nomi, la richiesta si legge **per id**
   (`PendingRequests.readOne`), con l'id mostrato dal dispositivo nuovo.
3. **Prova di presenza** (revisione M2). L'admin approva solo con una di queste (`ApprovalProof`):
   - **scansione** del QR mostrato dal dispositivo nuovo con la sua chiave intera (via consigliata quando si
     e' nella stessa stanza);
   - **codice digitato**: 16 caratteri (`ABCD-EFGH-JKMN-PQRS`, primi 80 bit di SHA-256 della chiave,
     `ApprovalCodes`) che l'admin **digita** leggendoli sullo schermo del dispositivo nuovo; non si
     "confrontano a vista", perche' a vista si controllano pochi caratteri. Requisito per la schermata della
     Fase 2.3 (verifica N6): `ApprovalProof.TypedCode` si costruisce **solo** dal testo digitato; il campo
     parte vuoto, non si precompila ne' si incolla dalla richiesta, e un test dell'interfaccia lo verifica.

   La chiave del richiedente e' leggibile nell'area comune prima dell'approvazione, quindi chi vuole farsi
   approvare al posto del dispositivo vero puo' cercare con calma una chiave con lo stesso codice: con 50 bit
   (prima versione) bastavano giorni di GPU, con 80 bit servono circa 10^24 tentativi.
4. **Finestra di 15 minuti**: l'app dell'admin annota quando ha visto la richiesta per la prima volta (ora del
   NAS, in locale: non `requestedAt`, che scrive il richiedente) e oltre 15 minuti non la approva; il
   dispositivo nuovo rifa' la richiesta con una chiave nuova (`ApprovalWindow`).
5. **Sosia**: le richieste il cui nome coincide, dopo NFKC, rimozione di ZWJ e selettori, minuscole e spazi,
   con un'altra richiesta o con un dispositivo gia' registrato sono segnalate all'admin (`LookAlike`).
6. **Account del NAS**: e' dichiarato dal richiedente (revisione B6). L'admin lo vede e lo conferma. Il primo
   potere legato all'account, la segnalazione "perso" del proprietario (§5.9), chiede la **prova di possesso**:
   una copia firmata nell'area privata di quell'account, dove scrive solo chi ne ha la password. La stessa prova
   servira' all'unione dei dati personali (Fase 4.7).
7. L'admin firma la versione successiva del registro con il dispositivo `approved`.

Fino all'approvazione il dispositivo legge soltanto: i suoi dati, se ne scrivesse, sono rifiutati da tutti
(`SIGNER_NOT_APPROVED`) e l'app non li scrive (`RegistryState.canWrite`).

### 4.6 Stati, revoca e destino dei dati

| Stato | Si entra da | Dati del dispositivo accettati dagli altri |
|---|---|---|
| `approved` | approvazione | tutti (con i controlli del loro tipo) |
| `retired` (dismesso) | `approved` | **solo le teste congelate** elencate dall'admin (`frozenHeads`): impronte dei contenuti firmati delle teste che l'admin aveva gia' verificato. Nient'altro, nemmeno teste piu' nuove. |
| `revoked` (revocato) | `approved`, `retired` | **nessuno**, qualunque data dichiarino |
| `lost` (perso) | `approved`, `retired`, `revoked` | nessuno; in piu' il dispositivo stesso, quando legge il registro, distrugge la chiave e poi i dati locali (Fase 2.4) |

Gli stati sono in ordine di severita' e vanno solo avanti (`DeviceStatus.canBecome`), sia per chi firma sia
per chi verifica (§4.4 punto 6); non si torna mai ad `approved`.

**Perche' "nessuno" e non "quelli firmati prima della revoca":** i dati non hanno un'ora certificata; un
dispositivo compromesso puo' scrivere qualunque data. Distinguere "prima" e "dopo" sarebbe una promessa
falsa. Il costo e' accettabile perche' quasi tutto cio' che firma un dispositivo e' **ricalcolabile**:

| Dato del dispositivo revocato | Che cosa succede |
|---|---|
| Teste e segmenti dell'indice | ignorati; il lavoro torna da fare e lo rifanno gli altri indicizzatori/preparatori (le prenotazioni di quel dispositivo non contano piu'). |
| Dati personali | ignorati i suoi contributi; restano quelli degli altri dispositivi dello stesso account (Fase 4.7 decide se ognuno ripubblica lo stato gia' unito). |
| Prenotazioni | ignorate: la risorsa e' libera. |
| Record del cestino | ignorati: i file restano nel cestino e **non vengono mai eliminati in automatico** (la pulizia la fa solo chi ha cestinato, §9); in "Eliminati di recente" compaiono come "senza record valido" e decide l'utente. |
| Album condivisi (Fase 7) | i suoi contributi alla definizione sono ignorati; i file restano dove sono. |

**Dismissione** (telefono sostituito, funzionava): l'admin la sceglie quando il dispositivo non e'
sospetto; congela le teste che il suo dispositivo ha gia' accettato, cosi' l'indice gia' fatto non si
ricalcola. L'impronta congelata e' quella del contenuto firmato, non dei byte del file: una copia della stessa
testa con un'altra firma valida resta valida (A3).

### 4.7 L'ancora di fiducia di un dispositivo nuovo

Il QR dell'app (piano 2.3) porta, oltre a indirizzi, utente, aree e preset (mai password), l'**ancora**
(`RegistryState.toAnchor`):

- l'**id della famiglia**;
- l'**impronta della chiave che ha firmato la versione corrente** del registro;
- la **versione minima del registro** (quella corrente quando il QR viene mostrato).

La rotazione si pubblica **completa** (`FamilyAuthority.rotate`): l'annuncio firmato dalla chiave vecchia e
subito una versione firmata dalla nuova, cosi' il QR mostra gia' la chiave nuova (B4). Finche' la versione
corrente e' solo l'annuncio, **non c'e' QR** (`toAnchor` restituisce null, verifica N4): un QR li' ancorerebbe
i dispositivi nuovi alla chiave vecchia, forse sospetta. Lo stesso QR ("stato della famiglia") lo puo' mostrare **qualunque dispositivo approvato**: e'
l'ancora del recupero dell'admin (§4.8).

Senza QR, la procedura manuale chiede l'id della famiglia e l'impronta, che l'utente **digita per intero
leggendole dallo schermo dell'admin**: non si precompilano mai leggendole dal NAS (premere "Conferma" su un
valore letto dal NAS non verifica nulla, revisione B9). La versione minima vale 0: e' accettato qualunque
registro firmato da quella chiave, anche uno vecchio, e la Diagnostica lo segnala finche' il dispositivo non
vede la versione corrente.

Un QR di un'altra famiglia o con un'impronta diversa da quella che firma il registro sul NAS non porta a
nulla (E-O5); una configurazione cambiata dopo il QR vale quella firmata sul NAS (E-O6).

### 4.8 Il kit di recupero (proposta 15)

Due pezzi, **servono entrambi**:

1. **Il foglio**: il **codice di recupero**, 128 bit casuali generati dall'app (mai scelti dall'utente) piu'
   10 bit di controllo che scoprono gli errori di battitura (ne lascia passare circa uno su mille) prima di
   tentare la decifratura, stampato come 7 gruppi di 4 caratteri (`ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345`);
   e, **non segreti, l'id della famiglia e l'impronta della chiave di famiglia**, che al recupero fanno da
   ancora indipendente dal NAS. Si mostra solo nella schermata protetta dell'onboarding e l'utente riscrive
   il codice per verificarlo (2.2).
2. **Il file cifrato** `recovery/<id famiglia>/<impronta della chiave>`, nell'**area privata dell'admin** sul
   NAS (gli altri familiari non lo leggono). Un file per chiave: dopo una rotazione il kit nuovo ha un posto
   suo (revisione M4).

```
0    4   "MCGK"
4    1   versione del formato
5    16  id della famiglia
21   32  SHA-256 della chiave pubblica di famiglia
53   8   ora di creazione (ora del NAS, ms, big endian)
61   16  sale
77   12  nonce
89   ... AES-256-GCM( chiave = HKDF-SHA256(codice, sale, "mycloudgallery/recovery-kit/v1" || id famiglia),
                      dati aggiuntivi autenticati = byte 0..88,
                      testo = lunghezza + chiave pubblica + lunghezza + PKCS#8 della privata )
```

Aprire il kit (`RecoveryKit.open`) **richiede l'id della famiglia e l'impronta attesi** (dal foglio, o dal QR
di stato): un kit con un'altra chiave, messo nell'area dell'admin da chi conosce il codice, e' rifiutato
prima ancora di decifrare (revisione M7). Poi controlla struttura, versione, autenticazione GCM (codice
sbagliato o file alterato danno lo stesso errore), che la chiave dentro sia quella dichiarata e che privata e
pubblica stiano insieme (firma di prova). Tetto: 1 KiB. L'intestazione in chiaro (id, impronta, ora) si legge
senza codice con `RecoveryKit.inspect`: il recupero elenca solo i kit la cui intestazione coincide con il nome
del file e della cartella.

**Recupero dell'admin** (E-S13, piano 2.4), senza nulla del vecchio telefono:

1. sul dispositivo nuovo si configura l'account dell'admin e si apre il kit con codice, id e impronta del
   foglio: torna la **stessa** chiave di famiglia;
2. l'ancora del registro e' il **QR di stato** mostrato da un dispositivo approvato qualunque (versione
   corrente): cosi' chi ha cancellato le versioni recenti non fa partire l'admin recuperato da un registro
   vecchio. Se nessun dispositivo e' a portata, si parte con versione minima 0 e la Diagnostica avverte
   finche' non si vede un dispositivo aggiornato;
3. si sincronizza il registro dal NAS e si controlla che la chiave aperta dal kit sia **quella che firma** il
   registro sincronizzato (`RegistryState.acceptsRecoveredKey`): se no, il QR di stato veniva da un
   dispositivo che ancora la fiducia a un'altra chiave, e ci si ferma subito (nota della verifica su M7);
4. si approva il dispositivo nuovo (`admin = true`) con la scansione della sua chiave; il vecchio si segna
   come perso o revocato. Le impronte nei QR gia' distribuiti restano valide.

### 4.9 Il secondo dispositivo admin

E' un dispositivo che possiede la chiave di famiglia: la ottiene **aprendo il kit** (stesso foglio, stesso
file) ed e' segnato `admin = true` nel registro (informativo: il potere vero e' la chiave). Il caso previsto
e' un profilo dell'admin sul tablet, con lo stesso account del NAS (quindi legge il file del kit). Due admin
non si sovrascrivono (§2.3) e, se uno dei due lavora su una base vecchia, la sua versione non annulla le
revoche dell'altro: diventa un ramo da unire (§4.4).

### 4.10 Rotazione e reset

- **Rotazione** (manutenzione, o il foglio del codice forse visto da qualcuno, ma il file nell'area privata
  al sicuro): la versione pubblicata dalla rotazione, firmata dalla chiave attuale, contiene `successorKey`;
  da li' in poi firma solo la nuova; la rotazione si pubblica completa, con una versione firmata dalla nuova
  subito dopo l'annuncio (`FamilyAuthority.rotate`, §4.7). Ogni dispositivo che accetta l'annuncio smette per
  sempre di accettare la vecchia chiave. Subito dopo si genera e si stampa un kit nuovo, **con un codice
  nuovo**, che si pubblica nel suo posto (`recovery/<famiglia>/<impronta nuova>`); il kit vecchio va
  **cancellato** dall'area privata dell'admin: la chiave vecchia vale ancora per i dispositivi che non hanno
  visto l'annuncio. `RecoveryKitFiles.obsolete` elenca i percorsi esatti dei kit da cancellare; finche' la
  Fase 2.4 non porta la cancellazione come azione locale confermata, l'app li mostra e la guida chiede di
  cancellarli a mano dal PC, e la rotazione non risulta conclusa finche' l'elenco non e' vuoto (verifica M4). Un QR mostrato subito dopo la
  rotazione funziona (§4.7). Se qualcuno cancella l'annuncio dal NAS, chi l'aveva visto lo ripubblica (§4.4).
  La rotazione **non** protegge da una chiave gia' rubata: il ladro potrebbe annunciare un suo successore.
- **Reset della famiglia** (chiave di famiglia persa senza kit, o rubata, o un ramo firmato che l'admin non
  riconosce): nuova chiave, **nuovo id di famiglia**, nuovo kit; ogni dispositivo deve essere riconfigurato
  con il nuovo QR (o l'impronta digitata a mano) e riapprovato. La fondazione della famiglia nuova sta nella
  **stessa cartella** del registro (`FamilyAuthority.refound`): al primo nome libero dopo l'ultima versione
  nota della famiglia vecchia, seguito da 32 nomi liberi. Il QR nuovo porta quel numero come versione minima,
  quindi i dispositivi nuovi partono da li'; le versioni della famiglia vecchia, firmate da un'altra chiave,
  per loro sono spazzatura (e viceversa). I dati legati al vecchio id di famiglia non
  valgono piu': ogni dispositivo, riapprovato, ripubblica le proprie teste col nuovo id (i segmenti,
  identificati per impronta, restano gli stessi e non si ricalcolano). Un dispositivo non ancora migrato
  continua a fidarsi della vecchia famiglia: la guida chiede di migrarli subito e, se la chiave e' stata
  rubata, di cambiare le password del NAS.

---

## 5. I file firmati

### 5.1 La busta

Ogni file firmato ha la stessa struttura binaria (`Envelope`):

```
offset  byte  contenuto
0       4     "MCGS"
4       1     versione del formato (1)
5       1     tipo (sotto)
6       91    chiave pubblica di chi firma (SPKI P-256)
97      4     lunghezza N del contenuto (big endian), 1 ≤ N ≤ tetto del tipo
101     N     contenuto: JSON canonico (§3.2)
101+N   1     lunghezza S della firma, 8 ≤ S ≤ 72
102+N   S     firma ECDSA P-256 / SHA-256 in DER, forma canonica (§4.1)
```

Si firma `"mycloudgallery/signed" + byte 0x00 + byte[0, 101+N)`: tipo, versione e chiave sono dentro la
firma (un file di un tipo non si spaccia per un altro). Il file finisce esattamente dopo la firma.

**Identita' di un file firmato** (`contentHash`): SHA-256 dei soli **byte firmati** `byte[0, 101+N)`, senza
la firma. E' l'identita' usata per le versioni del registro, i segni di anti-rollback delle teste
(`HeadMark`), le teste congelate e le equivocazioni: "stessa versione, **contenuto firmato** diverso".

| Tipo | Codice | Firmato da | Tetto del contenuto |
|---|---|---|---|
| Registro di famiglia | 1 | chiave di famiglia | 256 KiB |
| Richiesta di registrazione | 2 | il dispositivo stesso | 4 KiB |
| Testa di un dispositivo | 3 | dispositivo approvato | 1 MiB |
| Prenotazione | 4 | dispositivo approvato | 2 KiB |
| Record del cestino | 5 | dispositivo approvato | 8 KiB |
| Testa di un album condiviso | 6 | dispositivo approvato | 256 KiB |
| Segnalazione "perso" | 7 | dispositivo approvato dello stesso account | 2 KiB |
| Stato del dispositivo | 8 | il dispositivo stesso, approvato | 2 KiB |

**Ordine della lettura** (verifica prima di interpretare): dimensione del file entro il tetto del tipo →
copia privata dei byte (chi chiama non puo' cambiarli tra verifica e lettura, I7) → firma del formato →
versione (piu' nuova = "aggiorna l'app") → tipo atteso → forma della chiave e punto sulla curva → lunghezze
coerenti, nessun byte in piu' → firma valida e canonica → **giudizio sul firmatario** (§5.2) → solo ora il
JSON, con i suoi tetti e la forma canonica → campi del tipo → coerenza con il posto in cui il file si trova.
Nessuna allocazione dipende da lunghezze dichiarate dal file.

### 5.2 La regola unica per i dati dei dispositivi

`DeviceTrust`: la chiave che firma deve essere nel registro accettato; `approved` → si prosegue; `retired` →
solo una testa il cui `contentHash` e' in `frozenHeads`; `revoked`/`lost` → rifiuto; assente (sconosciuta o
in attesa) → rifiuto. Poi ogni contenuto deve dichiarare la famiglia giusta e **lo stesso id del firmatario**:
un dispositivo non puo' firmare dati "a nome" di un altro.

### 5.3 Registro di famiglia (tipo 1)

```json
{"config":{"commonExclusions":["Film","Musica/Copertine"],"commonShare":"test-comune",
           "defaults":{"preset":"balanced","quality":"standard"},"homeUla":["fd12:3456:789a::/48"],
           "nasGuid":"0f8fad5b-d9cb-469f-a165-70867728950e","nasHost":"192.168.178.58",
           "nasName":"MyCloudEX2Ultra.fritz.box","nasPower":"always"},
 "devices":[{"admin":true,"id":"…","key":"…","model":"SM-S931B","name":"Telefono di …",
             "nasUser":"…","since":1791806400000,"status":"approved"}],
 "family":"…","issuedAt":1791806400000,"version":1}
```

(Mostrato su piu' righe per leggerlo; il file e' su una riga sola, senza spazi.) Campi facoltativi:
`successorKey` (rotazione) e, per ogni dispositivo `retired`, `frozenHeads` (impronte in ordine crescente,
non vuoto). Vincoli: al massimo 256 dispositivi, in ordine crescente di id, senza doppioni; `id` = id della
`key`; `version` tra 1 e 10^9; testi da mostrare (§8.4); `nasUser` ASCII stretto.

**Configurazione** (`FamilyConfig`, Fase 2). Ogni campo ha **una sola scrittura**; quelli facoltativi si
**omettono** quando sono assenti o vuoti (una lista vuota scritta e' `NOT_CANONICAL`):

| Campo | Obbligatorio | Forma |
|---|---|---|
| `nasHost` | si' | IP **letterale** (E-R7): IPv4 decimale stretto (niente zeri iniziali) o IPv6 tra parentesi quadre. Mai un nome. |
| `commonShare` | si' | nome di share (§8.1), al massimo 80 caratteri |
| `nasName` | no | nome DNS informativo (mai risolto) |
| `nasGuid` | no (l'app lo scrive sempre) | GUID del server SMB dalla NEGOTIATE, forma di `UUID.toString()` (minuscolo, 36 caratteri) |
| `homeUla` | no | al massimo 4 prefissi ULA /48 nella forma `fdxx:xxxx:xxxx::/48` (4 cifre esadecimali minuscole per gruppo), in ordine crescente. `UlaPrefix.canonical` converte le altre scritture (compresse, maiuscole). |
| `nasPower` | si' | `always` oppure `HH:MM-HH:MM` (inizio diverso dalla fine): da qui la fascia notte |
| `wolMac` | no | `aa:bb:cc:dd:ee:ff` minuscolo (WoL, dietro `FeatureFlags.wol`) |
| `commonExclusions` | no | al massimo 256 percorsi di media (§8.1, mai dentro `.mycloudgallery`), ognuno al massimo 512 byte UTF-8, in ordine crescente |
| `defaults` | si' | `{preset, quality}`: valori `[a-z][a-z0-9_]{0,15}` (l'app traduce i suoi enum; uno sconosciuto vale il default) |

### 5.4 Richiesta di registrazione (tipo 2)

`family`, `device`, `key`, `name`, `model`, `nasUser`, `requestedAt`. La `key` deve essere quella della
busta, `device` il suo id, e il file deve chiamarsi `requests/<device>`.

### 5.5 Testa di un dispositivo (tipo 3) e segmenti

`family`, `device`, `share`, `kind` (`index` o `personal`), `version`, `registryVersion`, `createdAt`,
`segments` (al massimo 8192 elementi `{hash, size}`, in ordine crescente di impronta, ognuno al massimo
16 MiB). Il file deve stare in `devices/<device>/<kind>/head/<version>` della share `share`. Le teste sono una
serie numerata con le stesse regole del registro (§2.3: primo nome libero, lettura per nome, spazzatura
scavalcata); la lettura per nome delle teste e' codice della Fase 4.1.

- **Anti-rollback per dispositivo**: chi legge ricorda per (share, dispositivo, tipo) l'ultima versione e
  l'impronta del contenuto firmato accettati (`HeadMark`); versione piu' bassa → `ROLLBACK`, stessa versione
  con contenuto diverso → `EQUIVOCATION`. La stessa testa con un'altra firma valida non e' un'equivocazione
  (domanda aperta 1 della revisione).
- **Dati personali** (`personal`): validi solo se firmati da un dispositivo dello **stesso account del NAS**
  di chi legge, e mai se stanno nell'area comune. Quando la configurazione elenchera' le aree private (Fase
  2.2), varranno solo nella share privata dichiarata per quell'account (revisione B6).
- `registryVersion` dice quale registro conosceva chi ha scritto: se e' piu' alto del nostro, il nostro e'
  indietro (forse qualcuno ce ne nasconde uno nuovo) e lo si rilegge; se non si trova, e' un'anomalia da
  Diagnostica.
- **Segmenti**: non firmati uno per uno; li autentica l'impronta nella testa firmata. Formato: `"MCGB"`, un
  byte di versione, poi il contenuto (definito in Fase 4.1). Si accettano solo se dimensione e impronta
  coincidono con la testa (`Segments.unwrap`). Le immagini che contengono (miniature, anteprime) si
  decodificano solo entro 4096 × 4096 pixel e 16384 per lato (`ImageBounds`), leggendo prima le sole
  dimensioni.

### 5.6 Prenotazione (tipo 4)

`family`, `device`, `share`, `resource` (chiave del lavoro, al massimo 256 caratteri), `generation`,
`acquiredAt` (ora del NAS), `durationMillis` (0 = rilasciata). Sta in
`leases/<SHA-256 di resource>/<gruppo>/<generation>`, con `gruppo = (generation − 1) / 256 + 1`: una
prenotazione copiata in un altro posto non vale. Regole al §6.2.

### 5.7 Record del cestino (tipo 5)

`family`, `device`, `share`, `trashId`, `originalPath` (percorso di media validato, §8.1), `size`,
`trashedAt`. Sta in `trash/<device>/rec/<trashId>`; il file cestinato e' `trash/<device>/files/<trashId>`,
senza estensione (§2.1). Serve a mostrare "Eliminati di recente" e a sapere dove ripristinare; **non decide
mai una cancellazione** (§9). Il ripristino e' un'azione locale confermata: rinomina nella stessa share verso
`originalPath` senza sovrascrivere (collisione = nome alternativo, E-D3, E-SH4).

### 5.8 Testa di un album condiviso (tipo 6, Fase 7)

Qui e' definita solo la busta: un file per album e per dispositivo
(`albums/<id album>/<id dispositivo>/head/<versione>`), firmato da un dispositivo approvato, con anti-rollback
come le teste (§5.5). Ogni dispositivo scrive i propri contributi, cosi' due familiari che modificano lo
stesso album non si sovrascrivono (E-SH6). Il contenuto (elementi, permesso scelto dal creatore) arriva in
Fase 7 con una versione del formato che lo descrive. Una definizione d'album, vera o falsa, **non sposta e
non cancella nulla**: si limita a mostrare cio' che c'e' gia' nell'area comune (E-S11).

### 5.9 Segnalazione "perso" (tipo 7)

`family`, `device` (chi segnala, = firmatario), `target` (il dispositivo perso), `nasUser`, `reportedAt` (ora del
NAS). Firmata da un altro dispositivo **approvato** (non dismesso, revocato, perso o in attesa) **dello stesso
account** del dispositivo perso: `nasUser` deve essere l'account del firmatario e quello di `target` nel registro
accettato (`NOT_OWNER` altrimenti); `target` deve essere nel registro e diverso da `device`; `reportedAt` al
massimo 2 minuti nel futuro. Sta in `family/reports/<target>/<n>`, una serie numerata (primo nome libero; chi
legge va per nome da 1, spazzatura scavalcata, al massimo 32 di fila: inondare la cartella non nasconde una
segnalazione). Una copia in un'altra cartella non vale (`WRONG_LOCATION`).

La stessa segnalazione si pubblica in **due** share (`LostReports.publish`):
- nell'**area comune**, dove la vede l'admin, che la trasforma nello stato `lost` del registro con un gesto suo;
- nell'**area privata dell'account**, dove scrive solo chi ne ha la password: e' la **prova di possesso
  dell'account** (revisione B6, §12 punto 10). Con la decisione 3(a) il dispositivo perso, appena si ricollega,
  rispetta **solo** la copia che trova nella propria area privata.

Cosi' un dispositivo che ha dichiarato l'account di un altro familiare non puo' farne azzerare i telefoni: non
scrive nella sua area privata, e la copia nell'area comune la vede solo l'admin.

### 5.10 Stato del dispositivo (tipo 8)

`family`, `device`, `version`, `appVersion` (`[0-9A-Za-z][0-9A-Za-z.+_-]*`, al massimo 32 caratteri), `roles`
(array, sempre presente, valori `indexer`/`preparer` in ordine crescente, senza doppioni), `lastSeenAt` (ora del
NAS, al massimo 2 minuti nel futuro), `registryVersion`. Sta in `devices/<device>/status/<version>` dell'area
comune; firmato dal dispositivo stesso, **approvato** (in attesa, dismesso, revocato o perso → rifiuto, E-S14).
Serve solo all'elenco "Dispositivi" (L11): nessuna decisione dipende da qui, salvo l'avviso "registro rimasto
indietro" quando `registryVersion` e' piu' alto del nostro.

- **Anti-rollback come per le teste** (§5.5): chi legge ricorda per dispositivo l'ultima versione e l'impronta
  del contenuto firmato (`HeadMark`); versione piu' bassa → `ROLLBACK`, stessa versione con contenuto diverso →
  `EQUIVOCATION`, numero diverso dal nome → `WRONG_LOCATION`.
- **Al massimo una pubblicazione al giorno** (`DEVICE_STATUS_INTERVAL_MILLIS`, ora del NAS), approfittando di
  una sessione gia' aperta, oppure subito se cambiano versione dell'app o ruoli.
- La serie cresce di un file al giorno: chi legge non la percorre da 1 ma cerca **per nome** l'ultimo nome
  occupato partendo dall'ultimo stato accettato (salti doppi poi bisezione, poche letture anche dopo anni) e
  prende il piu' recente valido, tornando indietro di al massimo 32 nomi se in cima c'e' spazzatura. Un buco o
  un nome piantato lontano fanno vedere uno stato vecchio o nessuno: informazione persa, mai potere.

---

## 6. Ora di riferimento e prenotazioni

### 6.1 L'ora del NAS

Scadenze, prenotazioni e istanti firmati usano **solo** l'ora del NAS (`NasClock.nowMillis()`), mai
l'orologio del dispositivo (E-P9): un test controlla che il codice del modulo non legga l'orologio di sistema
in nessuna delle forme note (`System`, `java.time`, `Calendar`, `SystemClock`…).

L'implementazione (Fase 2.1) somma all'orologio monotono del dispositivo lo scarto letto alla NEGOTIATE SMB
(`getTimeOffsetMillis`, gratuito a ogni connessione). Requisiti per la Fase 2.1 (revisione B8):

- solo **SMB 3.1.1 con firma obbligatoria**: l'hash di pre-autenticazione copre anche l'ora della
  NEGOTIATE (con 3.0/3.0.2 chi fa MITM in LAN potrebbe spostarla);
- controllo incrociato con l'ora assegnata dal server a un file appena pubblicato;
- un salto dell'ora del NAS tra due sessioni oltre una soglia (da misurare) sospende cestino e prenotazioni e
  va in Diagnostica.

Istanti plausibili: dal 2020 al 2100; fuori, il campo e' malformato.

### 6.2 Prenotazioni

Servono a non fare due volte lo stesso lavoro (E-I1); non proteggono nulla: se due dispositivi lavorano
insieme, il risultato resta coerente.

- **Generazioni con `FILE_CREATE`**: prendere una risorsa libera = pubblicare la generazione successiva
  alla piu' alta; se il nome esiste gia', ha vinto un altro (`Lost`). Rilascio = generazione con durata 0.
  Nessun file altrui viene mai toccato.
- **Gruppi di 256 generazioni** (revisione M6): si elencano i gruppi (al massimo 1024) e poi solo il gruppo
  piu' alto (al massimo 512 nomi). Prima, con una cartella sola, dopo 257 usi della stessa risorsa restava
  solo `Contended`; ora una risorsa resta prenotabile per oltre 260 000 generazioni.
- **Durata massima: 1 ora** (si rinnova pubblicando una generazione nuova). Una prenotazione con durata
  oltre il tetto, o che comincia piu' di **2 minuti** nel futuro rispetto all'ora del NAS, e' **ignorata**
  (la risorsa e' libera), non accorciata (E-I16). Un dispositivo che muore libera la risorsa entro l'ora
  (E-I2).
- Valgono solo le prenotazioni firmate da dispositivi approvati e trovate nel posto giusto; tutto il resto
  (spazzatura, revocati, copie) conta come "libera".
- Cartelle anomale (elenchi oltre i tetti, generazione al tetto di 10^9): si lavora senza prenotazione
  (`Contended`) e la Diagnostica lo dice. E' un danno solo di efficienza.

---

## 7. Tetti della lettura difensiva

| Che cosa | Tetto |
|---|---|
| Contenuto firmato per tipo | §5.1 (file intero: + 174 byte) |
| Segmento | 16 MiB |
| Kit di recupero | 1 KiB (PKCS#8 dentro: 512 byte) |
| JSON: profondita' | 8 livelli |
| JSON: stringa | 4096 caratteri |
| JSON: chiave | 32 caratteri ASCII |
| JSON: campi per oggetto | 32 |
| JSON: elementi per array | 8192 |
| Versioni, generazioni, gruppi | da 1 a 10^9 |
| Dispositivi nel registro | 256 (le voci non si tolgono) |
| Teste congelate per dispositivo | 64 |
| Segmenti per testa | 8192 |
| Testi da mostrare (nomi, modello) | 64 caratteri |
| Account del NAS | 64 caratteri |
| Nome del NAS | 253 caratteri |
| Chiave di una prenotazione | 256 caratteri |
| Percorso | 4096 byte UTF-8, 64 livelli |
| Componente di percorso | 255 byte UTF-8 |
| Nome di share | 80 caratteri |
| Configurazione: prefissi ULA / esclusioni | 4 / 256 (ogni esclusione 512 byte UTF-8) |
| Versione dell'app nello stato di un dispositivo | 32 caratteri |
| Stato di un dispositivo: pubblicazioni | al massimo una al giorno (ora del NAS), salvo cambi |
| Pubblicazione del registro: nomi letti dopo quello libero | 32 per nome, poi indizi con 32 file non validi di fila al massimo |
| Letture per nome di una serie in una sincronizzazione | 1024 |
| Nomi non validi di fila scavalcati | 32 |
| Elenco del registro (solo indizio) | 1024 nomi |
| Elenco delle richieste | 64 nomi (oltre: lettura per id) |
| Elenco delle teste di un dispositivo | 256 nomi |
| Prenotazioni: gruppi / generazioni per gruppo | 1024 / 512 nomi |
| Prenotazione | 1 ora; inizio al massimo 2 minuti nel futuro |
| Richiesta di registrazione approvabile | 15 minuti da quando l'admin la vede |
| Permanenza nel cestino | 30 giorni (ora del NAS) |
| Pulizia del cestino per giro | 200 file e 20 GiB (il primo file del giro passa comunque) |
| Immagini dall'indice | 4096 × 4096 pixel, 16384 per lato |

Superare un tetto vuol dire rifiutare il dato (o l'elenco), contarlo in Diagnostica e proseguire con cio'
che si ha. **Mai un crash** (E-I14): ogni eccezione di un parser diventa un rifiuto.

---

## 8. Percorsi, nomi e testi letti dal NAS

### 8.1 Percorsi relativi alla share (`SharePath`)

Un percorso letto dal NAS arriva a un'operazione sui file solo come `SharePath`, e solo se e' **gia'** nella
forma sicura (nessuna normalizzazione silenziosa, cosi' due dispositivi non possono interpretarlo in due
modi). Si rifiutano:

- percorso vuoto, assoluto (`/x`), UNC (`\\server\x`), con unita' (`C:`), con `\` in qualunque posizione;
- componenti vuoti (`a//b`, `/` finale), `.` e `..`;
- oltre 4096 byte o 64 livelli; componenti oltre 255 byte;
- caratteri vietati da Windows: `\ / : * ? " < > |`; punto o spazio finale di un componente;
- nomi riservati di Windows, anche con estensione e senza distinzione di maiuscole: `CON`, `PRN`, `AUX`,
  `NUL`, `COM0`…`COM9`, `LPT0`…`LPT9`, `COM¹ ² ³`, `LPT¹ ² ³`, `CONIN$`, `CONOUT$`, `CLOCK$`;
- caratteri di controllo e invisibili (elenco esplicito, uguale su tutti i dispositivi qualunque versione di
  Unicode abbiano): C0, DEL, C1, trattino morbido, combining grapheme joiner, controlli bidirezionali
  (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069), U+200B, U+200C, U+2060–U+206F, BOM, separatori di
  riga e paragrafo, annotazioni interlineari, non-caratteri, tag invisibili, surrogati spaiati, e i caratteri
  che **sembrano vuoti** (riempitivi Hangul U+115F, U+1160, U+3164, U+FFA0; vocali khmer U+17B4, U+17B5;
  selettori mongoli U+180B–U+180F; braille vuoto U+2800; controlli musicali U+1D173–U+1D17A) e i selettori di
  variante U+FE00–U+FE0D e U+E0100–U+E01EF (revisione B2). Restano ammessi lo ZWJ (U+200D) e i selettori
  U+FE0E/U+FE0F, che servono alle emoji composte (E-N6); i nomi che differiscono solo per questi si scoprono
  con `LookAlike` (§4.5);
- **Unicode di confusione**: si ripetono i controlli sulla forma NFKC di ogni componente, cosi' `．．`,
  `／`, `＼`, `：`, `ＣＯＮ` (caratteri a larghezza piena che qualche livello potrebbe trasformare in `..`,
  separatori o nomi riservati) sono rifiutati.

I percorsi di **media** (cestino, album, voci dell'indice) in piu' non possono entrare nella cartella dell'app:
il primo componente si confronta **nella forma NFKC e senza maiuscole** con `.mycloudgallery`, quindi anche
`．mycloudgallery` (punto a larghezza piena) e' rifiutato (revisione B1). Un dato remoto non puo' indicare
come "foto da ripristinare" un file del registro.

Accenti, emoji, spazi interni, nomi lunghi fino ai tetti: ammessi.

### 8.2 Nomi nelle cartelle dell'app

Validati con la loro forma esatta (§2.4); il resto si ignora.

### 8.3 Nomi dei file locali

Cache, miniature e replica locale usano nomi **derivati da un hash** (`LocalNames`: `m` + 52 caratteri
base32 di SHA-256 delle parti, ciascuna preceduta dalla sua lunghezza), mai testo letto dal NAS.

### 8.4 Testi da mostrare

Nomi di dispositivi, modelli, (in futuro) nomi d'album: non vuoti, senza spazi ai bordi, al massimo 64
caratteri, senza i caratteri vietati del §8.1 (niente "fattura‹RLO›fdp.exe", niente nomi fatti solo di
riempitivi). Gli account del NAS: ASCII `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, confrontati senza
maiuscole/minuscole come fa Samba. Il nome del NAS: etichette DNS (lettere, cifre, trattini non ai bordi; vale
anche un IPv4) oppure un IPv6 tra parentesi quadre.

---

## 9. Nessuna azione distruttiva da dati remoti

Regola del piano (§4, §7 contromisura 4, E-S11, E-D4): **cancellazioni definitive, pulizie e spostamenti
partono solo da azioni locali confermate**, con tetti per giro. Come la garantisce il formato:

1. **`core-trust` non puo' cancellare ne' spostare nulla**: l'interfaccia del NAS che usa ha solo `list`,
   `read` e `publishExclusive`. Un test (`TrustFormatTest`) fallisce se qualcuno aggiunge un'operazione.
   L'unica scrittura fatta "per conto di un dato letto" e' la **ripubblicazione** di una versione del
   registro gia' fidata e sparita (§4.4): crea, non cancella.
2. **I tipi letti dal NAS sono solo informativi**: registro, teste, prenotazioni, record del cestino, album
   producono viste e decisioni di fiducia, mai operazioni. L'unione di un ramo del registro e' un'azione
   esplicita dell'admin.
3. **La pulizia del cestino** (`TrashPurgePolicy`, E-D4) ha come ingressi solo:
   - la voce del **diario locale** di questo dispositivo, che esiste solo se qui l'utente ha confermato
     l'eliminazione. E' una **convenzione** del codice che la usera' (Fase 4.5): `core-trust` non offre
     alcuna funzione che costruisca la voce da un record remoto (revisione B7);
   - lo stato del file nel **proprio** cestino (`trash/<io>/files/<id>`: il percorso lo deriva la regola,
     non chi chiama);
   - l'ora del NAS.

   Si elimina solo se il file c'e' ancora con la stessa dimensione, sono passati 30 giorni (ora del NAS)
   dall'ora annotata **localmente**, e il giro non ha superato 200 file o 20 GiB (il primo file di un giro
   passa comunque, altrimenti un file da solo oltre il tetto non si eliminerebbe mai); un'ora locale nel
   futuro blocca tutto. In Fase 4.5 la cancellazione avverra' tramite un handle aperto dopo il controllo
   della dimensione (niente controllo-poi-uso su due aperture diverse). Un record remoto, vero o finto, non
   compare tra gli ingressi: un "finto cestino" non fa eliminare nulla. Gli elementi cestinati da un
   dispositivo che non c'e' piu' non si eliminano mai in automatico.
4. **Spostamenti verso l'area comune** (album condivisi, Fase 7): partono solo dalla conferma locale
   "N foto verranno spostate…"; una definizione d'album letta dal NAS non sposta nulla.
5. **Ripristino dal cestino**: azione locale confermata, percorso validato (§8.1), mai sovrascrivendo.
6. **L'unica conseguenza distruttiva voluta di un dato remoto** e' l'auto-azzeramento di un dispositivo
   segnato `lost` in un registro firmato **e accettato**, oppure segnalato da un dispositivo approvato dello
   stesso account con una copia nella propria area privata (§5.9, decisione 3(a)): tocca solo i dati locali
   dell'app su quel dispositivo, prima le chiavi e poi i dati (`docs/famiglia-dispositivi.md` §3). Con
   l'anti-regressione (§4.4) e i kit per chiave (§4.8) non si puo' ottenere con una base vecchia o con un kit
   della chiave ruotata lasciato in giro; un registro non firmato dalla chiave attesa, un rollback,
   un'equivocazione o una regressione non diventano mai lo stato accettato, quindi non fanno azzerare (test
   `NoFalseWipeTest` di `core-family`).

Le operazioni distruttive vere vivranno in moduli e interfacce separate (Fasi 2.4, 4.5, 5, 7), ciascuna con
il suo diario locale e i suoi test "dato remoto ostile → nessuna azione".

---

## 10. Versione del formato e compatibilita' (E-I3)

- La versione sta nel quinto byte di ogni file firmato, del kit e dei segmenti. Questa build scrive la **1**
  e legge l'intervallo `OLDEST_READABLE_VERSION..CURRENT_VERSION` (oggi 1..1).
- Un file con versione **piu' nuova** non si interpreta: `NEWER_FORMAT` → "aggiorna l'app". Non e' un
  errore del dato e non si cancella nulla.
- **Un'app vecchia non rompe i dati di una nuova**: ogni dispositivo scrive solo i propri file; un admin con
  l'app vecchia che trova un registro di un'app piu' nuova non firma nulla (`CommitResult.NeedsAppUpdate`,
  `RegistrySync.needsAppUpdate`) e non lo scavalca; i dati che non capisce li ignora senza toccarli.
- Si aumenta la versione per **qualunque** cambiamento del contenuto (anche un campo in piu': nella stessa
  versione i campi sconosciuti sono errori). Le app nuove continuano a leggere le versioni vecchie per
  almeno tutto il periodo in cui in famiglia possono esserci app vecchie; quando la lettura di una versione
  viene tolta, se ne alza `OLDEST_READABLE_VERSION` e i dati vecchi vanno riscritti dai loro autori.
- **Finche' l'app non e' rilasciata e nessun dato e' stato scritto sul NAS, la versione 1 puo' ancora
  cambiare** (le Fasi 2.2, 4.1, 4.7 e 7 aggiungono campi alla v1; la revisione 1.4 l'ha gia' cambiata:
  firme canoniche, codice di approvazione a 80 bit, prenotazioni a gruppi, kit per chiave). Dal primo dato
  scritto sull'area di prova in poi, ogni modifica segue le regole sopra.

---

## 11. Analisi delle minacce del formato

### 11.1 Chi ha solo accesso SMB in scrittura, senza chiavi

Un PC con un ransomware, un apparecchio della rete finche' `NAS` e `Public` sono "pubbliche" (`sicurezza.md`
§1, azione 1), una password del NAS rubata.

| Puo' | Non puo' (grazie al formato) |
|---|---|
| Cancellare, alterare o cifrare **qualunque** file a cui ha accesso, compresi i media e `.mycloudgallery/` (L2, L3). | Far accettare a un dispositivo un dato non firmato da una chiave approvata. |
| Rimettere file vecchi validi (rollback), cancellare le versioni nuove. | Far tornare indietro un dispositivo che ha gia' visto una versione nuova, o un dispositivo nuovo sotto la versione del suo QR; far sparire una versione per chi l'ha vista (la ripubblica). |
| Riempire le cartelle dell'app di spazzatura, nomi strani, file enormi, JSON profondissimi. | Nascondere una versione nuova (si legge per nome), far allocare memoria oltre i tetti, far cadere l'app, far interpretare un nome o un percorso ostile. |
| Occupare i nomi delle versioni successive del registro. | Bloccare una revoca con pochi file: si scavalcano; oltre 32 di fila l'admin riceve l'elenco esatto dei file da cancellare. |
| Fabbricare una seconda firma valida di un file (malleabilita' di ECDSA). | Farla accettare (solo firme canoniche) o farla passare per un'equivocazione (identita' sul contenuto firmato). |
| Copiare un file firmato valido in un altro posto (altra share, altro nome, altra risorsa). | Farlo valere li': ogni contenuto dichiara il suo posto e deve coincidere. |
| Scrivere richieste di registrazione con nomi rassicuranti, anche sosia. | Farle approvare: serve la scansione della chiave o il codice di 80 bit digitato dall'admin entro 15 minuti; i sosia sono segnalati. |
| Rallentare il lavoro (prenotazioni spazzatura, cartelle inondate): solo efficienza. | Far cancellare o spostare qualcosa all'app (§9). |

Effetto peggiore sul formato: **negazione del servizio** (i dispositivi restano con le repliche locali e lo
dicono in Diagnostica) e, per un dispositivo configurato **senza** QR, un registro vecchio ma autentico.

### 11.2 Un dispositivo approvato ma compromesso

Malware con i privilegi dell'app (o root) su un telefono di famiglia (S5).

| Puo' | Non puo' |
|---|---|
| Tutto quello del §11.1, nei limiti dei permessi del suo account sul NAS. In particolare **ritardare la pubblicazione della propria revoca** riempiendo i nomi del registro di spazzatura (33 file bastano) o cancellandoli, e riscrivendoli appena vengono tolti, finche' la password NAS del suo account non viene cambiata: con la scrittura nell'area comune nessun nome prevedibile si puo' proteggere, e i nomi imprevedibili andrebbero cercati nell'elenco, che si puo' inondare. Per questo, quando la pubblicazione risponde `Blocked`, la guida fa cambiare **prima** la password NAS dell'account sospetto e solo dopo cancellare i file elencati e ripubblicare. | Firmare a nome di un altro dispositivo o della famiglia; approvare dispositivi; cambiare la configurazione. |
| Finche' non e' revocato, **firmare dati falsi con la sua chiave**: voci dell'indice sbagliate (miniature, testo, vettori → ricerche ingannevoli), finti record del cestino (solo viste), finte definizioni di album (solo viste). | Far eliminare o spostare file tramite l'app degli altri (§9). |
| Tenere occupate risorse con prenotazioni (al massimo un'ora alla volta, rinnovabili): rallenta l'indicizzazione. | Influire sui dati personali degli altri account (validi solo dallo stesso account). |
| Alterare i dati personali del **suo** account. Se si conferma la decisione 3 del §13, far azzerare gli altri dispositivi del suo account (nessun file perso, solo una nuova approvazione). | Portare via la chiave dal Keystore: dopo la revoca non vale piu' nulla di cio' che ha firmato, nemmeno se retrodatato. |
| Dichiarare un `registryVersion` alto per far comparire falsi avvisi "registro indietro". | Far dimenticare a un dispositivo una revoca gia' vista, o farla annullare da un admin su una base vecchia (anti-regressione). |

Risposta: revoca nel registro (o "perso"), poi cambio della password del NAS e revoca del peer WireGuard
(nell'ordine della guida, piano 2.4); il congelamento per modifiche di massa (4.2) limita i danni ai file.

### 11.3 Dispositivo admin compromesso o chiave di famiglia rubata (S12)

Chi ha la chiave di famiglia (dispositivo admin compromesso mentre e' sbloccato, oppure foglio del codice
**e** file del kit) controlla la fiducia: approva i suoi dispositivi, revoca gli altri, cambia la
configurazione. La nuova configurazione non cambia comunque da sola l'indirizzo del NAS usato dai dispositivi
(E-R7). Se pubblica una versione che contraddice quelle viste, i dispositivi la rifiutano come ramo e
l'admin la vede (§4.4); se pubblica prima dell'admin una versione "compatibile" (che aggiunge solo i suoi
dispositivi), viene accettata (B10, §12.11). Rimedio: **reset della famiglia** (§4.10) e cambio delle
password del NAS. Per questo il kit e' diviso in due pezzi in due posti, la cassaforte si apre solo a
telefono sbloccato, e la decisione 2 del §13 propone di chiedere la credenziale del telefono a ogni uso.

### 11.4 Il NAS stesso

Il NAS (o chi ne controlla il firmware o l'account amministratore) vede e modifica tutto, e fornisce l'ora di
riferimento. Puo' fare tutto il §11.1, e spostando l'orologio puo' far scadere prima le prenotazioni (lavoro
doppio) o anticipare la pulizia del cestino, che pero' riguarda solo file che l'utente ha gia' scelto di
eliminare su quel dispositivo. Un orologio all'indietro non fa eliminare nulla.

---

## 12. Limiti dichiarati

1. **Una firma dice chi ha scritto, non che sia vero** (L3). Un dispositivo approvato puo' scrivere dati
   falsi finche' non e' revocato.
2. **Freschezza**: un dispositivo che non riesce a leggere il registro nuovo (offline, o nomi cancellati
   prima che qualcuno li ripubblichi) continua a fidarsi di un dispositivo revocato finche' non lo legge. La
   versione nel QR, la lettura per nome, la ripubblicazione e `registryVersion` nelle teste riducono il
   problema; non lo eliminano.
3. **Configurazione senza QR**: senza la versione minima, un dispositivo nuovo accetta qualunque registro
   autentico, anche vecchio, finche' non vede quello corrente. Lo stesso vale per il recupero dell'admin
   senza un QR di stato.
4. **Nessuna attestazione delle chiavi**: l'admin non ha una prova crittografica che la chiave di un
   dispositivo nuovo stia nell'hardware; si fida della prova di presenza e della persona (decisione 6).
5. **Chiavi dei dispositivi usabili senza sblocco**: un malware installato puo' far firmare la chiave finche'
   resta installato (non puo' portarla via).
6. **La chiave di famiglia e' estraibile per costruzione** (kit, secondo admin) ed e' in memoria durante le
   operazioni dell'admin; il dispositivo admin e' il punto piu' delicato.
7. **Revocare costa lavoro**: tutti i dati del dispositivo revocato si scartano e si ricalcolano.
8. **Il NAS e' fidato per l'ora** (§11.4) e chiunque scriva nelle share puo' sempre fare negazione del
   servizio, compreso ritardare la pubblicazione di una revoca finche' non gli si toglie l'accesso (§11.2:
   prima la password NAS, poi la revoca), o sostituire una versione appena pubblicata con spazzatura e
   ritardarne l'arrivo fino alla ripubblicazione dell'admin (§4.4).
9. **Riservatezza**: l'indice non e' cifrato sul NAS; lo protegge solo il permesso della share (come i media).
   Il registro, nell'area comune, mostra a tutti i dispositivi della famiglia (L11).
10. **Account dichiarato**: l'account del NAS di un dispositivo e' quello che dichiara e che l'admin conferma
    (B6). Vincolo: la prova di possesso deve esistere **prima** di qualunque potere legato all'account. Per le
    segnalazioni "perso" della decisione 3(a) c'e' (Fase 2, §5.9: copia nell'area privata dell'account); per
    l'unione dei dati personali della Fase 4.7 va ripresa la stessa idea. Vale finche' le aree private sono
    davvero private: una share privata "pubblica" (`sicurezza.md` §1) toglie la prova.
11. **Chiave di famiglia rubata**: una versione del ladro che *aggiunge* soltanto (nessuna regressione) e
    arriva prima di quelle dell'admin viene accettata senza allarme; l'admin la vede nell'elenco dei
    dispositivi. Un allarme dedicato (l'app dell'admin ricorda le impronte delle versioni che ha firmato e
    segnala quelle che non riconosce) e' rimandato alla Fase 2.3 (B10).
12. **I file nel cestino conservano i byte dei media** (§2.1, I6).
13. **La protezione della cassaforte a telefono bloccato** e' verificata solo come politica della chiave
    (non bloccando davvero il telefono nei test), e non protegge dal malware che agisce a telefono sbloccato
    (decisione 2).

---

## 13. Decisioni che spettano all'utente

| # | Decisione | Opzioni (la prima e' quella consigliata e gia' scritta nel codice, salvo dove indicato) |
|---|---|---|
| 1 | Dove tenere il file cifrato del kit | **(a)** area privata dell'admin sul NAS + foglio con codice, id e impronta: servono entrambi. (b) In piu', il file stampato come QR su un secondo foglio tenuto altrove: sopravvive alla perdita dell'area privata, ma chi trova i due fogli insieme ha la chiave. (c) File e codice sullo stesso foglio in cassaforte: un solo pezzo da proteggere. |
| 2 | Credenziale del telefono per usare la chiave di famiglia | **(a)** impronta/PIN del telefono a ogni operazione dell'admin (approvare, revocare, ruotare, unire): le operazioni sono sempre interattive; si realizza in Fase 2.2 con la cassaforte che chiede l'autenticazione. (b) Nessuna richiesta oltre al telefono sbloccato con blocco schermo sicuro obbligatorio (come il codice di oggi, dopo la revisione M3 e la sua verifica). |
| 3 | Segnalazione "perso" fatta dal proprietario senza l'admin | **(a)** come dice il piano ("l'admin o il proprietario"): un altro dispositivo dello stesso account puo' far azzerare il dispositivo perso appena si ricollega; un dispositivo compromesso dello stesso account potrebbe pero' far azzerare i suoi "fratelli" (nessun file perso, nuova approvazione). (b) Solo l'admin. |
| 4 | Dati di un dispositivo revocato | **(a)** tutti scartati e ricalcolati; con la *dismissione* si congelano le teste gia' verificate. (b) Tenere quelli "firmati prima della revoca": sconsigliato, la data la scrive il dispositivo stesso. |
| 5 | Durata massima di una prenotazione | **(a)** 1 ora con rinnovo. (b) Piu' corta (15 min: meno attesa se un dispositivo muore, piu' scritture) o piu' lunga. |
| 6 | Approvazione di un dispositivo | **(a)** scansione della chiave quando si e' insieme, altrimenti codice di 16 caratteri digitato entro 15 minuti. (b) Aggiungere l'attestazione hardware del Keystore come requisito (piu' codice, nessun dispositivo di famiglia ne sarebbe escluso). |
| 7 | Runner del test sul dispositivo | **(a)** aggiungere `androidx.test:runner` a `versions-app.gradle` (standard; tocca file fuori da questo modulo). (b) Tenere il runner minimo di `core-trust` (oggi; esegue l'elenco `TEST_CLASSES` e rispetta `-e class`). |

**Applicate per default nella Fase 2 (P3)**, finche' l'utente non conferma: 1(a), 3(a) **con la prova di
possesso dell'account** (§5.9), 4(a), 5(a). La 2 **non** e' applicata: la cassaforte chiede il telefono sbloccato
con blocco schermo sicuro, non la credenziale a ogni uso (residuo M3, `docs/review/1.4-core-trust.md`).

Nota per `docs/sicurezza.md` (fuori dal perimetro di questa fase): C7, C8 (codice), C9, C10 (lato formato)
e C23 hanno ora codice e test; L11 e i limiti del §12 vanno riportati li' alla chiusura della Fase 1.

---

## 14. Mappa: specifica → codice → test

| Sezione | Codice (`core-trust/src/main/kotlin/.../core/trust/`) | Test |
|---|---|---|
| §2.3, §9 | `nas/NasFileSystem.kt`, `nas/NasLayout.kt` | `TrustFormatTest` (nessuna operazione distruttiva, nessun orologio del dispositivo) |
| §3.1 | `codec/Base32.kt` | `Base32AndTextTest` |
| §3.2 | `codec/CanonicalJson.kt`, `codec/Fields.kt` | `CanonicalJsonTest` |
| §4.1–4.3 | `crypto/Keys.kt` (`EcdsaDer`, `P256`), `crypto/FamilyKey.kt`, `crypto/AndroidKeystore.kt` | `EnvelopeTest`, `ReviewFixesTest` (A3, B3); sul dispositivo `KeystoreDeviceTest` (androidTest) |
| §4.4–4.7, §4.10 | `registry/*.kt` | `RegistryTest`, `ReviewFixesTest` (A1, A2, M1, M2, B4, B5, I9), `ReviewRound2Test` (N1–N5, M7), `ReviewRound3Test` (N2, R2, R3, R4), `Phase2FormatTest` (rifondazione) |
| §5.3 (configurazione) | `registry/FamilyRegistry.kt` (`FamilyConfig`, `ConfigDefaults`), `registry/ConfigRules.kt`, `codec/TextRules.kt` (`ipLiteral`) | `Phase2FormatTest` |
| §5.9 | `registry/LostReport.kt` (`LostReport`, `LostReports`) | `Phase2FormatTest`; in `core-family`: `NoFalseWipeTest`, `LifecycleTest` |
| §5.10 | `data/DeviceStatusReport.kt` (`DeviceStatusReport`, `DeviceStatusVerifier`, `DeviceStatusSeries`) | `Phase2FormatTest`; in `core-family`: `StatusAndDiagnosticsTest` |
| §4.8–4.9 | `crypto/RecoveryKit.kt` (`RecoveryKitFiles`) | `RecoveryKitTest`, `ReviewFixesTest` (M4, M7), `ReviewRound2Test` (M4, M3: cassaforte) |
| §5.1–5.2 | `envelope/Envelope.kt`, `registry/DeviceTrust.kt` | `EnvelopeTest`, `DeviceHeadTest`, `ReviewFixesTest` (A3) |
| §5.5 | `data/DeviceHead.kt` | `DeviceHeadTest` |
| §5.6, §6 | `data/Leases.kt` | `LeaseTest`, `ReviewFixesTest` (M6) |
| §5.7, §9 | `data/Trash.kt` | `TrashTest`, `ReviewFixesTest` (B7) |
| §7 | `TrustFormat.kt` (`TrustLimits`) | in tutti i test sopra |
| §8 | `nas/SharePath.kt`, `codec/TextRules.kt`, `nas/NasLayout.kt` (`LocalNames`, `ImageBounds`), `registry/Registration.kt` (`LookAlike`) | `SharePathTest`, `Base32AndTextTest`, `ReviewFixesTest` (B1, B2, I7) |

I test JVM usano un NAS finto volutamente scomodo: elenchi in ordine casuale e nomi senza distinzione di
maiuscole (I3).

Comandi: `gradlew.bat :core-trust:testDebugUnitTest :core-trust:lintDebug`; sul telefono
`set ANDROID_SERIAL=<seriale>` e `gradlew.bat :core-trust:connectedDebugAndroidTest`.

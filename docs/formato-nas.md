# Formato dei dati condivisi sul NAS e modello di fiducia

| | |
|---|---|
| **Stato** | Fase 1.4, 12/10/2026. Formato **versione 1**. Nessun dato e' ancora stato scritto sul NAS. |
| **Codice** | modulo `:core-trust` (`dev.pampa.mycloudgallery.core.trust`); la mappa sezione → file → test e' al §14. |
| **Sigle** | `E-xx` = casi limite del piano; `C1`…`C23`, `L1`…`L17`, `S1`…`S12` = `docs/sicurezza.md`. |
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
3. **Il NAS non puo' farci tornare indietro.** Il registro ha un contatore che cresce; ogni dispositivo
   ricorda l'ultimo visto, e un dispositivo nuovo riceve dal QR il minimo accettabile.
4. **Leggere non deve mai far male.** Tutto cio' che arriva dal NAS si legge con tetti di dimensione, in una
   forma unica (canonica), con percorsi validati; e nessun dato letto puo' da solo far cancellare o spostare
   un file.
5. **Semplice di proposito.** Un algoritmo di firma, un algoritmo di cifratura, un formato di busta, nessuna
   negoziazione, nessuna sovrascrittura: ogni file si pubblica una volta con un nome nuovo.

---

## 1. Attori e chiavi

| Chi | Chiave | Dove vive | Che cosa firma |
|---|---|---|---|
| **Famiglia** (l'admin) | *chiave di famiglia*, ECDSA P-256 | Sul dispositivo admin, cifrata da una chiave AES del Keystore (§4.2). Nel kit di recupero, cifrata col codice di recupero (§4.8). | Solo il **registro di famiglia** (configurazione + dispositivi). |
| **Ogni dispositivo** (ogni profilo Android e' un dispositivo) | *chiave del dispositivo*, ECDSA P-256 | Keystore Android, StrongBox se c'e' (altrimenti TEE). **Non estraibile.** | La propria richiesta di registrazione, le proprie teste di dati (indice, dati personali), prenotazioni, record del cestino, album condivisi, segnalazioni. |
| **Il NAS** | nessuna | – | Nulla. Fornisce solo file e l'**ora di riferimento** (§6). |

Un dispositivo non approvato (in attesa) **legge** ma nessuno accetta cio' che scrive (E-S14).

---

## 2. Struttura sul NAS

### 2.1 La cartella dell'app

Ogni share usata dall'app contiene **una** cartella `.mycloudgallery/`:

- **nascosta**: il punto iniziale la nasconde su Linux e, con l'opzione di Samba `hide dot files` (attiva di
  serie), anche ai client Windows;
- **nessun file ha un'estensione**: Twonky, Plex, DLNA e Foto di Windows scelgono i file per estensione, quindi
  non vedono nulla (E-I13);
- **nessun file comincia con i byte di un'immagine**: ogni file inizia con una firma propria (`MCGS`, `MCGB`,
  `MCGK`), cosi' nemmeno chi guarda il contenuto lo scambia per una foto. Le miniature stanno *dentro* i
  segmenti, mai come file a se'.

L'indice di una share sta **dentro quella share** (stessi permessi dei file, C19). Il registro di famiglia
sta solo nell'**area comune**, perche' tutti devono leggerlo.

### 2.2 L'albero

```
<share>/.mycloudgallery/
├── family/                               solo nell'area comune
│   ├── registry/<versione>               [1] registro di famiglia (chiave di famiglia)
│   ├── requests/<id dispositivo>         [2] richieste di registrazione (auto-firmate)
│   └── reports/<id dispositivo>/<vers.>  [7] segnalazioni "perso" (Fase 2.4)
├── devices/<id dispositivo>/
│   ├── index/head/<versione>             [3] testa dell'indice di questa share
│   ├── index/seg/<impronta>              segmenti (contenitori MCGB), nome = impronta
│   ├── personal/head/<versione>          [3] dati personali: SOLO nell'area privata del proprietario
│   └── personal/seg/<impronta>
├── leases/<impronta risorsa>/<generazione>   [4] prenotazioni
├── trash/<id dispositivo>/
│   ├── rec/<id elemento>                 [5] record firmato del cestino
│   └── files/<id elemento>               il file cestinato, rinominato senza estensione
├── albums/<id album>/<id dispositivo>/head/<versione>   [6] album condivisi (Fase 7), area comune
└── tmp/                                  temporanei della pubblicazione atomica

<area privata dell'admin>/.mycloudgallery/recovery/<id famiglia>   kit di recupero cifrato (§4.8)
```

`[n]` e' il tipo del file firmato (§5.1). Ogni dispositivo scrive **solo sotto i propri id** (piu' le
richieste e le prenotazioni); l'admin scrive il registro.

### 2.3 Come si scrive e come si legge (l'interfaccia `NasFileSystem`)

`core-trust` vede una share attraverso tre operazioni, e **solo tre**:

| Operazione | Contratto |
|---|---|
| `list(dir, limite)` | Nomi dei figli, al massimo `limite`, con l'indicazione "troncato". Cartella assente = vuota. |
| `read(path, maxBytes)` | Il file intero se non supera `maxBytes`, altrimenti "troppo grande". Mai allocare piu' di `maxBytes + 1` byte, qualunque dimensione dichiari il server. |
| `publishExclusive(path, bytes)` | Crea il file **solo se il nome non esiste**; per chi legge e' atomico (un file pubblicato e' sempre completo). Su SMB (Fase 2.1): temporaneo in `tmp/` aperto con `FILE_CREATE` (in Samba `O_CREAT\|O_EXCL`, atomico), scrittura, flush, verifica della lunghezza, `rename(path, replaceIfExist = false)`; collisione = "esiste gia'". Le cartelle mancanti si creano trattando "esiste gia'" come successo. |

Non ci sono cancellazione, spostamento o sovrascrittura (un test lo verifica, §9). Conseguenze:

- **Versioni numerate.** I dati che cambiano (registro, teste, prenotazioni) sono serie di file
  `<versione>` di 20 cifre con zeri a sinistra (l'ordine dei nomi e' l'ordine dei numeri, da 1). Chi legge
  sceglie la versione piu' alta **valida**. Due scrittori sullo stesso numero: vince il primo, l'altro
  riceve "esiste gia'", rilegge e riprova (due admin che approvano insieme non si sovrascrivono).
- **Contenuti per impronta.** I segmenti si chiamano con lo SHA-256 del loro contenuto: immutabili, senza
  conflitti.
- **Pulizia dei propri file vecchi** (teste superate, generazioni di prenotazione, segmenti non piu' elencati)
  arriva in Fase 4.1 con un'interfaccia separata e ristretta alla cartella del dispositivo stesso, dopo un
  periodo di grazia (chi sta leggendo la testa precedente deve trovare ancora i suoi segmenti).

### 2.4 Nomi

| Nome | Forma | Validazione in lettura |
|---|---|---|
| id (dispositivo, famiglia, elemento del cestino, album) | 128 bit in base32 (§3.1): 26 caratteri `[0-9a-z]` | decodifica stretta di 16 byte |
| id di un dispositivo | primi 128 bit di SHA-256(chiave pubblica): **auto-certificante** | deve coincidere con la chiave |
| versione / generazione | 20 cifre ASCII, valore ≥ 1 | esattamente 20 cifre, entro `Long` |
| impronta | SHA-256 in base32: 52 caratteri | decodifica stretta di 32 byte |

Ogni nome letto da un elenco che non rispetta la sua forma si **ignora** (e si conta in Diagnostica): un
attaccante puo' riempire una cartella di nomi strani, ma non farli interpretare.

---

## 3. Codifiche

### 3.1 Base32

L'unica codifica binario-testo del formato: Base32 di Crockford, **minuscolo, senza riempimento**, alfabeto
`0123456789abcdefghjkmnpqrstvwxyz` (senza `i l o u`). La decodifica e' **stretta**: lunghezza esatta per il
numero di byte e bit di coda a zero, cosi' ogni valore ha una sola scrittura. Solo per cio' che una persona
digita (codice di recupero, impronta inserita a mano) si accettano maiuscole, spazi, trattini e gli alias
`O→0`, `I/L→1`, e poi si passa comunque dalla decodifica stretta.

### 3.2 JSON canonico

Il contenuto dei file firmati e' un sottoinsieme di JSON con **una sola forma possibile** per ogni valore:

- UTF-8 valido, senza BOM; **nessuno spazio** fuori dalle stringhe; niente dopo la fine;
- tipi: oggetto, array, stringa, intero a 64 bit, booleano. **Niente `null`**, niente numeri con virgola o
  esponente, niente `-0`, niente zeri iniziali;
- chiavi: lettera ASCII seguita da lettere o cifre ASCII (max 32 caratteri), **in ordine crescente**,
  **mai ripetute**;
- stringhe: si scrivono con escape solo `"` `\` e i caratteri di controllo (`\b \f \n \r \t` e la forma
  `\u00` + due cifre esadecimali minuscole per gli altri sotto U+0020); tutto il resto letterale; un
  surrogato spaiato e' un errore.

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
| Firme | **ECDSA P-256 con SHA-256**, firma DER | Keystore e StrongBox lo supportano su tutti i dispositivi da Android 6; minSdk e' 29. **Ed25519** nel Keystore arriva solo da Android 13 (API 33) e StrongBox non lo supporta: con Ed25519 le chiavi dei dispositivi Android 10-12 starebbero fuori dall'hardware. |
| Cifratura (kit, cassaforte) | **AES-256-GCM**, tag di 128 bit | Standard, autenticata, nel Keystore e in JCA. |
| Derivazione dal codice di recupero | **HKDF-SHA256** | Il codice ha 128 bit casuali: una derivazione lenta (PBKDF2, scrypt) non aggiungerebbe nulla. |
| Impronte | **SHA-256** | – |

Tutto con JCA e il Keystore di Android: **nessuna libreria crittografica aggiunta**. Un algoritmo diverso
vorrebbe dire una nuova versione del formato, mai un campo "algoritmo" da negoziare.

Le chiavi pubbliche si accettano solo nella forma SubjectPublicKeyInfo P-256 con punto non compresso:
91 byte, i primi 27 fissi. Qualunque altra forma (altra curva, punto compresso, parametri espliciti) e'
rifiutata prima di arrivare al provider crittografico.

L'**impronta** di una chiave (da mostrare, da mettere nel QR, da confrontare a mano) sono i primi 26
caratteri base32 di SHA-256(chiave): 130 bit.

### 4.2 La chiave di famiglia

- Generata dall'app dell'admin all'onboarding (piano 2.2, passo 12), in software (JCA), e **subito** cifrata
  in due posti: la *cassaforte* sul dispositivo admin (AES-256-GCM con una chiave del Keystore, StrongBox se
  c'e', non estraibile; file nello spazio privato dell'app, escluso da backup e trasferimenti) e il **kit di
  recupero** (§4.8).
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
  per le credenziali del NAS (`sicurezza.md` §8). Prezzo dichiarato in §12.
- **L'id del dispositivo e' derivato dalla chiave** (§2.4): non si puo' rivendicare l'id di un altro senza la
  sua chiave, e un dispositivo reinstallato o ritrovato ha una chiave nuova, quindi un id nuovo (E-O7, E-O8,
  E-S2). Non esiste "riapprovare" una chiave revocata.
- Distruggibile (`destroyDeviceKey`) quando il dispositivo si scopre segnato come perso (Fase 2.4).

### 4.4 Il registro di famiglia

Un unico documento firmato dalla chiave di famiglia con **configurazione + dispositivi** e un contatore
`version` che cresce di 1 a ogni modifica (un solo contatore: niente incoerenze tra "configurazione N" e
"registro M"). Si pubblica come `family/registry/<version>` con `publishExclusive`.

**Accettazione** (`RegistryVerifier.accept`), in quest'ordine:

1. busta integra (§5.1) e di tipo registro;
2. la chiave che firma e' **quella attesa**: quella del registro gia' accettato (o il suo successore
   annunciato, §4.10), oppure, per un dispositivo nuovo, quella dell'ancora del QR;
3. firma valida;
4. contenuto canonico e ben formato (§5.3), famiglia giusta;
5. **anti-rollback**:
   - versione piu' bassa dell'ultima accettata → rifiuto (`ROLLBACK`);
   - stessa versione con contenuto diverso → rifiuto (`EQUIVOCATION`: due documenti firmati con lo stesso
     numero vogliono dire chiave di famiglia usata male o rubata; va in Diagnostica come allarme);
   - dispositivo nuovo: versione **almeno quella del QR** (`minRegistryVersion`), cosi' chi ha accesso SMB non
     puo' rifilargli un registro di prima di una revoca;
6. il nome del file deve coincidere con la versione dichiarata.

**Lettura dal NAS** (`RegistrySync`): si elencano le versioni piu' alte di quella nota; si prova la piu'
alta; se non e' valida (spazzatura in cima, oppure una rotazione della chiave in mezzo) si risale in ordine
crescente accettando cio' che verifica. **Lo stato locale non e' mai sostituito da qualcosa di piu' vecchio o
non verificato**: con la cartella manomessa, svuotata o piena di spazzatura si continua con l'ultimo registro
accettato e la Diagnostica lo dice (`newerUnverified`, `listingTruncated`, `needsAppUpdate`).

**Memoria locale**: ogni dispositivo salva nello spazio privato i byte dell'ultimo registro accettato e
l'impronta della chiave che lo ha firmato; al riavvio li riverifica (`RegistryVerifier.restore`).

**Configurazione** (estesa in Fase 2.2): `nasHost` (indirizzo del NAS, che l'app **non cambia mai da sola**,
E-R7: un indirizzo nuovo si propone e si accetta solo se il NAS a quell'indirizzo serve un registro firmato
dalla chiave di famiglia) e `commonShare` (l'area comune).

### 4.5 Registrazione e approvazione (piano 2.3)

1. Il dispositivo nuovo crea la sua chiave nel Keystore e pubblica `family/requests/<suo id>`: una
   **richiesta firmata da se stessa** (famiglia, id, chiave, nome, modello, account del NAS, ora). La firma
   prova solo che chi la scrive possiede quella chiave.
2. L'admin vede le richieste valide (firma giusta, id = chiave, nome del file = id, famiglia giusta; le
   altre si scartano e si contano).
3. **Codice di approvazione**: il dispositivo nuovo e quello dell'admin mostrano lo stesso codice di 10
   caratteri (`ABCDE-FGHJK`), i primi 50 bit di SHA-256 della chiave del dispositivo (`ApprovalCodes`).
   L'admin approva **solo dopo averli confrontati** (a vista, o a voce al telefono), oppure, quando sono
   nella stessa stanza, scansionando dal dispositivo nuovo un QR con la chiave intera (schermata della
   Fase 2.3). Senza questo passo, chiunque scriva nell'area
   comune potrebbe infilare una richiesta "Tablet — profilo Mamma" con la propria chiave. Per far
   coincidere il codice servirebbero circa 2^50 chiavi generate.
4. L'admin firma la versione successiva del registro con il dispositivo `approved`.

Fino all'approvazione il dispositivo legge soltanto: i suoi dati, se ne scrivesse, sono rifiutati da tutti
(`SIGNER_NOT_APPROVED`) e l'app non li scrive (`RegistryState.canWrite`).

### 4.6 Stati, revoca e destino dei dati

| Stato | Si entra da | Dati del dispositivo accettati dagli altri |
|---|---|---|
| `approved` | approvazione | tutti (con i controlli del loro tipo) |
| `retired` (dismesso) | `approved` | **solo le teste congelate** elencate dall'admin (`frozenHeads`): impronte dei file-testa che l'admin aveva gia' verificato. Nient'altro, nemmeno teste piu' nuove. |
| `revoked` (revocato) | `approved`, `retired` | **nessuno**, qualunque data dichiarino |
| `lost` (perso) | `approved`, `retired`, `revoked` | nessuno; in piu' il dispositivo stesso, quando legge il registro, distrugge la chiave e poi i dati locali (Fase 2.4) |

Gli stati vanno solo avanti (`DeviceStatus.canBecome`); non si torna mai ad `approved`.

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
ricalcola.

### 4.7 L'ancora di fiducia di un dispositivo nuovo

Il QR dell'app (piano 2.3) porta, oltre a indirizzi, utente, aree e preset (mai password):

- l'**id della famiglia**;
- l'**impronta della chiave di famiglia**;
- la **versione minima del registro** (quella corrente quando l'admin mostra il QR).

Senza QR, la procedura manuale chiede id e impronta (inseriti o confermati a mano) e la versione minima
vale 0: e' accettato qualunque registro firmato dalla chiave di famiglia, anche uno vecchio, e la
Diagnostica lo segnala finche' il dispositivo non vede la versione corrente.

Un QR di un'altra famiglia o con un'impronta diversa da quella che firma il registro sul NAS non porta a
nulla (E-O5); una configurazione cambiata dopo il QR vale quella firmata sul NAS (E-O6).

### 4.8 Il kit di recupero (proposta 15)

Due pezzi, **servono entrambi**:

1. **Il codice di recupero**, su carta: 128 bit casuali generati dall'app (mai scelti dall'utente) piu' 10
   bit di controllo che scoprono gli errori di battitura (lascia passare circa un errore su mille) prima di
   tentare la decifratura. Si stampa come 7 gruppi di 4 caratteri: `ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345`.
   Si mostra solo nella schermata protetta dell'onboarding e l'utente lo riscrive per verificarlo (2.2).
2. **Il file cifrato** `recovery/<id famiglia>`, nell'**area privata dell'admin** sul NAS (gli altri
   familiari non lo leggono):

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

Aprire il kit (`RecoveryKit.open`) controlla struttura, versione, famiglia, autenticazione GCM (codice
sbagliato o file alterato danno lo stesso errore), che la chiave dentro sia quella dichiarata e che privata e
pubblica stiano insieme (firma di prova). Tetto: 1 KiB.

**Recupero dell'admin** (E-S13, piano 2.4): sul dispositivo nuovo si configura l'account dell'admin, si
legge il file, si digita il codice: torna la **stessa** chiave di famiglia. Il dispositivo nuovo si approva
da se' (versione successiva del registro, `admin = true`) e segna il vecchio come perso o revocato. Le
impronte nei QR gia' distribuiti restano valide.

### 4.9 Il secondo dispositivo admin

E' un dispositivo che possiede la chiave di famiglia: la ottiene **aprendo il kit** (stesso codice, stesso
file) ed e' segnato `admin = true` nel registro (informativo: il potere vero e' la chiave). Il caso previsto
e' un profilo dell'admin sul tablet, con lo stesso account del NAS (quindi legge il file del kit). Due admin
non si sovrascrivono: se scrivono la stessa versione vince il primo (§2.3).

### 4.10 Rotazione e reset

- **Rotazione** (manutenzione, o il foglio del codice forse visto da qualcuno, ma il file nell'area privata
  al sicuro): l'ultima versione firmata dalla chiave attuale contiene `successorKey`; da li' in poi firma solo
  la nuova (`FamilyAuthority.rotateTo`). Ogni dispositivo che accetta l'annuncio smette per sempre di
  accettare la vecchia chiave. Si genera e si stampa un kit nuovo. I QR futuri portano la nuova impronta;
  un dispositivo con un QR vecchio risale la catena dall'annuncio. Se qualcuno cancella l'annuncio dal NAS,
  chi non l'ha visto resta alla versione precedente (e la Diagnostica lo dice): nessun danno, solo attesa
  (l'app dell'admin, che lo ha in locale, lo ripubblica, Fase 2.4).
  La rotazione **non** protegge da una chiave gia' rubata: il ladro potrebbe annunciare un suo successore.
- **Reset della famiglia** (chiave di famiglia persa senza kit, o rubata): nuova chiave, **nuovo id di
  famiglia**, nuovo kit; ogni dispositivo deve essere riconfigurato con il nuovo QR (o l'impronta confermata
  a mano) e riapprovato. I dati dei dispositivi legati al vecchio id di famiglia non valgono piu': ogni
  dispositivo, riapprovato, ripubblica le proprie teste col nuovo id (i segmenti, identificati per impronta,
  restano gli stessi e non si ricalcolano). Un dispositivo non ancora migrato continua a fidarsi della
  vecchia famiglia: la guida chiede di migrarli subito e, se la chiave e' stata rubata, di cambiare le
  password del NAS.

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
102+N   S     firma ECDSA P-256 / SHA-256 in DER
```

Si firma `"mycloudgallery/signed" + byte 0x00 + byte[0, 101+N)`: tipo, versione e chiave sono dentro la
firma (un file di un tipo non si spaccia per un altro). Il file finisce esattamente dopo la firma.

| Tipo | Codice | Firmato da | Tetto del contenuto |
|---|---|---|---|
| Registro di famiglia | 1 | chiave di famiglia | 256 KiB |
| Richiesta di registrazione | 2 | il dispositivo stesso | 4 KiB |
| Testa di un dispositivo | 3 | dispositivo approvato | 1 MiB |
| Prenotazione | 4 | dispositivo approvato | 2 KiB |
| Record del cestino | 5 | dispositivo approvato | 8 KiB |
| Testa di un album condiviso | 6 | dispositivo approvato | 256 KiB |
| Segnalazione "perso" | 7 | dispositivo approvato dello stesso account | 2 KiB |

**Ordine della lettura** (verifica prima di interpretare): dimensione del file entro il tetto del tipo →
firma del formato → versione (piu' nuova = "aggiorna l'app") → tipo atteso → forma della chiave →
lunghezze coerenti, nessun byte in piu' → firma valida → **giudizio sul firmatario** (§5.2) → solo ora il
JSON, con i suoi tetti e la forma canonica → campi del tipo → coerenza con il posto in cui il file si
trova. Nessuna allocazione dipende da lunghezze dichiarate dal file.

### 5.2 La regola unica per i dati dei dispositivi

`DeviceTrust`: la chiave che firma deve essere nel registro accettato; `approved` → si prosegue; `retired` →
solo una testa la cui impronta e' in `frozenHeads`; `revoked`/`lost` → rifiuto; assente (sconosciuta o in
attesa) → rifiuto. Poi ogni contenuto deve dichiarare la famiglia giusta e **lo stesso id del firmatario**:
un dispositivo non puo' firmare dati "a nome" di un altro.

### 5.3 Registro di famiglia (tipo 1)

```json
{"config":{"commonShare":"Public","nasHost":"mycloudex2ultra.fritz.box"},
 "devices":[{"admin":true,"id":"…","key":"…","model":"SM-S931B","name":"Telefono di …",
             "nasUser":"…","since":1791806400000,"status":"approved"}],
 "family":"…","issuedAt":1791806400000,"version":1}
```

(Mostrato su piu' righe per leggerlo; il file e' su una riga sola, senza spazi.) Campi facoltativi:
`successorKey` (rotazione) e, per ogni dispositivo `retired`, `frozenHeads` (impronte in ordine crescente,
non vuoto). Vincoli: al massimo 64 dispositivi, in ordine crescente di id, senza doppioni; `id` = id della
`key`; testi da mostrare (§8.4); `nasUser` ASCII stretto.

### 5.4 Richiesta di registrazione (tipo 2)

`family`, `device`, `key`, `name`, `model`, `nasUser`, `requestedAt`. La `key` deve essere quella della
busta, `device` il suo id, e il file deve chiamarsi `requests/<device>`.

### 5.5 Testa di un dispositivo (tipo 3) e segmenti

`family`, `device`, `share`, `kind` (`index` o `personal`), `version`, `registryVersion`, `createdAt`,
`segments` (al massimo 8192 elementi `{hash, size}`, in ordine crescente di impronta, ognuno al massimo
16 MiB). Il file deve stare in `devices/<device>/<kind>/head/<version>` della share `share`.

- **Anti-rollback per dispositivo**: chi legge ricorda per (share, dispositivo, tipo) l'ultima versione e
  l'impronta del file accettati (`HeadMark`); versione piu' bassa → `ROLLBACK`, stessa versione con file
  diverso → `EQUIVOCATION`.
- **Dati personali** (`personal`): validi solo se firmati da un dispositivo dello **stesso account del NAS**
  di chi legge, e mai se stanno nell'area comune.
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
`leases/<SHA-256 di resource>/<generation>`: una prenotazione copiata in un altro posto non vale. Regole al
§6.2.

### 5.7 Record del cestino (tipo 5)

`family`, `device`, `share`, `trashId`, `originalPath` (percorso di media validato, §8.1), `size`,
`trashedAt`. Sta in `trash/<device>/rec/<trashId>`; il file cestinato e' `trash/<device>/files/<trashId>`,
senza estensione (non compare nei servizi multimediali). Serve a mostrare "Eliminati di recente" e a sapere
dove ripristinare; **non decide mai una cancellazione** (§9). Il ripristino e' un'azione locale confermata:
rinomina nella stessa share verso `originalPath` senza sovrascrivere (collisione = nome alternativo, E-D3,
E-SH4).

### 5.8 Testa di un album condiviso (tipo 6, Fase 7)

Qui e' definita solo la busta: un file per album e per dispositivo
(`albums/<id album>/<id dispositivo>/head/<versione>`), firmato da un dispositivo approvato, con anti-rollback
come le teste (§5.5). Ogni dispositivo scrive i propri contributi, cosi' due familiari che modificano lo
stesso album non si sovrascrivono (E-SH6). Il contenuto (elementi, permesso scelto dal creatore) arriva in
Fase 7 con una versione del formato che lo descrive. Una definizione d'album, vera o falsa, **non sposta e
non cancella nulla**: si limita a mostrare cio' che c'e' gia' nell'area comune (E-S11).

### 5.9 Segnalazione "perso" (tipo 7, Fase 2.4)

Firmata da un altro dispositivo approvato **dello stesso account** del dispositivo perso (il piano: "l'admin
o il proprietario"). L'admin la trasforma nello stato `lost` del registro; se si conferma la decisione 3 del
§13, il dispositivo perso la rispetta anche prima, appena si ricollega. Contenuto definito in Fase 2.4.

---

## 6. Ora di riferimento e prenotazioni

### 6.1 L'ora del NAS

Scadenze, prenotazioni e istanti firmati usano **solo** l'ora del NAS (`NasClock.nowMillis()`), mai
l'orologio del dispositivo (E-P9): un test controlla che il codice del modulo non legga l'orologio di
sistema. L'implementazione (Fase 2.1) somma all'orologio monotono del dispositivo lo scarto letto alla
NEGOTIATE SMB (`getTimeOffsetMillis`, gratuito a ogni connessione); per i controlli incrociati si puo'
leggere anche la data assegnata dal server a un file appena pubblicato.

Istanti plausibili: dal 2020 al 2100; fuori, il campo e' malformato.

### 6.2 Prenotazioni

Servono a non fare due volte lo stesso lavoro (E-I1); non proteggono nulla: se due dispositivi lavorano
insieme, il risultato resta coerente.

- **Generazioni con `FILE_CREATE`**: prendere una risorsa libera = pubblicare la generazione successiva
  alla piu' alta trovata; se il nome esiste gia', ha vinto un altro (`Lost`). Rilascio = generazione con
  durata 0. Nessun file altrui viene mai toccato.
- **Durata massima: 1 ora** (si rinnova pubblicando una generazione nuova). Una prenotazione con durata
  oltre il tetto, o che comincia piu' di **2 minuti** nel futuro rispetto all'ora del NAS, e' **ignorata**
  (la risorsa e' libera), non accorciata (E-I16). Un dispositivo che muore libera la risorsa entro l'ora
  (E-I2).
- Valgono solo le prenotazioni firmate da dispositivi approvati e trovate nel posto giusto; tutto il resto
  (spazzatura, revocati, copie) conta come "libera".
- Cartella con piu' di 256 generazioni, o generazione oltre 999 999 999: si lavora senza prenotazione
  (`Contended`) e la Diagnostica lo dice. E' un danno solo di efficienza.

---

## 7. Tetti della lettura difensiva

| Che cosa | Tetto |
|---|---|
| Contenuto firmato per tipo | §5.1 (file intero: + 174 byte) |
| Segmento | 16 MiB |
| Kit di recupero | 1 KiB |
| JSON: profondita' | 8 livelli |
| JSON: stringa | 4096 caratteri |
| JSON: chiave | 32 caratteri ASCII |
| JSON: campi per oggetto | 32 |
| JSON: elementi per array | 8192 |
| Dispositivi nel registro | 64 |
| Teste congelate per dispositivo | 64 |
| Segmenti per testa | 8192 |
| Testi da mostrare (nomi, modello) | 64 caratteri |
| Chiave di una prenotazione | 256 caratteri |
| Percorso | 4096 byte UTF-8, 64 livelli |
| Componente di percorso | 255 byte UTF-8 |
| Nome di share | 80 caratteri |
| Elenco del registro | 1024 nomi |
| Elenco delle richieste | 64 nomi |
| Elenco delle teste di un dispositivo | 256 nomi |
| Generazioni di una prenotazione | 256 nomi |
| Prenotazione | 1 ora; inizio al massimo 2 minuti nel futuro |
| Permanenza nel cestino | 30 giorni (ora del NAS) |
| Pulizia del cestino per giro | 200 file e 20 GiB |
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
  Unicode abbiano): C0, DEL, C1, trattino morbido, controlli bidirezionali (U+061C, U+200E, U+200F,
  U+202A–U+202E, U+2066–U+2069), U+200B, U+200C, U+2060–U+206F, BOM, separatori di riga e paragrafo,
  annotazioni interlineari, non-caratteri, tag invisibili, surrogati spaiati. Restano ammessi lo ZWJ
  (U+200D) e i selettori di variante, che servono alle emoji composte (E-N6);
- **Unicode di confusione**: si ripetono i controlli sulla forma NFKC di ogni componente, cosi' `．．`,
  `／`, `＼`, `：`, `ＣＯＮ` (caratteri a larghezza piena che qualche livello potrebbe trasformare in `..`,
  separatori o nomi riservati) sono rifiutati.

I percorsi di **media** (cestino, album, voci dell'indice) in piu' non possono entrare nella cartella dell'app
(`.mycloudgallery`, in qualunque combinazione di maiuscole): un dato remoto non puo' indicare come "foto da
ripristinare" un file del registro.

Accenti, emoji, spazi interni, nomi lunghi fino ai tetti: ammessi.

### 8.2 Nomi nelle cartelle dell'app

Validati con la loro forma esatta (§2.4); il resto si ignora.

### 8.3 Nomi dei file locali

Cache, miniature e replica locale usano nomi **derivati da un hash** (`LocalNames`: `m` + 52 caratteri
base32 di SHA-256 delle parti, ciascuna preceduta dalla sua lunghezza), mai testo letto dal NAS.

### 8.4 Testi da mostrare

Nomi di dispositivi, modelli, (in futuro) nomi d'album: non vuoti, senza spazi ai bordi, al massimo 64
caratteri, senza i caratteri vietati del §8.1 (niente "fattura‹RLO›fdp.exe"). Gli account del NAS: ASCII
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, confrontati senza maiuscole/minuscole come fa Samba.

---

## 9. Nessuna azione distruttiva da dati remoti

Regola del piano (§4, §7 contromisura 4, E-S11, E-D4): **cancellazioni definitive, pulizie e spostamenti
partono solo da azioni locali confermate**, con tetti per giro. Come la garantisce il formato:

1. **`core-trust` non puo' cancellare ne' spostare nulla**: l'interfaccia del NAS che usa ha solo `list`,
   `read` e `publishExclusive`. Un test (`TrustFormatTest`) fallisce se qualcuno aggiunge un'operazione.
2. **I tipi letti dal NAS sono solo informativi**: registro, teste, prenotazioni, record del cestino, album
   producono viste e decisioni di fiducia, mai operazioni.
3. **La pulizia del cestino** (`TrashPurgePolicy`, E-D4) ha come ingressi solo:
   - la voce del **diario locale** di questo dispositivo, che esiste solo se qui l'utente ha confermato
     l'eliminazione (non si puo' costruire da un record remoto, e non esiste funzione che lo faccia);
   - lo stato del file nel **proprio** cestino (`trash/<io>/files/<id>`: il percorso lo deriva la regola,
     non chi chiama);
   - l'ora del NAS.

   Si elimina solo se il file c'e' ancora con la stessa dimensione, sono passati 30 giorni (ora del NAS)
   dall'ora annotata **localmente**, e il giro non ha superato 200 file o 20 GiB; un'ora locale nel futuro
   blocca tutto. Un record remoto, vero o finto, non compare tra gli ingressi: un "finto cestino" non fa
   eliminare nulla. Gli elementi cestinati da un dispositivo che non c'e' piu' non si eliminano mai in
   automatico.
4. **Spostamenti verso l'area comune** (album condivisi, Fase 7): partono solo dalla conferma locale
   "N foto verranno spostate…"; una definizione d'album letta dal NAS non sposta nulla.
5. **Ripristino dal cestino**: azione locale confermata, percorso validato (§8.1), mai sovrascrivendo.

Le operazioni distruttive vere vivranno in moduli e interfacce separate (Fasi 4.5, 5, 7), ciascuna con il
suo diario locale e i suoi test "dato remoto ostile → nessuna azione".

---

## 10. Versione del formato e compatibilita' (E-I3)

- La versione sta nel quinto byte di ogni file firmato, del kit e dei segmenti. Questa build scrive la **1**
  e legge l'intervallo `OLDEST_READABLE_VERSION..CURRENT_VERSION` (oggi 1..1).
- Un file con versione **piu' nuova** non si interpreta: `NEWER_FORMAT` → "aggiorna l'app". Non e' un
  errore del dato e non si cancella nulla.
- **Un'app vecchia non rompe i dati di una nuova**: ogni dispositivo scrive solo i propri file; il registro
  e' una serie numerata con `FILE_CREATE`, quindi un admin con l'app vecchia non puo' sovrascrivere una
  versione scritta da un'app nuova (riceve "esiste gia'") e `RegistrySync.needsAppUpdate` gli impedisce di
  provarci; i dati che non capisce li ignora senza toccarli.
- Si aumenta la versione per **qualunque** cambiamento del contenuto (anche un campo in piu': nella stessa
  versione i campi sconosciuti sono errori). Le app nuove continuano a leggere le versioni vecchie per
  almeno tutto il periodo in cui in famiglia possono esserci app vecchie; quando la lettura di una versione
  viene tolta, se ne alza `OLDEST_READABLE_VERSION` e i dati vecchi vanno riscritti dai loro autori.
- **Finche' l'app non e' rilasciata e nessun dato e' stato scritto sul NAS, la versione 1 puo' ancora
  cambiare** (le Fasi 2.2, 4.1, 4.7 e 7 aggiungono campi alla v1). Dal primo dato scritto sull'area di prova
  in poi, ogni modifica segue le regole sopra.

---

## 11. Analisi delle minacce del formato

### 11.1 Chi ha solo accesso SMB in scrittura, senza chiavi

Un PC con un ransomware, un apparecchio della rete finche' `NAS` e `Public` sono "pubbliche" (`sicurezza.md`
§1, azione 1), una password del NAS rubata.

| Puo' | Non puo' (grazie al formato) |
|---|---|
| Cancellare, alterare o cifrare **qualunque** file a cui ha accesso, compresi i media e `.mycloudgallery/` (L2, L3). | Far accettare a un dispositivo un dato non firmato da una chiave approvata. |
| Rimettere file vecchi validi (rollback), cancellare le versioni nuove. | Far tornare indietro un dispositivo che ha gia' visto una versione nuova, o un dispositivo nuovo sotto la versione del suo QR. |
| Riempire le cartelle dell'app di spazzatura, nomi strani, file enormi, JSON profondissimi. | Far allocare memoria oltre i tetti, far cadere l'app, far interpretare un nome o un percorso ostile. |
| Copiare un file firmato valido in un altro posto (altra share, altro nome, altra risorsa). | Farlo valere li': ogni contenuto dichiara il suo posto e deve coincidere. |
| Scrivere richieste di registrazione con nomi rassicuranti. | Farle approvare: serve il confronto del codice con il dispositivo vero. |
| Bloccare il lavoro (prenotazioni spazzatura, cartelle inondate): solo efficienza. | Far cancellare o spostare qualcosa all'app (§9). |

Effetto peggiore sul formato: **negazione del servizio** (i dispositivi restano con le repliche locali e lo
dicono in Diagnostica) e, per un dispositivo appena configurato **senza** QR, un registro vecchio ma
autentico.

### 11.2 Un dispositivo approvato ma compromesso

Malware con i privilegi dell'app (o root) su un telefono di famiglia (S5).

| Puo' | Non puo' |
|---|---|
| Tutto quello del §11.1, nei limiti dei permessi del suo account sul NAS. | Firmare a nome di un altro dispositivo o della famiglia; approvare dispositivi; cambiare la configurazione. |
| Finche' non e' revocato, **firmare dati falsi con la sua chiave**: voci dell'indice sbagliate (miniature, testo, vettori → ricerche ingannevoli), finti record del cestino (solo viste), finte definizioni di album (solo viste). | Far eliminare o spostare file tramite l'app degli altri (§9). |
| Tenere occupate risorse con prenotazioni (al massimo un'ora alla volta, rinnovabili): rallenta l'indicizzazione. | Influire sui dati personali degli altri account (validi solo dallo stesso account). |
| Alterare i dati personali del **suo** account. Se si conferma la decisione 3 del §13, far azzerare gli altri dispositivi del suo account (nessun file perso, solo una nuova approvazione). | Portare via la chiave dal Keystore: dopo la revoca non vale piu' nulla di cio' che ha firmato, nemmeno se retrodatato. |
| Dichiarare un `registryVersion` alto per far comparire falsi avvisi "registro indietro". | Far dimenticare a un dispositivo una revoca gia' vista. |

Risposta: revoca nel registro (o "perso"), poi cambio della password del NAS e revoca del peer WireGuard
(nell'ordine della guida, piano 2.4); il congelamento per modifiche di massa (4.2) limita i danni ai file.

### 11.3 Dispositivo admin compromesso o chiave di famiglia rubata (S12)

Chi ha la chiave di famiglia (dispositivo admin compromesso mentre la usa, oppure foglio del codice **e**
file del kit) controlla la fiducia: approva i suoi dispositivi, revoca gli altri, cambia la configurazione.
La nuova configurazione non cambia comunque da sola l'indirizzo del NAS usato dai dispositivi (E-R7).
Rimedio: **reset della famiglia** (§4.10) e cambio delle password del NAS. Per questo il kit e' diviso in due
pezzi in due posti, e la decisione 2 del §13 propone di chiedere la credenziale del telefono a ogni uso.

### 11.4 Il NAS stesso

Il NAS (o chi ne controlla il firmware o l'account amministratore) vede e modifica tutto, e fornisce l'ora di
riferimento. Puo' fare tutto il §11.1, e spostando l'orologio puo' far scadere prima le prenotazioni (lavoro
doppio) o anticipare la pulizia del cestino, che pero' riguarda solo file che l'utente ha gia' scelto di
eliminare su quel dispositivo. Un orologio all'indietro non fa eliminare nulla.

---

## 12. Limiti dichiarati

1. **Una firma dice chi ha scritto, non che sia vero** (L3). Un dispositivo approvato puo' scrivere dati
   falsi finche' non e' revocato.
2. **Freschezza**: un dispositivo che non riesce a leggere il registro nuovo (offline, o file nascosti da
   chi ha accesso SMB) continua a fidarsi di un dispositivo revocato finche' non lo legge. La versione nel QR,
   la memoria locale e `registryVersion` nelle teste riducono il problema; non lo eliminano.
3. **Configurazione senza QR**: senza la versione minima, un dispositivo nuovo accetta qualunque registro
   autentico, anche vecchio, finche' non vede quello corrente.
4. **Nessuna attestazione delle chiavi**: l'admin non ha una prova crittografica che la chiave di un
   dispositivo nuovo stia nell'hardware; si fida del codice di approvazione e della persona. (L'attestazione
   del Keystore si potra' aggiungere: e' una decisione del §13.)
5. **Chiavi dei dispositivi usabili senza sblocco**: un malware installato puo' far firmare la chiave finche'
   resta installato (non puo' portarla via).
6. **La chiave di famiglia e' estraibile per costruzione** (kit, secondo admin) ed e' in memoria durante le
   operazioni dell'admin; il dispositivo admin e' il punto piu' delicato.
7. **Revocare costa lavoro**: tutti i dati del dispositivo revocato si scartano e si ricalcolano.
8. **Il NAS e' fidato per l'ora** (§11.4) e chiunque scriva nelle share puo' sempre fare negazione del servizio.
9. **Riservatezza**: l'indice non e' cifrato sul NAS; lo protegge solo il permesso della share (come i media).
   Il registro, nell'area comune, mostra a tutti i dispositivi della famiglia (L11).
10. **Codice di approvazione a 50 bit**: sufficiente contro chi prova a produrre una chiave con lo stesso
    codice nei minuti dell'approvazione, non un margine "crittografico" di lungo periodo; per questo vale solo
    per quell'approvazione.

---

## 13. Decisioni che spettano all'utente

| # | Decisione | Opzioni (la prima e' quella consigliata e gia' scritta nel codice, salvo dove indicato) |
|---|---|---|
| 1 | Dove tenere il file cifrato del kit | **(a)** area privata dell'admin sul NAS + codice su carta: servono entrambi. (b) In piu', il file stampato come QR su un secondo foglio tenuto altrove: sopravvive alla perdita dell'area privata, ma chi trova i due fogli insieme ha la chiave. (c) File e codice sullo stesso foglio in cassaforte: un solo pezzo da proteggere. |
| 2 | Credenziale del telefono per usare la chiave di famiglia | **(a)** impronta/PIN del telefono a ogni operazione dell'admin (approvare, revocare, ruotare): le operazioni sono sempre interattive; si realizza in Fase 2.2 con la cassaforte che chiede l'autenticazione. (b) Nessuna richiesta (come il codice di oggi). |
| 3 | Segnalazione "perso" fatta dal proprietario senza l'admin | **(a)** come dice il piano ("l'admin o il proprietario"): un altro dispositivo dello stesso account puo' far azzerare il dispositivo perso appena si ricollega; un dispositivo compromesso dello stesso account potrebbe pero' far azzerare i suoi "fratelli" (nessun file perso, nuova approvazione). (b) Solo l'admin. |
| 4 | Dati di un dispositivo revocato | **(a)** tutti scartati e ricalcolati; con la *dismissione* si congelano le teste gia' verificate. (b) Tenere quelli "firmati prima della revoca": sconsigliato, la data la scrive il dispositivo stesso. |
| 5 | Durata massima di una prenotazione | **(a)** 1 ora con rinnovo. (b) Piu' corta (15 min: meno attesa se un dispositivo muore, piu' scritture) o piu' lunga. |
| 6 | Approvazione di un dispositivo | **(a)** confronto del codice di 10 caratteri, oppure scansione del QR del dispositivo nuovo quando si e' insieme. (b) Aggiungere l'attestazione hardware del Keystore come requisito (piu' codice, nessun dispositivo di famiglia ne sarebbe escluso). |
| 7 | Runner del test sul dispositivo | **(a)** aggiungere `androidx.test:runner` a `versions-app.gradle` (standard; tocca file fuori da questo modulo). (b) Tenere il runner minimo di `core-trust` (oggi). |

Nota per `docs/sicurezza.md` (fuori dal perimetro di questa fase): C7, C8 (codice), C9, C10 (lato formato)
e C23 hanno ora codice e test; L11 e i limiti del §12 vanno riportati li' alla chiusura della Fase 1.

---

## 14. Mappa: specifica → codice → test

| Sezione | Codice (`core-trust/src/main/kotlin/.../core/trust/`) | Test |
|---|---|---|
| §2.3, §9 | `nas/NasFileSystem.kt`, `nas/NasLayout.kt` | `TrustFormatTest` (nessuna operazione distruttiva, nessun orologio del dispositivo) |
| §3.1 | `codec/Base32.kt` | `Base32AndTextTest` |
| §3.2 | `codec/CanonicalJson.kt`, `codec/Fields.kt` | `CanonicalJsonTest` |
| §4.1–4.3 | `crypto/Keys.kt`, `crypto/FamilyKey.kt`, `crypto/AndroidKeystore.kt` | `EnvelopeTest`; sul dispositivo `KeystoreDeviceTest` (androidTest) |
| §4.4–4.7, §4.10 | `registry/*.kt` | `RegistryTest` |
| §4.8–4.9 | `crypto/RecoveryKit.kt` | `RecoveryKitTest` |
| §5.1–5.2 | `envelope/Envelope.kt`, `registry/DeviceTrust.kt` | `EnvelopeTest`, `DeviceHeadTest` |
| §5.5 | `data/DeviceHead.kt` | `DeviceHeadTest` |
| §5.6, §6 | `data/Leases.kt` | `LeaseTest` |
| §5.7, §9 | `data/Trash.kt` | `TrashTest` |
| §7 | `TrustFormat.kt` (`TrustLimits`) | in tutti i test sopra |
| §8 | `nas/SharePath.kt`, `codec/TextRules.kt`, `nas/NasLayout.kt` (`LocalNames`, `ImageBounds`) | `SharePathTest`, `Base32AndTextTest` |

Comandi: `gradlew.bat :core-trust:testDebugUnitTest :core-trust:lintDebug`; sul telefono
`set ANDROID_SERIAL=<seriale>` e `gradlew.bat :core-trust:connectedDebugAndroidTest`.

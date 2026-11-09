# Home Famiglia

Una Home per Android TV, pensata per le Philips su piattaforma `org.droidtv`: un paesaggio di colline
toscane che cambia con il periodo dell'anno, l'ora e il meteo, l'orologio, e sotto le righe che servono
davvero.

- **Stagioni in automatico:** Natale dal 1° dicembre al 6 gennaio, Halloween dal 15 al 31 ottobre, e nel
  resto dell'anno un paesaggio che cambia colore con la stagione. Giorno e notte seguono il sole.
- **Meteo:** pioggia animata se piove. Neve solo a 0 °C o meno. Dati da Open-Meteo, ogni 30 minuti.
- **Ultimi contenuti:** quello che stavi guardando, con locandina e avanzamento; OK riapre il titolo.
  Per le app che non pubblicano i titoli (molte italiane) compare almeno l'app usata di recente.
  Con il NAS del FRITZ!Box la riga è **condivisa tra tutte le TV di casa**.
- **App preferite**, fino a 6.
- **Canali live**, con anteprima del canale selezionato.
- **Ingressi HDMI** con nome e icona a scelta.
- **Personalizza**, direttamente dalla TV: preferite e loro ordine, righe e loro ordine, nomi HDMI.

## Provato su

Philips TPM191E (`PH9M_EA_5599`, Android TV 12) e 65PUS8506/12 (`PH1M_EA_9970A`, Android TV 11). Lista dei
canali e anteprima usano componenti Philips: su altre TV quella riga semplicemente non compare.

## Installazione

Serve un computer sulla stessa rete con `adb`, JDK 17 e Android SDK 34 (`ANDROID_HOME`), e sulla TV il
debug USB attivo.

```sh
cp cosy.defaults.properties cosy.properties   # città, preferite, soglia della neve
TV_HOST=192.168.1.50 ./install.sh
```

Lo script compila, installa, concede il permesso per gli ultimi contenuti e imposta la Home. Il launcher
di serie ha la precedenza finché è attivo: va disattivato a parte (`pm disable-user --user 0
com.google.android.tvlauncher`), dopo aver controllato che la nuova Home parta. Per alleggerire la TV in
sicurezza e con un ripristino, vedi [cleanyourtv](https://github.com/Gathin23/cleanyourtv). Dopo il cambio
di Home può servire un riavvio perché il tasto Home la apra.

Al termine spegni il debug sulla TV: la porta 5555 non è cifrata.

## Impostazioni

`cosy.defaults.properties` contiene tutte le chiavi; le tue vanno in `cosy.properties`, che git ignora.
La tua città resta quindi fuori dal repository.

| Chiave | Cosa fa |
|---|---|
| `location.name`, `location.latitude`, `location.longitude` | Città per meteo, alba e tramonto. Senza, niente meteo e nessuna connessione a Open-Meteo |
| `snow_at_or_below` | Temperatura (°C) a cui o sotto cui nevica, predefinita 0 |
| `channels_count` | Quanti canali mostrare, predefinito 6 |
| `dock` | Preferite iniziali, poi modificabili dalla TV |
| `hidden` | App mai mostrate |
| `sync.ftp_host`, `sync.ftp_user`, `sync.ftp_password`, `sync.ftp_dir` | NAS per condividere gli ultimi contenuti, vedi sotto. Vuoto: ogni TV tiene i suoi |
| `sync.tv_name` | Come le altre TV chiamano questa. Vuoto: il nome della TV nelle sue impostazioni |

Per vedere una scena senza aspettare la data giusta:

```sh
adb shell am start -n com.famiglia.home/com.cosyhome.tv.HomeActivity --es season christmas --es fx snow --es phase night
```

Valori: `season` christmas, halloween, spring, summer, autumn, winter; `fx` clear, rain, snow; `phase` dawn,
day, dusk, night. `auto` torna al comportamento normale.

## Ultimi contenuti

La riga mette insieme, dal più recente:

1. i titoli che le app pubblicano nella tabella «Watch Next» di Android (Netflix, Prime Video, YouTube,
   Disney+ e altre), con locandina e punto a cui eri arrivato;
2. i titoli già iniziati nelle righe che le app pubblicano per la Home, per chi aggiorna quelle e non
   Watch Next;
3. se resta posto, le app di streaming usate di recente (almeno 10 minuti nelle ultime due settimane),
   per le app che non pubblicano nulla: si riaprono, ma senza il titolo.

Android non permette di sapere altro: la cronologia interna di ogni app resta sua. I permessi per 1–2
(`READ_TV_LISTINGS`) e per 3 (accesso ai dati di utilizzo) li concede `install.sh`.

## Ultimi contenuti su tutte le TV (NAS del FRITZ!Box)

Ogni TV scrive in una cartella del NAS **solo il proprio file**, `recent-<id>.json`, e legge quelli delle
altre. Due TV non scrivono mai lo stesso file, quindi niente conflitti. Ogni file è un piccolo JSON:

```json
{"version": 1, "tv": "Camera", "updated": 1794221726742,
 "items": [{"pkg": "com.netflix.ninja", "title": "La casa sul lago", "detail": "S2 E4",
            "poster": "https://…", "intent": "intent:…", "engaged": 1794221726242, "progress": 0.42}]}
```

Ogni TV unisce i propri titoli a quelli delle altre; se due TV hanno lo stesso titolo vince la visione
più recente. I titoli arrivati da un'altra TV portano la scritta «su Camera». Compaiono solo i titoli di
app installate anche su questa TV, e OK li apre nella stessa app. Da dove riprendere lo decide l'app con
il tuo account, come sempre. Il file si aggiorna quando la lista cambia; la TV passa dal NAS al massimo
ogni 2 minuti, mentre la Home è aperta. Se il NAS non risponde, la TV mostra i suoi titoli e l'ultima
lista ricevuta.

Sul FRITZ!Box (i nomi dei menu possono cambiare un po' tra le versioni di FRITZ!OS):

1. collega una chiavetta o un disco USB, oppure usa la memoria interna;
2. in **Rete domestica → Memoria (NAS)** attiva l'accesso alla memoria e l'**accesso via FTP**;
3. in **Sistema → Utenti FRITZ!Box** crea un utente, per esempio `famiglia`, con **accesso ai contenuti
   NAS** in lettura e scrittura;
4. in `cosy.properties`:

```properties
sync.ftp_host=fritz.box
sync.ftp_user=famiglia
sync.ftp_password=…
# La cartella parte dal nome del disco come lo mostra la radice FTP (apri ftp://fritz.box dal computer)
sync.ftp_dir=NomeDelDisco/HomeFamiglia
```

5. `./install.sh` su ogni TV, con lo stesso `cosy.properties`.

Lo stesso APK va su tutte le TV: ognuna si riconosce dal suo identificativo Android e si presenta con il
nome che ha nelle sue impostazioni (Impostazioni → Info → Nome dispositivo). L'FTP non è cifrato e la
password finisce nell'APK: va bene dentro casa, non esporre l'FTP del FRITZ!Box su Internet.

## Cosa esce dalla TV

Ogni 30 minuti una richiesta a `api.open-meteo.com` con la posizione arrotondata a circa un chilometro, e le
locandine degli ultimi contenuti scaricate dai server delle rispettive app. Con la condivisione attiva, i
titoli degli ultimi contenuti vanno al NAS di casa, e solo lì. Nessun account e nessuna analisi.

Permessi: internet, `READ_TV_LISTINGS` per leggere gli ultimi contenuti, e l'accesso ai dati di utilizzo
per sapere quali app di streaming hai usato; questi due si concedono via ADB.

## Anteprime sul computer

```sh
./gradlew :app:testDebugUnitTest
```

disegna la Home come su una TV 1080p, con app, titoli e canali di esempio, in `app/build/previews`: tutte le
stagioni, il giorno e la notte, il pannello delle app. Serve per vedere una modifica grafica senza
installarla.

## Tornare indietro

```sh
adb shell am broadcast -f 32 -n com.famiglia.home/com.cosyhome.tv.HomeSwitch -a com.famiglia.home.HOME --ez on false
adb shell pm enable com.google.android.tvlauncher
```

## Crediti

- Basata su [Cosy Home](https://github.com/oliverjwroberts/cosy-home-tv) di Oliver Roberts, a sua volta nata
  da [cleanyourtv](https://github.com/Gathin23/cleanyourtv) di Gathin (MIT, vedi `LICENSE`).
- La lettura degli ultimi contenuti segue il metodo di [SferaDev/android-tv](https://github.com/SferaDev/android-tv) (MIT).
- Carattere [Outfit](https://github.com/Outfitio/Outfit-Fonts), SIL Open Font License (`FONT-LICENSE-OFL.txt`).
- Dati meteo di [Open-Meteo.com](https://open-meteo.com), CC BY 4.0.

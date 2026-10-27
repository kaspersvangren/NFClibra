# NFClibra

Android-app, der gør telefonens NFC til RFID-læser i Cicero Mobile. (Hed tidligere Cicero NFC.)

NFClibra er ikke lavet af eller godkendt af Systematic. Cicero er Systematics produkt.

App'en viser Cicero Mobile i Androids indbyggede webvisning (samme motor som Chrome)
og kører ved siden af en lille lokal RFID-server på `localhost:1667`. Serveren taler
samme protokol som Deichmans open source RFID-program
[go-feig](https://github.com/digibib/go-feig), som Cicero understøtter under
RFID-scanneren **Deichman**.

Udviklet privat af Kasper Svangren, der arbejder som bibliotekar og Cicero-systemadministrator i Gladsaxe.
Gladsaxe Kommune står ikke bag app'en. Kodet med AI-assistance (Claude).

## Hent

Seneste version: [NFClibra.apk](https://github.com/kaspersvangren/NFClibra/releases/latest/download/NFClibra.apk)

Kræver Android 8 eller nyere med NFC. Testet på Motorola Edge 50 Fusion (Android 16)
med NXP ICODE SLIX og SLIX2-tags.

## Opsætning i Cicero Mobile (inde i app'en)

Enhedsindstillinger → RFID scanner:
- Scanner: **Deichman**
- Hostname: **localhost**
- Port: **1667**
- Slå **"RFID-scanner til som standard"** til

App'en kan gøre det selv: menuen ⚙ → **Sæt Cicero op** (eller "Ret det for mig" ved den røde RFID-fejl). Den åbner Enhedsindstillinger, udfylder kun felterne under RFID scanner og trykker Gem.

## Brug

Første gang viser app'en en kort guide med tegninger af funktionerne; den ligger altid i menuen ⚙ → Guide.

Hold bogen mod bagsiden af telefonen:

| Signal | Betyder | Vibration |
|---|---|---|
| "Hold stille" | bogen er læst, Cicero arbejder | kort tik |
| Grøn pille, overstreget klokke | alarm slået fra (udlån) | to korte |
| Orange pille, klokke | alarm slået til (aflevering) | én lang |
| Rød pille | fejl, fx bogen fjernet for tidligt | tre hurtige |
| Grå pille, flueben | læst på en side uden alarmskift | – |
| Rød pille "RFID-fejl" | Hostname og/eller Port i Ciceros RFID-opsætning er forkert (fx et mellemrum); tryk for vejledning | – |
| Blå pille "Sæt 2 af 5" | del af et sæt – hold næste del mod telefonen | kort tik |
| Pille "Alarm mangler" | alarmen er skiftet på delen ved telefonen – hold de viste dele mod telefonen | to korte |

### Sæt i flere dele

Telefonen læser én chip ad gangen, men Cicero vil se alle dele af et sæt på én gang. Holdes en del af et sæt mod
telefonen, mens Cicero lytter, viser app'en en pop op med en cirkel for hver del og samler delene op én ad gangen.
De meldes først til Cicero, når alle er læst – som når hele sættet ligger på skrankens læser. Skifter Cicero så
alarmen, skiftes den med det samme på delen ved telefonen, og cirklerne viser, hvilke dele der mangler (grøn =
alarm fra, orange = alarm til). Pop op'en lukker af sig selv, når sættet er færdigt.

**Gennemfør nu** (så snart én del er læst) melder hele sættet til Cicero, også dele der ikke er læst – fx når en del mangler, har en
defekt chip, eller det er rent administrativt. Alarmen skiftes så kun på delen ved telefonen. I alarmfasen kan resten
springes over med **Spring resten over**. Begge spørger først, og loggen skriver, hvilke dele der ikke blev læst eller skiftet. Andre materialer ignoreres, indtil sættet er færdigt. Bliver et sæt ikke gjort færdigt
(1 minut uden ny del, 2 minutter for alarmen), opgives det, og loggen siger hvilke dele der mangler.

## Værktøj til tags (knappen nederst i menuen ⚙)

Mens værktøjet er åbent, får Cicero ikke besked om tags.

- Viser hvad der står på et tag: materialenummer, del x af y, alarm, bibliotek og chiptype – hvert felt for sig, fejl i rødt.
  Når chippen fjernes, står det som "Sidst læst" i 10 sekunder.
- **Alarm til/fra** i hånden og **Nulstil tag** (helt tom som en ny chip; spørger først).
- **Programmér chip**: materialenummer tastes eller læses med kameraet (stregkode eller trykte tal).
  Kameraet sætter numre med samme længde som jeres materialenumre øverst (app'en husker kun længden).
  Sæt programmeres del for del, én chip ad gangen. Efter programmering er alarmen slået til.
- **Biblioteksnummer** indstilles i værktøjet og skrives på nye chips sammen med DK.
- App'en sender aldrig lås-kommandoer til et tag (de kan ikke fortrydes).

## Tilpasninger af Cicero

- Fanerækken øverst kan swipes.
- Kontakten "Pinkode" ved "Send kvittering" i udlånsbilledet.
- Knaprækken Nulstil/Søg under søgeformularer ligger altid nederst som en lille pille over bundmenuen. Pillen er en kopi; et tryk på den trykker på Ciceros egen knap.
- Knappen **Tags** midt i Ciceros bjælke (hovedskærmene) åbner Værktøj til tags.
- Ciceros kamera-ikon i materialenummer-felterne åbner NFClibras kamera (stregkode og tal) i stedet for Ciceros. Det læste nummer vælges med en knap og tastes ind i feltet.
- Egen bundmenu (Indstillinger → Indret bundmenu): vælg hvilke fire funktioner der står i bundmenuen og i hvilken rækkefølge – også funktioner fra "…"-menuen. App'ens knapper trykker på Ciceros egne (eller på punktet i "…"-menuen); flyttes en funktion ud af bjælken, ligger den øverst under "…".
- RFID-opslag i CQL-feltet: står katalogsøgningen fremme, og Cicero lytter ikke efter RFID, giver en bog ved telefonen en søgning på bmn=<materialenummer>.
- Kamera-ikon i CQL-feltet: tag et billede; de fundne tekstlinjer og numre markeres i billedet, og man trykker på det, man vil søge på. Søg nu søger på én (fritekst i anførselstegn, faust=, bmn=); ＋ gemmer tekstlinjer, der kan få type (term.title, term.creator, dk5, term.subject), rettes, flettes og søges samlet: samme type med OR, forskellige typer med AND. ISBN søges med term.isbn=. Feltnavne og materialetyper ignoreres, når de står alene.
- Fly-ins (fx en post, der glider ind fra højre) kan swipes væk mod højre; app'en trykker så på Ciceros eget kryds. Swipes, der starter på faner, brede tabeller eller felter, lukker ikke.
- Felterne Låner, telefonnumre (Telefon, Mobil, Fastnet, Fax …) og Materialenummer får app'ens eget tastatur med tal, slet og Enter – plus C (Låner), + (telefon) eller kamera (Materialenummer; nummeret læses som stregkode eller tal og tastes ind). Tasterne sendes som almindelige tastetryk. ABC giver telefonens tastatur. Felter med tal-agtige navne, der ikke genkendes, nævnes i loggen (kun navnet).

App'en ændrer kun udseendet; Ciceros data og logik røres ikke. Hver tilpasning slås til og fra for sig i menuen ⚙ → Indstillinger → Tilpasninger af Cicero – eller alle på én gang (fx før en fejl meldes til Systematic).

## Data og sikkerhed

- App'en gemmer det samme som en almindelig browser (login, mellemlager) og egne indstillinger:
  lys/mørk, pinkode-tastatur, tilpasninger, biblioteksnummer, den sædvanlige længde på
  materialenumre (kun længden) og hvilken version man sidst har set. Den sender selv ingen data
  nogen steder hen; al trafik ud af telefonen er Cicero Mobiles egen, bortset fra opdateringstjekket nedenfor.
- Loggen (materialenumre og RFID-kommandoer) ligger kun i hukommelsen og forsvinder,
  når app'en lukkes. Den skrives ikke til telefonens systemlog. Går app'en ned, gemmes en kort
  fejlbeskrivelse (uden lånerdata) i en fil, som lægges i loggen ved næste start og derefter slettes.
- Intet følger med til skyen eller en ny telefon.
- App'en tjekker GitHub for nye versioner (højst hvert 15. minut, via en lille versionsfil) og tilbyder at opdatere sig selv.
  Der sendes ingen data; den spørger kun efter seneste version. Android installerer kun en
  opdatering med samme digitale segl som den installerede app.
- App'en låser sig efter 2 minutter uden brug, med det samme når skærmen slukkes, man skifter
  til en anden app eller en anden app får fokus (delt skærm), ved opstart, og når man kommer fra
  login-siden ind i Cicero – også når Værktøj til tags eller
  kameraet står fremme. Den låses op med telefonens egen skærmlås (fingeraftryk, ansigt eller pinkode).
  Mens den er låst, er Cicero skjult, og bøger hverken læses eller ændres. Værktøj til tags kræver
  altid oplåsning, også når Cicero står på login-siden.
- Den lokale server svarer kun forespørgsler fra Cicero Mobile
  (`https://cicero.systematic.com`) rettet til localhost – og kun dem, som Cicero inde i NFClibra
  faktisk har sendt (app'en ser hver forespørgsel først og udsteder en billet til netop den – metode,
  sti og parametre). Andre apps på telefonen kan derfor ikke styre RFID-læseren på egen hånd.
- Kameraet (scanning af lånerkort) gives kun til Ciceros egen side, og pinkoden sendes kun dertil.
- Andre hjemmesider end Cicero og login åbnes i telefonens browser, ikke i app'en – også ved
  omdirigeringer. Indlejrede rammer fra andre sider blokeres.
- Ukrypteret forbindelse er kun tilladt til app'ens egen server på telefonen.
- Ciceros certifikat er udstedt under Sectigos nyere rødder (Root R46/E46 fra 2021), som ældre Android (fx 12) ikke kender. App'en har derfor de to offentlige rødder med – kun for systematic.com og i tillæg til telefonens egen liste. Chrome og nyere Android godkender dem allerede. Afvises et certifikat alligevel, afvises forbindelsen, og loggen skriver hvorfor.
- Værktøjets kamera genkender tal og stregkoder på selve telefonen (Googles ML Kit via Play-tjenester).
  Billeder gemmes ikke og sendes ikke; Play-tjenester kan sende anonym brugsstatistik om genkendelsen til Google.
- Webvisningens "Safe Browsing" (som sender adresser til Google) er slået fra; app'en viser kun Cicero og login.
- Miniaturen i "seneste apps" viser ikke Cicero.
- Links, der åbner et nyt vindue, åbnes i telefonens browser.

## Protokol

| Cicero kalder | App'en gør |
|---|---|
| `/.status` | svarer med status (test forbindelse) |
| `/events/` | sender `addTag`/`removeTag`, når en bog holdes mod / fjernes fra telefonen |
| `/scan` | returnerer bogen ved telefonen |
| `/start`, `/stop` | Cicero begynder/holder op med at lytte |
| `/alarmOn`, `/alarmOff` | sætter AFI til 0x07 (sikret) / 0xC2 (udlånt) |
| `/write?barcode=` | programmerer tagget efter den danske RFID-datamodel (altid som del 1 af 1 – der ligger højst én bog ved telefonen; sæt programmeres i Værktøj til tags) |
| `/writetagbarcode?tagid=&barcode=` | programmerer et bestemt tag (ud fra tag-id) |

Protokollen er genskabt ud fra go-feigs dokumentation og adfærd (MIT-licens, Deichman bibliotek, Oslo).
Der er ikke kopieret kode fra go-feig. Alarm-værdierne (AFI 0x07/0xC2) følger samme praksis som go-feig og ISO 28560.

## Ændringer

Se [CHANGELOG.md](CHANGELOG.md).

## Licens

Endnu ikke valgt.

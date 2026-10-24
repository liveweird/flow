// The changelog is a build-time artifact: entries are authored here (newest first) and bundled
// into the SPA, so it can only change with a deploy — never at runtime. The newest entry's
// version must equal APP_VERSION in ./version.ts (the shell's eager import — this file stays
// out of the main bundle): a release adds the entry AND bumps that literal; entries.test.ts
// pins the pair. Bodies are markdown, one per language (content, not chrome — hence not in
// locales/); keep the phrases tests assert on in plain text runs, and follow the Polish style
// conventions (inclusive slash forms, active voice).
interface ChangelogEntry {
  version: string;
  /** Release date, YYYY-MM-DD. Keep the array strictly descending by date. */
  date: string;
  /** Markdown body, English. */
  en: string;
  /** Markdown body, Polish. */
  pl: string;
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  {
    version: "1.15.0",
    date: "2026-10-24",
    en: `**Sign-in changes now take effect on the very next request.**

- An account that is removed, demoted to a regular user or given a new password loses access on its next request, instead of staying signed in for up to 15 minutes. A demotion or promotion keeps you signed in with your new role; a removal or a new password signs you out.
- Changing your own password now signs you out everywhere, including this device: sign in again with the new password.
- Signing out is immediate on every server.`,
    pl: `**Zmiany dotyczące logowania działają już przy następnym żądaniu.**

- Gdy administrator/administratorka usunie Twoje konto, zdegraduje Cię do zwykłego użytkownika/zwykłej użytkowniczki albo nada Ci nowe hasło, tracisz dostęp już przy następnym żądaniu, zamiast pozostawać zalogowany/zalogowana do 15 minut. Po degradacji lub awansie zostajesz zalogowany/zalogowana z nową rolą; po usunięciu konta lub nadaniu nowego hasła wylogowujemy Cię.
- Gdy zmieniasz własne hasło, wylogowujemy Cię wszędzie, także na tym urządzeniu: zaloguj się ponownie nowym hasłem.
- Wylogowanie działa natychmiast na każdym serwerze.`,
  },
  {
    version: "1.14.4",
    date: "2026-10-23",
    en: `**Reports now have a fair-use limit per person.**

- If one person loads reports very quickly (more than 120 a minute by default), Flow pauses their report loads for up to a minute and shows "Too many requests. Wait a minute and try again." Everyone else keeps working as usual.`,
    pl: `**Raporty mają teraz limit uczciwego użycia na osobę.**

- Jeśli jedna osoba wczytuje raporty bardzo szybko (domyślnie ponad 120 na minutę), Flow wstrzymuje jej raporty na czas do minuty i pokazuje komunikat „Zbyt wiele żądań. Odczekaj minutę i spróbuj ponownie.” Pozostałe osoby pracują bez zmian.`,
  },
  {
    version: "1.14.3",
    date: "2026-10-23",
    en: `**An older or hand-written report link with a bar's item list open now keeps that list open.**

- When such a link names no team, the report picks your default team as before, and the list of items behind the bar stays open instead of sometimes closing.`,
    pl: `**Starszy lub wpisany ręcznie link do raportu z otwartą listą pozycji słupka zachowuje teraz tę listę.**

- Gdy taki link nie wskazuje zespołu, raport jak dotąd wybiera Twój domyślny zespół, a lista pozycji za słupkiem pozostaje otwarta, zamiast czasem się zamykać.`,
  },
  {
    version: "1.14.2",
    date: "2026-10-23",
    en: `**Sessions are harder to hijack: the token that renews your session now works only once.**

- Each time Flow renews your session in the background, the previous renewal token stops working, so a copied or stolen one is refused. A short allowance covers a renewal whose answer got lost on the way.
- Signing out ends that allowance too, even when you sign out after a long break.
- Sessions open in several tabs keep working as before.`,
    pl: `**Sesje trudniej przejąć: token odnawiający sesję działa teraz tylko raz.**

- Za każdym razem, gdy Flow odnawia sesję w tle, poprzedni token odnowienia przestaje działać, więc skopiowany lub skradziony token zostaje odrzucony. Krótki zapas czasu obejmuje odnowienie, którego odpowiedź zgubiła się po drodze.
- Wylogowanie kończy też ten zapas, nawet po dłuższej przerwie.
- Sesje otwarte w kilku kartach działają jak dotąd.`,
  },
  {
    version: "1.14.1",
    date: "2026-10-23",
    en: `**Small wording and layout fixes across the reports.**

- The Data quality summary and the Aging WIP thresholds now show their headline figures at the same size as the report tiles.
- An empty chart now says what to try (fewer or different filters), and so does a team without Jira members.
- The list of languages in the account menu marks the chosen one for screen readers, and the theme switch has a name.
- In Polish, work items are consistently called *pozycje*, and the hints under a few fields end like the rest.
- The "Of N finished" line above a distribution's left-out list reads correctly for a single item.`,
    pl: `**Drobne poprawki sformułowań i układu w raportach.**

- Podsumowanie jakości danych i progi starzenia WIP pokazują główne liczby w takim samym rozmiarze jak kafelki raportów.
- Pusty wykres podpowiada teraz, co spróbować (mniej filtrów albo inne), podobnie zespół bez członków/kiń Jira.
- Lista języków w menu konta oznacza wybrany język dla czytników ekranu, a przełącznik motywu ma nazwę.
- Po polsku elementy pracy są konsekwentnie nazywane *pozycjami*, a podpowiedzi pod kilkoma polami kończą się tak jak pozostałe.`,
  },
  {
    version: "1.14.0",
    date: "2026-10-22",
    en: `**Costs compares the team's actual work-category mix with its targets, and shows how each category's share builds up week by week.**

- **Work-category targets against the actual mix**: for a team with targets, a bar per category shows the actual share minus the target in percentage points (the side of the zero line tells over from under), over a table of target, man-days, actual share and difference. A category that is *Not mapped any more* is marked. A team without targets gets a link to its targets page.
- **Work-category share, cumulative by week**: a line per category showing its share of everything logged from the start of the period through each week — the right end is the period's whole mix — with the weekly figures in a table. It also answers for one author picked; it is left out for periods longer than 160 weeks.`,
    pl: `**Koszty porównują rzeczywisty podział pracy zespołu według kategorii z jego celami i pokazują, jak udział każdej kategorii narasta tydzień po tygodniu.**

- **Cele według kategorii pracy a rzeczywisty podział**: dla zespołu z celami słupek każdej kategorii pokazuje rzeczywisty udział minus cel w punktach procentowych (strona linii zera mówi, czy jest powyżej, czy poniżej), nad tabelą z celem, osobodniami, rzeczywistym udziałem i różnicą. Kategoria *Już niezmapowana* jest oznaczona. Zespół bez celów dostaje link do strony z celami.
- **Udział kategorii pracy, narastająco tydzień po tygodniu**: linia dla każdej kategorii z jej udziałem w całości zalogowanej od początku okresu do każdego tygodnia — prawy koniec to cały podział w okresie — z tygodniowymi liczbami w tabeli. Działa też po wybraniu jednej osoby; dla okresów dłuższych niż 160 tygodni go nie rysujemy.`,
  },
  {
    version: "1.13.0",
    date: "2026-10-22",
    en: `**Costs shows each closed sprint's actual cost.**

- **Actual cost per sprint, by epic**: a column per closed sprint of the period, stacked by the epic each task belongs to now — the six biggest epics in colours of their own, then *Other epics* and *No epic*.
- **Actual cost against capacity**: each sprint's cost beside the team's capacity for it, with the ratio and where the capacity comes from. Everyone sees the capacity, not only administrators and managers. With one author picked there is no capacity (it is the team's figure).
- Time logged in the period **outside those sprints** is noted under the widgets.
- Click a bar — or a figure in the table — to list the worklogs behind it: a whole sprint, or one epic of it.`,
    pl: `**Koszty pokazują koszt rzeczywisty każdego zakończonego sprintu.**

- **Koszt rzeczywisty sprintów wg epików**: kolumna dla każdego zakończonego sprintu z okresu, ułożona według epiku, do którego zadanie należy teraz — sześć największych epików we własnych kolorach, potem *Pozostałe epiki* i *Bez epiku*.
- **Koszt rzeczywisty a pojemność**: koszt każdego sprintu obok pojemności zespołu na ten sprint, z ilorazem i źródłem pojemności. Pojemność widzą wszyscy/wszystkie, nie tylko administratorzy/administratorki i kierownicy/kierowniczki. Po wybraniu jednej osoby pojemności nie ma (to liczba zespołu).
- Czas zalogowany w okresie **poza tymi sprintami** opisuje jedna linijka pod widżetami.
- Kliknij słupek — albo liczbę w tabeli — aby zobaczyć wpisy czasu pracy, które za nim stoją: cały sprint albo jeden jego epik.`,
  },
  {
    version: "1.12.0",
    date: "2026-10-22",
    en: `**Teams can now record work-category targets.**

- Each team has a **Work-category targets** page, opened from its row on the *Teams* page: the share of the team's effort each work category should take, in whole percent, adding up to exactly 100 % (or empty for no targets). The *No category* row is always there.
- Everyone can see the targets; administrators edit them. A category that is no longer mapped keeps its stored target, marked as such, until the next save drops it.
- The *Costs* report will compare the actual split against these targets in a later version.`,
    pl: `**Zespoły mogą teraz zapisywać cele według kategorii pracy.**

- Każdy zespół ma stronę **Cele według kategorii pracy**, otwieraną z jego wiersza na stronie *Zespoły*: jaką część nakładu pracy zespołu powinna zajmować każda kategoria pracy, w pełnych procentach, razem dokładnie 100 % (albo pusto, gdy zespół nie ma celów). Wiersz *Bez kategorii* jest zawsze dostępny.
- Cele widzą wszystkie osoby zalogowane; edytują je administratorzy/rki. Kategoria, która nie jest już zmapowana, zachowuje zapisany cel z odpowiednią plakietką, aż kolejny zapis go usunie.
- Raport *Koszty* porówna rzeczywisty podział z tymi celami w jednej z kolejnych wersji.`,
  },
  {
    version: "1.11.0",
    date: "2026-10-22",
    en: `**The Cost matrix is now Costs.**

- The page is called **Costs** and sits one step higher in the menu, between *Estimation* and *Flow metrics*. Old links to the Cost matrix redirect to it.
- The *Man-days by author and domain* matrix and the *Man-days by epic and author* table are gone (new sprint widgets come next). The totals, the foreign-work share and the *Man-days by activity type* and *by work category* breakdowns stay, with the drill to the tasks behind each slice.
- The matrix's *Week* and *Sprint* split and its capacity column went with it.
- The REST API moved too: \`/api/v1/reports/cost-matrix\` is now \`/api/v1/reports/costs\` (the old path no longer answers).`,
    pl: `**Macierz kosztów to teraz Koszty.**

- Strona nazywa się **Koszty** i jest w menu o jeden krok wyżej, między *Szacowaniem* a *Metrykami przepływu*. Stare linki do Macierzy kosztów przekierowują na nią.
- Macierz *Osobodni wg autora/ki i domeny* oraz tabela *Osobodni wg epiku i autora/ki* zniknęły (nowe widżety sprintów pojawią się wkrótce). Zostają sumy, udział pracy spoza zespołu oraz podziały *Osobodni wg typu aktywności* i *wg kategorii pracy* — wraz z przejściem do zadań stojących za każdym wycinkiem.
- Razem z macierzą zniknęły jej podział *Tydzień* i *Sprint* oraz kolumna pojemności.
- Zmieniło się też REST API: \`/api/v1/reports/cost-matrix\` to teraz \`/api/v1/reports/costs\` (stara ścieżka już nie odpowiada).`,
  },
  {
    version: "1.10.0",
    date: "2026-10-22",
    en: `**Throughput shows Actual cost beside Delivered.**

- On **Throughput**, per period and per sprint, a gray **Actual cost** bar stands beside **Delivered**: the time logged inside the period or sprint on the items that were not yet done when it began — delivered in it or still open (for a sprint, the items of its final scope).
- The tables gain an *Actual cost (MD)* column, and in the *Cumulative* view the cost accumulates too, as a dashed line over the delivered areas.
- A click on the figure (or a double-click on the bar) lists the tasks the time was logged on, and their total is the figure.
- With people picked, Actual cost is split by who logged the time, while Delivered stays by assignee.`,
    pl: `**Przepustowość pokazuje Koszt rzeczywisty obok Dostarczonych.**

- W **Przepustowości**, w okresach i w sprintach, obok **Dostarczonych** stoi szary słupek **Kosztu rzeczywistego**: czasu zalogowanego w okresie lub sprincie na zadaniach, które nie były jeszcze zakończone w chwili jego rozpoczęcia — dostarczonych w nim albo nadal otwartych (w sprincie: na zadaniach jego zakresu końcowego).
- Tabele mają nową kolumnę *Koszt rzeczywisty (MD)*, a w widoku *Narastająco* koszt również narasta — jako przerywana linia nad obszarami dostarczonych.
- Kliknięcie liczby (albo dwukrotne kliknięcie słupka) pokazuje zadania, na których zalogowano ten czas, a ich suma jest tą liczbą.
- Po wybraniu osób koszt rzeczywisty dzielimy według tego, kto zalogował czas, a dostarczone nadal według osoby przypisanej.`,
  },
  {
    version: "1.9.0",
    date: "2026-10-22",
    en: `**Commitments and Throughput: each sprint split by epic.**

- **Commitments** has a new chart, *Initial and final scope per sprint, by epic*, and **Throughput** one called *Delivered per sprint, by epic*. Each sprint's scope, or the work it delivered, is split into the six biggest epics, the rest as *Other epics*, and work with *No epic* — to see how many epics a team works on at once.
- A table under each chart lists every sprint and epic, and a click on a figure (or a double-click on a segment) lists the items behind it.
- With people picked, the figures are theirs.`,
    pl: `**Zobowiązania i Przepustowość: każdy sprint w podziale na epiki.**

- **Zobowiązania** mają nowy wykres, *Zakres początkowy i końcowy w sprintach, według epików*, a **Przepustowość** wykres *Dostarczone w sprintach, według epików*. Zakres każdego sprintu, albo dostarczona w nim praca, dzieli się na sześć największych epików, resztę jako *Pozostałe epiki* oraz pracę *Bez epiku* — żeby zobaczyć, nad iloma epikami zespół pracuje jednocześnie.
- Tabela pod każdym wykresem wylicza wszystkie sprinty i epiki, a kliknięcie liczby (albo dwukrotne kliknięcie segmentu) pokazuje zadania, które za nią stoją.
- Po wybraniu osób liczby dotyczą tylko ich.`,
  },
  {
    version: "1.8.0",
    date: "2026-10-22",
    en: `**"Users" is now "Flow users".**

- The menu entry, the pages and their addresses now say **Flow users**, to tell Flow's own accounts from Jira users. An old \`/users\` link (a bookmark, an email) still lands on the right page.
- The REST API moved too: \`/api/v1/users\` is now \`/api/v1/flow-users\`. Scripts that call the old path must be updated.`,
    pl: `**„Użytkownicy/czki” to teraz „Użytkownicy/czki Flow”.**

- Pozycja w menu, strony i ich adresy nazywają się teraz **Użytkownicy/czki Flow**, żeby odróżnić konta Flow od użytkowników/użytkowniczek Jira. Stary link \`/users\` (zakładka, e-mail) nadal prowadzi na właściwą stronę.
- Zmieniło się też REST API: \`/api/v1/users\` to teraz \`/api/v1/flow-users\`. Skrypty, które wywołują starą ścieżkę, trzeba zaktualizować.`,
  },
  {
    version: "1.7.0",
    date: "2026-10-22",
    en: `**Reports are read for a team or a domain, never the whole unit; Deep dive asks for a domain before epics.**

- **A team is always picked** on *Aging WIP*, *Blocked time*, *Data quality* and the *Cost matrix*: *All teams* is gone from their Team filter, and a link or a remembered filter without a team opens on your last picked team (else the first team by name). A domain can still be picked beside the team.
- **A team or a domain is needed** on *Estimated backlog* and *Epic progress*, like *WIP*: a domain stands in for the team, and picking one replaces the other. A single epic opened from *Epic progress* still needs neither.
- *Epic progress* has no *Whole unit* level any more, so its breadcrumb starts at the epic's domain (when you came through it).
- On *Home*, a section's title opens its report on the first team the overview shows, and the *Not in any team* note no longer links to *Data quality*, which now always reads one team.
- **Deep dive, by epics: pick a domain first.** The epic picker lists that domain's epics once you do, and changing the domain no longer drops epics you already picked, so a selection can span domains. Epics already picked stay removable without a domain.`,
    pl: `**Raporty liczymy dla zespołu lub domeny, nigdy dla całej jednostki; Deep dive prosi o domenę przed epikami.**

- **Zespół jest zawsze wybrany** w raportach *Wiek WIP*, *Czas zablokowania*, *Jakość danych* i w *Macierzy kosztów*: z filtra Zespół zniknęła opcja *Wszystkie zespoły*, a link lub zapamiętany filtr bez zespołu otwiera się na ostatnio wybranym zespole (a gdy go nie ma — na pierwszym zespole według nazwy). Obok zespołu nadal można wybrać domenę.
- **Potrzebny jest zespół lub domena** w raportach *Oszacowany backlog* i *Postęp epików*, tak jak w *WIP*: domena zastępuje zespół, a wybranie jednego z nich zastępuje drugie. Pojedynczy epik otwarty z *Postępu epików* nadal nie wymaga żadnego z nich.
- *Postęp epików* nie ma już poziomu *Cała jednostka*, więc jego ścieżka zaczyna się od domeny epiku (jeśli wchodzisz przez nią).
- Na *Stronie głównej* tytuł sekcji otwiera jej raport na pierwszym zespole z przeglądu, a notka *Poza jakimkolwiek zespołem* nie prowadzi już do *Jakości danych*, która teraz zawsze pokazuje jeden zespół.
- **Deep dive, wg epików: najpierw wybierz domenę.** Lista epików pokazuje wtedy epiki tej domeny, a zmiana domeny nie usuwa już wybranych epików, więc wybór może obejmować kilka domen. Wybrane już epiki możesz usunąć także bez domeny.`,
  },
  {
    version: "1.6.1",
    date: "2026-10-21",
    en: `**Capacity: a locked day's date stays readable.**

- In a sprint calendar, the column of a day counted in an earlier sprint — and of a public holiday — keeps its hatching, but the day's date and weekday above it now sit on the plain shade instead of under the hatch, and the hatching of a day counted in an earlier sprint is lighter.`,
    pl: `**Pojemność: data zablokowanego dnia jest czytelna.**

- W kalendarzu sprintu kolumna dnia liczonego we wcześniejszym sprincie — i święta — zachowuje kreskowanie, ale data i dzień tygodnia nad nią stoją teraz na zwykłym tle, a nie pod kreskowaniem, a kreskowanie dnia liczonego we wcześniejszym sprincie jest jaśniejsze.`,
  },
  {
    version: "1.6.0",
    date: "2026-10-21",
    en: `**One Jira site for every data source, and no project in two data sources.**

- Every data source now uses the **same Jira site**. Creating a data source for a different site is refused.
- A Jira project can belong to **one data source only**, so its issues are never synced twice and never counted twice in the reports. Adding a project key that another data source already holds is refused, on create and on edit.
- The data source editor now shows which field is the problem: *Site URL*, *Project keys* or *Name*.`,
    pl: `**Jedna witryna Jira dla wszystkich źródeł danych i żaden projekt w dwóch źródłach.**

- Wszystkie źródła danych korzystają teraz z **tej samej witryny Jira**. Dodanie źródła danych dla innej witryny jest odrzucane.
- Projekt Jira może należeć do **tylko jednego źródła danych**, więc jego zgłoszenia nigdy nie są pobierane dwa razy ani liczone podwójnie w raportach. Dodanie klucza projektu, który ma już inne źródło danych, jest odrzucane — przy tworzeniu i przy edycji.
- Edytor źródła danych pokazuje teraz, które pole jest problemem: *Adres URL witryny*, *Klucze projektów* lub *Nazwa*.`,
  },
  {
    version: "1.5.0",
    date: "2026-10-21",
    en: `**Cost matrix: man-days by epic.**

- Under the matrix, the new *Man-days by epic* table splits the very same man-days — the same period, team and person — by epic: a row per epic (its Jira key, summary and domain), the 50 biggest first, the rest folded into *Other epics*, and the work without an epic last under *No epic*.
- A column per author (per author team in the unit view), then *Total*, and a footer with the column totals — every figure reconciles to the matrix above.
- Every figure opens the worklogs behind it, as a list of the tasks and epics they were logged on; the cells of *Other epics* open through its *Total*.`,
    pl: `**Macierz kosztów: osobodni według epików.**

- Pod macierzą nowa tabela *Osobodni według epiku* rozbija te same osobodni — ten sam okres, zespół i osobę — na epiki: wiersz na epik (jego klucz w Jirze, tytuł i domena), 50 największych na początku, reszta zwinięta w *Pozostałe epiki*, a praca bez epiku na końcu w wierszu *Bez epiku*.
- Kolumna na autora/kę (w widoku jednostki na zespół autorów), potem *Suma* i stopka z sumami kolumn — każda liczba zgadza się z macierzą powyżej.
- Każda liczba otwiera wpisy czasu pracy, które za nią stoją, jako listę zadań i epików, na których je zapisano; komórki *Pozostałych epików* otwiera ich *Suma*.`,
  },
  {
    version: "1.4.3",
    date: "2026-10-21",
    en: `**Capacity: a day belongs to one sprint of a team only.**

- **No more double counting.** When one sprint ends and the team's next one starts on the same day — or when two sprints overlap — that day now counts in the **earlier** sprint only.
- **Shaded and named.** In the later sprint's calendar the day is hatched, locked off and labelled *Counted in* and the earlier sprint's name; the legend explains it, and the total skips it.
- **Default capacity matches the grid.** Default capacity (a sprint with no saved calendar) now counts whole working days, so it equals the calendar grid's total — and most sprints' default figure moves, by up to a day per member.
- **Reports move after the next data refresh.** Saved capacity figures — Commitments' capacity and the cost matrix — are recomputed on the next refresh, and closed sprints may show up once as snapshot drift in *Data quality*.`,
    pl: `**Pojemność: dzień należy tylko do jednego sprintu zespołu.**

- **Koniec z podwójnym liczeniem.** Gdy jeden sprint kończy się, a kolejny sprint zespołu zaczyna się tego samego dnia — albo gdy sprinty na siebie zachodzą — taki dzień liczy się teraz tylko we **wcześniejszym** sprincie.
- **Zacieniony i podpisany.** W kalendarzu późniejszego sprintu ten dzień jest zakreskowany, zablokowany i opisany *Liczony w* z nazwą wcześniejszego sprintu; legenda to wyjaśnia, a suma go pomija.
- **Pojemność domyślna zgadza się z siatką.** Pojemność domyślna (sprint bez zapisanego kalendarza) liczy teraz całe dni robocze, więc równa się sumie siatki kalendarza — a wartość domyślna większości sprintów się zmieni, nawet o dzień na osobę.
- **Raporty zmienią się po następnym odświeżeniu danych.** Zapisane wartości pojemności — pojemność w Zobowiązaniach i macierz kosztów — przeliczą się przy następnym odświeżeniu, zamknięte sprinty mogą jednorazowo pojawić się w *Jakości danych* jako rozjazd migawki.`,
  },
  {
    version: "1.4.2",
    date: "2026-10-21",
    en: `**WIP: the "Working days only" switch lines up with its row.**

- On the *WIP* report the *Working days only* switch now sits centred on the *Show in chart* chips beside it, instead of a little above them. In a narrow window it moves to its own line under the chips without the extra gap.`,
    pl: `**WIP: przełącznik „Tylko dni robocze” jest w jednej linii ze swoim wierszem.**

- W raporcie *WIP* przełącznik *Tylko dni robocze* stoi teraz na środku wiersza z chipami *Pokaż na wykresie*, a nie nieco nad nimi. W wąskim oknie przechodzi do osobnej linii pod chipami, bez dodatkowego odstępu.`,
  },
  {
    version: "1.4.1",
    date: "2026-10-20",
    en: `**Task estimates: the long-tail hint matches the chart.**

- On the *Task estimates* tab the *Long tail* help now says the last bar holds the values **above** X ("> X"), as the bar itself reads — a task estimated at exactly X sits on the X bar. The other reports keep "≥ X".`,
    pl: `**Szacunki zadań: podpowiedź długiego ogona zgadza się z wykresem.**

- Na zakładce *Szacunki zadań* podpowiedź *Długi ogon* mówi teraz, że ostatni słupek zbiera wartości **powyżej** X („> X”), tak jak opisuje go sam słupek — zadanie oszacowane dokładnie na X trafia na słupek X. Pozostałe raporty zachowują „≥ X”.`,
  },
  {
    version: "1.4.0",
    date: "2026-10-20",
    en: `**Estimation: a new Task estimates tab — how big are the tasks a team finishes?**

- **How many tasks at how many MD.** Reports → Estimation → *Task estimates* draws, for one team and period, how the finished tasks' estimates spread: one bar per half man-day (0.5, 1, 1.5 MD…), so you see at a glance whether the team cuts its work into small pieces or carries large ones.
- **At done and at start.** The first chart uses each task's estimate when it was finished (the size as delivered); the second its estimate when work began (the size as planned) — side by side, they show how estimates grow.
- **The usual filters.** Period, team, members (several people split each bar), domain (delivered in or earned in), activity type, work category and the long tail, which folds the largest estimates into one last bar.
- **Nothing hidden.** Tasks with no estimate are counted under each chart, not drawn; click any bar or count to list the tasks behind it.`,
    pl: `**Szacowanie: nowa zakładka Szacunki zadań — jak duże są zadania, które kończy zespół?**

- **Ile zadań na ile MD.** Raporty → Szacowanie → *Szacunki zadań* pokazuje dla jednego zespołu i okresu, jak rozkładają się szacunki zakończonych zadań: jeden słupek na każde pół osobodnia (0.5, 1, 1.5 MD…), więc od razu widzisz, czy zespół tnie pracę na małe kawałki, czy niesie duże.
- **Przy zakończeniu i na starcie.** Pierwszy wykres używa szacunku zadania z chwili zakończenia (rozmiar dostarczony), drugi — szacunku z chwili rozpoczęcia pracy (rozmiar planowany); obok siebie pokazują, jak rosną szacunki.
- **Zwykłe filtry.** Okres, zespół, osoby (kilka osób dzieli każdy słupek), domena (dostarczone w niej albo wypracowane w niej), typ aktywności, kategoria pracy i długi ogon, który zbiera największe szacunki w jeden ostatni słupek.
- **Nic nie znika.** Zadania bez szacunku są policzone pod każdym wykresem, a nie narysowane; kliknij dowolny słupek lub liczbę, aby zobaczyć zadania, które się za nimi kryją.`,
  },
  {
    version: "1.3.0",
    date: "2026-10-20",
    en: `**Home: choose the teams it shows.**

- **Up to three teams, your choice.** A *Teams shown* field at the top of Home picks which teams the overview compares — at most three, so every chart stays readable.
- **A sensible default.** Until you pick, Home shows the teams you manage, then the others by name. *Back to my default teams* returns to it.
- **Remembered for you.** Your pick stays the next time you open Home on this browser, and is forgotten when you sign out.
- **Work outside every team.** The note under *What is missing from the data* now counts the work that belongs to no team at all, whichever teams you show.`,
    pl: `**Strona główna: wybierz zespoły, które pokazuje.**

- **Do trzech zespołów, według Twojego wyboru.** Pole *Wyświetlane zespoły* na górze strony głównej wybiera, które zespoły porównuje przegląd — najwyżej trzy, aby każdy wykres był czytelny.
- **Rozsądne ustawienie domyślne.** Dopóki nic nie wybierzesz, strona główna pokazuje zespoły, którymi zarządzasz, a potem pozostałe według nazwy. *Wróć do moich zespołów domyślnych* przywraca to ustawienie.
- **Zapamiętane dla Ciebie.** Twój wybór zostaje przy następnym otwarciu strony głównej w tej przeglądarce i znika po wylogowaniu.
- **Praca poza wszystkimi zespołami.** Notka pod sekcją *Czego brakuje w danych* liczy teraz pracę, która nie należy do żadnego zespołu, niezależnie od tego, które zespoły pokazujesz.`,
  },
  {
    version: "1.2.0",
    date: "2026-10-20",
    en: `**Home: what is missing from the data, as numbers.**

- **A compact table instead of six charts.** The *What is missing from the data* section on Home is now one table: a row per team, a column per finding — tasks with no estimate, no assignee or estimated late, and epics with no estimate, no dates or an estimate far from their tasks' total. It takes much less room.
- **Still one click to the work.** Click any count to list the tasks or epics behind it.
- **Is it getting better?** The task findings show the arrow and the previous 90 days' count beside each number.`,
    pl: `**Strona główna: czego brakuje w danych — w liczbach.**

- **Zwarta tabela zamiast sześciu wykresów.** Sekcja *Czego brakuje w danych* na stronie głównej to teraz jedna tabela: wiersz dla każdego zespołu, kolumna dla każdego ustalenia — zadania bez szacunku, bez osoby przypisanej albo oszacowane za późno oraz epiki bez szacunku, bez dat albo z szacunkiem daleko od sumy ich zadań. Zajmuje dużo mniej miejsca.
- **Nadal jedno kliknięcie do pracy.** Kliknij dowolną liczbę, aby zobaczyć zadania lub epiki, które się za nią kryją.
- **Czy jest lepiej?** Ustalenia dotyczące zadań pokazują przy każdej liczbie strzałkę i liczbę z poprzednich 90 dni.`,
  },
  {
    version: "1.1.0",
    date: "2026-10-20",
    en: `**Compare people: pick a person from a team.**

- **Team first, then the person.** Each side of Compare people now has a *Team* list before its person picker. Choose a team and the picker lists its current members by name — no need to remember how to spell anyone. Typing still narrows the list, and with no team chosen the picker searches everyone, as before.
- **Remembered for you.** Each side keeps its team the next time you open the page, so the comparison you make most often is two clicks away.`,
    pl: `**Porównanie osób: wybierz osobę z zespołu.**

- **Najpierw zespół, potem osoba.** Każda strona porównania ma teraz listę *Zespół* przed wyborem osoby. Gdy wybierzesz zespół, lista pokaże jego obecnych członków/członkinie według imion i nazwisk — nie musisz pamiętać, jak kogo zapisać. Wpisywanie nadal zawęża listę, a bez wybranego zespołu wyszukiwanie obejmuje wszystkie osoby, tak jak dotąd.
- **Zapamiętane dla Ciebie.** Każda strona zachowuje swój zespół przy następnym otwarciu strony, więc porównanie, które robisz najczęściej, masz na dwa kliknięcia.`,
  },
  {
    version: "1.0.0",
    date: "2026-10-18",
    en: `**Flow 1.0 — a new Home: the overview of every team.**

This release marks a milestone: Flow now covers plan, delivery, flow, estimation, cost and data quality across eighteen reports, and Home brings the most important of them together on one page.

- **One page, every team.** Home now opens with when the data was last synced and derived, then seven sections, each comparing the teams side by side: *Last sprint* (capacity, initial and final scope, delivered), *How big the work is* (median task estimate, cycle time and logged cost), *How well estimates hold* (a curve per team, tasks and epics), *Epics in progress* and *Tasks in progress* (how many, and how old: median, 85th and 95th percentile), *How much is delivered* (per week, with cycle time), and *What is missing from the data*.
- **Is it getting better?** Every figure over the last 90 days shows how it compares with the 90 days before — an arrow and the word *Better*, *Worse* or *No change*.
- **From a bar to the work behind it.** Double-click any bar to list the tasks or epics it counts, and each section title opens its full report for the same team and period.
- **Keyboard and screen reader.** Each chart has its numbers in a table that appears when you tab into it.
- **One setting, two places.** How far an epic's estimate may differ from its tasks' total before Data quality flags it is now a Metrics setting ("Epic estimate gap tolerance", 25 % by default), used by Home and the Data quality page alike.`,
    pl: `**Flow 1.0 — nowa strona główna: przegląd wszystkich zespołów.**

To wydanie to kamień milowy: Flow obejmuje teraz plan, dostarczanie, przepływ, szacowanie, koszt i jakość danych w osiemnastu raportach, a strona główna zbiera najważniejsze z nich na jednej stronie.

- **Jedna strona, wszystkie zespoły.** Strona główna zaczyna się od informacji, kiedy dane były ostatnio zsynchronizowane i przeliczone, a potem ma siedem sekcji, w których zespoły są porównane obok siebie: *Ostatni sprint* (pojemność, zakres początkowy i końcowy, dostarczone), *Jak duża jest praca* (mediana szacunku zadania, czasu cyklu i zalogowanego kosztu), *Jak trafne są szacunki* (krzywa dla każdego zespołu, zadania i epiki), *Epiki w toku* i *Zadania w toku* (ile i jak długo: mediana, 85. i 95. percentyl), *Ile jest dostarczane* (tygodniowo, z czasem cyklu) oraz *Czego brakuje w danych*.
- **Czy jest lepiej?** Każda wartość z ostatnich 90 dni pokazuje, jak wypada na tle poprzednich 90 dni — strzałką i słowem *Lepiej*, *Gorzej* albo *Bez zmian*.
- **Od słupka do pracy, która się za nim kryje.** Kliknij dwukrotnie dowolny słupek, aby zobaczyć zadania lub epiki, które liczy, a tytuł każdej sekcji otwiera pełny raport dla tego samego zespołu i okresu.
- **Klawiatura i czytnik ekranu.** Każdy wykres ma swoje liczby w tabeli, która pojawia się, gdy przejdziesz do niej klawiszem Tab.
- **Jedno ustawienie, dwa miejsca.** To, o ile szacunek epika może różnić się od sumy szacunków jego zadań, zanim Jakość danych go wskaże, jest teraz ustawieniem metryk („Tolerancja rozbieżności szacunku epika”, domyślnie 25%), z którego korzystają i strona główna, i strona Jakość danych.`,
  },
  {
    version: "0.45.0",
    date: "2026-10-18",
    en: `**Jira keys open in Jira.**

- Task and epic keys in the reports — the item lists behind bars, Aging WIP, Blocked time, Epic accuracy, Data quality, Epic progress and the Deep dive — now link to the issue in Jira, in a new tab.
- A small icon marks the link; screen readers hear "opens in Jira".`,
    pl: `**Klucze z Jiry otwierają się w Jirze.**

- Klucze zadań i epików w raportach — na listach elementów za słupkami, w Starzeniu się pracy w toku, Czasie zablokowania, Trafności szacowania epików, Jakości danych, Postępie epików i w Deep dive — prowadzą teraz do zgłoszenia w Jirze, w nowej karcie.
- Link oznacza mała ikona; czytnik ekranu odczytuje „otwiera się w Jirze”.`,
  },
  {
    version: "0.44.0",
    date: "2026-10-18",
    en: `**Print any report — or save it as a PDF — and jump back to the top.**

- Every report has a **Print / save as PDF** button. The printout drops the navigation and the on-screen controls, keeps the charts and tables, and starts with a header naming the report, its filters and when it was printed. It always prints in light colours, even if you use the dark theme.
- On long report pages a **Back to top** button appears in the bottom-right corner once you scroll down.`,
    pl: `**Wydrukuj dowolny raport — albo zapisz go jako PDF — i wróć na górę jednym kliknięciem.**

- Każdy raport ma przycisk **Drukuj / zapisz jako PDF**. Wydruk pomija nawigację i kontrolki ekranowe, zachowuje wykresy i tabele, a zaczyna się nagłówkiem z nazwą raportu, jego filtrami i chwilą wydruku. Zawsze drukuje się w jasnych kolorach, nawet jeśli używasz ciemnego motywu.
- Na długich stronach raportów po przewinięciu w dół w prawym dolnym rogu pojawia się przycisk **Na górę**.`,
  },
  {
    version: "0.43.0",
    date: "2026-10-18",
    en: `**Deep dive: a tidier Selection that folds away.**

- The Selection section is laid out on one aligned row: the mode first, then the domain, the sprints or epics and the dates, with Show at the end.
- Once a selection is shown, the section folds to a single line — for example "Flow · Sprints: Sprint 4, Sprint 5 · 2026-09-01 → 2026-10-15" — so the matrix gets the space. "Edit selection" opens it again, and your choice is remembered.
- If the server cannot show a selection, the form opens again so you can fix it.`,
    pl: `**Deep dive: uporządkowany wybór, który się zwija.**

- Sekcja Wybór jest ułożona w jednym wyrównanym wierszu: najpierw tryb, potem domena, sprinty albo epiki i daty, a na końcu Pokaż.
- Po pokazaniu wyboru sekcja zwija się do jednej linii — na przykład „Flow · Sprinty: Sprint 4, Sprint 5 · 2026-09-01 → 2026-10-15” — więc macierz ma więcej miejsca. „Edytuj wybór” otwiera ją ponownie, a Twój wybór jest zapamiętany.
- Jeśli serwer nie może pokazać wyboru, formularz otwiera się ponownie, żeby można go było poprawić.`,
  },
  {
    version: "0.42.0",
    date: "2026-10-18",
    en: `**Deep dive: easier-to-read cell tooltips.**

- Hovering a matrix cell now shows its figures in sections — Plan, Approximate EV, Earned value and done items, work in progress with nothing earned yet, and Cost by author — each with a heading and separated by lines.
- Labels sit on the left and figures line up on the right.`,
    pl: `**Deep dive: czytelniejsze podpowiedzi komórek.**

- Po najechaniu na komórkę macierzy jej wartości są teraz podzielone na sekcje — Plan, Przybliżone EV, Wartość wypracowana i ukończone elementy, praca w toku bez wypracowanej wartości oraz Koszt według autorów — każda z nagłówkiem i oddzielona linią.
- Etykiety są po lewej, a liczby wyrównane po prawej.`,
  },
  {
    version: "0.41.0",
    date: "2026-10-18",
    en: `**Compare people: the better figure is highlighted, not the difference.**

- The cell holding the better value — A's or B's — is now tinted teal and marked with a check, so you see the winner where the number is.
- The Difference column shows the plain gap, without a sign, arrow or colour.
- Ties, neutral rows and figures based on too few items stay untinted.`,
    pl: `**Porównanie osób: wyróżniona jest lepsza wartość, a nie różnica.**

- Komórka z lepszą wartością — osoby A albo B — ma teraz morskie tło i znacznik ✓, więc lepszą stronę widać tam, gdzie jest liczba.
- Kolumna Różnica pokazuje samą różnicę, bez znaku, strzałki i koloru.
- Remisy, wiersze neutralne i wartości oparte na zbyt małej liczbie elementów zostają bez wyróżnienia.`,
  },
  {
    version: "0.40.0",
    date: "2026-10-18",
    en: `**A cleaner items panel.**

- The "Back to the chart" button is gone — close the panel with the ✕ in its corner, the Escape key or the browser's Back button, and you land where you were.
- The panel's title now stands out: what is listed in bold, with the sprint, period, range or person as a smaller line under it.`,
    pl: `**Czytelniejszy panel z elementami.**

- Przycisk „Wróć do wykresu” zniknął — zamknij panel krzyżykiem ✕ w rogu, klawiszem Escape albo przyciskiem Wstecz przeglądarki, a wrócisz tam, gdzie byłeś/byłaś.
- Tytuł panelu jest teraz wyraźniejszy: to, co jest na liście, pogrubione, a sprint, okres, przedział albo osoba mniejszą czcionką pod spodem.`,
  },
  {
    version: "0.39.0",
    date: "2026-10-17",
    en: `**New report: Compare people — two people head to head.**

- Pick two people and a date range; they can be in different teams, and someone who changed teams is read across both. The usual domain, activity type and work category filters apply to both alike.
- One table compares delivered work, cycle time, flow efficiency, reported time, estimation accuracy and adjustments, work in progress, blocked time, logged and foreign time, and data quality — A, B and the difference.
- The difference column marks the better side with a teal arrow ("A better by 1.4 days"), where "better" is defined per row; a tie, a neutral row or too few items shows no winner.
- Cycle time is drawn as one histogram with both people side by side, and a work-mix section shows how each person's delivered work splits.
- Every figure opens the items behind it. An administrator, or the manager of both people's teams, also sees each person's available man-days and the figures per available man-day.`,
    pl: `**Nowy raport: Porównanie osób — dwie osoby łeb w łeb.**

- Wybierz dwie osoby i zakres dat; mogą być w różnych zespołach, a osoba, która zmieniła zespół, jest czytana w obu. Zwykłe filtry domeny, typu aktywności i kategorii pracy działają na obie tak samo.
- Jedna tabela porównuje dostarczoną pracę, czas cyklu, efektywność przepływu, zalogowany czas, trafność szacowania i korekty, pracę w toku, czas zablokowania, czas zalogowany i na rzecz innych zespołów oraz jakość danych — A, B i różnicę.
- Kolumna różnicy oznacza lepszą stronę morską strzałką („A lepiej o 1,4 dnia”), a „lepiej” jest zdefiniowane osobno dla każdego wiersza; remis, wiersz neutralny albo zbyt mało elementów nie wskazuje zwycięzcy.
- Czas cyklu to jeden histogram z obiema osobami obok siebie, a sekcja struktury pracy pokazuje, jak dzieli się dostarczona praca każdej osoby.
- Każda liczba otwiera elementy, które się za nią kryją. Administrator/ka albo kierownik/czka zespołów obu osób widzi też dostępne osobodni każdej osoby i wyniki na dostępny osobodzień.`,
  },
  {
    version: "0.38.0",
    date: "2026-10-17",
    en: `**Deep dive: Approximate EV replaces Execution.**

- The matrix no longer counts "task-days". Each finished task's earned value is spread evenly over the working days it spent in progress — a 3 MD task in progress for 5 days earns 0.6 MD on each of them — so plan, approximate EV and cost share one man-day scale.
- The pieces add up to the task's EV exactly, per task, epic, week and month; EV itself still lands on the day the task is done.
- Open tasks, and tasks done without an estimate, earn nothing but keep a hollow mark on the days they were worked, so the work does not vanish.
- The Burn-up tab gains a cumulative Approximate EV line beside EV.`,
    pl: `**Deep dive: Przybliżone EV zamiast Realizacji.**

- Macierz nie liczy już „zadanio-dni”. Wartość wypracowaną każdego ukończonego zadania rozkładamy równo na dni robocze, w których było w toku — zadanie na 3 MD w toku przez 5 dni zarabia po 0,6 MD w każdym z nich — więc plan, przybliżone EV i koszt mają jedną skalę w osobodniach.
- Części sumują się dokładnie do EV zadania — na zadanie, epik, tydzień i miesiąc; samo EV nadal trafia na dzień ukończenia zadania.
- Zadania otwarte i ukończone bez szacunku nic nie zarabiają, ale zachowują pusty znacznik w dniach, w których nad nimi pracowano, więc praca nie znika.
- Zakładka Burn-up zyskuje skumulowaną linię przybliżonego EV obok EV.`,
  },
  {
    version: "0.37.0",
    date: "2026-10-17",
    en: `**Flow remembers your filters on every report.**

- Come back to a report, the Deep dive or Capacity and it opens with the filters you last set there — period, team, people, domain, activity type, work category and the view switches.
- A preset period stays a preset: "Last 30 days" comes back as the last 30 days from today, not the old dates.
- A link that carries its own filters still opens exactly as shared, and does not overwrite what you set.
- A filter that no longer makes sense — a deleted team, a person who left it, a sprint that is gone — is dropped on the way back.
- The remembered filters live in this browser only and are cleared when you sign out.`,
    pl: `**Flow pamięta Twoje filtry w każdym raporcie.**

- Wróć do raportu, do Deep dive albo do Pojemności, a otworzą się z filtrami, które tam ostatnio ustawiłeś/ustawiłaś — okresem, zespołem, osobami, domeną, typem aktywności, kategorią pracy i przełącznikami widoku.
- Okres z gotowej listy zostaje taki, jak go wybrano: „Ostatnie 30 dni” wraca jako ostatnie 30 dni od dziś, a nie stare daty.
- Link z własnymi filtrami nadal otwiera się dokładnie tak, jak go udostępniono, i nie nadpisuje tego, co ustawiłeś/ustawiłaś.
- Filtr, który przestał mieć sens — usunięty zespół, osoba, która z niego odeszła, sprint, którego już nie ma — zostaje pominięty przy powrocie.
- Zapamiętane filtry są tylko w tej przeglądarce i znikają po wylogowaniu.`,
  },
  {
    version: "0.36.0",
    date: "2026-10-17",
    en: `**The Cost matrix shows where the time went: by activity type and by work category.**

- Two donut charts sit beside the matrix: logged man-days per activity type and per work category, over the same work the matrix shows. Each chart appears only while its own filter is off.
- Small slices (under 3%) are grouped into one "Other" slice that lists what it holds.
- Click a slice — or its figure in the table beside the chart — to see the tasks behind it, with the time logged on each and who logged it.`,
    pl: `**Macierz kosztów pokazuje, na co poszedł czas: według typu aktywności i kategorii pracy.**

- Obok macierzy są dwa wykresy pierścieniowe: zalogowane osobodni według typu aktywności i według kategorii pracy, dla tej samej pracy, którą pokazuje macierz. Każdy wykres pojawia się tylko wtedy, gdy jego własny filtr jest wyłączony.
- Małe wycinki (poniżej 3%) łączą się w jeden wycinek „Inne”, który wymienia, co zawiera.
- Kliknij wycinek — albo jego liczbę w tabeli obok wykresu — aby zobaczyć zadania, które się za nim kryją, z czasem zalogowanym na każdym i osobami, które go zalogowały.`,
  },
  {
    version: "0.35.0",
    date: "2026-10-17",
    en: `**Deep dive: a simpler selection.**

- Sprint options show their dates (name · from → to) in every sprint picker — the Deep dive, Capacity and the report filter bar.
- The Connection picker is gone. Pick a domain in either mode — it now narrows the epic list too — and the connection follows from what you pick. Where a domain spans several connections, the options name theirs.
- Changing the domain keeps the sprints and epics that still fit and says which were removed; nothing is wiped silently.
- The "Tasks of an epic" mode is gone. An old link to it opens the whole epic and says so.`,
    pl: `**Deep dive: prostszy wybór.**

- Opcje sprintów pokazują daty (nazwa · od → do) w każdym selektorze sprintów — w Deep dive, w Pojemności i na pasku filtrów raportów.
- Wybór połączenia zniknął. Wskaż domenę w dowolnym trybie — zawęża teraz także listę epików — a połączenie wynika z tego, co wskażesz. Gdy domena obejmuje kilka połączeń, opcje podają nazwę swojego.
- Zmiana domeny zachowuje sprinty i epiki, które nadal pasują, i mówi, które usunęła; nic nie znika po cichu.
- Tryb „Zadania epika” zniknął. Stary link do niego otwiera cały epik i o tym informuje.`,
  },
  {
    version: "0.34.0",
    date: "2026-10-17",
    en: `**Click a figure to see the items behind it — and read them in a wider panel.**

- The **Items** icons are gone. In the tables under the charts, the figures themselves now open the list: Initial and Final on Commitments, every figure on Sprint consistency, Delivered on Throughput and Home, and each range's count under every histogram. A zero has nothing to list, so it stays plain text.
- The items panel is wider and uses its full height, so the list no longer scrolls sideways.
- A screen reader announces each figure with its column and what it opens, for example "20 — Initial (items): show the items of Sprint 4 · Committed".`,
    pl: `**Kliknij liczbę, aby zobaczyć elementy, które się za nią kryją — i przeczytaj je w szerszym panelu.**

- Ikony **Elementy** zniknęły. W tabelach pod wykresami listę otwierają teraz same liczby: Początkowy i Końcowy w Zobowiązaniach, każda liczba w Spójności sprintów, Dostarczone w Przepustowości i na stronie głównej oraz liczebność każdego przedziału pod każdym histogramem. Zero nie ma czego pokazać, więc zostaje zwykłym tekstem.
- Panel z elementami jest szerszy i wykorzystuje całą wysokość, więc lista nie przewija się już w bok.
- Czytnik ekranu odczytuje każdą liczbę razem z jej kolumną i tym, co otwiera, na przykład „20 — Początkowy (zadania): pokaż zadania (Sprint 4 · Zobowiązane)”.`,
  },
  {
    version: "0.33.0",
    date: "2026-10-17",
    en: `**The Cost matrix lists every team member, not only those who logged time.**

- In a team's view, everyone who was in the team on at least one working day of the period now has a row — a member who logged nothing shows zeros instead of being missing.
- The rows are the same for everyone; the team's manager and administrators still see each person's capacity beside them.
- With a filter (domain, activity type, work category), a member who logged only outside it shows zeros too.`,
    pl: `**Macierz kosztów pokazuje wszystkich członków zespołu, nie tylko tych, którzy zalogowali czas.**

- W widoku zespołu każda osoba, która była w zespole w co najmniej jednym dniu roboczym okresu, ma teraz swój wiersz — członek/członkini bez zalogowanego czasu widzi zera, zamiast znikać z tabeli.
- Wiersze są takie same dla wszystkich; kierownik/kierowniczka zespołu i administratorzy nadal widzą obok pojemność każdej osoby.
- Z filtrem (domena, typ aktywności, kategoria pracy) osoba, która logowała czas tylko poza nim, też widzi zera.`,
  },
  {
    version: "0.32.0",
    date: "2026-10-17",
    en: `**Data quality flags epics whose estimate disagrees with their tasks.**

- A new finding lists the epics that have their own estimate and estimated tasks, where the tasks add up to more than 25% above or below the epic's estimate.
- Each epic shows both figures, the gap and its direction: **Over** — the tasks add up to more than the epic, so the scope grew past its budget; **Under** — they add up to less, so the epic is not broken down yet or its estimate is padded. The widest gaps come first.
- It counts in the per-team epic findings and can appear on Home.`,
    pl: `**Jakość danych wskazuje epiki, których szacunek nie zgadza się z zadaniami.**

- Nowe ustalenie wymienia epiki, które mają własny szacunek i oszacowane zadania, a suma zadań jest o ponad 25% wyższa lub niższa od szacunku epika.
- Przy każdym epiku widać obie wartości, różnicę i jej kierunek: **Powyżej** — zadania dają razem więcej niż epik, więc zakres urósł ponad budżet; **Poniżej** — dają mniej, więc epik nie jest jeszcze rozbity na zadania albo jego szacunek jest zawyżony. Największe różnice są na górze.
- Liczy się do ustaleń o epikach w podziale na zespoły i może pojawić się na stronie głównej.`,
  },
  {
    version: "0.31.0",
    date: "2026-10-17",
    en: `**Blocked time charts now show only the work that was blocked.**

- Most finished tasks are never blocked, and their zeros used to fill the first bar so the rest of the chart was hard to read. Both charts — blocked working days and the share of cycle time — now draw only the items with at least one blocked working day, so their bars and percentiles describe how long blocked work stays blocked.
- The never-blocked items are still counted: a line under the chart says how many of all items were never blocked, and each chart's accounting lists them.
- The item lists behind the bars match the new charts.`,
    pl: `**Wykresy Czasu zablokowania pokazują teraz tylko pracę, która była zablokowana.**

- Większość ukończonych zadań nigdy nie jest blokowana, a ich zera wypełniały pierwszy słupek, przez co reszta wykresu była nieczytelna. Oba wykresy — zablokowane dni robocze i udział w czasie cyklu — rysują teraz tylko elementy z co najmniej jednym zablokowanym dniem roboczym, więc ich słupki i percentyle opisują, jak długo zablokowana praca pozostaje zablokowana.
- Elementy nigdy niezablokowane są nadal liczone: linia pod wykresem mówi, ile ze wszystkich elementów nigdy nie było zablokowanych, a rozliczenie każdego wykresu je wymienia.
- Listy elementów za słupkami odpowiadają nowym wykresom.`,
  },
  {
    version: "0.30.0",
    date: "2026-10-17",
    en: `**Teams have one member list: the Jira people.**

- A team's page used to show two lists: the Jira people the reports count, and a separate list of Flow sign-ins that nothing used. The second list is gone, along with its add and remove buttons and the Members count on the Teams list.
- Nothing in the reports changes: they always counted the Jira members, and the team's manager still edits its capacity.`,
    pl: `**Zespoły mają jedną listę członków: osoby z Jiry.**

- Strona zespołu pokazywała dwie listy: osoby z Jiry, które liczą raporty, i osobną listę kont Flow, z której nic nie korzystało. Druga lista znika razem z przyciskami dodawania i usuwania oraz kolumną Członkowie na liście zespołów.
- W raportach nic się nie zmienia: zawsze liczyły członków z Jiry, a pojemność zespołu nadal edytuje jego kierownik/kierowniczka.`,
  },
  {
    version: "0.29.0",
    date: "2026-10-17",
    en: `**The Cost matrix no longer has a Connection filter.**

- A connection is the technical link to Jira. Here it narrowed the matrix the same way the Domain filter already does, so it only added noise: pick a team and a domain instead.
- An old link that still names a connection opens without it.`,
    pl: `**Macierz kosztów nie ma już filtra Połączenie.**

- Połączenie to techniczne powiązanie z Jirą. Tutaj zawężało macierz tak samo jak filtr Domena, więc tylko dokładało szumu: wybierz zespół i domenę.
- Stary link, który wciąż wskazuje połączenie, otwiera się bez niego.`,
  },
  {
    version: "0.28.0",
    date: "2026-10-17",
    en: `**Open the items behind any histogram bar: Cycle time, Reported time, Task and Epic accuracy, Blocked time and Adjustments.**

- Double-click a histogram bar — or a person's segment of it — to list the tasks or epics that fall into that range, each with its own value (working days, ratio, change in estimate…), sorted largest first, with the bar's count on top.
- Every histogram's table has an **Item list** column with a button per range, so the lists open from the keyboard and with a screen reader too.
- This completes the drill-down: every bar chart that sums work planned, work done or time logged now opens the items behind it. Trend lines, areas and the cost matrix's cells don't drill — the report pages say so.`,
    pl: `**Otwórz elementy kryjące się za dowolnym słupkiem histogramu: Czas cyklu, Zalogowany czas, Trafność szacowania zadań i epików, Czas zablokowania i Korekty szacunków.**

- Kliknij dwukrotnie słupek histogramu — albo segment jednej osoby — aby zobaczyć listę zadań lub epików z tego przedziału, każde z własną wartością (dni robocze, stosunek, zmiana szacunku…), od największej, a nad nimi liczbę z samego słupka.
- Tabela każdego histogramu ma kolumnę **Lista zadań** z przyciskiem dla każdego przedziału, więc listy otwierają się też z klawiatury i z czytnikiem ekranu.
- To kończy drążenie w głąb: każdy wykres słupkowy, który sumuje zaplanowaną pracę, wykonaną pracę albo zalogowany czas, otwiera teraz elementy, które się za nim kryją. Linie trendu, obszary i komórki macierzy kosztów tego nie robią — strony raportów o tym mówią.`,
  },
  {
    version: "0.27.0",
    date: "2026-10-17",
    en: `**Open the items behind the sprint bars: Commitments, Throughput per sprint, Sprint consistency and Home.**

- Double-click a sprint's bar — or a person's segment of it — to list the items it sums: committed and final scope on Commitments and Home, delivered work on Throughput's sprint chart, and every Sprint consistency figure (committed, delivered, carried over, dropped, added, removed, final).
- Each sprint row also has an **Items** button, or an Items menu where a row has more figures, so the same lists open from the keyboard and with a screen reader.
- The list opens in the same side panel as Throughput's: the chart stays as it was, and Back, Escape or the browser's Back button returns to it.`,
    pl: `**Otwórz elementy kryjące się za słupkami sprintów: Zobowiązania, Przepustowość w sprintach, Spójność sprintów i strona główna.**

- Kliknij dwukrotnie słupek sprintu — albo segment jednej osoby — aby zobaczyć listę elementów, które sumuje: zakres zobowiązany i końcowy w Zobowiązaniach i na stronie głównej, dostarczoną pracę na wykresie sprintów w Przepustowości oraz każdą wartość Spójności sprintów (zobowiązane, dostarczone, przeniesione, porzucone, dodane, usunięte, końcowe).
- Każdy wiersz sprintu ma też przycisk **Zadania** albo, gdy wartości jest więcej, menu z pozycją Zadania, więc te same listy otwierają się z klawiatury i z czytnikiem ekranu.
- Lista otwiera się w tym samym panelu bocznym co w Przepustowości: wykres zostaje taki, jaki był, a Wróć, Escape albo przycisk Wstecz przeglądarki do niego wraca.`,
  },
  {
    version: "0.26.0",
    date: "2026-10-17",
    en: `**Cost matrix: each person's capacity beside their cost, and a split by week or sprint.**

- In a team's view, the team's manager and administrators now see a **Capacity (MD)** column: each person's man-days available in the period, from the team's sprint calendars (availability per day, coefficients, public holidays off). A day without a saved calendar counts one man-day per team member on a working day. Other readers see the matrix as before.
- A new **Split: Total | Week | Sprint** switch spreads the matrix over Monday-start weeks or over the team's sprints (time outside every sprint comes last), with the period's figures at the end — so a shift in where the time went shows up. Sprint needs a team.
- On a phone the wide table scrolls inside its card, with the author column kept in place.`,
    pl: `**Macierz kosztów: pojemność każdej osoby obok jej kosztu oraz podział na tygodnie albo sprinty.**

- W widoku zespołu manager/managerka zespołu i administratorzy/administratorki widzą teraz kolumnę **Pojemność (MD)**: osobodni, którymi każda osoba dysponowała w okresie, według kalendarzy sprintów zespołu (dostępność w poszczególne dni, współczynniki, święta państwowe wolne). Dzień bez zapisanego kalendarza liczy jeden osobodzień na członka/członkinię zespołu w dniu roboczym. Pozostałe osoby widzą macierz tak jak dotąd.
- Nowy przełącznik **Podział: Łącznie | Tydzień | Sprint** rozkłada macierz na tygodnie od poniedziałku albo na sprinty zespołu (czas poza wszystkimi sprintami na końcu), z wartościami za cały okres na końcu — dzięki temu widać, gdy zmienia się to, na co idzie czas. Podział na sprinty wymaga zespołu.
- Na telefonie szeroka tabela przewija się wewnątrz karty, a kolumna autora/autorki zostaje na miejscu.`,
  },
  {
    version: "0.25.0",
    date: "2026-10-17",
    en: `**Estimated backlog leaves out work nobody has touched in a while.**

- A new **Last change within** slider (7 days to 1 year, or No limit; 90 days by default) leaves out backlog items with no change in that window — no status or field change in Jira, no time logged, and not created in it — so long-dead items stop inflating the estimated backlog and the "sprints ahead" figure.
- The window is applied to every day of the trend, not only today, so the trend and the current figure always agree. The Items tile says how many items (and man-days) the slider hides.
- The User filter is gone: the backlog belongs to a team, not a person. A link with a person in it opens on the team.`,
    pl: `**Oszacowany backlog pomija pracę, której nikt dawno nie dotykał.**

- Nowy suwak **Ostatnia zmiana w ciągu** (od 7 dni do roku albo Bez limitu; domyślnie 90 dni) pomija pozycje backlogu bez żadnej zmiany w tym oknie — bez zmiany statusu ani pola w Jirze, bez zalogowanego czasu i bez utworzenia w tym czasie — więc dawno martwe pozycje przestają zawyżać oszacowany backlog i liczbę „sprintów do przodu”.
- Okno dotyczy każdego dnia trendu, nie tylko dzisiejszego, więc trend i bieżąca wartość zawsze się zgadzają. Kafelek Pozycje podaje, ile pozycji (i osobodni) ukrywa suwak.
- Filtr osoby zniknął: backlog należy do zespołu, nie do osoby. Link z osobą otwiera się na zespole.`,
  },
  {
    version: "0.24.0",
    date: "2026-10-17",
    en: `**Delivery → Throughput: open the tasks behind any bar.**

- Double-click a bar of the delivered-per-period chart — or a person's segment of it — to list the tasks it sums: key, summary, assignee, done date and man-days, with the bar's own count and total on top.
- Every row of the table under the chart has an **Items** button that opens the same list, so it works from the keyboard and with a screen reader too.
- The list opens in a side panel and the chart stays exactly as it was: **Back to the chart**, Escape or the browser's Back button closes it. The link carries the open list, so you can share it.
- More charts get the same drill-down in the next releases.`,
    pl: `**Dostarczanie → Przepustowość: otwórz zadania kryjące się za dowolnym słupkiem.**

- Kliknij dwukrotnie słupek wykresu dostarczonej pracy w okresie — albo segment jednej osoby — aby zobaczyć listę zadań, które sumuje: klucz, podsumowanie, osobę przypisaną, datę zakończenia i osobodni, a nad nimi liczbę i sumę samego słupka.
- Każdy wiersz tabeli pod wykresem ma przycisk **Zadania**, który otwiera tę samą listę, więc działa to też z klawiatury i z czytnikiem ekranu.
- Lista otwiera się w panelu bocznym, a wykres zostaje dokładnie taki, jaki był: **Wróć do wykresu**, Escape albo przycisk Wstecz przeglądarki ją zamyka. Link zawiera otwartą listę, więc możesz go udostępnić.
- Kolejne wykresy dostaną to samo w następnych wydaniach.`,
  },
  {
    version: "0.23.0",
    date: "2026-10-17",
    en: `**Adjustments charts only the estimates that changed.**

- On Estimation → Adjustments, the "Change from start to done" histogram now plots only the items whose estimate changed between start and done. Items that kept their estimate (a 0 % change) used to make one tall bar that dwarfed the real adjustments.
- How many items kept their estimate is shown under the chart and in the accounting list, so the population still adds up. When no estimate changed at all, the page says so instead of drawing an empty chart.
- The median, mean and percentiles now describe the adjustments themselves, and the same applies to the epics block and the per-person split.`,
    pl: `**Korekty pokazują na wykresie tylko szacunki, które się zmieniły.**

- W Szacowaniu → Korekty szacunków histogram „Zmiana od startu do zakończenia” pokazuje teraz tylko elementy, których szacunek zmienił się między startem a zakończeniem. Elementy z niezmienionym szacunkiem (zmiana 0 %) tworzyły jeden wysoki słupek, który przyćmiewał prawdziwe korekty.
- Liczba elementów z niezmienionym szacunkiem jest pod wykresem i na liście rozliczenia, więc populacja nadal się sumuje. Gdy żaden szacunek się nie zmienił, strona mówi to wprost zamiast rysować pusty wykres.
- Mediana, średnia i percentyle opisują teraz same korekty — tak samo w bloku epików i w podziale na osoby.`,
  },
  {
    version: "0.22.0",
    date: "2026-10-17",
    en: `**WIP counts tasks or epics, never both added together.**

- The **Count** control on Flow metrics → WIP now offers **Tasks** and **Epics** only. "Tasks and epics" is gone: an epic and its own tasks in one band counted the same work twice.
- An older link or bookmark that still asks for both opens on Tasks.
- Blocked time is unchanged and keeps its "Tasks and epics" option.`,
    pl: `**WIP liczy zadania albo epiki, nigdy obu naraz.**

- Kontrolka **Licz** w Metrykach przepływu → WIP oferuje teraz tylko **Zadania** i **Epiki**. Opcja „Zadania i epiki” zniknęła: epik i jego własne zadania w jednym paśmie liczyły tę samą pracę dwa razy.
- Starszy link albo zakładka, która nadal prosi o oba rodzaje, otwiera się na zadaniach.
- Czas zablokowania się nie zmienia i zachowuje opcję „Zadania i epiki”.`,
  },
  {
    version: "0.21.0",
    date: "2026-10-17",
    en: `**Aging WIP shows tasks or epics, one at a time, across the whole page.**

- A **Tasks | Epics** switch under the filters now drives the whole page: the age thresholds, the aging chart and the open-work table all show the chosen kind, since tasks and epics age on very different scales. It replaces the switch that sat on the chart alone.
- The choice is kept in the link, so a shared or bookmarked page opens on the same kind. Tasks is the default.
- When epics aren't available (a single person's view, or no open epics), the switch is hidden and the page shows tasks.`,
    pl: `**Wiek WIP pokazuje zadania albo epiki, jedne naraz, na całej stronie.**

- Przełącznik **Zadania | Epiki** pod filtrami steruje teraz całą stroną: progi wieku, wykres starzenia się i tabela otwartej pracy pokazują wybrany rodzaj, bo zadania i epiki starzeją się w zupełnie innej skali. Zastępuje przełącznik, który był tylko przy wykresie.
- Wybór zapisuje się w linku, więc udostępniona albo zapisana strona otwiera się na tym samym rodzaju. Domyślnie pokazujemy zadania.
- Gdy epiki nie są dostępne (widok jednej osoby albo brak otwartych epików), przełącznik się chowa, a strona pokazuje zadania.`,
  },
  {
    version: "0.20.0",
    date: "2026-10-17",
    en: `**Stacked bars by status, board column or person are easier to tell apart.**

- Bars split by status or board column (WIP), and by person (Commitments, Throughput, WIP by person and the per-person histograms), now use seven distinct colours — blue, violet, cyan, indigo, pink, gray and green-lime — instead of alternating blue and gray, so neighbouring segments never look alike.
- WIP by stage is unchanged: a stage keeps the colour that means it (teal done, red blocked, orange unmapped, purple waiting).
- Every colour stays readable in the light and dark themes, and people are still numbered in labels, legends and tables, so colour is never the only cue.`,
    pl: `**Słupki skumulowane według statusu, kolumny tablicy albo osoby łatwiej odróżnić.**

- Słupki dzielone według statusu albo kolumny tablicy (WIP) i według osoby (Zobowiązania, Przepustowość, WIP według osoby i histogramy dla poszczególnych osób) używają teraz siedmiu wyraźnie różnych kolorów — niebieskiego, fioletowego, cyjanowego, indygo, różowego, szarego i limonkowego — zamiast naprzemiennie niebieskiego i szarego, więc sąsiednie segmenty nigdy nie wyglądają podobnie.
- WIP według etapu się nie zmienia: etap zachowuje kolor, który go oznacza (morski — ukończone, czerwony — zablokowane, pomarańczowy — niezmapowane, purpurowy — oczekujące).
- Każdy kolor pozostaje czytelny w jasnym i ciemnym motywie, a osoby nadal mają numery w etykietach, legendach i tabelach, więc kolor nigdy nie jest jedyną wskazówką.`,
  },
  {
    version: "0.19.0",
    date: "2026-10-17",
    en: `**Task and Epic accuracy are more compact: each measure's explanation is now behind an info icon.**

- The accuracy table lists each measure with its value only, so the rest of the page moves up.
- To read what a measure means, point at the ⓘ icon beside its name, or reach it with Tab — the explanation opens in a tooltip.`,
    pl: `**Trafność szacowania zadań i epików jest bardziej zwarta: wyjaśnienie każdej miary kryje się teraz pod ikoną informacji.**

- Tabela trafności pokazuje przy każdej mierze tylko jej wartość, więc reszta strony przesuwa się wyżej.
- Aby przeczytać, co znaczy miara, wskaż ikonę ⓘ obok jej nazwy albo dojdź do niej klawiszem Tab — wyjaśnienie otwiera się w podpowiedzi.`,
  },
  {
    version: "0.18.0",
    date: "2026-10-16",
    en: `**Epic progress reads a domain, team or the unit over the period, with a weekly SPI and CPI trend.**

- **This period / Since start**: for a domain, a team or the whole unit the page now opens on the period — the planned, earned and actual-cost curves start near 0 at the period's first day and show only what was planned, earned and spent in it; SPI and CPI are the period's own. A single epic still opens on its whole life. Switch either way; the tiles show the other view as a small second line.
- **Thin periods hide their indices**: when less than 1 man-day was planned (for SPI) or spent (for CPI) in the period, the index is shown as a dash with the reason instead of an absurd ratio.
- **A weekly trend**: a new chart of SPI and CPI per week, each over the 28 days up to that week's end, with a reference line at 1.0 and the numbers in a table beside it — the line that shows a team improving.
- At team level the foreign-work share now matches the CPI shown, and the drill-down rows follow the chosen view.`,
    pl: `**Postęp epików czyta domenę, zespół albo całą jednostkę w okresie, z tygodniowym trendem SPI i CPI.**

- **W tym okresie / Od początku**: dla domeny, zespołu albo całej jednostki strona otwiera się teraz na okresie — krzywe planu, wartości wypracowanej i kosztu rzeczywistego zaczynają się blisko 0 w pierwszym dniu okresu i pokazują tylko to, co w nim zaplanowano, wypracowano i wydano; SPI i CPI są wskaźnikami samego okresu. Pojedynczy epik nadal otwiera się na całym swoim życiu. Możesz przełączać w obie strony; kafelki pokazują drugi widok jako mniejszą linię.
- **Cienkie okresy ukrywają wskaźniki**: gdy w okresie zaplanowano (dla SPI) albo wydano (dla CPI) mniej niż 1 osobodzień, zamiast absurdalnego ilorazu pokazujemy kreskę z wyjaśnieniem.
- **Tygodniowy trend**: nowy wykres SPI i CPI tydzień po tygodniu, każdy z 28 dni do końca danego tygodnia, z linią odniesienia na 1,0 i liczbami w tabeli obok — to linia, która pokazuje, że zespół się poprawia.
- Na poziomie zespołu udział pracy obcej odpowiada teraz pokazanemu CPI, a wiersze szczegółów podążają za wybranym widokiem.`,
  },
  {
    version: "0.17.0",
    date: "2026-10-16",
    en: `**Blocked is now a stage of its own, instead of a checkbox on a status.**

- On **Metrics configuration → Statuses**, the Blocked checkbox is gone. A status that means work is held up by an impediment (blocked, on hold, waiting for a customer) is mapped to the new **Blocked** stage, globally or per domain, like any other stage. **Waiting** stays for queues (waiting for review, test, deploy). Jira's Flagged marker still marks an item as blocked whatever its status.
- **WIP** gets a sixth band, **Blocked**, in red and visible by default, so the chart separates queues from impediments. Work in progress is In progress + Waiting + Blocked.
- **Aging WIP** lists blocked items with the Blocked badge (and a ring on the aging chart), never as waiting. **Blocked time** counts all Blocked-stage time inside the cycle, together with Flagged spans. A Blocked status starts an item, like Waiting; its time counts as waiting, not active, and the Deep dive does not count it as execution.
- Any status ticked Blocked before is converted automatically.`,
    pl: `**Zablokowane to teraz osobny etap, a nie pole wyboru przy statusie.**

- W **Konfiguracja metryk → Statusy** nie ma już pola Zablokowane. Status oznaczający, że pracę wstrzymuje przeszkoda (zablokowane, wstrzymane, czeka na klienta), mapujesz na nowy etap **Zablokowany** — globalnie albo dla domeny, jak każdy inny etap. **Oczekujące** zostaje dla kolejek (czeka na przegląd, test, wdrożenie). Znacznik Flagged z Jiry nadal oznacza pozycję jako zablokowaną niezależnie od statusu.
- **WIP** dostaje szóste pasmo, **Zablokowane**, na czerwono i widoczne od początku, więc wykres oddziela kolejki od przeszkód. Praca w toku to W toku + Oczekujące + Zablokowane.
- **Wiek WIP** pokazuje zablokowane pozycje ze znaczkiem Zablokowane (i obwódką na wykresie starzenia), nigdy jako oczekujące. **Czas zablokowania** liczy cały czas w etapie Zablokowane w trakcie cyklu, razem z okresami Flagged. Status Zablokowane rozpoczyna pozycję, jak Oczekujące; jego czas liczy się jako oczekiwanie, a nie praca, a Analiza szczegółowa nie liczy go jako realizacji.
- Statusy, przy których wcześniej zaznaczono pole Zablokowane, zostają przekształcone automatycznie.`,
  },
  {
    version: "0.16.0",
    date: "2026-10-16",
    en: `**An estimate entered after a task was done is a backfill, not an adjustment.**

- An estimate change now counts as an adjustment only while the item is being worked on: after it started and up to the moment it was done. An estimate that first appears after done is a backfill: for start → done measures the item stays unestimated, and the backfill is shown separately. Real data had a one-off bulk fill that set estimates on thousands of long-finished tasks; it dominated Estimation → Adjustments, which now shows the real re-estimation during the work.
- **Adjustments** shows how many of the unestimated items got their estimate only after done. Its figures for epics now follow the same rule as tasks.
- **Data quality** has two new findings: items estimated late (no estimate at start, one at done) and items estimated after done, each with its list.
- When a task's estimate is the sum of its sub-tasks, adding a sub-task no longer counts as an estimate change on its own.
- Accuracy, throughput and the plan figures do not move: they read the estimate at start, at done or at commitment, which a backfill never had. The metrics recalculate once after the update.`,
    pl: `**Szacunek wpisany po zakończeniu zadania to uzupełnienie, a nie korekta.**

- Zmiana szacunku liczy się teraz jako korekta tylko wtedy, gdy nad pozycją trwa praca: po jej rozpoczęciu i do chwili zakończenia. Szacunek, który pojawia się dopiero po zakończeniu, to uzupełnienie: w miarach od startu do zakończenia pozycja zostaje bez szacunku, a uzupełnienie pokazujemy osobno. W prawdziwych danych jednorazowe masowe uzupełnienie nadało szacunki tysiącom dawno zakończonych zadań; dominowało ono w Szacowanie → Korekty, które teraz pokazują rzeczywiste zmiany szacunków w trakcie pracy.
- **Korekty** pokazują, ile pozycji bez szacunku dostało go dopiero po zakończeniu. Liczby dla epików stosują teraz tę samą regułę co zadania.
- **Jakość danych** ma dwa nowe wyniki: pozycje oszacowane z opóźnieniem (bez szacunku na starcie, z szacunkiem przy zakończeniu) i pozycje oszacowane po zakończeniu, każdy z listą.
- Gdy szacunek zadania jest sumą jego podzadań, samo dodanie podzadania nie liczy się już jako zmiana szacunku.
- Trafność, przepustowość i wartości planu się nie zmieniają: czytają szacunek na starcie, przy zakończeniu albo w chwili zobowiązania, którego uzupełnienie nigdy nie miało. Metryki przeliczają się raz po aktualizacji.`,
  },
  {
    version: "0.15.0",
    date: "2026-10-16",
    en: `**Aging WIP gets the aging chart: every open item by where it is in the workflow and how old it is.**

- A new chart above the open-work table puts one dot per item: across, where the item sits in the workflow; up, its age in working days. The team's cycle-time percentiles (p50, p85, p95) are dashed lines with their values, so an item high up and far left — old and still early in the workflow — stands out.
- With a team whose board is mapped, the workflow axis is the board's columns in board order; otherwise it is the statuses, in-progress ones before waiting ones.
- Dots take the table's colours (red above the top percentile, orange above the next); a hollow dot is waiting, a ringed one blocked; the tooltip names all three. Click a dot to find its row in the table. Tasks and epics have separate charts, each with its own percentiles.
- The report now says each item's status and stage too, and the outliers are never cut off: the age axis reaches the oldest item.
- **Log scale**: a switch puts the age axis on a log scale, so a few very old items no longer squash the rest. Ages under half a day sit on the 0.5 line; the tooltip and the table keep the true age. Your choice is remembered.`,
    pl: `**Wiek WIP dostaje wykres starzenia: każda otwarta pozycja według miejsca w przepływie pracy i wieku.**

- Nowy wykres nad tabelą otwartej pracy pokazuje jedną kropkę na pozycję: w poziomie miejsce pozycji w przepływie pracy, w pionie jej wiek w dniach roboczych. Percentyle czasu cyklu zespołu (p50, p85, p95) są liniami przerywanymi z wartościami, więc pozycja wysoko i daleko z lewej — stara i wciąż na początku przepływu — od razu się wyróżnia.
- Dla zespołu ze zmapowaną tablicą oś przepływu to kolumny tablicy w jej kolejności; w przeciwnym razie to statusy, najpierw te w toku, potem oczekujące.
- Kropki mają kolory z tabeli (czerwony powyżej najwyższego percentyla, pomarańczowy powyżej kolejnego); pusta w środku kropka oznacza oczekiwanie, kropka z obwódką blokadę; podpowiedź nazywa wszystkie trzy. Kliknij kropkę, aby znaleźć jej wiersz w tabeli. Zadania i epiki mają osobne wykresy, każdy z własnymi percentylami.
- Raport podaje teraz też status i etap każdej pozycji, a wartości odstające nigdy nie są obcinane: oś wieku sięga najstarszej pozycji.
- **Skala logarytmiczna**: przełącznik ustawia oś wieku w skali logarytmicznej, więc kilka bardzo starych pozycji nie spłaszcza już reszty. Wiek poniżej pół dnia leży na linii 0,5; podpowiedź i tabela pokazują prawdziwy wiek. Twój wybór zostaje zapamiętany.`,
  },
  {
    version: "0.14.0",
    date: "2026-10-16",
    en: `**WIP can be read for up to eight people, day by day.**

- On the WIP page, pick a team and then up to eight people: the chart shows their tasks per stage at the end of every day, from what Flow already derives — no extra data is stored.
- With two or more people a "By band / By person" switch shows how the work splits between them; the summary and daily tables add a column per person.
- A person's tasks count inside the chosen team, as on the other per-person reports: an open task while it belongs to the team that day, a finished one if the team got the credit for it. Epics have no assignee, so the epic view stays empty for people and "both" counts their tasks, each with a note.`,
    pl: `**WIP można czytać dla maksymalnie ośmiu osób, dzień po dniu.**

- Na stronie WIP wybierz zespół, a potem maksymalnie osiem osób: wykres pokazuje ich zadania według etapów na koniec każdego dnia, wyliczone z tego, co Flow już przelicza — bez zapisywania dodatkowych danych.
- Przy dwóch lub więcej osobach przełącznik „Czytaj wykres według: Pasm / Osób” pokazuje, jak praca się między nie rozkłada; tabela podsumowania i tabela dzienna dostają kolumnę dla każdej osoby.
- Zadania osoby liczymy w obrębie wybranego zespołu, jak w innych raportach dla osób: otwarte zadanie, gdy danego dnia należy do zespołu, a zakończone, jeśli to zespołowi zaliczono jego dostarczenie. Epiki nie mają osoby przypisanej, więc widok epików pozostaje dla osób pusty, a „oba” liczy ich zadania — za każdym razem z notką.`,
  },
  {
    version: "0.13.1",
    date: "2026-10-16",
    en: `**A burst of configuration edits recalculates the metrics once, not once per edit.**

- Each change to the metrics settings, capacity, team memberships, public holidays or a data source's metrics configuration used to start a full recalculation of every data source at once. Five quick capacity edits meant five recalculations each. A change now waits 45 seconds for further edits, so a burst becomes one recalculation, about 45 seconds after the last edit.
- While it waits, the data source's job list says "Waiting for further changes" with the time it will start, instead of looking stuck.
- Recalculations after a sync and ones you start by hand still begin at once.`,
    pl: `**Seria zmian w konfiguracji przelicza metryki raz, a nie po każdej zmianie.**

- Każda zmiana ustawień metryk, pojemności, członkostwa w zespołach, świąt państwowych albo konfiguracji metryk źródła danych od razu uruchamiała pełne przeliczanie wszystkich źródeł danych. Pięć szybkich zmian pojemności oznaczało pięć przeliczeń każdego z nich. Teraz zmiana czeka 45 sekund na kolejne, więc seria kończy się jednym przeliczeniem, około 45 sekund po ostatniej zmianie.
- W tym czasie lista zadań źródła danych pokazuje „Czeka na kolejne zmiany” wraz z godziną startu, zamiast wyglądać na zawieszoną.
- Przeliczanie po synchronizacji i to uruchomione ręcznie nadal startuje od razu.`,
  },
  {
    version: "0.13.0",
    date: "2026-10-16",
    en: `**WIP's Done band counts each finished item for the team and domain that delivered it.**

- A finished item now stays, on every day after it was done, with the team credited with delivering it (the sprint's team at done, else the assignee's team) and the domain it belonged to at done. Before, the Done band followed the team on each later day, so a task finished in a sprint that has since closed fell to "Unassigned".
- The whole-unit Done totals and the other four bands (Not started, In progress, Waiting, Unmapped status) are unchanged; only the split of Done by team and domain moves.
- Behind the scenes Flow no longer stores a row for every finished item on every later day; the Done band is computed when you open the report. On real data that is over a hundred megabytes less in the database and a much shorter recalculation.`,
    pl: `**Pasmo Zakończone w WIP liczy każdą zakończoną pozycję dla zespołu i domeny, które ją dostarczyły.**

- Zakończona pozycja zostaje teraz, każdego dnia po zakończeniu, przy zespole, któremu zaliczono jej dostarczenie (zespół sprintu w chwili zakończenia, a w przeciwnym razie zespół osoby przypisanej), i przy domenie, do której należała w chwili zakończenia. Wcześniej pasmo Zakończone podążało za zespołem w każdym kolejnym dniu, więc zadanie zakończone w sprincie, który już się zamknął, trafiało do „Nieprzypisane”.
- Sumy Zakończonych dla całej jednostki i pozostałe cztery pasma (Nierozpoczęte, W toku, Oczekujące, Status bez przypisanego etapu) się nie zmieniają; przesuwa się tylko podział Zakończonych według zespołów i domen.
- Flow nie zapisuje już wiersza dla każdej zakończonej pozycji w każdym kolejnym dniu; pasmo Zakończone liczymy w chwili otwarcia raportu. Przy prawdziwych danych to ponad sto megabajtów mniej w bazie i dużo krótsze przeliczanie.`,
  },
  {
    version: "0.12.0",
    date: "2026-10-16",
    en: `**WIP is read for one team or one domain, and only from the days the data is complete.**

- **WIP needs a specific team or a specific domain**: the page no longer offers "All teams". A link without either opens on the sprint's team, your last team, or the first team by name. Picking a domain stands in for the team; clearing it returns to your team.
- **No days before the backfill window**: before a data source's backfill date Flow only knows the issues that changed after it, so those days' counts were too low. WIP now starts at that date and says so in a note. When the team or domain spans several data sources, it starts at the latest of their backfill dates, so every day shown counts all of them.`,
    pl: `**WIP pokazujemy dla jednego zespołu albo jednej domeny i tylko od dni, dla których dane są kompletne.**

- **WIP wymaga konkretnego zespołu albo konkretnej domeny**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez żadnego z nich otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy. Wybrana domena zastępuje zespół; po jej wyczyszczeniu wracasz do swojego zespołu.
- **Bez dni sprzed okna synchronizacji**: przed datą początkową synchronizacji źródła danych Flow zna tylko zgłoszenia zmienione po niej, więc liczby z tamtych dni były zaniżone. WIP zaczyna się teraz od tej daty i mówi o tym w notce. Gdy zespół lub domena obejmuje kilka źródeł danych, zaczyna się od najpóźniejszej z ich dat, więc każdy pokazany dzień liczy je wszystkie.`,
  },
  {
    version: "0.11.3",
    date: "2026-10-16",
    en: `**Jira change history and worklogs take about a third of the space they did.**

- Every change-history entry and worklog Jira returns embeds a full copy of its author: avatar links, email address, time zone. Flow now keeps only the account id, the display name and the account type there; the full user is stored once, in the users list. On real data this is about 30% of the database.
- It applies to what Flow syncs from now on. Data synced earlier keeps the full copies until the installation is set up afresh.
- The raw issue inspector shows the shorter author accordingly. No figure in any report changes: Flow only ever read the account id.`,
    pl: `**Historia zmian i rejestry pracy z Jiry zajmują około jednej trzeciej dotychczasowego miejsca.**

- Każdy wpis historii zmian i każdy rejestr pracy zwracany przez Jirę zawiera pełną kopię autora: linki do awatarów, adres e-mail, strefę czasową. Flow zachowuje w nich teraz tylko identyfikator konta, nazwę wyświetlaną i typ konta; pełne dane osoby są zapisane raz, na liście użytkowników. Przy prawdziwych danych to około 30% bazy danych.
- Dotyczy to tego, co Flow synchronizuje od teraz. Dane zsynchronizowane wcześniej zachowują pełne kopie do czasu ponownej konfiguracji instalacji od zera.
- Podgląd surowych danych zgłoszenia pokazuje odpowiednio krótszego autora. Żadna liczba w raportach się nie zmienia: Flow zawsze odczytywał tylko identyfikator konta.`,
  },
  {
    version: "0.11.2",
    date: "2026-10-16",
    en: `**Saving capacity, a team or a data source no longer waits for the metrics to finish recalculating.**

- A save on the Capacity page (the team default or a sprint calendar), a team edit or delete, adding a Jira membership and a data-source edit used to wait until a running recalculation of the metrics finished. With real data that could take minutes, so the save failed after 30 seconds even though it landed later. They now save at once.
- Two edits of the same team or data source still take turns, so nothing is lost or overwritten.`,
    pl: `**Zapis pojemności, zespołu lub źródła danych nie czeka już na zakończenie przeliczania metryk.**

- Zapis na stronie Pojemność (domyślna pojemność zespołu albo kalendarz sprintu), edycja lub usunięcie zespołu, dodanie członkostwa w Jirze i edycja źródła danych czekały, aż skończy się trwające przeliczanie metryk. Przy prawdziwych danych mogło to trwać kilka minut, więc zapis kończył się błędem po 30 sekundach, choć później i tak się zapisywał. Teraz zapisują się od razu.
- Dwie edycje tego samego zespołu albo źródła danych nadal wykonują się po kolei, więc nic nie ginie ani nie zostaje nadpisane.`,
  },
  {
    version: "0.11.1",
    date: "2026-10-16",
    en: `**A momentary DNS failure no longer fails a whole Jira sync.**

- If looking up the Jira host's address fails for a moment, Flow now retries the request with the usual back-off instead of stopping the sync. Before, one lost DNS answer during a long sync failed it with "BLOCKED_HOST", as if the security guard had refused the host.
- "BLOCKED_HOST" now means only what it says: a host that is not allowed, or an address in a blocked range. A DNS failure that outlasts the retries is reported as "UPSTREAM_UNAVAILABLE", like any other Jira outage.
- Test connection with a mistyped site name now answers after about 10 seconds (the site row reads TIMEOUT) instead of at once.`,
    pl: `**Chwilowy błąd DNS nie przerywa już całej synchronizacji z Jirą.**

- Gdy wyszukanie adresu hosta Jiry na chwilę się nie powiedzie, Flow ponawia żądanie ze zwykłym odstępem zamiast przerywać synchronizację. Wcześniej jedna zgubiona odpowiedź DNS podczas długiej synchronizacji kończyła ją błędem „BLOCKED_HOST”, tak jakby zabezpieczenie odrzuciło hosta.
- „BLOCKED_HOST” oznacza teraz tylko to, co mówi: niedozwolony host albo adres z zablokowanego zakresu. Błąd DNS, który trwa dłużej niż ponowienia, zgłaszamy jako „UPSTREAM_UNAVAILABLE”, jak każdą inną niedostępność Jiry.
- Test połączenia z błędnie wpisaną nazwą witryny odpowiada teraz po około 10 sekundach (wiersz witryny pokazuje TIMEOUT), a nie od razu.`,
  },
  {
    version: "0.11.0",
    date: "2026-10-16",
    en: `**Epic estimation accuracy is read per team and gains nine accuracy measures.**

- **Epic accuracy needs a specific team**: the page no longer offers "All teams". A link without a team opens on the team the sprint belongs to, your last team, or the first team by name. The team is the epic's owner team.
- **No Member filter**: an epic belongs to a team, not to a person, so the control is gone and a member in a pasted link is dropped from the address.
- **p90 and p95 are gone from this page**: each view (against the own estimate at start and at done) now shows the median, the mean and the item count; the accuracy measures below say how wide and how heavy-tailed the misses are.
- **The same nine accuracy measures as Task accuracy, each with a one-line meaning**: PRED(10), PRED(25) and PRED(50) (the share of epics whose own estimate missed by no more than 10%, 25% or 50% of the actual cost), MdMRE (the median relative error, against the actual), sMAPE (the symmetric mean percentage error), IQR and IDR (how wide the middle half and the middle 80% of the actual ÷ own estimate ratios spread), MAD (the median absolute deviation from the median ratio) and the tail share beyond ±2σ (a normal distribution has about 4.6% there; shown as — with fewer than six epics or when every ratio is equal).
- **The "By team" table is gone**: a specific team is always chosen, so there is nothing to split by owner team.
- **Epics without an owner team are not shown here**: with a team required, the "No owner team" group can no longer be opened from this report.`,
    pl: `**Trafność szacowania epików liczymy dla zespołu i zyskuje ona dziewięć miar trafności.**

- **Trafność epików wymaga konkretnego zespołu**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez zespołu otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy. Zespół to zespół będący właścicielem epiku.
- **Bez filtra Osoba**: epik należy do zespołu, a nie do osoby, więc kontrolka zniknęła, a osoba z wklejonego linku jest usuwana z adresu.
- **Na tej stronie nie ma już p90 ani p95**: każdy widok (względem własnego szacunku na starcie i przy zakończeniu) pokazuje medianę, średnią i liczbę pozycji; miary trafności poniżej mówią, jak szerokie i jak „ciężkoogonowe” są pomyłki.
- **Te same dziewięć miar trafności co w Trafności zadań, każda z jednozdaniowym objaśnieniem**: PRED(10), PRED(25) i PRED(50) (udział epików, w których własny szacunek pomylił się o najwyżej 10%, 25% lub 50% kosztu rzeczywistego), MdMRE (mediana błędu względnego, liczonego względem kosztu rzeczywistego), sMAPE (symetryczny średni błąd procentowy), IQR i IDR (jak szeroko rozkłada się środkowa połowa i środkowe 80% ilorazów rzeczywisty ÷ własny szacunek), MAD (mediana bezwzględnych odchyleń od mediany ilorazu) oraz udział ogona poza ±2σ (rozkład normalny ma tam około 4,6%; pokazujemy —, gdy epików jest mniej niż sześć albo wszystkie ilorazy są równe).
- **Tabeli „Według zespołów” już nie ma**: zawsze wybieramy konkretny zespół, więc nie ma czego dzielić według zespołu będącego właścicielem.
- **Epików bez zespołu będącego właścicielem tu nie widać**: skoro zespół jest wymagany, grupy „Brak zespołu właściciela” nie otworzymy już z tego raportu.`,
  },
  {
    version: "0.10.0",
    date: "2026-10-16",
    en: `**Task estimation accuracy is read per team, stacks per person, and gains nine accuracy measures.**

- **Task accuracy needs a specific team**: the page no longer offers "All teams". A link without a team opens on the team the sprint belongs to, your last team, or the first team by name.
- **Pick up to eight people at once**: the Member filter takes several people. Both histograms (against the estimate at start and at done) then stack one segment per person, numbered "1. Name", "2. Name" in the legend, the tooltip and the tables, with a column per person and a new "Totals per person" table. The median, the mean, the accuracy measures and the "left out of this distribution" lists stay over everyone picked together.
- **p90 and p95 are gone from this page**: each view now shows the median, the mean and the item count; the accuracy measures below say how wide and how heavy-tailed the misses are.
- **Nine accuracy measures, each with a one-line meaning**: PRED(10), PRED(25) and PRED(50) (the share of tasks whose estimate missed by no more than 10%, 25% or 50% of the actual cost), MdMRE (the median relative error, against the actual), sMAPE (the symmetric mean percentage error), IQR and IDR (how wide the middle half and the middle 80% of the actual ÷ estimate ratios spread), MAD (the median absolute deviation from the median ratio) and the tail share beyond ±2σ (a normal distribution has about 4.6% there).`,
    pl: `**Trafność szacowania zadań liczymy dla zespołu, układa się w stos według osób i zyskuje dziewięć miar trafności.**

- **Trafność zadań wymaga konkretnego zespołu**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez zespołu otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy.
- **Wybierz do ośmiu osób naraz**: filtr Osoba przyjmuje kilka osób. Oba histogramy (względem szacunku na starcie i przy zakończeniu) układają wtedy po jednym segmencie na osobę, numerowanym „1. Imię”, „2. Imię” w legendzie, podpowiedzi i tabelach, z kolumną na osobę i nową tabelą „Sumy według osób”. Mediana, średnia, miary trafności i listy „pominięte w tym rozkładzie” pozostają policzone dla wszystkich wybranych osób razem.
- **Na tej stronie nie ma już p90 ani p95**: każdy widok pokazuje medianę, średnią i liczbę pozycji; miary trafności poniżej mówią, jak szerokie i jak „ciężkoogonowe” są pomyłki.
- **Dziewięć miar trafności, każda z jednozdaniowym objaśnieniem**: PRED(10), PRED(25) i PRED(50) (udział zadań, w których szacunek pomylił się o najwyżej 10%, 25% lub 50% kosztu rzeczywistego), MdMRE (mediana błędu względnego, liczonego względem kosztu rzeczywistego), sMAPE (symetryczny średni błąd procentowy), IQR i IDR (jak szeroko rozkłada się środkowa połowa i środkowe 80% ilorazów rzeczywisty ÷ szacunek), MAD (mediana bezwzględnych odchyleń od mediany ilorazu) oraz udział ogona poza ±2σ (rozkład normalny ma tam około 4,6%).`,
  },
  {
    version: "0.9.0",
    date: "2026-10-16",
    en: `**Estimate adjustments are read per team, and the change histogram stacks per person.**

- **Adjustments need a specific team**: the page no longer offers "All teams". A link without a team opens on the team the sprint belongs to, your last team, or the first team by name.
- **Pick up to eight people at once**: the Member filter takes several people. The tasks' change histogram then stacks one segment per person, numbered "1. Name", "2. Name" in the legend, the tooltip and the table, so a person is never told apart by colour alone. The percentiles, the mean and the "left out of this distribution" list stay over everyone picked together.
- **A column per person in the table**: the histogram table gains one column per person beside the total, and a new "Totals per person" table lists every picked person's own figures.
- **Epics stay a team figure**: epics have no assignee, so the epics block is not split per person.`,
    pl: `**Korekty szacunków liczymy dla zespołu, a histogram zmian układa się w stos według osób.**

- **Korekty wymagają konkretnego zespołu**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez zespołu otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy.
- **Wybierz do ośmiu osób naraz**: filtr Osoba przyjmuje kilka osób. Histogram zmian zadań układa wtedy po jednym segmencie na osobę, numerowanym „1. Imię”, „2. Imię” w legendzie, podpowiedzi i tabeli, więc osoby nie odróżniamy samym kolorem. Percentyle, średnia i lista „pominięte w tym rozkładzie” pozostają policzone dla wszystkich wybranych osób razem.
- **Kolumna na osobę w tabeli**: tabela histogramu zyskuje po jednej kolumnie na osobę obok sumy, a nowa tabela „Sumy według osób” podaje własne wartości każdej wybranej osoby.
- **Epiki pozostają wielkością zespołu**: epiki nie mają przypisanej osoby, więc blok epików nie jest dzielony według osób.`,
  },
  {
    version: "0.8.0",
    date: "2026-10-16",
    en: `**Cycle time is read per team, and its histograms stack per person.**

- **Cycle time needs a specific team**: the page no longer offers "All teams". A link without a team opens on the team the sprint belongs to, your last team, or the first team by name.
- **Pick up to eight people at once**: the Member filter takes several people. Both histograms (working days and elapsed days) then stack one segment per person, numbered "1. Name", "2. Name" in the legend, the tooltip and the tables, so a person is never told apart by colour alone. The percentiles, the mean and the "left out of this distribution" lists stay over everyone picked together.
- **A column per person in the tables**: each histogram table gains one column per person beside the total, and a new "Totals per person" table lists every picked person's own figures.
- **The trend line stays the whole selection**: the per-period median and p90 are still over everyone picked together, not one line per person.`,
    pl: `**Czas cyklu liczymy dla zespołu, a jego histogramy układają się w stos według osób.**

- **Czas cyklu wymaga konkretnego zespołu**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez zespołu otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy.
- **Wybierz do ośmiu osób naraz**: filtr Osoba przyjmuje kilka osób. Oba histogramy (dni robocze i dni kalendarzowe) układają wtedy po jednym segmencie na osobę, numerowanym „1. Imię”, „2. Imię” w legendzie, podpowiedzi i tabelach, więc osoby nie odróżniamy samym kolorem. Percentyle, średnia i listy „pominięte w tym rozkładzie” pozostają policzone dla wszystkich wybranych osób razem.
- **Kolumna na osobę w tabelach**: tabela każdego histogramu zyskuje po jednej kolumnie na osobę obok sumy, a nowa tabela „Sumy według osób” podaje własne wartości każdej wybranej osoby.
- **Linia trendu pozostaje dla całego wyboru**: mediana i p90 w każdym okresie są nadal policzone dla wszystkich wybranych osób razem, bez osobnej linii na osobę.`,
  },
  {
    version: "0.7.0",
    date: "2026-10-16",
    en: `**Reported time is read per team, and its histograms stack per person.**

- **Reported time needs a specific team**: the page no longer offers "All teams". A link without a team opens on the team the sprint belongs to, your last team, or the first team by name.
- **Pick up to eight people at once**: the Member filter takes several people. Both histograms (reported time ÷ cycle time and flow efficiency) then stack one segment per person, numbered "1. Name", "2. Name" in the legend, the tooltip and the tables, so a person is never told apart by colour alone. The percentiles, the mean and the "left out of this distribution" lists stay over everyone picked together.
- **A column per person in the tables**: each histogram table gains one column per person beside the total, and a new "Totals per person" table lists every picked person's own figures.`,
    pl: `**Zalogowany czas liczymy dla zespołu, a jego histogramy układają się w stos według osób.**

- **Zalogowany czas wymaga konkretnego zespołu**: strona nie oferuje już opcji „Wszystkie zespoły”. Link bez zespołu otwiera się na zespole sprintu, ostatnio wybranym zespole albo pierwszym zespole według nazwy.
- **Wybierz do ośmiu osób naraz**: filtr Osoba przyjmuje kilka osób. Oba histogramy (zalogowany czas ÷ czas cyklu oraz efektywność przepływu) układają wtedy po jednym segmencie na osobę, numerowanym „1. Imię”, „2. Imię” w legendzie, podpowiedzi i tabelach, więc osoby nie odróżniamy samym kolorem. Percentyle, średnia i listy „pominięte w tym rozkładzie” pozostają policzone dla wszystkich wybranych osób razem.
- **Kolumna na osobę w tabelach**: tabela każdego histogramu zyskuje po jednej kolumnie na osobę obok sumy, a nowa tabela „Sumy według osób” podaje własne wartości każdej wybranej osoby.`,
  },
  {
    version: "0.6.0",
    date: "2026-10-16",
    en: `**Sprint consistency: one team at a time, sliced by activity type or work category.**

- **Sprint consistency needs a specific team.** There is no "All teams" any more; a link without a team (such as the consistency tile on Home, which counts every team) opens on the remembered team, or the first team by name.
- **No member filter.** The report always covers every member of the chosen team; a member in an old link is ignored, and the per-member rows are no longer links.
- **New Activity type and Work category filters.** The sliced figures are read live from each sprint's scope, without the frozen snapshot, so a sliced view shows no drift badge.`,
    pl: `**Spójność sprintów: jeden zespół naraz, z podziałem według typu aktywności lub kategorii pracy.**

- **Spójność sprintów wymaga wskazanego zespołu.** Nie ma już opcji „Wszystkie zespoły”; link bez zespołu (np. z kafelka spójności na stronie głównej, który liczy wszystkie zespoły) otwiera się na zapamiętanym zespole albo pierwszym według nazwy.
- **Bez filtra członka/kini zespołu.** Raport zawsze obejmuje wszystkie osoby z wybranego zespołu; osoba z dawnego linku jest pomijana, a wiersze poszczególnych osób nie są już linkami.
- **Nowe filtry Typ aktywności i Kategoria pracy.** Wycięte liczby pochodzą na żywo z zakresu każdego sprintu, bez zamrożonego zrzutu, więc w widoku z filtrem nie ma znacznika dryfu.`,
  },
  {
    version: "0.5.0",
    date: "2026-10-16",
    en: `**Public holidays: one list of days off that every calendar and report respects.**

- **Public holidays** (a new page under Administration, readable by everyone) lists the days nobody works, one year at a time. Polish statutory holidays for 2017 to 2030 are pre-filled; administrators add and remove entries.
- **Holidays are always non-working days.** In a sprint's capacity calendar a holiday is shaded, named and cannot be switched on, even in a calendar saved earlier; the default capacity (members × working days) and the working days of every report leave holidays out too. Changing the list re-derives the metrics shortly after.
- **The holiday list moved off Metrics settings**: the Holidays field there is gone and points to the new page. Dates entered there earlier were carried over, named "Holiday".`,
    pl: `**Święta państwowe: jedna lista dni wolnych, którą uwzględniają wszystkie kalendarze i raporty.**

- **Święta państwowe** (nowa strona w sekcji Administracja, dostępna dla każdej osoby) pokazują dni, w których nikt nie pracuje, rok po roku. Polskie święta ustawowe z lat 2017–2030 są już wpisane; administratorzy/rki dodają i usuwają wpisy.
- **Święta są zawsze dniami wolnymi od pracy.** W kalendarzu pojemności sprintu święto jest zacieniowane, nazwane i nie da się go włączyć, także w kalendarzu zapisanym wcześniej; pojemność domyślna (liczba osób × dni robocze) i dni robocze w każdym raporcie również pomijają święta. Zmiana listy wkrótce uruchamia ponowne przeliczenie metryk.
- **Lista świąt zniknęła z Ustawień metryk**: pole Święta zostało usunięte i odsyła do nowej strony. Daty wpisane tam wcześniej zostały przeniesione pod nazwą „Holiday”.`,
  },
  {
    version: "0.4.0",
    date: "2026-10-06",
    en: `**Deep dive: plan, execution and cost in man-days for any set of work, task by task and day by day.**

- **Deep dive** (a new report page, open to every signed-in user) lays plan, execution and cost side by side for a chosen set of work. Select it in one of three ways: the tasks of a domain in the sprints you pick, whole epics, or handpicked tasks of one epic. The selection lives in the page address, so a link shares it.
- **Drill down** from months to weeks to days, and from epics to their tasks. Tooltips show who logged time on a day.
- **Burn-up tab** in the Deep dive: plan, earned value and cost rising over time on one chart, for the same selection, with an optional line for the epics' own budget plan.
- **Frozen figures per person**: the person level of Commitments, Throughput and Sprint consistency now shows each sprint's frozen figures, as they stood when the sprint closed, attributed to whoever held the task at commitment, and flags where they differ from the live ones.
- **Cycle time gains an epics block**: how long epics took from start to done, by owner team. It is not shown per person.
- **Per-domain stage mapping**: an administrator can map a status to a different stage for one domain (for example a team whose "In review" already counts as done); every other domain keeps the shared mapping.
- **Sprint totals add up**: each item's man-days are rounded before they are summed, so a team's total no longer differs by 0.01 from its people's figures.
- **Keyboard access**: every table that can scroll sideways is now reachable with the keyboard and announced with a name; report tabs are real links and heading levels follow the page structure.
- **Issues above the epic level**: an issue of a higher hierarchy level than epics (a Premium "Program" or "Initiative") is no longer counted as a task. It leaves the estimated backlog and will never enter work in progress, throughput, cycle time or aging; its logged time still counts in team cost and logged hours, under the issue's own domain, but never in an epic's or a domain's earned-value (EVM) figures. It applies after the next sync, or straight away with "Re-derive metrics" on the data source.
- **Throughput**: the page always shows one team, the person filter takes several people (stacked per person, with per-person tables), and a switch shows the charts as a cumulative running total instead of per period.
- **Capacity**: a new Capacity page under Administration plans each sprint person by person and day by day: availability from 0 to 1 MD per cell, people and days that can be turned off, a coefficient per person and one for the team, and a team default figure for sprints without a calendar (members × working days stays the fallback). Teams can have a manager, who edits the team's capacity alongside administrators; everyone else can read it. The per-sprint Capacities tab on the data source is gone, and each team's earlier figure became its team default.
- **Seed a team's Jira members from the Team field**: on a team's page, administrators can fill the Jira members from Jira's Team field. Pick an Atlassian team, tick the people assigned to its issues, and each is added as an open-ended member starting on the day of their earliest issue for that team. People who already belong to a team in that period are shown but cannot be selected.
- **Commitments**: The Velocity report is now called Commitments (old links still work): it shows what a team commits to each sprint, while velocity — what a team delivers per sprint — is the Delivered figure under Sprint consistency. Each sprint shows the team's capacity next to its initial and final scope, with a load column (committed ÷ capacity) and a note when the capacity is the default (members × working days) rather than configured. The page always shows one team, and the person filter takes several people, shown as stacked columns per person. **Every report's filters** can now be folded away; each report remembers whether they are open.
- **For operators**: adding a project or moving the backfill start earlier now downloads the missing issues in bulk instead of trickling them in through the reconcile. Deriving the metrics of a large tenant takes about half as long (a work-in-progress statement no longer looks up each task's sprint team day by day). A full sync of a large Jira project no longer runs out of memory while it profiles the data, and an issue whose history passes through a status since deleted in Jira is no longer skipped by processing. A worker that stopped mid-derivation no longer leaves a derivation stuck as running, unknown \`/api\` paths answer 404 instead of the app page, and the app waits for a database that is still starting. No new environment variables are required; \`POSTGRES_CONNECT_RETRIES\` (default 10) is optional.
- **Jira scopes**: Test connection now names the right API-token scopes (including \`read:workflow:jira\` for statuses), and the README lists every scope a full sync needs. The board checks in Test connection are now required, because a sync needs them.
- **Histograms**: the top 5% of values (adjustable from 0 to 25%) are shown as one final '≥ X' bucket, so a few outliers no longer squash the rest of the distribution into the first bar.`,
    pl: `**Analiza szczegółowa: plan, wykonanie i koszt w osobodniach dla dowolnego zbioru pracy, zadanie po zadaniu i dzień po dniu.**

- **Analiza szczegółowa** (nowa strona raportu, dostępna dla każdej zalogowanej osoby) zestawia obok siebie plan, wykonanie i koszt wybranego zbioru pracy. Wybierasz go na jeden z trzech sposobów: zadania domeny w wybranych sprintach, całe epiki albo wskazane zadania jednego epika. Wybór zapisuje się w adresie strony, więc link go udostępnia.
- **Drążenie** od miesięcy przez tygodnie do dni oraz od epików do ich zadań. Podpowiedzi pokazują, kto zalogował czas danego dnia.
- **Karta Burn-up** w analizie szczegółowej: plan, wartość wypracowana i koszt narastające w czasie na jednym wykresie, dla tego samego wyboru, z opcjonalną linią planu budżetu samych epików.
- **Zamrożone wartości na poziomie osoby**: poziom osoby w raportach Zobowiązania, Przepustowość i Spójność sprintów pokazuje teraz zamrożone wartości każdego sprintu, takie jak w chwili jego zamknięcia, przypisane osobie, która miała zadanie w chwili zobowiązania, i zaznacza, gdzie różnią się od bieżących.
- **Czas cyklu zyskuje blok epików**: ile trwały epiki od startu do ukończenia, według zespołu-właściciela. Nie jest pokazywany na poziomie osoby.
- **Mapowanie etapów dla domeny**: administrator/administratorka może przypisać status do innego etapu w jednej domenie (na przykład w zespole, w którym „In review” oznacza już ukończenie); pozostałe domeny korzystają ze wspólnego mapowania.
- **Sumy sprintów się zgadzają**: osobodni każdej pozycji są zaokrąglane przed zsumowaniem, więc suma zespołu nie różni się już o 0,01 od wartości jego członków.
- **Obsługa z klawiatury**: każda tabela, którą można przewijać w poziomie, jest osiągalna z klawiatury i ma nazwę dla czytników ekranu; karty raportów są prawdziwymi linkami, a poziomy nagłówków odzwierciedlają strukturę strony.
- **Zgłoszenia powyżej poziomu epika**: zgłoszenie na wyższym poziomie hierarchii niż epiki (w Premium „Program” lub „Initiative”) nie jest już liczone jako zadanie. Znika z oszacowanego backlogu i nigdy nie trafi do pracy w toku, przepustowości, czasu cyklu ani starzenia; zalogowany na nim czas nadal liczy się w kosztach zespołu i zalogowanych godzinach, w domenie samego zgłoszenia, ale nigdy w wartości wypracowanej (EVM) epika ani domeny. Zmiana zadziała po następnej synchronizacji albo od razu po użyciu „Ponowne przeliczenie metryk” przy źródle danych.
- **Przepustowość**: strona zawsze pokazuje jeden zespół, filtr osób przyjmuje kilka osób (słupki skumulowane per osoba, z tabelami per osoba), a przełącznik pokazuje wykresy jako narastającą sumę zamiast wartości per okres.
- **Pojemność**: nowa strona Pojemność w sekcji Administracja planuje każdy sprint osoba po osobie i dzień po dniu: dostępność od 0 do 1 MD w każdej komórce, osoby i dni, które możesz wyłączyć, współczynnik dla każdej osoby i jeden dla zespołu oraz domyślna wartość zespołu dla sprintów bez kalendarza (zapasowo nadal liczba osób × dni robocze). Zespół może mieć kierownika/czkę, który/a edytuje jego pojemność razem z administratorami; pozostali mogą ją przeglądać. Zakładka Pojemności na źródle danych zniknęła, a dotychczasowa wartość każdego zespołu stała się jego wartością domyślną.
- **Wypełnianie członków/kiń zespołu z pola Team**: na stronie zespołu administratorzy/rki mogą wypełnić członków/kinie Jira na podstawie pola Team w Jirze. Wybierz zespół Atlassian, zaznacz osoby przypisane do jego zadań, a każda z nich zostanie dodana jako członek/kini bez daty końca, od dnia swojego najwcześniejszego zadania tego zespołu. Osoby, które w tym okresie należą już do zespołu, są widoczne, ale nie można ich wybrać.
- **Zobowiązania**: Raport Velocity nazywa się teraz Zobowiązania (stare linki nadal działają): pokazuje, do czego zespół zobowiązuje się w każdym sprincie, a velocity — to, co zespół dostarcza w sprincie — to wartość Dostarczone w raporcie Spójność sprintów. Każdy sprint pokazuje pojemność zespołu obok zakresu początkowego i końcowego, z kolumną obciążenia (zobowiązanie ÷ pojemność) i uwagą, gdy pojemność jest domyślna (liczba osób × dni robocze), a nie skonfigurowana. Strona zawsze pokazuje jeden zespół, a filtr osób przyjmuje kilka osób, pokazanych jako słupki skumulowane per osoba. **Filtry każdego raportu** możesz teraz zwinąć; każdy raport pamięta, czy są otwarte.
- **Dla osób utrzymujących system**: dodanie projektu lub cofnięcie początku pobierania wstecz pobiera teraz brakujące zgłoszenia hurtowo, zamiast dociągać je pojedynczo podczas uzgadniania. Wyliczanie metryk dużej organizacji trwa mniej więcej o połowę krócej (zapytanie o pracę w toku nie szuka już zespołu sprintu każdego zadania dzień po dniu). Pełna synchronizacja dużego projektu Jira nie wyczerpuje już pamięci podczas profilowania danych, a zgłoszenie, którego historia przechodzi przez status usunięty później w Jira, nie jest już pomijane podczas przetwarzania. Worker zatrzymany w trakcie wyliczania nie zostawia już wyliczenia na zawsze w stanie „w toku”, nieznane ścieżki \`/api\` zwracają 404 zamiast strony aplikacji, a aplikacja czeka na bazę danych, która jeszcze się uruchamia. Nowe zmienne środowiskowe nie są wymagane; \`POSTGRES_CONNECT_RETRIES\` (domyślnie 10) jest opcjonalna.
- **Zakresy Jiry**: Test połączenia podaje teraz właściwe zakresy tokenu API, w tym \`read:workflow:jira\` dla statusów, a README wymienia wszystkie zakresy potrzebne do pełnej synchronizacji. Sprawdzenia tablic w Teście połączenia są teraz wymagane, bo synchronizacja ich potrzebuje.
- **Histogramy**: górne 5% wartości (do ustawienia od 0 do 25%) widać jako jeden ostatni przedział „≥ X”, więc kilka odstających wartości nie ściska już reszty rozkładu w pierwszym słupku.`,
  },
  {
    version: "0.3.0",
    date: "2026-09-29",
    en: `**Flow metrics: administrators configure how work is measured, and every signed-in user gets sixteen reports built on it.**

- **Metrics configuration** (Administration): the working calendar and time zone, hours per day and the sample-size thresholds; per connection, the status-to-stage, project-to-domain and board-to-team maps and the estimate, epic-date and work-category fields; and each Jira user's team membership over time.
- Flow **derives the metrics after every sync and every configuration change**, from the data it already stored — a change never waits for a new Jira call. Every report shows when it was last derived and the current configuration revision.
- **Sixteen reports on fifteen pages** (the backlog in sprints sits on the estimated-backlog page), open to every signed-in user, from the whole unit down to a team or a person. Period-based reports take any calendar period or the last N sprints:
  - **Delivery**: velocity, throughput, sprint consistency (carry-over, added scope) and cycle time;
  - **Estimation**: task accuracy, epic accuracy, estimate adjustments and reported time against cycle time;
  - **Flow metrics**: WIP, the estimated backlog, aging WIP, blocked time and epic progress (planned value, earned value and actual cost, PV/EV/AC, in man-days);
  - **Data quality**: where the numbers rest on missing or late data;
  - **Cost matrix**: man-days logged per team and domain, with the share of foreign work.
- The **Home** page is now the unit overview.
- **For operators**: the first deploy of this version reprocesses the whole tenant and then runs the first derivation — a few minutes on the worker, once. No new environment variables.`,
    pl: `**Metryki przepływu: administrator/ka konfiguruje sposób mierzenia pracy, a każda zalogowana osoba dostaje szesnaście raportów zbudowanych na tej konfiguracji.**

- **Konfiguracja metryk** (Administracja): kalendarz pracy i strefa czasowa, liczba godzin w dniu oraz progi minimalnej próby; dla każdego połączenia — mapowania statusów na etapy, projektów na domeny i tablic na zespoły oraz pola szacunku, dat epików i kategorii pracy; a także przynależność każdej osoby z Jiry do zespołu w czasie.
- Flow **wylicza metryki po każdej synchronizacji i każdej zmianie konfiguracji**, z danych, które już zapisał — zmiana nie czeka na nowe zapytanie do Jiry. Każdy raport pokazuje, kiedy dane wyliczono po raz ostatni, oraz bieżącą wersję konfiguracji.
- **Szesnaście raportów na piętnastu stronach** (backlog w sprintach jest na stronie oszacowanego backlogu), dostępnych dla każdej zalogowanej osoby, od całej jednostki po zespół lub pojedynczą osobę. Raporty okresowe przyjmują dowolny okres kalendarzowy lub ostatnie N sprintów:
  - **Dostarczanie**: velocity, przepustowość, spójność sprintów (przeniesienia, dodany zakres) i czas cyklu;
  - **Szacowanie**: trafność zadań, trafność epików, korekty szacunków oraz zalogowany czas ÷ czas cyklu;
  - **Metryki przepływu**: WIP, oszacowany backlog, wiek WIP, czas zablokowania i postęp epików (wartość planowana, wartość zrealizowana i rzeczywisty koszt, PV/EV/AC, w osobodniach);
  - **Jakość danych**: gdzie liczby opierają się na brakujących lub spóźnionych danych;
  - **Macierz kosztów**: osobodni zalogowane na zespół i domenę wraz z udziałem pracy spoza zespołu.
- Strona **główna** jest teraz przeglądem jednostki.
- **Dla osób utrzymujących system**: pierwsze wdrożenie tej wersji przetwarza ponownie całą dzierżawę, a następnie uruchamia pierwsze wyliczenie metryk — kilka minut na workerze, jednorazowo. Bez nowych zmiennych środowiskowych.`,
  },
  {
    version: "0.2.0",
    date: "2026-09-27",
    en: `**Jira Cloud ingestion: administrators connect Flow to Jira and it keeps a local, incremental copy of the data.**

- **Data sources** (Administration): add a Jira Cloud connection with a service account and a scoped, read-only API token (stored encrypted), and check every required permission with **Test connection** before saving.
- Flow syncs on a schedule and on demand: issues, changelogs, worklogs (for the chosen projects only), boards and sprints. A daily reconcile catches deleted issues and issues moved out of scope.
- A connection's page shows the running job live, its history, and **Reconcile now**, **Reprocess** and **Cancel running job**.
- The **Data profile** summarises what the connected Jira actually contains — workflows, board columns, custom fields, estimate and worklog coverage, reopens, sprints — ahead of the flow metrics to come.
- The **Raw issue inspector** looks up one issue and shows what Flow stored and how it read its status history.`,
    pl: `**Pobieranie danych z Jira Cloud: administrator/ka łączy Flow z Jirą, a Flow przechowuje lokalną, przyrostowo aktualizowaną kopię danych.**

- **Źródła danych** (Administracja): dodaj połączenie z Jira Cloud przez konto serwisowe i ograniczony token API tylko do odczytu (przechowywany w postaci zaszyfrowanej), a przed zapisaniem sprawdź wszystkie wymagane uprawnienia przyciskiem **Testuj połączenie**.
- Flow synchronizuje dane według harmonogramu i na żądanie: zgłoszenia, historię zmian, rejestry czasu pracy (tylko dla wybranych projektów), tablice i sprinty. Codzienne uzgadnianie wykrywa zgłoszenia usunięte i przeniesione poza zakres.
- Strona połączenia pokazuje na żywo trwające zadanie, jego historię oraz akcje **Uzgodnij teraz**, **Przetwórz ponownie** i **Anuluj trwające zadanie**.
- **Profil danych** podsumowuje, co faktycznie zawiera podłączona Jira — przepływy pracy, kolumny tablic, pola niestandardowe, pokrycie estymacjami i rejestrami czasu, ponowne otwarcia, sprinty — zanim pojawią się metryki przepływu.
- **Inspektor surowych zgłoszeń** wyszukuje pojedyncze zgłoszenie i pokazuje, co Flow zapisał i jak odczytał historię jego statusów.`,
  },
  {
    version: "0.1.0",
    date: "2026-09-26",
    en: `**First release: the Flow foundation — sign-in with email MFA, users, teams, feature flags, English and Polish, light and dark themes.**`,
    pl: `**Pierwsze wydanie: fundament Flow — logowanie z MFA e-mailem, użytkownicy/czki, zespoły, flagi funkcji, język angielski i polski, motyw jasny i ciemny.**`,
  },
];

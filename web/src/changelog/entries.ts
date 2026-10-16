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

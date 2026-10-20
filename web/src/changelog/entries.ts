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

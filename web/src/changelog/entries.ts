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
    version: "1.46.0",
    date: "2026-11-01",
    en: `**Code activity links merge requests to their Jira tasks.**

- In the lists behind the Code activity figures (and the code lists on Home), each merge request now shows the Jira issues its title or branch names, as links to Jira.
- Only keys of the Jira projects you have connected are shown. A key is shown even if Jira has no such issue, and then the link leads to Jira's "not found" page.`,
    pl: `**Aktywność w kodzie łączy merge requesty z zadaniami w Jirze.**

- W listach za wartościami raportu Aktywność w kodzie (oraz w listach kodu na stronie głównej) każdy merge request pokazuje teraz zadania z Jiry, które wskazuje jego tytuł lub gałąź, jako odnośniki do Jiry.
- Pokazywane są tylko klucze z podłączonych projektów Jiry. Klucz jest widoczny także wtedy, gdy w Jirze nie ma takiego zadania, a odnośnik prowadzi wtedy na stronę „nie znaleziono”.`,
  },
  {
    version: "1.45.0",
    date: "2026-11-01",
    en: `**The data profile counts merge requests that name a Jira task.**

- The data profile of a GitLab data source now shows how many merge requests mention a Jira issue key (such as \`FLO-123\`) in their title or branch name, and how many such keys were found.
- These are only candidates: they are not yet matched to Jira tasks, and a few will be look-alikes such as \`UTF-8\`. This is groundwork for linking code to tasks.`,
    pl: `**Profil danych zlicza merge requesty, które wskazują zadanie z Jiry.**

- Profil danych źródła GitLab pokazuje teraz, ile merge requestów wspomina klucz zadania Jiry (na przykład \`FLO-123\`) w tytule lub nazwie gałęzi oraz ile takich kluczy znaleziono.
- To tylko kandydaci: nie są jeszcze dopasowani do zadań w Jirze, a część to coś podobnego do klucza, na przykład \`UTF-8\`. To przygotowanie do powiązania kodu z zadaniami.`,
  },
  {
    version: "1.44.0",
    date: "2026-11-01",
    en: `**Home shows the code of each team.**

- A new **Code** section appears on Home while a GitLab data source is connected. For each team you picked it shows the merge requests merged in the last 90 days with their median cycle time, the merge requests open right now (drafts counted apart) with their median age, and how many were merged without a review — each beside the previous 90 days, with the same "Better", "Worse" and "No change" words as the rest of the page.
- The counts open the merge requests behind them, each key a link to GitLab. The figures are the ones the Code activity report shows for the same team and period; the section title opens that report.
- A team with no linked GitLab identity says so (an administrator gets the link to the Identities page), and a figure resting on too few merge requests says so instead of showing a share. When the code is too large to summarise, the section says so and the rest of Home loads as usual.`,
    pl: `**Strona główna pokazuje kod każdego zespołu.**

- Nowa sekcja **Kod** pojawia się na stronie głównej, gdy podłączone jest źródło danych GitLab. Dla każdego wybranego zespołu pokazuje merge requesty scalone w ostatnich 90 dniach z medianą czasu cyklu, merge requesty otwarte teraz (wersje robocze liczone osobno) z medianą wieku oraz liczbę scalonych bez przeglądu — każdą obok poprzednich 90 dni, z tymi samymi słowami „Lepiej”, „Gorzej” i „Bez zmian” co reszta strony.
- Liczby otwierają merge requesty, które za nimi stoją, a każdy klucz jest linkiem do GitLaba. Wartości są takie same jak w raporcie Aktywność w kodzie dla tego samego zespołu i okresu; tytuł sekcji otwiera ten raport.
- Zespół bez połączonej tożsamości GitLab mówi o tym wprost (administrator/ka dostaje link do strony Tożsamości), a wartość oparta na zbyt małej liczbie merge requestów to zaznacza zamiast pokazywać udział. Gdy kodu jest zbyt dużo, by go podsumować, sekcja mówi o tym, a reszta strony głównej ładuje się jak zwykle.`,
  },
  {
    version: "1.43.0",
    date: "2026-11-01",
    en: `**Code activity can show the work no team holds, and says why a selection was refused.**

- The Team control of the Code activity page has a new last choice, **Unassigned**: the work of GitLab identities that no administrator has linked to a person yet, and of linked people who were in no team when they did it. The members' table lists each unlinked identity by the name GitLab gives it, the flow cards and the lists behind the figures read the same bucket, and the choice stays in the address, so a reload or a shared link opens it again. Other reports are unchanged.
- A selection that is too large to read (a long period for the whole bucket, or for a big team) now says so — "narrow the period or pick fewer people" — instead of a bare "Load failed (400)", on the page and in the lists behind its figures. When the server is busy computing other Code activity reports, the page asks you to retry in a moment.`,
    pl: `**Aktywność w kodzie pokazuje pracę, której nie ma żaden zespół, i mówi, dlaczego odrzuciła wybór.**

- Kontrolka Zespół na stronie Aktywność w kodzie ma nowy, ostatni wybór: **Nieprzypisane**. To praca tożsamości z GitLaba, których żaden administrator nie połączył jeszcze z osobą, oraz połączonych osób, które w chwili pracy nie należały do żadnego zespołu. Tabela członków wypisuje każdą niepołączoną tożsamość pod nazwą, jaką nadaje jej GitLab, karty przepływu i listy za liczbami czytają ten sam zbiór, a wybór zostaje w adresie, więc odświeżenie strony lub udostępniony link otwiera go ponownie. Pozostałe raporty się nie zmieniają.
- Wybór zbyt duży, żeby go wczytać (długi okres dla całego zbioru albo dla dużego zespołu), mówi to teraz wprost — „zawęź okres lub wybierz mniej osób” — zamiast gołego „Wczytywanie nie powiodło się (400)”, na stronie i na listach za jej liczbami. Gdy serwer jest zajęty liczeniem innych raportów Aktywności w kodzie, strona prosi o ponowną próbę za chwilę.`,
  },
  {
    version: "1.42.2",
    date: "2026-11-01",
    en: `**Code activity can no longer exhaust the server's memory.**

- A very wide selection on the Code activity page (a long period for a whole team, or the unassigned work of a large source) is now refused with a clear message — "narrow the period or pick fewer people" — instead of loading every commit, merge request and review event it touches. A selection that fits answers exactly as before; the same limit applies to the lists behind the figures and to the code figures of Compare people and Person trend.
- When several Code activity reports are being computed at once, the extra ones wait a moment for their turn and, if the server stays busy, ask you to retry shortly.
- Administrators can tune both limits (\`REPORTS_CODE_MAX_ROWS\`, \`REPORTS_CODE_READ_PERMITS\`); the defaults suit a unit of about seventy developers.`,
    pl: `**Aktywność w kodzie nie przeciąży już pamięci serwera.**

- Bardzo szeroki wybór na stronie Aktywność w kodzie (długi okres dla całego zespołu albo nieprzypisana praca dużego źródła) jest teraz odrzucany z czytelnym komunikatem — „zawęź okres lub wybierz mniej osób” — zamiast wczytywać każdy commit, merge request i zdarzenie przeglądu, których dotyczy. Wybór, który się mieści, odpowiada dokładnie jak dotąd; ten sam limit obejmuje listy za liczbami oraz liczby z kodu w Porównaniu osób i Trendzie osoby.
- Gdy naraz liczy się kilka raportów Aktywności w kodzie, kolejne chwilę czekają na swoją kolej, a jeśli serwer pozostaje zajęty, proszą o ponowną próbę za moment.
- Administratorzy mogą dostroić oba limity (\`REPORTS_CODE_MAX_ROWS\`, \`REPORTS_CODE_READ_PERMITS\`); wartości domyślne pasują do jednostki liczącej około siedemdziesięciu programistów.`,
  },
  {
    version: "1.42.1",
    date: "2026-11-01",
    en: `**Code activity loads a team's review requests faster.**

- The review requests received by a team's members are now picked out by the database before they reach the report, instead of the report reading every review request of the period and discarding the ones for other teams. The figures, the lists behind them and the report's cache are unchanged; a team's report on a large source just answers sooner and uses less memory.
- It applies to the Code activity page and its lists; nothing to configure.`,
    pl: `**Aktywność w kodzie szybciej wczytuje prośby o przegląd zespołu.**

- Prośby o przegląd, które otrzymali członkowie zespołu, wybiera teraz baza danych, zanim trafią do raportu, zamiast czytać wszystkie prośby z okresu i odrzucać te dla innych zespołów. Liczby, listy za nimi i pamięć podręczna raportu pozostają bez zmian; raport zespołu na dużym źródle po prostu odpowiada szybciej i zużywa mniej pamięci.
- Dotyczy strony Aktywność w kodzie i jej list; nic nie trzeba konfigurować.`,
  },
  {
    version: "1.42.0",
    date: "2026-11-01",
    en: `**Compare two people's code, and follow a person's merge requests over time.**

- Compare people has a new "Code (GitLab)" group: each person's commits, lines changed, merge requests merged, the median cycle time and size of those merge requests, and the merge requests they reviewed and approved and their comments. It counts the GitLab identities an administrator linked to the person, in every team, and the filters do not apply to it; a person with no link shows zeros. Every figure opens the commits or merge requests behind it.
- Counts are plain context and are never marked as better or worse; only the median cycle time and size of a person's merge requests (lower is better) can name a side.
- Person trend gains three charts, one per window: merge requests merged, their median cycle time and merge requests reviewed, with the team's median beside them like the others. The two counts show their change over the range without a verdict.`,
    pl: `**Porównaj kod dwóch osób i śledź merge requesty osoby w czasie.**

- Porównanie osób ma nową grupę „Kod (GitLab)”: commity, zmienione linie, scalone merge requesty, mediana czasu cyklu i rozmiaru tych merge requestów oraz merge requesty, które osoba przejrzała i zatwierdziła, i jej komentarze. Liczy tożsamości GitLaba, które administrator połączył z osobą, we wszystkich zespołach, a filtry jej nie dotyczą; osoba bez połączenia ma zera. Każda liczba otwiera commity lub merge requesty, które za nią stoją.
- Liczby są samym kontekstem i nigdy nie są oznaczane jako lepsze lub gorsze; stronę może wskazać tylko mediana czasu cyklu i rozmiaru merge requestów osoby (im mniej, tym lepiej).
- Trend osoby ma trzy nowe wykresy, po jednym punkcie na okno: scalone merge requesty, ich mediana czasu cyklu i przejrzane merge requesty, a obok nich mediana zespołu jak przy pozostałych. Dwie liczby pokazują zmianę w zakresie bez oceny.`,
  },
  {
    version: "1.41.0",
    date: "2026-11-01",
    en: `**See the commits and merge requests behind a Code activity figure.**

- On the Code activity page, double-click a bar of any histogram or of the merged-per-week chart, or click a figure — a tile, the open-now count or a count in the members' table — to list the merge requests or commits it counts. Each line shows the project, the number or commit, the title, who did it, when and the figure's own value; the key opens it in GitLab in a new tab.
- The list always adds up to the figure it came from, and the cell of one member lists only that member's items. The members' table stays plain context: the figures are underlined buttons and nothing more.`,
    pl: `**Zobacz commity i merge requesty stojące za liczbą w Aktywności w kodzie.**

- Na stronie Aktywność w kodzie kliknij dwukrotnie słupek dowolnego histogramu lub wykresu scalonych w tygodniu albo kliknij liczbę — kafel, liczbę otwartych teraz lub liczbę w tabeli osób — aby zobaczyć listę merge requestów lub commitów, które ona liczy. Każdy wiersz pokazuje projekt, numer lub commit, tytuł, kto to zrobił, kiedy i własną wartość liczby; klucz otwiera element w GitLabie w nowej karcie.
- Lista zawsze sumuje się do liczby, z której powstała, a komórka jednej osoby pokazuje tylko jej elementy. Tabela osób pozostaje samym tłem: liczby są podkreślonymi przyciskami i niczym więcej.`,
  },
  {
    version: "1.40.0",
    date: "2026-11-01",
    en: `**The Code activity page.**

- A new page under Reports, **Code activity**, shows how a team's merge requests flow through GitLab: how long they take from creation to merge, how long they wait for a first review, how big they are, how many are merged each week, how many were merged without a review or an approval, how many are open now and how review work is spread among reviewers. Pick the team, optionally up to eight members, and a period of dates or sprints.
- Beside the flow, a table lists each member's commits, lines, merge requests, reviews, approvals and comments. It is context, not a score: it has no ranking, no colours and no order of merit.
- People appear once an administrator has linked their GitLab identity to their Jira account on the data source's Identities page; the page tells you how many identities are still waiting for that, and what is not read yet.`,
    pl: `**Strona Aktywność w kodzie.**

- Nowa strona w Raportach, **Aktywność w kodzie**, pokazuje, jak merge requesty zespołu płyną przez GitLaba: ile trwają od utworzenia do scalenia, ile czekają na pierwszy przegląd, jak są duże, ile scalono co tydzień, ile scalono bez przeglądu lub zatwierdzenia, ile jest teraz otwartych i jak rozkłada się praca przeglądowa między osoby przeglądające. Wybierz zespół, opcjonalnie do ośmiu osób oraz okres z dat lub sprintów.
- Obok przepływu tabela podaje commity, linie, merge requesty, przeglądy, zatwierdzenia i komentarze poszczególnych osób. To tło, a nie ocena: bez rankingu, bez kolorów i bez kolejności zasług.
- Osoby pojawiają się, gdy administrator połączy ich tożsamość w GitLabie z kontem w Jirze na stronie Tożsamości źródła danych; strona mówi, ile tożsamości na to czeka i czego jeszcze nie odczytano.`,
  },
  {
    version: "1.39.0",
    date: "2026-11-01",
    en: `**The API for the coming Code activity report.**

- A new endpoint, \`GET /api/v1/reports/code-activity\`, reports a team's merge-request flow over the GitLab data: cycle time, time to first review, merge requests merged without review or approval, open merge requests waiting, size, the weekly merged count and review concentration. Next to them it lists each member's commits, merge requests and reviews as plain context, not a score.
- Code is attributed through the GitLab identity links an administrator confirmed, and to the team the person belonged to on the day of each commit, merge request or review. Identities still waiting for a decision are counted, so you can see how much is left unattributed; identities marked as bots or ignored count nowhere.
- There is no page for it yet: the Code activity page comes in the next release.`,
    pl: `**API nadchodzącego raportu Aktywność w kodzie.**

- Nowy punkt końcowy, \`GET /api/v1/reports/code-activity\`, raportuje przepływ merge requestów zespołu na podstawie danych z GitLaba: czas realizacji, czas do pierwszego przeglądu, merge requesty scalone bez przeglądu lub zatwierdzenia, otwarte merge requesty czekające na przegląd, rozmiar, tygodniową liczbę scalonych oraz koncentrację przeglądów. Obok podaje commity, merge requesty i przeglądy poszczególnych osób jako samo tło, a nie ocenę.
- Kod jest przypisywany przez powiązania tożsamości GitLab potwierdzone przez administratora oraz do zespołu, do którego osoba należała w dniu danego commita, merge requesta lub przeglądu. Tożsamości czekające jeszcze na decyzję są liczone, więc widać, ile pozostaje nieprzypisane; tożsamości oznaczone jako boty lub pomijane nie są liczone nigdzie.
- Strony dla tego raportu jeszcze nie ma: strona Aktywność w kodzie pojawi się w następnym wydaniu.`,
  },
  {
    version: "1.38.1",
    date: "2026-11-01",
    en: `**A second data source's metrics no longer wait on stale database statistics.**

- When a sync rewrites a large share of a source's normalized data (a first sync, a second source of a similar size, a reprocess), Flow now refreshes the database's planner statistics for those tables right after it. Before, the metrics calculation that followed could plan the new source's rows as if there were one and run about three times longer, depending on when the database's own maintenance happened to run.
- It applies to Jira and GitLab sources alike, costs a fraction of a second and is skipped for small incremental syncs.
- Nothing to configure; a source that is busy with another maintenance task simply keeps the statistics it had, and the sync still succeeds.`,
    pl: `**Metryki drugiego źródła danych nie czekają już na nieaktualne statystyki bazy.**

- Gdy synchronizacja przepisuje dużą część znormalizowanych danych źródła (pierwsza synchronizacja, drugie źródło o podobnej wielkości, ponowne przetworzenie), Flow odświeża zaraz potem statystyki planera bazy dla tych tabel. Wcześniej następujące po niej obliczanie metryk mogło zaplanować wiersze nowego źródła tak, jakby był jeden, i trwać około trzy razy dłużej, zależnie od tego, kiedy akurat uruchomiła się własna konserwacja bazy.
- Dotyczy źródeł Jira i GitLab, kosztuje ułamek sekundy i jest pomijane przy małych synchronizacjach przyrostowych.
- Nic nie trzeba konfigurować; źródło zajęte inną czynnością konserwacyjną zachowuje po prostu dotychczasowe statystyki, a synchronizacja i tak się udaje.`,
  },
  {
    version: "1.38.0",
    date: "2026-10-31",
    en: `**GitLab identities are linked to Jira people.**

- A GitLab source's new **Identities** page lists every GitLab user and commit address the sync found, proposes the matching Jira person by e-mail (a bot account is proposed as a bot), and lets an administrator confirm all proposals at once or decide row by row: link to a Jira person from the site directory, mark as bot, ignore, or remove a decision.
- Decisions belong to the GitLab instance, not to the connection: deleting and re-creating the source, or a purge, keeps them.
- Only confirmed links attribute code activity; the reports that read them come in a later release.`,
    pl: `**Tożsamości GitLab są powiązane z osobami z Jiry.**

- Nowa strona **Tożsamości** źródła GitLab wyświetla każdego użytkownika GitLab i adres commitu znaleziony podczas synchronizacji, proponuje pasującą osobę z Jiry po adresie e-mail (konto bota jest proponowane jako bot) i pozwala administratorowi potwierdzić wszystkie propozycje naraz albo decydować wiersz po wierszu: powiązać z osobą z katalogu witryny, oznaczyć jako bota, zignorować lub usunąć decyzję.
- Decyzje należą do instancji GitLab, nie do połączenia: usunięcie i ponowne utworzenie źródła albo czyszczenie danych je zachowuje.
- Tylko potwierdzone powiązania przypisują aktywność w kodzie; raporty, które je odczytują, pojawią się w późniejszym wydaniu.`,
  },
  {
    version: "1.37.0",
    date: "2026-10-31",
    en: `**GitLab: commits, merge requests and review activity become normalized facts.**

- Every sync now turns the stored GitLab data into the normalized code layer: commits with their author identity and their lines counted after the excluded paths, merge requests with their size and state, and review events (comments, diff comments, approvals, review requests, reviews, changes requested). Merge commits are kept but never counted; a commit whose file details are still pending stays "unknown" rather than undercounted.
- Identities are keys, not people yet: a GitLab user, or a commit e-mail that matches no user. Linking them to Jira people comes in the next release.
- A changed excluded-paths list is applied at the next sync, or at once with Reprocess, which is now offered for GitLab sources. The data profile is available for GitLab sources and summarizes projects, commits, merge requests, review events and identities.
- Deleting a GitLab source now purges its normalized rows with the raw ones.`,
    pl: `**GitLab: commity, merge requesty i aktywność przeglądowa stają się znormalizowanymi faktami.**

- Każda synchronizacja przetwarza teraz zapisane dane GitLab do znormalizowanej warstwy kodu: commity z tożsamością autora i liczbą linii policzoną po wyłączeniu wskazanych ścieżek, merge requesty z rozmiarem i stanem oraz zdarzenia przeglądu (komentarze, komentarze do zmian, zatwierdzenia, prośby o przegląd, przeglądy, prośby o zmiany). Commity merge są zachowywane, ale nigdy nie liczone; commit, którego szczegóły plików jeszcze nie dotarły, pozostaje „nieznany” zamiast zaniżony.
- Tożsamości to na razie klucze, nie osoby: użytkownik GitLab albo adres e-mail commitu, który nie pasuje do żadnego użytkownika. Powiązanie ich z osobami z Jiry przyjdzie w następnym wydaniu.
- Zmieniona lista wykluczonych ścieżek jest stosowana przy następnej synchronizacji albo od razu przez „Przetwórz ponownie”, dostępne teraz także dla źródeł GitLab. Profil danych jest dostępny dla źródeł GitLab i podsumowuje projekty, commity, merge requesty, zdarzenia przeglądu i tożsamości.
- Usunięcie źródła GitLab czyści teraz jego znormalizowane wiersze razem z surowymi.`,
  },
  {
    version: "1.36.1",
    date: "2026-10-31",
    en: `**GitLab: a daily reconcile catches what the incremental syncs cannot.**

- Once a day, and on "Reconcile now" (now offered for GitLab sources too), Flow re-lists every project's default branch and every group's merge requests from the backfill date. A commit or merge request that is no longer listed is confirmed before it is marked deleted - a commit against the branch, a merge request against its project - so a listing hiccup never loses data. The size statistics GitLab did not answer are asked again, and a few merge requests whose details could not be read are read again.
- A project GitLab no longer answers for is marked deleted; a moved or archived project keeps its data.
- The data source details show two more counts: merge requests marked deleted and merge requests whose details could not be read.`,
    pl: `**GitLab: codzienne uzgadnianie wychwytuje to, czego nie widzą synchronizacje przyrostowe.**

- Raz dziennie oraz po „Uzgodnij teraz” (dostępnym teraz także dla źródeł GitLab) Flow ponownie listuje domyślną gałąź każdego projektu i merge requesty każdej grupy od daty początkowej. Commit lub merge request, którego nie ma już na liście, jest potwierdzany, zanim zostanie oznaczony jako usunięty - commit względem gałęzi, merge request względem projektu - więc chwilowy błąd listowania nigdy nie usuwa danych. Statystyki rozmiaru, których GitLab nie podał, są pobierane ponownie, a kilka merge requestów, których szczegółów nie udało się odczytać, jest odczytywanych jeszcze raz.
- Projekt, o którym GitLab już nie odpowiada, jest oznaczany jako usunięty; przeniesiony lub zarchiwizowany zachowuje dane.
- Szczegóły źródła danych pokazują dwie nowe liczby: merge requesty oznaczone jako usunięte i merge requesty, których szczegółów nie udało się odczytać.`,
  },
  {
    version: "1.36.0",
    date: "2026-10-31",
    en: `**GitLab: merge requests and review activity are read.**

- A sync now reads the merge requests of the configured groups (state, times, branches, title, head, merge and squash commits, reviewers and assignees) and, for each one, its notes and system events as action codes (approved, changes requested, review requested, merged, …), its approvals and reviewer states, and its size (lines added and deleted, files changed) from one batched GraphQL query. Note bodies and descriptions are never stored.
- Later syncs read only the merge requests updated since the last run; a merge request's details are refreshed when it changes, newest first, under a per-run budget.
- The data source details show the merge-request counts: total, merged, review events, those still awaiting details and those without size statistics.`,
    pl: `**GitLab: odczyt merge requestów i aktywności review.**

- Synchronizacja odczytuje teraz merge requesty skonfigurowanych grup (stan, czasy, gałęzie, tytuł, commity head, merge i squash, recenzentów i przypisanych), a dla każdego z nich notatki i zdarzenia systemowe jako kody akcji (zatwierdzono, poproszono o zmiany, poproszono o review, scalono, …), zatwierdzenia i stany recenzentów oraz rozmiar (dodane i usunięte linie, zmienione pliki) z jednego zbiorczego zapytania GraphQL. Treści notatek i opisy nigdy nie są zapisywane.
- Kolejne synchronizacje odczytują tylko merge requesty zmienione od ostatniego przebiegu; szczegóły merge requesta są odświeżane, gdy się zmieni, od najnowszych, w ramach budżetu na przebieg.
- Szczegóły źródła danych pokazują liczby merge requestów: łącznie, scalone, zdarzenia review, czekające jeszcze na szczegóły i bez statystyk rozmiaru.`,
  },
  {
    version: "1.35.0",
    date: "2026-10-31",
    en: `**GitLab: commits are read, with per-file line counts.**

- A sync now reads each project's default-branch commits (within the backfill window), then the files each commit changed with their added and deleted lines; merge commits are flagged and left out of the counts. Later syncs read only what is new, and a force-pushed branch is listed again so the commits that vanished are marked.
- A GitLab data source has an "Excluded paths" list (glob patterns, prefilled with lock files, minified bundles, vendored and generated directories) that the upcoming code-activity reports will leave out of the line counts.
- The data source details show the commit counts and how many commits still wait for their file details.`,
    pl: `**GitLab: odczyt commitów z liczbą linii na plik.**

- Synchronizacja odczytuje teraz commity domyślnej gałęzi każdego projektu (w oknie historii), a potem pliki zmienione w każdym commicie z liczbą dodanych i usuniętych linii; commity scalające są oznaczane i pomijane w zliczeniach. Kolejne synchronizacje odczytują tylko nowe commity, a gałąź po force-pushu jest odczytywana ponownie, by oznaczyć commity, które zniknęły.
- Źródło danych GitLab ma listę „Wykluczone ścieżki” (wzorce glob, wstępnie wypełnioną plikami lock, zminifikowanymi paczkami oraz katalogami vendor i generowanymi), których nadchodzące raporty aktywności w kodzie nie uwzględnią w liczbie linii.
- Szczegóły źródła danych pokazują liczbę commitów i to, ile commitów czeka jeszcze na szczegóły plików.`,
  },
  {
    version: "1.34.0",
    date: "2026-10-31",
    en: `**Data sources: a GitLab instance can be connected.**

- A data source now has a kind: Jira Cloud, or a self-managed GitLab instance (its address, a read-only access token and the groups to read; subgroups are included, archived projects are not).
- Test connection checks the instance version, the token's owner and scopes (a token that could write is refused) and each group; a sync stores the groups' projects and the instance's users. Commits and merge requests follow in a later version.
- The data source list and details show each source's kind; the Jira-only pages (data profile, raw issue inspector, metrics configuration) stay with Jira sources.`,
    pl: `**Źródła danych: można podłączyć instancję GitLab.**

- Źródło danych ma teraz rodzaj: Jira Cloud albo własna instancja GitLab (jej adres, token dostępu tylko do odczytu i grupy do odczytania; podgrupy są uwzględniane, zarchiwizowane projekty nie).
- Test połączenia sprawdza wersję instancji, właściciela/właścicielkę tokenu i jego zakresy (token, który mógłby zapisywać, jest odrzucany) oraz każdą grupę; synchronizacja zapisuje projekty grup i użytkowników/użytkowniczki instancji. Commity i merge requesty pojawią się w kolejnej wersji.
- Lista i szczegóły źródeł danych pokazują rodzaj każdego źródła; strony tylko dla Jiry (profil danych, inspektor surowych zgłoszeń, konfiguracja metryk) pozostają przy źródłach Jira.`,
  },
  {
    version: "1.33.0",
    date: "2026-10-30",
    en: `**Epic progress: an epic that changed domain is split between its domains.**

- In a domain's view, an epic that moved between domains is listed under each domain it was in, with only that domain's share of the plan, earned value and cost. Such a row is marked "part".
- So the epic rows of a domain now add up to the domain's totals. The epic view still shows the whole epic.`,
    pl: `**Postęp epików: epik, który zmienił domenę, jest dzielony między swoje domeny.**

- W widoku domeny epik przeniesiony między domenami jest pokazany w każdej domenie, w której był, tylko z udziałem tej domeny w planie, wartości wypracowanej i koszcie. Taki wiersz ma oznaczenie „część”.
- Dzięki temu wiersze epików domeny sumują się teraz do łącznych wartości domeny. Widok epika nadal pokazuje cały epik.`,
  },
  {
    version: "1.32.0",
    date: "2026-10-30",
    en: `**Epics that changed project are counted where they were.**

- A finished epic now belongs to the domain it was in when it was finished, like its tasks. Epic accuracy, Cycle time, Blocked time, estimate adjustments and data quality follow that rule.
- Work in progress counts an epic under the domain it was in on each day, and the Done band uses the domain at the moment it was finished. Open epics are unchanged.`,
    pl: `**Epiki, które zmieniły projekt, liczą się tam, gdzie były.**

- Zakończony epik należy teraz do domeny, w której był w chwili zakończenia, tak jak jego zadania. Trafność epików, Czas cyklu, Czas zablokowania, zmiany estymacji i jakość danych stosują tę zasadę.
- Praca w toku liczy epik w domenie, w której był danego dnia, a pasmo Zakończone używa domeny z chwili zakończenia. Otwarte epiki się nie zmieniają.`,
  },
  {
    version: "1.31.0",
    date: "2026-10-30",
    en: `**Reports: work since the history horizon.**

- Every report now shows only work since the history horizon set in the metrics settings. A period that starts before it is shortened to the horizon, and a period that lies entirely before it is refused.
- The period pickers and presets start at the horizon, and each report says since when it counts. The deep dive, Compare people and Person trend follow the same rule, with a note when the horizon cut the range.
- Nothing is deleted: raising the horizon or turning it off brings older work back.`,
    pl: `**Raporty: praca od horyzontu historii.**

- Każdy raport pokazuje teraz tylko pracę od horyzontu historii ustawionego w ustawieniach metryk. Okres zaczynający się wcześniej jest skracany do horyzontu, a okres w całości sprzed niego jest odrzucany.
- Wybór okresu i gotowe zakresy zaczynają się od horyzontu, a każdy raport mówi, od kiedy liczy. Analiza szczegółowa, Porównanie osób i Trend osoby stosują tę samą zasadę, z notatką, gdy horyzont przyciął zakres.
- Nic nie jest usuwane: wydłużenie horyzontu albo jego wyłączenie przywraca starszą pracę.`,
  },
  {
    version: "1.30.0",
    date: "2026-10-30",
    en: `**Data sources: the history horizon is also the earliest backfill date.**

- When a history horizon is set in the metrics settings, a data source can no longer be set to fetch Jira history from before it. A blank date still means 24 months back, or the horizon's first day when that is later.
- An existing data source keeps its saved date; only moving the date earlier than the horizon is refused. Syncs that are already running are not affected.`,
    pl: `**Źródła danych: horyzont historii jest też najwcześniejszą datą pobierania.**

- Gdy w ustawieniach metryk jest ustawiony horyzont historii, źródła danych nie można już ustawić tak, by pobierało historię z Jiry sprzed niego. Puste pole nadal oznacza 24 miesiące wstecz albo pierwszy dzień horyzontu, jeśli jest późniejszy.
- Istniejące źródło danych zachowuje zapisaną datę; odrzucane jest tylko przesunięcie daty przed horyzont. Trwające synchronizacje nie są zmieniane.`,
  },
  {
    version: "1.29.0",
    date: "2026-10-30",
    en: `**Metrics settings: a history horizon.**

- Administrators can now set how far back the reports look: 24 months by default, anything from 6 to 120 months, or no limit. The settings page shows the first day that still counts.
- This version only stores the setting; the reports start to follow it in the next versions. Nothing is ever deleted: raising the horizon or turning it off brings older work back.`,
    pl: `**Ustawienia metryk: horyzont historii.**

- Administratorzy mogą teraz ustawić, jak daleko wstecz sięgają raporty: domyślnie 24 miesiące, dowolnie od 6 do 120 miesięcy albo bez limitu. Strona ustawień pokazuje pierwszy dzień, który jeszcze się liczy.
- Ta wersja tylko zapisuje ustawienie; raporty zaczną je uwzględniać w kolejnych wersjach. Nic nie jest usuwane: wydłużenie horyzontu lub jego wyłączenie przywraca starszą pracę.`,
  },
  {
    version: "1.28.0",
    date: "2026-10-30",
    en: `**Reports: work stays with the domain it was done in when an issue or epic moves between your projects.**

- When a task or an epic moves from one of your projects to another, work done and tasks finished before the move now count for the domain they belonged to at the time, in Costs, Throughput, Epic progress and the other domain views. Before, everything followed the item to its new project.
- Moves recorded in Jira's history were partly missed until now; they are read correctly from this version on. The first sync after the update re-reads every stored issue, so it takes longer than usual.
- Moves from projects this data source does not read keep counting for the item's current domain, so older history is not lost from your domains. A move between two projects of the same data source is the case that changes: the earlier work stays with the domain it had.
- An epic's own list entry still appears under its current domain, so in Epic progress a moved epic's row can differ from its domain totals.`,
    pl: `**Raporty: praca zostaje przy domenie, w której ją wykonano, gdy zgłoszenie lub epik przechodzi między Twoimi projektami.**

- Gdy zadanie lub epik przechodzi z jednego z Twoich projektów do innego, praca wykonana i zadania zakończone przed przeniesieniem liczą się teraz dla domeny, do której wtedy należały — w kosztach, przepustowości, postępie epików i pozostałych widokach domen. Wcześniej wszystko przechodziło razem z elementem do nowego projektu.
- Przeniesienia zapisane w historii Jiry były dotąd częściowo pomijane; od tej wersji są odczytywane poprawnie. Pierwsza synchronizacja po aktualizacji ponownie przetwarza wszystkie zapisane zgłoszenia, więc potrwa dłużej niż zwykle.
- Przeniesienia z projektów, których to źródło danych nie odczytuje, nadal liczą się dla bieżącej domeny elementu, więc starsza historia nie znika z Twoich domen. Zmienia się dopiero przeniesienie między dwoma projektami tego samego źródła danych: wcześniejsza praca zostaje przy domenie, którą wtedy miała.
- Sam epik nadal jest wymieniony pod swoją bieżącą domeną, więc w postępie epików wiersz przeniesionego epiku może różnić się od sum jego domeny.`,
  },
  {
    version: "1.27.3",
    date: "2026-10-29",
    en: `**Data sources: memory from a finished sync is released.**

- The server no longer keeps the data of a finished sync in memory after the sync ends. Before, a database connection opened during a large sync could hold that sync's whole batch of Jira data for up to ten minutes.`,
    pl: `**Źródła danych: pamięć po zakończonej synchronizacji jest zwalniana.**

- Serwer nie przechowuje już w pamięci danych synchronizacji po jej zakończeniu. Wcześniej połączenie z bazą danych otwarte podczas dużej synchronizacji mogło przez nawet dziesięć minut trzymać całą porcję danych tej synchronizacji z Jiry.`,
  },
  {
    version: "1.27.2",
    date: "2026-10-29",
    en: `**Data sources: tighter memory limits for very large Jira data.**

- Inspecting a stored issue and testing a connection now take turns for the memory they need, so several large inspections and a connection test at once can no longer exhaust the server's memory. An inspection may wait up to about half a second behind a connection test.
- An issue too large to inspect safely (its stored text and custom fields together) is now refused with the usual "too large" answer instead of risking the server.
- Syncing issues with tens of thousands of worklogs uses about a quarter less memory, and the server's start-up memory check now counts that work too.`,
    pl: `**Źródła danych: ściślejsze limity pamięci dla bardzo dużych danych z Jiry.**

- Podgląd zapisanego zgłoszenia i test połączenia korzystają teraz z pamięci po kolei, więc kilka dużych podglądów naraz z testem połączenia nie może już wyczerpać pamięci serwera. Podgląd może poczekać do około pół sekundy na zakończenie testu połączenia.
- Zgłoszenie zbyt duże, by bezpiecznie je podejrzeć (jego zapisany tekst razem z polami niestandardowymi), jest teraz odrzucane zwykłą odpowiedzią „za duże”, zamiast narażać serwer.
- Synchronizacja zgłoszeń z dziesiątkami tysięcy wpisów czasu pracy zużywa około jednej czwartej mniej pamięci, a sprawdzenie pamięci przy starcie serwera uwzględnia teraz także tę pracę.`,
  },
  {
    version: "1.27.1",
    date: "2026-10-29",
    en: `**Data sources: syncs are gentler on the database and recover faster from a database restart.**

- Very large batches of stored Jira data (for example an issue with thousands of long worklogs) are now written in several smaller steps, so one sync can no longer make the database run out of memory.
- If the database restarts while a sync is running, the sync is now marked as failed within seconds once the database is back, instead of showing "running" for about five minutes; a sync that had already stored everything is not marked as failed.`,
    pl: `**Źródła danych: synchronizacje mniej obciążają bazę danych i szybciej wracają po jej restarcie.**

- Bardzo duże porcje zapisywanych danych z Jiry (na przykład zgłoszenie z tysiącami długich wpisów czasu pracy) są teraz zapisywane w kilku mniejszych krokach, więc jedna synchronizacja nie może już wyczerpać pamięci bazy danych.
- Jeśli baza danych zrestartuje się w trakcie synchronizacji, synchronizacja zostaje teraz oznaczona jako nieudana w ciągu kilku sekund od powrotu bazy, zamiast przez około pięć minut pokazywać „w toku”; synchronizacja, która zdążyła już wszystko zapisać, nie jest oznaczana jako nieudana.`,
  },
  {
    version: "1.27.0",
    date: "2026-10-29",
    en: `**Data sources: a reconcile that hits its limits every run now pauses on its own.**

- A scheduled daily reconcile that stops at a safety limit or the time limit several runs in a row now pauses by itself, instead of retrying forever; the number of runs is the same one that pauses scheduled syncs. Scheduled syncs keep running while it is paused.
- A data source with a paused reconcile shows a "Reconcile paused" badge in the list, on its page and in the Sync table of the telemetry panel; its page also explains since when, why and after how many runs. If syncs are paused too, both are shown side by side.
- "Reconcile now" or saving the connection resumes the reconcile. "Sync now" resumes only the syncs, and saving the connection resumes both.`,
    pl: `**Źródła danych: uzgodnienie, które w każdym przebiegu osiąga limity, wstrzymuje się samo.**

- Zaplanowane codzienne uzgodnienie, które kilka przebiegów z rzędu zatrzymuje się na limicie bezpieczeństwa lub limicie czasu, wstrzymuje się teraz samo, zamiast ponawiać próby bez końca; liczba przebiegów jest taka sama jak przy wstrzymywaniu zaplanowanych synchronizacji. Zaplanowane synchronizacje działają dalej, gdy uzgodnienie jest wstrzymane.
- Źródło danych z wstrzymanym uzgodnieniem ma plakietkę „Uzgodnienie wstrzymane” na liście, na swojej stronie i w tabeli Synchronizacja panelu telemetrii; jego strona wyjaśnia też, od kiedy, dlaczego i po ilu przebiegach. Jeśli wstrzymane są także synchronizacje, obie informacje są pokazane obok siebie.
- „Uzgodnij teraz” albo zapisanie połączenia wznawia uzgodnienie. „Synchronizuj teraz” wznawia tylko synchronizacje, a zapisanie połączenia wznawia oba.`,
  },
  {
    version: "1.26.3",
    date: "2026-10-29",
    en: `**Person trend: a teammate's trend opens faster.**

- Once anyone on a team has opened their Person trend, a teammate's trend for the same period and filters reuses the team comparison already worked out, so it opens several times faster (a 15-person team's year: about 0.5 s down to about 0.06 s in our measurements).
- The numbers are exactly the same; new data (a sync, a recalculation, a settings change) starts afresh.`,
    pl: `**Trend osoby: trend członka/członkini zespołu otwiera się szybciej.**

- Gdy ktoś z zespołu otworzył już swój Trend osoby, trend kolejnej osoby z zespołu za ten sam okres i z tymi samymi filtrami korzysta z wyliczonego już porównania z zespołem, więc otwiera się kilkukrotnie szybciej (rok 15-osobowego zespołu: w naszych pomiarach z ok. 0,5 s do ok. 0,06 s).
- Liczby są dokładnie takie same; nowe dane (synchronizacja, przeliczenie, zmiana ustawień) zaczynają od nowa.`,
  },
  {
    version: "1.26.2",
    date: "2026-10-29",
    en: `**Data sources: the one-off TRIM step is gone.**

- Every install has rewritten its older stored Jira rows to the current trim rules, so the sync no longer runs the TRIM step and Reprocess no longer mentions it. Stored data and every report are unchanged.
- Upgrading from a version older than 1.25.0: first run one sync on 1.25.0–1.26.1 and wait until the data source status shows the \`trim\` cursor at \`DONE\`, version 2.`,
    pl: `**Źródła danych: jednorazowy krok TRIM został usunięty.**

- Każda instalacja przepisała już starsze zapisane wiersze Jiry według bieżących reguł przycinania, więc synchronizacja nie uruchamia już kroku TRIM, a Przetwórz ponownie o nim nie wspomina. Zapisane dane i wszystkie raporty pozostają bez zmian.
- Aktualizacja z wersji starszej niż 1.25.0: najpierw uruchom jedną synchronizację na wersji 1.25.0–1.26.1 i poczekaj, aż status źródła danych pokaże kursor \`trim\` w stanie \`DONE\`, wersja 2.`,
  },
  {
    version: "1.26.1",
    date: "2026-10-29",
    en: `**Jira sync: a run that outlives its time budget is now stopped mid-page.**

- A sync or reconcile run was checked against its time budget (\`INGEST_MAX_JOB_MINUTES\`) only between pages, so a slow Jira call or a rate-limit wait could keep it running up to about five minutes past the budget. It is now cancelled once the budget plus a short grace (\`INGEST_HARD_STOP_GRACE_SECONDS\`, 60 seconds by default) has passed: the page in flight is rolled back, the run ends as a time-budget stop like before, and the next run resumes where it stopped.`,
    pl: `**Synchronizacja z Jirą: przebieg, który przekroczy limit czasu, jest teraz zatrzymywany w trakcie strony.**

- Przebieg synchronizacji lub uzgadniania był porównywany z limitem czasu (\`INGEST_MAX_JOB_MINUTES\`) tylko między stronami, więc wolne wywołanie Jiry albo oczekiwanie przy limicie zapytań mogło przedłużyć go nawet o około pięć minut ponad limit. Teraz jest przerywany, gdy minie limit plus krótki zapas (\`INGEST_HARD_STOP_GRACE_SECONDS\`, domyślnie 60 sekund): przetwarzana strona jest wycofywana, przebieg kończy się jak dotąd zatrzymaniem z powodu limitu czasu, a następny przebieg wznawia pracę od miejsca zatrzymania.`,
  },
  {
    version: "1.26.0",
    date: "2026-10-29",
    en: `**Reports: the API refuses a whole-unit read.**

- Every report request names a team (\`teamId\`) or a domain (\`domain\`); Commitments and Sprint consistency, which have no domain slice, need the team; Epic progress takes an epic, a domain or a team. A request with none is refused with \`400\` instead of reading every team's data.
- The pages already send a scope, so nothing changes on screen; scripts and other API clients must pass one. Compare people, Person trend, the Deep dive and Home are unaffected.`,
    pl: `**Raporty: API odmawia odczytu dla całej jednostki.**

- Każde żądanie raportu wskazuje zespół (\`teamId\`) lub domenę (\`domain\`); Zobowiązania i Spójność sprintów, które nie mają podziału na domeny, wymagają zespołu; Postęp epików przyjmuje epik, domenę lub zespół. Żądanie bez żadnego z nich jest odrzucane z kodem \`400\` zamiast czytać dane wszystkich zespołów.
- Strony już wysyłają zakres, więc na ekranie nic się nie zmienia; skrypty i inne klienty API muszą go podać. Porównanie osób, Trend osoby, Deep dive i strona główna pozostają bez zmian.`,
  },
  {
    version: "1.25.0",
    date: "2026-10-29",
    en: `**Jira sync: stored Jira issues keep a slimmer copy of their links, and drop sub-tasks and environment.**

- Each issue link now keeps only its id, its type (name, inward and outward wording) and the linked issue's id and key; the linked issue's summary, status, priority and type are no longer stored with it. The \`subtasks\` and \`environment\` fields are no longer stored or fetched. No report reads any of them, so no number changes.
- Issues stored before this version are rewritten once by the first sync after the upgrade (the TRIM step runs again; it calls no Jira endpoint). The dropped parts do not come back on their own: getting them back would take a code change, a purge and a full re-sync.`,
    pl: `**Synchronizacja z Jirą: zapisane zgłoszenia przechowują odchudzoną kopię powiązań i nie przechowują już podzadań ani środowiska.**

- Każde powiązanie zgłoszenia przechowuje teraz tylko swój identyfikator, typ (nazwę oraz opis kierunku w obie strony) oraz identyfikator i klucz powiązanego zgłoszenia; podsumowanie, status, priorytet i typ powiązanego zgłoszenia nie są już przy nim zapisywane. Pola \`subtasks\` i \`environment\` nie są już zapisywane ani pobierane. Żaden raport ich nie czyta, więc żadna liczba się nie zmienia.
- Zgłoszenia zapisane przed tą wersją zostaną raz przepisane przez pierwszą synchronizację po aktualizacji (krok TRIM uruchomi się ponownie; nie wywołuje on żadnego endpointu Jiry). Usuniętych części nie da się odzyskać samoczynnie: wymagałoby to zmiany w kodzie, wyczyszczenia danych i pełnej ponownej synchronizacji.`,
  },
  {
    version: "1.24.2",
    date: "2026-10-29",
    en: `**Jira sync: stored Jira documents now have size limits, and processing is cut into pages that fit in memory.**

- An issue or reference record over 2 MiB (\`JIRA_MAX_STORED_PAYLOAD_BYTES\`), a change history over 512 KiB or a worklog over 16 KiB is no longer stored. The sync logs a warning, counts it as skipped (\`issuesSkipped\`, \`entitiesSkipped\`, \`historiesSkipped\`, \`worklogsSkipped\`) and carries on; a version that was already stored is kept as it is. Real data is far below these limits.
- Processing now takes issues in pages cut by their stored size (\`JIRA_PROCESS_PAGE_BYTES\`, 8 MiB by default). One issue that alone is larger than that is left unprocessed with a warning (\`issuesOversized\`), and the raw issue inspector says it is too large to show instead of loading it.
- The startup memory check now counts this work too. The shipped settings still fit \`-Xmx512m\`; if you raised either new setting, raise the worker's \`JAVA_OPTS\` heap with it.`,
    pl: `**Synchronizacja z Jirą: zapisywane dokumenty Jiry mają teraz limity rozmiaru, a przetwarzanie jest dzielone na strony mieszczące się w pamięci.**

- Zgłoszenie lub rekord referencyjny powyżej 2 MiB (\`JIRA_MAX_STORED_PAYLOAD_BYTES\`), historia zmian powyżej 512 KiB albo wpis czasu pracy powyżej 16 KiB nie jest już zapisywany. Synchronizacja zapisuje ostrzeżenie w logu, liczy go jako pominięty (\`issuesSkipped\`, \`entitiesSkipped\`, \`historiesSkipped\`, \`worklogsSkipped\`) i działa dalej; wersja zapisana wcześniej zostaje bez zmian. Prawdziwe dane są znacznie poniżej tych limitów.
- Przetwarzanie pobiera teraz zgłoszenia stronami wyciętymi według ich zapisanego rozmiaru (\`JIRA_PROCESS_PAGE_BYTES\`, domyślnie 8 MiB). Pojedyncze zgłoszenie większe niż ten limit zostaje nieprzetworzone z ostrzeżeniem (\`issuesOversized\`), a inspektor surowych zgłoszeń informuje, że jest zbyt duże do wyświetlenia, zamiast je ładować.
- Kontrola pamięci przy starcie uwzględnia teraz także tę pracę. Domyślne ustawienia nadal mieszczą się w \`-Xmx512m\`; jeśli podniesiono któreś z dwóch nowych ustawień, trzeba odpowiednio zwiększyć stertę workera w \`JAVA_OPTS\`.`,
  },
  {
    version: "1.24.1",
    date: "2026-10-28",
    en: `**Person trend: the team line loads faster for large teams.**

- For a person in a large team, the trend with the team reference now needs fewer database reads and less work per row, so it opens sooner. The numbers are unchanged.`,
    pl: `**Trend osoby: linia zespołu ładuje się szybciej w dużych zespołach.**

- Dla osoby z dużego zespołu trend z linią odniesienia zespołu wymaga teraz mniej odczytów z bazy i mniej pracy na wiersz, więc otwiera się szybciej. Liczby się nie zmieniają.`,
  },
  {
    version: "1.24.0",
    date: "2026-10-28",
    en: `**Jira sync: the worker checks at startup that its memory can hold the configured Jira limits.**

- A worker (or the all-in-one setup) now refuses to start when its memory cannot hold the Jira response limits it is configured with (\`INGEST_WORKER_SLOTS\`, \`JIRA_MAX_RESPONSE_BYTES\`, \`JIRA_MAX_CONCURRENT_REQUESTS\`). The error names the \`-Xmx\` it needs. The shipped settings need nothing new: \`-Xmx512m\` is enough, and the web server only logs a warning.
- A stored Jira response used to be held in memory three times over while it was saved; it is now held once, so a very large or malformed answer from Jira is far less likely to take the worker down. The stored data does not change.
- If you raised one of those three settings, raise the worker's \`JAVA_OPTS\` heap with it (see the README).`,
    pl: `**Synchronizacja z Jirą: worker sprawdza przy starcie, czy jego pamięć pomieści skonfigurowane limity Jiry.**

- Worker (lub instalacja all-in-one) odmawia teraz uruchomienia, gdy jego pamięć nie pomieści skonfigurowanych limitów odpowiedzi Jiry (\`INGEST_WORKER_SLOTS\`, \`JIRA_MAX_RESPONSE_BYTES\`, \`JIRA_MAX_CONCURRENT_REQUESTS\`). Błąd podaje potrzebną wartość \`-Xmx\`. Domyślne ustawienia nie wymagają zmian: wystarcza \`-Xmx512m\`, a serwer webowy jedynie zapisuje ostrzeżenie w logu.
- Zapisywana odpowiedź z Jiry była do tej pory trzymana w pamięci trzykrotnie; teraz jest trzymana raz, więc bardzo duża lub uszkodzona odpowiedź Jiry znacznie rzadziej przewróci workera. Zapisane dane się nie zmieniają.
- Jeśli podniesiono któreś z tych trzech ustawień, trzeba odpowiednio zwiększyć stertę workera w \`JAVA_OPTS\` (zob. README).`,
  },
  {
    version: "1.23.1",
    date: "2026-10-28",
    en: `**Jira sync: a pause now also catches a run that only re-stores unchanged data.**

- A sync that stops at a limit after only re-saving data that did not change (the same pages, again and again) is now counted like one that stored nothing, so a data source stuck that way is paused instead of retrying for ever.
- A run stopped by the time limit now counts what every step of the run stored, not only the step it happened to stop in.`,
    pl: `**Synchronizacja z Jirą: wstrzymanie obejmuje teraz także przebieg, który tylko ponownie zapisuje niezmienione dane.**

- Synchronizacja, która zatrzymuje się na limicie po tym, jak jedynie ponownie zapisała dane, które się nie zmieniły (w kółko te same strony), jest teraz liczona tak samo jak ta, która nic nie zapisała, więc źródło danych utknięte w ten sposób zostaje wstrzymane, zamiast ponawiać próby w nieskończoność.
- Przebieg zatrzymany limitem czasu uwzględnia teraz to, co zapisały wszystkie jego kroki, a nie tylko ten, w którym akurat się zatrzymał.`,
  },
  {
    version: "1.23.0",
    date: "2026-10-28",
    en: `**Test connection: a new optional "field search" check.**

- Test connection now also tries Jira's field search endpoint and shows it as an optional row, so you can see in one click whether your token may use it. It does not change what Flow syncs, and a failure of this row never fails the test.
- The server log gets one line comparing the number of fields it returns with the plain field list, so it is clear whether the two agree.`,
    pl: `**Test połączenia: nowa opcjonalna kontrola „wyszukiwania pól”.**

- Test połączenia sprawdza teraz także endpoint wyszukiwania pól w Jirze i pokazuje go jako opcjonalny wiersz, więc jednym kliknięciem zobaczysz, czy Twój token może z niego korzystać. Nie zmienia to tego, co Flow synchronizuje, a niepowodzenie tego wiersza nigdy nie oznacza niepowodzenia testu.
- W logu serwera pojawia się jedna linia porównująca liczbę pól zwróconych przez ten endpoint ze zwykłą listą pól, więc od razu widać, czy się zgadzają.`,
  },
  {
    version: "1.22.1",
    date: "2026-10-28",
    en: `**Person trend: the team line loads about twice as fast.**

- For a person in a large team, the trend with the team reference now loads in about half the time, and several people opening it at once no longer slow each other down. The numbers are unchanged.`,
    pl: `**Trend osoby: linia zespołu ładuje się mniej więcej dwa razy szybciej.**

- Dla osoby z dużego zespołu trend z linią odniesienia zespołu ładuje się teraz w mniej więcej połowę czasu, a kilka osób otwierających go jednocześnie nie spowalnia się już nawzajem. Liczby się nie zmieniają.`,
  },
  {
    version: "1.22.0",
    date: "2026-10-28",
    en: `**Jira sync: a sync that keeps hitting its limits now pauses.**

- A sync run now has a time limit (60 minutes by default, \`INGEST_MAX_JOB_MINUTES\`). When it is reached the run stops between pages; if it had already stored pages, the next run simply carries on at the normal interval.
- When several syncs in a row stop at a safety or time limit without storing anything, Flow stops running that data source's scheduled syncs and marks it **Paused** on the Data sources pages, with the reason. Data already synced stays, the daily reconcile and the reports keep working.
- **Sync now**, or saving the data source, resumes it. How many such stops in a row pause it is \`INGEST_PAUSE_AFTER_LIMIT_TRIPS\` (5 by default).`,
    pl: `**Synchronizacja z Jirą: synchronizacja, która ciągle trafia w limity, zostaje teraz wstrzymana.**

- Pojedyncza synchronizacja ma teraz limit czasu (domyślnie 60 minut, \`INGEST_MAX_JOB_MINUTES\`). Po jego osiągnięciu zatrzymuje się między stronami; jeśli zdążyła już zapisać strony, kolejna po prostu kontynuuje w zwykłym odstępie.
- Gdy kilka synchronizacji z rzędu zatrzyma się na limicie bezpieczeństwa lub czasu, nic nie zapisując, Flow przestaje uruchamiać zaplanowane synchronizacje tego źródła danych i oznacza je na stronach Źródeł danych jako **Wstrzymane**, podając powód. Dane już pobrane zostają, a codzienne uzgodnienie i raporty nadal działają.
- **Synchronizuj teraz** albo zapisanie źródła danych wznawia synchronizację. Po ilu takich zatrzymaniach z rzędu następuje wstrzymanie, ustawia \`INGEST_PAUSE_AFTER_LIMIT_TRIPS\` (domyślnie 5).`,
  },
  {
    version: "1.21.1",
    date: "2026-10-28",
    en: `**Jira sync: protection against oversized responses.**

- The Jira sync now refuses a response that is unreasonably deep or dense, instead of risking the worker. The affected data source shows a clear error on its status page, and nothing already stored is lost.
- Large Jira responses are now read one at a time, which keeps the sync's memory use steadier.`,
    pl: `**Synchronizacja z Jirą: ochrona przed zbyt dużymi odpowiedziami.**

- Synchronizacja z Jirą odrzuca teraz odpowiedź, która jest nadmiernie głęboka lub gęsta, zamiast narażać proces roboczy. Dotknięte źródło danych pokazuje czytelny błąd na stronie statusu, a nic z tego, co już zapisano, nie ginie.
- Duże odpowiedzi Jiry są teraz przetwarzane pojedynczo, dzięki czemu zużycie pamięci przez synchronizację jest bardziej wyrównane.`,
  },
  {
    version: "1.21.0",
    date: "2026-10-28",
    en: `**Person trend: see how the person's team did over the same windows.**

- The Person trend line charts can now show the team's median beside the person, as a second, quieter line: dotted and gray. It appears when the person has a team with enough active members. The median is taken over the person's team-mates who were active in that window, so a move the whole team made is not read as this person's.
- The team is the one the person belonged to on the window's last day, so a person who changed teams is compared with each team in turn. Hover a point to see the team and how many team-mates the median is based on. Where too few team-mates were active there is no median, and the line has a gap.
- On the cycle-time chart the dotted line is the team's median of the median. The numbers table under a chart gains a Team column and a team-median column with the number of team-mates in brackets.`,
    pl: `**Trend osoby: zobacz, jak w tych samych oknach radził(a) sobie zespół tej osoby.**

- Wykresy liniowe Trendu osoby mogą teraz pokazywać obok osoby medianę zespołu, jako drugą, spokojniejszą linię: kropkowaną i szarą. Pojawia się, gdy osoba ma zespół z wystarczającą liczbą aktywnych członków/członkiń. Mediana jest liczona ze współpracowników z zespołu osoby, którzy byli aktywni w danym oknie, dzięki czemu zmiana, którą przeszedł cały zespół, nie jest przypisywana tej osobie.
- Zespół to ten, do którego osoba należała ostatniego dnia okna, więc osoba, która zmieniała zespoły, jest porównywana kolejno z każdym z nich. Najedź na punkt, aby zobaczyć zespół i liczbę współpracowników, na której oparto medianę. Gdy aktywnych osób jest za mało, mediany nie ma, a linia ma przerwę.
- Na wykresie czasu cyklu linia kropkowana to mediana zespołu z mediany. Tabela z liczbami pod wykresem ma nową kolumnę Zespół i kolumnę mediany zespołu z liczbą współpracowników w nawiasie.`,
  },
  {
    version: "1.20.1",
    date: "2026-10-27",
    en: `**Jira sync: stricter safety limits.**

- The Jira sync now stops, with a clear error on the data source's status page, when Jira keeps paging without end or sends an answer far larger than any real one. Nothing already stored is lost.
- The largest single Jira response the sync accepts is now 8 MiB (it was 32 MiB). A site whose own responses are bigger can raise it with the \`JIRA_MAX_RESPONSE_BYTES\` setting.`,
    pl: `**Synchronizacja z Jirą: ostrzejsze limity bezpieczeństwa.**

- Synchronizacja z Jirą zatrzymuje się teraz z czytelnym błędem na stronie statusu źródła danych, gdy Jira stronicuje bez końca albo odsyła odpowiedź znacznie większą niż jakakolwiek prawdziwa. Nic z tego, co już zapisano, nie ginie.
- Największa pojedyncza odpowiedź Jiry, jaką synchronizacja przyjmuje, ma teraz 8 MiB (wcześniej 32 MiB). Jeśli odpowiedzi Twojej instancji są większe, możesz podnieść limit ustawieniem \`JIRA_MAX_RESPONSE_BYTES\`.`,
  },
  {
    version: "1.20.0",
    date: "2026-10-27",
    en: `**Person trend: see the items behind a point.**

- Click a dot on a Person trend chart to list the items behind it: the same tasks, worklogs or work in progress that the figure counted over that 90-day window. The same list opens from a figure in the chart's table of numbers, which is also the way to open it with the keyboard.
- The list is headed with the figure and the day the window ends on, and Escape closes it and returns you to where you were. A window with no figure has no dot to click, and a figure withheld for too small a sample has nothing to open.`,
    pl: `**Trend osoby: zobacz zadania stojące za punktem.**

- Kliknij punkt na wykresie Trendu osoby, aby zobaczyć listę tego, co za nim stoi: te same zadania, wpisy czasu pracy lub prace w toku, które policzono w danej wartości w tym 90-dniowym oknie. Tę samą listę otworzysz też z liczby w tabeli pod wykresem, co jest jednocześnie drogą dla klawiatury.
- Nagłówek listy to nazwa liczby i dzień, w którym kończy się okno; Escape zamyka listę i wraca tam, gdzie byłaś/byłeś. Okno bez liczby nie ma punktu do kliknięcia, a liczba ukryta przez zbyt małą próbę nie ma czego otworzyć.`,
  },
  {
    version: "1.19.0",
    date: "2026-10-27",
    en: `**Person trend: how one person's figures change over time.**

- The new **Person trend** report (in the Reports menu, after Compare people) shows one person's figures from Compare people over rolling 90-day windows, a point every 14 days, across every team they belonged to. The period starts at the last 365 days and can be changed in the filters.
- Each figure has its own chart: cycle time (median and p90), flow efficiency, delivered items and man-days, estimate accuracy, work in progress, blocked tasks, logged time, tasks without an estimate, reopened tasks and task size. Under every title a mark says whether the person got better or worse, comparing the first and last full window of the range, and a table lists the numbers.
- An open dot is a window that only partly lies in the derived data, and a gap in a line is a window with no figure - never a zero.
- The work mix shows what the person delivered in each window as shares of 100%, by activity type and by work category.`,
    pl: `**Trend osoby: jak zmieniają się liczby jednej osoby w czasie.**

- Nowy raport **Trend osoby** (w menu Raporty, po Porównaniu osób) pokazuje liczby jednej osoby z Porównania osób w kroczących 90-dniowych oknach, punkt co 14 dni, we wszystkich zespołach, do których należała. Okres zaczyna się od ostatnich 365 dni i można go zmienić w filtrach.
- Każda liczba ma własny wykres: czas cyklu (mediana i p90), efektywność przepływu, dostarczone zadania i osobodni, trafność szacowania, praca w toku, zablokowane zadania, zalogowany czas, zadania bez oszacowania, ponownie otwarte zadania i rozmiar zadań. Pod każdym tytułem znacznik mówi, czy osoba poprawiła się, czy pogorszyła, porównując pierwsze i ostatnie pełne okno zakresu, a tabela wylicza liczby.
- Puste kółko to okno tylko częściowo objęte wyliczonymi danymi, a przerwa w linii to okno bez liczby - nigdy zero.
- Struktura pracy pokazuje, co osoba dostarczyła w każdym oknie, jako udziały sumujące się do 100%, według typu aktywności i według kategorii pracy.`,
  },
  {
    version: "1.18.0",
    date: "2026-10-27",
    en: `**Compare people: two new rows, task size and reopened tasks.**

- **Task size** is the median estimate, in man-days, that the person's finished tasks had when work started. A task that was never started or never estimated is left out. The smaller figure is the better one.
- **Reopened share** is the share of the person's finished tasks that were reopened at least once. The lower share is the better one.
- Both rows sit in the Delivery group, and clicking a figure lists the tasks behind it.`,
    pl: `**Porównanie osób: dwa nowe wiersze, rozmiar zadań i ponownie otwarte zadania.**

- **Rozmiar zadań** to mediana oszacowania, w osobodniach, jakie zakończone zadania danej osoby miały w chwili rozpoczęcia pracy. Zadanie, którego nigdy nie rozpoczęto lub nie oszacowano, nie jest liczone. Lepsza jest mniejsza wartość.
- **Udział ponownie otwartych** to odsetek zakończonych zadań danej osoby, które otwarto ponownie przynajmniej raz. Lepszy jest niższy udział.
- Oba wiersze są w grupie Dostarczanie, a kliknięcie wartości pokazuje listę zadań, które za nią stoją.`,
  },
  {
    version: "1.17.4",
    date: "2026-10-25",
    en: `**Routine Jira synchronizations make far fewer calls.**

- Every synchronization of every data source used to fetch the whole list of Jira users, about two thirds of the calls a routine synchronization makes. Flow now refreshes that list once a day per data source, so many data sources stay well clear of Jira's rate limits.
- "Sync now" still refreshes the users at once: after creating a new colleague's Jira account, press it before adding them to a team. Reports and figures do not change.`,
    pl: `**Rutynowe synchronizacje z Jirą wykonują dużo mniej wywołań.**

- Każda synchronizacja każdego źródła danych pobierała dotąd całą listę użytkowników Jiry, czyli około dwóch trzecich wywołań rutynowej synchronizacji. Teraz Flow odświeża tę listę raz dziennie dla każdego źródła danych, więc nawet wiele źródeł danych trzyma się z dala od limitów Jiry.
- „Synchronizuj teraz” nadal od razu odświeża użytkowników: po założeniu konta w Jirze nowej osobie w zespole naciśnij ten przycisk, zanim dodasz ją do zespołu. Raporty i liczby się nie zmieniają.`,
  },
  {
    version: "1.17.3",
    date: "2026-10-25",
    en: `**Backlog's "recently touched only" filter answers much faster for a team.**

- With several data sources, a team's backlog narrowed to recently touched items worked through every data source, including those where the team owns nothing. It now reads only the data sources where the team owns a domain: with twelve data sources the page answers in under a tenth of a second instead of about 0.7 seconds, and about 3 seconds when several people open it at once. The figures do not change.`,
    pl: `**Filtr „tylko ostatnio ruszane” w raporcie zaległości odpowiada dla zespołu dużo szybciej.**

- Przy kilku źródłach danych zaległości zespołu zawężone do ostatnio ruszanych pozycji przechodziły przez każde źródło danych, także te, w których zespół nie ma żadnej domeny. Teraz Flow czyta tylko źródła danych, w których zespół jest właścicielem domeny: przy dwunastu źródłach danych strona odpowiada w mniej niż jednej dziesiątej sekundy zamiast w około 0,7 sekundy, a w około 3 sekundy, gdy otwiera ją jednocześnie kilka osób. Liczby się nie zmieniają.`,
  },
  {
    version: "1.17.2",
    date: "2026-10-25",
    en: `**A new Jira data source's first synchronization is much faster.**

- Flow now asks Jira for the change history of an issue batch 1,000 entries at a time instead of 50. A first synchronization of a project with 6,400 issues took about 50 minutes, three quarters of it fetching that history, so a new data source is ready much sooner. Later synchronizations that pick up many changed issues are faster too; reports and figures do not change.`,
    pl: `**Pierwsza synchronizacja nowego źródła danych z Jiry jest dużo szybsza.**

- Flow pobiera teraz z Jiry historię zmian paczki zgłoszeń po 1000 wpisów zamiast po 50. Pierwsza synchronizacja projektu z 6400 zgłoszeniami trwała około 50 minut, z czego trzy czwarte zajmowało pobieranie tej historii, więc nowe źródło danych jest gotowe dużo wcześniej. Kolejne synchronizacje z wieloma zmienionymi zgłoszeniami też przyspieszają; raporty i liczby się nie zmieniają.`,
  },
  {
    version: "1.17.1",
    date: "2026-10-24",
    en: `**Flow no longer keeps what it never reads from Jira issues.**

- Issue comments, descriptions, attachment details and Jira's embedded copy of the work log are no longer stored: Flow never used them, and they made up about 60% of the stored issue documents. Of the people on an issue (assignee, reporter, creator) it keeps only the id, name and type; a person picked in a custom field keeps their full details.
- The next synchronization trims the data already stored, once; a large data source takes a few minutes longer that one time. The raw issue inspector now shows the trimmed document. Reports and figures do not change.`,
    pl: `**Flow nie zapisuje już tego, czego nigdy nie czyta ze zgłoszeń Jiry.**

- Flow nie zapisuje już komentarzy, opisów, danych załączników ani osadzonej w zgłoszeniu kopii wpisów czasu pracy: nigdy z nich nie korzystał, a stanowiły około 60% zapisanych dokumentów zgłoszeń. Z osób przy zgłoszeniu (przypisanej, zgłaszającej i tworzącej) zachowuje tylko identyfikator, nazwę i typ; osoba wybrana w polu własnym zachowuje wszystkie dane.
- Najbliższa synchronizacja jednorazowo przycina dane już zapisane; duże źródło danych potrwa ten jeden raz o kilka minut dłużej. Podgląd surowego zgłoszenia pokazuje teraz przycięty dokument. Raporty i liczby się nie zmieniają.`,
  },
  {
    version: "1.17.0",
    date: "2026-10-24",
    en: `**A telemetry panel shows how Flow itself is doing (administrators only).**

- Open it from the account menu (or the command palette): a panel beside the page, on every page, with Memory, Storage, Sync, DERIVE, Requests and JVM sections. Memory shows each process's share of its container limit, with a "High" and a "Critical" mark from 80% and 90%.
- Sync and DERIVE show, per data source, how old the last good sync is, how many syncs failed in a row, how often Jira throttled us and how long the last backfill and DERIVE took. Storage shows the database, each schema and an estimate (≈) per data source.
- Every section draws a trend over 24 hours, 7 days or 90 days, with its numbers in a table behind "Show the figures".`,
    pl: `**Panel telemetrii pokazuje, jak radzi sobie sam Flow (tylko dla administratorów/rek).**

- Otwierasz go z menu konta (lub z palety poleceń): panel obok strony, na każdej stronie, z sekcjami Pamięć, Przestrzeń, Synchronizacja, DERIVE, Żądania i JVM. Pamięć pokazuje udział każdego procesu w limicie kontenera, ze znacznikiem „Wysokie” od 80% i „Krytyczne” od 90%.
- Synchronizacja i DERIVE pokazują dla każdego źródła danych wiek ostatniej udanej synchronizacji, liczbę nieudanych synchronizacji z rzędu, jak często Jira nas ograniczała oraz jak długo trwały ostatnie pobranie historii i DERIVE. Przestrzeń pokazuje bazę, każdy schemat i szacunek (≈) dla każdego źródła danych.
- Każda sekcja rysuje trend z 24 godzin, 7 dni lub 90 dni, a jego liczby leżą w tabeli pod przyciskiem „Pokaż wartości”.`,
  },
  {
    version: "1.16.1",
    date: "2026-10-24",
    en: `**Report filters keep every choice.**

- Picking a person (or another filter) right after changing the period could quietly undo the period: the page wrote the URL from the filters as they were a moment earlier. Every filter change now builds on the latest filters.
- The search box, the account menu and the Deep dive's cells now carry the words and numbers you see in their names, so screen readers and voice control use the same words as the screen.`,
    pl: `**Filtry raportów zachowują każdy Twój wybór.**

- Wybranie osoby (lub innego filtra) tuż po zmianie okresu mogło po cichu cofnąć ten okres: strona zapisywała adres z filtrów w takim stanie, w jakim były chwilę wcześniej. Każda zmiana filtra opiera się teraz na najnowszych filtrach.
- Pole wyszukiwania, menu konta i komórki Deep dive mają teraz w nazwach te słowa i liczby, które widzisz, więc czytnik ekranu i sterowanie głosem posługują się tymi samymi słowami co ekran.`,
  },
  {
    version: "1.16.0",
    date: "2026-10-24",
    en: `**Resetting a forgotten password now goes through a link you confirm.**

- "Forgot password?" emails you a single-use link that works for 15 minutes. Nothing changes until you open it and choose a new password: your current password keeps working, and nobody can sign you out by typing your address into the form.
- Once you set the new password, every other session of your account is signed out, and a short email confirms the change.`,
    pl: `**Resetowanie zapomnianego hasła odbywa się teraz przez link, który potwierdzasz.**

- „Nie pamiętasz hasła?” wysyła Ci jednorazowy link ważny 15 minut. Nic się nie zmienia, dopóki go nie otworzysz i nie wybierzesz nowego hasła: Twoje obecne hasło nadal działa i nikt nie wyloguje Cię, wpisując Twój adres w formularzu.
- Gdy ustawisz nowe hasło, wylogowujemy wszystkie pozostałe sesje Twojego konta, a krótki e-mail potwierdza zmianę.`,
  },
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

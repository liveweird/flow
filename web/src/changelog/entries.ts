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
    version: "0.4.0",
    date: "2026-10-06",
    en: `**Deep dive: plan, execution and cost in man-days for any set of work, task by task and day by day.**

- **Deep dive** (a new report page, open to every signed-in user) lays plan, execution and cost side by side for a chosen set of work. Select it in one of three ways: the tasks of a domain in the sprints you pick, whole epics, or handpicked tasks of one epic. The selection lives in the page address, so a link shares it.
- **Drill down** from months to weeks to days, and from epics to their tasks. Tooltips show who logged time on a day.
- **Velocity per person**: the person level now shows each sprint's frozen figures, as they stood when the sprint closed, attributed to whoever held the task at commitment, and flags where they differ from the live ones.
- **Cycle time gains an epics block**: how long epics took from start to done, by owner team. It is not shown per person.
- **Sprint totals add up**: each item's man-days are rounded before they are summed, so a team's total no longer differs by 0.01 from its people's figures.
- **Keyboard access**: every table or chart area that can scroll sideways is now reachable with the keyboard and announced with a name; report tabs are real links and heading levels follow the page structure.
- **For operators**: adding a project or moving the backfill start earlier now downloads the missing issues in bulk instead of trickling them in through the reconcile. A worker that stopped mid-derivation no longer leaves a derivation stuck as running, unknown \`/api\` paths answer 404 instead of the app page, and the app waits for a database that is still starting. No new environment variables are required; \`POSTGRES_CONNECT_RETRIES\` (default 10) is optional.`,
    pl: `**Pogłębiona analiza: plan, wykonanie i koszt w osobodniach dla dowolnego zbioru pracy, zadanie po zadaniu i dzień po dniu.**

- **Pogłębiona analiza** (nowa strona raportu, dostępna dla każdej zalogowanej osoby) zestawia obok siebie plan, wykonanie i koszt wybranego zbioru pracy. Wybierasz go na jeden z trzech sposobów: zadania domeny w wybranych sprintach, całe epiki albo wskazane zadania jednego epika. Wybór zapisuje się w adresie strony, więc link go udostępnia.
- **Drążenie** od miesięcy przez tygodnie do dni oraz od epików do ich zadań. Podpowiedzi pokazują, kto zalogował czas danego dnia.
- **Velocity na poziomie osoby**: poziom osoby pokazuje teraz zamrożone wartości każdego sprintu, takie jak w chwili jego zamknięcia, przypisane osobie, która miała zadanie w chwili zobowiązania, i zaznacza, gdzie różnią się od bieżących.
- **Czas cyklu zyskuje blok epików**: ile trwały epiki od startu do ukończenia, według zespołu-właściciela. Nie jest pokazywany na poziomie osoby.
- **Sumy sprintów się zgadzają**: osobodni każdej pozycji są zaokrąglane przed zsumowaniem, więc suma zespołu nie różni się już o 0,01 od wartości jego członków.
- **Obsługa z klawiatury**: każdy obszar tabeli lub wykresu, który można przewijać w poziomie, jest osiągalny z klawiatury i ma nazwę dla czytników ekranu; karty raportów są prawdziwymi linkami, a poziomy nagłówków odzwierciedlają strukturę strony.
- **Dla osób utrzymujących system**: dodanie projektu lub cofnięcie początku pobierania wstecz pobiera teraz brakujące zgłoszenia hurtowo, zamiast dociągać je pojedynczo podczas uzgadniania. Worker zatrzymany w trakcie wyliczania nie zostawia już wyliczenia na zawsze w stanie „w toku”, nieznane ścieżki \`/api\` zwracają 404 zamiast strony aplikacji, a aplikacja czeka na bazę danych, która jeszcze się uruchamia. Nowe zmienne środowiskowe nie są wymagane; \`POSTGRES_CONNECT_RETRIES\` (domyślnie 10) jest opcjonalna.`,
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

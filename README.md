# Abhyas

**Your notes ask the questions.**

Photograph a page of notes. Abhyas reads it, writes the flashcards, and then schedules them so
you see each one again just before you would have forgotten it.

अभ्यास — repeated practice. Which is the whole method.

## Why this exists

Making flashcards works. Almost nobody does it, because *writing* the cards is an hour of
copying before any studying starts, and that hour is where people quit.

Anki is the serious tool in this space and its scheduling is excellent, but the card-authoring
burden is entirely on you and its Android app is hostile to a beginner. Everything else is a
subscription content farm selling someone else's decks.

Abhyas removes the authoring step and keeps the scheduling honest.

## Nothing leaves the phone

Abhyas does not declare the `INTERNET` permission, so the process cannot open a socket. Your
pages and your cards cannot leave the device even if a dependency tried to send them, and that
is checkable on the Play Store listing without taking anyone's word for it.

This is not decoration on a study app. The pages people photograph are coursework, tuition
material and their own handwriting, and a lot of it is copyrighted or simply private.

`app/src/main/AndroidManifest.xml` strips network access explicitly:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Libraries declare permissions of their own and the manifest merger unions them into the app, so
these two lines remove network access no matter what any current or future dependency asks for.
**Never resolve a merger conflict by deleting them.** Verify after a build in
`app/build/outputs/logs/manifest-merger-*-report.txt`.

Three consequences that shape the code:

- **OCR uses ML Kit's bundled text recogniser**, not the unbundled Play-Services-backed one. The
  model ships inside the APK; the unbundled build downloads it at runtime and would need network.
- **Card generation is heuristic and on-device.** A language model would write better questions.
  That trade was made deliberately, and `CardGenerator` is a one-method interface precisely so
  the decision can be revisited without touching anything else.
- **No analytics, no crash reporting, no remote config.** If something breaks in the field, we
  find out because someone tells us.

Abhyas asks for `CAMERA`, and for notifications **only if you turn reminders on**. Importing an
existing photo goes through the system photo picker, which needs no storage permission at all.

## The daily reminder

**Off. Nothing is scheduled, no notification channel is created and no permission is requested
until you switch it on yourself in Settings.** An app that starts nudging you because it was
installed has decided something on your behalf that was never its call.

When you do switch it on, the permission is requested at that moment — not at first launch,
where a prompt arrives before anyone knows what it is for and earns a denial you can never come
back from. Deny it and reminders stay off and say so, rather than storing an intention the
system will never honour.

Three things it refuses to do, all of them the reason reminders get switched off for good:

- **Fire when nothing is due.** A notification that leads to an empty deck teaches people to
  dismiss the next one unread.
- **Fire when you have already studied that day.**
- **Fire twice.** WorkManager guarantees a job runs *at least* once per period, not exactly once,
  so a device waking from a long sleep can run a deferred job immediately.

It also re-checks consent on every run — permission can be revoked in system settings without the
app ever being opened again, and the honest response to that is to stop and turn the setting off.
Settings does the same check when opened, so the toggle never claims to be on when nothing can
be posted.

A chain of one-shot jobs, not a `PeriodicWorkRequest`: a periodic request cannot be anchored to a
wall-clock time, so "remind me at 7pm" would drift into "some point in the evening, eventually".

## Backup

No account and no sync, so the export file is the only way a collection survives a lost phone.

- **Schedules travel with the cards.** Restoring the words but not the intervals would hand back
  a pile of new cards and quietly erase months of work.
- **Restoring adds, never replaces.** Every deck comes in fresh alongside what is already there,
  so the wrong file costs you a few decks to delete instead of everything. Undoing an unwanted
  restore is easy; undoing a wipe would be impossible.
- **Reading is forgiving.** A file from a newer version, hand-edited, or truncated restores what
  it can. Every field falls back to a sane default and an unreadable row is skipped, not fatal.

Plain JSON through the system file picker, so the file lands wherever the user chooses and stays
readable in a text editor.

## Scripts

Abhyas reads five writing systems, and the script is chosen **per deck** — a student's Hindi
deck and Biology deck are open at the same time.

| Script | Reads | Sentence splitting | Definitions | Q&A labels | Clozes |
|---|---|---|---|---|---|
| Latin | English, European | `. ? !` | colon + copula | `Q.` / `Ans.` | terms and digits |
| Devanagari | Hindi, Marathi, Sanskrit, Nepali (+ Latin) | danda `।॥` | colon + "का अर्थ है" | `प्रश्न` / `उत्तर` | terms and digits |
| Chinese | Simplified, Traditional (+ Latin) | `。？！` | colon | `問` / `答` | digits only |
| Japanese | Kanji, kana (+ Latin) | `。？！` | colon | `問` / `答` | digits only |
| Korean | Hangul (+ Latin) | `。？！` | colon | `문제` / `답` | digits only |

**Per deck, not auto-detected.** The Latin model shown Devanagari returns confident nonsense
rather than failing, which is the worst possible behaviour — the user watches cards get made and
only later finds out they are gibberish. Every non-Latin model also reads Latin, so a Hindi
textbook with English technical terms in it works under Devanagari, and there is no case where
guessing beats asking once.

**Support is deliberately uneven**, and that is honest rather than lazy:

- **Copula inversion is off for Hindi.** Hindi is subject-object-verb, so its copula sits at the
  end of the clause rather than between the two halves — splitting on `है` would put the whole
  definition on the left and nothing on the right. Colon definitions and the fixed phrase
  `X का अर्थ है Y` carry that script instead.
- **CJK gets no term clozes.** Choosing a word to blank needs segmentation, and these scripts do
  not mark word boundaries with spaces. A cloze cut at the wrong character is not a harder card,
  it is a broken one — so CJK falls back to digits and dates, which are unambiguous everywhere.

Every language-specific rule lives in `ScriptProfile`, not in the generator. Adding a sixth
script is a new object plus a dependency line.

> **APK size.** Each bundled recogniser is several MB and they are additive — all five add
> roughly 30–40 MB. That is a real price on a cheap phone and a metered connection. To trim it,
> delete the recogniser lines in `app/build.gradle.kts` and the matching `ScriptOption` /
> `ScriptProfile` entries; nothing else refers to them. The proper fix when it starts to hurt is
> Play Feature Delivery, with each non-default script as an on-demand module.

## How a page becomes cards

```
photo -> ML Kit OCR -> rebuild sentences -> three generator passes -> you approve -> deck
```

**Rebuilding sentences is the single biggest lever on card quality.** A camera sees line breaks,
not sentences, so a paragraph arrives pre-shredded at whatever width the page was typeset at.
`PageText` joins wrapped lines back into runs (and rejoins words split across a hyphen) before
any generator sees them, and never joins across an ML Kit block boundary, which is what stops a
caption being welded onto the end of a paragraph.

Then three passes, in descending order of how much the page told us:

| Pass | Fires on | Example |
|---|---|---|
| **Q&A** | The page already had questions on it | `Q1. Where does photosynthesis take place?` / `Ans. In the chloroplasts.` |
| **Definition** | `Term: meaning`, or `X is/are/means/refers to Y` | `Chlorophyll: the green pigment...` becomes *What is Chlorophyll?* |
| **Cloze** | Anything left with a date, figure or distinctive term | `...described by Jan Ingenhousz in 1779.` becomes `...in _____.` |

Everything is scored and sorted so the confident suggestions land at the top of the review
screen, and the whole thing is biased towards **fewer, better** cards — a student who sees six
good suggestions accepts them all; one who sees forty mediocre ones closes the app.

Nothing is ever saved silently. The review screen is where suggestions become cards, and every
one can be edited or dropped first.

### What the passes deliberately refuse

Most of the quality is in the refusals, and each one is there because it produced a bad card:

- `was` / `were` are **not** definition verbs. Past tense is narrative — *"The process was first
  described by Jan Ingenhousz in 1779"* is a fact about a thing, not a definition of it, and
  inverting it yields the useless *"What is The process?"*. Sentences like that fall through to
  the cloze pass, which blanks the date and produces the card actually worth studying.
- A colon only makes a definition when the left side is **short and term-shaped**. Prose is full
  of colons: *"There are three reasons:"* is a lead-in, not a definition.
- Plural is read off the **sentence's own verb** where there is one, not guessed from the noun.
  Otherwise *"Photosynthesis is..."* becomes *"What are Photosynthesis?"*. Colon definitions have
  no verb to read, so they fall back to a suffix test that knows `-sis`, `-is`, `-us`, `-ss` and
  `-ous` are not plurals.
- A cloze never blanks a word that occurs twice in the sentence, which would leave the answer
  sitting in plain sight inside the question.
- An unlabelled sentence is only taken as an answer when the question carried an explicit `Q`
  label (or `प्रश्न`, `問`, `문제`). A numbered line on its own is far more often a list item
  than a question.
- A **chapter heading is not a definition**. "Chapter 4: Photosynthesis" has the exact shape of a
  colon definition, so each script carries a heading prefix that rules it out.

## Scheduling

**FSRS-5**, the Free Spaced Repetition Scheduler — the same algorithm Anki made its default — with
short in-session learning steps in front of it.

Abhyas shipped SM-2 first and that was a mistake worth correcting. SM-2 is forty years old and
tracks one number per card, an "ease factor" it multiplies the interval by. It therefore cannot
tell the difference between a card you answered the day it was due and the same card answered
three months late. FSRS models memory with two numbers instead:

| | |
|---|---|
| **Stability** | days until your chance of recall falls to 90% |
| **Difficulty** | how hard this card is *for you*, 1–10 |
| **Retrievability** | derived at review time: the probability you still know it, right now |

That third one is what SM-2 has no concept of, and three things follow from it:

1. **Answering late is rewarded, not punished.** Recalling a card 120 days after it was due
   proves far more than recalling it on time, and the next interval reflects that — 217 days
   versus 90 for the identical card. SM-2 gives the same answer either way. Anyone who studies in
   bursts around exams lives in this case.
2. **Fewer reviews for the same retention**, because the model is fitted to millions of real
   reviews rather than assumed.
3. **Retention is a dial, not an outcome.** Ask for 90% and you get the interval that achieves
   it; ask for 95% and the intervals shorten. Under SM-2, retention was whatever fell out.

The weights are the published FSRS-5 defaults, deliberately untuned — per-user optimisation needs
a review history to fit against, and the defaults already beat anything hand-chosen.

**Learning steps are kept.** FSRS alone sends a brand-new card answered Good straight out to
three days, which reads as broken to someone learning something for the first time. Minutes
first, days once it has actually been recalled.

**Collections created before the change are converted, not reset.** The old interval becomes
stability and the old ease factor becomes a difficulty. The mapping is approximate and allowed to
be — the alternative was resetting every card to new, throwing away exactly the history it is
reconstructing.

`Scheduler` and `Fsrs` are pure functions of their inputs, with no database, no clock and no
Android. Expected values in the tests come from the published formulas, not from running the
implementation and writing down what it said — a test built that way would have passed just as
happily on the SM-2 version it replaced.

### Leeches

Forget a card eight times and it is not a memory problem any more — the card is written badly or
is trying to hold too much at once. Reaching the threshold **suspends it** and surfaces it on the
Insights screen for rewriting. Left alone, one leech comes back every few days forever, soaking
up review time and teaching the user that the app wastes it.

### Undo

Mis-tapping Easy when you meant Again is the most common mistake in any review app, and without
undo it silently costs months of correct scheduling on that card — damage the user cannot see and
would not know how to repair. One step only, deliberately: the mistake this exists for is always
the answer you just gave. It restores the card *and* deletes the log row, so a review taken back
stops counting towards the streak and the retention figure too.

### What the buttons say

Each grade button shows the interval it would actually schedule — `3d`, `2w`, `4mo`. Computed by
running the real scheduler four times, so what the button says is exactly what pressing it does.
"Good" meaning three weeks and "Easy" meaning three months is the choice the user is really
making.

## Insights

| | |
|---|---|
| **Retention** | share of the last 30 days' answers recalled, against the 90% target — the number that says whether the schedule is pitched right for *this* user |
| **Maturity** | where the collection sits: unseen, learning, under three weeks, sticking |
| **Forecast** | how many cards fall due on each of the next 14 days |
| **Leeches** | the cards set aside, with a way to put them back |

The forecast is the one most students will feel: seeing a wall of reviews on Thursday while it is
still Monday is the difference between planning and being ambushed.

The chart colours were re-stepped into the dark-mode lightness band and checked with a palette
validator rather than by eye. The obvious choice — reusing the existing accent colours directly —
failed on lightness and read as four pastels against the surface, and the muted purple failed the
chroma floor by rendering as grey. Colour-blind separation passes for deutan and protan but is
tight for tritan, so every segment carries a direct label: identity is never on colour alone.

## Project layout

One module. Abhyas shares no code with LayerLink or Deja — the brand constants and the font are
kept in step by hand, which is cheaper at this size than a shared library nobody else consumes.

```
app/src/main/java/com/layerbit/abhyas/
  data/
    db/          Room: decks, cards, an append-only review log
    srs/         Fsrs - the FSRS-5 memory model, pure
                 Scheduler - the state machine around it, pure and unit-tested
    ocr/         PageTextReader - ML Kit per script, and the sentence rebuilding
                 ScriptOption / ScriptProfile - every language-specific rule, in one place
    generate/    CardGenerator interface + the heuristic implementation
    repo/        AbhyasRepository - everything the UI may do to the collection
    reminder/    ReminderPreferences / Scheduler / Worker - the opt-in daily nudge
    stats/       Streak - pure, and deliberately forgiving about today
    backup/      BackupCodec - export and restore as plain JSON
    model/       Grade, CardState
  ui/
    decks/       Deck list with due and new counts, and the streak
    deck/        One deck: counts, study, add cards, browse every card
    capture/     Camera -> reading -> review -> saved, as one screen with four states
    study/       The review loop, grade buttons that show their intervals, undo
    insights/    Retention, maturity, the 14-day forecast, leeches
    search/      One field across every deck
    settings/    Reminder consent and time, export and restore
    about/       Practice stats, the privacy story, support links
    theme/       Palette and Space Grotesk
```

## Design

Deep indigo `#17122B` with warm saffron `#F5A524` — lamplight, and studying late.

LayerLink is cool blue-black because it is a tool and Deja is warm charcoal because it is a
memory. Abhyas keeps the family's dark base but takes the accent warm: the audience is students,
and it should not feel like an IDE. Space Grotesk throughout, as everywhere else at Layerbit.

The icon is **अ** (U+0905), the first letter of the name — the real glyph outline from Noto Sans
Devanagari Bold rather than a hand-drawn approximation, because the audience reads Devanagari and
a letterform that is subtly wrong is worse than no letter at all. It is scaled to sit entirely
inside the 66dp safe zone; drawn any larger, a circular launcher mask clips the shirorekha and
the right-hand stem.

## Requirements

- Android Studio with an SDK for **compileSdk/targetSdk 36**
- JDK 17
- `minSdk` 26

No `google-services.json` is required.

## Building

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Five test classes, all of them covering things that fail *silently*:

| Test | Guards against |
|---|---|
| `SchedulerTest` | An interval 30% wrong is invisible until months of study are wasted — and it pins FSRS against the published formulas, not against itself |
| `HeuristicCardGeneratorTest` | Every case is a bug that reached the working tree — wrong cards, or a whole pass quietly not firing |
| `StreakTest` | A streak counter that reads zero every morning talks people out of their own habit |
| `BackupCodecTest` | The file is the only thing between a user and losing a collection with a phone |
| `ReminderSchedulerTest` | A reminder at the wrong hour still fires — nobody reports it, they just switch reminders off |

This repository's sandbox had no Android SDK and no access to Google's Maven repo, so **the
Gradle build has not been run end to end** — the source was written and reviewed by hand against
the AndroidX, CameraX, Room and ML Kit APIs. Open the project in Android Studio and sync to pull
dependencies and catch anything environment-specific.

The generator and scheduler logic *was* exercised: both were ported to a scratch script and run
against a realistic OCR read of a textbook page, which is how four bugs — including one that
stopped the highest-confidence pass firing at all — were found and fixed before the first commit.

## Status

Feature-complete for a first release, in source. Create a deck in any of five scripts,
photograph a page, approve the suggested cards, study with real spaced repetition, edit, rename
and merge what you have, opt into a daily reminder, and export the lot to a file.

Known limits, none of which are oversights:

- **Tamil, Telugu, Bengali and the other Indic scripts.** ML Kit ships no bundled model for them.
  This needs a different OCR engine, not another line in the gradle file, and it is the single
  biggest gap for an Indian audience.
- **Handwriting** is only as good as the underlying recogniser, which is to say: mixed. Printed
  pages are reliable; a rushed lecture note often is not.
- **No sync.** Two phones means two collections and a backup file passed between them — the
  direct cost of having no account and no network, and a deliberate trade.
- **Card generation is heuristic.** A language model would write better questions. `CardGenerator`
  is a one-method interface so that stays a decision rather than a rewrite.
- **FSRS weights are the defaults, not fitted to you.** Optimising them per user needs a few
  hundred reviews to fit against and an optimiser to run; the defaults are already strong, and
  this is the obvious next step once a collection has history.
- **No image occlusion.** Hiding labels on a photographed diagram is the one thing Anki does that
  fits this app's camera-first premise better than it fits Anki's, and it is not built yet.

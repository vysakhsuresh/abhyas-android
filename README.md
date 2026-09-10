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

The only permission Abhyas asks for is `CAMERA`. Importing an existing photo goes through the
system photo picker, which needs no storage permission at all.

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
  label. A numbered line on its own is far more often a list item than a question.

## Scheduling

SM-2 — SuperMemo 2, the algorithm Anki is built on — with short in-session learning steps in
front of it.

Plain SM-2 sends a brand-new card straight to a one-day interval the first time you answer it,
which reads as broken to anyone learning something for the first time: you see a card once, say
Good, and it vanishes for a day. So new cards move through minute-scale steps (1 min, 10 min)
and only graduate to day-scale scheduling once they have actually been recalled.

A card you forget drops back to those short steps but **keeps its history** — its interval is
halved rather than reset, so one bad day does not throw away three months of work.

`Scheduler` is a pure function of its inputs, with no database, no clock and no Android. That is
what makes the arithmetic testable, and `SchedulerTest` pins down interval growth, the ease
floor, lapse handling and the interval cap — a mistake here would be close to invisible, since
nobody notices an interval that is 30% too long until months of studying have been wasted.

Worth knowing: SM-2's ease delta for **Good is exactly zero**. Good is the neutral answer; only
Hard and Easy move a card's ease.

## Project layout

One module. Abhyas shares no code with LayerLink or Deja — the brand constants and the font are
kept in step by hand, which is cheaper at this size than a shared library nobody else consumes.

```
app/src/main/java/com/layerbit/abhyas/
  data/
    db/          Room: decks, cards, an append-only review log
    srs/         Scheduler - SM-2 plus learning steps, pure and unit-tested
    ocr/         PageTextReader - ML Kit, and the sentence rebuilding
    generate/    CardGenerator interface + the heuristic implementation
    repo/        AbhyasRepository - everything the UI may do to the collection
    model/       Grade, CardState
  ui/
    decks/       Deck list with due and new counts
    deck/        One deck: counts, study, add cards, browse every card
    capture/     Camera -> reading -> review -> saved, as one screen with four states
    study/       The review loop and the four grade buttons
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

This repository's sandbox had no Android SDK and no access to Google's Maven repo, so **the
Gradle build has not been run end to end** — the source was written and reviewed by hand against
the AndroidX, CameraX, Room and ML Kit APIs. Open the project in Android Studio and sync to pull
dependencies and catch anything environment-specific.

The generator and scheduler logic *was* exercised: both were ported to a scratch script and run
against a realistic OCR read of a textbook page, which is how four bugs — including one that
stopped the highest-confidence pass firing at all — were found and fixed before the first commit.

## Status

Working end to end in source: create a deck, photograph a page, review the suggestions, study
with real spaced repetition.

Not built yet:

- A daily reminder, and a streak worth defending
- Editing a card after it is in a deck (you can delete it, not fix it)
- Bulk actions on the review screen — keep all, drop all
- Handwriting is only as good as ML Kit's Latin recogniser, which is to say: mixed
- Devanagari and other Indic scripts in the OCR itself; the recogniser is Latin-only today

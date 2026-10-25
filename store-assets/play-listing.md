# Play Store listing copy for Abhyas

Every factual claim below is true of the build as it stands — the offline
recognition, the absent internet permission, the five scripts, the Hindi interface
and the on-device schedule fitting are all in the code. Do not loosen them without
checking the app still earns them.

## Privacy policy URL

```
https://vysakhsuresh.github.io/abhyas-android/privacy/
```

Served by GitHub Pages from `docs/privacy/index.html` on `main`, which mirrors
`privacy-policy.md` in this folder. Keep the two in step. If the policy later moves
to layerbit.co.in, update this heading, the Play Console, and `BrandLinks.PRIVACY_URL`
(the About screen links to it) together.

## App name (30 characters max)

```
Abhyas: Flashcards from Notes
```
(29 characters)

## Short description (80 characters max)

```
Photograph your notes. Abhyas reads them and writes the flashcards for you.
```
(74 characters)

## Full description (4000 characters max)

```
Abhyas turns a page of your own notes into flashcards you can actually study.

Photograph a page. Abhyas reads the text on your device, finds the definitions,
the questions, the term-and-meaning tables and the facts worth remembering, and
proposes cards from them. You keep the ones you want and drop the rest — nothing
reaches your deck without you agreeing to it first. Doing a whole chapter? Switch
on "Several pages", keep shooting, and review every suggestion at the end.

DIAGRAMS, TOO

Photograph a labelled diagram — the heart, a map, a circuit — and Abhyas finds the
labels and masks them. Each mask becomes a card: the picture with one label hidden.
Made for anatomy, geography and engineering, from your phone's camera.

STUDY THAT FITS YOU

Abhyas schedules with FSRS, the modern spaced-repetition algorithm. Every grade
button tells you exactly how long pressing it will put the card away for. Once you
have a few hundred reviews, "Re-fit schedule to me" tunes the schedule to how your
own memory behaves — on your phone, with no account — and tells you how much better
it predicts you, measured on cards it had not seen.

• Cards you forget come back within the same sitting, in minutes
• Cards made from the same sentence or picture never give each other away — the
  rest wait until tomorrow
• Exam on Thursday? Search or pick a tag and study everything it matches, without
  touching your real schedule
• Subdecks: a subject holding its chapters, with the counts rolled up
• Tags on any card, and cards read aloud with your phone's offline voices
• One-step undo, because tapping Easy when you meant Again happens to everybody
• Cards you keep forgetting are set aside to be rewritten, not repeated forever
• A two-week forecast, an honest retention figure, and a quiet daily reminder that
  is off until you turn it on

NOTHING LEAVES YOUR PHONE

Abhyas has no account, no sign-in, no analytics and no advertising. It does not
request internet permission at all, which is not a promise but a fact Android
enforces — you can check it yourself in the app's permissions. Text recognition
runs offline from models bundled inside the app, so photographing a page works in
aeroplane mode. Even crash reports stay on your phone unless you choose to email
them yourself.

FIVE SCRIPTS, TWO LANGUAGES

Pages in Latin, Devanagari, Chinese, Japanese and Korean script — English, Hindi,
Marathi, Sanskrit and Nepali are all covered. The app itself speaks English and
Hindi.

YOUR COLLECTION, YOUR FILE

Export your whole collection — decks, cards, pictures and every scheduling detail —
to a single file you choose the location of. Restoring adds alongside what you have
rather than replacing it.

ACCESSIBLE

Every control is labelled for TalkBack, every target is a real target, and the
layouts hold together at the largest system font sizes.

WHAT ABHYAS DELIBERATELY DOES NOT DO

• No sync and no account. That is the direct cost of having no internet access;
  the backup file is how you move to a new phone.
• No shared deck library. The premise is your notes, not someone else's.
• Tamil, Telugu and Bengali pages are not read yet: the offline recogniser Abhyas
  is built on has no model for them.

Abhyas means practice.
```

## Phone screenshots

1080 x 1920 (9:16), PNG, in `screenshots/`, in this order. Taken on an emulator set
to 1080 x 1920 at 420 dpi with a seeded demo collection (`DemoSeedActivity`, debug
builds only) and a clean status bar. Captions are ready to paste into the Console or
to overlay on the images.

| File | Caption |
|---|---|
| `01-decks.png` | Every subject and chapter, with what is due today |
| `02-suggestions.png` | Photograph a page — Abhyas writes the cards, you choose |
| `03-study.png` | Every button shows when the card will come back |
| `04-diagram.png` | Hide the labels on any diagram and learn it |
| `05-insights.png` | See your recall, your forecast, and fit the schedule to you |
| `06-scripts.png` | Reads Latin, Devanagari, Chinese, Japanese and Korean |
| `07-settings.png` | Offline, private, with a backup file you own |
| `08-intro.png` | From your notes to flashcards in three steps |

## Feature graphic

`feature-graphic.png` — 1024 x 500, PNG, RGB with no alpha. Rendered by
`tools/FeatureGraphic.java` (`java store-assets/tools/FeatureGraphic.java` from the
repository root) so it can be regenerated after a rebrand.

## App icon

`play-icon-512.png` (512 x 512).

## Upload format

Upload the **Android App Bundle** from `./gradlew :app:bundleRelease`
(`app/build/outputs/bundle/release/app-release.aab`), never an APK. Play signs the
per-device APKs from it. The bundle must be signed with the upload key named in
`keystore.properties` (see `keystore.properties.example`); an unsigned bundle is
rejected at upload.

## Data safety form answers

- Does your app collect or share any of the required user data types? **No.**
- Is all of the user data collected by your app encrypted in transit? Not
  applicable — no data is transmitted.
- Crash logs: stored only on the device and sent only when the user emails them
  through their own mail app, so they are not "collected" in Play's sense.
- Do you provide a way for users to request that their data is deleted?
  Uninstalling removes everything; there is no server-side copy.

## Content rating

Expect **Everyone / PEGI 3**. The app has no user-generated content shared between
users, no ads, no purchases, and no communication features.

# English UI and accessibility validation

The app interface is English only. There is no language chooser, Telugu/Hindi UI resource bundle or app-language preference. Original SOS text and media/transcripts remain as supplied; the interface does not rewrite user content.

Automated checks exercise the implemented interface. They do not establish that real users understand it, that TalkBack users have accepted it, or that physical phone radios work.

## What is implemented

- Core SOS navigation, emergency choices, review actions, location controls, delivery states and retention/background controls use English Android string resources. The Activity requests an English resource configuration regardless of an obsolete language preference.
- Text selection and typed SOS drafts recover from private encrypted storage on a fresh Activity launch. Draft media follows its separate capture lifecycle. Discard removes the draft; a successful send clears only the submitted draft.
- Screen titles are accessibility headings, form captions point to their controls, disclosure/selection state is exposed, and actionable controls have at least 48dp minimum height. At short screen heights or large font settings, form actions are part of the scrollable content so they remain reachable.
- Background relay is opt-in and visibly reported in Settings. Its notification and Android lifecycle limitations remain explicit. Retention defaults to keeping all history and never targets pending reports.

## Automated checks

`EnglishAccessibilityTest` contains two default-scale emulator checks and one separately scoped large-text check:

1. Save an encrypted synthetic text draft, recreate its store, verify values and verify explicit discard. It uses a unique test preference file.
2. Open English Home, choose a medical SOS and reach review. Verify heading semantics and minimum action targets, then close and relaunch the Activity to recover the selected draft. No SOS is sent and no camera or microphone is opened. Original draft preferences are restored.
3. With the emulator's actual font scale set to 200% by the test operator, use landscape and reach the review/send action through scrolling. The test asserts visible bounds, restores the Activity's requested orientation and restores draft preferences. It skips when the emulator is not configured at 200%; it does not simulate scaling or modify global settings itself.

English Home/review screenshots and landscape/200% screenshots are saved under the app's external-files `local-completion-ui` directory for layout inspection. Screenshots are interface evidence, not proof of user comprehension.

`android/tools/check_english_resources.py` verifies unique, nonempty English resource keys, Kotlin references and English-only UI configuration. It verifies that the removed Telugu/Hindi resource directories are absent.

## Human sessions still required

Use synthetic scenarios with first-time volunteers, including a TalkBack user and a person using large text. Tell each person this is a prototype and that no real emergency or dispatch is involved.

| Task | What to observe |
| --- | --- |
| Choose help and reach SOS review without typing | Can the person find the correct emergency choice and tell whether anything has been sent? |
| Send a general SOS with location unavailable | Can the person identify what will be included and what is unknown? |
| Deny microphone and location permissions | Can the person recover and find a different path without assistance? |
| Read a delivered alert without human acknowledgement | Do they correctly distinguish backend receipt from responder acknowledgement and actual dispatch? |
| Leave a text draft, restart the app, resume it, then discard | Is persistence discoverable and is discard understood? |
| Enable background relay, then turn it off | Can the person explain the notification, battery cost and possibility that Android stops the service? |
| Review retention settings | Can the person distinguish pending reports from eligible delivered history? |

Record task completion, wrong actions, requests for help and the person's own explanation of the delivery state. Do not report synthetic or staff-led test results as real-user validation.

Repeat the same tasks in portrait and landscape at default and 200% font size, using TalkBack traversal and a hardware keyboard where available. Check that every field, error and final action remains reachable. Physical Nearby/Bluetooth relay tests remain a separate hardware exercise.

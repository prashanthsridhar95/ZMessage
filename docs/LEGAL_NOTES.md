# Legal notes: what is covered and what is not

**This is not legal advice, and nothing here claims that Zyke Message complies with any law.** The in-app text (Settings, Privacy and terms) and these notes were written by the developers and an AI assistant from what the code does. **Have a lawyer review the text, the operator details and the distribution plan before any public release** (any store, website download, or distribution beyond people who know the developer).

## Where things live

| What | Where |
|---|---|
| Plain-language privacy, permissions and terms shown in the app | `LegalText` (`:domain`), drawn by `LegalPages.kt`; Settings, This app, Privacy and terms |
| Operator name, contact, minimum age, text version and date | `LegalInfo` (`:domain`), one place |
| Third-party libraries, font, icons, services and their licences | `OpenSourceNotices` (`:domain`); Settings, This app, Open-source licences; `NOTICE` at the repository root (generated from the same list) |
| Technical data inventory and threat model | [PRIVACY.md](PRIVACY.md), [../SECURITY.md](../SECURITY.md) |
| Tests that keep the words honest | `LegalTextTest`, `OpenSourceNoticesTest` (`:domain`), `LegalPolicyTest` and `AccessibilityPolicyTest` (`:app`) |

When the text changes in meaning, bump `LegalInfo.TEXT_VERSION` and `LAST_UPDATED`, and update PRIVACY.md.

## Decisions the owner must make

1. **Licence of the code.** The repository has no LICENSE file. Choosing one (MIT, Apache 2.0, GPL, proprietary, ...) is the owner's; nothing here picks it. `NOTICE` lists only third-party items.
2. **Exact operator name and address.** `LegalInfo.OPERATOR_NAME` is "Prashanth Sridhar" (the repository owner) and `OPERATOR_ADDRESS` is empty, so the page shows no address. Both carry a `TODO(owner)`. A real legal name, a postal address, and whether to publish them is a legal question for the owner and a lawyer.
3. **Governing law and where disputes are heard.** Not stated in the terms. A lawyer should decide.
4. **Distribution.** See Play Store below.

## Checklist items

| Item | Status |
|---|---|
| Privacy notice | In the app and in PRIVACY.md. Describes data, hosts, permissions, control. Not reviewed by a lawyer |
| Live train status source | Opt-in and off by default. There is no official key-less Indian Railways running-status API, so the app asks one unofficial service (`api.confirmtkt.com`) that has no agreement with this project; its terms may forbid automated use and it may change or block without notice. The reply format and the host are not verified against the live service. Sent: a train number and the run's start day only. Listed in `OpenSourceNotices` and `LegalText`; if the owner prefers, remove the switch or point `TrainFetcher` at a licensed source (one host, one file) |
| Train source probe (developer only) | `TrainProbe` asks a short list of unofficial train-status sites (`www.confirmtkt.com`, `www.railyatri.in`, `www.ixigo.com`, `enquiry.indianrail.gov.in`, `www.trainman.in`, `runningstatus.in`, besides `api.confirmtkt.com`) to find a working source. Developer probe only, nothing is sent unless the developer taps Probe, only the sample train number; shown with developer options on, never in Demo mode, never used by the live train feature. None has an agreement with this project and their terms may forbid automated use. The list is cut down to the winner and the class removed afterwards. Listed in `OpenSourceNotices` and `LegalText` |
| Terms of use | In the app: as is, not advice, may be wrong, SMS features at own risk and carrier charges, share codes only with people you trust, third-party terms, limit of liability, changes. Not reviewed by a lawyer; limits of liability are not always enforceable |
| Minimum age | 18 and over, stated; the app asks for no age. No data about children is collected on purpose. The app has no age check |
| Consent at the point of use | "See what is sent" next to code sharing and balance check; one line with a link on the first-run Welcome screen ("By continuing you agree to the Terms and Privacy"), not a blocking screen. Android's own permission prompts are the consent for each permission. Whether a click-through is enough in a given country is a legal question |
| Data subject rights (access, deletion) | The app keeps the person's data on their phone; Export and Delete all my data are in Settings. The publisher holds no copy, so there is nothing to look up. Copies in Google Drive or Android Auto Backup are the person's own Google account |
| Open-source notices | Licences page and NOTICE. Licence names are to our best knowledge; the projects' own licence files win. The Google Play services licence and the Material Icons attribution should be confirmed |
| Cookies | Not applicable. The app has no website and sets no cookies, so there is no cookie banner or policy. One exception is described in the page: the in-app Google Images page (only when the person taps "From the web") is Google's page, which may use its own cookies |
| Refunds | Not applicable: free, no purchases. Stated on the page |
| Fake or bought reviews | Not applicable: the app collects and shows no reviews |
| Email unsubscribe | Not applicable: the app sends no email and has no mailing list |
| Advertising, analytics, tracking | None. No ad SDK, no analytics, no crash reporter |
| Accounts | None. Google sign-in is used only if the person turns on Drive backup |
| Accessibility | Pass done by reading the code, not with TalkBack on a device: see the commit and `AccessibilityPolicyTest`. Not a conformance claim for any standard |

## Play Store specifics

`READ_SMS`, `RECEIVE_SMS` and `SEND_SMS` are *restricted permissions* on Google Play. An app may use them only if it is the phone's default SMS handler or meets a narrow listed exception, and must pass a permissions declaration review. A finance app that reads the inbox (SMS and MMS) and sends texts (code sharing, balance check) is unlikely to qualify as it stands. Treat this app as **sideload only** (direct install, GitHub releases). A Play build would need a different feature set (no `SEND_SMS`, and a review of `READ_SMS`), a privacy policy URL hosted on the web, and a Data safety form. None of that is done here.

## Not done

- No lawyer review; no claim of compliance with any law (India's DPDP Act, GDPR, the IT Act, RBI or TRAI rules, or any other).
- No privacy policy hosted on a web page (only the in-app text and this repository).
- No data-protection impact assessment, records of processing, or breach procedure.
- Sending an SMS to a third party (code sharing) may have consent and telecom-rule consequences for the person who adds the contact; the terms ask the person to share only with people they trust and have the right to share with, but this is not a legal clearance.

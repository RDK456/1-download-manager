# Translating

The Android app can be translated. So far only part of it is ready for that.

## What can be translated today

- Navigation tabs and screen titles.
- The notification channel names and their Pause all / Resume all buttons.

Everything else is still written in English inside the code. The Windows app and the
browser extension are English only.

## Add a language

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<code>/strings.xml`
   (`values-hi`, `values-ta`, `values-de`, ...).
2. Translate the text between the tags. Leave each `name` as it is.
3. Delete `app_name` from your copy: the app keeps one name.
4. Open a pull request. Say that you speak the language, or who checked it.

Machine translations that nobody who speaks the language has read are not accepted.

## Make more of the app translatable

Move a screen's text into `values/strings.xml` and read it with `stringResource(...)`, one
screen per pull request.

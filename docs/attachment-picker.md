# Android attachment picker prototype

This feature is independent of upstream PR #15. Initial device validation used a build including that v3 compatibility fix; the pull request contains only the attachment picker.

## Use from WhatsApp

1. Sign in using the companion’s existing launcher screen.
2. In a WhatsApp chat choose **Attach → Gallery → Recents → More apps → Immich Photos**.
3. Browse recent photos or enter a smart-search query and press **Search**.
4. Tap one result. The original downloads and opens in WhatsApp’s photo preview.
5. Review the recipient and send manually, or cancel.

This adds an external gallery choice; it does not replace Android’s system picker or WhatsApp’s first gallery screen. No system-picker activation flag or special ADB allowlist is needed for this attachment activity.

## Implementation

- Reuses the companion’s existing account and authenticated HTTP client.
- Uses the companion’s Material 3 theme: filled indigo Search, outlined Load more and a compact Cancel text button.
- Registers GET_CONTENT and PICK for image MIME types and the legacy `vnd.android.cursor.dir/image` contract needed for WhatsApp discovery.
- Uses `/search/metadata` for recent photos, `/search/smart` for queries, 30 results per page and Load more.
- Downloads thumbnails with four concurrent workers; downloads the original only after selection.
- Returns a FileProvider content URI, MIME type, ClipData and a read-only URI grant. The provider is not exported, and only its attachments cache subtree is exposed.
- Random filenames avoid server filename path traversal. Originals are limited to 100 MiB. Failed or cancelled downloads are removed. Completed cache entries older than 24 hours are pruned on the next selection.
- No public release key or account credentials are stored in the repository.

## Validation on Pixel 8 / GrapheneOS

- Debug APK updates the existing PR15 test companion without losing its login.
- Android resolves the new image selection activity.
- WhatsApp initially omitted image/*-only registration. Adding the legacy image-directory contract made **Immich Photos** appear under More apps.
- Searching **ginger cat** returned matching cat thumbnails.
- Selecting a result opened `com.whatsapp/.mediacomposer.ui.app.MediaComposerActivity`, showing the photo, caption and Send controls.
- Cancelled the preview. No message or photo sent.
- `assembleDebug lintDebug` passed. See build reports locally; existing upstream warnings remain.

## Prototype scope / remaining work

Single photos only. Multi-selection, videos, albums, rotation/process-death state restoration, richer loading UI and comprehensive network-error testing remain future work. Search can return semantically similar photos. Only this WhatsApp/Pixel flow has been validated; other apps need testing.

The previously considered modern system picker activation is unrelated to this activity and remains unavailable via the phone’s ADB flag allowlist.

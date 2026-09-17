# Welcome to your Encyclopedia

This library lives in **local storage**, not inside the app package — every
file here is editable, renamable, deletable, importable and shareable.

## What you can do

- Tap a card to open it in the **full-view reader**
- Tap ✎ to **edit** — with live preview and a formatting toolbar
- Tap **＋** on the library screen to create a new note or import `.md` files
- Tap **⋮** on a card to rename, share, or delete a file

## Task list

- [x] Load markdown from local storage instead of assets/
- [x] Editable notes with live preview
- [ ] Add your own modules

## Inline styles

Supports **bold**, *italic*, ~~strikethrough~~, `inline code`, and
[tappable links](https://developer.android.com).

## Code blocks

```kotlin
fun greet(name: String) {
    println("Hello, $name!")
}
```

## Tables

| Feature | Status |
|---|---|
| Local storage | ✅ |
| Live preview | ✅ |
| Import / export | ✅ |

> Tip: swipe back or tap ← any time — the editor will ask before discarding
> unsaved changes.

---

Everything above lives in `00_welcome.md` inside the app's private storage
folder, so feel free to edit or delete this file once you're comfortable.

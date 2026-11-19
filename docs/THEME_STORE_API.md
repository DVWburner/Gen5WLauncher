# Gen5W Theme Store HTTP / JSON API Specification (`https://market.ioniqthemes.com/`)

This document defines the HTTP and JSON contract used by **Gen5W Studio** when uploading single themes and browsing/downloading themes from `https://market.ioniqthemes.com/`.

All remote endpoints and download/thumbnail URLs **must use HTTPS** in production (local `http://localhost` / `http://127.0.0.1` is accepted by Studio for local development).

---

## 1. Browse & Search Themes Catalog

### Request

Studio queries the catalog endpoint in this order:

1. `GET /api/v1/themes?sort=newest[&category=<Category>][&q=<SearchQuery>]`
2. Fallback if `GET /api/v1/themes` returns `404 Not Found`: `GET /?sort=newest[&category=<Category>][&q=<SearchQuery>]`

Query parameters:

| Parameter | Required | Description |
|---|---|---|
| `sort` | Yes | Always `newest` (sort by `updatedAt` DESC, then `createdAt` DESC, then `revision` DESC). |
| `category` | No | Exact category name (omitted when the user picks **All Categories**). |
| `q` | No | Case-insensitive free-text query matching theme `name`, `author`, `id`, `description`, `tags`, or `compatibleVersions`. |

> **Client-side guarantee:** Gen5W Studio also filters by `category` / `q` and enforces **Date Newest First** ordering client-side so even a static catalog JSON file hosted at `/` or `/api/v1/themes` works cleanly.

### Response JSON (`200 OK`, `Content-Type: application/json`)

Studio accepts `{ "themes": [...] }` (recommended), `{ "items": [...] }`, `{ "data": [...] }`, or a bare JSON array `[...]`.

```json
{
  "apiVersion": 1,
  "generatedAt": "2026-11-18T16:00:00Z",
  "categories": [
    "EV & Telemetry",
    "Minimal",
    "OEM+",
    "Sports / Performance",
    "Animated",
    "Anime / Gaming",
    "Nature",
    "Dark / AMOLED",
    "Utility",
    "Other"
  ],
  "total": 1,
  "themes": [
    {
      "id": "autumn-ronin-ev",
      "name": "Autumn Ronin Telemetry",
      "author": "Tapple",
      "version": "1.2.0",
      "revision": 4,
      "category": "Anime / Gaming",
      "description": "Widescreen 1920x720 EV battery + live pedal torque rings over autumn artwork.",
      "minAppVersion": "0.3.4",
      "compatibleVersions": ["0.3.4+"],
      "formatVersion": 2,
      "showSystemBar": true,
      "createdAt": "2026-11-10T14:20:00Z",
      "updatedAt": "2026-11-18T15:30:00Z",
      "downloadUrl": "https://market.ioniqthemes.com/downloads/autumn-ronin-ev_v1.2.0_r4.zip",
      "thumbnailUrl": "https://market.ioniqthemes.com/thumbnails/autumn-ronin-ev_v1.2.0_r4.png",
      "fileSizeBytes": 2480120,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "downloads": 142,
      "tags": ["ev", "ioniq", "telemetry", "grid"]
    }
  ]
}
```

#### Field Reference (`StoreThemeListing`)

| Field | Type | Description |
|---|---|---|
| `id` | `string` | URL-safe theme slug (`a-z0-9-_`). |
| `name` | `string` | Human-readable theme title. |
| `author` | `string` | Creator handle or name. |
| `version` | `string` | Theme semantic version (e.g. `"1.2.0"`). |
| `revision` | `integer` | Monotonic integer revision (`>= 1`). |
| `category` | `string` | Primary category label for dropdown filtering. |
| `description` | `string?` | Short description or release notes shown on the Store card. |
| `minAppVersion` | `string?` | Minimum Gen5W Launcher / Studio software version (e.g. `"0.3.4"`). |
| `compatibleVersions` | `string[]?` | List of supported software versions (e.g. `["0.3.4+", "0.3.5"]`). |
| `formatVersion` | `integer` | Layout schema version (`2`). |
| `showSystemBar` | `boolean?` | Whether the theme expects the 90px top system bar (`true`) or fullscreen (`false`). |
| `createdAt` | `string` | ISO-8601 UTC timestamp of initial release. |
| `updatedAt` | `string` | ISO-8601 UTC timestamp of latest update (primary newest-first sort key). |
| `downloadUrl` | `string` | HTTPS URL (or path relative to base URL) serving the verified single-theme `.zip`. |
| `thumbnailUrl` | `string?` | HTTPS URL (or path relative to base URL) serving the `960x360` PNG preview image. |
| `fileSizeBytes` | `integer?` | Uncompressed/package byte count displayed on card. |
| `sha256` | `string?` | Lowercase hex SHA-256 of the `.zip` archive; Studio rejects mismatched downloads when present. |
| `downloads` | `integer?` | Optional download counter. |
| `tags` | `string[]?` | Optional search keywords. |
| `isOwner` | `boolean?` | Included as `true` only when a valid `Authorization: Bearer <token>` is sent on `GET` and the account owns this theme; omitted/`null` otherwise. Stale/invalid Bearer headers on `GET` fall back to anonymous `200 OK` without `isOwner`. Responses include `Cache-Control: private, no-store` and `Vary: Authorization`. |

---

## 1B. Creator Authentication (`username` + `password`, Bearer Token)

Accounts use `username` (`3–32` chars `[a-zA-Z0-9_-]`, unique case-insensitively) and `password` (`8–128` chars) only — no email, password reset, password-change, or server-side logout endpoints. Account `username` is private and never exposed on public listings (`metadata.author` remains cosmetic).

- `POST /api/v1/auth/register` (`{ "username", "password" }`) -> `201 Created` returning `{ "ok": true, "token", "tokenType": "Bearer", "expiresAt", "account": { "username", "createdAt" } }`, or `400`, `409`, `503` (with `Retry-After` seconds).
- `POST /api/v1/auth/login` (`{ "username", "password" }`) -> `200 OK` returning the same token payload, or `401` / `429` (with `Retry-After` seconds).
- `GET /api/v1/auth/me` (`Authorization: Bearer <token>`) -> `200 OK` `{ "ok": true, "account": { "username", "createdAt" } }`, or `401 Unauthorized`.

---

## 2. Upload a Single Theme (`Upload to Theme Store`)

### Request

```http
POST https://market.ioniqthemes.com/api/v1/themes
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

Server verifies `metadata.id` matches `layout.json -> id` inside `.zip` (case-insensitively) and binds `theme.id` ownership to the uploading account (`403 Forbidden` if owned by another account, `409 Conflict` on duplicate `(id, version, revision)` or unavailable ID).

Multipart form parts sent by Gen5W Studio:

| Part Name | Content-Type | Filename | Contents |
|---|---|---|---|
| `package` | `application/zip` | `<id>_v<version>_r<revision>.zip` | Single-theme `.zip` archive verified by Studio's security scanner. |
| `thumbnail` | `image/png` | `thumbnail.png` | `960x360` rendered PNG screenshot of the theme stage (without editor overlays). |
| `metadata` | `application/json` | — | JSON object matching `manifest.json` -> `theme` metadata (author, version, revision, category, compatibility, dates, counts). Note: `categories` returned by `GET /api/v1/themes` drives Studio's category dropdowns for both browsing and uploading. |

#### `metadata` JSON Payload Example

```json
{
  "id": "autumn-ronin-ev",
  "name": "Autumn Ronin Telemetry",
  "author": "Tapple",
  "version": "1.2.0",
  "revision": 4,
  "category": "Anime / Gaming",
  "description": "Widescreen 1920x720 EV battery + live pedal torque rings.",
  "minAppVersion": "0.3.4",
  "compatibleVersions": ["0.3.4+"],
  "formatVersion": 2,
  "showSystemBar": true,
  "speedUnit": "mph",
  "tempUnit": "F",
  "createdAt": "2026-11-10T14:20:00Z",
  "updatedAt": "2026-11-18T15:30:00Z",
  "thumbnail": "thumbnail.png",
  "fileCount": 14,
  "totalBytes": 2480120,
  "studioVersion": "0.3.4"
}
```

### Response JSON

Success (`200 OK` or `201 Created`):

```json
{
  "ok": true,
  "message": "Theme uploaded and queued/published successfully.",
  "theme": {
    "id": "autumn-ronin-ev",
    "name": "Autumn Ronin Telemetry",
    "author": "Tapple",
    "version": "1.2.0",
    "revision": 4,
    "category": "Anime / Gaming",
    "compatibleVersions": ["0.3.4+"],
    "createdAt": "2026-11-10T14:20:00Z",
    "updatedAt": "2026-11-18T15:30:00Z",
    "downloadUrl": "https://market.ioniqthemes.com/downloads/autumn-ronin-ev_v1.2.0_r4.zip",
    "thumbnailUrl": "https://market.ioniqthemes.com/thumbnails/autumn-ronin-ev_v1.2.0_r4.png",
    "sha256": "..."
  }
}
```

Rejected / Error (`4xx` / `5xx`):

```json
{
  "ok": false,
  "error": "Upload rejected: blocked unsafe file in archive."
}
```

---

## 2B. Delete Owned Theme (`DELETE /api/v1/themes/{id}`)

```http
DELETE https://market.ioniqthemes.com/api/v1/themes/{id}
Authorization: Bearer <token>
```

- `200 OK`: `{ "ok": true, "message": "Theme deleted successfully." }`
- `401 Unauthorized`: missing or expired token.
- `403 Forbidden`: theme belongs to a different creator account.
- `404 Not Found`: theme does not exist (also returned for repeat `DELETE`, `GET /api/v1/themes/{id}`, `/downloads/*`, and `/thumbnails/*` after deletion). Re-uploading a previously deleted `id` returns `409 Conflict` (`{ "ok": false, "error": "That theme ID is unavailable. Please choose a different Theme ID." }`).

---

## 3. Single-Theme `.zip` Internal Structure & `manifest.json`

Every package created by **Gen5W Studio** contains **exactly ONE theme** at archive root (never nested multi-theme packs):

```text
autumn-ronin-ev_v1.2.0_r4.zip
├── layout.json
├── manifest.json
├── thumbnail.png          (960x360 PNG canvas screenshot)
├── img/                   (optional wallpapers / tile backgrounds)
├── icons/                 (optional .svg / .png icons)
├── media/                 (optional media / animated backgrounds)
├── anim/                  (optional tile animations)
└── fonts/                 (optional .ttf / .otf fonts)
```

### `manifest.json` Format

```json
{
  "manifestVersion": 1,
  "singleTheme": true,
  "theme": {
    "id": "autumn-ronin-ev",
    "name": "Autumn Ronin Telemetry",
    "author": "Tapple",
    "version": "1.2.0",
    "revision": 4,
    "category": "Anime / Gaming",
    "description": "...",
    "minAppVersion": "0.3.4",
    "compatibleVersions": ["0.3.4+"],
    "formatVersion": 2,
    "showSystemBar": true,
    "speedUnit": "mph",
    "tempUnit": "F",
    "createdAt": "2026-11-10T14:20:00Z",
    "updatedAt": "2026-11-18T15:30:00Z",
    "thumbnail": "thumbnail.png",
    "fileCount": 5,
    "totalBytes": 2480120,
    "studioVersion": "0.3.4"
  },
  "files": [
    { "path": "layout.json", "size": 12480, "sha256": "..." },
    { "path": "thumbnail.png", "size": 245110, "sha256": "..." },
    { "path": "img/wallpaper.jpg", "size": 2222530, "sha256": "..." }
  ]
}
```

---

## 4. Recommended Server-Side Security Verification Checklist

Gen5W Studio enforces all of the rules below before uploading and before extracting any downloaded `.zip`. Your web server should perform the exact same checks on every `POST /api/v1/themes` upload:

1. **Single-Theme Rule:** Reject archives missing root `layout.json` or containing any additional `layout.json` in subdirectories.
2. **ZIP-Slip & Symlink Prevention:** Reject absolute paths, drive letters, `..` segments, backslashes normalizing outside root, and symlinks (`(ExternalAttributes >> 16) & 0xF000 == 0xA000`).
3. **Size & Entry Caps:** Enforce max `2500` entries, max `25 MB` per entry, and max `150 MB` total uncompressed size (stream-read with running byte counter to defeat zip bombs).
4. **Strict Directory & Extension Whitelist:**
   - Root allowed files: `layout.json`, `manifest.json`, `thumbnail.png`, `thumbnail.jpg`.
   - Allowed asset subdirectories: `icons/`, `img/`, `media/`, `anim/`, `fonts/` only.
   - Allowed asset extensions: `.svg`, `.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.ttf`, `.otf` (plus valid `anim/<clip>/frames.json` manifests inside `anim/`).
   - Explicitly reject executables, libraries, scripts, APKs, Java bytecode, HTML, arbitrary `.json`, and nested archives (`.exe`, `.dll`, `.bat`, `.cmd`, `.ps1`, `.sh`, `.apk`, `.jar`, `.dex`, `.so`, `.js`, `.py`, `.php`, `.html`, `.zip`, etc.).
5. **Magic-Byte Header Validation:**
   - Reject executable/archive signatures (`MZ`, `\x7fELF`, `#!`, `PK\x03\x04`) even if renamed to `.png` / `.jpg` / `.ttf` / `frames.json`.
   - Verify actual headers (`89 50 4E 47` for PNG, `FF D8 FF` for JPEG, `GIF8` for GIF, `RIFF....WEBP` for WebP, TTF/OTF sfnt signatures).
6. **SVG Script / Event Stripping Check:**
   - Parse `.svg` as XML cleanly (forbidding DTD/entity declarations) and reject `<script>`, `<foreignObject>`, `<iframe>`, `<object>`, `<embed>`, any `on*` event handler attribute, and `javascript:` / `vbscript:` / `data:text/html` URIs.
7. **Layout Actions:**
   - Gen5W head units run with HMG-API and root access, so `root_start`, `root_service`, and `root_broadcast` actions in `layout.json` are permitted (must still be valid `layout.json` JSON).
8. **Authoritative Server Hash:**
   - Ignore client-supplied archive size/hash hints and compute the authoritative `sha256` & `fileSizeBytes` on the server after verification.

# Solibri REST Automation API — verified reference

Base URL: `http://localhost:10876/solibri/v1`
Verified against **Solibri 26.4.1.108** (product "Office"), 2026-05-21, by route extraction
from `solibri.jar` (Spring controllers) + live probing. The server is a Spring Boot app;
there is **no** OpenAPI/swagger endpoint served.

Legend: ✅ live-verified · ⚠️ exists, contract unresolved · 🔴 mutates session/UI · ⏳ slow

## Endpoints

| Verb | Path | Params / Body | Returns | Notes |
|---|---|---|---|---|
| GET | `/ping` | — | `{answer:"pong"}` | ✅ health |
| GET | `/about` | — | `{version, product}` | ✅ |
| GET | `/status` | — | `{filename, status, busy, timestamp, dateTime}` | ✅ status=SAVED/MODIFIED |
| GET | `/models` | — | `[{name, metadata, uuid}]` | ✅ sub-models |
| POST | `/models` | IFC body, `?name=` | ModelInfo | 🔴 import IFC |
| PUT | `/models/{uuid}/update` | IFC body | — | 🔴 replace model |
| POST | `/models/{uuid}/partialUpdate` | IFC body | — | 🔴 |
| POST | `/models/{uuid}/deleteComponents` | IfcGuidList | — | 🔴 |
| GET | `/models/{uuid}/components` | `?filter=<ComponentFilter>` | IfcGuidList | ⚠️ 500 without a valid filter; ComponentFilter is hard to express via query string — prefer SDK or read IFC directly |
| GET/PUT | `/models/{uuid}/metadata/{key}` | — | metadata | model metadata |
| POST | `/checking` | `?` (RequestParam, optional) | CheckingResultList | 🔴⏳ runs ruleset(s); GET is **405** |
| GET | `/bcfxml/{version}` | version e.g. `two_one`; `?` | `{content: base64(BCF zip)}` | ✅ export issues; `two_one` confirmed |
| GET | `/selectionBasket` | — | `["<ifcGuid>", ...]` | ✅ |
| POST | `/selectionBasket` | `["<ifcGuid>", ...]` | — | 🔴 sets UI selection |
| GET | `/threed/camera` | — | CameraState | ✅ |
| POST | `/threed/camera` | CameraState | — | 🔴 moves camera |
| POST | `/presentations` | `{name, prefix}` | `{id}` | 🔴 create issue container |
| POST | `/slides` | SlideRequest | — | 🔴 add issue/slide |
| POST | `/info/{guid}` | path guid | — | 🔴 show info panel |
| GET | `/information` | — | text/plain | ⚠️ 500 even with selection — contract unresolved |
| POST | `/parametricInformation` | `?componentGuid=`, body | text/plain | ⚠️ 400 — body format unresolved |
| POST | `/project` | SMC body / `?path=` | ModelInfo | 🔴 open project |
| POST | `/project/persist` | — | — | 🔴 save |
| POST | `/project/close` | — | — | 🔴 close |
| POST | `/shutdown` | `?` | — | 🔴 quits Solibri |

## Response shapes (from REST model classes)

- **Status**: `filename, status, busy, timestamp, dateTime`
- **ModelInfo**: `name, uuid, metadata, class`
- **CheckingResult**: `name, description, severity, location, uniqueKey`  → CheckingResultList `{results:[...]}`
- **CameraState**: `projection (PERSPECTIVE/…), location{x,y,z}, direction{x,y,z}, upDirection{x,y,z}, fieldOfView, viewToWorldScale`
- **SlideRequest**: `presentationId, title, description, components (IFC GUIDs), status, stage, priority, responsibilities, dueDate, labels, modifiedTimeInExternalSystem, type`
- **PresentationRequest/Response**: `{name, prefix}` → `{id}`
- **AboutResponse**: `version, product`
- **EncodedBinary** (BCF): `{content: <base64>}`

## What the API CANNOT do

- **List, load, or write rulesets** — no endpoint. Read ruleset metadata from `.cset`/`.smc`
  files instead (see `solibri_loop/cset.py`); author rules via the Solibri SDK or UI.
- **GET checking results** — `/checking` is POST-only (405 on GET). Results come back in the
  POST response (CheckingResultList) and/or via the BCF export.
- **Query components by property over REST** — the components filter is a `ComponentFilter`
  object that doesn't round-trip cleanly through a query string. For property-level queries,
  read the IFC with IfcOpenShell (pure Python) or use the SDK.

## File formats (read with `solibri_loop/cset.py`)

`.cset` (ruleset) and `.smc` (project) are both `ZIP { Model<n>.smc = Java-serialized
object graph (magic 0xACED0005), <version> }`. Ruleset names, `.cset` filenames, rule
titles and `com.solibri.*` rule classes are plain strings in the serialized blob.

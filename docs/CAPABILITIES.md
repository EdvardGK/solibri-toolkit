# Solibri Capability Map

Generated 2026-05-30 from a parallel SDK survey (7 regions, 334 classes, 132 endpoint candidates, 28 workflow ideas).

Canonical index of what Solibri exposes (public SDK + internal API) and what this toolkit covers today. **No silos**: build any gap into the toolkit; see feedback memories `feedback-superuser-scope` and `feedback-move-fast-when-reversible`.

## Channels

- **Plugin (port 10999)** — in-JVM HTTP server we own (`SuperuserServer.java`), starts on every Solibri launch, self-sufficient since v7. Primary interface. See [SUPERUSER.md](SUPERUSER.md).
- **Built-in REST (port 10876)** — Solibri's stock Spring server, only up with `--rest-api-server-*` flags. Legacy. See [SOLIBRI_API.md](SOLIBRI_API.md).
- **Out-of-band tools** — IfcOpenShell directly on IFC for analysis Solibri's API can't do (e.g. `cli.py ito PATH`).

## Coverage summary

| Region | Classes | Internal gems | Wired | Endpoints to add | Workflow ideas |
|---|--:|--:|--:|--:|--:|
| `project-lifecycle` | 28 | 11 | 11 | 20 | 4 |
| `model-components` | 83 | 5 | 9 | 19 | 4 |
| `checking-rules` | 36 | 17 | 6 | 16 | 4 |
| `information-takeoff` | 37 | 6 | 0 | 14 | 4 |
| `threed-layouts-views` | 41 | 4 | 3 | 14 | 4 |
| `coordination-bcf-presentations` | 14 | 4 | 4 | 17 | 4 |
| `selection-settings-user-internal` | 95 | 8 | 13 | 32 | 4 |

## Build queue (all 132 endpoint candidates, sorted by priority)

Each row = a proposed plugin endpoint backed by an SDK call. Build by adding the handler to [`SuperuserServer.java`](../../../dev/solibri-bim-tools/java/src/main/java/no/skiplum/solibri/superuser/SuperuserServer.java), mirroring in `solibri_loop/superuser.py`, and exposing as `cli.py su <cmd>`.

| Pri | Method | Endpoint | Purpose | SDK call | Region |
|---|---|---|---|---|---|
| high | GET | `GET /skiplum/v1/results/full` | Return Result with category, decision, models, custom key, component-level slides + decisions (today's /results truncates to 7 fields) | `Checking.getResults() then Result.getDecision/getCategory/getCategories/getInvolvedModels/getComponentDecisions/getComponentSlides` | checking-rules |
| high | GET | `GET /skiplum/v1/rules` | Flat list of all Rule nodes across rulesets with name, helpUrl, parameters (id, type, value, options) - real headless rule inspection without toJson() | `Ruleset/Rule.getChildren + RuleParameters.of(rule).asList() + RuleParameter.getId/getValue` | checking-rules |
| high | GET | `GET /skiplum/v1/rules/{ruleName}/components` | Per-rule passed/failed/checked component sets with filters (decision, severity) | `Checking.getCheckedComponents/getPassedComponents/getFailedComponents(Rule, Decision, Severity)` | checking-rules |
| high | GET | `GET /skiplum/v1/score` | Return latest Score snapshot for the current project (project/rule coverage + counts) plus history range | `ScoreHandler.getInstance.collectCheckingInfo + ScoreHandler.getScores(uuid, industry, from, to)` | checking-rules |
| high | POST | `POST /skiplum/v1/presentations/fromRuleset` | Generate one Presentation full of slides from a ruleset's results (auto-coordination deck) | `ConvertToPresentationAction.convertToPresentation(...) or CreatePresentationFromRulesetTask` | checking-rules |
| high | POST | `POST /skiplum/v1/report` | Write a checking report to disk - mirrors File > Write Report | `Reporting.createReport(ReportingSettings, filter, file, ReportFiller, …) via CheckingPlugin.getReportFiller()` | checking-rules |
| high | POST | `POST /skiplum/v1/results/{uniqueKey}/decision` | Set Decision (ACCEPTED/REJECTED/UNDEFINED) on a Result; closes the loop on QA workflows | `Result.setDecision(Decision)` | checking-rules |
| high | POST | `POST /skiplum/v1/rules/{ruleName}/parameters` | Patch RuleParameter values headlessly (replaces opening .cset, editing in UI, re-saving) | `RuleParameter.setValue(T) via RuleParameters.of(rule); fall back to UpdateRuleParametersTask for batch` | checking-rules |
| high | POST | `POST /skiplum/v1/rulesets/openFile` | Load a .cset / .smcx ruleset (or .ids) without UI prompts | `OpenRuleSetTask / OpenIdsRuleSetTask (internal) or CheckingControllerInterface.openIdsModel(Resource, boolean)` | checking-rules |
| high | POST | `/bcfxml/export` | BCF export with explicit version (V1_0/V2_0/V2_1/V3_0) AND scope (ALL/SELECTED/MARKED) - currently only ALL/V2_1 reachable. | `BcfXml.exportBcfXml(version, scope, OutputStream)` | coordination-bcf-presentations |
| high | GET | `/presentations/list` | Enumerate presentations with id/name/slide-count. | `SMC.getPresentations()` | coordination-bcf-presentations |
| high | GET | `/presentations/{id}/slides` | Return all slides in a presentation with full metadata (status, stage, priority, labels, responsibilities, dueDate, components, comments, viewpoint count). | `Presentation.getSlides() + Slide.get*` | coordination-bcf-presentations |
| high | POST | `/slides/from-results` | Bulk-create slides from a set of checking Result UUIDs - links the Result, copies components, sets title from Result name. | `Checking.getResults + Slide.create + Slide.addComponent + Presentation.addSlide` | coordination-bcf-presentations |
| high | GET | `/slides/{id}` | Single slide deep-read including Viewpoints (camera + PNG bytes base64) and Comments. | `Slide.getViewpoints/getComments/getCheckingResult` | coordination-bcf-presentations |
| high | PUT | `/slides/{id}` | Patch any subset of Slide fields (status, stage, priority, labels, responsibilities, dueDate, components by GUID, externalIds). | `Slide.set* family` | coordination-bcf-presentations |
| high | POST | `/slides/{id}/comments` | Add a Comment to a slide or specified viewpoint (text, author, creationDate). | `Comment.create()/setText/setAuthor + Slide.addComment or Viewpoint.addComment` | coordination-bcf-presentations |
| high | POST | `/slides/{id}/viewpoints` | Create a Viewpoint from current 3D camera + screenshot, attach to slide. | `Viewpoint.create()/setCameraState/setImagePngBytes + Slide.addViewpoint` | coordination-bcf-presentations |
| high | GET | `/ito` | List ItoDefinitions in the open project (id, name, description, column count). | `SMC.getInformationTakeoff().getItoDefinitions()` | information-takeoff |
| high | POST | `/ito` | AUTHOR a new ItoDefinition from JSON (name, description, columns:[{type, pset/prop/classification/identification, operator, grouping, ascending, formula?, javascript?}], filters[]); register into the project. | `new ItoSettings(name); for each col create the right ItoColumn subclass (PropertySetColumn, ClassificationColumn, etc.); ItoSettings.addColumn; ItoSettings.setFilters; ItoPlugin.getInstance().addItoSettings(s, true)` | information-takeoff |
| high | GET | `/ito/{id}` | Detail of one ITO: columns (name, type, operator, grouping, scripted/formula flags), filter summary, row count. | `Find SMCItoDefinition by UUID, then read backing ItoSettings.getColumns() + getColumnIndex + getRows()` | information-takeoff |
| high | POST | `/ito/{id}/calculate` | Force recalculation of one ITO (or all when id=*); blocks via ItoPlugin.waitForTakeoff(). | `SMC.getInformationTakeoff().calculate(def) or calculateAll(); then ItoPlugin.getInstance().waitForTakeoff()` | information-takeoff |
| high | POST | `/ito/{id}/presentation` | Generate a Presentation from an ITO (slides per row, linked components, auto-zoom). | `PresentationFromItoParameters p; p.setIto(s); p.setTitleColumn(col); p.setAutoZoom(true); new PresentationFromITOWorker(p).run()` | information-takeoff |
| high | POST | `/ito/{id}/report` | Export an ITO to Excel; body or query specifies template (optional) + output path; writes default template if none. | `new ItoReport(template?, itoSettings).save(null, outFile) or writeDefaultTemplateForSingleITO + save` | information-takeoff |
| high | GET | `/ito/{id}/rows` | Stream calculated rows as JSON (components + cell values). | `ItoSettings.getRows() + ItoRow.getComponents()/getValue(i)` | information-takeoff |
| high | GET | `/components/byClass?cls=Door\|Window\|Space\|Stair\|...` | Enumerate typed components with their type-specific attributes (door operationType+widthxheight, window partitioning, stair flight slope, space net areas). | `model.getComponents(ComponentFilter.ACCEPT_ALL, Door.class) etc.` | model-components |
| high | GET | `/components/near?guid=&pad=0.5` | Spatial neighbours of a component (AABB intersection with padding) - powers per-clash 'show context elements'. | `model.getComponents(AABBIntersectionFilter.ofComponentBounds(target, pad, pad))` | model-components |
| high | GET | `/components/{guid}` | Full component dump (all psets/props, materials, hyperlinks, relations, quantities, bbox, predefined+resolved types). | `model.getComponentByGuid(guid).get() -> serialize getPropertySets / getMaterials / Quantities.of(c).get(*) / getRelated(Relation.of(...)) / getHyperlinks` | model-components |
| high | GET | `/components/{guid}/mesh` | Stream the triangle mesh of one component as GLTF/OBJ for downstream rendering or external clash overlay. | `component.getTriangleMesh() then iterate toTriangleCollection()` | model-components |
| high | GET | `/components/{guid}/quantities` | Return all 23 Quantities.Type values (GROSS_AREA, MINIMUM_HEIGHT, FRAME_LENGTH, etc.) without round-tripping through psets. | `Quantities q = Quantities.of(c); for (Type t : Type.values()) q.get(t)` | model-components |
| high | GET | `/components/{guid}/related?type=BOUNDED_BY&dir=FORWARD` | Walk the Component graph (CONTAINS, FILLS, VOIDS, BOUNDED_BY, NEAREST_SPACES, SERVES, NESTS, DECOMPOSES, DEFINES_BY_TYPE...) - foundation for any topology-aware automation. | `component.getRelated(Relation.of(Relation.Type.valueOf(type), Relation.Direction.valueOf(dir)))` | model-components |
| high | POST | `/filter` | Take a JSON filter DSL (pset/prop/value/op + componentType + spatial AABB + storey) and return the GUID set - lets any external app build complex queries without REST sprawl. | `Compose ComponentFilter.all(propertyValueMatches(...), componentTypeIs(...), AABBIntersectionFilter.of(box))` | model-components |
| high | GET | `/spaces` | List all Space components with name/numberText/zone, perimeter, net areas and boundary breakdown - the spine of any space program ITO. | `model.getComponents(ACCEPT_ALL, Space.class) and serialize Space getters` | model-components |
| high | GET | `/keywords` | List every Robot Framework keyword in the JVM with docstring and args - free, version-correct discovery of capabilities. | `new SMCRobotLibrary().getKeywordNames() + getKeywordDocumentation(name) + getKeywordArguments(name)` | project-lifecycle |
| high | POST | `/models/update` | Replace an existing sub-model with a fresh file (?name=&path=) - 'refresh a discipline IFC' without losing rule state or annotations. | `new ModelRobot().updateModel(name, path)` | project-lifecycle |
| high | GET | `/projects/info` | Rich project descriptor: file, fileStatus, lastSaveTime, industry, product, productVersion, isModified, projectResource URI/provider. | `SMC.getProject() + SmcProjectImportProxy.isProjectModified() + SmcProjectImportProxy.getProjectResource() + SMC.getProduct()` | project-lifecycle |
| high | POST | `/projects/save` | Save the open project back to its current Resource (no path needed). Round-trips ACC/BIM360 sources too. | `SaveSMCModelAction.saveModel(SmcProjectImportProxy.getProject(), SmcProjectImportProxy.getProjectResource().get(), null)` | project-lifecycle |
| high | POST | `/projects/saveAs` | Save current session as a new .smc/.smcx at ?path= (the snapshot primitive we lack). Optionally ?markClean=1 to clear dirty flag. | `SaveAsSMCModelAction.saveAsModel(currentSModel, new File(path), null); optional SMCModelHandlingPlugin.getInstance().setModelModified(false)` | project-lifecycle |
| high | POST | `/batch/auto-update-models` | Background refresh of every model from its source path - 'pull latest IFCs' button | `new AutoUpdateModelsTask().run()` | selection-settings-user-internal |
| high | POST | `/batch/calculate-ito` | Recompute an Information Take-Off (real ITO, not our IFC stub) | `new CalculateItoTask(name).run()` | selection-settings-user-internal |
| high | POST | `/batch/open-ids` | Load a buildingSMART IDS as a ruleset | `new OpenIdsRuleSetTask(res, idsOpener).run()` | selection-settings-user-internal |
| high | POST | `/batch/open-ito` | Load a .ito template - prelude to /batch/calculate-ito | `new OpenItoTask(AutorunResource.createFile(FILE, f)).run()` | selection-settings-user-internal |
| high | POST | `/batch/open-ruleset` | Load a .cset (no built-in 10876 equivalent) | `new OpenRuleSetTask(res).run()` | selection-settings-user-internal |
| high | POST | `/batch/partial-update-model` | Delta update an IFC (fast re-check after small edits) | `new ModelRobot().partialUpdateModel(uuid, newPath)` | selection-settings-user-internal |
| high | POST | `/batch/presentation-from-ruleset` | Auto-author a slide-deck of issues per ruleset (one-click coordination meeting deck) | `new CreatePresentationFromRulesetTask().run()` | selection-settings-user-internal |
| high | POST | `/batch/report-bcf` | Write BCF v2.1 from current issues without going through built-in 10876 | `new BCFReportTask(AutorunResource.createFile(REPORT, file), 'two_one').run()` | selection-settings-user-internal |
| high | POST | `/batch/report-coordination` | Produce Solibri's flagship Coordination PDF/RTF report | `new CoordinationReportTask(template, out, settings).run()` | selection-settings-user-internal |
| high | POST | `/batch/report-ito` | Export ITO to XLS/CSV/etc. | `new ReportItoTask(name, format, template, out).run()` | selection-settings-user-internal |
| high | POST | `/batch/run` | Body = JSON pipeline (targets[].tasks[]); we instantiate BatchTasks and run BatchRun in-process - replaces autorun XML + saves an Solibri.exe spawn | `new BatchRun(name); target.addTask(...); run.addTarget(target); run.run()` | selection-settings-user-internal |
| high | POST | `/batch/update-model` | Refresh ONE model from a new IFC | `new UpdateModelTask(src, template, true).run()` | selection-settings-user-internal |
| high | POST | `/classifications/{name}/apply` | Re-evaluate one classification against the open model (fixes 'I edited the .classification on disk, re-classify everything') | `ClassificationPlugin.getInstance().applyClassifications(List.of(name))` | selection-settings-user-internal |
| high | GET | `/classifications/{name}/references` | Read entity->class map for a classification (powers GUID-keyed reports + external rollups in Python) | `target.getClassificationReferences()` | selection-settings-user-internal |
| high | POST | `/classifications/{name}/set` | Bulk-set a class on a list of IFC GUIDs (manual override flow) | `target.setClassification(Set<Entity>, className)` | selection-settings-user-internal |
| high | POST | `/skiplum/v1/layouts/switch` | Switch to a named layout (e.g. 'Coordination', 'Architecture Inspection'). | `SMC.getLayouts().switchToLayout(name)` | threed-layouts-views |
| high | GET | `/skiplum/v1/threed/snapshot` | PNG of current 3D viewport. | `SMC.get3D().get3DSnapshot() -> ImageIO.write(bi, 'png', os)` | threed-layouts-views |
| high | POST | `/skiplum/v1/threed/snapshot/by-guids` | Frame a list of IFC GUIDs (Camera.lookAt(Collection<Component>)) then return PNG; optional perspective/orthogonal + fov/scale. | `SMC.get3D().getCamera().lookAt(components); get3DSnapshot()` | threed-layouts-views |
| high | POST | `/skiplum/v1/threed/visibility` | Show/hide components by IFC GUID, or {showAll:true}. | `SMC.get3D().hideComponent/showComponent/showAll(); getVisibleComponents/getHiddenComponents for GET.` | threed-layouts-views |
| high | POST | `/skiplum/v1/threed/visualization` | Apply a transient color overlay: body {colors:[{argb, guids:[...]}], sections:[plane], footprints:[guid]}. Returns void. | `Visualization v = SMC.get3D().createVisualization(); v.addComponentsWithColor(...); v.addSection(Plane); SMC.get3D().setVisualization(v)` | threed-layouts-views |
| medium | POST | `POST /skiplum/v1/categories/{uniqueName}/decision` | Bulk-decide a whole ResultCategory tree | `ResultCategory.setDecision(Decision)` | checking-rules |
| medium | POST | `POST /skiplum/v1/check/cancel` | Abort an in-progress check from outside | `CheckingControllerInterface.requestStopCheckingAndWait(this, false)` | checking-rules |
| medium | POST | `POST /skiplum/v1/check/selection` | Check ONLY the current selection basket (current /check runs everything; runChecking(true) means the same but the toggle is opaque) | `CheckingControllerInterface.requestStartCheckingSelectedEntities() or CheckingPlugin.getCheckDesignBySelectionBasketAction` | checking-rules |
| medium | POST | `POST /skiplum/v1/results/{uniqueKey}/slide` | Promote a Result into a Slide (or attach an existing one) - basis for BCF authoring | `Result.setSlide(Slide) using SMC.getPresentations() builders` | checking-rules |
| medium | POST | `POST /skiplum/v1/score/store` | Persist a Score for the current checking state - underpins KPI dashboards | `ScoreHandler.storeScore()` | checking-rules |
| medium | GET | `/bcflive/coordination-values` | Allowed labels/priorities/stages/topic statuses/topic types/users from server schema - needed before authoring slides. | `BCFCoordinationValues getters` | coordination-bcf-presentations |
| medium | GET | `/bcflive/servers` | List configured live BCF servers (BIMcollab/BIMTrack/ACC). | `BcfApi.getBCFServerList + getCustomBCFServers` | coordination-bcf-presentations |
| medium | POST | `/bcflive/servers/{id}/auth` | Authenticate to a live BCF server; returns token/handle. | `BcfApi.authenticate(BCFServer)` | coordination-bcf-presentations |
| medium | GET | `/bcflive/slides` | Fetch slides from live server by UUID set; useful to merge ACC-side comments back into local model. | `BcfApi.getSlides(Set<UUID>)` | coordination-bcf-presentations |
| medium | GET | `/events/slides/stream` | SSE/long-poll stream of SlideModifiedListener callbacks (status changes, new comments, viewpoint added) - turns Solibri into a coordination event bus. | `Listeners.add(SlideModifiedListener) (internal)` | coordination-bcf-presentations |
| medium | GET | `/issues-view/selected` | Slides currently selected/marked in the Issues UI - powers 'act on what the user sees' flows. | `SMC.getInternalViews -> IssuesView.getSelectedSlides/getMarkedSlides` | coordination-bcf-presentations |
| medium | PUT | `/slides/{id}/components` | Replace/add/remove component set on a slide by IFC GUID list. | `Slide.setComponents/addComponent/removeComponent` | coordination-bcf-presentations |
| medium | POST | `/ito/load` | Load a saved .ito file from disk into the running session. | `OpenItoTask with AutorunResource over the .ito file (or open via ItoPlugin recommended-files path)` | information-takeoff |
| medium | DELETE | `/ito/{id}` | Delete an ITO from the project. | `ItoPlugin.getInstance().removeModel(itoSettings)` | information-takeoff |
| medium | POST | `/ito/{id}/columns` | Append a column to an existing ITO (e.g. add a JS-scripted formula column on the fly). | `ItoSettings.addColumn(ItoColumn.createColumn(propRef)) or new PropertySetColumn(pset, prop, Double.class)` | information-takeoff |
| medium | POST | `/ito/{id}/save` | Persist an ITO definition to a .ito file on disk. | `ItoSettings.setFile(File) then ItoPlugin.getFile + standard save path` | information-takeoff |
| medium | GET | `/ito/{id}/totals` | Return per-column totals (Number+formatted string) for an ITO. | `ItoSettings.calculateTotal(int) / calculateTotalAsString(int)` | information-takeoff |
| medium | GET | `/components/raycast?x=&y=&z=&dx=&dy=&dz=` | Pick the first component a ray hits (e.g. from camera through screen point) - LLM-driven 'click that wall' UX. | `model.getComponents(RayIntersectionFilter.of(Ray3d.create(origin, dir)))` | model-components |
| medium | GET | `/grids` | Plan grids (U/V/W axes with labels + sampled polylines) for drawing-friendly overlays. | `model.getGrids() and GridAxis.getLabel/getCurve` | model-components |
| medium | GET | `/materials` | Aggregate Material across components (layerSet, thickness, fraction, IFC type, priority). | `Iterate components, call getAllMaterials()` | model-components |
| medium | GET | `/models` | Full list of sub-models with metadata + IfcMetaData + sourceApplication + transformation matrix. | `SMC.getModels().getModels() then getIfcMetaData / getTransformationMatrix` | model-components |
| medium | POST | `/models/{uuid}/update` | Stream an IFC body to update or partial-update a sub-model in place (disruptive but key for incremental refreshes). | `models.updateIFCModel(uuid, inputStream) / partialUpdateIFCModel` | model-components |
| medium | GET | `/spaces/{guid}/boundaries` | Walls/doors/windows that bound a space + boundary area + physical/virtual flag. | `space.getSpaceBoundaries() -> SpaceBoundary.getRelatedBuildingElement, getArea, getType` | model-components |
| medium | GET | `/storeys` | Building storeys with elevation, federated vs owned component sets - feeds NS3451 ITO by floor. | `model.getComponents(ACCEPT_ALL, BuildingStorey.class) and getFederatedComponents()` | model-components |
| medium | POST | `/keywords/run` | Invoke any robot keyword by name (?name= body: JSON args) - escape hatch for internals we don't yet wrap. Gate behind ?yes=1. | `new SMCRobotLibrary().runKeyword(name, args, kwargs)` | project-lifecycle |
| medium | POST | `/models/close` | Close a single sub-model by name without closing the whole project. | `new ModelRobot().closeModel(name)` | project-lifecycle |
| medium | POST | `/models/partialUpdate` | Delta-update an existing sub-model (?name=&path=) - faster than full updateModel on large IFCs. | `new ModelRobot().partialUpdateModel(name, path)` | project-lifecycle |
| medium | POST | `/models/saveAs` | Save a sub-model to a new path (?name=&path=) - extract a single discipline back out of the project. | `new ModelRobot().saveAsModel(name, path)` | project-lifecycle |
| medium | POST | `/projects/closeNow` | Force-close without save prompt (current /projects/close calls closeProject() which can prompt). | `SMC.closeProjectNow()` | project-lifecycle |
| medium | GET | `/projects/scores` | Historical checking scores for the open project (?industry=, ?from=, ?to= ISO instants) - KPI feed for dashboards. | `SMC.getProject().getScores(industry, fromInstant, toInstant)` | project-lifecycle |
| medium | GET | `/recents/providers` | Group recents by ResourceProvider (FILES vs. BIM360/ACC) so the dashboard can show ACC traffic separately. | `RecentResource.getRecentResources(*) + Resource.getResourceProviderName()` | project-lifecycle |
| medium | POST | `/resources/download` | Download a remote Resource (?uri=&out=) to a local path - clean wrapper around BIM360/ACC mirror. | `ResourceUtils.writeToFile(Resource.parse(uri), new File(out))` | project-lifecycle |
| medium | POST | `/batch/auto-comment` | Bulk-comment issues by status (e.g. mark all CRITICAL as Awaiting Discipline) | `new AutoCommentIssuesTask(status, onlyNew).run()` | selection-settings-user-internal |
| medium | POST | `/batch/presentation-from-ito` | Build a Presentation that walks every ITO row (great for QA walkthroughs) | `new PresentationFromITOTask(params).run()` | selection-settings-user-internal |
| medium | POST | `/batch/presentation-update` | Refresh a saved presentation against the now-current model state | `new UpdatePresentationTask(res, name, true, true).run()` | selection-settings-user-internal |
| medium | POST | `/batch/report-resultsummary` | Status/score summary export (the GYR dashboard) - shipped to clients as the recurring report | `new ResultSummaryReportTask(template, out, prefix).run()` | selection-settings-user-internal |
| medium | POST | `/batch/report-xls` | Most flexible report writer with explicit include flags (text/snapshot/components/etc.) | `new WriteReportTask(list, settings, ...).run()` | selection-settings-user-internal |
| medium | POST | `/batch/save-as` | Save the open project under a new path (snapshot) | `new ProjectRobot().saveModel(path) or new SaveModelTask(res).run()` | selection-settings-user-internal |
| medium | POST | `/classifications/create` | Programmatically spawn a classification with a name + list of Entity classes, no template file needed | `ClassificationPlugin.getInstance().createClassificationIfNeeded(name, classes)` | selection-settings-user-internal |
| medium | POST | `/classifications/{name}/setColor` | Make a classification the active color-coding (drives 3D view colors) | `target.setColorClassification(true); plugin.updateModelClassifications(target)` | selection-settings-user-internal |
| medium | POST | `/skiplum/v1/info/show` | Open the Info panel for a given IFC GUID - drives a guided review. | `SMC.getInfoDisplayer().showInfo(component)` | threed-layouts-views |
| medium | GET | `/skiplum/v1/layouts` | List workspace tabs (names + split tree summary + per-LayoutView state). | `SMC.getLayouts().getLayouts() -> walk Layout.getSplits()` | threed-layouts-views |
| medium | GET | `/skiplum/v1/threed/camera/state` | Full camera state including projection type (perspective vs ortho) + fov / viewToWorldScale - richer than built-in /threed/camera which is geometry only. | `SMC.get3D().createCameraState() instanceof PerspectiveCameraState/OrthogonalCameraState` | threed-layouts-views |
| medium | POST | `/skiplum/v1/threed/camera/state` | Apply a serialized perspective/orthogonal camera state in one call. | `PerspectiveCameraState.create(...) / OrthogonalCameraState.create(...); SMC.get3D().setCameraState(...)` | threed-layouts-views |
| medium | GET | `/skiplum/v1/views/issues` | All / selected / marked BCF slides currently in the Issues view. | `SMC.getInternalViews().getIssuesView().getAllSlides()/getSelectedSlides()/getMarkedSlides()` | threed-layouts-views |
| medium | POST | `/skiplum/v1/views/results/choose` | Drive the Checking Results table selection from outside (by Result uniqueKey or BCF slide id). | `SMC.getInternalViews().getCheckingResultsView().chooseRows(Set<...>) / chooseRows(Collection<Slide>)` | threed-layouts-views |
| low | GET | `GET /skiplum/v1/checking/state` | Lightweight poll for IDLE/CHECKING/CANCELING + isCheckingRunByAPI flag | `Checking.getState() + CheckingPlugin.isCheckingRunByAPI` | checking-rules |
| low | GET | `GET /skiplum/v1/checking/tracking` | Per-rule elapsed-ms profile of the last run - find the rules eating the check budget | `RuleCheckingTracking.getTrackingDataForRuleCheck(constraint, sinceMs)` | checking-rules |
| low | POST | `/checking-results/viewpoint-column` | Toggle the Viewpoint column visibility in Checking Results view. | `ViewpointColumn.show/hide` | coordination-bcf-presentations |
| low | DELETE | `/presentations/{id}` | Remove presentation (and optionally its slides). | `Presentation.removeSlide loop (no direct delete; manage via collection)` | coordination-bcf-presentations |
| low | DELETE | `/ito/{id}/columns/{index}` | Remove a column by index. | `ItoSettings.removeColumn(int)` | information-takeoff |
| low | POST | `/ito/{id}/select-row` | Highlight the components of a row in the 3D view selection basket. | `ItoRow.getComponents() -> SMC.getSelectionBasket().set(...)` | information-takeoff |
| low | POST | `/models/{uuid}/delete-components` | Remove components by GUID from a writeable model (e.g. to filter out furniture before clash run). | `MModel.deleteComponents(set)` | model-components |
| low | GET | `/profiles` | Steel/concrete sections in the model (parametric I/L/C/U/T plus dims) - useful for QTO + steel orders. | `ProfileFactory.INSTANCE.getProfile(component); cast to IProfile/LProfile/etc.` | model-components |
| low | GET | `/site` | Project Site with WGS84 lat/lon + elevation - lets us auto-link a project to its geo location. | `Site site = (Site) model.getComponents(ACCEPT_ALL, Site.class); site.getLatitude()/getLongitude()/getElevation()` | model-components |
| low | POST | `/transform` | Apply a 4x4 transform to a sub-model (relocate stray models without re-export). | `MModel.transform(Matrix4d.create(double[16]))` | model-components |
| low | GET | `/filters` | List the canonical Solibri FileTypeFilters (IFC, SMC, SMCX, SMV, SMCT, DWG, PDF, V3D, E57, ...) so clients don't hard-code extensions. | `ProductModelHandlingPlugin.getOpenFileTypeFilters() + getAddFileTypeFilters()` | project-lifecycle |
| low | GET | `/models/exists` | Cheap GUID existence probe inside a named sub-model (?name=&guid=). | `new ModelRobot().doesComponentExist(name, guid)` | project-lifecycle |
| low | POST | `/projects/industry` | Set Project.Industry from a query/body string - lets fleet automation tag projects by discipline before scoring. | `SMC.getProject().setIndustry(Project.Industry.valueOf(...))` | project-lifecycle |
| low | POST | `/projects/markClean` | Force the dirty flag to false (or true) so scripted close() doesn't trigger save prompts. | `SMCModelHandlingPlugin.getInstance().setModelModified(false)` | project-lifecycle |
| low | POST | `/recents/add` | Seed Solibri's File>Recent list from a fleet manifest (?key=&path=&priority=). Lets a project switcher pre-populate recents per workstation. | `RecentResource.addRecentFiles(key, priority, new File(path))` | project-lifecycle |
| low | POST | `/recents/clear` | Clear recents for a category (?key=) - cleanup for shared/demo machines. | `RecentResource.clearRecent(key)` | project-lifecycle |
| low | POST | `/resources/check` | Reachability probe for one or more resource URIs (body: JSON array). | `ResourceUtils.checkConnections(resources) and Resource.isConnected()` | project-lifecycle |
| low | GET | `/about` | Mirror /ping with Product enum + version (lets clients route by edition - some endpoints only valid in Office) | `SMC.getProduct().name() + SMC.getProductVersion()` | selection-settings-user-internal |
| low | POST | `/batch/close-ruleset` | Symmetric close | `new CloseRuleSet(name).run()` | selection-settings-user-internal |
| low | GET | `/checkpoint/models` | List models in a Checkpoint project | `CheckpointPlugin.getInstance().getModelsApi(region)` | selection-settings-user-internal |
| low | GET | `/checkpoint/projects` | List Solibri Checkpoint projects using the user's OAuth (no separate token) | `CheckpointPlugin.getInstance().getProjectsApi(region)` | selection-settings-user-internal |
| low | GET | `/checkpoint/versions` | List versions of a Checkpoint model (audit trail) | `CheckpointPlugin.getInstance().getVersionsApi(region)` | selection-settings-user-internal |
| low | POST | `/classifications/{name}/copy` | Branch a classification for what-if analysis | `target.createCopy(); plugin.addToCurrentClassifications(copy)` | selection-settings-user-internal |
| low | GET | `/settings` | Dump non-secret user settings (units, thread count, currentUser) for diagnostic + project provenance | `SMC.getSettings().getGeneralSettings()/getUnitSettings()` | selection-settings-user-internal |
| low | GET | `/trimble/api/raw` | Proxy through Trimble Connect's ApiClient (user already logged in via Solibri) | `TrimbleConnectPlugin.getInstance().getApiClient()` | selection-settings-user-internal |
| low | GET | `/user` | Return SMC.getUser().getUsername() (stamps reports/BCF with the running operator) | `SMC.getUser().getUsername()` | selection-settings-user-internal |
| low | POST | `/skiplum/v1/progress` | Surface a long-running plugin operation in Solibri's status bar. | `SMC.createProgressBar(name, total) -> increment/close` | threed-layouts-views |
| low | POST | `/skiplum/v1/views/checking/choose` | Drive CheckingView ruleset/rule selection (e.g. focus a node in the tree). | `SMC.getInternalViews().getCheckingView().chooseNodes(Set<CheckingNode>)` | threed-layouts-views |
| low | POST | `/skiplum/v1/views/results/columns` | Toggle Main/Hyperlink/Viewpoint columns + show/hide named custom columns. | `getMainColumn().show()/hide(), getHyperlinkColumn(), getViewpointColumn(), showCustomColumn(id)` | threed-layouts-views |

## Workflow catalog

Value-creating workflows that combine a Solibri capability area with Python / IfcOpenShell / custom plugins. These are the build targets when prioritizing for outcomes rather than endpoint coverage.

### project-lifecycle

- **Project snapshotter** — Auto-checkpoint Solibri sessions to a timestamped .smc on every coordination milestone (BCF export, end-of-day, after rule run) so coordinators can diff/restore. Today this requires a human pressing Save As.
  - Steps: Plugin: add /projects/saveAs (SaveAsSMCModelAction) + /projects/info (lastSaveTime, isModified). → Python orchestrator polls /projects/info; on dirty + threshold (e.g. after /check or after a BCF export), POST /projects/saveAs to <projectId>/snapshots/<UTC>.smc. → Push snapshot path + checking summary to a Notion 'Project Log' DB, and (optional) git-LFS commit the .smc for audit. → Restore tool: pick a snapshot from Notion, /projects/close + /projects/openFile.
  - Combines with: spruceforge sync.outbox, /results, /bcfxml
- **Discipline model refresh bot** — Ingest a new IFC export from a discipline lead (ACC webhook or watched folder) and refresh ONLY that sub-model in every affected Solibri project, preserving rule state, presentations and annotations - work normally done manually by a BIM coordinator.
  - Steps: Watcher (Python) detects new IFC in ACC/Downloads via /recents poll or ACC webhook. → For each open project that references the old sub-model: POST /models/update?name=ARK&path=newIfc (ModelRobot.updateModel). → Re-run checking via /check, diff results against the previous run, generate a delta report. → Auto-create BCF topics for newly-introduced clashes, post a Teams/Notion summary.
  - Combines with: IfcOpenShell (pre-validate IFC schema), /check + /results, ACC SDK
- **Fleet project switcher with LLM summaries** — A unified picker that shows EVERY recent Solibri project across both workstations (edkjo + omarchy), enriched with last-checking-score, dirty flag, and an LLM-generated 'where you left off' note. Sells as a productivity layer for multi-machine BIM consultants.
  - Steps: /recents/providers groups recents by FILES vs BIM360/ACC; sync to spruce.db across machines. → For each candidate project: open via /projects/openFile, read /projects/info + /projects/scores, summarise checking results via LLM, then /projects/close. → Cache the summary indexed by project URI in Notion 'Project Memory'. → TUI/Web picker: choose project -> POST /projects/openFile on the right machine (fleet comms).
  - Combines with: spruceforge fleet comms, Notion sync, Claude summaries
- **Capability auto-discovery agent** — Self-updating documentation of every Solibri automation verb available in the running JVM, regenerated on each Solibri version bump. Replaces the manual /docs/SOLIBRI_API.md drift that hits us on every upgrade.
  - Steps: Add /keywords + /filters endpoints. → Nightly cron (Task Scheduler): hit /ping, then /keywords + /filters and snapshot to git as docs/keywords-<version>.json. → On change vs previous snapshot, open a spruceforge GitHub issue with the diff. → LLM agent reads the diff + javap output for new internal classes and proposes new plugin endpoints (a PR draft for our SuperuserServer).
  - Combines with: javap regional surveys (this very task), GitHub Issues, Claude PR drafts

### model-components

- **NS3451-by-storey ITO to Excel** — Replace the manual quantity take-off Norwegian QS firms charge 50-150k NOK per project for. Federate IFC -> filter by NS3451 classification + storey -> sum Quantities.AREA/VOLUME -> emit per-section workbook.
  - Steps: Open .smc via existing /projects/openFile → GET /storeys and GET /components/byClass?cls=Wall|Slab|Beam|... grouped by storey → For each component fetch /components/{guid}/quantities and the NS3451 classification value via /components GET (already wired) → Python aggregates by NS3451 prefix + storey, writes XLSX with openpyxl
  - Combines with: openpyxl, spruceforge worklog hook for delivery tracking
- **Auto-narrated clash presentation** — Turn a 2000-clash run into a 30-slide MD/Notion presentation with LLM-summarised explanations + camera snapshots - a 1-day consultant task becomes 5 minutes.
  - Steps: POST /check to run rulesets, GET /results for raw hits → Group results by ruleset + involved components -> ask Claude to cluster + write Norwegian-language explanations → For each cluster use EntityFactory.INSTANCE.createPresentation/createSlide/createViewpoint/createPerspectiveCameraState to author a Solibri Presentation in-JVM → Export presentation, optionally also embed PNG snapshots via /screenshot (other region) into a Notion page
  - Combines with: Claude LLM, Notion API, spruceforge knowledge graph
- **Accessibility / TEK17 stair + ramp audit** — Run section 12 of TEK17 (Norwegian building code) over all stairs/ramps: riser height, tread length, slope, handrail presence + height. Produces a code-compliance PDF that universal-design consultants charge 80k+ NOK to produce.
  - Steps: GET /components/byClass?cls=Stair and ?cls=Ramp → Per stair: enumerate StairFlight.getSteps() -> StairStep getElevation+getLength+isWinder; check getRiserHeight() <= 180mm, getTreadLength() >= 250mm → Per ramp: RampFlight.getSlope() <= 1:12 (or 1:20 outdoor); Landing every 0.6m vertical → Cross-check Flight.getHandrails() -> Handrail.getHeight() in [800,900] mm and Side coverage on both sides → Emit BCF + PDF with sketches per violation
  - Combines with: reportlab, BCF SDK, NS-EN 3457 mapping
- **Evacuation path / escape distance check** — Use TriangleMesh.shortestPathTo on Space meshes against the nearest door+stair to verify TEK17 evacuation distance limits (typically <= 30 m). Norwegian fire consultants do this manually in 2D - we do it in true 3D with mesh routing.
  - Steps: GET /spaces - per Space build a TriangleMesh from its boundary polygons (Polygon3d -> TriangleMesh.fromPolygons) → For each Space mesh, find the nearest Door (filter by /components/near with padding) and call mesh.shortestHorizontalPathTo(doorMesh) → From door, chain shortest path to a Stair (use Relation.NEAREST_SPACES backward + StairFlight bottom segment) - sum lengths → Flag any Space where total path > 30 m, emit a BCF topic with the polyline as viewpoint geometry
  - Combines with: custom 3D routing, BCF authoring via EntityFactory

### checking-rules

- **Auto-coordination deck from checking + LLM narrative** — Replaces a senior BIM coordinator's manual triage. After every nightly check, produce a client-ready coordination presentation with grouped clashes, 3D snapshots, and LLM-written summaries per ResultCategory.
  - Steps: Run /skiplum/v1/check headlessly on the scheduled .smc → Pull /results/full + /rules to get categories, severities, involved components and visualization camera state → For each ResultCategory, ask Claude to summarize the failed components into a Norwegian sentence + recommended action → POST /presentations/fromRuleset (or call ConvertToPresentationAction) to materialize the Slides in-Solibri → Export via /bcfxml/two_one and push the .bcf into ACC Issues; attach the rendered PDF deck
  - Combines with: BcfApi, ConvertToPresentationAction, ACC Issues REST
- **Headless rule-parameter tuning loop** — Calibrate a Norwegian-standards ruleset (e.g. MMI-driven clash tolerances) without re-saving .cset files by hand. Iterates parameters against a benchmark model until acceptance rate hits a target.
  - Steps: POST /rulesets/openFile to load a baseline .cset → Sweep RuleParameter values via POST /rules/{ruleName}/parameters → Run /check after each sweep, capture /score and /checking/tracking → Pick the parameter set that maximizes passed/failed ratio at minimum elapsed-ms → Persist the final .cset back to disk via the rulesetfactoryplugin save handler
  - Combines with: UpdateRuleParametersTask, ScoreHandler, RuleCheckingTracking
- **KPI dashboard: project-coverage score over time per client** — Sells as a quality-management deliverable: weekly graph of project- and rule-coverage scores per Skiplum client, with drill-down to which rules regressed.
  - Steps: After each scheduled check, POST /score/store to snapshot Score → Pull /score (range query) into a Skiplum reports repo on GitHub Pages → Render a Plotly time-series + table of rule-coverage deltas in the existing skiplum-reports site → When Score regresses week-over-week, raise a spruceforge GitHub Issue tagging the responsible discipline
  - Combines with: ScoreHandler, skiplum-reports GitHub Pages, spruceforge fleet comms
- **IDS-as-a-service: validate against buildingSMART IDS without opening Solibri UI** — Niche today - most consultants still hand-load .ids in Solibri. We expose a one-shot HTTP endpoint that accepts an IFC + .ids, returns a JSON verdict + BCF.
  - Steps: POST /projects/openFile with the .ifc (existing endpoint) → POST /rulesets/openFile pointing at the .ids (uses OpenIdsRuleSetAction internally) → POST /check (current endpoint) → Return /results/full as JSON and stream the BCF via /bcfxml/two_one - all in one request from a CLI shim
  - Combines with: OpenIdsRuleSetAction, IfcOpenShell pre-validation

### information-takeoff

- **NS3451/NS3457/MMI quantity ITO from a JSON spec** — An estimator hands the plugin a JSON spec ('group by NS3451 2-digit + MMI, sum Lengde, count components, filter to IFC4 model X'); plugin authors a fresh ItoSettings with ClassificationColumn(NS3451) + ClassificationColumn(MMI) + PropertySetColumn(RIV,Lengde, SUM) + CountColumn + the right ClassAndPropertyFilter, registers via ItoPlugin, calculates, exports to xlsx via ItoReport.save - all from a single curl. Replaces a 2-hour consulting workflow with a 10-second API call.
  - Steps: POST /ito with {name, columns:[{type:CLASSIFICATION,name:'NS3451',group:true},{type:CLASSIFICATION,name:'MMI',group:true},{type:PROPERTY_SET,pset:'RIV',prop:'Lengde',operator:SUM},{type:COUNT}], filters:[...]} → POST /ito/{id}/calculate (server blocks on ItoPlugin.waitForTakeoff) → POST /ito/{id}/report?out=C:\out\quantities.xlsx → Python wrapper formats result for AECON / Linde / standard FDV deliverable
  - Combines with: solibri-loop CLI, Notion 'Mengder' database push
- **LLM-authored ITO from a natural-language brief** — User in Norwegian: 'Lag en mengdeoppgave per etasje og NS3457-romtype, count rom + sum BRA fra Pset_SpaceCommon.GrossArea'. LLM agent translates the brief into a JSON ITO spec, hits POST /ito, calculates, exports. Removes the 'I don't know the property name' barrier for non-Solibri-power-users.
  - Steps: LLM reads project /classifications and /components to learn available psets/props → LLM emits ITO JSON via tool-use → POST /ito -> POST /ito/{id}/calculate -> GET /ito/{id}/rows for sanity preview → If preview matches brief, POST /ito/{id}/report
  - Combines with: Claude/GPT agent, /classifications endpoint, /components endpoint
- **Coordination presentation auto-build from a clash ITO** — Combines this region with checking: clash results -> RelationColumn 'collides-with' + ClassificationColumn(NS3451) + IdentificationTypeColumn(NAME) ITO, then POST /ito/{id}/presentation generates a slide-per-row deck with linked components + auto-zoom. Coordinators normally do this by hand for hours; this is one POST.
  - Steps: POST /check to run clash detection → POST /ito with RelationColumn + ClassificationColumn + CustomColorColumn auto-author → POST /ito/{id}/calculate → POST /ito/{id}/presentation?title=NAME&desc=NS3451 → Export presentation as PDF via existing 10876 REST or new endpoint
  - Combines with: /check endpoint, Presentation API, BCF push
- **Cross-version BIM diff ITO** — Open two versions of the same .smc back-to-back; for each, author the same ITO via POST /ito, calculate, dump rows; Python diff highlights every (NS3451, MMI, etage) bucket where Lengde changed by more than a threshold - perfect for change-order documentation. Cannot be done in stock Solibri UI without endless click-through.
  - Steps: POST /projects/openFile A.smc -> POST /ito with spec -> GET /ito/{id}/rows -> save → POST /projects/openFile B.smc -> POST /ito with same spec -> GET /ito/{id}/rows -> save → Python diffs and emits a change-order xlsx / Notion table → Optional: POST /ito/{id}/presentation on the deltas for stakeholder review
  - Combines with: /projects/openFile endpoint, Notion sync, Excel pipeline

### threed-layouts-views

- **Per-clash snapshot pack from BCF** — Replaces the niche BIM-coordinator workflow of manually opening every clash in Solibri, framing the camera, and taking a screenshot for a PDF coordination report. Output is a folder of deterministic, high-resolution PNGs keyed by BCF topic guid, ready for PDF assembly or LLM-captioning.
  - Steps: GET /skiplum/v1/results to fetch the active clash/result set → For each result: POST /threed/visibility with the involved IFC GUIDs (hide everything else) → POST /threed/snapshot/by-guids {guids:[a,b], projection:'perspective', fov:0.78} -> PNG bytes → Also POST /threed/visualization to color the two participants (e.g. blue/red) → Optional: pipe PNG + result.description to an LLM to generate a one-line caption per clash → Assemble PNG+caption into PDF or a static site (skiplum-reports)
  - Combines with: IfcOpenShell (to compute oriented camera positions from component bbox), ReportLab/Weasyprint (PDF assembly), LLM summarisation
- **Property-driven heatmap renders (MMI / NS3451 / fire compartments)** — Visualizes a property an architect chose - MMI maturity, NS3451 category, fire-rating - as a color overlay on the 3D model and exports plan + iso renders for documentation, without ever leaving the live session. Today this requires manually coloring filters in Solibri or exporting to BIMcollab/Navisworks.
  - Steps: GET /skiplum/v1/components?pset=...&prop=MMI to enumerate components with the chosen property → Bucket GUIDs by property value with Python (e.g. MMI 200/300/400 -> color ramp) → POST /threed/visualization with the bucketed argb colorings → POST /threed/camera/state to set 4 deterministic orthographic camera states (N/E/S/W plan + iso) using OrthogonalCameraState.create → GET /threed/snapshot for each and write to disk → Optional: include a legend image generated from the same color palette
  - Combines with: IfcOpenShell (cross-check property values against IFC source-of-truth), Pillow (compose legend onto render)
- **Guided review session by layout switching** — An LLM agent drives a coordination meeting via Solibri: switches to the Issues layout, frames the next slide, opens the Info panel for the offending component, and dictates a comment. Eliminates the typical 'screen-share fumble' during fagmotemoter (cross-discipline coordination meetings).
  - Steps: POST /skiplum/v1/layouts/switch {name:'Coordination'} → GET /skiplum/v1/views/issues -> pick next slide → POST /skiplum/v1/threed/camera/state with the BCF slide's camera viewpoint → POST /skiplum/v1/info/show with the offending IFC GUID (Info panel opens) → POST /skiplum/v1/views/results/choose to follow the corresponding result row in the Checking Results table → LLM observes the snapshot + Info panel content and proposes a comment text
  - Combines with: BCF export (existing wired endpoint), Notion (push the auto-summary into the issue tracker), Microsoft Teams (post review minutes)
- **Custom Information column for cost / carbon / NS3451** — Ship a plugin Information<T> implementation (e.g. carbon kg-CO2e per element computed from a property table) that shows up in the Info panel, the ITO and as a CustomColumn in Checking Results. Surfaces values that today only exist in side spreadsheets, directly inside Solibri's UI - the kind of feature a vendor would charge for as an add-on.
  - Steps: Implement Information<Double> with getInformation(Component) returning kgCO2e by NS3451+volume (lookup table) → Register via META-INF/services alongside SuperuserServer → Implement CheckingResultsColumn (CustomColumn<CheckingResultsViewRow>) backed by the same Information so each result row shows its element's carbon → Expose POST /skiplum/v1/views/results/columns to toggle the column on/off remotely → Optional: ParametricInformation + InformationParameters for a user-editable carbon factor table
  - Combines with: NS3451 / EPD database, Excel export via the existing ITO wiring, spruceforge knowledge base for factor tables

### coordination-bcf-presentations

- **LLM-narrated coordination presentation from rule violations** — Replace the 'BIM coordinator spends 4 hours writing topic descriptions and grouping issues' step. Auto-cluster rule violations into a presentation with human-readable narratives, MMI-aware priority, and discipline-tagged responsibilities - sellable as a per-model fixed-price service to GCs.
  - Steps: Run /checking/check via existing CLI to produce Results → POST /slides/from-results to bulk-create skeleton slides linked to each Result → For each slide: POST /slides/{id}/viewpoints with a snapshot from /3d (camera framed on the component bbox) → Pipe Result+Component metadata to Claude with a NS3451/MMI-aware prompt -> get title/description/priority/responsibilities JSON → PUT /slides/{id} with the LLM-authored fields + labels (discipline, building part) → POST /bcfxml/export with V2_1 + scope=ALL -> drop into ACC Issues or BIMcollab
  - Combines with: checking region, threed region, ito region
- **Coordination delta digest to ACC + Slack** — Daily 'what changed in coordination since yesterday' digest - normally a manual task or skipped entirely on small projects. Drives accountability and is hard for competitors to replicate without plugin-level event access.
  - Steps: Plugin registers SlideModifiedListener via Listeners (internal) → Stream events out via /events/slides/stream (SSE) → Python daemon buffers a 24h window, groups by responsibility → LLM summarises 'new clashes, resolved clashes, overdue items, comments needing reply' → Post Markdown digest to Slack channel + create ACC Issue attached to the affected document
  - Combines with: internal-services region, ACC/BIM360 REST
- **Round-trip BCF with PDF clash report** — Single command produces an A3 landscape PDF: per slide a 3D snapshot, component table (GUID/Type/Storey/MMI from IfcOpenShell), rule violation summary, responsibilities, due date - the same artifact niche AEC consultants charge ~10-20k NOK to produce per project.
  - Steps: GET /presentations/list, pick presentation → GET /presentations/{id}/slides + GET /slides/{id} (with viewpoint PNG bytes) for each → Cross-reference Slide.components GUIDs against the source IFC via IfcOpenShell to enrich (Pset values, classification codes, MMI) → Render WeasyPrint/ReportLab PDF: 1 slide per page, embed PNG, table, narrative → Optionally POST /bcfxml/export and attach the BCFZIP alongside the PDF for hand-off
  - Combines with: ito region, IfcOpenShell
- **Field-feedback inbound: photo + voice memo -> Solibri slide** — Closes the loop site->office without a coordinator transcribing anything. Sold as 'mobile-to-BIM coordination' subscription per active site.
  - Steps: Worker sends photo + voice memo via Telegram bot to a webhook → Whisper transcribes voice; Claude extracts (location, problem, suggested responsibility) → POST /presentations/list -> pick or create the site's coordination presentation → POST /slides/from-results-or-create with title+description+responsibility+labels=['field'] → POST /slides/{id}/viewpoints uploading the site photo as PNG; if BIM GUID hinted, /slides/{id}/components attaches the affected element → POST /bcfxml/export weekly and email to GC
  - Combines with: bcflive region, selection-basket region

### selection-settings-user-internal

- **One-call coordination meeting kit** — Replace half a day of consultant work with a 30-second pipeline: open .smc -> auto-update IFCs from ACC -> run rules -> emit BCF + Coordination PDF + Presentation slide-deck + ResultSummary dashboard, then email everything. Sells to BIM coordinators as the recurring 'before every meeting' button.
  - Steps: POST /batch/run with targets: [openSmc, autoUpdateModels, runChecking, bcfReport, coordinationReport, createPresentationFromRuleset, resultSummaryReport, saveAs <snapshot>] → Python wrapper zips the four artefacts + a SHA of the .smc → Push to ACC/Notion + create GitHub Issue summarising the diff vs last run (LLM summary of new vs resolved issues parsed from BCF) → Optional: also call /batch/auto-comment to mark stale issues 'Pending response'
  - Combines with: batchplugin tasks, BCF zip, ACC/Notion sync, LLM summary
- **MMI/NS3451 driven property-aware ITO -> Excel** — Combine Solibri's native ITO engine with a Skiplum classification (MMI x NS3451) to slice pipe length / wall area / window count by trade phase per storey - the kind of cross-cut split that today is done by hand in Excel.
  - Steps: POST /batch/open-ito MMI_NS3451_quantities.ito → POST /classifications/load MMI.classification + NS3451.classification, then /classifications/.../apply for both → POST /batch/calculate-ito MMI_NS3451_quantities → POST /batch/report-ito format=xls template=Skiplum_ITO_template.xls → Python post-process pivots quantities by (MMI, NS3451, Storey) and uploads to ACC; LLM writes a 1-paragraph narrative ('phase 4 sanitary pipework volume up 12% vs last week')
  - Combines with: classificationplugin, batchplugin ITO tasks, Excel templating, LLM summary
- **Auto-classify by Skiplum standard from IFC custom psets** — Many Skiplum models carry our own Pset/property scheme. Today an engineer hand-loads four classifications per project. Wire a single endpoint that lets a project setup script create + apply all four against a fresh model, and persist coloured snapshots back to disk.
  - Steps: POST /classifications/create name=MMI classes=[Wall,Slab,...] → POST /classifications/{MMI}/set body=GUID->class map produced offline from IfcOpenShell + property scan → POST /classifications/{MMI}/apply → POST /classifications/{MMI}/setColor → POST built-in /threed/camera (we already have this) + GET /snapshot to capture coloured views per storey → POST /classifications/{MMI}/save file=...standards/MMI.classification (so the next session is a single load)
  - Combines with: classificationplugin, IfcOpenShell property scan, 3D snapshot via built-in 10876
- **Continuous nightly QA loop with Checkpoint snapshotting** — Run a headless nightly cycle: pull latest IFC versions from Trimble Connect / Checkpoint, classify, check, write BCF + score summary, and snapshot each result as a Checkpoint version. Result: a per-night quality trend graph that no Solibri user produces today.
  - Steps: Cron triggers POST /batch/run on a Solibri agent VM → Pipeline: trimble or checkpoint resource provider URLs in AutorunResource -> openModel/updateModel for each discipline -> applyClassifications -> runChecking -> resultSummaryReport -> bcfReport → Save .smc as new Checkpoint version (POST CheckpointPlugin versions API) → Push score series to InfluxDB; LLM agent reads the BCF + summary, emits a 5-bullet 'what got worse since yesterday' note posted to Slack
  - Combines with: batchplugin, CheckpointPlugin / TrimbleConnectPlugin, time-series DB, LLM digest agent

---

## Per-region detail

### project-lifecycle

The project-lifecycle region governs how Solibri boots, opens, saves, closes, and lists project/model resources. The public SDK exposes a thin facade: `com.solibri.smc.api.SMC` (the static entry point) plus a near-trivial `Project` interface that only carries industry, save-state, file handle, and historical scores. The real machinery lives in three internal layers: (1) `com.solibri.smc.robot.keywords.ProjectRobot`/`ModelRobot` - the Robot Framework keywords that actually open .smc/.smcx projects in-JVM with no UI dialog (the only public way today to open from a blank state, already wired in our plugin); (2) `com.solibri.resource.Resource` + `ResourceProvider` + `ResourcePackage` - the unified URI-addressable file abstraction that powers File>Recent, ACC/BIM360 references, packaged resource handlers, and even the cryptic `ContentHandler.scrambleFile` content-protection primitive; (3) `com.solibri.saf.core.resource.RecentResource`/`RecentResourceUtils`/`ResourceUtils` - the recent-files registry and resource pretty-printing/IO helpers used by every File menu. There is no public "saveAs to a specific path" on the SDK Project (only the broken `SMC.saveModel(Path)` and the internal `SaveAsSMCModelAction.saveAsModel(SModel,File,...)`), so save/saveAs is the biggest untapped capability. Secondary opportunities: industry tagging, project score history (`Project.getScores`), file-type filters (`ProductModelHandlingPlugin.OPEN_FILE_FILTER` etc.), and exposing the full recent-files index per resource provider (filesystem vs. BIM360/ACC) for a fleet-wide "what is everyone touching today" view. The Robot Framework server (`SolibriRobotServer.startRobotServer(int,boolean,boolean)`) is also a latent in-JVM lever - it could give us a second control plane on a different port if we ever need keyword-level scripting without our plugin.

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `SMC.getProductVersion()` | SuperuserServer /ping -> productVersion; SolibriClient.about() via 10876 |
| `SMC.isBusy()` | SuperuserServer /model -> busy; SolibriClient.status().busy via 10876 |
| `SMC.getModel()` | SuperuserServer /model (name, subModels, rulesetIds); guards /components, /component, /select |
| `SMC.openModel(String, InputStream)` | SuperuserServer /projects/openFile when path is non-.smc/.smcx (IFC add path) |
| `SMC.closeProject()` | SuperuserServer /projects/close; SuperuserClient.close_project(); cli.py su close; SolibriClient.close_project via 10876 |
| `SMC.shutdown() / shutdownNow()` | SuperuserServer /shutdown[?force=1]; SuperuserClient.shutdown(force); launcher.shutdown(); cli.py shutdown |
| `SMC.getSelectionBasket().set(...)` | SuperuserServer /select; SuperuserClient.select(guids); cli.py su select |
| `SMC.getChecking()` | SuperuserServer /results, /check |
| `ProjectRobot.openSMCProject / openSMCXProject` | SuperuserServer /projects/openFile (.smc/.smcx branch); SuperuserClient.open_file; cli.py su open |
| `RecentResource.getRecentResources(String)` | SuperuserServer /recents (loops a known key set); SuperuserClient.recents(); cli.py su recents |
| `Resource.getURI / getName / getLocation / getType / getResourceProviderName` | Per-row serialization in SuperuserServer /recents |

**Internal gems:**

- `com.solibri.smc.robot.keywords.ProjectRobot` — The only in-JVM, dialog-free way to open .smc/.smcx project files. Already powering our plugin's open-from-nothing path. Also has saveModel() which is the safer save-by-name we should expose. — notable: `openSMCProject(String)`, `openSMCXProject(String)`, `saveModel(String)`, `closeModel(String)`
- `com.solibri.smc.robot.keywords.ModelRobot` — Sub-model lifecycle (add/update/saveAs IFC, GUID existence probe). updateModel + partialUpdateModel unlock 'refresh a discipline model' workflows without losing rule state. — notable: `openModel(String)`, `updateModel(String,String)`, `partialUpdateModel(String,String)`, `saveAsModel(String,String)`, `doesComponentExist(String,String)`
- `com.solibri.smc.robot.SMCRobotLibrary` — Top-level KeywordDocumentationRepository - enumerates EVERY robot keyword in the JVM with docstring + args. Free discovery surface for capabilities we don't yet know about. — notable: `getKeywordNames()`, `getKeywordDocumentation(String)`, `runKeyword(String,List,Map)`
- `com.solibri.smc.robot.server.SolibriRobotServer` — Embedded Robot Framework remote server on a chosen port. Latent second control plane if we ever want keyword-level scripting without going through our HTTP plugin. — notable: `startRobotServer(int,boolean,boolean)`
- `com.solibri.saf.core.resource.RecentResource` — Per-key recent-files registry. Read side is wired in /recents. Write side (addRecentFiles, setSavingEnabled, clearRecent) lets us seed the UI from a fleet manifest or run sandboxed scripted sessions without polluting Solibri's history. — notable: `getRecentResources(key)`, `addRecentFiles(key,priority,files...)`, `addRecentDirectories(key,dirs...)`, `clearRecent(key)`, `setSavingEnabled(boolean)`
- `com.solibri.saf.core.resource.ResourceUtils` — Pretty-printing + getResources(dir,filter) + writeToFile(Resource,File). writeToFile is the BIM360-aware 'download a remote resource to disk' primitive - a clean wrapper around ACC download. — notable: `writeToFile(Resource,File)`, `getResources(File,filter,key,boolean)`, `getPrettyLocation(Resource)`, `checkConnections(Collection<Resource>)`
- `com.solibri.resource.ResourceProvider` — Per-provider Resource enumerator/getter - lets us partition recents/resources by FILES vs. BIM360, which our current /recents key-loop conflates. — notable: `getResourceProvider(name)`, `getResources()`, `getResources(prefix)`
- `com.solibri.modelchecker.plugins.modelhandling.actions.SaveAsSMCModelAction` — Internal action for save-as to a specific File. Closes the biggest SDK gap: today we have no first-class scripted way to snapshot a session. — notable: `saveAsModel(SModel,File,Component)`
- `com.solibri.modelchecker.plugins.modelhandling.SMCModelHandlingPlugin` — Owner of project dirty-flag and update hook. setModelModified(false) is the trick to skip a save prompt on close without losing data. — notable: `setModelModified(boolean)`, `getInstance() (inherited)`
- `com.solibri.saf.plugins.modelhandling.ProductModelHandlingPlugin` — Holds the authoritative FileTypeFilter constants (SMC_AND_IFC_FILTER, OPEN_FILE_FILTER, IFC_FILTER, etc.) and the singleton. Lets us replace hard-coded extension lists with what Solibri itself recognises. — notable: `getInstance()`, `getOpenFileTypeFilters()`, `isValidFile(File)`, `isValidResource(Resource)`
- `com.solibri.modelchecker.proxy.SmcProjectImportProxy` — Truthful dirty-flag + origin Resource for the open project (handles BIM360 sources, not just local files). — notable: `isProjectModified()`, `getProjectResource()`, `getProject()`

<details><summary>Classes (28) — method signatures</summary>

#### `com.solibri.smc.api.SMC`  *(class, public)*

Static entry point for every SDK facade (model, checking, BCF, 3D, presentations, layouts, info, selection basket, settings, current user, product/version). Also owns the lifecycle verbs.

```
public static Model openModel(String, InputStream)  // DISRUPTIVE  // Add a model to the OPEN project from a stream. Requires a project to already be 
public static void saveModel(Path)  // DISRUPTIVE  // Saves the current project to disk. Note misleading name - it saves the project, 
public static Model getModel()  // The currently open root model (null if no project).
public static Models getModels()  // Plural accessor for sub-model enumeration.
public static Project getProject()  // Lightweight project descriptor (file, save status, industry, scores).
public static Product getProduct()  // Enum OFFICE/SITE/ANYWHERE.
public static String getProductName()  // Product name string ("Office"/"Site"/"Anywhere").
public static String getProductVersion()  // Version string like "26.4.1.108".
public static boolean hasFeature(String)  // License/feature gate probe.
public static boolean isBusy()  // True while Solibri is mid-operation (loading, checking, etc.).
public static InformationTakeoff getInformationTakeoff()  // ITO facade.
public static SelectionBasket getSelectionBasket()  // Selection basket facade.
public static InfoDisplayer getInfoDisplayer()  // Toast/status messages.
public static BcfXml getBcfXml()  // BCF XML import/export.
public static BcfApi getBCF()  // Live BCF API.
public static User getCurrentUser()  // Currently logged-in user.
public static Settings getSettings()  // App settings.
public static List<Presentation> getPresentations()  // Coordination presentations in the open project.
public static List<View> getCustomViews()  // Plugin-registered views (our SuperuserServer is one of these).
public static Checking getChecking()  // Checking subsystem (rulesets, results, runChecking).
public static ThreeD get3D()  // 3D viewport + camera.
public static Layouts getLayouts()  // Solibri window/panel layouts.
public static InternalViews getInternalViews()  // Backdoor to internal Solibri views/panels.
public static void shutdown()  // DISRUPTIVE  // Graceful application shutdown.
public static void shutdownNow()  // DISRUPTIVE  // Immediate JVM exit.
public static void bringToFront()  // Raise the Solibri window.
public static ProgressBar createProgressBar(String, int)  // Pop a progress bar in the UI.
public static JFrame getApplicationFrame()  // Root Swing frame - useful for parenting custom dialogs.
public static void closeProject()  // DISRUPTIVE  // Close current project (may prompt to save).
public static void closeProjectNow()  // DISRUPTIVE  // Close project, skipping the save prompt.
```

#### `com.solibri.smc.api.project.Project`  *(interface, public)*

Lightweight project descriptor exposing the project file, save status, industry tag, and historical checking scores.

```
Optional<File> getFile()  // Underlying .smc/.smcx file if any.
Project.SaveStatus getFileStatus()  // NO_FILE / UNSAVED / SAVED / MODIFIED.
Optional<OffsetDateTime> getLastSaveTime()  // Last save timestamp.
List<Score> getScores(Project.Industry, Instant, Instant)  // Historical checking scores per industry within a time range - latent KPI source.
Project.Industry getIndustry()  // Industry tag (architecture/engineering/consulting/construction/other-AEC/undefin
void setIndustry(Project.Industry)  // DISRUPTIVE  // Set the project's industry tag.
```

#### `com.solibri.smc.api.project.Project$Industry`  *(enum, public)*

Project industry classification: UNDEFINED, ARCHITECTURE, ENGINEERING, CONSULTING, CONSTRUCTION, OTHER_AEC.

#### `com.solibri.smc.api.project.Project$SaveStatus`  *(enum, public)*

Project save state: NO_FILE, UNSAVED, SAVED, MODIFIED.

#### `com.solibri.smc.api.Product`  *(enum, public)*

Solibri product edition enum: OFFICE, SITE, ANYWHERE.

#### `com.solibri.smc.api.Internal`  *(interface, public)*

Annotation marker that tags an SDK symbol as internal/unstable across releases.

#### `com.solibri.smc.robot.keywords.ProjectRobot`  *(class, **internal**)*

Robot Framework keyword class - the ONLY in-JVM way to open .smc/.smcx project files without a modal UI dialog. Already wired in our plugin's /projects/openFile.

```
Map<String,Object> openSMCProject(String)  // DISRUPTIVE  // Open a .smc project file by path; returns status map.
Map<String,Object> openSMCXProject(String)  // DISRUPTIVE  // Open a .smcx project file by path; returns status map.
boolean closeModel(String)  // DISRUPTIVE  // Close a named model.
Map<String,Object> saveModel(String)  // DISRUPTIVE  // Save the project; returns status map - the closest thing to SDK saveAs.
```

#### `com.solibri.smc.robot.keywords.ModelRobot`  *(class, **internal**)*

Robot Framework keyword class for sub-model operations: add/update/saveAs IFC, existence probes.

```
String openModel(String)  // DISRUPTIVE  // Add a model (IFC etc.) by path to the open project.
String updateModel(String, String)  // DISRUPTIVE  // Replace an existing sub-model with a fresh file.
Map<String,Object> partialUpdateModel(String, String)  // DISRUPTIVE  // Partial/delta update of an existing sub-model.
Map<String,Object> saveAsModel(String, String)  // DISRUPTIVE  // Save a sub-model to a new path; the saveAs we don't yet expose.
boolean doesComponentExist(String, String)  // Quick component-existence probe (model, GUID).
```

#### `com.solibri.smc.robot.keywords.BringApplicationToFront`  *(interface, **internal**)*

Marker mix-in for robot keywords that should pop the Solibri window to front before running.

```
default void bringApplicationToFront()  // Raise the Solibri main window.
```

#### `com.solibri.smc.robot.SMCRobotLibrary`  *(class, **internal**)*

Top-level Robot Framework library implementing KeywordDocumentationRepository - lists every available keyword + docs. Latent backdoor to discover all internal automation verbs.

```
List<String> getKeywordNames()  // Every robot keyword name registered in the JVM.
String getKeywordDocumentation(String)  // Docstring for a keyword - free SDK documentation.
List<String> getKeywordArguments(String)  // Arg list for a keyword.
Object runKeyword(String, List)  // DISRUPTIVE  // Invoke any keyword by name - bypass SDK altogether.
Object runKeyword(String, List, Map)  // DISRUPTIVE  // Invoke with kwargs.
```

#### `com.solibri.smc.robot.server.SolibriRobotServer`  *(class, **internal**)*

Launches an embedded Robot Framework remote server on a chosen port - alternative control plane to our HTTP plugin.

```
static void startRobotServer(int, boolean, boolean)  // Start a Robot Framework remote server (port, ?, ?).
```

#### `com.solibri.modelchecker.proxy.SmcProjectImportProxy`  *(class, **internal**)*

Adapter exposing internal SModel project state (modified flag, source Resource, name) to the proxy framework. Needed when bridging SDK<->internal during import.

```
SModel getProject()  // Internal SModel for the open project.
void setProjectNameIfNeeded(File)  // DISRUPTIVE  // Apply the file's basename as project name when missing.
boolean isProjectModified()  // Dirty flag - more truthful than SaveStatus when combined.
Optional<Resource> getProjectResource()  // Underlying Resource (file or BIM360/ACC etc.).
```

#### `com.solibri.modelchecker.proxy.SmcImportProxies`  *(class, **internal**)*

Aggregate of import-time proxies (project, classification, geometry cache, tracking, IFC ports). The plumbing root for custom importers.

```
ProjectImportProxy getProjectImportProxy()  // Project import bridge.
ClassificationProxy getClassificationProxy()  // Classification import bridge.
ApiModelProxy getApiModelProxy()  // SDK Model bridge.
TrackingProxy getTrackingProxy()  // Change-tracking bridge.
GeometryCacheProxy getGeometryCacheProxy()  // Geometry cache for fast re-open.
boolean isIfcPortsImported()  // Whether IFC ports are present in this import.
```

#### `com.solibri.modelchecker.proxy.SMCModelProxy`  *(class, **internal**)*

Adapter from internal redox Model to SDK Model.

```
com.solibri.smc.api.model.Model getModel(com.solibri.sae.redox.Model)  // Lift internal model to SDK facade.
```

#### `com.solibri.saf.core.resource.RecentResource`  *(class, **internal**)*

Entry in the File>Recent list with timestamp + priority. Static side has the full recent-files registry and per-key add/clear/list API. Used today by our plugin /recents handler.

```
Resource getResource()  // Backing resource.
long getTime() / Date getDate()  // When the resource was last touched.
int getPriority()  // Sort hint for the menu.
static List<RecentResource> getRecentResources(String key)  // Recent items for a category key (model/project/ifc/classification/ito/...).
static List<Resource> getResources(String key)  // Resources only, no timestamp.
static List<RecentResource> getRecentDirectories(String key)  // Recent directories per key.
static File getRecentFile(String) / getRecentDirectory(String)  // Single most-recent.
static void addRecentResources(String, int, Resource...)  // DISRUPTIVE  // Programmatically push to recents - lets us seed Solibri's UI from outside.
static void addRecentFiles(String, int, File...)  // DISRUPTIVE  // Same but for raw files.
static void addRecentDirectories(String, File...)  // DISRUPTIVE  // Seed recent directories.
static void clearRecent(String) / clearRecentDirectories(String)  // DISRUPTIVE  // Wipe a category.
static void setSavingEnabled(boolean)  // DISRUPTIVE  // Disable persistence of recents (handy during scripted runs).
StatefulResource convertToStatefulResource()  // Adapter to the fixed/loose model handling bookkeeping.
```

#### `com.solibri.saf.core.resource.RecentResourceUtils`  *(class, **internal**)*

Scans a directory for resources matching a FileTypeFilter, optionally restricted to a recents key.

```
static List<Resource> getRecentResources(File, FileTypeFilter, String)  // Directory-scan helper.
```

#### `com.solibri.saf.core.resource.ResourceUtils`  *(class, **internal**)*

Pretty-printing, naming, file-extension, IO and validation helpers for the Resource abstraction.

```
static String getPrettyLocation(Resource)  // User-facing location (handles BIM360/ACC URIs nicely).
static String getNameFromResource(Resource) / getExtensionFromResource(Resource)  // Filename + extension helpers.
static void writeToFile(Resource, File)  // DISRUPTIVE  // Copy a Resource (even a remote one) to a local file - useful for BIM360 mirrorin
static List<Resource> getResources(File, FileTypeFilter, String, boolean)  // Discover resources under a directory.
static boolean checkConnections(Collection<Resource>)  // Probe network/BIM360 reachability of resources.
static boolean isSupportedResourceType(Resource)  // Predicate for accepted types.
static void showCannotOpenDialog(Collection<String>)  // UI fallback for failed opens.
```

#### `com.solibri.resource.Resource`  *(class, **internal**)*

Unified URI-addressable resource handle - file, BIM360 doc, packaged extension, etc. Carries name/location/type/URI/version/lock state.

```
URI getURI() / String getString()  // Canonical URI and string round-trip.
String getName() / getLocation() / getType() / getFileName()  // Display + classification.
String getResourceProviderName()  // e.g. "FILES", "BIM360" - the discriminator we use in /recents.
long getUncompressedSize() / lastModified()  // Size + mtime.
boolean isLocked() / isProtected() / isConnected() / exists()  // State probes (lock for collaborative editing, protected for vendor-secured, conn
boolean connect()  // DISRUPTIVE  // Establish the network connection for a remote resource.
Optional<Resource> getParent()  // Containing folder/package.
Optional<String> getVersion()  // Version string (BIM360 versioning).
InputStream getInputStream()  // Open for reading.
static Resource parse(String)  // Round-trip from getString().
```

#### `com.solibri.resource.ResourceFile`  *(class, **internal**)*

Concrete Resource backed by a local java.io.File. Wrap-and-pass when you need to feed local paths through Resource-typed APIs.

```
ResourceFile(File, String)  // Wrap a local file with a type tag.
File getFile()  // Unwrap to File.
```

#### `com.solibri.resource.ResourceProvider`  *(class, **internal**)*

Abstract provider (FILES, BIM360, packaged, ...). Registry lookup via static getResourceProvider(name). Lets us enumerate resources per provider.

```
static ResourceProvider getResourceProvider(String)  // Lookup by provider name (e.g. "FILES", "BIM360").
List<Resource> getResources() / getResources(String)  // All resources known to the provider, optionally filtered.
InputStream getInputStream(Resource)  // Stream by Resource.
boolean exists(Resource) / isProtected(Resource) / isConnected(Resource) / connect(Resource)  // State probes + connect.
Resource parse(Resource, String)  // Provider-scoped resource parse.
String getProductId()  // Provider product identifier.
```

#### `com.solibri.resource.ResourcePackage`  *(class, **internal**)*

Provider variant for packaged/sealed resource bundles (used by extension distribution). Has byte 'key' for content scrambling.

```
byte getKey()  // Scrambling key for ContentHandler.
boolean isProtected(String) / isProtected(Resource)  // Protected-bit lookup by name or Resource.
```

#### `com.solibri.resource.ContentHandler`  *(class, **internal**)*

Streaming XOR-style scrambler used to obfuscate packaged resource bytes. Single-purpose vendor IP-protection utility.

```
static void scrambleFile(InputStream, OutputStream, byte)  // Scramble (or unscramble - involutive) bytes with a key.
```

#### `com.solibri.modelchecker.plugins.modelhandling.actions.CloseSMCModelAction`  *(class, **internal**)*

Internal action that drives close-project including dirty prompts. SMC.closeProject/closeProjectNow are the SDK proxies for this.

```
void closeModel(Component, SModel, boolean)  // DISRUPTIVE  // Close, with optional save prompt.
```

#### `com.solibri.modelchecker.plugins.modelhandling.actions.SaveSMCModelAction`  *(class, **internal**)*

Internal action that saves the open SModel back to its Resource. Together with SaveAsSMCModelAction this is the real saveAs surface SDK lacks.

```
boolean saveModel(SModel, Resource, Component)  // DISRUPTIVE  // Save back to its origin Resource.
```

#### `com.solibri.modelchecker.plugins.modelhandling.actions.SaveAsSMCModelAction`  *(class, **internal**)*

Internal action: saveAs project to a specific File. The cleanest way to script 'save snapshot of session to X.smc' since SDK SMC.saveModel(Path) is unreliable.

```
boolean saveAsModel(SModel, File, Component)  // DISRUPTIVE  // Save project to a new path.
```

#### `com.solibri.modelchecker.plugins.modelhandling.SMCModelHandlingPlugin`  *(class, **internal**)*

Plugin singleton owning SMC project lifecycle (update/modified flag). Extends ProductModelHandlingPlugin (which exposes the file-type filters and getInstance()).

```
void setModelModified(boolean)  // DISRUPTIVE  // Forcibly mark project dirty/clean - lets us guarantee save-prompt behaviour from
void updateModel(SModel, Object)  // Apply update hook.
```

#### `com.solibri.saf.plugins.modelhandling.ProductModelHandlingPlugin`  *(class, **internal**)*

Base class housing the canonical FileTypeFilters (SMC, SMCX, IFC, DWG, PDF, SMV, SMCT, SLDB, V3D, E57, ADD_FILE_FILTER, OPEN_FILE_FILTER, SMC_AND_IFC_FILTER) and the singleton accessor.

```
static ProductModelHandlingPlugin getInstance()  // Singleton.
static List<FileTypeFilter> getOpenFileTypeFilters() / getAddFileTypeFilters()  // Authoritative open/add file filters.
static boolean isValidFile(File) / isValidResource(Resource)  // Type validation.
ProjectImportProxy getProjectImportProxy()  // Bridge to SmcProjectImportProxy.
```

#### `com.solibri.saf.plugins.modelhandling.StatefulResource`  *(class, **internal**)*

Resource + 'fixed/loose' bookkeeping flag - tracks whether a sub-model link is pinned in the project (re-open uses the same Resource).

```
StatefulResource(Resource, boolean fixed)  // Construct with fixed flag.
void setFixed(boolean)  // DISRUPTIVE  // Pin/unpin a resource reference.
Resource getResource()  // Underlying Resource.
```

</details>

### model-components

The model-components region is the spine of the Solibri SDK: every other capability (checking, BCF, visualization, footprints) ultimately points back to a `Component`. `SMC.getModel()` returns the federated `Model`, which hands out `getComponents(ComponentFilter)` over a 280k-element graph; a `MModel` adds write ops (deleteComponents, transform, setMetadata) and `Models` lets us actually open/update IFC streams in-JVM. The class graph is rich: typed `Component` subinterfaces (`Wall`, `Door`, `Window`, `Space`, `Beam`, `Stair`/`Ramp` with their `Flight`/`Step`/`Handrail` children, `Grid`/`GridAxis`, `Site`, `Building`, `BuildingStorey`, `Project`, `Zone`, `Group`/`FunctionalSystem`), profile metadata (`Profile` + 18 parametric subtypes for sections), materials, hyperlinks, IFC metadata, and a robust `Quantities.of(component).get(Type)` accessor with 24 quantity flavours. `ComponentFilter` is composable (`and/or/negate/all/any` + `propertyValueEquals`, `propertyValueMatches`, `componentTypeIs`, `componentClassIs`) and the package adds three spatial filters (`AABBIntersectionFilter`, `LineIntersectionFilter`, `RayIntersectionFilter`) - this is the buried gold for spatial queries Python can't do alone. Geometry primitives (`Vector3d`, `Matrix4d`, `AABB3d`, `Plane`, `Triangle3d`, `Polygon3d`, `Ray3d`, `Line3d`) plus a full `TriangleMesh` algebra (union, intersection, difference, encloses, shortestPathTo) are exposed - sufficient to implement BVH-style component scans, mesh booleans and routing checks without leaving the JVM. Today the plugin only wires `Model.getComponents(filter-by-property)` + `getComponentByGuid` + selection; the biggest opportunity is exposing the typed components (esp. spaces, openings, stairs/ramps), the quantities accessor, and the spatial filters as endpoints - that unlocks ITO, accessibility checks, code compliance, and clash narration workflows that no other toolkit on the Norwegian market sells turnkey.

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `SMC.getModel()` | SuperuserServer.handleModel -> GET /skiplum/v1/model; SuperuserClient.model(); cli.py 'su model' |
| `Model.getName / getUUID / getSubModels / getRulesets` | Inside handleModel JSON payload |
| `Model.getComponentByGuid` | SuperuserServer.handleComponentByGuid -> GET /skiplum/v1/component?guid=...; SuperuserClient.component([guids]); cli.py 'su component' |
| `Model.getComponents(ComponentFilter)` | SuperuserServer.handleComponents -> GET /skiplum/v1/components?pset=&prop=&value=&op=; SuperuserClient.components(); cli.py 'su components' |
| `Component.getGUID / getName / getComponentType / getPropertySets(name)` | Inside handleComponents + handleSelect lambdas |
| `PropertySet.getProperty(name) / Property.getValueAsString` | Inside handleComponents filter lambda |
| `Models / openIFCModel (via SMC.openModel)` | SuperuserServer.handleProjects (openFile *.ifc) -> POST /projects/openFile |
| `com.solibri.smc.api.model.classification.Classification (public iface)` | Indirectly listed via internal ClassificationPlugin in /classifications; only the *internal* type's metadata is currently returned |
| `SMC.getSelectionBasket().set(Set<Component>)` | SuperuserServer.handleSelect -> POST /skiplum/v1/select; SuperuserClient.select(guids); cli.py 'su select' |

**Internal gems:**

- `com.solibri.smc.api.model.internal.EntityFactory` — The factory behind Presentations, Slides, Viewpoints, Comments, Visualizations, and Camera states. Hidden behind an `INSTANCE` field. This is the bridge that lets the plugin AUTHOR communication artefacts programmatically - essential for any LLM-driven reporting workflow. — notable: `createPresentation(String)`, `createSlide()`, `createViewpoint()`, `createComment()`, `createVisualization()` (+2 more)
- `com.solibri.smc.api.model.internal.ProfileFactory` — The ONLY way to obtain a Profile from a Component. Without it, Component.getProfile() returns Optional.empty for steel/concrete sections in many models. — notable: `INSTANCE.getProfile(Component)`
- `com.solibri.smc.api.model.Quantities` — Public but criminally underused - 23 quantity types (GROSS_AREA, NET areas, perimeter, profile width/height, thickness) computed by Solibri without us having to triangulate the mesh. — notable: `of(Component)`, `get(Quantities.Type)`
- `com.solibri.smc.api.filter.AABBIntersectionFilter / LineIntersectionFilter / RayIntersectionFilter` — Spatial query primitives most consumers never find - allow O(log n) BVH-backed scans for components near a point/segment/ray. Foundation for raycast picking and proximity-based ITO. — notable: `of(AABB3d)`, `ofComponentBounds(Component, padding, padding)`, `of(Ray3d)`, `of(Line3d)`
- `com.solibri.geometry.mesh.TriangleMesh` — Full CSG boolean mesh algebra (union/intersection/difference) + encloses(point) + shortestPathTo(point|mesh) variants (3d, vertical, horizontal, XY-projected). Powerful enough to implement evacuation-distance checks and net-volume ITO without IfcOpenShell. — notable: `intersection(TriangleMesh)`, `difference(TriangleMesh)`, `encloses(Vector3d)`, `shortestVerticalPathTo(...)`, `getVolume()`

<details><summary>Classes (83) — method signatures</summary>

#### `com.solibri.smc.api.model.Model`  *(interface, public)*

Root federated model handle for the open project.

```
Optional<Component> getComponentByGuid(String)
Optional<Component> getComponentByBatId(String)
Optional<Model> getSubModel(UUID)
Collection<Model> getSubModels()
Set<Component> getComponents(ComponentFilter)
<T extends Component> Set<T> getComponents(ComponentFilter, Class<T>)
UUID getUUID()
String getName()
Optional<String> getResourceName()
Map<String,String> getMetadata()
Collection<Classification> getClassifications()
Optional<Classification> getClassification(String)
Optional<String> getSourceApplication()
Set<Ruleset> getRulesets()
Matrix4d getTransformationMatrix()
Optional<IfcMetaData> getIfcMetaData()
Collection<Grid> getGrids()
Collection<Component> getTypes()
```

#### `com.solibri.smc.api.model.MModel`  *(interface, public)*

Mutable extension of Model - delete components, replace metadata, transform geometry.

```
void setMetadata(Map<String,String>)  // DISRUPTIVE
void deleteComponents(Collection<Component>)  // DISRUPTIVE
void transform(Matrix4d)  // DISRUPTIVE
```

#### `com.solibri.smc.api.model.Models`  *(interface, public)*

Project-level IFC stream IO: open, full update, partial update, list.

```
MModel openIFCModel(String, InputStream)  // DISRUPTIVE
MModel updateIFCModel(UUID, InputStream)  // DISRUPTIVE
MModel partialUpdateIFCModel(UUID, InputStream)  // DISRUPTIVE
List<MModel> getModels()
MModel getModel(UUID)
```

#### `com.solibri.smc.api.model.Component`  *(interface, public)*

Every IFC entity exposed by Solibri - properties, geometry, distance, relations.

```
String getGUID()
Optional<String> getBATID()
ComponentType getComponentType()
String getName()
Optional<String> getComponentName()
Optional<String> getNameText()
Optional<String> getTypeName()
Optional<String> getElementType()
Optional<String> getDescriptionText()
Optional<String> getDisciplineName()
Optional<String> getPredefinedType()
Optional<String> getResolvedPredefinedType()
Optional<String> getObjectType()
Optional<String> getCompositionType()
Optional<String> getConstructionType()
Optional<IfcEntityType> getIfcEntityType()
Optional<IfcEntityType> getIfcType()
Collection<PropertySet> getPropertySets()
Collection<PropertySet> getPropertySets(String)
<T> Optional<T> getPropertyValue(PropertyReference)
TriangleMesh getTriangleMesh()
AABB3d getBoundingBox()
OptionalDouble getBoundingBoxHeight()
OptionalDouble getBoundingBoxLength()
OptionalDouble getBoundingBoxWidth()
Optional<Vector3d> getGlobalCoordinate()
double getGlobalBottomElevation()
double getGlobalTopElevation()
double getBottomElevationFromFloor()
double getTopElevationFromFloor()
double getDistanceToNextFloor()
Optional<Building> getBuilding()
Optional<BuildingStorey> getFederatedFloor()
Optional<BuildingStorey> getFloor()
Optional<Site> getSite()
Footprint getFootprint()
Set<FunctionalSystem> getFunctionalSystems()
Set<Group> getGroups()
Collection<Hyperlink> getHyperlinks()
Collection<Component> getRelated(Relation)
Set<Intersection> getIntersections(Component)
Optional<Double> distance(Vector3d)
Optional<Double> distance(Component)
Optional<Double> horizontalDistance(Vector3d|Component)
Optional<Double> verticalDistance(Vector3d|Component)
Optional<Double> projectionOnXYPlaneDistance(Vector3d|Component)
Optional<Profile> getProfile()
boolean isOnBuildingEnvelope()
List<Material> getMaterials()
List<Material> getAllMaterials()
Optional<ComponentGeometryType> getGeometryType()
Model getModel()
<T> Optional<T> getInformation(Class<? extends Information<T>>)
Optional<String> getInformationAsString(String)
Optional<String> getParametricInformationAsString(String, Map<String,String>)
```

#### `com.solibri.smc.api.model.Property`  *(interface, public)*

Single property on a PropertySet (generic T).

```
String getName()
Optional<T> getValue()
Optional<String> getImportedValueType()
String getValueAsString()
boolean containsValue()
```

#### `com.solibri.smc.api.model.PropertySet`  *(interface, public)*

Named bag of properties (Iterable<Property<?>>).

```
<T> Optional<Property<T>> getProperty(String)
Set<Property<?>> getProperties()
Set<String> getPropertyNames()
String getName()
boolean contains(String)
boolean containsAll(Set<String>)
```

#### `com.solibri.smc.api.model.PropertyReference`  *(interface, public)*

Pointer to (psetName, propertyName), passed to filters and Component.getPropertyValue.

```
String getPropertySetName()
String getPropertyName()
```

#### `com.solibri.smc.api.model.PropertyType`  *(enum, public)*

BOOLEAN/INT/DOUBLE/DATE/STRING/AREA/LENGTH/PERCENTAGE/PLANE_ANGLE/SOLID_ANGLE/TIME/VOLUME/UNKNOWN - drives parsing + format.

```
Class<?> getDataType()
boolean isNumberType()
Format getFormat()
```

#### `com.solibri.smc.api.model.ComponentType`  *(enum, public)*

Full IFC4.3 entity taxonomy (~170 constants from WALL to RAILWAY to MARINE_FACILITY).

```
Icon getIcon()
```

#### `com.solibri.smc.api.model.ComponentGeometryType`  *(enum, public)*

EXTRUSION / BOUNDARY_REPRESENTATION / CSG_SOLID / BOX.

#### `com.solibri.smc.api.model.Building`  *(interface, public)*

Building spatial container; carries elevation reference + terrain elevation.

```
OptionalDouble getElevationRefHeight()
OptionalDouble getElevationOfTerrain()
```

#### `com.solibri.smc.api.model.SpatialElement`  *(interface, public)*

Marker for Component-with-longName (Building, BuildingStorey, Space).

```
Optional<String> getLongName()
```

#### `com.solibri.smc.api.model.Site`  *(interface, public)*

Site Component with WGS84 lat/lon + elevation.

```
String getName()
double getElevation()
Optional<GeographicCoordinate$Angle> getLatitude()
Optional<GeographicCoordinate$Angle> getLongitude()
```

#### `com.solibri.smc.api.model.GeographicCoordinate`  *(interface, public)*

Lat/Lon pair as Angle objects.

```
GeographicCoordinate$Angle getLatitude()
GeographicCoordinate$Angle getLongitude()
```

#### `com.solibri.smc.api.model.Material`  *(interface, public)*

Material/layer descriptor on a Component (thickness, fraction, ventilated, IFC entity type).

```
String getName()
Optional<String> getConstituentName()
OptionalDouble getThickness()
Optional<IfcEntityType> getIfcEntityType()
Optional<String> getCategory()
Optional<String> getDescription()
Optional<Double> getFraction()
Optional<Boolean> isVentilated()
Optional<String> getLayerSetName()
Optional<Integer> getPriority()
Collection<PropertySet> getPropertySets()
```

#### `com.solibri.smc.api.model.Hyperlink`  *(interface, public)*

External link bound to a component (URI + topic).

```
Optional<String> getTopic()
URI getUri()
```

#### `com.solibri.smc.api.model.Relation`  *(interface, public)*

Typed graph edge for Component.getRelated().

```
static Relation of(Relation.Type, Relation.Direction)
Relation$Type getType()
Relation$Direction getDirection()
```

#### `com.solibri.smc.api.model.Relation$Type`  *(enum, public)*

BOUNDED_BY, CONNECTED_TO, CONTAINS, COVERS_ELEMENTS, DECOMPOSES, DEFINES_BY_TYPE, FILLS, GROUPS, OCCUPIES_SPACES, PARTIALLY_VOIDS, PROVIDES, NEAREST_SPACES, SERVES, VOIDS, NESTS, CONNECTS_PATH_ELEMENTS, CONNECTS, CONTAINS_REFERENCED.

#### `com.solibri.smc.api.model.Relation$Direction`  *(enum, public)*

FORWARD / BACKWARD / BOTH.

#### `com.solibri.smc.api.model.Group`  *(interface, public)*

Logical group of components; supports nesting (sub-groups).

```
String getName()
Set<Component> getGroupedComponents()
Set<Group> getSubGroups()
```

#### `com.solibri.smc.api.model.FunctionalSystem`  *(interface, public)*

Group representing an IFC functional system (heating loop, sprinkler zone, etc.).

#### `com.solibri.smc.api.model.Zone`  *(interface, public)*

Group of spaces (Zone of Zones).

```
Optional<String> getLongName()
Set<Zone> getZones()
```

#### `com.solibri.smc.api.model.Grid`  *(interface, public)*

Grid Component holding U/V/W axes for plan grids.

```
List<GridAxis> getUAxes()
List<GridAxis> getVAxes()
List<GridAxis> getWAxes()
```

#### `com.solibri.smc.api.model.GridAxis`  *(interface, public)*

One grid line (label + polyline samples).

```
String getLabel()
List<Vector3d> getCurve()
List<Vector3d> getCurve(Grid)
```

#### `com.solibri.smc.api.model.IfcMetaData`  *(interface, public)*

STEP file header (author, organisation, originating system, schema).

```
String getDescription()
Optional<IfcImplementationLevel> getImplementationLevel()
String getName()
String getTimeStamp()
String getAuthor()
String getOrganization()
String getPreprocessorVersion()
String getOriginatingSystem()
String getAuthorization()
String getSchemaName()
```

#### `com.solibri.smc.api.model.IfcImplementationLevel`  *(enum, public)*

LEVEL_2_1.

#### `com.solibri.smc.api.model.Quantities`  *(interface, public)*

Read derived quantities from a component (the BIG win - no need to compute these in Python).

```
static Quantities of(Component)
Optional<Double> get(Quantities.Type)
```

#### `com.solibri.smc.api.model.Quantities$Type`  *(enum, public)*

AREA, MINIMUM_AREA, AREA_OF_DOORS, AREA_OF_OPENINGS, AREA_OF_WINDOWS, BOTTOM_AREA, DIAMETER, FRAME_LENGTH, GROSS_AREA, MINIMUM_GROSS_AREA, HEIGHT, MINIMUM_HEIGHT, LENGTH, MINIMUM_LENGTH, PERIMETER, PERIMETER_OF_OPENINGS, PROFILE_WIDTH, PROFILE_HEIGHT, SKIN_AREA, THICKNESS, MINIMUM_THICKNESS, VOLUME, WIDTH.

```
PropertyType getPropertyType()
```

#### `com.solibri.smc.api.model.classification.Classification`  *(interface, public)*

Named classification system (filter + classify(component)).

```
String getName()
ComponentFilter getFilter()
String getDescription()
Optional<String> getEdition()
Optional<String> getSource()
Collection<ClassificationResult> classify(Component)
```

#### `com.solibri.smc.api.model.classification.ClassificationResult`  *(interface, public)*

One classification outcome (name).

```
String getName()
```

#### `com.solibri.smc.api.model.ModelOpeningException`  *(class, public)*

Thrown when openIFCModel/updateIFCModel fails.

#### `com.solibri.smc.api.model.ProjectNotOpenException`  *(class, public)*

Thrown when an SDK call needs a project but none is open.

#### `com.solibri.smc.api.model.CheckingInterruptedException`  *(class, public)*

Thrown when checking is cancelled mid-run.

#### `com.solibri.smc.api.model.ModelNotFoundException`  *(class, public)*

Thrown when a model UUID does not resolve.

#### `com.solibri.smc.api.model.components.BuildingStorey`  *(interface, public)*

IfcBuildingStorey with federated and own component sets, elevation.

```
String getName()
Set<Component> getFederatedComponents()
Set<Component> getComponents()
OptionalDouble getElevation()
```

#### `com.solibri.smc.api.model.components.Wall`  *(interface, public)*

Marker subtype of Component for walls.

#### `com.solibri.smc.api.model.components.Door`  *(interface, public)*

Door with footprints (closed + opened), dimensions, swing/slide operation type.

```
Footprint getFootprint()
Footprint getFootPrintForOpenedDoor()
Footprint getGeometricalFootprint()
Vector3d getReferenceDirection()
double getOverallWidth()
double getOverallHeight()
Door$OperationType getOperationType()
```

#### `com.solibri.smc.api.model.components.Door$OperationType`  *(enum, public)*

All IFC door operation modes (SINGLE_SWING_LEFT/RIGHT, DOUBLE_SWING_*, SLIDING_*, REVOLVING, FOLDING_*, LIFTING_*).

```
Door$OperationType$OpeningSide getOpeningSide()
```

#### `com.solibri.smc.api.model.components.Window`  *(interface, public)*

Window with footprints + partitioning type.

```
Footprint getFootprint()
Footprint getGeometricalFootprint()
Optional<Window$PartitioningType> getPartitioningType()
Optional<String> getUserDefinedPartitioningType()
Optional<String> getResolvedPartitioningType()
```

#### `com.solibri.smc.api.model.components.Window$PartitioningType`  *(enum, public)*

SINGLE_PANEL, DOUBLE_PANEL_*, TRIPLE_PANEL_*, USERDEFINED, NOTDEFINED.

#### `com.solibri.smc.api.model.components.Space`  *(interface, public)*

Room/space with door/window/opening lists, boundary areas, zones, number text.

```
Collection<Door> getDoors()
Collection<Opening> getOpenings()
Collection<Window> getWindows()
Collection<SpaceBoundary> getSpaceBoundaries()
double getSpaceBoundariesWallsArea()
double getSpaceBoundariesWindowsArea()
double getSpaceBoundariesDoorsArea()
double getSpaceBoundariesColumnsArea()
double getSpaceBoundariesBeamsArea()
double getSpaceBoundariesFloorsArea()
double getSpaceBoundariesCeilingsArea()
double getReferencePerimeterNetArea()
Set<Zone> getZones()
double getElevationWithFloor()
Optional<String> getNumberText()
```

#### `com.solibri.smc.api.model.components.SpaceBoundary`  *(interface, public)*

Edge between Space and an element (PHYSICAL/VIRTUAL).

```
double getArea()
Optional<Space> getSpace()
SpaceBoundary$Type getType()
Optional<Component> getRelatedBuildingElement()
```

#### `com.solibri.smc.api.model.components.Opening`  *(interface, public)*

IfcOpeningElement marker.

#### `com.solibri.smc.api.model.components.Project`  *(interface, public)*

IfcProject component (phase, long name).

```
Optional<String> getPhase()
Optional<String> getLongName()
```

#### `com.solibri.smc.api.model.components.Beam`  *(interface, public)*

Beam component with IFC predefined type.

```
Beam$Type getType()
```

#### `com.solibri.smc.api.model.components.Covering`  *(interface, public)*

Covering with IFC predefined type (CEILING, FLOORING, CLADDING, ROOFING, INSULATION, MEMBRANE, SLEEVING, WRAPPING).

```
Covering$Type getType()
```

#### `com.solibri.smc.api.model.components.Stair`  *(interface, public)*

Stair assembly with flights/landings.

```
Optional<StairFlight> getBottomFlight()
Optional<StairFlight> getTopFlight()
List<StairElement> getStairElements()
```

#### `com.solibri.smc.api.model.components.StairFlight`  *(interface, public)*

Stair flight with riser height, tread length, steps.

```
List<StairStep> getSteps()
double getRiserHeight()
double getTreadLength()
double getTreadLength(double)
```

#### `com.solibri.smc.api.model.components.StairStep`  *(interface, public)*

Single step (footprint, elevation, length, angle, isWinder).

```
MPolygon2d getFootprint()
MPolygon2d getNotCoveredFootprintPolygon()
double getElevation()
double getLength()
double getAngle()
boolean isWinder()
```

#### `com.solibri.smc.api.model.components.StairElement`  *(interface, public)*

Marker for stair-part elements (StairFlight, Landing).

#### `com.solibri.smc.api.model.components.Ramp`  *(interface, public)*

Ramp assembly (flights + landings).

```
Optional<RampFlight> getBottomFlight()
Optional<RampFlight> getTopFlight()
List<RampElement> getRampElements()
```

#### `com.solibri.smc.api.model.components.RampFlight`  *(interface, public)*

Ramp flight with slope + length (accessibility checks!).

```
double getSlope()
double getLength()
```

#### `com.solibri.smc.api.model.components.RampElement`  *(interface, public)*

Marker for ramp parts (RampFlight, Landing).

#### `com.solibri.smc.api.model.components.Flight`  *(interface, public)*

Common flight geometry: top/bottom segments, width, height, handrails.

```
Vector3d getBottomCenterPoint()
Vector3d getTopCenterPoint()
Vector3d getBottomDirection()
double getBottomElevation()
Segment2d getBottomSegment()
double getBottomWidth()
Vector3d getTopDirection()
double getTopElevation()
Segment2d getTopSegment()
double getTopWidth()
double getHeight()
Collection<Handrail> getHandrails()
```

#### `com.solibri.smc.api.model.components.Handrail`  *(interface, public)*

Handrail with height, footprint, side (LEFT/RIGHT/UNDEFINED).

```
double getHeight()
MPolygon2d getFootprint()
Handrail$Side getSide()
```

#### `com.solibri.smc.api.model.components.Landing`  *(interface, public)*

Landing element shared by stairs and ramps.

```
MPolygon2d getFootprint()
double getElevation()
```

#### `com.solibri.smc.api.model.profile.Profile`  *(interface, public)*

Section profile root (name + ProfileType).

```
Optional<String> getName()
ProfileType getType()
```

#### `com.solibri.smc.api.model.profile.ProfileType`  *(enum, public)*

ARBITRARY / PARAMETRIC / COMPOSITE.

#### `com.solibri.smc.api.model.profile.ParametricProfileType`  *(enum, public)*

18 parametric section flavours (I/L/T/U/Z/C, RECTANGLE [+ hollow], CIRCLE [+ hollow], ELLIPSE, ROUNDED_RECTANGLE, TRAPEZIUM, NON_UNIFORM_*, CRANE_RAIL_*, ASYMMETRIC_I).

#### `com.solibri.smc.api.model.profile.IProfile`  *(interface, public)*

I-beam parameters (width, depth, web/flange thickness, fillet radius, flange slope).

#### `com.solibri.smc.api.model.profile.CircleProfile`  *(interface, public)*

Round profile (radius).

#### `com.solibri.smc.api.model.profile.RectangleProfile`  *(interface, public)*

Rectangular profile (XDim, YDim).

#### `com.solibri.smc.api.model.profile.LProfile`  *(interface, public)*

L profile (depth, width, thickness, fillet/edge radius, leg slope, centre of gravity).

#### `com.solibri.smc.api.filter.ComponentFilter`  *(interface, public)*

Composable predicate over Component; ships with ACCEPT_ALL/NONE, and/or/negated, propertyValueEquals, propertyValueMatches, componentTypeIs, componentClassIs.

```
boolean accept(Component)
default ComponentFilter negated()
default ComponentFilter and(ComponentFilter)
default ComponentFilter or(ComponentFilter)
static <T> ComponentFilter propertyValueEquals(PropertyReference, T)
static <T> ComponentFilter propertyValueMatches(PropertyReference, Predicate<Optional<T>>)
static ComponentFilter all(ComponentFilter...)
static ComponentFilter any(ComponentFilter...)
static <T> ComponentFilter componentClassIs(Class<T>)
static ComponentFilter componentTypeIs(ComponentType)
```

#### `com.solibri.smc.api.filter.AABBIntersectionFilter`  *(interface, public)*

Filter accepting components whose AABB intersects a given box / a reference component's bounds (with optional padding).

```
static AABBIntersectionFilter of(AABB3d)
static AABBIntersectionFilter ofComponentBounds(Component)
static AABBIntersectionFilter ofComponentBounds(Component, double, double)
```

#### `com.solibri.smc.api.filter.LineIntersectionFilter`  *(interface, public)*

Filter accepting components intersected by a Line3d.

```
static LineIntersectionFilter of(Line3d)
```

#### `com.solibri.smc.api.filter.RayIntersectionFilter`  *(interface, public)*

Filter accepting components hit by a Ray3d (used for raycast picks).

```
static RayIntersectionFilter of(Ray3d)
```

#### `com.solibri.geometry.linearalgebra.Vector3d`  *(interface, public)*

Immutable 3-vector with full linear algebra (add/sub/scale/normalize/dot/cross/distance/angle/interpolate/project), plus standard basis constants.

```
static Vector3d create(double,double,double)
double distance(Vector3d)
MVector3d crossProduct(Vector3d)
MVector3d normalize()
```

#### `com.solibri.geometry.linearalgebra.Matrix4d`  *(interface, public)*

4x4 transform with create/IDENTITY, transform(Vector3d), productOf, add/multiply.

```
static Matrix4d create(double[16])
MVector3d transform(Vector3d)
static MMatrix4d productOf(Matrix4d, Matrix4d)
```

#### `com.solibri.geometry.primitive3d.AABB3d`  *(interface, public)*

Axis-aligned bounding box: create/contains/distance/intersects/edges/volume.

```
static AABB3d create(Vector3d,Vector3d)
boolean intersects(AABB3d)
MVector3d getCentroid()
double getVolume()
```

#### `com.solibri.geometry.primitive3d.Box`  *(interface, public)*

Oriented box (rectangle + up direction + height).

```
static Box create(Rectangle3d,double)
```

#### `com.solibri.geometry.primitive3d.Plane`  *(interface, public)*

Infinite plane with projection, distance, intersect(Plane), dihedralAngle.

```
static Plane create(Vector3d point, Vector3d normal)
double signedDistance(Vector3d)
Optional<MLine3d> intersect(Plane)
```

#### `com.solibri.geometry.primitive3d.Polygon3d`  *(interface, public)*

Planar polygon (area, centroid, normal, segment intersect).

```
double getArea()
MVector3d getNormal()
AABB3d getBoundingBox()
```

#### `com.solibri.geometry.primitive3d.Triangle3d`  *(interface, public)*

Triangle with ray/line intersect (raycast atoms).

```
Optional<MVector3d> intersect(Ray3d)
```

#### `com.solibri.geometry.primitive3d.Ray3d`  *(interface, public)*

Origin+direction ray; intersect AABB3d/Triangle3d.

```
static Ray3d create(Vector3d origin, Vector3d direction)
Optional<MVector3d> intersect(Triangle3d)
```

#### `com.solibri.geometry.primitive3d.Line3d`  *(interface, public)*

Infinite line through two points; intersect Plane/AABB3d.

```
static Line3d create(Vector3d, Vector3d)
Optional<MVector3d> getIntersectionPoint(Plane)
```

#### `com.solibri.geometry.mesh.TriangleMesh`  *(interface, public)*

Full mesh algebra: union/intersection/difference, encloses, flipped, shortest paths (3D/horizontal/vertical/2D projection), area, volume.

```
static TriangleMesh fromPolygons(Collection<? extends Polygon3d>)
TriangleMesh intersection(TriangleMesh)
TriangleMesh union(TriangleMesh)
TriangleMesh difference(TriangleMesh)
boolean encloses(Vector3d)
Optional<Segment3d> shortestPathTo(TriangleMesh)
Optional<Segment3d> shortestVerticalPathTo(TriangleMesh)
double getArea()
double getVolume()
```

#### `com.solibri.geometry.primitive2d.Polygon2d`  *(interface, public)*

2D polygon: area, centroid, perimeter, intersect, distance, oriented bounding rectangle.

```
double getArea()
MRectangle2d getOrientedBoundingRectangle()
boolean intersects(Polygon2d)
```

#### `com.solibri.geometry.primitive2d.MPolygon2d`  *(interface, public)*

Mutable Polygon2d (simplify, resize, translate); used by stair/handrail/landing footprints.

```
void simplify(double)
void resize(double)
```

#### `com.solibri.geometry.primitive2d.Area`  *(interface, public)*

Possibly-multiply-connected 2D region: getHoles, getPolygons, getLargestPolygon, getMinimumBoundingRectangle, getSize, getCentroid.

```
static Area create(java.awt.Shape)
List<MPolygon2d> getHoles()
MPolygon2d getLargestPolygon()
```

#### `com.solibri.geometry.primitive2d.Segment2d`  *(interface, public)*

2D line segment with intersect/distance/closestPointTo/sample/shortestSegmentBetween.

```
static Segment2d create(Vector2d, Vector2d)
Optional<MVector2d> intersect(Segment2d)
```

#### `com.solibri.smc.api.model.internal.EntityFactory`  *(interface, **internal**)*

INTERNAL: build CheckingResultsViewRow, Presentation/Slide/Viewpoint/Comment, Visualization, camera states. Single INSTANCE.

```
Viewpoint createViewpoint()
Presentation createPresentation(String)
Slide createSlide()
Visualization createVisualization()
PerspectiveCameraState createPerspectiveCameraState(Vector3d, Vector3d, Vector3d, double)
```

#### `com.solibri.smc.api.model.internal.ProfileFactory`  *(interface, **internal**)*

INTERNAL: hydrate a Profile from a Component (the only way to get parametric profile data).

```
Profile getProfile(Component)
```

</details>

### checking-rules

The com.solibri.smc.api.checking package is the SDK surface for Solibri's automated checking engine: a `Checking` facade (got via `SMC.getChecking()`) drives `Ruleset` trees built from `Rule` nodes; each rule emits typed `Result` objects with `Severity`, `Decision`, `ResultCategory` and per-component slides. Rules expose strongly-typed `RuleParameter<T>` (Boolean/Int/Double/String/Filter/Classification/Enumeration/PropertyReference/Table) plus localized `RuleResources`. Authoring a rule means subclassing `OneByOneRule` (per-component) or `ConcurrentRule` (thread-parallel per-component) and implementing `preCheck` + `check` against a `ComponentSelector`/`CheckingSelection`. `Score` exposes project- and rule-coverage KPIs (passed/failed counts, timestamps) - currently only obtainable via the internal `ScoreHandler.getScores(uuid, industry, from, to)`. Today the toolkit wires only the bare minimum: `runChecking`, `getResults`, `getRulesets`. The biggest opportunities are (a) exposing the rich `Result` graph (decisions, categories, slides, models, custom keys) so we can author/replay coordination presentations from outside the JVM, (b) writing back `Decision`/category overrides for QA workflows, (c) scraping the rule parameter tree to generate IDS-style rule documentation, and (d) capturing `Score` snapshots over time for client KPI dashboards. Notably absent from this SDK region: no public ruleset *loader* (you must use the internal `OpenRuleSetTask` + `CheckingPlugin` + `IdsOpener` to load .cset / .ids), and no public report writer (use internal `Reporting.createReport`).

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `SMC.getChecking().runChecking(boolean)` | POST /skiplum/v1/check (SuperuserServer.handleCheck) ; SolibriClient.run_checking (10876) ; cli.py 'su check --yes' and 'check --yes' |
| `Checking.getResults()` | GET /skiplum/v1/results (SuperuserServer.handleResults) ; SuperuserClient.results ; cli.py 'su results' |
| `Model.getRulesets()` | GET /skiplum/v1/rulesets (SuperuserServer.handleRulesets) ; SuperuserClient.rulesets ; cli.py 'su rulesets' |
| `Ruleset.getUniqueId / getDescription / toJson` | Serialized inside GET /skiplum/v1/rulesets (json=1 includes toJson) |
| `Result.getName / getDescription / getSeverity / getUniqueKey / getLocation / getInvolvedComponents / getRule` | Flattened by resultJson() inside GET /skiplum/v1/results and POST /skiplum/v1/check |
| `Severity (enum)` | String name() emitted in result JSON; SolibriClient.CheckingResult.severity |

**Internal gems:**

- `com.solibri.saf.plugins.checkingplugin.CheckingPlugin` — Singleton (getInstance) for the running checking subsystem - source of truth for state, score-enabled flag, report filler, default paths, and the CheckDesignAction (selection + full); also exposes CheckDesignBySelectionBasketAction for selection-only checking - a feature SMC.getChecking().runChecking(boolean) hides behind a single bool — notable: `getInstance()`, `getState()`, `isScoreEnabled()`, `getReportFiller()`, `getDefaultReportPath()` (+3 more)
- `com.solibri.saf.plugins.checkingplugin.CheckingControllerInterface` — Robot-grade controller: start/stop/abort checking, refresh tables, open IDS models, add models, raise events. Lets us mutate the checking lifecycle from outside (the public SDK only offers runChecking()) — notable: `requestStartCheckingAllEntities()`, `requestStartCheckingSelectedEntities()`, `requestStopCheckingAndWait(Object, boolean)`, `openIdsModel(Resource, boolean)`
- `com.solibri.saf.plugins.checkingplugin.actions.OpenIdsRuleSetAction` — The internal IDS loader (buildingSMART IDS files) - lets the plugin accept .ids as ruleset input. SDK has no public IDS opener — notable: `openIdsModel(File, boolean)`, `openIdsModel(Resource, boolean)`, `setRuleSerializedClassName(String)`
- `com.solibri.saf.plugins.checkingplugin.actions.ConvertToPresentationAction` — Static helper converts checked Constraint results into a Presentation (set of slides) - the proven path for auto-generating coordination decks from checking output — notable: `convertToPresentation(Presentation, Collection<ConstraintControlInterface>, Collection<ProblemStateInterface>)`
- `com.solibri.saf.plugins.checkingplugin.Reporting` — Static report writer used by Solibri's own Write Report action; createReport() produces XML-based reports filtered by ItemFilter and powered by a ReportFiller — notable: `createReport(ReportingSettings, ItemFilter, File, ReportFiller, boolean, HeaderElement[])`, `getSlides(ItemFilter)`
- `com.solibri.saf.plugins.checkingplugin.ScoreHandler` — The only path to retrieve historical Score entries for a project; collectCheckingInfo() builds a snapshot; sendCheckingInfoToServer() ships to Solibri's cloud Score service — notable: `getScores(UUID, Project$Industry, Instant, Instant)`, `collectCheckingInfo()`, `storeScore()`, `sendCheckingInfoToServer(CheckingInfo)`
- `com.solibri.saf.plugins.checkingplugin.ScoreData` — Mutable concrete Score impl - useful when faking/serializing scores in tests or wiring an external KPI feed — notable: `setProjectCoverageScore(int)`, `setRuleCoverageScore(int)`, `setScoreUUID(UUID)`, `setIndustry(String)`
- `com.solibri.saf.plugins.batchplugin.tasks.OpenRuleSetTask` — Batch task to open a .cset/.smcx ruleset by AutorunResource without UI prompts — notable: `run()`
- `com.solibri.saf.plugins.batchplugin.tasks.OpenIdsRuleSetTask` — Same as above for .ids buildingSMART files — notable: `run()`
- `com.solibri.saf.plugins.batchplugin.tasks.UpdateRuleParametersTask` — Batch task to push rule parameter overrides into the open ruleset - the only headless way to retarget rule thresholds (e.g. min headroom) — notable: `run()`
- `com.solibri.saf.plugins.batchplugin.tasks.CheckTask` — Batch task wrapping CheckDesignAction for headless runs — notable: `run()`
- `com.solibri.saf.plugins.batchplugin.tasks.CreatePresentationFromRulesetTask` — Batch task that wraps ConvertToPresentationAction across an entire ruleset — notable: `run()`
- `com.solibri.saf.plugins.batchplugin.tasks.ExcludeSubPartsInRule01Task` — Domain-specific batch task - kept on the radar as proof that ruleset-rewriting happens via these BatchTask helpers — notable: `run()`
- `com.solibri.sae.greenox.RuleCheckingTracking` — Per-constraint timing telemetry - getTrackingDataForRuleCheck() returns {rule->elapsedMs}; ideal for slow-rule profiling — notable: `getTrackingDataForRuleCheck(Constraint, long)`
- `com.solibri.sae.greenox.CheckingContext` — Internal: selection scope passed to the legacy checking engine (gatekeeper entities + selection); wholeModel() factory — notable: `wholeModel()`, `getEntitiesToBeChecked()`, `isCheckSelection()`
- `com.solibri.sae.greenox.RuleLibraryLister` — Internal SPI to enumerate the available rule classes shipped in a library jar - useful for documenting/auto-discovering rules — notable: `getName()`, `getRuleClasses()`, `getAuthor()`
- `com.solibri.score.ScoreView` — The Score panel singleton - hooks onCheckingStarted/onCheckingEnded; could be subscribed to drive external telemetry — notable: `getInstance()`, `onCheckingEnded()`, `onCheckingStarted()`, `update()`

<details><summary>Classes (36) — method signatures</summary>

#### `com.solibri.smc.api.checking.Checking`  *(interface, public)*

Top-level checking facade obtained via SMC.getChecking(); runs checking, exposes results, state, per-rule component sets

```
Checking$State getState()
Collection<Result> getResults()
Set<Component> getCheckedComponents(Rule)
Set<Component> getPassedComponents(Rule)
Set<Component> getFailedComponents(Rule)
Set<Component> getFailedComponents(Rule, Decision, Severity)
Collection<Result> getResults(Rule)
Collection<Result> getResults(Component)
Collection<ResultCategory> getCategories(Rule)
Collection<Result> runChecking(boolean)  // DISRUPTIVE  // Run all rulesets; boolean = checkSelectionOnly
```

#### `com.solibri.smc.api.checking.Checking$State`  *(enum, public)*

IDLE / CHECKING / CANCELING

#### `com.solibri.smc.api.checking.Result`  *(interface, public)*

A single checking finding; immutable builders for decoration, plus mutators for decision/slide; carries components, models, severity, category and visualization

```
Result withLocation(String)
Result withSeverity(Severity)
Result withInvolvedComponent(Component)
Result withInvolvedComponents(Collection<? extends Component>)
Result withInvolvedModel(Model)
Result withVisualization(Consumer<Visualization>)
Result withReplacedVisualization(Consumer<Visualization>)
Result withCustomUniqueKey(String)
Result withCategory(ResultCategory)
Result withCategories(Collection<? extends ResultCategory>)
String getName()
String getDescription()
String getUniqueKey()
Optional<String> getLocation()
List<Consumer<Visualization>> getVisualizationCreators()
Collection<? extends Component> getInvolvedComponents()
Collection<Model> getInvolvedModels()
Severity getSeverity()
Decision getDecision()
void setDecision(Decision)  // DISRUPTIVE
Optional<ResultCategory> getCategory()
Collection<ResultCategory> getCategories()
Optional<Slide> getSlide()
Optional<Slide> setSlide(Slide)  // DISRUPTIVE
Rule getRule()
Map<Component, Slide> getComponentSlides()
Map<Component, Decision> getComponentDecisions()
```

#### `com.solibri.smc.api.checking.Ruleset`  *(interface, public)*

Top-level container for Rule trees; extends CheckingNode; serializable to JSON

```
String getUniqueId()
String getDescription()
String toJson()
```

#### `com.solibri.smc.api.checking.Rule`  *(interface, public)*

Single rule node implementing pre-check/check lifecycle; provides parameters UI and help URL

```
PreCheckResult preCheck(ComponentSelector)
void check(CheckingSelection, ResultFactory)
void postCheck()
UIContainer getParametersUIDefinition()
UIContainer getReportUIDefinition()
boolean hasReportView()
Optional<URL> getHelpUrl()
Optional<CheckingNode> getParent()
List<CheckingNode> getChildren()
String getName()
Map<String,String> getParameterTemplateKeyToIdMap()
Optional<Boolean> updateParametersFromExternalSource()
```

#### `com.solibri.smc.api.checking.Severity`  *(enum, public)*

CRITICAL / MODERATE / LOW / PASSED

#### `com.solibri.smc.api.checking.Decision`  *(enum, public)*

ACCEPTED / REJECTED / UNDEFINED - user verdict on a result/category

#### `com.solibri.smc.api.checking.ResultCategory`  *(interface, public)*

Hierarchical grouping of results with own decision + slide

```
String getUniqueName()
String getDisplayName()
Optional<ResultCategory> getParentCategory()
Set<ResultCategory> getChildCategories()
Optional<Slide> getSlide()
Optional<Slide> setSlide(Slide)  // DISRUPTIVE
Decision getDecision()
void setDecision(Decision)  // DISRUPTIVE
Set<Result> getResults()
Set<Result> getAllResults()
```

#### `com.solibri.smc.api.checking.Score`  *(interface, public)*

KPI snapshot: project-coverage and rule-coverage scores plus passed/failed/checked counts at a time

```
Instant getTime()
int getProjectCoverageScore()
int getTotalComponentsCount()
int getPassedComponentsCount()
int getFailedComponentsCount()
int getRuleCoverageScore()
int getCheckedRuleCount()
int getUncheckedRuleCount()
```

#### `com.solibri.smc.api.checking.CheckingNode`  *(interface, public)*

Tree node base for Ruleset/Rule navigation

```
String getName()
Optional<CheckingNode> getParent()
List<CheckingNode> getChildren()
```

#### `com.solibri.smc.api.checking.CheckingSelection`  *(interface, public)*

Sink passed into Rule.check(); accept/reject components, emit Results

```
void pass(Component)
void pass(Collection<? extends Component>)
void fail(Component, Result)
void fail(Model, Result)
void fail(Collection<? extends Component>, Result)
void passRemaining()
boolean isEmpty()
Set<Component> getRemaining()
boolean isRestricted()
```

#### `com.solibri.smc.api.checking.ResultFactory`  *(interface, public)*

Singleton-style factory for Result and ResultCategory instances inside rule code

```
boolean hasCategory(String)
boolean hasResult(String)
ResultCategory createCategory(String name, String key)
ResultCategory createCategory(String, String, ResultCategory parent)
Result create(String name, String description)
Result create(String, String, ResultCategory)
Result create(String, String, Collection<ResultCategory>)
```

#### `com.solibri.smc.api.checking.ComponentSelector`  *(interface, public)*

Filter-based selector handed to Rule.preCheck; resolves to candidate Components

```
Set<Component> select(ComponentFilter)
boolean hasSelectedComponents()
Model getTargetModel()
```

#### `com.solibri.smc.api.checking.PreCheckResult`  *(interface, public)*

Outcome of Rule.preCheck: relevant/irrelevant + comment; static factories

```
static PreCheckResult createRelevant()
static PreCheckResult createIrrelevant()
static PreCheckResult createRelevant(String)
static PreCheckResult createIrrelevant(String)
boolean isRelevant()
boolean isIrrelevant()
String getComment()
```

#### `com.solibri.smc.api.checking.ConcurrentRule`  *(class, public)*

Abstract base: parallel per-component checking; subclass implements check(Component, ResultFactory)

```
FilterParameter getDefaultFilterParameter()
ComponentFilter getDefaultFilter()
PreCheckResult preCheck()
abstract Collection<Result> check(Component, ResultFactory)
```

#### `com.solibri.smc.api.checking.OneByOneRule`  *(class, public)*

Abstract base: sequential per-component checking

```
FilterParameter getDefaultFilterParameter()
ComponentFilter getDefaultFilter()
PreCheckResult preCheck()
abstract Collection<Result> check(Component, ResultFactory)
```

#### `com.solibri.smc.api.checking.RuleParameter`  *(interface, public)*

Generic base for typed rule parameters

```
String getId()
T getValue()
void setValue(T)
Rule getRule()
```

#### `com.solibri.smc.api.checking.BooleanParameter`  *(interface, public)*

RuleParameter<Boolean>

#### `com.solibri.smc.api.checking.IntParameter`  *(interface, public)*

RuleParameter<Integer>

#### `com.solibri.smc.api.checking.DoubleParameter`  *(interface, public)*

RuleParameter<Double> with PropertyType (unit-aware)

```
PropertyType getPropertyType()
```

#### `com.solibri.smc.api.checking.StringParameter`  *(interface, public)*

RuleParameter<String>

#### `com.solibri.smc.api.checking.ClassificationParameter`  *(interface, public)*

RuleParameter<String> referring to a project classification

#### `com.solibri.smc.api.checking.EnumerationParameter`  *(interface, public)*

RuleParameter<String> drawn from a fixed option list

```
List<String> getOptions()
```

#### `com.solibri.smc.api.checking.FilterParameter`  *(interface, public)*

RuleParameter<ComponentFilter>

#### `com.solibri.smc.api.checking.PropertyReferenceParameter`  *(interface, public)*

RuleParameter<PropertyReference> (pset+property pointer)

#### `com.solibri.smc.api.checking.TableParameter`  *(interface, public)*

RuleParameter<ParameterTable> for tabular config

#### `com.solibri.smc.api.checking.ParameterTable`  *(interface, public)*

Read-only table view with typed columns

```
int getRowCount()
int getColumnCount()
<T> T getValueAt(int, int)
List<String> getColumnIDs()
List<PropertyType> getColumnPropertyTypes()
```

#### `com.solibri.smc.api.checking.RuleParameters`  *(interface, public)*

Per-rule collection: builders for every parameter type plus freeze()

```
static RuleParameters of(Rule)
void freeze()
List<RuleParameter<?>> asList()
BooleanParameter createBoolean(String)
ClassificationParameter createClassification(String)
DoubleParameter createDouble(String, PropertyType)
EnumerationParameter createEnumeration(String, List<String>)
FilterParameter createFilter(String)
IntParameter createInt(String)
PropertyReferenceParameter createPropertyReference(String)
StringParameter createString(String)
TableParameter createTable(String, List<String>, List<PropertyType>)
```

#### `com.solibri.smc.api.checking.RuleResources`  *(interface, public)*

I18N + image resource lookup for a rule

```
static RuleResources of(Rule)
static RuleResources getCommon()
ResourceBundle getJavaResourceBundle()
String getString(String, Object...)
URL getImageUrl(String)
```

#### `com.solibri.smc.api.checking.internal.FilterFactory`  *(interface, **internal**)*

Internal: build geometry-based ComponentFilters (AABB, line, ray)

```
AABBIntersectionFilter aabbFilterOf(AABB3d)
LineIntersectionFilter lineFilterOf(Line3d)
RayIntersectionFilter rayFilterOf(Ray3d)
```

#### `com.solibri.smc.api.checking.internal.PreCheckResultFactory`  *(interface, **internal**)*

Internal singleton mirroring PreCheckResult statics

#### `com.solibri.smc.api.checking.internal.QuantitiesFactory`  *(interface, **internal**)*

Build Quantities (length/area/volume) for a Component

```
Quantities quantitiesOf(Component)
```

#### `com.solibri.smc.api.checking.internal.RelationFactory`  *(interface, **internal**)*

Build Relation specs (Type+Direction) for queries

```
Relation relationOf(Relation$Type, Relation$Direction)
```

#### `com.solibri.smc.api.checking.internal.ResultFactoryUtils`  *(interface, **internal**)*

Construct a ResultFactory for an arbitrary host + obtain the bi-consumer that actually submits failures

```
ResultFactory createResultFactory(Object)
BiConsumer<Result, Collection<? extends Component>> retrieveResultSubmitter(ResultFactory)
```

#### `com.solibri.smc.api.checking.internal.RuleParametersFactory`  *(interface, **internal**)*

Internal accessor for RuleParameters.of(Rule)

#### `com.solibri.smc.api.checking.internal.RuleResourcesFactory`  *(interface, **internal**)*

Internal accessor for RuleResources bundles

</details>

### information-takeoff

The public ITO SDK (com.solibri.smc.api.ito) is strictly read-only: 5 interfaces (InformationTakeoff, ItoDefinition, ItoColumn, ItoRow, ItoValue) let you enumerate definitions already saved in the project, kick off calculation, and read result cells/columns - but offer ZERO authoring. The real authoring power lives in the internal com.solibri.saf.plugins.itoplugin.* package, which is wide open: ItoSettings IS an ItoDefinition (the SMC* impl wraps it) with full mutability (addColumn, insertColumn, removeColumn, moveColumn, setName, setDescription, setFilters, setRows, setTemplate, setReportTitle, update()), a static factory ItoSettings.createIto(List<ClassPropertyAndValue>) for synth from a drag-and-drop list, 13 concrete ItoColumn subclasses (Property/PropertySet/Classification/ComponentType/Count/Material/Profile/Relation/Custom/Color/etc.), grouping/sorting/sum-max-min-average operators, JavaScript+formula-backed columns, and a singleton plugin (ItoPlugin.getInstance().addItoSettings) that registers your synth definition into the running project. Excel reporting and Presentation-from-ITO are equally accessible via ItoReport and PresentationFromITOWorker/Parameters. The biggest opportunity is huge: turn the Skiplum plugin into the only Norwegian ITO authoring REST surface on the market - build an NS3451+MMI quantity ITO from a JSON spec, calculate, export to xlsx, and optionally generate a coordination presentation - all from Python or an LLM agent, no UI clicks. Nothing in this region is wired today; the existing cli `ito` command reads IFC via IfcOpenShell completely outside Solibri.

**Internal gems:**

- `com.solibri.saf.plugins.itoplugin.data.ItoSettings` — THE missing public API. Fully mutable ItoDefinition - add columns, set filters, name, template, calculate, read totals. SMCItoDefinition wraps it so anything you author here is observable through the public SDK. — notable: `createIto(List<ClassPropertyAndValue>)`, `addColumn(ItoColumn)`, `setFilters(...)`, `update()`, `calculateTotal(int)` (+1 more)
- `com.solibri.saf.plugins.itoplugin.ItoPlugin` — Singleton registry to plug a synth ItoSettings into the live project. getInstance().addItoSettings(s, true) makes it visible in the UI and SDK. — notable: `getInstance()`, `addItoSettings(ItoSettings, boolean)`, `getItoModel()`, `waitForTakeoff()`, `getDefaultReportPath()` (+1 more)
- `com.solibri.saf.plugins.itoplugin.data.ItoColumn.createColumn(PropertyReference)` — Smart factory - hand it a PropertySet/Classification/Relation/Identification reference and it returns the right ItoColumn subclass. Drops a layer of dispatch logic from any authoring tool. — notable: `createColumn(PropertyReference)`
- `com.solibri.saf.plugins.itoplugin.data.ItoModelFactory` — ModelFactoryPlugin entry: createModel() returns a fresh empty ItoSettings; closeModel/exit/update hooks tie into Solibri plugin lifecycle. — notable: `createModel()`, `closeModel(ItoSettings)`
- `com.solibri.saf.plugins.itoplugin.report.ItoReport` — Excel POI report engine. Programmatically materialise xlsx from any ItoSettings, with or without a template - no UI prompt needed when called with (File, ItoSettings) + save(null, outFile). — notable: `new ItoReport(InputStream, ItoSettings)`, `writeDefaultTemplate()`, `save(Component, File)`
- `com.solibri.saf.plugins.presentationplugin.PresentationFromITOWorker` — One Runnable away from auto-generating a coordination Presentation from an ITO (slides per row, linked components, auto-zoom). Combined with the BCF API, the basis for full automated coordination deliverables. — notable: `run()`

<details><summary>Classes (37) — method signatures</summary>

#### `com.solibri.smc.api.ito.InformationTakeoff`  *(interface, public)*

Top-level ITO accessor: list/calculate definitions, read result columns/rows.

```
void calculateAll()  // DISRUPTIVE  // Run calculation for every ItoDefinition
void calculate(ItoDefinition)  // DISRUPTIVE  // Run a specific definition
List<ItoColumn> getColumns()
List<ItoRow> getRows()
ItoRow getTotalRow()
List<ItoDefinition> getItoDefinitions()
```

#### `com.solibri.smc.api.ito.ItoDefinition`  *(interface, public)*

READ-ONLY public handle to a takeoff definition (UUID, name, description).

```
UUID getUniqueId()
String getName()
String getDescription()
```

#### `com.solibri.smc.api.ito.ItoColumn`  *(interface, public)*

READ-ONLY column metadata.

```
String getName()
PropertyType getType()
```

#### `com.solibri.smc.api.ito.ItoRow`  *(interface, public)*

READ-ONLY row of ItoValue cells.

```
List<ItoValue<?>> getValues()
```

#### `com.solibri.smc.api.ito.ItoValue<T>`  *(interface, public)*

READ-ONLY cell value paired with its column.

```
ItoColumn getColumn()
T getValue()
```

#### `com.solibri.api.impl.ito.SMCInformationTakeoff`  *(class, **internal**)*

Default impl of InformationTakeoff returned by SMC.getInformationTakeoff(); thin wrapper over ItoPlugin.

```
void calculateAll()
void calculate(ItoDefinition)
List<ItoColumn> getColumns()
List<ItoRow> getRows()
ItoRow getTotalRow()
List<ItoDefinition> getItoDefinitions()
```

#### `com.solibri.api.impl.ito.SMCItoDefinition`  *(class, **internal**)*

Bridge: wraps an internal ItoSettings as a public ItoDefinition. Constructor exposes ItoSettings - the unlock that lets us author internally and surface publicly.

```
SMCItoDefinition(com.solibri.saf.plugins.itoplugin.data.ItoSettings)
UUID getUniqueId()
String getName()
String getDescription()
```

#### `com.solibri.saf.plugins.itoplugin.ItoPlugin`  *(class, **internal**)*

Singleton plugin managing ITO model lifecycle. The entry point for registering authored ItoSettings into the running session.

```
static ItoPlugin getInstance()
ItoModel getItoModel()
boolean addItoSettings(ItoSettings, boolean)  // DISRUPTIVE  // Register a freshly authored ItoSettings
void removeModel(ItoSettings)  // DISRUPTIVE
void setCurrentModel(ItoSettings)  // DISRUPTIVE
void takeoffStarted()
void takeoffReady()
void waitForTakeoff()  // Block until current calc finishes
File getDefaultModelPath()
File getDefaultReportPath()
File getDefaultTemplateFolder()
File getFile(ItoSettings)
ItoView getView()
Collection<StatefulResource> getRecommendedFiles()  // Lists .ito files Solibri suggests for this project
Set<String> getRequiredClassificationNames()
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoModel`  *(class, **internal**)*

Holds the collection of ItoSettings (definitions) in the project plus active selection.

```
ItoSettings[] getItoSettings()
boolean addItoSettings(ItoSettings)  // DISRUPTIVE
boolean removeItoSettings(ItoSettings)  // DISRUPTIVE
boolean hasActiveSettings()
ItoSettings getActiveSettings()
void setActiveSettings(ItoSettings)  // DISRUPTIVE
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoSettings`  *(class, **internal**)*

THE authoring class - fully mutable ItoDefinition. Add columns, filters, set name/template/report, calculate, and read rows/cells. The single most valuable internal class in this region.

```
ItoSettings()
ItoSettings(String name)
ItoSettings(ItoSettings template)  // Copy constructor
static ItoSettings createIto(List<ClassPropertyAndValue>)  // Synth from drag-and-drop selection - mirrors UI drag-from-properties path
void setName(String)
String getName()
void setDescription(String)
void setReportTitle(String)
void addColumn(ItoColumn)
void insertColumn(int, ItoColumn)
void removeColumn(int)
void moveColumn(int, int)
void setColumnName(int, String)
List<ItoColumn> getColumns()
<T extends ItoColumn> ArrayList<T> getColumns(Class<T>)
ArrayList<RelationColumn> getRelationColumns()
Vector<ClassAndPropertyFilter> getFilters()
void setFilters(List<ClassAndPropertyFilter>)
void setUseGrouping(boolean)
void setIncludeShortNames(boolean)
void setUpdateFromSelection(boolean)
void setPlainReport(boolean)
void setTemplate(File)
void setTemplateResourse(Resource)
Resource getTemplateResource()
void setFile(File)
Collection<IComponent> getItoComponents(boolean)
ItoRow[] getRows()
void setRows(Collection<ItoRow>)
void removeRows()
void sortRows()
void update()  // DISRUPTIVE  // Re-run column calculations
void update(boolean)  // DISRUPTIVE
void update(boolean, JavaScriptHandlingMap)  // DISRUPTIVE
Optional<Number> calculateTotal(int)
Optional<String> calculateTotalAsString(int)
Optional<Number> calculateSelectedTotal(int, int[])
void delete()  // DISRUPTIVE
Object getValue(int row, int col)
int getColumnIndex(String)
Map<String,Exception> getColumnExceptions()
boolean isInSync()
List<PluginTask> getPluginTasks()
void setPluginTasks(List<PluginTask>)
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoModelFactory`  *(class, **internal**)*

ModelFactoryPlugin for ITO - createModel() returns a fresh ItoSettings.

```
ItoSettings createModel()
void closeModel(ItoSettings)
void setCalculators(IComponent)
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoColumn`  *(class, **internal**)*

Abstract base for every column variety. Holds operator (SUM/MAX/MIN/AVG), grouping/sort flags, optional JS/formula. Static createColumn(PropertyReference) factory builds the right concrete subclass from a property reference.

```
ItoColumn(String name)
static Optional<ItoColumn> createColumn(PropertyReference)
String getName()
void setName(String)
void setGrouping(boolean)
boolean isUsedInGrouping()
void setAscending(boolean)
void setOperator(ItoColumnValueOperator)
ItoColumnValueOperator getOperator()
boolean isScripted()
boolean isFormula()
Optional<String> getJavaScript()
PropertyType getPropertyType()
Object getValue(IComponent)
Class<?> getColumnClass()
void setReferredColumn(ItoColumn)
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoColumnType`  *(enum, **internal**)*

Catalogue of column kinds: COMPONENT, COMPONENT_COUNT, IDENTIFICATION, LOCATION, QUANTITY, SPACE_BOUNDARY_AREAS, PROPERTY_SET, MATERIAL, PROFILE, CLASSIFICATION, HYPERLINK, RELATION, FORMULA, COLOR, JAVASCRIPT.

```
static ItoColumnType[] values()
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoColumnValueOperator`  *(enum, **internal**)*

Aggregation operator (SUM/MAX/MIN/AVERAGE) applied per ItoColumn when grouping.

```
static ItoColumnValueOperator[] values()
Number getValue(ItoColumn, Collection<IComponent>)
```

#### `com.solibri.saf.plugins.itoplugin.data.PropertyColumn`  *(class, **internal**)*

Generic field-by-name column.

```
PropertyColumn(String name)
String getFieldName()
void setFieldName(Object)
```

#### `com.solibri.saf.plugins.itoplugin.data.PropertySetColumn`  *(class, **internal**)*

Column reading from an IFC PropertySet.PropertyName pair. Supports formula and JavaScript variants. The workhorse for NS3457/NS3451/MMI takeoffs.

```
PropertySetColumn(PropertySetPropertyReference)
PropertySetColumn(String pset, String prop, Class<?>)
String getPropertySetName()
void setPropertySetName(String)
String getPropertyName()
void setPropertyName(String)
void setFormula(String)
Optional<String> getFormula()
Optional<String> getJavaScript()
void setColumnClass(Class<?>)
```

#### `com.solibri.saf.plugins.itoplugin.data.ClassificationColumn`  *(class, **internal**)*

Column that resolves a named classification (NS3451, NS3457, MMI) per component.

```
ClassificationColumn(String name)
String getClassification()
void setClassification(String)
void setValuesToOneRow(boolean)
```

#### `com.solibri.saf.plugins.itoplugin.data.ClassificationColorColumn`  *(class, **internal**)*

Per-classification color column (for visual ITO mapping).

```
ClassificationColorColumn(String name)
String getClassification()
void setClassification(String)
```

#### `com.solibri.saf.plugins.itoplugin.data.CustomColorColumn`  *(class, **internal**)*

Color cell driven by transparency + material - drives the 3D coloring when running a presentation from ITO.

```
CustomColorColumn()
int getTransparency()
void setTransparency(int)
void resetValues(ItoSettings)
void setMaterialInfo(ItoSettings, int, MaterialInfo)
```

#### `com.solibri.saf.plugins.itoplugin.data.ComponentTypeColumn`  *(class, **internal**)*

Renders the component type (IfcWall, IfcPipeSegment...).

```
ComponentTypeColumn()
```

#### `com.solibri.saf.plugins.itoplugin.data.CountColumn`  *(class, **internal**)*

Pure count cell (groups -> count of components).

```
CountColumn()
```

#### `com.solibri.saf.plugins.itoplugin.data.CustomColumn`  *(class, **internal**)*

Wraps a custom Information<?> calculator (SDK Info plugin) into an ITO cell.

```
CustomColumn()
CustomColumn(Information<?>)
CustomColumn(String name, Information<?>)
Information<?> getInformation()
void setInformation(Information<?>)
String getInformationClassName()
```

#### `com.solibri.saf.plugins.itoplugin.data.IdentificationTypeColumn`  *(class, **internal**)*

GUID / name / type-name / etc. picked by IdentificationType enum.

```
IdentificationTypeColumn(IdentificationType)
void setIdentificationType(IdentificationType)
String getStringValue(IComponent)
```

#### `com.solibri.saf.plugins.itoplugin.data.MaterialColumn`  *(class, **internal**)*

Aggregate material property column (with optional name-pattern filter).

```
MaterialColumn()
void setMaterialProperty(SMaterial.Property)
void setNamePattern(String)
Object getRowMaterials(ItoRow)
```

#### `com.solibri.saf.plugins.itoplugin.data.ProfileColumn`  *(class, **internal**)*

Structural profile column (PROFILE_NAME / PROFILE_TYPE / PARAMETRIC_PROFILE_TYPE).

```
ProfileColumn()
String getProperty()
void setProperty(String)
```

#### `com.solibri.saf.plugins.itoplugin.data.RelationColumn`  *(class, **internal**)*

Follows a user-defined relation (e.g. door-in-wall, equipment-in-room) and emits related component info per row.

```
RelationColumn(RelationPropertyReference)
RelationColumn(RelationReference, Class<Entity>)
void setRelationReference(RelationPropertyReference)
Collection<IComponent> getRelated(ItoRow)
```

#### `com.solibri.saf.plugins.itoplugin.data.ItoRow`  *(class, **internal**)*

Row impl - one or more grouped components plus cell values.

```
ItoRow(ItoSettings, IComponent)
ItoRow(ItoSettings, Collection<IComponent>)
int getId()
ArrayList<IComponent> getComponents()
Object getValue(int)
Object getValue(String)
void setValue(Object, int)
```

#### `com.solibri.saf.plugins.itoplugin.data.IItoRow`  *(interface, **internal**)*

Minimal row read interface.

```
Object getValue(String)
```

#### `com.solibri.saf.plugins.itoplugin.report.ItoReport`  *(class, **internal**)*

Excel report generator (POI). Reads .ito_template files, can write a default template if none, then save(File). The path to programmatic xlsx export.

```
static ItoReport readTemplate(Component, File, ItoSettings)
static ItoReport readTemplate(Component, String, InputStream, ItoSettings)
ItoReport(File, ItoSettings)
ItoReport(InputStream, ItoSettings)
ItoReport(ItoSettings, AbstractExcelReport.Format)
boolean save(Component, File)  // DISRUPTIVE
void writeDefaultTemplate()
void writeDefaultTemplateForAllITOs()
void writeDefaultTemplateForSingleITO(ItoSettings)
void writePlainReport()
Sheet writeTemplateFields(Sheet)
Sheet getSheet(String)
void setReportTitle(String)
void setItoSettings(ItoSettings)
```

#### `com.solibri.saf.plugins.itoplugin.report.ExpectedRuleSets`  *(class, **internal**)*

Lookup helper: tells whether the ruleset an ITO expects is loaded (status enum) and finds the ConstraintModel.

```
static ConstraintModel findRuleSet(String)
static ExpectedRuleSetStatus getStatus(String)
```

#### `com.solibri.saf.plugins.presentationplugin.PresentationFromITOWorker`  *(class, **internal**)*

Runnable that turns an ItoSettings + parameters into a Presentation (slides per row with linked components and zoom). Closes the loop ITO -> coordination deliverable.

```
PresentationFromITOWorker(PresentationFromItoParameters)
void run()  // DISRUPTIVE
static <T extends IItoRow> int collectSelectedChildRows(int, T[], ArrayList<CustomColorColumn>, ArrayList<RelationColumn>, ArrayList<T>)
static boolean colorColumnHasCorrectTransparency(IItoRow, ArrayList<CustomColorColumn>)
```

#### `com.solibri.saf.plugins.presentationplugin.PresentationFromItoParameters`  *(class, **internal**)*

Bag of options for PresentationFromITOWorker (title column, desc column, include-previous-row, link components, link related, auto-zoom, prefix, name).

```
PresentationFromItoParameters()
void setIto(ItoSettings)
void setPresentationName(String)
void setPrefix(String)
void setTitleColumn(ItoColumn)
void setDescColumn(ItoColumn)
void setIncPreviousRow(boolean)
void setLinkComponents(boolean)
void setLinkCompRelations(boolean)
void setAutoZoom(boolean)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenItoTask`  *(class, **internal**)*

BatchTask to load a saved .ito file (from an AutorunResource).

```
OpenItoTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CloseItoTask`  *(class, **internal**)*

BatchTask: close an ITO by name.

```
CloseItoTask(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CalculateItoTask`  *(class, **internal**)*

BatchTask: calculate an ITO by name.

```
CalculateItoTask(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.ReportItoTask`  *(class, **internal**)*

BatchTask: ITO name + report title + template autorun resource + output autorun resource.

```
ReportItoTask(String, String, AutorunResource, AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.PresentationFromITOTask`  *(class, **internal**)*

BatchTask wrapper around PresentationFromITOWorker - run a presentation generation as part of a batch.

```
PresentationFromITOTask(PresentationFromItoParameters)
```

</details>

### threed-layouts-views

The threed-layouts-views region is Solibri's surface for driving the 3D viewer (camera, snapshot, visibility, transient visualization overlays), the workspace tab system ("Layouts" = the named view configurations switchable from the top of the UI), the built-in View singletons (3D, Info, Checking Results, Issues, ITO, Presentation, etc.) and the per-component Info panel. The biggest unrealized opportunity is the snapshot+camera+visualization triad: SMC.get3D() exposes get3DSnapshot() (returns a java.awt.image.BufferedImage), setCameraState (perspective AND orthogonal projections from createCameraState/PerspectiveCameraState.create/OrthogonalCameraState.create), plus Visualization (color-coded component overlays + section planes + footprints). None of these are wired in the SuperuserServer today (only the built-in 10876 /threed/camera GET/POST is used for camera get/set). Wiring snapshot + visualization + layout switching unlocks fully scripted, deterministic 2D/3D documentation pipelines (per-issue PNG, color-coded property heatmaps, MMI status renders, layout-driven coordination tabs) that the built-in 10876 REST and BCF cameras can't produce. CheckingResultsView/IssuesView also expose write-side hooks (chooseRows/CustomColumn show/hide, getMarkedSlides) that let a plugin drive selections in the results table from outside Solibri, today entirely manual.

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `ThreeD camera read (SMC.get3D().getCamera())` | SolibriClient.get_camera() / cli.py 'camera' via built-in REST GET /threed/camera (port 10876) - NOT via plugin |
| `ThreeD camera write (Camera.setLocation/lookAt or ThreeD.setCameraState)` | SolibriClient.set_camera(dict) via built-in REST POST /threed/camera (port 10876) - NOT via plugin |
| `com.solibri.smc.api.ui.View` | SuperuserServer implements View as its bootstrap entry point (registered via META-INF/services to start the :10999 HTTP server) |

**Internal gems:**

- `com.solibri.smc.api.layout.LayoutUpdater` — Singleton that mutates an existing Layout's split tree at runtime (otherwise Layout/Split are read-only). The only way to script 'add a new tab with this configuration' without restarting Solibri. — notable: `INSTANCE`, `update(Layout, List<Split>)`
- `com.solibri.smc.api.ui.internal.UIComponentFactory` — Singleton that constructs all the public UIContainer/UIImage/UILabel/UIRadioButtonPanel/UIRuleParameter widgets - the actual entry point because public types only expose interfaces. — notable: `INSTANCE`, `createUIContainerVertical(...)`, `createUIRuleParameter(RuleParameter<?>)`
- `com.solibri.smc.api.info.internal.InformationParametersFactory` — Singleton wiring a ParametricInformation extension to its live parameter set; needed when shipping a parametric Information plugin. — notable: `INSTANCE`, `getInformationParametersInstance(ParametricInformation<?>)`
- `com.solibri.smc.api.visualization.Visualization` — Public but adjacent; the workhorse called from ThreeD.setVisualization for color-coded overlays, section planes, and footprints. Combined with snapshot this powers status renders. — notable: `addComponentWithColor`, `addSection(Plane)`, `addFootprints(Component)`, `setVisibleComponents`

<details><summary>Classes (41) — method signatures</summary>

#### `com.solibri.smc.api.threed.ThreeD`  *(interface, public)*

Top-level 3D viewer handle returned by SMC.get3D(). Snapshot, visibility, camera, visualization overlay.

```
void showAll()  // DISRUPTIVE  // Reset visibility (show all components).
Optional<BufferedImage> get3DSnapshot()  // Capture the current 3D viewport as a Java BufferedImage. Read-only.
Camera getCamera()  // Live mutable camera handle (location/direction/lookAt).
Collection<Component> getVisibleComponents()  // Currently visible components in 3D.
Collection<Component> getHiddenComponents()  // Currently hidden components in 3D.
void hideComponent(Component)  // DISRUPTIVE  // Hide a single component in 3D.
void showComponent(Component)  // DISRUPTIVE  // Show a single component in 3D.
void setVisualization(Visualization)  // DISRUPTIVE  // Apply a transient overlay (per-component color, sections, footprints).
Visualization createVisualization()  // Empty Visualization scoped to this 3D viewer.
void setCameraState(CameraState)  // DISRUPTIVE  // Snap the camera to a saved Perspective- or OrthogonalCameraState.
CameraState createCameraState()  // Snapshot the current camera as an immutable CameraState (for round-tripping).
```

#### `com.solibri.smc.api.threed.Camera`  *(interface, public)*

Mutable live camera. lookAt(Collection<Component>) is the workhorse for 'frame these components'.

```
Vector3d getLocation() / void setLocation(Vector3d)
Vector3d getDirection()
Vector3d getUpVector() / void setUpVector(Vector3d)
void lookAt(Vector3d)
void lookAt(Component)
void lookAt(Collection<Component>)  // Frame a set of components - perfect for per-clash snapshots.
void lookAtComponentsFromPosition(Collection<Component>, Vector3d)  // Frame components from a chosen camera position (control angle).
```

#### `com.solibri.smc.api.threed.CameraState`  *(interface, public)*

Immutable camera snapshot (location/direction/upDirection). Base for Perspective/Orthogonal subtypes.

```
Vector3d getLocation() / getUpDirection() / getDirection()
```

#### `com.solibri.smc.api.threed.PerspectiveCameraState`  *(interface, public)*

Camera state with field-of-view; factory create() for use with setCameraState.

```
double getFieldOfView()
static PerspectiveCameraState create(Vector3d location, Vector3d direction, Vector3d up, double fov)
```

#### `com.solibri.smc.api.threed.OrthogonalCameraState`  *(interface, public)*

Orthographic camera with view-to-world scale. Critical for plan/section deterministic renders.

```
double getViewToWorldScale()
static OrthogonalCameraState create(Vector3d location, Vector3d direction, Vector3d up, double scale)
```

#### `com.solibri.smc.api.layout.Layouts`  *(interface, public)*

Workspace-tab manager. SMC.getLayouts(). switchToLayout drives the top-of-window layout switcher.

```
List<Layout> getLayouts()
void addLayout(Layout)  // DISRUPTIVE
void switchToLayout(String name)  // DISRUPTIVE
```

#### `com.solibri.smc.api.layout.Layout`  *(interface, public)*

A single named layout (workspace tab) - tree of Splits, each containing LayoutViews.

```
String getName()
List<Split> getSplits()
```

#### `com.solibri.smc.api.layout.Split`  *(interface, public)*

Horizontal/vertical split node containing nested splits or LayoutViews with weights.

```
Split.Orientation getOrientation()
double getWeight()
List<Split> getSubSplits()
List<LayoutView> getViews()
```

#### `com.solibri.smc.api.layout.Split$Orientation`  *(enum, public)*

HORIZONTAL / VERTICAL.

#### `com.solibri.smc.api.layout.LayoutView`  *(interface, public)*

A View placed in a Split slot. State (MAXIMIZED/DOCKED/UNDOCKED/HIDDEN) + weight.

```
View getView()
double getWeight()
LayoutView.State getState()
```

#### `com.solibri.smc.api.layout.LayoutView$State`  *(enum, public)*

MAXIMIZED, DOCKED, UNDOCKED, HIDDEN.

#### `com.solibri.smc.api.layout.LayoutUpdater`  *(interface, **internal**)*

Singleton INSTANCE that updates an existing Layout's split tree in place (otherwise immutable).

```
static final LayoutUpdater INSTANCE
void update(Layout, List<Split>)  // DISRUPTIVE  // Replace a Layout's split tree at runtime.
```

#### `com.solibri.smc.api.ui.View`  *(interface, public)*

Extension-point SPI: implement + register via SuperuserServer's pattern, get a Swing JPanel slot + lifecycle callbacks (model loaded/closed, checking started/ended, basket selection changed, ITO start/end, ruleset add/remove). Returned from getCustomViews()/InternalViews constants.

```
String getUniqueId()
default String getName()
default Icon getIcon()
default Optional<JToolBar> getToolBar()
void initializePanel(JPanel)  // The only required UI hook - own a Swing panel inside Solibri.
default boolean onApplicationExit()
default void onModelLoaded/Closed/Modified/SubModelUpdated/SubModelAdded(Model)
default void onBasketSelectionChanged(Set<Component> added, Set<Component> removed)  // Live selection callback - perfect for a sidecar HTTP push.
default void onCheckingStarted/Ended()
default void onComponentChosen/ComponentsZoomedTo/ComponentsHidden(...)
default void onItoStarted/onItoFinished()
default void onRulesetAdded/Removed(Ruleset)
default void onViewShown()
```

#### `com.solibri.smc.api.ui.views.InternalViews`  *(interface, public)*

Singletons for every built-in view tab: MODEL_TREE, THREED, INFO, SELECTION, RESULT_SUMMARY, RULESETS, CHECKING_RESULTS, FILTERING, CLASSIFICATION, PRESENTATION, ISSUES, ISSUE_DETAILS, ISSUE_SORTER, INFORMATION_TAKEOFF, CHECKED_COMPONENTS, COMPARTMENTATION, USER_DEFINED_RELATIONS, HYPERLINK, PARAMETERS. Plus typed getters for the writable ones.

```
static final View THREED_VIEW / INFO_VIEW / CHECKING_RESULTS_VIEW / ISSUES_VIEW / INFORMATION_TAKEOFF_VIEW / PRESENTATION_VIEW / RULESETS_VIEW / SELECTION_VIEW / RESULT_SUMMARY_VIEW / MODEL_TREE_VIEW / FILTERING_VIEW / CLASSIFICATION_VIEW / ISSUE_DETAILS_VIEW / ISSUE_SORTER_VIEW / CHECKED_COMPONENTS_VIEW / COMPARTMENTATION_VIEW / USER_DEFINED_RELATIONS_VIEW / HYPERLINK_VIEW / PARAMETERS_VIEW
CheckingView getCheckingView()
CheckingResultsView getCheckingResultsView()
IssuesView getIssuesView()
```

#### `com.solibri.smc.api.ui.views.checking.CheckingView`  *(interface, public)*

Drive ruleset tree selection from code.

```
Set<CheckingNode> getChosenNodes()
void chooseNodes(Set<CheckingNode>)  // DISRUPTIVE
```

#### `com.solibri.smc.api.ui.views.checkingresults.CheckingResultsView`  *(interface, public)*

Drive the Results table: pick rows, toggle Main/Hyperlink/Viewpoint/custom columns.

```
Set<CheckingResultsViewRow> getChosenRows()
void chooseRows(Set<CheckingResultsViewRow>)  // DISRUPTIVE
MainColumn/HyperlinkColumn/ViewpointColumn getMainColumn/getHyperlinkColumn/getViewpointColumn()
void chooseRows(Collection<Slide>)  // Select results by BCF slide id.
void showCustomColumn(String)/void hideCustomColumn(String)  // DISRUPTIVE
```

#### `com.solibri.smc.api.ui.views.issues.IssuesView`  *(interface, public)*

Read access to BCF slides currently in the Issues view (all/selected/marked).

```
Collection<Slide> getAllSlides()
Collection<Slide> getSelectedSlides()
Collection<Slide> getMarkedSlides()
```

#### `com.solibri.smc.api.ui.views.checkingresults.CheckingResultsViewRow`  *(interface, public)*

A row in the Results view; build with create(Result) / create(ResultCategory). Read-only accessors for component + slide.

```
Optional<Result> getResult() / Optional<ResultCategory> getResultCategory() / Optional<Component> getComponent() / Optional<Slide> getSlide()
static CheckingResultsViewRow create(Result)/(Result,Component)/(ResultCategory)
```

#### `com.solibri.smc.api.ui.views.checkingresults.MainColumn`  *(interface, public)*

Toggle visibility of the Main column in Results.

```
void show()/void hide()  // DISRUPTIVE
```

#### `com.solibri.smc.api.ui.views.checkingresults.HyperlinkColumn`  *(interface, public)*

Toggle Hyperlink column.

```
void show()/void hide()  // DISRUPTIVE
```

#### `com.solibri.smc.api.ui.views.checkingresults.ViewpointColumn`  *(interface, public)*

Toggle Viewpoint thumbnail column.

```
void show()/void hide()  // DISRUPTIVE
```

#### `com.solibri.smc.api.ui.views.CheckingResultsColumn`  *(interface, public)*

Marker subtype of CustomColumn<CheckingResultsViewRow> - implement to add your own column to the Results table.

#### `com.solibri.smc.api.ui.views.CustomColumn`  *(interface, public)*

SPI for adding a column to a built-in table view (text, icon, tooltip, comparator, click/selection listeners).

```
String getUniqueId()/getHeaderText()/Icon getHeaderIcon()/Icon getIcon(T,bool,bool)/String getText(T,bool,bool)/Comparator<T> getComparator()
default MouseListener getMouseListener(T)/ListSelectionListener getSelectionListener(...)
```

#### `com.solibri.smc.api.ui.views.CellRenderer`  *(interface, public)*

Generic AWT cell renderer companion for CustomColumn.

```
java.awt.Component getComponent(T value, boolean selected, boolean focused)
```

#### `com.solibri.smc.api.ui.ProgressBar`  *(interface, public)*

Auto-closeable progress bar handle (SMC.createProgressBar(String name, int total)). Mirror long-running plugin work into the UI.

```
void increment()
void increment(int)
void close()
```

#### `com.solibri.smc.api.ui.UIContainer`  *(interface, public)*

Swing-equivalent container for plugin panels. Title + border + addComponent.

#### `com.solibri.smc.api.ui.UIContainerHorizontal / UIContainerVertical`  *(interface, public)*

Layout variants of UIContainer.

#### `com.solibri.smc.api.ui.UIComponent`  *(interface, public)*

Root of plugin UI building blocks; enabler/invisible-when-disabled wiring.

#### `com.solibri.smc.api.ui.UIImage / UILabel`  *(interface, public)*

Image + label widgets for plugin UIs.

#### `com.solibri.smc.api.ui.UIRadioButtonPanel / UIRadioButtonPanelHorizontal / UIRadioButtonPanelVertical`  *(interface, public)*

Radio-button group bound to an EnumerationParameter (rule parameter UI).

#### `com.solibri.smc.api.ui.UIRuleParameter`  *(interface, public)*

Auto-rendered UI for a checking RuleParameter.

```
static UIRuleParameter create(RuleParameter<?>)
```

#### `com.solibri.smc.api.ui.RuleParameterCustomUi`  *(interface, public)*

Plug a custom java.awt.Component into a rule parameter slot.

#### `com.solibri.smc.api.ui.BorderType`  *(enum, public)*

Border options for UIContainer.

#### `com.solibri.smc.api.ui.internal.UIComponentFactory`  *(interface, **internal**)*

Singleton INSTANCE that constructs UIContainer/UIImage/UILabel/UIRadioButtonPanel/UIRuleParameter for plugin rule UIs.

```
static final UIComponentFactory INSTANCE
```

#### `com.solibri.smc.api.info.InfoDisplayer`  *(interface, public)*

SMC.getInfoDisplayer().showInfo(Component) opens the Info panel for a single component. Tiny but high-leverage for guided review.

```
void showInfo(Component)  // DISRUPTIVE
```

#### `com.solibri.smc.api.info.Information`  *(interface, public)*

SPI to publish a virtual property/info value for a Component (text, double, type-aware). Shows up in Info panel + ITO + custom columns.

```
String getUniqueId()
default String getName()
Optional<T> getInformation(Component)
default String getInformationAsString(Component)
default Object getCustomInformation(Component)
default PropertyType getType()
```

#### `com.solibri.smc.api.info.ParametricInformation`  *(interface, public)*

Subtype of Information that exposes user-tweakable parameters in the UI (via InformationParameters).

#### `com.solibri.smc.api.info.InformationParameters`  *(interface, public)*

Factory + container for typed parameters on a ParametricInformation (createBoolean/Double/Int/String).

```
static InformationParameters of(ParametricInformation<?>)
List<InformationParameter<?>> asList()
BooleanInformationParameter createBoolean(String id, boolean default_)
DoubleInformationParameter createDouble(String id, PropertyType, double)
IntInformationParameter createInt(String, int)
StringInformationParameter createString(String, String)
```

#### `com.solibri.smc.api.info.InformationParameter`  *(interface, public)*

Typed value holder with id + setStringValue (for declarative loading).

#### `com.solibri.smc.api.info.BooleanInformationParameter / DoubleInformationParameter / IntInformationParameter / StringInformationParameter`  *(interface, public)*

Concrete parameter types; DoubleInformationParameter additionally exposes its PropertyType (LENGTH, VOLUME, ...).

#### `com.solibri.smc.api.info.internal.InformationParametersFactory`  *(interface, **internal**)*

INSTANCE that hands out the live InformationParameters bound to a ParametricInformation registered in the running app.

```
static final InformationParametersFactory INSTANCE
```

</details>

### coordination-bcf-presentations

Solibri's coordination authoring surface is a small but expressive object graph: Presentation (named container) -> Slide (one BCF topic, with status/stage/priority/labels/responsibilities/dueDate/components/comments/viewpoints/Result-link) -> Viewpoint (CameraState + PNG image + Visualization snapshot + comments) -> Comment (text + author + creation date). All three core types are interfaces with static create() factories, so the plugin can fabricate them in-process and add them to a Presentation, then export the whole set via BcfXml.exportBcfXml(version, scope, OutputStream) - V1_0/V2_0/V2_1/V3_0, scope ALL/SELECTED/MARKED. SMC.getPresentations() and SMC.getBcfXml()/getBCF() are the entry points, plus IssuesView.getAllSlides()/getSelectedSlides()/getMarkedSlides() for UI-state-aware reads. The bcflive package adds live-server sync (BCFServer auth, getExportedSlides/getSlides by UUID, coordination value lists - allowed labels/priorities/stages/topic statuses/topic types/users). Internal SlideModifiedListener gives fine-grained change events (status/responsibility/comment/viewpoint added etc.) that would let a plugin push real-time deltas to webhooks. Currently the built-in REST exposes /bcfxml/{version} + create_presentation + add_slide, but only with minimal fields - the Slide interface has ~25 settable properties (stage/priority/responsibilities/labels/dueDate/components/externalIds/result link) that are untapped, no Viewpoint creation with CameraState+PNG, no listener-based event stream, and no bcflive server bridge. Biggest opportunity: a richly-typed plugin endpoint that creates full Slide+Viewpoint+Comment graphs from JSON (including camera state and base64 PNG), wired to LLM-authored coordination narratives.

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `com.solibri.smc.api.issues.BcfXml.exportBcfXml(BcfVersion.V2_1, ALL, OutputStream)` | Built-in Solibri REST GET /bcfxml/{version}; solibri_loop/superuser.py SolibriClient.export_bcf(version='two_one'); cli.py 'bcf OUT'. BcfScope is hardcoded ALL; SELECTED/MARKED not exposed. |
| `com.solibri.smc.api.communication.Presentation.create(String)` | Built-in REST POST /presentations (name, prefix); SolibriClient.create_presentation(name, prefix). NOT in SuperuserServer/cli. |
| `com.solibri.smc.api.communication.Slide (minimal subset: presentationId+title+description+components)` | Built-in REST POST /slides; SolibriClient.add_slide(slide_dict). Only ~4 of ~25 Slide setters are reachable - no status/stage/priority/labels/responsibilities/dueDate/viewpoints/comments/externalIds. |
| `com.solibri.smc.api.SMC.getPresentations / getBcfXml / getBCF` | Indirectly via the two built-in REST endpoints above. Not exposed in SuperuserServer.java (the keys list at line 345 mentions 'presentation' but only as a key name for an unrelated reflection probe). |

**Internal gems:**

- `com.solibri.smc.api.internal.listeners.SlideModifiedListener` — The only push channel for BCF activity inside Solibri - 21 callback hooks covering every field on Slide plus Viewpoint/Comment/Component CRUD. With a plugin registering this on SMC.getInternalViews() / Listeners, you can stream coordination changes to webhooks, Slack, Notion, ACC Issues in real time without polling. — notable: `statusModified`, `commentAdded(Slide,Comment)`, `commentAdded(Slide,Viewpoint,Comment)`, `viewpointAdded`, `componentsAdded` (+1 more)
- `com.solibri.smc.api.internal.SlideEventType` — Coarse event type enum likely consumed by an internal listener registry next to SlideModifiedListener (DATA_CHANGED/TITLE_CHANGED/DESCRIPTION_CHANGED). — notable: `values`
- `com.solibri.smc.api.internal.listeners.Listeners` — Registry facade for installing internal listeners (slide, checking, etc.) - this is how a plugin actually subscribes a SlideModifiedListener. — notable: `(register/unregister wrappers - see internal-services region)`
- `com.solibri.smc.api.bcflive.BcfApi` — Read-only handle to the live BCF subsystem already running inside Solibri - cheaper to wire than reimplementing OAuth+REST clients for Solibri-hosted servers (BIMcollab, BIMTrack, ACC). — notable: `getBCFServerList`, `authenticate`, `getSlides`, `getExportedSlides`

<details><summary>Classes (14) — method signatures</summary>

#### `com.solibri.smc.api.communication.Presentation`  *(interface, public)*

Named container of Slides; the BCF topic-set. Static factory; tracking-id prefix sets per-slide id scheme.

```
public static Presentation create()  // Create unnamed presentation in current project.
public static Presentation create(String name)  // DISRUPTIVE  // Create named presentation.
public Collection<Slide> getSlides()
public boolean addSlide(Slide)  // DISRUPTIVE
public boolean removeSlide(Slide)  // DISRUPTIVE
public String getName()
public void setTrackingIdPrefix(String)  // Sets the prefix used for sequential slide tracking ids (e.g. 'CLASH-').
public String getUniqueId()
```

#### `com.solibri.smc.api.communication.Slide`  *(interface, public)*

One BCF topic / coordination issue. Holds metadata, components, viewpoints, comments, optional Result link.

```
public static Slide create()  // DISRUPTIVE
public Collection<Presentation> getPresentations()
public String getUniqueId()
public String getTitle()
public void setTitle(String)  // DISRUPTIVE
public String getType()
public void setType(String)  // DISRUPTIVE  // BCF topic type (free-form string, constrained by BCFCoordinationValues).
public String getStatus()
public void setStatus(String)  // DISRUPTIVE
public Optional<String> getStage()
public void setStage(String)  // DISRUPTIVE
public Optional<String> getPriority()
public void setPriority(String)  // DISRUPTIVE
public Optional<String> getDescription()
public void setDescription(String)  // DISRUPTIVE
public Collection<String> getResponsibilities()
public void setResponsibilities(List<String>)  // DISRUPTIVE
public Set<String> getLabels()
public void setLabels(Set<String>)  // DISRUPTIVE
public Optional<LocalDate> getDueDate()
public void setDueDate(LocalDate)  // DISRUPTIVE
public Set<Component> getComponents()
public void setComponents(Set<Component>)  // DISRUPTIVE
public boolean addComponent(Component)  // DISRUPTIVE
public boolean removeComponent(Component)  // DISRUPTIVE
public List<Viewpoint> getViewpoints()
public boolean addViewpoint(Viewpoint)  // DISRUPTIVE
public boolean removeViewpoint(Viewpoint)  // DISRUPTIVE
public List<Comment> getComments()
public boolean addComment(Comment)  // DISRUPTIVE
public boolean removeComment(Comment)  // DISRUPTIVE
public Optional<Result> getCheckingResult()  // Linked checking-Result if slide originated from a rule violation.
public Optional<ResultCategory> getResultCategory()
public Optional<Pair<Result,Component>> getResultAndComponent()
public boolean addExternalID(UUID)  // DISRUPTIVE  // Persist link to external system (BCF server / ACC / etc).
public boolean removeExternalID(UUID)  // DISRUPTIVE
public Set<UUID> getExternalIds()
public OffsetDateTime getModifiedTimeInExternalSystem()
public void setModifiedTimeInExternalSystem(OffsetDateTime)  // DISRUPTIVE
public boolean hasLocalChanges()
public void setHasLocalChanges(boolean)  // DISRUPTIVE
```

#### `com.solibri.smc.api.communication.Viewpoint`  *(interface, public)*

One snapshot inside a Slide: camera state, PNG image, optional Visualization (component colors/visibility), comments.

```
public static Viewpoint create()  // DISRUPTIVE
public static Viewpoint create(UUID externalId)  // DISRUPTIVE
public String getUniqueId()
public Optional<CameraState> getCameraState()
public void setCameraState(CameraState)  // DISRUPTIVE
public Optional<BufferedImage> getImage()
public void setImage(BufferedImage)  // DISRUPTIVE
public Optional<byte[]> getImagePngBytes()
public void setImagePngBytes(byte[])  // DISRUPTIVE
public Optional<Visualization> getVisualization()  // Color overrides / visibility state to recreate the 3D scene.
public void setVisualization(Visualization)  // DISRUPTIVE
public List<Comment> getComments()
public boolean addComment(Comment)  // DISRUPTIVE
public boolean removeComment(Comment)  // DISRUPTIVE
public boolean addExternalID(UUID)  // DISRUPTIVE
public boolean removeExternalID(UUID)  // DISRUPTIVE
public Set<UUID> getExternalIds()
```

#### `com.solibri.smc.api.communication.Comment`  *(interface, public)*

BCF comment with text/author/creationDate; can hang off a Slide or a Viewpoint.

```
public static Comment create()  // DISRUPTIVE
public static Comment create(UUID externalId)  // DISRUPTIVE
public String getUniqueId()
public String getText()
public void setText(String)  // DISRUPTIVE
public String getAuthor()
public void setAuthor(String)  // DISRUPTIVE
public LocalDateTime getCreationDate()
public void setCreationDate(LocalDateTime)  // DISRUPTIVE
public boolean addExternalID(UUID)  // DISRUPTIVE
public boolean removeExternalID(UUID)  // DISRUPTIVE
public Set<UUID> getExternalIds()
```

#### `com.solibri.smc.api.issues.BcfXml`  *(interface, public)*

Export current model's issues to BCF zip (XML), choosing version and scope.

```
public boolean exportBcfXml(BcfVersion, BcfScope, OutputStream)  // Writes BCFZIP to stream. Returns success.
```

#### `com.solibri.smc.api.issues.BcfVersion`  *(enum, public)*

BCF format versions supported by exporter: V1_0, V2_0, V2_1, V3_0.

```
public static BcfVersion[] values()
public static BcfVersion valueOf(String)
```

#### `com.solibri.smc.api.issues.BcfScope`  *(enum, public)*

Which slides to include in BCF export: ALL, SELECTED, MARKED.

```
public static BcfScope[] values()
public static BcfScope valueOf(String)
```

#### `com.solibri.smc.api.bcflive.BcfApi`  *(interface, public)*

Live BCF-server bridge: enumerate servers, authenticate, sign off, push/pull slides by UUID, configure allowed coordination values.

```
public List<BCFServer> getBCFServerList()
public Map<String,List<BCFServer>> getCustomBCFServers()
public String authenticate(BCFServer)  // DISRUPTIVE
public void signoff(BCFServer)  // DISRUPTIVE
public Map<UUID,Slide> getExportedSlides(Set<UUID>)
public Map<UUID,Slide> getSlides(Set<UUID>)
public void setCoordinationValues(BCFCoordinationValues)  // DISRUPTIVE
```

#### `com.solibri.smc.api.bcflive.BCFServer`  *(interface, public)*

Reference to a remote BCF live server.

```
public String getUniqueId()
public String getUrl()
```

#### `com.solibri.smc.api.bcflive.BCFCoordinationValues`  *(interface, public)*

Server-defined coordination vocabulary - allowed labels/priorities/stages/topic statuses/topic types/userIDs and 'allow any' flags.

```
public Collection<String> getLabels()
public Collection<String> getPriorities()
public Collection<String> getStages()
public Collection<String> getTopicStatuses()
public Collection<String> getTopicTypes()
public Collection<String> getUserIDs()
public default boolean isAllowAnyLabel()
public default boolean isAllowAnyPriority()
public default boolean isAllowAnyStage()
public default boolean isAllowAnyTopicStatus()
public default boolean isAllowAnyTopicType()
public default boolean isAllowAnyUserID()
```

#### `com.solibri.smc.api.ui.views.issues.IssuesView`  *(interface, public)*

UI-bound accessor for the Issues panel - returns all/selected/marked slides as currently shown to the user.

```
public Collection<Slide> getAllSlides()
public Collection<Slide> getSelectedSlides()
public Collection<Slide> getMarkedSlides()
```

#### `com.solibri.smc.api.ui.views.checkingresults.ViewpointColumn`  *(interface, public)*

Show/hide the Viewpoint column in Checking Results view.

```
public void hide()  // DISRUPTIVE
public void show()  // DISRUPTIVE
```

#### `com.solibri.smc.api.internal.SlideEventType`  *(enum, **internal**)*

Coarse slide-event categories used by internal listeners: DATA_CHANGED, TITLE_CHANGED, DESCRIPTION_CHANGED.

```
public static SlideEventType[] values()
public static SlideEventType valueOf(String)
```

#### `com.solibri.smc.api.internal.listeners.SlideModifiedListener`  *(interface, **internal**)*

Fine-grained slide-change callbacks: title/description/status/type/stage/priority/responsibilities/labels/dueDate, viewpoint add/remove/update, comment add/modify/remove (on slide or viewpoint), components add/remove, slide remove. Internal but hugely valuable as a webhook source.

```
public void titleModified(List<Slide>)
public void descriptionModified(List<Slide>)
public void statusModified(List<Slide>)
public void typeModified(List<Slide>)
public void stageModified(List<Slide>)
public void priorityModified(List<Slide>)
public void responsibilitiesModified(List<Slide>)
public void labelsModified(List<Slide>)
public void dueDateModified(List<Slide>)
public void viewpointAdded(Slide, Viewpoint)
public void viewpointRemoved(Slide, String)
public void viewpointUpdated(Slide, Viewpoint)
public void commentAdded(Slide, Viewpoint, Comment)
public void commentAdded(Slide, Comment)
public void commentModified(Slide, Viewpoint, Comment)
public void commentModified(Slide, Comment)
public void commentRemoved(Slide, String)
public void commentRemoved(Slide, Viewpoint, String)
public void componentsAdded(Slide, Collection<Component>)
public void componentsRemoved(Slide, Collection<Component>)
public void slideRemoved(Slide)
```

</details>

### selection-settings-user-internal

Region spans three thin public SDK packages (selectionbasket, settings, user) plus the Product enum, and four very thick INTERNAL plugin packages on solibri.jar (classificationplugin, batchplugin, checkpointplugin, sapplugin, trimbleconnectplugin) and an RFC-style robot keyword library (com.solibri.smc.robot.*). The public API is tiny: SelectionBasket is a 2-method get/set on Set<Component>; Settings is a typed key-value bag (BooleanSetting, DoubleSetting, IntegerSetting, StringSetting, PasswordSetting) keyed by a unique-id; User exposes only getUsername(); Product enumerates OFFICE/SITE/ANYWHERE. The real power is in the internal plugins. ClassificationPlugin is the only path to programmatically create/load/apply/save classifications (already partly wired) and ships a full table-model DSL (ClassificationColumn, PropertyClassificationColumn, NumericConstraints, DateConstraints, EntityValues). BatchPlugin + TaskCreator + ~25 BatchTask subclasses provide an in-process scriptable equivalent of the Autorun XML format: opening/saving/closing/updating models, rulesets, ITOs, classifications, IDS rulesets; running checking + reports (BCF, Coordination, GeneralReport, ResultSummary, XLS template); creating presentations from rulesets/ITOs; auto-commenting issues; updating models from external sources. CheckpointPlugin, SapPlugin and TrimbleConnectPlugin are remote-storage singletons exposing connection state + ProjectsApi/ModelsApi/VersionsApi (Checkpoint) and resource providers + OAuth callback handlers (SAP, TC). Robot package has only three keyword classes (ModelRobot, ProjectRobot, BringApplicationToFront) wrapping the SAME .smc/.smcx/.ifc open/update/save calls our plugin already uses for ProjectRobot. Biggest opportunity: wrap BatchTask as REST endpoints. The Solibri Autorun XML world is essentially a fully-headless pipeline runtime already inside the JVM - we can drive a list-of-tasks endpoint and unlock end-to-end "ingest IFC -> reclassify -> run rules -> coordination BCF -> presentation -> XLS ITO" jobs without touching the UI or shelling out to solibri.exe in -autorun mode.

**Already wired:**

| SDK symbol | Wired as |
|---|---|
| `com.solibri.smc.api.selectionbasket.SelectionBasket.set(Collection)` | SuperuserServer /select (POST) + SolibriClient.set_selection via built-in 10876; CLI: su select / select |
| `com.solibri.smc.api.selectionbasket.SelectionBasket.get()` | SolibriClient.get_selection (via built-in 10876 /selectionBasket); CLI: selection |
| `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin.getInstance()` | SuperuserServer.handleClassifications uses it as the entry point for /classifications endpoints |
| `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin.getClassifications()` | SuperuserServer GET /classifications -> SuperuserClient.classifications(); CLI: su classifications |
| `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin.getClassification(String)` | SuperuserServer GET /classifications/{name} -> SuperuserClient.classification(name); CLI: su classification |
| `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin.openClassifications(Component, File[])` | SuperuserServer POST /classifications/load -> SuperuserClient.load_classification(file) |
| `com.solibri.saf.plugins.classificationplugin.Classification.saveIntoFile(File)` | SuperuserServer POST /classifications/{name}/save -> SuperuserClient.save_classification(name, file) |
| `com.solibri.saf.plugins.classificationplugin.Classification.getTableModel()` | SuperuserServer GET /classifications/{name} serializes rows + headers |
| `com.solibri.saf.plugins.classificationplugin.Classification.getAllClassifications()` | SuperuserServer GET /classifications/{name} -> classes[] |
| `com.solibri.saf.plugins.classificationplugin.Classification.getClassificationColumns()` | SuperuserServer GET /classifications/{name} -> columns[] |
| `com.solibri.saf.plugins.classificationplugin.Classification.getClassificationMethod()` | SuperuserServer GET /classifications/{name} -> method |
| `com.solibri.smc.robot.keywords.ProjectRobot.openSMCProject(String)` | SuperuserServer POST /projects/openFile (path.smc) -> SuperuserClient.open_file; CLI: su open |
| `com.solibri.smc.robot.keywords.ProjectRobot.openSMCXProject(String)` | SuperuserServer POST /projects/openFile (path.smcx) |

**Internal gems:**

- `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin` — Already a singleton, already partly used by us. Adding the missing apply/create/addToCurrentClassifications calls unlocks a complete classification CRUD over HTTP. — notable: `getInstance`, `openClassifications`, `saveClassification`, `applyClassifications`, `createClassificationIfNeeded` (+2 more)
- `com.solibri.saf.plugins.batchplugin.BatchRun` — The orchestrator: a Runnable that owns BatchTargets that own BatchTasks. Wrapping this as a /batch endpoint = scripted multi-step pipelines without writing autorun XML. — notable: `BatchRun(String)`, `addTarget`, `runTarget`, `run`
- `com.solibri.saf.plugins.batchplugin.tasks.BatchTask` — Every concrete task is a Runnable; we can instantiate, set fields, .run() them in-JVM. 25+ subclasses cover the entire autorun feature set. — notable: `run`
- `com.solibri.saf.plugins.batchplugin.AutorunResource` — Universal resource wrapper accepted by every task constructor. createFile(ResourceType, File) is the easy on-ramp; we don't need to author jdom2 XML. — notable: `createFile`, `create`, `createList`
- `com.solibri.saf.plugins.classificationplugin.Classification` — Direct CRUD on the in-memory classification, including TableModel access we already use, plus apply(SModel) which we don't yet expose. — notable: `apply`, `createCopy`, `saveIntoFile`, `setClassification`, `getClassificationNameToMaterialInfoMap`
- `com.solibri.smc.robot.keywords.ModelRobot` — The partialUpdateModel keyword is the same delta-update Solibri uses in its UI - perfect for fast re-checks after small IFC changes. — notable: `openModel`, `updateModel`, `partialUpdateModel`, `saveAsModel`, `doesComponentExist`
- `com.solibri.saf.plugins.checkpointplugin.CheckpointPlugin` — Bridges to Solibri's own cloud Checkpoint REST (Projects/Models/Versions APIs) reusing the user's OAuth - no separate token plumbing needed. — notable: `getInstance`, `getProjectsApi`, `getModelsApi`, `getVersionsApi`, `isAuthenticated`
- `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectPlugin` — Exposes a logged-in Trimble Connect ApiClient - we get the user's TC files without re-doing OAuth in our Python. — notable: `getInstance`, `getApiClient`, `isAuthenticated`, `getCredentials`

<details><summary>Classes (95) — method signatures</summary>

#### `com.solibri.smc.api.selectionbasket.SelectionBasket`  *(interface, public)*

Whole-application selection set. Reachable via SMC.getSelectionBasket().

```
void set(Collection<Component>)  // DISRUPTIVE  // Replace selection (drives UI highlight + camera focus actions)
Set<Component> get()  // Read current selection
```

#### `com.solibri.smc.api.settings.Settings`  *(interface, public)*

Typed key/value root reachable via SMC.getSettings(). Plugin-defined Setting<T> classes registered via SettingDialog/SettingGroup are pulled out with getSetting(Class).

```
GeneralSettings getGeneralSettings()
UnitSettings getUnitSettings()
ProxySettings getProxySettings()
<T extends Setting<?>> T getSetting(Class<T>)
```

#### `com.solibri.smc.api.settings.GeneralSettings`  *(interface, public)*

Application-wide misc.

```
int getThreadCount()
String getCurrentUser()
```

#### `com.solibri.smc.api.settings.UnitSettings`  *(interface, public)*

Locale-aware date/number formatters for length/area/volume/percentage/angle.

```
DateFormat getDateFormat()
DateFormat getTimeFormat()
DateFormat getDateTimeFormat()
PercentageFormat getPercentageFormat()
AngleFormat getAngleFormat()
LengthFormat getLenghtFormat()
AreaFormat getAreaFormat()
VolumeFormat getVolumeFormat()
```

#### `com.solibri.smc.api.settings.Setting<T>`  *(interface, public)*

Generic plugin-defined typed setting. UniqueId, value, default, validator.

```
String getUniqueId()
T getValue()
T getDefaultValue()
void setValue(T)  // DISRUPTIVE
Optional<String> invalidReason(T)
```

#### `com.solibri.smc.api.settings.BooleanSetting`  *(class, public)*

Setting<Boolean>

#### `com.solibri.smc.api.settings.DoubleSetting`  *(class, public)*

Setting<Double>

#### `com.solibri.smc.api.settings.IntegerSetting`  *(class, public)*

Setting<Integer>

#### `com.solibri.smc.api.settings.StringSetting`  *(class, public)*

Setting<String> + isMultiLine()

#### `com.solibri.smc.api.settings.PasswordSetting`  *(class, public)*

StringSetting forced isMultiLine()=false; obscured in the Solibri Settings UI

#### `com.solibri.smc.api.settings.SettingDialog`  *(interface, public)*

Plugin-supplied tab in Solibri's Settings dialog. Returns list of SettingGroup classes.

```
String getUniqueId()
Icon getIcon()
List<Class<? extends SettingGroup>> getSettingGroups()
```

#### `com.solibri.smc.api.settings.SettingGroup`  *(interface, public)*

Named cluster of Setting<?> classes inside a SettingDialog.

```
String getUniqueId()
List<Class<? extends Setting<?>>> getSettings()
```

#### `com.solibri.smc.api.settings.proxy.ProxySettings`  *(interface, public)*

Network proxy bundle.

```
Socks4Proxy getSocks4Proxy()
HttpProxy getHttpProxy()
```

#### `com.solibri.smc.api.settings.proxy.Socks4Proxy`  *(interface, public)*

```
boolean isActive()
String getAddress()
int getPort()
```

#### `com.solibri.smc.api.settings.proxy.HttpProxy`  *(interface, public)*

```
boolean isActive()
String getAddress()
int getPort()
String getUsername()
String getPassword()
```

#### `com.solibri.smc.api.settings.formats.PercentageFormat`  *(interface, public)*

NumberFormat wrapper

```
NumberFormat getFormat()
```

#### `com.solibri.smc.api.settings.formats.AngleFormat`  *(interface, public)*

NumberFormat wrapper (radians vs deg per locale)

```
NumberFormat getFormat()
```

#### `com.solibri.smc.api.settings.formats.LengthFormat`  *(interface, public)*

Length formatter incl. unit suffix

```
Format getFormat()
```

#### `com.solibri.smc.api.settings.formats.AreaFormat`  *(interface, public)*

```
Format getFormat()
```

#### `com.solibri.smc.api.settings.formats.VolumeFormat`  *(interface, public)*

```
Format getFormat()
```

#### `com.solibri.smc.api.user.User`  *(interface, public)*

Stub - only getUsername(). Reachable via SMC.getUser().

```
String getUsername()
```

#### `com.solibri.smc.api.Product`  *(enum, public)*

Product edition enum (OFFICE / SITE / ANYWHERE). Returned by SMC.getProduct().

```
Product[] values()
Product valueOf(String)
```

#### `com.solibri.saf.plugins.classificationplugin.ClassificationPlugin`  *(class, **internal**)*

Singleton holding all in-memory Classification objects. The ONLY way to load/save .classification files or apply classifications to a model. Already partly wired in our plugin.

```
static ClassificationPlugin getInstance()
Classification[] openClassifications(Component, File[])  // DISRUPTIVE  // Load .classification from disk into session
Classification[] openClassifications(Component, List<Resource>)  // DISRUPTIVE
Collection<Classification> getClassifications()  // List currently loaded
Classification getClassification(String)
Classification createClassificationIfNeeded(String, Class...)  // DISRUPTIVE  // Programmatically create empty classification
static Optional<Classification> createClassification(ClassPropertyAndValue)  // DISRUPTIVE
void addClassificationToModel(Classification)  // DISRUPTIVE
void addToCurrentClassifications(Classification)  // DISRUPTIVE
void applyClassifications(Collection<String>)  // DISRUPTIVE  // Re-evaluate classification rules against the current model
void applyClassifications(Collection<String>, SModel)  // DISRUPTIVE
boolean saveClassification(Classification, Component)
boolean saveAsClassification(Classification, Component)
Collection<StatefulResource> getRecommendedFiles()  // Discovery: classifications shipped with the product/ruleset
File getClassificationFolder()
ArrayList<Classification> getNonLockedClassifications()
Vector<Classification> getModifiedClassifications()
Collection<Classification> getClassificationsUsingPropertyReference(PropertyReference)
Optional<Classification> getCurrentColorClassification()  // Which classification drives the model coloring right now
```

#### `com.solibri.saf.plugins.classificationplugin.Classification`  *(class, **internal**)*

One classification (set of rules + result rows). Implements Savable + ClassificationSource. Large API.

```
static Classification openClassification(File, ClassificationProxy)
static Classification openClassification(Resource, ClassificationProxy)
static Classification openClassification(InputStream, ClassificationProxy)
void saveIntoFile(File)  // Write to disk; what our /classifications/{name}/save endpoint already uses
boolean apply(SModel)  // DISRUPTIVE  // Run THIS classification against a model
static boolean apply(Collection<Classification>, SModel)  // DISRUPTIVE
Collection<ClassificationReference> getClassificationReferences()  // Component->class mapping
String classify(Entity)  // Classify one entity ad-hoc
String getClassification(Entity)
String[] getClassifications(Entity)
String[] getAllClassifications()
void setClassification(Collection<Entity>, String)  // DISRUPTIVE  // Manual override for components
List<ClassificationColumn> getClassificationColumns()
void addClassificationColumn(ClassificationColumn)  // DISRUPTIVE
void removeClassificationColumn(ClassificationColumn)  // DISRUPTIVE
void setFilters(List<ClassAndPropertyFilter>)  // DISRUPTIVE
Vector<ClassAndPropertyFilter> getFilters()
Class<? extends Entity>[] getClassificationClasses()
Classification createCopy()  // Branch a classification for what-if experiments
TableModel getTableModel()  // Swing TableModel - what our /classifications/{name} endpoint already serializes
Map<String,MaterialInfo> getClassificationNameToMaterialInfoMap()  // Colors per class (drives visualization)
```

#### `com.solibri.saf.plugins.classificationplugin.ClassificationColumn`  *(class, **internal**)*

Abstract column descriptor; concrete subtypes drive the matrix UI + matching

```
static List<ClassificationColumn> createClassificationColumns(List<ClassPropertyAndValue>)
abstract PropertyReference getPropertyReference()
Object getValue(SEntity)
String getStringValue(SEntity)
```

#### `com.solibri.saf.plugins.classificationplugin.ClassificationReference`  *(class, **internal**)*

(entity -> classification value) edge with Source = MANUAL/AUTO/etc.

```
static String[] toMultiNameArray(String)
String getName()
String[] getNames()
ClassificationReference$Source getSource()
Set<Entity> getClassifiedEntities()
MaterialInfo getMaterial()
```

#### `com.solibri.saf.plugins.classificationplugin.ClassificationSource`  *(interface, **internal**)*

Sealed lookup-by-source contract

```
Classification getClassification()
```

#### `com.solibri.saf.plugins.classificationplugin.PropertyClassificationColumn`  *(class, **internal**)*

Column matched on PropertySet.Property

#### `com.solibri.saf.plugins.classificationplugin.PropertySetClassificationColumn`  *(class, **internal**)*

Column matched on whole PSet

#### `com.solibri.saf.plugins.classificationplugin.ComponentClassificationColumn`  *(class, **internal**)*

Column matched on component facets

#### `com.solibri.saf.plugins.classificationplugin.ClassificationClassificationColumn`  *(class, **internal**)*

Column whose source is another Classification

#### `com.solibri.saf.plugins.classificationplugin.EntityValues`  *(class, **internal**)*

Cached row of property values pulled out of an Entity, fed into Classification.classify

#### `com.solibri.saf.plugins.classificationplugin.NumericConstraints`  *(class, **internal**)*

Range constraint cell (e.g. >= 200, < 300)

#### `com.solibri.saf.plugins.classificationplugin.DateConstraints`  *(class, **internal**)*

Date range constraint cell

#### `com.solibri.saf.plugins.classificationplugin.DefaultClassification`  *(class, **internal**)*

Helper for default-named rows

#### `com.solibri.saf.plugins.classificationplugin.SpaceClassification`  *(class, **internal**)*

Specialized Classification for IfcSpace grouping (SPACE_GROUPING_CLASSIFICATION constant)

#### `com.solibri.saf.plugins.classificationplugin.Classifies`  *(class, **internal**)*

Relation entity (DirectedRelation) used internally to wire entity<->class

#### `com.solibri.saf.plugins.batchplugin.BatchPlugin`  *(class, **internal**)*

Singleton that owns task creators and dispatches autorun XML elements to BatchTasks.

```
static BatchPlugin getInstance()
TaskCreator getTaskCreator(String)
void addTaskCreator(TaskCreator)
BatchAction getBatchAction()
static SModel findModelLoadedFrom(String)
static Collection<SModel> findMatchingModelsLoadedFrom(String)
static ExternalFile getExternalFile(SModel)
```

#### `com.solibri.saf.plugins.batchplugin.BatchRun`  *(class, **internal**)*

One named end-to-end batch run; holds a list of BatchTargets.

```
BatchRun(String)
void run()  // DISRUPTIVE
void addTarget(BatchTarget)
void runTarget(String)  // DISRUPTIVE
```

#### `com.solibri.saf.plugins.batchplugin.BatchTarget`  *(class, **internal**)*

Ordered list of BatchTasks executed under a target name.

```
BatchTarget(String)
void addTask(BatchTask)
void run()  // DISRUPTIVE
```

#### `com.solibri.saf.plugins.batchplugin.TaskCreator`  *(class, **internal**)*

Pluggable factory: each registered creator owns one autorun XML element name -> BatchTask.

```
TaskCreator(String)
abstract BatchTask createTask(BatchRun, Element)
```

#### `com.solibri.saf.plugins.batchplugin.AutorunResource`  *(class, **internal**)*

Resource handle (file path, ACC/Trimble URL, template etc.) used by every BatchTask constructor.

```
static AutorunResource createFile(ResourceType, File)
static Optional<AutorunResource> create(ResourceType, Element)
static List<AutorunResource> createList(ResourceType, Element, List<String>, String)
Optional<Resource> getResourceForOpen()
Optional<Resource> getResourceForSave()
```

#### `com.solibri.saf.plugins.batchplugin.ResourceURL`  *(class, **internal**)*

Resource backed by an HTTP URL

```
ResourceURL(URL, ResourceType)
InputStream getInputStream()
boolean exists()
```

#### `com.solibri.saf.plugins.batchplugin.tasks.BatchTask`  *(class, **internal**)*

Abstract Runnable parent of every concrete task.

```
void run()  // DISRUPTIVE
```

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenModelTask`  *(class, **internal**)*

Open an IFC/model into the current project (with translation/discipline/category)

```
OpenModelTask(AutorunResource)
void setDiscipline(String)
void setTranslation(Tuple3d)
void setShortName(String)
void addCategory(String)
void setPointSize(int)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.SaveModelTask`  *(class, **internal**)*

Save project to a destination AutorunResource

```
SaveModelTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CloseModelTask`  *(class, **internal**)*

Close one loaded model

```
CloseModelTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.UpdateModelTask`  *(class, **internal**)*

Refresh an existing model from a new source (full or delta)

```
UpdateModelTask(AutorunResource src, AutorunResource template, boolean asUpdate)
String getName()
```

#### `com.solibri.saf.plugins.batchplugin.tasks.AutoUpdateModelsTask`  *(class, **internal**)*

Re-pull every model whose backing file has changed since last open (cron-friendly).

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenRuleSetTask`  *(class, **internal**)*

Load a .cset into the open project

```
OpenRuleSetTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenIdsRuleSetTask`  *(class, **internal**)*

Import an .ids file via IdsOpener and turn it into rules

```
OpenIdsRuleSetTask(AutorunResource, IdsOpener)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CloseRuleSet`  *(class, **internal**)*

Close ruleset by name

```
CloseRuleSet(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CheckTask`  *(class, **internal**)*

Run checking (same effect as SMC.getChecking().runChecking)

#### `com.solibri.saf.plugins.batchplugin.tasks.UpdateRuleParametersTask`  *(class, **internal**)*

Apply parameter overrides on loaded rules before checking

#### `com.solibri.saf.plugins.batchplugin.tasks.ExcludeSubPartsInRule01Task`  *(class, **internal**)*

Special-purpose rule 01 toggle (often used to ignore voids/sub-elements)

```
ExcludeSubPartsInRule01Task(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.AutoCommentIssuesTask`  *(class, **internal**)*

Bulk-add a stock comment to issues matching a status filter

```
AutoCommentIssuesTask(int status, boolean onlyNew)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenClassificationTask`  *(class, **internal**)*

Load a .classification

```
OpenClassificationTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CloseClassificationTask`  *(class, **internal**)*

Close one classification by name

```
CloseClassificationTask(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.OpenItoTask`  *(class, **internal**)*

Load a saved ITO template

```
OpenItoTask(AutorunResource)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CalculateItoTask`  *(class, **internal**)*

Recompute a named ITO over the open model

```
CalculateItoTask(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.ReportItoTask`  *(class, **internal**)*

Export ITO as a report (xls/csv/etc.)

```
ReportItoTask(String, String, AutorunResource template, AutorunResource out)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CloseItoTask`  *(class, **internal**)*

Close ITO by name

```
CloseItoTask(String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.BCFReportTask`  *(class, **internal**)*

Write a BCF v2.1 zip from current issues

```
BCFReportTask(AutorunResource out, String version)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CoordinationReportTask`  *(class, **internal**)*

Filter -> coordination PDF/RTF (Solibri's two-table layout)

```
CoordinationReportTask(AutorunResource template, AutorunResource out, ReportingSettings)
void setHeaders(HeaderElement[])
```

#### `com.solibri.saf.plugins.batchplugin.tasks.GeneralReportTask`  *(class, **internal**)*

Bulk multi-template report run

```
GeneralReportTask(List<AutorunResource>, ReportingSettings)
void setHeaders(HeaderElement[])
```

#### `com.solibri.saf.plugins.batchplugin.tasks.WriteReportTask`  *(class, **internal**)*

Most flexible writer (PDF/RTF/XLS, with which flags to include)

```
WriteReportTask(List<AutorunResource>, ReportingSettings, boolean, boolean, boolean, boolean, boolean)
void setHeaders(HeaderElement[])
```

#### `com.solibri.saf.plugins.batchplugin.tasks.ResultSummaryReportTask`  *(class, **internal**)*

Score/status summary report (the green-yellow-red dashboard export)

```
ResultSummaryReportTask(AutorunResource template, AutorunResource out, String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.CreatePresentationFromRulesetTask`  *(class, **internal**)*

Auto-build a Presentation (= slide-deck of issues) from a ruleset's results

#### `com.solibri.saf.plugins.batchplugin.tasks.PresentationFromITOTask`  *(class, **internal**)*

Auto-build a Presentation from an ITO row set (great for QA walkthroughs)

```
PresentationFromITOTask(PresentationFromItoParameters)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.UpdatePresentationTask`  *(class, **internal**)*

Refresh a saved presentation against the (changed) current model

```
UpdatePresentationTask(AutorunResource, String, boolean, boolean)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.BatchCallTask`  *(class, **internal**)*

Recurse: call another BatchTarget by name (composable pipelines)

```
BatchCallTask(BatchRun, String)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.ReportStatusTask`  *(class, **internal**)*

Stream batch progress into a JDBC PreparedStatement (for fleet dashboards)

```
ReportStatusTask(PreparedStatement)
```

#### `com.solibri.saf.plugins.batchplugin.tasks.ExitTask`  *(class, **internal**)*

Exit Solibri at end of batch (autorun shutdown)

#### `com.solibri.saf.plugins.checkpointplugin.CheckpointPlugin`  *(class, **internal**)*

Solibri Checkpoint (cloud collaboration) singleton + REST API factories.

```
static CheckpointPlugin getInstance()
ProjectsApi getProjectsApi(Region)
ModelsApi getModelsApi(Region)
VersionsApi getVersionsApi(Region)
boolean isAuthenticated()
boolean askUserToConnect()  // DISRUPTIVE
ProjectImportProxy getProjectImportProxy()
```

#### `com.solibri.saf.plugins.checkpointplugin.CheckpointIntegration`  *(class, **internal**)*

Connection lifecycle bound to ConnectionState machine

#### `com.solibri.saf.plugins.checkpointplugin.CheckpointResource`  *(class, **internal**)*

Resource implementation backed by a Checkpoint model/version

#### `com.solibri.saf.plugins.checkpointplugin.CheckpointResourceProvider`  *(class, **internal**)*

Resource provider used by File>Open dialog and Autorun

#### `com.solibri.saf.plugins.checkpointplugin.CheckpointExternalFileUpdater`  *(class, **internal**)*

Background updater for tracked external files (Checkpoint variant)

#### `com.solibri.saf.plugins.sapplugin.SapPlugin`  *(class, **internal**)*

SAP backend integration singleton (auth + resource provider).

```
static SapPlugin getInstance()
boolean isAuthenticated()
void startAuthentication()  // DISRUPTIVE
void disconnect(boolean)  // DISRUPTIVE
void handleCallbackURLCalled(String, HttpResponse)
Optional<SapCredentials> getCredentials()
Optional<ResourceProviderUI> getResourceProviderUI(Usage, String, FileTypeFilter...)
ProjectImportProxy getProjectImportProxy()
```

#### `com.solibri.saf.plugins.sapplugin.SapCredentials`  *(class, **internal**)*

OAuth token bundle for SAP

#### `com.solibri.saf.plugins.sapplugin.SapIntegration`  *(class, **internal**)*

SAP connection lifecycle

#### `com.solibri.saf.plugins.sapplugin.SapResource`  *(class, **internal**)*

Resource handle for a SAP-served file

#### `com.solibri.saf.plugins.sapplugin.SapResourceProvider`  *(class, **internal**)*

Provider plumbing

#### `com.solibri.saf.plugins.sapplugin.SapTokenResponse`  *(class, **internal**)*

OAuth response DTO

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectPlugin`  *(class, **internal**)*

Trimble Connect singleton; full OAuth + ApiClient access.

```
static TrimbleConnectPlugin getInstance()
ApiClient getApiClient()
boolean isAuthenticated()
void startAuthentication()  // DISRUPTIVE
void disconnect(boolean)  // DISRUPTIVE
void handleCallbackURLCalled(String, HttpResponse)
Optional<TrimbleConnectCredentials> getCredentials()
Optional<ResourceProviderUI> getResourceProviderUI(Usage, String, FileTypeFilter...)
ProjectImportProxy getProjectImportProxy()
```

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectCredentials`  *(class, **internal**)*

OAuth bundle for TC

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectIntegration`  *(class, **internal**)*

TC lifecycle

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectResource`  *(class, **internal**)*

Resource backed by a TC item

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleConnectResourceProvider`  *(class, **internal**)*

Provider plumbing

#### `com.solibri.saf.plugins.trimbleconnectplugin.TrimbleTokenRefreshHandler`  *(class, **internal**)*

Auto-refresh OAuth token before expiry

#### `com.solibri.smc.robot.SMCRobotLibrary`  *(class, **internal**)*

Robot Framework dynamic library facade (used by Solibri's QA tests).

```
List<String> getKeywordNames()
Object runKeyword(String, List)
Object runKeyword(String, List, Map)
```

#### `com.solibri.smc.robot.keywords.ModelRobot`  *(class, **internal**)*

Wraps Models for Robot keywords - already partly used in our plugin (ProjectRobot is its sibling).

```
String openModel(String)  // DISRUPTIVE
String updateModel(String, String)  // DISRUPTIVE
Map<String,Object> partialUpdateModel(String, String)  // DISRUPTIVE  // Diff-based reload of an IFC
Map<String,Object> saveAsModel(String, String)  // DISRUPTIVE
boolean doesComponentExist(String, String)
```

#### `com.solibri.smc.robot.keywords.ProjectRobot`  *(class, **internal**)*

Already wired - our /projects/openFile uses openSMCProject / openSMCXProject.

```
Map<String,Object> openSMCProject(String)  // DISRUPTIVE
Map<String,Object> openSMCXProject(String)  // DISRUPTIVE
boolean closeModel(String)  // DISRUPTIVE
Map<String,Object> saveModel(String)  // DISRUPTIVE
```

#### `com.solibri.smc.robot.keywords.BringApplicationToFront`  *(interface, **internal**)*

Mixin that focuses the Solibri window before a keyword runs (anti-flake for screenshots)

```
default void bringApplicationToFront()  // DISRUPTIVE
```

#### `com.solibri.smc.robot.server.SolibriRobotServer`  *(class, **internal**)*

Stand-alone Robot Framework remote server (port-bound).

```
static void startRobotServer(int port, boolean, boolean)  // DISRUPTIVE
```

</details>

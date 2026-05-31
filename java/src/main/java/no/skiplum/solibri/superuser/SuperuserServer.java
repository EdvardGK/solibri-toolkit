package no.skiplum.solibri.superuser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.solibri.smc.api.SMC;
import com.solibri.smc.api.checking.Checking;
import com.solibri.smc.api.checking.Result;
import com.solibri.smc.api.checking.Ruleset;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.Model;
import com.solibri.smc.api.model.Property;
import com.solibri.smc.api.model.PropertySet;
import com.solibri.smc.api.ui.View;

// Internal (non-SDK) Solibri classes - required for classification + project handshake.
// These can change between Solibri releases; pin Solibri version in the build manifest.
import com.solibri.saf.plugins.classificationplugin.ClassificationPlugin;
import com.solibri.saf.plugins.classificationplugin.Classification;
import com.solibri.saf.core.resource.RecentResource;
import com.solibri.resource.Resource;
// Internal automation keyword: opens .smc/.smcx project files in-JVM (no built-in 10876 REST server needed).
import com.solibri.smc.robot.keywords.ProjectRobot;

import com.solibri.smc.api.checking.Decision;
import com.solibri.smc.api.checking.ResultCategory;
import com.solibri.smc.api.communication.Slide;
import com.solibri.smc.api.checking.Severity;
import com.solibri.smc.api.checking.Rule;
import com.solibri.smc.api.checking.RuleParameter;
import com.solibri.smc.api.checking.RuleParameters;
import com.solibri.smc.api.checking.CheckingNode;
import com.solibri.smc.api.checking.BooleanParameter;
import com.solibri.smc.api.checking.IntParameter;
import com.solibri.smc.api.checking.DoubleParameter;
import com.solibri.smc.api.checking.StringParameter;
import com.solibri.smc.api.checking.EnumerationParameter;
import com.solibri.smc.api.checking.ClassificationParameter;
import com.solibri.smc.api.checking.PropertyReferenceParameter;
import com.solibri.smc.api.model.PropertyReference;
import com.solibri.smc.api.filter.ComponentFilter;
import com.solibri.smc.api.communication.Presentation;
import java.time.LocalDate;
import com.solibri.smc.api.issues.BcfXml;
import com.solibri.smc.api.issues.BcfVersion;
import com.solibri.smc.api.issues.BcfScope;
import com.solibri.smc.api.ito.InformationTakeoff;
import com.solibri.smc.api.ito.ItoDefinition;
import com.solibri.smc.api.model.PropertyType;
import java.util.UUID;
import com.solibri.smc.api.ito.ItoColumn;
import com.solibri.smc.api.ito.ItoRow;
import com.solibri.smc.api.ito.ItoValue;
import com.solibri.smc.api.project.Project;
import java.nio.file.Path;
import java.io.File;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import com.solibri.smc.api.threed.ThreeD;
import com.solibri.smc.api.threed.Camera;
import com.solibri.smc.api.threed.CameraState;
import com.solibri.smc.api.threed.PerspectiveCameraState;
import com.solibri.smc.api.threed.OrthogonalCameraState;
import com.solibri.geometry.linearalgebra.Vector3d;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Skiplum "superuser" extension: a small HTTP server, run inside the Solibri JVM, that
 * exposes the capabilities the built-in REST API (port 10876) lacks - listing rulesets,
 * reading checking results, running checking, querying components by property - all backed
 * by the full SMC SDK.
 *
 * Registered as a {@link View} (the SDK's startup extension point); the server is started
 * once from the constructor. Listens on http://localhost:10999/skiplum/v1.
 *
 * Endpoints (JSON):
 *   GET  /skiplum/v1/ping
 *   GET  /skiplum/v1/model
 *   GET  /skiplum/v1/rulesets                  [?json=1 to include Ruleset.toJson()]
 *   GET  /skiplum/v1/results
 *   GET  /skiplum/v1/components?pset=&prop=&value=&op=equals|prefix|contains|exists
 *   POST /skiplum/v1/check                     (DISRUPTIVE: runs checking)
 */
public final class SuperuserServer implements View {

    private static final int PORT = 10999;
    private static final String BASE = "/skiplum/v1";
    private static volatile HttpServer server;

    public SuperuserServer() {
        startOnce();
    }

    private static synchronized void startOnce() {
        if (server != null) {
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            // Larger pool: a single blocking SDK call (e.g. an open that pops a modal dialog)
            // must not be able to wedge the whole API, as a 2-thread pool could.
            s.setExecutor(Executors.newFixedThreadPool(8));
            s.createContext(BASE + "/ping", ex -> respond(ex, 200,
                    "{\"answer\":\"pong\",\"plugin\":\"skiplum-superuser\",\"productVersion\":"
                            + str(safe(SMC::getProductVersion, "")) + "}"));
            s.createContext(BASE + "/model", SuperuserServer::handleModel);
            s.createContext(BASE + "/rulesets", SuperuserServer::handleRulesets);
            s.createContext(BASE + "/results", SuperuserServer::handleResults);
            s.createContext(BASE + "/components", SuperuserServer::handleComponents);
            s.createContext(BASE + "/check", SuperuserServer::handleCheck);
            s.createContext(BASE + "/classifications", SuperuserServer::handleClassifications);
            s.createContext(BASE + "/recents", SuperuserServer::handleRecents);
            s.createContext(BASE + "/projects", SuperuserServer::handleProjects);
            s.createContext(BASE + "/component", SuperuserServer::handleComponentByGuid);
            s.createContext(BASE + "/select", SuperuserServer::handleSelect);
            s.createContext(BASE + "/shutdown", SuperuserServer::handleShutdown);
            s.createContext(BASE + "/results/full", SuperuserServer::handleResultsFull);
            s.createContext(BASE + "/results/byRule", SuperuserServer::handleResultsByRule);
            s.createContext(BASE + "/results/bySeverity", SuperuserServer::handleResultsBySeverity);
            s.createContext(BASE + "/results/", SuperuserServer::handleResultDecision);
            s.createContext(BASE + "/rules", SuperuserServer::handleRules);
            s.createContext(BASE + "/rules/", SuperuserServer::handleRuleParameters);
            s.createContext(BASE + "/presentations", SuperuserServer::handlePresentations);
            s.createContext(BASE + "/presentations/", SuperuserServer::handlePresentationSlides);
            s.createContext(BASE + "/bcfxml/export", SuperuserServer::handleBcfXmlExport);
            s.createContext(BASE + "/ito", SuperuserServer::handleIto);
            s.createContext(BASE + "/ito/calculate", SuperuserServer::handleItoCalculate);
            s.createContext(BASE + "/ito/results", SuperuserServer::handleItoResults);
            s.createContext(BASE + "/project/save", SuperuserServer::handleProjectSave);
            s.createContext(BASE + "/project/saveAs", SuperuserServer::handleProjectSaveAs);
            s.createContext(BASE + "/project/info", SuperuserServer::handleProjectInfo);
            s.createContext(BASE + "/project/industry", SuperuserServer::handleProjectIndustry);
            s.createContext(BASE + "/threed/camera", SuperuserServer::handleThreedCamera);
            s.createContext(BASE + "/threed/snapshot", SuperuserServer::handleThreedSnapshot);
            s.createContext(BASE + "/threed/fit", SuperuserServer::handleThreedFit);
            s.start();
            server = s;
            System.out.println("[skiplum-superuser] HTTP server up on http://localhost:" + PORT + BASE);
        } catch (IOException e) {
            System.err.println("[skiplum-superuser] failed to start: " + e.getMessage());
        }
    }

    // ---- handlers --------------------------------------------------------

    private static void handleModel(HttpExchange ex) throws IOException {
        respond(ex, 200, onEdt(() -> {
            Model m = SMC.getModel();
            if (m == null) return "{\"open\":false}";
            StringBuilder sub = new StringBuilder("[");
            boolean first = true;
            for (Model s : m.getSubModels()) {
                if (!first) sub.append(",");
                first = false;
                sub.append("{\"name\":").append(str(s.getName()))
                   .append(",\"uuid\":").append(str(s.getUUID().toString())).append("}");
            }
            sub.append("]");
            List<String> rs = new ArrayList<>();
            for (Ruleset r : m.getRulesets()) rs.add(str(r.getUniqueId()));
            return "{\"name\":" + str(m.getName())
                    + ",\"busy\":" + SMC.isBusy()
                    + ",\"subModels\":" + sub
                    + ",\"rulesetIds\":" + arr(rs) + "}";
        }));
    }

    private static void handleRulesets(HttpExchange ex) throws IOException {
        boolean withJson = query(ex).getOrDefault("json", "").equals("1");
        respond(ex, 200, onEdt(() -> {
            List<String> out = new ArrayList<>();
            for (Ruleset r : SMC.getModel().getRulesets()) {
                StringBuilder o = new StringBuilder("{\"uniqueId\":").append(str(r.getUniqueId()))
                        .append(",\"description\":").append(str(r.getDescription()));
                if (withJson) {
                    o.append(",\"json\":").append(str(r.toJson()));
                }
                out.add(o.append("}").toString());
            }
            return arr(out);
        }));
    }

    private static void handleResults(HttpExchange ex) throws IOException {
        // Off-EDT: SDK component/result access is concurrent-safe (cf. ConcurrentRule).
        try {
            Checking checking = SMC.getChecking();
            if (checking == null) { respond(ex, 200, "[]"); return; }
            List<String> out = new ArrayList<>();
            int skipped = 0;
            for (Result r : checking.getResults()) {
                // Serialize each result independently: a single result that throws inside a
                // Solibri internal getter (e.g. a space result with a null spaceGroupType) must
                // not blank out the whole response.
                try { out.add(resultJson(r)); }
                catch (Exception perResult) { skipped++; }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"skipped\":" + skipped
                    + ",\"results\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleComponents(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String pset = q.getOrDefault("pset", "");
        String prop = q.getOrDefault("prop", "");
        String value = q.getOrDefault("value", "");
        String op = q.getOrDefault("op", "equals");
        int limit;
        try { limit = Math.min(Math.max(Integer.parseInt(q.getOrDefault("limit", "200")), 1), 10000); }
        catch (NumberFormatException nfe) { limit = 200; }
        if (pset.isEmpty() || prop.isEmpty()) {
            respond(ex, 400, "{\"error\":\"pset and prop are required\"}");
            return;
        }
        // Off-EDT iteration: huge models (280k+ components) would freeze the UI otherwise.
        // SDK component reads are concurrent-safe (cf. ConcurrentRule).
        try {
            Model model = SMC.getModel();
            if (model == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            int[] hit = {0};
            int cap = limit;
            List<String> out = new ArrayList<>();
            model.getComponents(c -> {
                if (hit[0] >= cap) return false;
                for (PropertySet ps : c.getPropertySets(pset)) {
                    var p = ps.getProperty(prop);
                    if (p.isPresent()) {
                        String v = p.get().getValueAsString();
                        if (matches(v, value, op)) {
                            hit[0]++;
                            out.add("{\"guid\":" + str(c.getGUID()) + ",\"name\":" + str(c.getName())
                                    + ",\"value\":" + str(v) + "}");
                            return true;
                        }
                    }
                }
                return false;
            });
            respond(ex, 200, "{\"count\":" + out.size() + ",\"limit\":" + cap
                    + ",\"components\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleCheck(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.isBusy()) {
            respond(ex, 409, "{\"error\":\"Solibri is busy\"}");
            return;
        }
        // Run checking on the handler thread (NOT the EDT) so the UI is not frozen.
        Collection<Result> results = SMC.getChecking().runChecking(true);
        List<String> out = new ArrayList<>();
        for (Result r : results) out.add(resultJson(r));
        respond(ex, 200, "{\"count\":" + out.size() + ",\"results\":" + arr(out) + "}");
    }

    /**
     * GET  /classifications              - list current classifications in the open project
     * POST /classifications/load?file=PATH - load a .classification file into the running session
     *
     * Uses the INTERNAL com.solibri.saf.plugins.classificationplugin API (no SDK write equivalent
     * exists). May break across Solibri versions.
     */
    private static void handleClassifications(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        try {
            ClassificationPlugin plugin = ClassificationPlugin.getInstance();
            if (plugin == null) { respond(ex, 503, "{\"error\":\"classification plugin unavailable\"}"); return; }

            // POST /classifications/load - load a .classification from disk
            if (method.equals("POST") && path.endsWith("/load")) {
                String filePath = query(ex).getOrDefault("file", "");
                if (filePath.isEmpty()) { respond(ex, 400, "{\"error\":\"file query param required\"}"); return; }
                java.io.File f = new java.io.File(filePath);
                if (!f.exists() || !f.isFile()) {
                    respond(ex, 404, "{\"error\":\"file not found: " + filePath.replace("\\", "\\\\") + "\"}"); return;
                }
                Classification[] loaded = plugin.openClassifications((java.awt.Component) null, new java.io.File[]{ f });
                int n = loaded != null ? loaded.length : 0;
                List<String> names = new ArrayList<>();
                if (loaded != null) for (Classification c : loaded) if (c != null) names.add(str(c.getName()));
                respond(ex, 200, "{\"loaded\":" + n + ",\"names\":" + arr(names) + "}");
                return;
            }

            // GET /classifications/{name} - detail of one classification
            String prefix = BASE + "/classifications/";
            if (method.equals("GET") && path.startsWith(prefix) && path.length() > prefix.length()
                    && !path.endsWith("/load")) {
                String name = java.net.URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
                Classification target = plugin.getClassification(name);
                if (target == null) { respond(ex, 404, "{\"error\":\"not found: " + name.replace("\"","\\\"") + "\"}"); return; }
                StringBuilder b = new StringBuilder("{");
                b.append("\"name\":").append(str(target.getName()));
                b.append(",\"imported\":").append(target.isImported());
                b.append(",\"colorClassification\":").append(target.isColorClassification());
                b.append(",\"method\":").append(str(String.valueOf(target.getClassificationMethod())));
                List<String> classes = new ArrayList<>();
                try { String[] all = target.getAllClassifications();
                      if (all != null) for (String s : all) classes.add(str(s)); } catch (Exception ignore) {}
                b.append(",\"classes\":").append(arr(classes));
                List<String> cols = new ArrayList<>();
                try { java.util.List<?> cl = target.getClassificationColumns();
                      if (cl != null) for (Object o : cl) cols.add(str(String.valueOf(o))); } catch (Exception ignore) {}
                b.append(",\"columns\":").append(arr(cols));

                // rows from the swing TableModel (toString cells for inspection)
                List<String> rows = new ArrayList<>();
                try {
                    javax.swing.table.TableModel tm = target.getTableModel();
                    if (tm != null) {
                        int nr = tm.getRowCount(), nc = tm.getColumnCount();
                        List<String> headers = new ArrayList<>();
                        for (int c = 0; c < nc; c++) headers.add(str(String.valueOf(tm.getColumnName(c))));
                        b.append(",\"tableHeaders\":").append(arr(headers));
                        for (int r = 0; r < nr; r++) {
                            List<String> cells = new ArrayList<>();
                            for (int c = 0; c < nc; c++) {
                                Object v = tm.getValueAt(r, c);
                                cells.add(str(v == null ? "" : v.toString()));
                            }
                            rows.add(arr(cells));
                        }
                    }
                } catch (Exception ignore) {}
                b.append(",\"rowCount\":").append(rows.size());
                b.append(",\"rows\":").append(arr(rows));
                respond(ex, 200, b.append("}").toString());
                return;
            }

            // POST /classifications/{name}/save?file=PATH - persist to disk via Classification.saveIntoFile
            String saveBase = BASE + "/classifications/";
            if (method.equals("POST") && path.startsWith(saveBase) && path.endsWith("/save")) {
                String middle = path.substring(saveBase.length(), path.length() - "/save".length());
                String name = java.net.URLDecoder.decode(middle, StandardCharsets.UTF_8);
                String filePath = query(ex).getOrDefault("file", "");
                if (filePath.isEmpty()) { respond(ex, 400, "{\"error\":\"file query param required\"}"); return; }
                Classification target = plugin.getClassification(name);
                if (target == null) { respond(ex, 404, "{\"error\":\"classification not found: " + name.replace("\"","\\\"") + "\"}"); return; }
                java.io.File out = new java.io.File(filePath);
                target.saveIntoFile(out);
                respond(ex, 200, "{\"saved\":true,\"path\":" + str(out.getAbsolutePath())
                        + ",\"size\":" + (out.exists() ? out.length() : -1) + "}");
                return;
            }

            // GET /classifications - list current (with internal detail)
            if (method.equals("GET")) {
                List<String> out = new ArrayList<>();
                java.util.Collection<?> classes = plugin.getClassifications();
                if (classes != null) {
                    for (Object o : classes) {
                        if (o instanceof Classification) {
                            Classification c = (Classification) o;
                            out.add("{\"name\":" + str(c.getName())
                                    + ",\"imported\":" + c.isImported()
                                    + ",\"colorClassification\":" + c.isColorClassification()
                                    + ",\"method\":" + str(String.valueOf(c.getClassificationMethod())) + "}");
                        }
                    }
                }
                respond(ex, 200, "{\"count\":" + out.size() + ",\"classifications\":" + arr(out) + "}");
                return;
            }
            respond(ex, 405, "{\"error\":\"use GET /classifications, GET /classifications/{name}, or POST /classifications/load?file=PATH\"}");
        } catch (NoClassDefFoundError e) {
            respond(ex, 500, "{\"error\":\"internal classification API unavailable: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * GET /recents - aggregate Solibri's recent files lists (across known keys), matching
     * what the File > Recent menu shows. Internal handshake via RecentResource.
     */
    private static void handleRecents(HttpExchange ex) throws IOException {
        try {
            String[] keys = {"model","models","smc","project","projects","files","main","ifc","ruleset","classification","ito","presentation","extension","Sre"};
            List<String> out = new ArrayList<>();
            java.util.Set<String> seenUris = new java.util.HashSet<>();
            for (String key : keys) {
                java.util.List<?> recents;
                try { recents = RecentResource.getRecentResources(key); } catch (Exception e) { continue; }
                if (recents == null) continue;
                for (Object r : recents) {
                    if (!(r instanceof RecentResource)) continue;
                    RecentResource rr = (RecentResource) r;
                    Resource res = rr.getResource();
                    if (res == null) continue;
                    String uri = String.valueOf(res.getURI());
                    if (!seenUris.add(uri)) continue;
                    out.add("{\"key\":" + str(key)
                            + ",\"name\":" + str(res.getName())
                            + ",\"provider\":" + str(res.getResourceProviderName())
                            + ",\"location\":" + str(res.getLocation())
                            + ",\"type\":" + str(res.getType())
                            + ",\"uri\":" + str(uri)
                            + ",\"time\":" + rr.getTime() + "}");
                }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"recents\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * /projects/{action}:
     *   POST /projects/close                  - close the currently open project
     *   POST /projects/openFile?path=&name=   - open a local .smc (close current first)
     */
    private static void handleProjects(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        try {
            if (method.equals("POST") && path.endsWith("/close")) {
                try { SMC.closeProject(); } catch (Exception e) { /* may NPE if no project */ }
                respond(ex, 200, "{\"closed\":true}");
                return;
            }
            if (method.equals("POST") && path.endsWith("/openFile")) {
                Map<String, String> q = query(ex);
                String filePath = q.getOrDefault("path", "");
                if (filePath.isEmpty()) { respond(ex, 400, "{\"error\":\"path query param required\"}"); return; }
                java.io.File f = new java.io.File(filePath);
                if (!f.exists() || !f.isFile()) { respond(ex, 404, "{\"error\":\"file not found\"}"); return; }
                String lower = filePath.toLowerCase();
                // .smc / .smcx project files: open via the internal robot keyword. Self-contained -
                // does NOT need the built-in 10876 REST server, so it works on any Solibri launch.
                if (lower.endsWith(".smc") || lower.endsWith(".smcx")) {
                    ProjectRobot robot = new ProjectRobot();
                    Map<String, Object> r = lower.endsWith(".smcx")
                            ? robot.openSMCXProject(f.getAbsolutePath())
                            : robot.openSMCProject(f.getAbsolutePath());
                    respond(ex, 200, "{\"opened\":" + str(f.getName()) + ",\"kind\":\"project\",\"result\":"
                            + str(String.valueOf(r)) + "}");
                    return;
                }
                // IFC (and other model files): ADDED to the currently-open project via the public
                // SDK. openModel() requires an existing project - with none open it blocks on a
                // modal UI prompt, so we guard that case. To open from nothing, POST a .smc/.smcx.
                if (SMC.getModel() == null) {
                    respond(ex, 409, "{\"error\":\"no project open - open a .smc/.smcx project first, "
                            + "then add IFC models\",\"kind\":\"model\"}");
                    return;
                }
                String name = q.getOrDefault("name", f.getName());
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    Model opened = SMC.openModel(name, in);
                    String uuid = "";
                    try { uuid = opened != null ? String.valueOf(opened.getUUID()) : ""; } catch (Exception ignore) {}
                    respond(ex, 200, "{\"opened\":" + str(name) + ",\"kind\":\"model\",\"uuid\":" + str(uuid) + "}");
                }
                return;
            }
            respond(ex, 405, "{\"error\":\"use POST /projects/close or POST /projects/openFile?path=PATH\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * GET /component?guid=G1,G2,... - for each GUID, how many components Solibri actually
     * holds (the duplicate-GUID dedup test) + a sample type/name. Single pass over the model.
     */
    private static void handleComponentByGuid(HttpExchange ex) throws IOException {
        String guidsParam = query(ex).getOrDefault("guid", "");
        if (guidsParam.isEmpty()) { respond(ex, 400, "{\"error\":\"guid query param required (comma-separated)\"}"); return; }
        try {
            Model model = SMC.getModel();
            if (model == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (String g : guidsParam.split(",")) { String t = g.trim(); if (!t.isEmpty()) targets.add(t); }
            java.util.concurrent.ConcurrentHashMap<String,int[]> counts = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String,String> sample = new java.util.concurrent.ConcurrentHashMap<>();
            model.getComponents(c -> {
                String g = c.getGUID();
                if (targets.contains(g)) {
                    counts.computeIfAbsent(g, k -> new int[1])[0]++;
                    sample.putIfAbsent(g, String.valueOf(c.getComponentType()) + "|" + c.getName());
                }
                return false;
            });
            List<String> out = new ArrayList<>();
            int totalKept = 0;
            for (String g : targets) {
                int n = counts.containsKey(g) ? counts.get(g)[0] : 0;
                totalKept += n;
                String s = sample.getOrDefault(g, "|");
                String[] parts = s.split("\\|", 2);
                out.add("{\"guid\":" + str(g) + ",\"countInModel\":" + n
                        + ",\"type\":" + str(parts[0]) + ",\"name\":" + str(parts.length>1?parts[1]:"") + "}");
            }
            respond(ex, 200, "{\"requested\":" + targets.size() + ",\"totalComponentsInModel\":" + totalKept
                    + ",\"results\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * POST /select  (body: JSON array of IFC GUIDs) - resolve to components and set the
     * selection basket = flag/highlight them all in the UI. Single pass over the model.
     */
    private static void handleSelect(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "{\"error\":\"POST a JSON array of GUIDs\"}"); return; }
        try {
            Model model = SMC.getModel();
            if (model == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.Set<String> targets = new java.util.HashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
            while (m.find()) targets.add(m.group(1));
            if (targets.isEmpty()) { respond(ex, 400, "{\"error\":\"no GUIDs in body (expect JSON array of strings)\"}"); return; }
            java.util.Set<Component> matched = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.Set<String> guidsFound = java.util.concurrent.ConcurrentHashMap.newKeySet();
            model.getComponents(c -> {
                if (targets.contains(c.getGUID())) { matched.add(c); guidsFound.add(c.getGUID()); }
                return false;
            });
            SMC.getSelectionBasket().set(matched);
            respond(ex, 200, "{\"requested\":" + targets.size() + ",\"guidsResolved\":" + guidsFound.size()
                    + ",\"componentsSelected\":" + matched.size() + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /**
     * POST /shutdown[?force=1] - stop the running Solibri instance gracefully (SMC.shutdown)
     * or immediately (SMC.shutdownNow). The HTTP response is flushed BEFORE the shutdown is
     * called, so the client sees a 200; the JVM exits a moment later.
     *
     * Makes the toolkit lifecycle self-sufficient on 10999 - no built-in 10876 needed.
     */
    private static void handleShutdown(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        boolean force = "1".equals(query(ex).getOrDefault("force", ""));
        respond(ex, 200, "{\"shutdown\":true,\"force\":" + force + "}");
        // Off-handler thread: brief delay so the response definitely flushes to the client,
        // then ask the SDK to stop the application. SMC.shutdown saves & exits cleanly;
        // shutdownNow exits immediately.
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignore) {}
            try { if (force) SMC.shutdownNow(); else SMC.shutdown(); }
            catch (Exception e) { System.err.println("[skiplum-superuser] shutdown failed: " + e); }
        }, "skiplum-shutdown").start();
    }

    // ==== cluster: checking-results-decisions ====
    private static void handleResultsFull(HttpExchange ex) throws IOException {
        // Off-EDT: SDK Result/Component reads are concurrent-safe (cf. ConcurrentRule, existing handleResults).
        try {
            if (SMC.getModel() == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            Checking checking = SMC.getChecking();
            if (checking == null) { respond(ex, 200, "{\"count\":0,\"skipped\":0,\"results\":[]}"); return; }
            List<String> out = new ArrayList<>();
            int skipped = 0;
            for (Result r : checking.getResults()) {
                try { out.add(resultJsonFull(r)); }
                catch (Exception perResult) { skipped++; }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"skipped\":" + skipped
                    + ",\"results\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Deep Result serializer used by /results/full. Includes category, decision, models,
     *  customUniqueKey flag, per-component decisions + slide refs. */
    private static String resultJsonFull(Result r) {
        List<String> guids = new ArrayList<>();
        for (Component c : r.getInvolvedComponents()) guids.add(str(c.getGUID()));
        String sev = r.getSeverity() != null ? r.getSeverity().name() : "";
        String dec = "";
        try { Decision d = r.getDecision(); if (d != null) dec = d.name(); } catch (Exception ignored) {}
        String ruleName = "";
        String ruleUid = "";
        try { if (r.getRule() != null) { ruleName = r.getRule().getName(); /* Rule SDK has no UID accessor */ } }
        catch (Exception ignored) {}
        // Primary category (Optional) + full category list with unique + display names.
        String primaryCat = "";
        try { Optional<ResultCategory> oc = r.getCategory(); if (oc.isPresent()) primaryCat = oc.get().getUniqueName(); }
        catch (Exception ignored) {}
        List<String> cats = new ArrayList<>();
        try {
            Collection<ResultCategory> cc = r.getCategories();
            if (cc != null) for (ResultCategory rc : cc) {
                cats.add("{\"uniqueName\":" + str(rc.getUniqueName())
                        + ",\"displayName\":" + str(rc.getDisplayName()) + "}");
            }
        } catch (Exception ignored) {}
        List<String> models = new ArrayList<>();
        try {
            Collection<Model> mm = r.getInvolvedModels();
            if (mm != null) for (Model m : mm) {
                String uuid = "";
                try { uuid = String.valueOf(m.getUUID()); } catch (Exception ignored) {}
                models.add("{\"name\":" + str(m.getName()) + ",\"uuid\":" + str(uuid) + "}");
            }
        } catch (Exception ignored) {}
        // Per-component decisions: map of GUID -> decision.name().
        List<String> compDecs = new ArrayList<>();
        try {
            Map<Component, Decision> cd = r.getComponentDecisions();
            if (cd != null) for (Map.Entry<Component, Decision> e : cd.entrySet()) {
                String dn = e.getValue() != null ? e.getValue().name() : "";
                compDecs.add("{\"guid\":" + str(e.getKey().getGUID()) + ",\"decision\":" + str(dn) + "}");
            }
        } catch (Exception ignored) {}
        // Per-component slides: map of GUID -> slide UUID + name. Slide.getUUID exists on the SDK;
        // we guard each call so an internal NPE on one entry never kills the whole serialization.
        List<String> compSlides = new ArrayList<>();
        try {
            Map<Component, Slide> cs = r.getComponentSlides();
            if (cs != null) for (Map.Entry<Component, Slide> e : cs.entrySet()) {
                String slideUuid = "";
                String slideName = "";
                try { Slide s = e.getValue(); if (s != null) { slideUuid = s.getUniqueId(); slideName = s.getTitle(); } }
                catch (Exception ignored2) {}
                compSlides.add("{\"guid\":" + str(e.getKey().getGUID())
                        + ",\"slideUUID\":" + str(slideUuid)
                        + ",\"slideName\":" + str(slideName) + "}");
            }
        } catch (Exception ignored) {}
        // Slide attached directly to the Result (separate from per-component slides).
        String resultSlideUuid = "";
        String resultSlideName = "";
        try { Optional<Slide> os = r.getSlide(); if (os.isPresent()) { resultSlideUuid = os.get().getUniqueId(); resultSlideName = os.get().getTitle(); } }
        catch (Exception ignored) {}
        boolean hasCustomKey = false;
        try { hasCustomKey = r.hasCustomUniqueKey(); } catch (Exception ignored) {}
        return "{\"name\":" + str(r.getName())
                + ",\"description\":" + str(r.getDescription())
                + ",\"severity\":" + str(sev)
                + ",\"decision\":" + str(dec)
                + ",\"rule\":" + str(ruleName)
                + ",\"ruleUid\":" + str(ruleUid)
                + ",\"uniqueKey\":" + str(r.getUniqueKey())
                + ",\"hasCustomUniqueKey\":" + hasCustomKey
                + ",\"location\":" + str(r.getLocation().orElse(""))
                + ",\"category\":" + str(primaryCat)
                + ",\"categories\":" + arr(cats)
                + ",\"models\":" + arr(models)
                + ",\"components\":" + arr(guids)
                + ",\"componentDecisions\":" + arr(compDecs)
                + ",\"componentSlides\":" + arr(compSlides)
                + ",\"slideUUID\":" + str(resultSlideUuid)
                + ",\"slideName\":" + str(resultSlideName) + "}";
    }

    private static void handleResultsByRule(HttpExchange ex) throws IOException {
        String rule = query(ex).getOrDefault("rule", "");
        if (rule.isEmpty()) { respond(ex, 400, "{\"error\":\"rule query param required\"}"); return; }
        boolean full = "1".equals(query(ex).getOrDefault("full", ""));
        try {
            if (SMC.getModel() == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            Checking checking = SMC.getChecking();
            if (checking == null) { respond(ex, 200, "{\"count\":0,\"skipped\":0,\"matched\":0,\"results\":[]}"); return; }
            int total = 0, skipped = 0;
            List<String> out = new ArrayList<>();
            for (Result r : checking.getResults()) {
                total++;
                String rn = "";
                try { rn = r.getRule() != null ? r.getRule().getName() : ""; } catch (Exception ignored) {}
                if (!rule.equals(rn)) continue;
                try { out.add(full ? resultJsonFull(r) : resultJson(r)); }
                catch (Exception perResult) { skipped++; }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"scanned\":" + total
                    + ",\"skipped\":" + skipped + ",\"rule\":" + str(rule)
                    + ",\"results\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleResultsBySeverity(HttpExchange ex) throws IOException {
        String sevParam = query(ex).getOrDefault("sev", "");
        if (sevParam.isEmpty()) { respond(ex, 400, "{\"error\":\"sev query param required (CRITICAL|MODERATE|LOW|PASSED)\"}"); return; }
        Severity wanted;
        try { wanted = Severity.valueOf(sevParam.trim().toUpperCase()); }
        catch (IllegalArgumentException iae) {
            respond(ex, 400, "{\"error\":\"unknown severity: " + sevParam.replace("\"","\\\"")
                    + " (use CRITICAL|MODERATE|LOW|PASSED)\"}"); return;
        }
        boolean full = "1".equals(query(ex).getOrDefault("full", ""));
        try {
            if (SMC.getModel() == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
            Checking checking = SMC.getChecking();
            if (checking == null) { respond(ex, 200, "{\"count\":0,\"skipped\":0,\"results\":[]}"); return; }
            int total = 0, skipped = 0;
            List<String> out = new ArrayList<>();
            for (Result r : checking.getResults()) {
                total++;
                Severity s = r.getSeverity();
                if (s != wanted) continue;
                try { out.add(full ? resultJsonFull(r) : resultJson(r)); }
                catch (Exception perResult) { skipped++; }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"scanned\":" + total
                    + ",\"skipped\":" + skipped + ",\"severity\":" + str(wanted.name())
                    + ",\"results\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 200, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleResultDecision(HttpExchange ex) throws IOException {
        // Registered at the trailing-slash prefix "/results/" so longer-prefix contexts
        // (/results/full, /results/byRule, /results/bySeverity) win for those paths.
        // Only the dynamic "/results/{uniqueKey}/decision" pattern lands here.
        String path = ex.getRequestURI().getPath();
        String prefix = BASE + "/results/";
        String suffix = "/decision";
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length() <= prefix.length() + suffix.length()) {
            respond(ex, 404, "{\"error\":\"unknown /results subpath; expected /results/{uniqueKey}/decision\"}"); return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST with ?decision=ACCEPTED|REJECTED|UNDEFINED or a JSON body {\\\"decision\\\":\\\"...\\\"}\"}"); return;
        }
        String middle = path.substring(prefix.length(), path.length() - suffix.length());
        String uniqueKey = java.net.URLDecoder.decode(middle, StandardCharsets.UTF_8);
        if (uniqueKey.isEmpty()) { respond(ex, 400, "{\"error\":\"uniqueKey is empty\"}"); return; }
        // Decision from ?decision=... preferred; fall back to a tiny body parse (no full JSON dep).
        String decisionParam = query(ex).getOrDefault("decision", "");
        if (decisionParam.isEmpty()) {
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"decision\"\\s*:\\s*\"([A-Za-z]+)\"").matcher(body);
                if (m.find()) decisionParam = m.group(1);
            } catch (Exception ignored) {}
        }
        if (decisionParam.isEmpty()) {
            respond(ex, 400, "{\"error\":\"decision required (query ?decision= or body {\\\"decision\\\":...})\"}"); return;
        }
        final Decision target;
        try { target = Decision.valueOf(decisionParam.trim().toUpperCase()); }
        catch (IllegalArgumentException iae) {
            respond(ex, 400, "{\"error\":\"unknown decision: " + decisionParam.replace("\"","\\\"")
                    + " (use ACCEPTED|REJECTED|UNDEFINED)\"}"); return;
        }
        if (SMC.getModel() == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
        Checking checking = SMC.getChecking();
        if (checking == null) { respond(ex, 409, "{\"error\":\"checking subsystem unavailable\"}"); return; }
        // Locate the Result by uniqueKey off-EDT (read), then apply setDecision on the EDT
        // (model-state mutation; mirrors model graph access pattern used by handleModel).
        Result match = null;
        try {
            for (Result r : checking.getResults()) {
                try { if (uniqueKey.equals(r.getUniqueKey())) { match = r; break; } }
                catch (Exception ignored) {}
            }
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}"); return;
        }
        if (match == null) {
            respond(ex, 404, "{\"error\":\"no Result with uniqueKey=" + uniqueKey.replace("\"","\\\"") + "\"}"); return;
        }
        final Result fmatch = match;
        String applied = onEdt(() -> {
            Decision before = fmatch.getDecision();
            fmatch.setDecision(target);
            Decision after = fmatch.getDecision();
            return "{\"uniqueKey\":" + str(fmatch.getUniqueKey())
                    + ",\"before\":" + str(before != null ? before.name() : "")
                    + ",\"requested\":" + str(target.name())
                    + ",\"after\":" + str(after != null ? after.name() : "") + "}";
        });
        respond(ex, 200, applied);
    }

    // ==== cluster: checking-rules-parameters ====
    private static void handleRules(HttpExchange ex) throws IOException {
    if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
        respond(ex, 405, "{\"error\":\"use GET\"}");
        return;
    }
    respond(ex, 200, onEdt(() -> {
        Model m = SMC.getModel();
        if (m == null) return "{\"error\":\"no project open\"}";
        List<String> out = new ArrayList<>();
        for (Ruleset rs : m.getRulesets()) {
            collectRulesFlat(rs, rs.getUniqueId(), out);
        }
        return "{\"count\":" + out.size() + ",\"rules\":" + arr(out) + "}";
    }));
}

private static void collectRulesFlat(CheckingNode node, String rulesetId, List<String> out) {
    if (node instanceof Rule) {
        Rule r = (Rule) node;
        StringBuilder b = new StringBuilder("{");
        b.append("\"name\":").append(str(safe(r::getName, "")));
        b.append(",\"ruleset\":").append(str(rulesetId));
        String help = "";
        try { Optional<java.net.URL> u = r.getHelpUrl(); if (u != null && u.isPresent()) help = u.get().toString(); } catch (Exception ignore) {}
        b.append(",\"helpUrl\":").append(str(help));
        // params: id + type only (full inspection is /rules/{name}/parameters)
        List<String> params = new ArrayList<>();
        try {
            RuleParameters rps = RuleParameters.of(r);
            if (rps != null) {
                for (RuleParameter<?> p : rps.asList()) {
                    String type = paramTypeName(p);
                    String id = safe(p::getId, "");
                    String valStr;
                    try { Object v = p.getValue(); valStr = v == null ? "" : String.valueOf(v); }
                    catch (Exception e) { valStr = ""; }
                    params.add("{\"id\":" + str(id) + ",\"type\":" + str(type)
                            + ",\"value\":" + str(valStr) + "}");
                }
            }
        } catch (Exception ignore) {}
        b.append(",\"parameters\":").append(arr(params));
        b.append("}");
        out.add(b.toString());
        return;
    }
    try {
        List<CheckingNode> kids = node.getChildren();
        if (kids != null) for (CheckingNode k : kids) collectRulesFlat(k, rulesetId, out);
    } catch (Exception ignore) {}
}

private static String paramTypeName(RuleParameter<?> p) {
    if (p instanceof com.solibri.smc.api.checking.BooleanParameter) return "BOOLEAN";
    if (p instanceof com.solibri.smc.api.checking.IntParameter) return "INT";
    if (p instanceof com.solibri.smc.api.checking.DoubleParameter) return "DOUBLE";
    if (p instanceof com.solibri.smc.api.checking.StringParameter) return "STRING";
    if (p instanceof com.solibri.smc.api.checking.EnumerationParameter) return "ENUMERATION";
    if (p instanceof com.solibri.smc.api.checking.ClassificationParameter) return "CLASSIFICATION";
    if (p instanceof com.solibri.smc.api.checking.FilterParameter) return "FILTER";
    if (p instanceof com.solibri.smc.api.checking.PropertyReferenceParameter) return "PROPERTY_REFERENCE";
    if (p instanceof com.solibri.smc.api.checking.TableParameter) return "TABLE";
    return p.getClass().getSimpleName();
}

private static Rule findRuleByName(Model m, String name) {
    if (m == null || name == null) return null;
    for (Ruleset rs : m.getRulesets()) {
        Rule r = findRuleByNameRecursive(rs, name);
        if (r != null) return r;
    }
    return null;
}

private static Rule findRuleByNameRecursive(CheckingNode node, String name) {
    if (node instanceof Rule) {
        try { if (name.equals(node.getName())) return (Rule) node; } catch (Exception ignore) {}
    }
    try {
        List<CheckingNode> kids = node.getChildren();
        if (kids != null) for (CheckingNode k : kids) {
            Rule hit = findRuleByNameRecursive(k, name);
            if (hit != null) return hit;
        }
    } catch (Exception ignore) {}
    return null;
}

    private static void handleRuleParameters(HttpExchange ex) throws IOException {
    String method = ex.getRequestMethod().toUpperCase();
    String path = ex.getRequestURI().getPath();
    // path = BASE + "/rules/<name>/parameters" OR BASE + "/rules/<name>/components"
    String prefix = BASE + "/rules/";
    if (!path.startsWith(prefix)) {
        respond(ex, 404, "{\"error\":\"not found\"}");
        return;
    }
    String tail = path.substring(prefix.length());
    // Route /rules/{name}/components to handleRuleComponents (same context registered to both)
    if (tail.endsWith("/components")) {
        handleRuleComponents(ex);
        return;
    }
    if (!tail.endsWith("/parameters")) {
        respond(ex, 404, "{\"error\":\"use /rules/{name}/parameters or /rules/{name}/components\"}");
        return;
    }
    String ruleName = java.net.URLDecoder.decode(
            tail.substring(0, tail.length() - "/parameters".length()), StandardCharsets.UTF_8);
    if (ruleName.isEmpty()) {
        respond(ex, 400, "{\"error\":\"rule name required\"}");
        return;
    }

    if (method.equals("GET")) {
        respond(ex, 200, onEdt(() -> {
            Model m = SMC.getModel();
            if (m == null) return "{\"error\":\"no project open\"}";
            Rule rule = findRuleByName(m, ruleName);
            if (rule == null) return "{\"error\":\"rule not found: " + ruleName.replace("\"", "\\\"") + "\"}";
            List<String> out = new ArrayList<>();
            try {
                RuleParameters rps = RuleParameters.of(rule);
                if (rps != null) for (RuleParameter<?> p : rps.asList()) out.add(paramJson(p));
            } catch (Exception e) {
                return "{\"error\":" + str(String.valueOf(e.getMessage())) + "}";
            }
            return "{\"rule\":" + str(ruleName)
                    + ",\"count\":" + out.size()
                    + ",\"parameters\":" + arr(out) + "}";
        }));
        return;
    }

    if (method.equals("POST")) {
        // Read body BEFORE jumping to EDT (handler thread already off-EDT here).
        final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (SMC.isBusy()) { respond(ex, 409, "{\"error\":\"Solibri is busy\"}"); return; }
        respond(ex, 200, onEdt(() -> {
            Model m = SMC.getModel();
            if (m == null) return "{\"error\":\"no project open\"}";
            Rule rule = findRuleByName(m, ruleName);
            if (rule == null) return "{\"error\":\"rule not found: " + ruleName.replace("\"", "\\\"") + "\"}";
            // Parse flat {id: value, ...} - values are strings or JSON literals (numbers / true / false).
            java.util.Map<String, String> patches = parseFlatJsonObject(body);
            if (patches.isEmpty()) return "{\"error\":\"body must be JSON object {paramId: value, ...}\"}";
            RuleParameters rps;
            try { rps = RuleParameters.of(rule); }
            catch (Exception e) { return "{\"error\":" + str(String.valueOf(e.getMessage())) + "}"; }
            if (rps == null) return "{\"error\":\"no parameters on rule\"}";
            java.util.Map<String, RuleParameter<?>> byId = new java.util.HashMap<>();
            for (RuleParameter<?> p : rps.asList()) byId.put(safe(p::getId, ""), p);
            List<String> applied = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, String> e : patches.entrySet()) {
                RuleParameter<?> p = byId.get(e.getKey());
                if (p == null) {
                    failed.add("{\"id\":" + str(e.getKey()) + ",\"error\":\"unknown parameter id\"}");
                    continue;
                }
                try {
                    applyParamValue(p, e.getValue());
                    String now;
                    try { Object v = p.getValue(); now = v == null ? "" : String.valueOf(v); }
                    catch (Exception ex2) { now = ""; }
                    applied.add("{\"id\":" + str(e.getKey()) + ",\"value\":" + str(now) + "}");
                } catch (Exception ex2) {
                    failed.add("{\"id\":" + str(e.getKey())
                            + ",\"error\":" + str(String.valueOf(ex2.getMessage())) + "}");
                }
            }
            return "{\"rule\":" + str(ruleName)
                    + ",\"appliedCount\":" + applied.size()
                    + ",\"failedCount\":" + failed.size()
                    + ",\"applied\":" + arr(applied)
                    + ",\"failed\":" + arr(failed) + "}";
        }));
        return;
    }

    respond(ex, 405, "{\"error\":\"use GET or POST\"}");
}

private static String paramJson(RuleParameter<?> p) {
    StringBuilder b = new StringBuilder("{");
    String id = safe(p::getId, "");
    String type = paramTypeName(p);
    b.append("\"id\":").append(str(id));
    b.append(",\"type\":").append(str(type));
    String valStr;
    try { Object v = p.getValue(); valStr = v == null ? "" : String.valueOf(v); }
    catch (Exception e) { valStr = ""; }
    b.append(",\"value\":").append(str(valStr));
    if (p instanceof EnumerationParameter) {
        List<String> opts = new ArrayList<>();
        try { for (String o : ((EnumerationParameter) p).getOptions()) opts.add(str(o)); }
        catch (Exception ignore) {}
        b.append(",\"options\":").append(arr(opts));
    }
    if (p instanceof DoubleParameter) {
        try { b.append(",\"propertyType\":").append(str(String.valueOf(((DoubleParameter) p).getPropertyType()))); }
        catch (Exception ignore) {}
    }
    if (p instanceof PropertyReferenceParameter) {
        try {
            PropertyReference pr = ((PropertyReferenceParameter) p).getValue();
            if (pr != null) {
                b.append(",\"pset\":").append(str(pr.getPropertySetName()));
                b.append(",\"prop\":").append(str(pr.getPropertyName()));
            }
        } catch (Exception ignore) {}
    }
    b.append("}");
    return b.toString();
}

@SuppressWarnings({"unchecked","rawtypes"})
private static void applyParamValue(RuleParameter<?> p, String raw) {
    String s = raw == null ? "" : raw.trim();
    if (p instanceof BooleanParameter) {
        ((BooleanParameter) p).setValue(Boolean.parseBoolean(s));
    } else if (p instanceof IntParameter) {
        ((IntParameter) p).setValue(Integer.parseInt(s));
    } else if (p instanceof DoubleParameter) {
        ((DoubleParameter) p).setValue(Double.parseDouble(s));
    } else if (p instanceof StringParameter) {
        ((StringParameter) p).setValue(s);
    } else if (p instanceof EnumerationParameter) {
        ((EnumerationParameter) p).setValue(s);
    } else if (p instanceof ClassificationParameter) {
        ((ClassificationParameter) p).setValue(s);
    } else {
        throw new IllegalArgumentException("unsupported parameter type for patch: " + paramTypeName(p));
    }
}

/** Very small JSON-object parser: top-level {"key":value,...} where value is a string, number, true/false, or null. Nested objects/arrays not supported (out of scope for parameter patching). */
private static java.util.Map<String, String> parseFlatJsonObject(String body) {
    java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
    if (body == null) return out;
    String s = body.trim();
    if (s.isEmpty() || s.charAt(0) != '{') return out;
    int i = 1, n = s.length();
    while (i < n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i < n && s.charAt(i) == '}') break;
        if (i >= n || s.charAt(i) != '"') break;
        // parse key (string)
        i++;
        StringBuilder k = new StringBuilder();
        while (i < n && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) { k.append(s.charAt(i + 1)); i += 2; }
            else { k.append(c); i++; }
        }
        if (i < n) i++; // closing "
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= n || s.charAt(i) != ':') break;
        i++;
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        // parse value
        StringBuilder v = new StringBuilder();
        if (i < n && s.charAt(i) == '"') {
            i++;
            while (i < n && s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < n) { v.append(s.charAt(i + 1)); i += 2; }
                else { v.append(c); i++; }
            }
            if (i < n) i++;
        } else {
            while (i < n && ",}".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) {
                v.append(s.charAt(i)); i++;
            }
        }
        out.put(k.toString(), v.toString());
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i < n && s.charAt(i) == ',') { i++; continue; }
        if (i < n && s.charAt(i) == '}') break;
    }
    return out;
}

    private static void handleRuleComponents(HttpExchange ex) throws IOException {
    if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
        respond(ex, 405, "{\"error\":\"use GET\"}");
        return;
    }
    String path = ex.getRequestURI().getPath();
    String prefix = BASE + "/rules/";
    if (!path.startsWith(prefix) || !path.endsWith("/components")) {
        respond(ex, 404, "{\"error\":\"use /rules/{name}/components\"}");
        return;
    }
    String ruleName = java.net.URLDecoder.decode(
            path.substring(prefix.length(), path.length() - "/components".length()),
            StandardCharsets.UTF_8);
    String status = query(ex).getOrDefault("status", "checked").toLowerCase();
    int limit;
    try { limit = Math.min(Math.max(Integer.parseInt(query(ex).getOrDefault("limit", "5000")), 1), 100000); }
    catch (NumberFormatException nfe) { limit = 5000; }
    final int cap = limit;
    // Off-EDT: component sets can be large; SDK reads are concurrent-safe (cf. ConcurrentRule).
    try {
        Model m = SMC.getModel();
        if (m == null) { respond(ex, 503, "{\"error\":\"no project open\"}"); return; }
        Checking checking = SMC.getChecking();
        if (checking == null) { respond(ex, 503, "{\"error\":\"checking unavailable\"}"); return; }
        Rule rule = findRuleByName(m, ruleName);
        if (rule == null) {
            respond(ex, 404, "{\"error\":\"rule not found: " + ruleName.replace("\"", "\\\"") + "\"}");
            return;
        }
        Set<Component> comps;
        switch (status) {
            case "passed":  comps = checking.getPassedComponents(rule); break;
            case "failed":  comps = checking.getFailedComponents(rule); break;
            case "checked": comps = checking.getCheckedComponents(rule); break;
            default:
                respond(ex, 400, "{\"error\":\"status must be passed|failed|checked\"}");
                return;
        }
        if (comps == null) comps = java.util.Collections.emptySet();
        int total = comps.size();
        List<String> guids = new ArrayList<>(Math.min(total, cap));
        int n = 0;
        for (Component c : comps) {
            if (n++ >= cap) break;
            try { guids.add(str(c.getGUID())); } catch (Exception ignore) {}
        }
        respond(ex, 200, "{\"rule\":" + str(ruleName)
                + ",\"status\":" + str(status)
                + ",\"total\":" + total
                + ",\"returned\":" + guids.size()
                + ",\"limit\":" + cap
                + ",\"guids\":" + arr(guids) + "}");
    } catch (Exception e) {
        respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
    }
}

    // ==== cluster: coordination-presentations-slides ====
    private static void handlePresentations(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        // Slide sub-resources (list/add) are handled by handlePresentationSlides; the contexts
        // are distinct but createContext does longest-prefix routing, so guard here too in case
        // the slide context isn't registered.
        if (path.contains("/slides")) {
            handlePresentationSlides(ex);
            return;
        }
        try {
            if (method.equals("GET")) {
                respond(ex, 200, onEdt(() -> {
                    java.util.List<Presentation> pres = SMC.getPresentations();
                    if (pres == null) return "{\"count\":0,\"presentations\":[]}";
                    List<String> out = new ArrayList<>();
                    for (Presentation p : pres) {
                        int slideCount = 0;
                        try { java.util.Collection<Slide> ss = p.getSlides(); if (ss != null) slideCount = ss.size(); } catch (Exception ignore) {}
                        out.add("{\"uniqueId\":" + str(p.getUniqueId())
                                + ",\"name\":" + str(p.getName())
                                + ",\"slideCount\":" + slideCount + "}");
                    }
                    return "{\"count\":" + out.size() + ",\"presentations\":" + arr(out) + "}";
                }));
                return;
            }
            if (method.equals("POST")) {
                if (SMC.getModel() == null) {
                    respond(ex, 409, "{\"error\":\"no project open - open a .smc/.smcx project first\"}");
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = extractJsonString(body, "name");
                String prefix = extractJsonString(body, "prefix");
                if (name == null || name.isEmpty()) {
                    respond(ex, 400, "{\"error\":\"body must be JSON with a non-empty 'name' field\"}");
                    return;
                }
                final String fname = name;
                final String fprefix = prefix;
                respond(ex, 200, onEdt(() -> {
                    Presentation p = Presentation.create(fname);
                    if (p == null) return "{\"error\":\"Presentation.create returned null\"}";
                    if (fprefix != null && !fprefix.isEmpty()) {
                        try { p.setTrackingIdPrefix(fprefix); } catch (Exception ignore) {}
                    }
                    return "{\"uniqueId\":" + str(p.getUniqueId())
                            + ",\"name\":" + str(p.getName())
                            + ",\"slideCount\":0}";
                }));
                return;
            }
            respond(ex, 405, "{\"error\":\"use GET /presentations or POST /presentations\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Minimal JSON string-field extractor for shallow {"key":"value"} bodies. Returns null if absent. */
    private static String extractJsonString(String body, String key) {
        if (body == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void handlePresentationSlides(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        String path = ex.getRequestURI().getPath();
        // Expected: BASE + /presentations/{id}/slides
        String prefix = BASE + "/presentations/";
        if (!path.startsWith(prefix) || !path.endsWith("/slides")) {
            respond(ex, 404, "{\"error\":\"use /presentations/{id}/slides\"}");
            return;
        }
        String id = java.net.URLDecoder.decode(
                path.substring(prefix.length(), path.length() - "/slides".length()),
                StandardCharsets.UTF_8);
        if (id.isEmpty()) {
            respond(ex, 400, "{\"error\":\"presentation id required in path\"}");
            return;
        }
        try {
            // Locate the presentation up-front so both GET and POST share the same lookup.
            Presentation target = onEdtPresentation(id);
            if (target == null) {
                respond(ex, 404, "{\"error\":\"presentation not found: " + id.replace("\"", "\\\"") + "\"}");
                return;
            }
            if (method.equals("GET")) {
                final Presentation fTarget = target;
                respond(ex, 200, onEdt(() -> {
                    java.util.Collection<Slide> slides = fTarget.getSlides();
                    List<String> out = new ArrayList<>();
                    if (slides != null) for (Slide s : slides) {
                        try { out.add(slideSummaryJson(s)); }
                        catch (Exception perSlide) { /* skip broken slide */ }
                    }
                    return "{\"presentationId\":" + str(fTarget.getUniqueId())
                            + ",\"count\":" + out.size()
                            + ",\"slides\":" + arr(out) + "}";
                }));
                return;
            }
            if (method.equals("POST")) {
                if (SMC.getModel() == null) {
                    respond(ex, 409, "{\"error\":\"no project open\"}");
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractJsonString(body, "title");
                String description = extractJsonString(body, "description");
                String status = extractJsonString(body, "status");
                String stage = extractJsonString(body, "stage");
                String priority = extractJsonString(body, "priority");
                String type = extractJsonString(body, "type");
                String dueDate = extractJsonString(body, "dueDate");
                List<String> components = extractJsonStringArray(body, "components");
                List<String> responsibilities = extractJsonStringArray(body, "responsibilities");
                List<String> labels = extractJsonStringArray(body, "labels");

                // Resolve component GUIDs to Component instances OFF-EDT (large-model scan).
                java.util.Set<Component> resolved = java.util.concurrent.ConcurrentHashMap.newKeySet();
                int requestedComponents = components.size();
                if (requestedComponents > 0) {
                    java.util.Set<String> targets = new java.util.HashSet<>(components);
                    Model model = SMC.getModel();
                    model.getComponents(c -> {
                        if (targets.contains(c.getGUID())) resolved.add(c);
                        return false;
                    });
                }

                final Presentation fTarget = target;
                final String fTitle = title;
                final String fDescription = description;
                final String fStatus = status;
                final String fStage = stage;
                final String fPriority = priority;
                final String fType = type;
                final String fDueDate = dueDate;
                final List<String> fResponsibilities = responsibilities;
                final java.util.Set<String> fLabels = new java.util.HashSet<>(labels);
                final java.util.Set<Component> fComponents = resolved;
                final int fRequested = requestedComponents;

                // Mutate the slide graph on the EDT.
                respond(ex, 200, onEdt(() -> {
                    Slide s = Slide.create();
                    if (fTitle != null) s.setTitle(fTitle);
                    if (fDescription != null) s.setDescription(fDescription);
                    if (fStatus != null) s.setStatus(fStatus);
                    if (fStage != null) s.setStage(fStage);
                    if (fPriority != null) s.setPriority(fPriority);
                    if (fType != null) s.setType(fType);
                    if (fResponsibilities != null && !fResponsibilities.isEmpty()) {
                        s.setResponsibilities(new ArrayList<>(fResponsibilities));
                    }
                    if (!fLabels.isEmpty()) s.setLabels(fLabels);
                    if (fDueDate != null && !fDueDate.isEmpty()) {
                        try { s.setDueDate(LocalDate.parse(fDueDate)); } catch (Exception ignore) {}
                    }
                    if (!fComponents.isEmpty()) s.setComponents(fComponents);
                    boolean added = fTarget.addSlide(s);
                    return "{\"presentationId\":" + str(fTarget.getUniqueId())
                            + ",\"slideId\":" + str(s.getUniqueId())
                            + ",\"added\":" + added
                            + ",\"componentsRequested\":" + fRequested
                            + ",\"componentsResolved\":" + fComponents.size() + "}";
                }));
                return;
            }
            respond(ex, 405, "{\"error\":\"use GET or POST on /presentations/{id}/slides\"}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Look up a Presentation by uniqueId on the EDT. Null if absent. */
    private static Presentation onEdtPresentation(String id) {
        Presentation[] holder = new Presentation[1];
        try {
            Runnable r = () -> {
                java.util.List<Presentation> pres = SMC.getPresentations();
                if (pres == null) return;
                for (Presentation p : pres) {
                    if (id.equals(p.getUniqueId())) { holder[0] = p; return; }
                }
            };
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
        } catch (Exception ignore) {}
        return holder[0];
    }

    /** Compact per-slide JSON for list views (no viewpoint bytes, no comment bodies). */
    private static String slideSummaryJson(Slide s) {
        List<String> guids = new ArrayList<>();
        try { for (Component c : s.getComponents()) guids.add(str(c.getGUID())); } catch (Exception ignore) {}
        String stage = ""; try { stage = s.getStage().orElse(""); } catch (Exception ignore) {}
        String priority = ""; try { priority = s.getPriority().orElse(""); } catch (Exception ignore) {}
        String due = ""; try { due = s.getDueDate().map(java.time.LocalDate::toString).orElse(""); } catch (Exception ignore) {}
        int viewpoints = 0; try { viewpoints = s.getViewpoints() != null ? s.getViewpoints().size() : 0; } catch (Exception ignore) {}
        int comments = 0; try { comments = s.getComments() != null ? s.getComments().size() : 0; } catch (Exception ignore) {}
        return "{\"uniqueId\":" + str(s.getUniqueId())
                + ",\"title\":" + str(s.getTitle())
                + ",\"status\":" + str(s.getStatus())
                + ",\"stage\":" + str(stage)
                + ",\"priority\":" + str(priority)
                + ",\"type\":" + str(s.getType())
                + ",\"dueDate\":" + str(due)
                + ",\"viewpointCount\":" + viewpoints
                + ",\"commentCount\":" + comments
                + ",\"components\":" + arr(guids) + "}";
    }

    /** Minimal JSON string-array extractor for shallow {"key":["a","b"]} bodies. Returns empty list when absent. */
    private static List<String> extractJsonStringArray(String body, String key) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(body);
        if (!m.find()) return out;
        String inner = m.group(1);
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile(
                "\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(inner);
        while (sm.find()) out.add(sm.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return out;
    }

    private static void handleBcfXmlExport(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST /bcfxml/export?file=PATH[&version=&scope=]\"}");
            return;
        }
        Map<String, String> q = query(ex);
        String filePath = q.getOrDefault("file", "");
        if (filePath.isEmpty()) {
            respond(ex, 400, "{\"error\":\"file query param required\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        BcfVersion version;
        try { version = BcfVersion.valueOf(q.getOrDefault("version", "V2_1")); }
        catch (IllegalArgumentException iae) {
            respond(ex, 400, "{\"error\":\"unknown version (use V1_0|V2_0|V2_1|V3_0)\"}");
            return;
        }
        BcfScope scope;
        try { scope = BcfScope.valueOf(q.getOrDefault("scope", "ALL")); }
        catch (IllegalArgumentException iae) {
            respond(ex, 400, "{\"error\":\"unknown scope (use ALL|SELECTED|MARKED)\"}");
            return;
        }
        java.io.File out = new java.io.File(filePath);
        java.io.File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            respond(ex, 404, "{\"error\":\"parent directory does not exist: "
                    + parent.getAbsolutePath().replace("\\", "\\\\") + "\"}");
            return;
        }
        try {
            BcfXml bcf = SMC.getBcfXml();
            if (bcf == null) { respond(ex, 500, "{\"error\":\"BcfXml service unavailable\"}"); return; }
            boolean ok;
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                ok = bcf.exportBcfXml(version, scope, os);
            }
            long size = out.exists() ? out.length() : -1;
            respond(ex, 200, "{\"exported\":" + ok
                    + ",\"path\":" + str(out.getAbsolutePath())
                    + ",\"size\":" + size
                    + ",\"version\":" + str(version.name())
                    + ",\"scope\":" + str(scope.name()) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    // ==== cluster: ito-full-coverage ====
    private static void handleIto(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use GET\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        try {
            InformationTakeoff ito = SMC.getInformationTakeoff();
            if (ito == null) {
                respond(ex, 200, "{\"count\":0,\"definitions\":[]}");
                return;
            }
            List<ItoDefinition> defs = ito.getItoDefinitions();
            List<String> out = new ArrayList<>();
            if (defs != null) {
                for (ItoDefinition d : defs) {
                    try {
                        out.add("{\"id\":" + str(String.valueOf(d.getUniqueId()))
                                + ",\"name\":" + str(d.getName())
                                + ",\"description\":" + str(d.getDescription()) + "}");
                    } catch (Exception perDef) { /* skip individual broken def */ }
                }
            }
            respond(ex, 200, "{\"count\":" + out.size() + ",\"definitions\":" + arr(out) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleItoCalculate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        if (SMC.isBusy()) {
            respond(ex, 409, "{\"error\":\"Solibri is busy\"}");
            return;
        }
        String idParam = query(ex).getOrDefault("id", "").trim();
        long t0 = System.currentTimeMillis();
        try {
            InformationTakeoff ito = SMC.getInformationTakeoff();
            if (ito == null) {
                respond(ex, 503, "{\"error\":\"ITO engine unavailable\"}");
                return;
            }
            String calculated;
            int defCount;
            if (idParam.isEmpty()) {
                ito.calculateAll();
                calculated = "all";
                List<ItoDefinition> defs = ito.getItoDefinitions();
                defCount = defs != null ? defs.size() : 0;
            } else {
                UUID target;
                try { target = UUID.fromString(idParam); }
                catch (IllegalArgumentException iae) {
                    respond(ex, 400, "{\"error\":\"id must be a UUID\"}");
                    return;
                }
                ItoDefinition match = null;
                List<ItoDefinition> defs = ito.getItoDefinitions();
                if (defs != null) {
                    for (ItoDefinition d : defs) {
                        try { if (target.equals(d.getUniqueId())) { match = d; break; } }
                        catch (Exception ignore) {}
                    }
                }
                if (match == null) {
                    respond(ex, 404, "{\"error\":\"no ItoDefinition with id " + idParam + "\"}");
                    return;
                }
                ito.calculate(match);
                calculated = idParam;
                defCount = 1;
            }
            long ms = System.currentTimeMillis() - t0;
            int rowCount = 0;
            try { rowCount = ito.getRows() != null ? ito.getRows().size() : 0; } catch (Exception ignore) {}
            respond(ex, 200, "{\"calculated\":" + str(calculated)
                    + ",\"definitions\":" + defCount
                    + ",\"rows\":" + rowCount
                    + ",\"elapsedMs\":" + ms + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    private static void handleItoResults(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use GET\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        try {
            InformationTakeoff ito = SMC.getInformationTakeoff();
            if (ito == null) {
                respond(ex, 200, "{\"columns\":[],\"rows\":[],\"total\":null,\"count\":0}");
                return;
            }
            List<ItoColumn> cols = ito.getColumns();
            List<String> colJson = new ArrayList<>();
            if (cols != null) {
                for (ItoColumn c : cols) {
                    try {
                        PropertyType pt = c.getType();
                        String typeName = pt != null ? pt.name() : "UNKNOWN";
                        boolean num = pt != null && pt.isNumberType();
                        colJson.add("{\"name\":" + str(c.getName())
                                + ",\"type\":" + str(typeName)
                                + ",\"numeric\":" + num + "}");
                    } catch (Exception perCol) { /* skip */ }
                }
            }
            List<ItoRow> rows = ito.getRows();
            List<String> rowJson = new ArrayList<>();
            int skipped = 0;
            if (rows != null) {
                for (ItoRow r : rows) {
                    try { rowJson.add(itoRowJson(r)); }
                    catch (Exception perRow) { skipped++; }
                }
            }
            String totalJson = "null";
            try {
                ItoRow total = ito.getTotalRow();
                if (total != null) totalJson = itoRowJson(total);
            } catch (Exception ignore) {}
            respond(ex, 200, "{\"columns\":" + arr(colJson)
                    + ",\"count\":" + rowJson.size()
                    + ",\"skipped\":" + skipped
                    + ",\"rows\":" + arr(rowJson)
                    + ",\"total\":" + totalJson + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str(String.valueOf(e.getMessage())) + "}");
        }
    }

    /** Serialize one ItoRow as a JSON array of cells, typing each via its column's PropertyType. */
    private static String itoRowJson(ItoRow r) {
        List<ItoValue<?>> vals = r.getValues();
        List<String> cells = new ArrayList<>();
        if (vals != null) {
            for (ItoValue<?> v : vals) {
                cells.add(itoCellJson(v));
            }
        }
        return arr(cells);
    }

    /** Serialize one ItoValue as a JSON literal honoring its column's PropertyType. */
    private static String itoCellJson(ItoValue<?> v) {
        if (v == null) return "null";
        Object value;
        PropertyType pt;
        try { value = v.getValue(); } catch (Exception e) { return "null"; }
        try { pt = v.getColumn() != null ? v.getColumn().getType() : null; } catch (Exception e) { pt = null; }
        if (value == null) return "null";
        if (pt == PropertyType.BOOLEAN || value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? "true" : "false";
        }
        if (pt != null && pt.isNumberType() && value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            // INT renders without decimals; everything else as a plain Number string.
            if (pt == PropertyType.INT) return Long.toString(((Number) value).longValue());
            return value.toString();
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            return value.toString();
        }
        return str(String.valueOf(value));
    }

    // ==== cluster: project-save-metadata ====
        private static void handleProjectSave(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        if (SMC.isBusy()) {
            respond(ex, 409, "{\"error\":\"Solibri is busy\"}");
            return;
        }
        Project project = SMC.getProject();
        if (project == null) {
            respond(ex, 409, "{\"error\":\"no project handle available\"}");
            return;
        }
        Optional<File> currentFile = safe(project::getFile, Optional.<File>empty());
        if (currentFile == null || !currentFile.isPresent()) {
            respond(ex, 409, "{\"error\":\"project has no file path yet - use POST /project/saveAs?path=PATH\","
                    + "\"fileStatus\":\"NO_FILE\"}");
            return;
        }
        File target = currentFile.get();
        try {
            // Off-EDT: SMC.saveModel may take seconds/minutes for large projects and will
            // drive its own progress bar. Blocking the EDT here would freeze the UI.
            SMC.saveModel(target.toPath());
            String statusAfter = safe(() -> String.valueOf(project.getFileStatus()), "");
            String lastSave = safe(() -> {
                java.util.Optional<java.time.OffsetDateTime> t = project.getLastSaveTime();
                return t.isPresent() ? t.get().toString() : "";
            }, "");
            respond(ex, 200, "{\"saved\":true,\"path\":" + str(target.getAbsolutePath())
                    + ",\"size\":" + (target.exists() ? target.length() : -1)
                    + ",\"fileStatus\":" + str(statusAfter)
                    + ",\"lastSaveTime\":" + str(lastSave) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str("save failed: " + String.valueOf(e.getMessage())) + "}");
        }
    }

        private static void handleProjectSaveAs(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        if (SMC.isBusy()) {
            respond(ex, 409, "{\"error\":\"Solibri is busy\"}");
            return;
        }
        String pathParam = query(ex).getOrDefault("path", "");
        if (pathParam.isEmpty()) {
            respond(ex, 400, "{\"error\":\"path query param required\"}");
            return;
        }
        // Solibri projects are .smc - reject anything else explicitly so callers don't end
        // up with a 200 GB binary blob named project.txt.
        String lower = pathParam.toLowerCase();
        if (!(lower.endsWith(".smc") || lower.endsWith(".smcx"))) {
            respond(ex, 400, "{\"error\":\"path must end in .smc or .smcx\"}");
            return;
        }
        File target;
        try {
            target = new File(pathParam).getAbsoluteFile();
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"invalid path: " + String.valueOf(e.getMessage()) + "\"}");
            return;
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            respond(ex, 400, "{\"error\":\"parent directory does not exist: " + str(parent == null ? "" : parent.getAbsolutePath()).replace("\"", "") + "\"}");
            return;
        }
        Project project = SMC.getProject();
        try {
            // SMC.saveModel(Path) is the SDK's single save entrypoint; passing a NEW path
            // is the save-as primitive (verified via javap on com.solibri.smc.api.SMC).
            SMC.saveModel(target.toPath());
            String statusAfter = project == null ? "" : safe(() -> String.valueOf(project.getFileStatus()), "");
            String lastSave = project == null ? "" : safe(() -> {
                java.util.Optional<java.time.OffsetDateTime> t = project.getLastSaveTime();
                return t.isPresent() ? t.get().toString() : "";
            }, "");
            String newPath = "";
            if (project != null) {
                Optional<File> f = safe(project::getFile, Optional.<File>empty());
                if (f != null && f.isPresent()) newPath = f.get().getAbsolutePath();
            }
            respond(ex, 200, "{\"saved\":true,\"path\":" + str(target.getAbsolutePath())
                    + ",\"projectFile\":" + str(newPath)
                    + ",\"size\":" + (target.exists() ? target.length() : -1)
                    + ",\"fileStatus\":" + str(statusAfter)
                    + ",\"lastSaveTime\":" + str(lastSave) + "}");
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":" + str("saveAs failed: " + String.valueOf(e.getMessage())) + "}");
        }
    }

        private static void handleProjectInfo(HttpExchange ex) throws IOException {
        respond(ex, 200, onEdt(() -> {
            Model model = SMC.getModel();
            Project project = SMC.getProject();
            if (model == null && project == null) {
                return "{\"open\":false}";
            }
            StringBuilder b = new StringBuilder("{\"open\":true");
            b.append(",\"productName\":").append(str(safe(SMC::getProductName, "")));
            b.append(",\"productVersion\":").append(str(safe(SMC::getProductVersion, "")));
            b.append(",\"busy\":").append(safe(SMC::isBusy, Boolean.FALSE));
            if (model != null) {
                b.append(",\"name\":").append(str(safe(model::getName, "")));
            }
            if (project != null) {
                Optional<File> file = safe(project::getFile, Optional.<File>empty());
                if (file != null && file.isPresent()) {
                    File f = file.get();
                    b.append(",\"file\":").append(str(f.getAbsolutePath()));
                    b.append(",\"fileExists\":").append(f.exists());
                    b.append(",\"fileSize\":").append(f.exists() ? f.length() : -1);
                } else {
                    b.append(",\"file\":null");
                }
                String status = safe(() -> String.valueOf(project.getFileStatus()), "");
                b.append(",\"fileStatus\":").append(str(status));
                String lastSave = safe(() -> {
                    Optional<OffsetDateTime> t = project.getLastSaveTime();
                    return t.isPresent() ? t.get().toString() : "";
                }, "");
                b.append(",\"lastSaveTime\":").append(str(lastSave));
                String industry = safe(() -> String.valueOf(project.getIndustry()), "");
                b.append(",\"industry\":").append(str(industry));
            }
            return b.append("}").toString();
        }));
    }

        private static void handleProjectIndustry(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) {
            respond(ex, 409, "{\"error\":\"no project open\"}");
            return;
        }
        Project project = SMC.getProject();
        if (project == null) {
            respond(ex, 409, "{\"error\":\"no project handle available\"}");
            return;
        }
        String value = query(ex).getOrDefault("value", "").trim().toUpperCase();
        if (value.isEmpty()) {
            respond(ex, 400, "{\"error\":\"value query param required\",\"allowed\":"
                    + "[\"UNDEFINED\",\"ARCHITECTURE\",\"ENGINEERING\",\"CONSULTING\",\"CONSTRUCTION\",\"OTHER_AEC\"]}");
            return;
        }
        Project.Industry parsed;
        try {
            parsed = Project.Industry.valueOf(value);
        } catch (IllegalArgumentException iae) {
            respond(ex, 400, "{\"error\":\"unknown industry: " + value + "\",\"allowed\":"
                    + "[\"UNDEFINED\",\"ARCHITECTURE\",\"ENGINEERING\",\"CONSULTING\",\"CONSTRUCTION\",\"OTHER_AEC\"]}");
            return;
        }
        final Project.Industry target = parsed;
        String result = onEdt(() -> {
            String before = String.valueOf(project.getIndustry());
            project.setIndustry(target);
            String after = String.valueOf(project.getIndustry());
            return "{\"previous\":" + str(before) + ",\"industry\":" + str(after) + "}";
        });
        respond(ex, 200, result);
    }

    // ==== cluster: threed-camera-snapshots ====
    private static void handleThreedCamera(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase();
        if (method.equals("GET")) {
            respond(ex, 200, onEdt(() -> {
                if (SMC.getModel() == null) return "{\"open\":false}";
                ThreeD td = SMC.get3D();
                if (td == null) return "{\"error\":\"3D view unavailable\"}";
                Camera cam = td.getCamera();
                StringBuilder b = new StringBuilder("{");
                b.append("\"location\":").append(vec(cam.getLocation()));
                b.append(",\"direction\":").append(vec(cam.getDirection()));
                b.append(",\"up\":").append(vec(cam.getUpVector()));
                // Richer projection state (perspective vs ortho + fov/scale).
                try {
                    CameraState st = td.createCameraState();
                    if (st instanceof PerspectiveCameraState) {
                        b.append(",\"projection\":\"perspective\"");
                        b.append(",\"fieldOfView\":").append(((PerspectiveCameraState) st).getFieldOfView());
                    } else if (st instanceof OrthogonalCameraState) {
                        b.append(",\"projection\":\"orthogonal\"");
                        b.append(",\"viewToWorldScale\":").append(((OrthogonalCameraState) st).getViewToWorldScale());
                    } else if (st != null) {
                        b.append(",\"projection\":\"unknown\"");
                    }
                } catch (Exception ignore) {}
                return b.append("}").toString();
            }));
            return;
        }
        if (!method.equals("POST")) {
            respond(ex, 405, "{\"error\":\"use GET or POST\"}");
            return;
        }
        // POST: accept a JSON body describing the desired CameraState and apply it.
        if (SMC.getModel() == null) { respond(ex, 409, "{\"error\":\"no project open\"}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        double[] loc = parseVec(body, "location");
        double[] dir = parseVec(body, "direction");
        double[] up  = parseVec(body, "up");
        if (loc == null || dir == null || up == null) {
            respond(ex, 400, "{\"error\":\"body must contain location, direction and up as 3-number arrays\"}");
            return;
        }
        String projection = parseStringField(body, "projection");
        Double fov   = parseNumberField(body, "fieldOfView");
        Double scale = parseNumberField(body, "viewToWorldScale");
        respond(ex, 200, onEdt(() -> {
            ThreeD td = SMC.get3D();
            if (td == null) return "{\"error\":\"3D view unavailable\"}";
            Vector3d vLoc = Vector3d.create(loc[0], loc[1], loc[2]);
            Vector3d vDir = Vector3d.create(dir[0], dir[1], dir[2]);
            Vector3d vUp  = Vector3d.create(up[0],  up[1],  up[2]);
            CameraState desired;
            if ("orthogonal".equalsIgnoreCase(projection)) {
                double s = scale != null ? scale : 1.0;
                desired = OrthogonalCameraState.create(vLoc, vDir, vUp, s);
            } else {
                // Default to perspective; FOV defaults to a sane mid-value (radians) if omitted.
                double f = fov != null ? fov : Math.toRadians(45.0);
                desired = PerspectiveCameraState.create(vLoc, vDir, vUp, f);
            }
            td.setCameraState(desired);
            return "{\"applied\":true,\"projection\":" + str(
                    desired instanceof OrthogonalCameraState ? "orthogonal" : "perspective") + "}";
        }));
    }

    private static void handleThreedSnapshot(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) { respond(ex, 409, "{\"error\":\"no project open\"}"); return; }
        Map<String, String> q = query(ex);
        String filePath = q.getOrDefault("file", "");
        if (filePath.isEmpty()) {
            respond(ex, 400, "{\"error\":\"file query param required (PNG output path)\"}");
            return;
        }
        final File out = new File(filePath);
        if (out.getParentFile() != null && !out.getParentFile().exists()) {
            respond(ex, 400, "{\"error\":\"parent directory does not exist: "
                    + out.getParentFile().getAbsolutePath().replace("\\", "\\\\") + "\"}");
            return;
        }
        // width/height accepted for API symmetry; the SDK snapshot returns whatever the
        // current 3D viewport is, so we resize after capture if both dims are present.
        Integer wantW = null, wantH = null;
        try { if (q.containsKey("width"))  wantW = Integer.parseInt(q.get("width")); }  catch (NumberFormatException ignore) {}
        try { if (q.containsKey("height")) wantH = Integer.parseInt(q.get("height")); } catch (NumberFormatException ignore) {}
        final Integer fW = wantW, fH = wantH;
        // Snapshot must happen on the EDT - it reads the live viewport.
        String result = onEdt(() -> {
            ThreeD td = SMC.get3D();
            if (td == null) return "{\"error\":\"3D view unavailable\"}";
            Optional<BufferedImage> snap = td.get3DSnapshot();
            if (snap == null || !snap.isPresent()) return "{\"error\":\"snapshot unavailable (no active 3D view?)\"}";
            BufferedImage img = snap.get();
            BufferedImage toWrite = img;
            if (fW != null && fH != null && fW > 0 && fH > 0
                    && (fW != img.getWidth() || fH != img.getHeight())) {
                BufferedImage scaled = new BufferedImage(fW, fH, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, fW, fH, null);
                g.dispose();
                toWrite = scaled;
            }
            try {
                boolean ok = ImageIO.write(toWrite, "png", out);
                if (!ok) return "{\"error\":\"no PNG writer available\"}";
            } catch (IOException ioe) {
                return "{\"error\":" + str("write failed: " + ioe.getMessage()) + "}";
            }
            return "{\"saved\":true,\"path\":" + str(out.getAbsolutePath())
                    + ",\"width\":" + toWrite.getWidth()
                    + ",\"height\":" + toWrite.getHeight()
                    + ",\"sourceWidth\":" + img.getWidth()
                    + ",\"sourceHeight\":" + img.getHeight()
                    + ",\"size\":" + (out.exists() ? out.length() : -1) + "}";
        });
        respond(ex, 200, result);
    }

    private static void handleThreedFit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"use POST\"}");
            return;
        }
        if (SMC.getModel() == null) { respond(ex, 409, "{\"error\":\"no project open\"}"); return; }
        String target = query(ex).getOrDefault("target", "selection").toLowerCase();
        if (!target.equals("selection") && !target.equals("model")) {
            respond(ex, 400, "{\"error\":\"target must be 'selection' or 'model'\"}");
            return;
        }
        respond(ex, 200, onEdt(() -> {
            ThreeD td = SMC.get3D();
            if (td == null) return "{\"error\":\"3D view unavailable\"}";
            Camera cam = td.getCamera();
            Collection<Component> targets;
            String mode;
            if ("selection".equals(target)) {
                Set<Component> sel = SMC.getSelectionBasket().get();
                if (sel == null || sel.isEmpty()) {
                    return "{\"error\":\"selection basket is empty\",\"target\":\"selection\"}";
                }
                targets = sel;
                mode = "selection";
            } else {
                // Whole model: use the federated Model.getComponents(ACCEPT_ALL).
                targets = SMC.getModel().getComponents(ComponentFilter.ACCEPT_ALL);
                mode = "model";
                if (targets == null || targets.isEmpty()) {
                    return "{\"error\":\"model has no components\",\"target\":\"model\"}";
                }
            }
            cam.lookAt(targets);
            return "{\"fit\":true,\"target\":" + str(mode)
                    + ",\"components\":" + targets.size() + "}";
        }));
    }

    private static String vec(Vector3d v) {
        if (v == null) return "null";
        return "[" + v.getX() + "," + v.getY() + "," + v.getZ() + "]";
    }

    private static double[] parseVec(String body, String key) {
        if (body == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)\\s*\\]");
        Matcher m = p.matcher(body);
        if (!m.find()) return null;
        try {
            return new double[]{ Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)) };
        } catch (NumberFormatException nfe) { return null; }
    }

    private static String parseStringField(String body, String key) {
        if (body == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static Double parseNumberField(String body, String key) {
        if (body == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");
        Matcher m = p.matcher(body);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException nfe) { return null; }
    }


    private static String resultJson(Result r) {
        List<String> guids = new ArrayList<>();
        for (Component c : r.getInvolvedComponents()) guids.add(str(c.getGUID()));
        String sev = r.getSeverity() != null ? r.getSeverity().name() : "";
        String ruleName = "";
        try { ruleName = r.getRule() != null ? r.getRule().getName() : ""; } catch (Exception ignored) {}
        return "{\"name\":" + str(r.getName())
                + ",\"description\":" + str(r.getDescription())
                + ",\"severity\":" + str(sev)
                + ",\"rule\":" + str(ruleName)
                + ",\"uniqueKey\":" + str(r.getUniqueKey())
                + ",\"location\":" + str(r.getLocation().orElse(""))
                + ",\"components\":" + arr(guids) + "}";
    }

    // ---- helpers ---------------------------------------------------------

    private static boolean matches(String v, String value, String op) {
        if (v == null) v = "";
        switch (op) {
            case "exists":   return true;
            case "prefix":   return v.startsWith(value);
            case "contains": return v.contains(value);
            default:         return v.equals(value);
        }
    }

    /** Run an SDK read on the Swing EDT (Solibri model access is UI-thread bound). */
    private static String onEdt(Callable<String> body) {
        final String[] holder = new String[1];
        final Exception[] err = new Exception[1];
        try {
            Runnable r = () -> { try { holder[0] = body.call(); } catch (Exception e) { err[0] = e; } };
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            return "{\"error\":" + str(String.valueOf(e.getMessage())) + "}";
        }
        if (err[0] != null) return "{\"error\":" + str(String.valueOf(err[0].getMessage())) + "}";
        return holder[0];
    }

    private static <T> T safe(java.util.function.Supplier<T> s, T dflt) {
        try { return s.get(); } catch (Exception e) { return dflt; }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new java.util.HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            m.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
        }
        return m;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** JSON-escape and quote a string. */
    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    /** Join already-serialized JSON fragments into an array. */
    private static String arr(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    // ---- View SPI (only getUniqueId + initializePanel are abstract) ------

    @Override
    public String getUniqueId() {
        return "no.skiplum.solibri.superuser";
    }

    @Override
    public String getName() {
        return "Skiplum Superuser API";
    }

    @Override
    public boolean shouldBeLoaded() {
        return true;
    }

    @Override
    public void initializePanel(JPanel panel) {
        startOnce();
        panel.add(new JLabel("Skiplum Superuser API on http://localhost:" + PORT + BASE));
    }
}

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

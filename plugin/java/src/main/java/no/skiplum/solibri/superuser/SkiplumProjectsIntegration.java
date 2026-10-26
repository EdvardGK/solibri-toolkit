package no.skiplum.solibri.superuser;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import com.solibri.resource.Resource;
import com.solibri.resource.ResourceProvider;
import com.solibri.sae.util.FileTypeFilter;
import com.solibri.saf.core.ui.ResourceProviderUI;
import com.solibri.saf.core.ui.resourceprovider.Usage;
import com.solibri.product.requirement.license.LicenseRequirement;
import com.solibri.saf.plugins.remoteintegrationsplugin.RemoteIntegration;

/**
 * A Skiplum-owned tab in Solibri's Open Model browser, sitting next to Files / Recent /
 * SharePoint / ACC. A local-filesystem source rooted at the Skiplum client-projects tree.
 *
 * Registered via META-INF/services/com.solibri.saf.plugins.remoteintegrationsplugin.RemoteIntegration.
 * Selected files are emitted as ordinary {@code Files}-provider Resources, so Solibri opens
 * them exactly as from the built-in Files tab.
 */
public final class SkiplumProjectsIntegration implements RemoteIntegration {

    static final File ROOT = new File("C:\\workspace\\skiplum\\client-projects");

    /** Model file types shown when the dialog does not pass its own filters. */
    private static final FileTypeFilter MODEL_FILTER =
            new FileTypeFilter(new String[]{"ifc", "ifczip", "ifcxml", "smc", "smcx", "smv"}, "Model files");

    @Override public String getName() { return "Skiplum Projects"; }

    @Override public String getIntegrationKey() { return "skiplum-projects"; }

    /** Reuse the built-in Files provider so emitted Resources open through the normal path. */
    @Override public String getResourceProviderName() { return ResourceProvider.FILES_PROVIDER_NAME; }

    @Override public boolean isEnabled() { return ROOT.isDirectory(); }

    @Override public LicenseRequirement getLicenseRequirement() { return LicenseRequirement.FOR_ANY; }

    @Override
    public JPanel getPanel() {
        JPanel p = new JPanel(new BorderLayout());
        if (ROOT.isDirectory()) {
            p.add(new Browser(ROOT, new FileTypeFilter[]{MODEL_FILTER}).getComponent(), BorderLayout.CENTER);
        } else {
            p.add(new JLabel("  Folder not found: " + ROOT.getAbsolutePath()), BorderLayout.NORTH);
        }
        return p;
    }

    @Override public boolean isReadyToExit() { return true; }

    @Override
    public <T extends com.solibri.saf.core.PlugInInterface> void plugInChanged(
            com.solibri.saf.core.PluginStateChangedEvent<T> event) {
        // local source has no plugin-state dependencies
    }

    @Override
    public Optional<ResourceProviderUI> getResourceProviderUI(Usage usage, String providerName,
                                                              FileTypeFilter... filters) {
        if (!ROOT.isDirectory()) {
            return Optional.empty();
        }
        FileTypeFilter[] f = (filters == null || filters.length == 0)
                ? new FileTypeFilter[]{MODEL_FILTER} : filters;
        return Optional.of(new Browser(ROOT, f));
    }

    // ----------------------------------------------------------------------------------
    // Navigation: a Project dropdown (top-level project picker) + a clickable breadcrumb +
    // an Up button + a drill-in content list. Double-click a folder to enter it, double-click
    // a model to open it; breadcrumb segments and the Up button move back up. Never goes above
    // the client-projects root.
    // ----------------------------------------------------------------------------------
    static final class Browser implements ResourceProviderUI {
        private static final String ALL_PROJECTS = "— select project —";

        private final File root;
        private final FileTypeFilter[] filters;
        private File current;
        private boolean syncing = false;

        private final DefaultListModel<Entry> model = new DefaultListModel<>();
        private final JList<Entry> list = new JList<>(model);
        private final JComboBox<String> projectCombo = new JComboBox<>();
        private final JButton upButton = new JButton("↑ Up");
        private final JPanel crumbBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        private final JPanel panel = new JPanel(new BorderLayout());
        private final List<ActionListener> listeners = new ArrayList<>();

        Browser(File root, FileTypeFilter[] filters) {
            this.root = root;
            this.filters = filters;
            this.current = root;

            // --- Project dropdown: every top-level folder under client-projects ---
            projectCombo.addItem(ALL_PROJECTS);
            File[] projects = root.listFiles(File::isDirectory);
            if (projects != null) {
                Arrays.sort(projects, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File p : projects) projectCombo.addItem(p.getName());
            }
            projectCombo.addActionListener(e -> {
                if (syncing) return;
                int idx = projectCombo.getSelectedIndex();
                setCurrent(idx <= 0 ? root : new File(root, (String) projectCombo.getSelectedItem()));
            });

            upButton.addActionListener(e -> {
                File parent = current.getParentFile();
                if (parent != null && within(parent)) setCurrent(parent);
            });

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            controls.add(new JLabel("Project:"));
            controls.add(projectCombo);
            controls.add(upButton);

            crumbBar.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));

            JPanel header = new JPanel(new BorderLayout());
            header.add(controls, BorderLayout.NORTH);
            header.add(crumbBar, BorderLayout.SOUTH);

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new EntryRenderer());
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2) return;
                    int i = list.locationToIndex(e.getPoint());
                    if (i < 0 || !list.getCellBounds(i, i).contains(e.getPoint())) return;
                    activate(model.get(i), i);
                }
            });

            panel.add(header, BorderLayout.NORTH);
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            setCurrent(root);
        }

        /** Enter a folder, or open a file. */
        private void activate(Entry en, int index) {
            if (en.isParent) {
                File parent = current.getParentFile();
                if (parent != null && within(parent)) setCurrent(parent);
            } else if (en.file.isDirectory()) {
                setCurrent(en.file);
            } else {
                list.setSelectedIndex(index);
                fire(APPROVE_SELECTION);
            }
        }

        private boolean within(File dir) {
            try {
                String r = root.getCanonicalPath();
                String d = dir.getCanonicalPath();
                return d.equals(r) || d.startsWith(r + File.separator);
            } catch (Exception e) {
                return false;
            }
        }

        private void setCurrent(File dir) {
            if (dir == null || !dir.isDirectory() || !within(dir)) return;
            current = dir;
            rebuildList();
            rebuildCrumbs();
            syncControls();
        }

        private void rebuildList() {
            model.clear();
            if (!sameFile(current, root)) model.addElement(Entry.parent());
            File[] kids = current.listFiles();
            if (kids != null) {
                Arrays.sort(kids, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File k : kids) if (k.isDirectory()) model.addElement(new Entry(k));
                for (File k : kids) if (k.isFile() && accepts(k)) model.addElement(new Entry(k));
            }
        }

        /** Clickable breadcrumb: root → … → current, each segment jumps there. */
        private void rebuildCrumbs() {
            crumbBar.removeAll();
            List<File> chain = new ArrayList<>();
            for (File f = current; f != null && within(f); f = f.getParentFile()) chain.add(0, f);
            for (int i = 0; i < chain.size(); i++) {
                File f = chain.get(i);
                String label = sameFile(f, root) ? "Skiplum Projects" : f.getName();
                if (i > 0) crumbBar.add(dim(" › "));
                if (i == chain.size() - 1) {
                    JLabel here = new JLabel(label);
                    crumbBar.add(here);
                } else {
                    crumbBar.add(crumbLink(label, f));
                }
            }
            crumbBar.revalidate();
            crumbBar.repaint();
        }

        private void syncControls() {
            syncing = true;
            // reflect which project we're inside in the dropdown
            String proj = ALL_PROJECTS;
            try {
                String r = root.getCanonicalPath();
                String d = current.getCanonicalPath();
                if (d.length() > r.length()) {
                    String rel = d.substring(r.length() + 1);
                    int sep = rel.indexOf(File.separatorChar);
                    proj = sep < 0 ? rel : rel.substring(0, sep);
                }
            } catch (Exception ignore) { }
            projectCombo.setSelectedItem(proj);
            syncing = false;
            upButton.setEnabled(!sameFile(current, root));
        }

        private JButton crumbLink(String text, File target) {
            JButton b = new JButton(text);
            b.setBorderPainted(false);
            b.setContentAreaFilled(false);
            b.setMargin(new java.awt.Insets(0, 0, 0, 0));
            b.setForeground(new Color(0x2E6FB7));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> setCurrent(target));
            return b;
        }

        private static JLabel dim(String s) {
            JLabel l = new JLabel(s);
            l.setForeground(Color.GRAY);
            return l;
        }

        /** Known model/BIM extensions we always show (plain string check — does not rely on
         *  Solibri's FileTypeFilter.acceptName, whose matching proved unreliable here). */
        private static final java.util.Set<String> MODEL_EXTS = java.util.Set.of(
                "ifc", "ifczip", "ifcxml", "smc", "smcx", "smv");

        private boolean accepts(File f) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && MODEL_EXTS.contains(name.substring(dot + 1).toLowerCase())) return true;
            // also honour any filter the dialog supplied (standard FileFilter.accept(File))
            if (filters != null) {
                for (FileTypeFilter flt : filters) {
                    try { if (flt != null && flt.accept(f)) return true; } catch (Exception ignore) { }
                }
            }
            return false;
        }

        private static boolean sameFile(File a, File b) {
            try { return a.getCanonicalPath().equals(b.getCanonicalPath()); }
            catch (Exception e) { return a.getAbsolutePath().equals(b.getAbsolutePath()); }
        }

        private void fire(String command) {
            ActionEvent ev = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, command);
            for (ActionListener l : new ArrayList<>(listeners)) l.actionPerformed(ev);
        }

        @Override public String getName() { return "Skiplum Projects"; }

        @Override public Component getComponent() { return panel; }

        @Override
        public void setMultiSelectionEnabled(boolean enabled) {
            list.setSelectionMode(enabled
                    ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                    : ListSelectionModel.SINGLE_SELECTION);
        }

        @Override public void addActionListener(ActionListener l) { if (l != null) listeners.add(l); }

        @Override
        public List<Resource> getSelectedResources() {
            List<Resource> out = new ArrayList<>();
            for (Entry en : list.getSelectedValuesList()) {
                if (en != null && !en.isParent && en.file.isFile()) out.add(toResource(en.file));
            }
            return out;
        }

        /** Build a Files-provider Resource mirroring how recents serialize a local model:
         *  location is the FULL file path (incl. filename), not the parent directory. */
        private static Resource toResource(File f) {
            return new Resource(f.getName(), f.getAbsolutePath(), "Model", f.toURI(),
                    ResourceProvider.FILES_PROVIDER_NAME);
        }

        @Override
        public void handleTabSelected(javax.swing.JRootPane rootPane) {
            setCurrent(current); // refresh listing when the tab is shown
        }
    }

    /** One row: a real file/folder, or the synthetic ".." parent entry. */
    static final class Entry {
        final File file;        // null for the parent entry
        final boolean isParent;
        private Entry(File f, boolean parent) { this.file = f; this.isParent = parent; }
        Entry(File f) { this(f, false); }
        static Entry parent() { return new Entry(null, true); }
        boolean isDir() { return isParent || (file != null && file.isDirectory()); }
        @Override public String toString() { return isParent ? ".." : file.getName(); }
    }

    /** Folder/file icons so rows read like a normal file list. */
    static final class EntryRenderer extends DefaultListCellRenderer {
        private static final Icon DIR = UIManager.getIcon("FileView.directoryIcon");
        private static final Icon FILE = UIManager.getIcon("FileView.fileIcon");
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, value, index, sel, focus);
            if (value instanceof Entry) {
                setIcon(((Entry) value).isDir() ? DIR : FILE);
            }
            return this;
        }
    }
}

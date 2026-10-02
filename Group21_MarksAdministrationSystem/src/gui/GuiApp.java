package gui;

import model.*;
import model.Module;
import service.*;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Group 21 - Marks Administration System
 * Swing GUI mode. Re-uses the exact same service layer as the CLI
 * (AuthService, MarksService, CsvService), so behaviour is identical:
 * Lecturers see only their own modules, the HoD sees everything.
 *
 * All database / file work runs on SwingWorker threads so the window never freezes.
 */
public class GuiApp {

    private static final AuthService auth  = new AuthService();
    private static final MarksService marks = new MarksService();
    private static final CsvService csv     = new CsvService();

    private static final Color BRAND   = new Color(0x1F3A5F);
    private static final Color FLAG_BG = new Color(0xFFE3E3);

    // ------------------------------------------------------------------ entry point
    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new LoginFrame().setVisible(true);
        });
    }

    // ------------------------------------------------------------------ helpers
    /** Run a job off the Event Dispatch Thread, then deliver the result back on it. */
    private static <T> void async(Callable<T> job, Consumer<T> onOk, Consumer<Throwable> onFail) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return job.call(); }
            @Override protected void done() {
                try { onOk.accept(get()); }
                catch (ExecutionException e) { onFail.accept(e.getCause() == null ? e : e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }.execute();
    }

    private static String messageOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return (m == null || m.isBlank()) ? root.getClass().getSimpleName() : m;
    }

    private static void error(Component parent, String title, Throwable t) {
        JOptionPane.showMessageDialog(parent, messageOf(t), title, JOptionPane.ERROR_MESSAGE);
    }

    private static void showText(Component parent, String title, String text) {
        JTextArea area = new JTextArea(text, 18, 80);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(parent, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }

    private static JTable readOnlyTable(String... columns) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable t = new JTable(model);
        t.setRowHeight(24);
        t.setFillsViewportHeight(true);
        t.setAutoCreateRowSorter(true);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    private static void fill(JTable t, List<String[]> rows) {
        DefaultTableModel m = (DefaultTableModel) t.getModel();
        m.setRowCount(0);
        for (String[] r : rows) m.addRow(r);
    }

    // =================================================================== LOGIN
    private static class LoginFrame extends JFrame {
        private final JTextField userField = new JTextField(18);
        private final JPasswordField passField = new JPasswordField(18);
        private final JButton loginBtn = new JButton("Login");
        private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
        private int attempts = 0;

        LoginFrame() {
            super("Marks Administration System - Login");
            setDefaultCloseOperation(EXIT_ON_CLOSE);

            JLabel title = new JLabel("CPUT Marks Administration System", SwingConstants.CENTER);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
            title.setForeground(Color.WHITE);
            JLabel sub = new JLabel("Group 21", SwingConstants.CENTER);
            sub.setForeground(new Color(0xCFD8E6));
            JPanel banner = new JPanel(new GridLayout(2, 1, 0, 2));
            banner.setBackground(BRAND);
            banner.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            banner.add(title);
            banner.add(sub);

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(20, 30, 10, 30));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(6, 6, 6, 6);
            g.anchor = GridBagConstraints.WEST;
            g.gridx = 0; g.gridy = 0; form.add(new JLabel("Username:"), g);
            g.gridx = 1;              form.add(userField, g);
            g.gridx = 0; g.gridy = 1; form.add(new JLabel("Password:"), g);
            g.gridx = 1;              form.add(passField, g);
            g.gridx = 0; g.gridy = 2; g.gridwidth = 2; g.anchor = GridBagConstraints.CENTER;
            form.add(loginBtn, g);
            g.gridy = 3; status.setForeground(new Color(0xB00020));
            form.add(status, g);

            add(banner, BorderLayout.NORTH);
            add(form, BorderLayout.CENTER);
            getRootPane().setDefaultButton(loginBtn);
            loginBtn.addActionListener(e -> doLogin());

            // make sure default accounts exist (same as CLI start-up)
            loginBtn.setEnabled(false);
            status.setForeground(Color.DARK_GRAY);
            status.setText("Connecting to database...");
            async(() -> { auth.seedDefaults(); return Boolean.TRUE; },
                  ok -> { loginBtn.setEnabled(true); status.setText(" "); status.setForeground(new Color(0xB00020)); },
                  err -> {
                      JOptionPane.showMessageDialog(this,
                          "Could not connect to the database:\n" + messageOf(err)
                          + "\n\nCheck that MySQL is running and schema.sql has been executed.",
                          "Database error", JOptionPane.ERROR_MESSAGE);
                      csv.shutdown();
                      System.exit(1);
                  });

            pack();
            setMinimumSize(getSize());
            setLocationRelativeTo(null);
            userField.requestFocusInWindow();
        }

        private void doLogin() {
            String u = userField.getText().trim();
            String p = new String(passField.getPassword());
            if (u.isEmpty() || p.isEmpty()) { status.setText("Enter username and password."); return; }
            loginBtn.setEnabled(false);
            status.setText(" ");
            async(() -> auth.login(u, p),
                  found -> {
                      if (found.isPresent()) {
                          new Dashboard(found.get()).setVisible(true);
                          dispose();
                          return;
                      }
                      attempts++;
                      passField.setText("");
                      if (attempts >= 3) {
                          JOptionPane.showMessageDialog(this, "Login failed 3 times. The application will close.",
                                  "Login failed", JOptionPane.ERROR_MESSAGE);
                          csv.shutdown();
                          System.exit(0);
                      }
                      status.setText("Invalid credentials. Attempts left: " + (3 - attempts));
                      loginBtn.setEnabled(true);
                  },
                  err -> { status.setText("Login error: " + messageOf(err)); loginBtn.setEnabled(true); });
        }
    }

    // =================================================================== DASHBOARD
    private static class Dashboard extends JFrame {
        private final User user;
        private final JTable moduleTable = readOnlyTable("ID", "Code", "Module", "Lecturer");
        private final JPanel detailHolder = new JPanel(new CardLayout());
        private final JLabel emptyLabel = new JLabel("Select a module on the left to open it.", SwingConstants.CENTER);
        private final Map<Integer, ModulePanel> panels = new HashMap<>();
        private List<Module> modules = new ArrayList<>();

        Dashboard(User user) {
            super(user.isHod() ? "HoD Dashboard - All Modules" : "Lecturer Dashboard");
            this.user = user;
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { csv.shutdown(); System.exit(0); }
            });

            add(buildHeader(), BorderLayout.NORTH);

            JPanel left = new JPanel(new BorderLayout(0, 8));
            left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
            left.add(new JLabel(user.isHod() ? "All modules" : "My modules"), BorderLayout.NORTH);
            left.add(new JScrollPane(moduleTable), BorderLayout.CENTER);
            moduleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            moduleTable.removeColumn(moduleTable.getColumnModel().getColumn(0));   // hide ID column
            if (user.isHod()) left.add(buildHodButtons(), BorderLayout.SOUTH);

            detailHolder.add(emptyLabel, "empty");
            ((CardLayout) detailHolder.getLayout()).show(detailHolder, "empty");

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detailHolder);
            split.setDividerLocation(320);
            add(split, BorderLayout.CENTER);

            moduleTable.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) openSelected();
            });

            setSize(1150, 680);
            setLocationRelativeTo(null);
            loadModules();
        }

        private JComponent buildHeader() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(BRAND);
            bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            JLabel who = new JLabel("Welcome, " + user.getFullName() + "  |  " + (user.isHod() ? "Head of Department" : "Lecturer"));
            who.setForeground(Color.WHITE);
            who.setFont(who.getFont().deriveFont(Font.BOLD, 14f));
            JButton logout = new JButton("Logout");
            logout.addActionListener(e -> { dispose(); new LoginFrame().setVisible(true); });
            bar.add(who, BorderLayout.WEST);
            bar.add(logout, BorderLayout.EAST);
            return bar;
        }

        private JComponent buildHodButtons() {
            JButton missing = new JButton("Missing entries (all modules)");
            JButton summary = new JButton("Summary report");
            JButton refresh = new JButton("Refresh modules");
            missing.addActionListener(e -> async(() -> {
                        List<String> all = new ArrayList<>();
                        for (Module m : marks.listModules(null)) all.addAll(marks.missingEntries(m.getId()));
                        return all;
                    },
                    all -> showText(this, "Missing entries - all modules",
                            all.isEmpty() ? "No missing entries across all modules."
                                          : String.join("\n", all.stream().map(s -> "[FLAG] " + s).toList())),
                    err -> error(this, "Report failed", err)));
            summary.addActionListener(e -> async(marks::hodSummary,
                    lines -> showText(this, "HoD summary report", String.join("\n", lines)),
                    err -> error(this, "Report failed", err)));
            refresh.addActionListener(e -> loadModules());
            JPanel p = new JPanel(new GridLayout(3, 1, 0, 6));
            p.add(missing); p.add(summary); p.add(refresh);
            return p;
        }

        private void loadModules() {
            async(() -> marks.listModules(user.isHod() ? null : user),
                  list -> {
                      modules = list;
                      DefaultTableModel m = (DefaultTableModel) moduleTable.getModel();
                      m.setRowCount(0);
                      for (Module mod : list)
                          m.addRow(new Object[]{ mod.getId(), mod.getCode(), mod.getName(), mod.getLecturerName() });
                  },
                  err -> error(this, "Could not load modules", err));
        }

        private void openSelected() {
            int viewRow = moduleTable.getSelectedRow();
            if (viewRow < 0) return;
            int row = moduleTable.convertRowIndexToModel(viewRow);
            Module m = modules.get(row);
            // same access rule as the CLI
            if (m.getLecturerId() != user.getId() && !user.isHod()) {
                JOptionPane.showMessageDialog(this, "Module not found or access denied.");
                return;
            }
            String key = "m" + m.getId();
            ModulePanel panel = panels.get(m.getId());
            if (panel == null) {
                panel = new ModulePanel(this, m, user);
                panels.put(m.getId(), panel);
                detailHolder.add(panel, key);
            }
            ((CardLayout) detailHolder.getLayout()).show(detailHolder, key);
            panel.refresh();
        }
    }

    // =================================================================== MODULE PANEL
    private record Sheet(List<String[]> rows, List<Assessment> assessments, double remaining,
                         Map<String, Map<Integer, Double>> raw) { }

    private static class ModulePanel extends JPanel {
        private final Component owner;
        private final Module module;
        private final User user;
        private final JTable table = readOnlyTable("Student No", "Name", "Weighted %", "Grade", "Flags");
        private static final String MISSING = "MISSING";
        private final JLabel assessmentsLabel = new JLabel(" ");
        private final JLabel status = new JLabel(" ");
        private final JProgressBar busyBar = new JProgressBar();

        ModulePanel(Component owner, Module module, User user) {
            super(new BorderLayout(0, 8));
            this.owner = owner; this.module = module; this.user = user;
            setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));

            JLabel title = new JLabel(module.getCode() + " : " + module.getName() + "   (Lecturer: " + module.getLecturerName() + ")");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));

            JPanel top = new JPanel(new BorderLayout(0, 4));
            top.add(title, BorderLayout.NORTH);
            top.add(assessmentsLabel, BorderLayout.CENTER);
            top.add(buildToolbar(), BorderLayout.SOUTH);
            add(top, BorderLayout.NORTH);

            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                                         boolean focus, int row, int col) {
                    Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                    if (!sel) {
                        int last = t.getColumnCount() - 1;
                        Object flag = t.getValueAt(row, last);
                        boolean flagged = flag != null && !flag.toString().isBlank();
                        boolean missingCell = MISSING.equals(v);
                        c.setBackground(missingCell ? new Color(0xFFB3B3)
                                      : flagged     ? FLAG_BG : t.getBackground());
                    }
                    return c;
                }
            });
            add(new JScrollPane(table), BorderLayout.CENTER);

            busyBar.setIndeterminate(true);
            busyBar.setVisible(false);
            JPanel bottom = new JPanel(new BorderLayout(8, 0));
            bottom.add(status, BorderLayout.CENTER);
            bottom.add(busyBar, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);
        }

        private JComponent buildToolbar() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            p.add(button("Add student",    e -> addStudent()));
            p.add(button("Add assessment", e -> addAssessment()));
            p.add(button("Import CSV",     e -> importCsv()));
            p.add(button("Export CSV",     e -> exportCsv()));
            p.add(button("Missing entries", e -> missingReport()));
            p.add(button("Refresh",        e -> refresh()));
            return p;
        }

        private JButton button(String text, ActionListener l) {
            JButton b = new JButton(text);
            b.addActionListener(l);
            return b;
        }

        private void busy(boolean on, String msg) {
            busyBar.setVisible(on);
            status.setText(msg);
        }

        /** Reload marksheet, assessment summary and remaining weight. */
        void refresh() {
            busy(true, "Loading...");
            async(() -> new Sheet(marks.marksheet(module.getId()),
                                  marks.assessments(module.getId()),
                                  marks.remainingWeight(module.getId()),
                                  marks.rawMarks(module.getId())),
                  s -> {
                      fillMarksheet(s);
                      StringBuilder sb = new StringBuilder("<html><b>Assessments:</b> ");
                      if (s.assessments().isEmpty()) sb.append("none yet");
                      for (int i = 0; i < s.assessments().size(); i++) {
                          Assessment a = s.assessments().get(i);
                          if (i > 0) sb.append(" &nbsp;|&nbsp; ");
                          sb.append(a.getName()).append(" (/").append(trim(a.getMaxMarks()))
                            .append(", ").append(trim(a.getWeightPct())).append("%)");
                      }
                      sb.append(" &nbsp;&nbsp;<b>Weight remaining:</b> ").append(trim(s.remaining())).append("%</html>");
                      assessmentsLabel.setText(sb.toString());
                      long flagged = s.rows().stream().filter(r -> !r[4].isBlank()).count();
                      busy(false, s.rows().size() + " students, " + flagged + " flagged for missing entries.");
                  },
                  err -> { busy(false, " "); error(owner, "Could not load module", err); });
        }

        /** Build the full table: Student No | Name | one column per assessment | Weighted % | Grade | Flags. */
        private void fillMarksheet(Sheet s) {
            List<String> cols = new ArrayList<>(List.of("Student No", "Name"));
            for (Assessment a : s.assessments())
                cols.add(a.getName() + " (/" + trim(a.getMaxMarks()) + ", " + trim(a.getWeightPct()) + "%)");
            cols.addAll(List.of("Weighted %", "Grade", "Flags"));

            DefaultTableModel m = (DefaultTableModel) table.getModel();
            m.setRowCount(0);
            m.setColumnIdentifiers(cols.toArray());
            for (String[] r : s.rows()) {              // r = [studentNo, name, weighted, grade, flags]
                List<String> row = new ArrayList<>();
                row.add(r[0]);
                row.add(r[1]);
                Map<Integer, Double> mine = s.raw().getOrDefault(r[0], Map.of());
                for (Assessment a : s.assessments()) {
                    Double mark = mine.get(a.getId());
                    row.add(mark == null ? MISSING : markText(mark));
                }
                row.add(r[2]);
                row.add(r[3]);
                row.add(r[4]);
                m.addRow(row.toArray());
            }
            // sensible column widths
            TableColumnModel cm = table.getColumnModel();
            if (cm.getColumnCount() >= 2) {
                cm.getColumn(0).setPreferredWidth(100);
                cm.getColumn(1).setPreferredWidth(180);
            }
            table.setAutoResizeMode(cols.size() > 7 ? JTable.AUTO_RESIZE_OFF : JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        }

        private static String markText(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.2f", d);
        }

        private static String trim(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.1f", d);
        }

        private void addStudent() {
            JTextField num = new JTextField(14);
            JTextField name = new JTextField(20);
            JPanel p = new JPanel(new GridLayout(2, 2, 6, 6));
            p.add(new JLabel("Student number:")); p.add(num);
            p.add(new JLabel("Full name:"));      p.add(name);
            if (JOptionPane.showConfirmDialog(owner, p, "Add student", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String n = num.getText().trim().toUpperCase();
            String f = name.getText().trim();
            if (n.isEmpty() || f.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Student number and full name are required.");
                return;
            }
            busy(true, "Adding student...");
            async(() -> { marks.addStudentAndEnrol(module.getId(), n, f, user.getId()); return Boolean.TRUE; },
                  ok -> { refresh(); status.setText("Student added and enrolled."); },
                  err -> { busy(false, " "); error(owner, "Could not add student", err); });
        }

        private void addAssessment() {
            JTextField name = new JTextField(16);
            JTextField max = new JTextField(8);
            JTextField weight = new JTextField(8);
            JPanel p = new JPanel(new GridLayout(3, 2, 6, 6));
            p.add(new JLabel("Name (e.g. Test 2):")); p.add(name);
            p.add(new JLabel("Max marks:"));          p.add(max);
            p.add(new JLabel("Weight %:"));           p.add(weight);
            if (JOptionPane.showConfirmDialog(owner, p, "Add assessment", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            double mx, w;
            try {
                mx = Double.parseDouble(max.getText().trim());
                w  = Double.parseDouble(weight.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(owner, "Max marks and weight must be numbers.");
                return;
            }
            String n = name.getText().trim();
            busy(true, "Adding assessment...");
            async(() -> { marks.addAssessment(module.getId(), n, mx, w, user.getId()); return Boolean.TRUE; },
                  ok -> { refresh(); status.setText("Assessment added."); },
                  err -> { busy(false, " "); error(owner, "Could not add assessment", err); });
        }

        private void importCsv() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose CSV file to import");
            if (fc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
            Path file = fc.getSelectedFile().toPath();
            busy(true, "Importing " + file.getFileName() + " ...");
            async(() -> csv.importAsync(module.getId(), file, user.getId()).get(),
                  (ImportResult r) -> {
                      StringBuilder sb = new StringBuilder();
                      sb.append(String.format("%d marks updated, %d new students enrolled, %d cells skipped.%n",
                              r.updated, r.created, r.skipped));
                      if (!r.warnings.isEmpty()) {
                          sb.append("\nWarnings:\n");
                          r.warnings.forEach(w -> sb.append("  ! ").append(w).append('\n'));
                      }
                      refresh();
                      showText(owner, "Import complete", sb.toString());
                  },
                  err -> { busy(false, " "); error(owner, "Import failed", err); });
        }

        private void exportCsv() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Export marks to CSV");
            fc.setSelectedFile(new java.io.File(module.getCode() + "_marks.csv"));
            if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
            Path target = fc.getSelectedFile().toPath();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(owner,
                    target.getFileName() + " already exists. Overwrite?", "Confirm overwrite",
                    JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            busy(true, "Exporting...");
            async(() -> csv.exportAsync(module.getId(), target, user.getId()).get(),
                  done -> {
                      busy(false, "Exported to " + target.toAbsolutePath());
                      JOptionPane.showMessageDialog(owner, "Exported to:\n" + target.toAbsolutePath());
                  },
                  err -> { busy(false, " "); error(owner, "Export failed", err); });
        }

        private void missingReport() {
            busy(true, "Checking for missing entries...");
            async(() -> marks.missingEntries(module.getId()),
                  list -> {
                      busy(false, list.size() + " missing entries.");
                      showText(owner, "Missing entries - " + module.getCode(),
                              list.isEmpty() ? "No missing entries - well done."
                                             : String.join("\n", list.stream().map(s -> "[FLAG] " + s).toList()));
                  },
                  err -> { busy(false, " "); error(owner, "Report failed", err); });
        }
    }
}

import java.nio.file.*;
import java.util.*;
import model.*;
import model.Module;
import service.*;

/**
 * Group 21 - Marks Administration System
 * Console (CLI) mode. Role-based menus: Lecturer (own modules) vs HoD (all modules).
 */
public class CliApp {
    private static Scanner sc;
    private static User currentUser;
    private static final AuthService auth = new AuthService();
    private static final MarksService marks = new MarksService();
    private static final CsvService csv = new CsvService();

    public static void run(Scanner in) {
        sc = in;
        System.out.println("===============================================");
        System.out.println("  CPUT Marks Administration System - Group 21");
        System.out.println("===============================================");
        auth.seedDefaults();
        if (!login()) return;

        boolean running = true;
        while (running) {
            if (currentUser.isHod()) running = hodMenu();
            else                     running = lecturerMenu();
        }
        csv.shutdown();
        System.out.println("Goodbye.");
    }

    private static boolean login() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            System.out.print("Username: ");
            String u = sc.nextLine().trim();
            System.out.print("Password: ");
            String p = sc.nextLine();
            Optional<User> found = auth.login(u, p);
            if (found.isPresent()) {
                currentUser = found.get();
                System.out.printf("Welcome %s (%s)%n", currentUser.getFullName(), currentUser.getRole());
                return true;
            }
            System.out.println("Invalid credentials. Attempts left: " + (3 - attempt));
        }
        System.out.println("Login failed.");
        return false;
    }

    private static boolean lecturerMenu() {
        System.out.println("\n--- LECTURER DASHBOARD (" + currentUser.getFullName() + ") ---");
        System.out.println("1. My modules        2. Open module        0. Logout");
        int c = readInt("Choice: ");
        switch (c) {
            case 1 -> listModules(false);
            case 2 -> openModule(false);
            case 0 -> { return false; }
            default -> System.out.println("Invalid choice.");
        }
        return true;
    }

    private static boolean hodMenu() {
        System.out.println("\n=== HoD DASHBOARD (ALL MODULES) ===");
        System.out.println("1. All modules summary      2. Open module (any)");
        System.out.println("3. Missing entries (all)    4. Summary report");
        System.out.println("0. Logout");
        int c = readInt("Choice: ");
        switch (c) {
            case 1 -> listModules(true);
            case 2 -> openModule(true);
            case 3 -> reportMissingAll();
            case 4 -> printSummary();
            case 0 -> { return false; }
            default -> System.out.println("Invalid choice.");
        }
        return true;
    }

    private static void listModules(boolean all) {
        List<Module> mods = marks.listModules(all ? null : currentUser);
        if (mods.isEmpty()) { System.out.println("No modules found."); return; }
        System.out.printf("%-6s %-12s %-35s %-20s%n", "ID", "Code", "Name", "Lecturer");
        for (Module m : mods)
            System.out.printf("%-6d %-12s %-35s %-20s%n",
                    m.getId(), m.getCode(), m.getName(), m.getLecturerName());
    }

    private static void openModule(boolean hodCanPickAny) {
        listModules(hodCanPickAny);
        int id = readInt("Module ID: ");
        Optional<Module> mod = marks.getModule(id);
        if (mod.isEmpty() || (mod.get().getLecturerId() != currentUser.getId() && !currentUser.isHod())) {
            System.out.println("Module not found or access denied.");
            return;
        }
        moduleMenu(mod.get());
    }

    private static void moduleMenu(Module m) {
        boolean back = false;
        while (!back) {
            System.out.println("\n--- MODULE " + m.getCode() + " : " + m.getName() + " ---");
            System.out.println("1. View marksheet (totals & grades)");
            System.out.println("2. Add student / enrol");
            System.out.println("3. Add assessment (test + weight)");
            System.out.println("4. Import marks from CSV");
            System.out.println("5. Export marks to CSV");
            System.out.println("6. Missing entries report");
            System.out.println("0. Back");
            switch (readInt("Choice: ")) {
                case 1 -> printMarksheet(m);
                case 2 -> addStudentFlow(m);
                case 3 -> addAssessmentFlow(m);
                case 4 -> importFlow(m);
                case 5 -> exportFlow(m);
                case 6 -> reportMissing(m);
                case 0 -> back = true;
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static void printMarksheet(Module m) {
        List<String[]> rows = marks.marksheet(m.getId());
        List<Assessment> asmt = marks.assessments(m.getId());
        System.out.printf("%-12s %-25s", "StudentNo", "Name");
        for (Assessment a : asmt) System.out.printf(" %-14s", a.getName() + "(" + (int) a.getWeightPct() + "%)");
        System.out.printf(" %-10s %-6s %s%n", "Weighted%", "Grade", "Flags");
        for (String[] r : rows)
            System.out.printf("%-12s %-25s %-10s %-6s %s%n", r[0], r[1], r[2], r[3], r[4]);
    }

    private static void addStudentFlow(Module m) {
        System.out.print("Student number: ");
        String num = sc.nextLine().trim().toUpperCase();
        System.out.print("Full name: ");
        String name = sc.nextLine().trim();
        try {
            marks.addStudentAndEnrol(m.getId(), num, name, currentUser.getId());
            System.out.println("Student added and enrolled.");
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void addAssessmentFlow(Module m) {
        System.out.print("Assessment name (e.g. Test 2): ");
        String name = sc.nextLine().trim();
        double max = readDouble("Max marks: ");
        double w   = readDouble("Weight % (remaining: " + String.format("%.1f", marks.remainingWeight(m.getId())) + "): ");
        try {
            marks.addAssessment(m.getId(), name, max, w, currentUser.getId());
            System.out.println("Assessment added.");
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void importFlow(Module m) {
        System.out.print("CSV file path: ");
        Path p = Paths.get(sc.nextLine().trim());
        if (!Files.exists(p)) { System.out.println("File not found."); return; }
        System.out.print("Importing on worker thread");
        try {
            var future = csv.importAsync(m.getId(), p, currentUser.getId());
            while (!future.isDone()) { System.out.print("."); Thread.sleep(300); }
            ImportResult r = future.get();
            System.out.printf("%nDone: %d marks updated, %d new students enrolled, %d cells skipped.%n",
                    r.updated, r.created, r.skipped);
            r.warnings.forEach(w -> System.out.println("  ! " + w));
        } catch (Exception e) {
            System.out.println("Import failed: " + e.getMessage());
        }
    }

    private static void exportFlow(Module m) {
        System.out.print("Output file path: ");
        Path p = Paths.get(sc.nextLine().trim());
        try {
            var future = csv.exportAsync(m.getId(), p, currentUser.getId());
            while (!future.isDone()) { System.out.print("."); Thread.sleep(250); }
            future.get();
            System.out.println("\nExported to " + p.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("Export failed: " + e.getMessage());
        }
    }

    private static void reportMissing(Module m) {
        List<String> missing = marks.missingEntries(m.getId());
        if (missing.isEmpty()) { System.out.println("No missing entries - well done."); return; }
        System.out.println("MISSING ENTRIES in " + m.getCode() + ":");
        missing.forEach(s -> System.out.println("  [FLAG] " + s));
    }

    private static void reportMissingAll() {
        List<String> all = new ArrayList<>();
        for (Module m : marks.listModules(null)) all.addAll(marks.missingEntries(m.getId()));
        if (all.isEmpty()) { System.out.println("No missing entries across all modules."); return; }
        all.forEach(s -> System.out.println("  [FLAG] " + s));
    }

    private static void printSummary() {
        for (String line : marks.hodSummary()) System.out.println(line);
    }

    private static int readInt(String prompt) {
        System.out.print(prompt);
        while (true) {
            try { return Integer.parseInt(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.print("Enter a number: "); }
        }
    }

    private static double readDouble(String prompt) {
        System.out.print(prompt);
        while (true) {
            try { return Double.parseDouble(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.print("Enter a number: "); }
        }
    }
}

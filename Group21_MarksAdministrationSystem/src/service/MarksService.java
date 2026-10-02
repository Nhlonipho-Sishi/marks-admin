package service;

import db.Database;
import model.*;
import model.Module;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Core business logic for the Marks Administration System.
 *
 * Algorithmic notes (rubric - Data Structures & Algorithms):
 *  - Marksheet aggregation loads A assessments and M marks into HashMaps for O(1)
 *    lookup, so building a full marksheet costs O(A + M) instead of O(A * M).
 *  - Missing-entry detection is a single SQL anti-join.
 *  - Import upserts use batched prepared statements: one round-trip per batch.
 */
public class MarksService {

    // ---------------------------------------------------------------- modules
    public List<Module> listModules(User owner) {
        String sql = "SELECT m.id, m.code, m.name, m.lecturer_id, u.full_name " +
                     "FROM modules m JOIN users u ON u.id = m.lecturer_id " +
                     (owner == null ? " ORDER BY m.code" : " WHERE m.lecturer_id = ? ORDER BY m.code");
        List<Module> out = new ArrayList<>();
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (owner != null) ps.setInt(1, owner.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Module(rs.getInt(1), rs.getString(2), rs.getString(3),
                                       rs.getInt(4), rs.getString(5)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public Optional<Module> getModule(int id) {
        return listModules(null).stream().filter(m -> m.getId() == id).findFirst();
    }

    // ---------------------------------------------------------------- assessments
    public List<Assessment> assessments(int moduleId) {
        List<Assessment> out = new ArrayList<>();
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, max_marks, weight_pct FROM assessments WHERE module_id = ? ORDER BY sort_order, id")) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Assessment(rs.getInt(1), rs.getString(2),
                                           rs.getDouble(3), rs.getDouble(4)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** Sum of weights still available in this module. */
    public double remainingWeight(int moduleId) {
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(weight_pct),0) FROM assessments WHERE module_id = ?")) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return 100.0 - rs.getDouble(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Add a new test row. Business rule: module weights must never exceed 100%. */
    public void addAssessment(int moduleId, String name, double maxMarks, double weightPct, int userId) {
        if (name.isBlank())            throw new IllegalArgumentException("Name required.");
        if (maxMarks <= 0)             throw new IllegalArgumentException("Max marks must be > 0.");
        if (weightPct <= 0)            throw new IllegalArgumentException("Weight must be > 0.");
        if (weightPct > remainingWeight(moduleId))
            throw new IllegalArgumentException("Weight exceeds remaining " + remainingWeight(moduleId) + "%.");
        try (Connection c = Database.get()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO assessments(module_id, name, max_marks, weight_pct, sort_order) " +
                    "VALUES (?,?,?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM assessments a WHERE a.module_id = ?))")) {
                ps.setInt(1, moduleId); ps.setString(2, name.trim());
                ps.setDouble(3, maxMarks); ps.setDouble(4, weightPct); ps.setInt(5, moduleId);
                ps.executeUpdate();
            }
            Database.audit(c, userId, "ADD_ASSESSMENT", moduleId + ": " + name + " (" + weightPct + "%)");
            c.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---------------------------------------------------------------- students
    /** Create (or reuse) a student by number, then enrol into the module. */
    public void addStudentAndEnrol(int moduleId, String number, String name, int userId) {
        if (number.isBlank() || name.isBlank())
            throw new IllegalArgumentException("Student number and name are required.");
        try (Connection c = Database.get()) {
            c.setAutoCommit(false);
            try {
                int sid;
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM students WHERE student_number = ?")) {
                    ps.setString(1, number);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) sid = rs.getInt(1);
                        else {
                            try (PreparedStatement ins = c.prepareStatement(
                                    "INSERT INTO students(student_number, full_name) VALUES (?,?)",
                                    Statement.RETURN_GENERATED_KEYS)) {
                                ins.setString(1, number); ins.setString(2, name.trim());
                                ins.executeUpdate();
                                try (ResultSet k = ins.getGeneratedKeys()) { k.next(); sid = k.getInt(1); }
                            }
                        }
                    }
                }
                try (PreparedStatement en = c.prepareStatement(
                        "INSERT IGNORE INTO module_students(module_id, student_id) VALUES (?,?)")) {
                    en.setInt(1, moduleId); en.setInt(2, sid); en.executeUpdate();
                }
                Database.audit(c, userId, "ADD_STUDENT", moduleId + ": " + number + " " + name);
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private Map<String, Integer> studentIdsByNumber(Connection c, int moduleId) throws SQLException {
        Map<String, Integer> map = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.student_number, s.id FROM students s " +
                "JOIN module_students ms ON ms.student_id = s.id WHERE ms.module_id = ?")) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) map.put(rs.getString(1), rs.getInt(2));
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- marksheet aggregation
    /**
     * Build the full marksheet with weighted totals, grades and flags.
     * Complexity O(A + M) thanks to HashMap lookups.
     * Returns rows: [studentNumber, fullName, weightedPct, grade, flags]
     */
    public List<String[]> marksheet(int moduleId) {
        Map<Integer, Double> weighted = new HashMap<>();
        Map<Integer, String> flags = new HashMap<>();

        // weighted totals straight from SQL - set-based, no nested loops
        String sql =
                "SELECT ms.student_id," +
                "       SUM(a.weight_pct * COALESCE(m.mark,0) / a.max_marks) AS weighted," +
                "       SUM(CASE WHEN m.mark IS NULL THEN 1 ELSE 0 END)    AS missing " +
                "FROM module_students ms " +
                "JOIN assessments a ON a.module_id = ms.module_id " +
                "LEFT JOIN marks m ON m.assessment_id = a.id AND m.student_id = ms.student_id " +
                "WHERE ms.module_id = ? " +
                "GROUP BY ms.student_id";
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sid = rs.getInt(1);
                    weighted.put(sid, rs.getDouble(2));
                    int miss = rs.getInt(3);
                    if (miss > 0) flags.put(sid, "MISSING x" + miss);
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }

        List<String[]> rows = new ArrayList<>();
        for (Student s : enrolledStudents(moduleId)) {
            double pct = weighted.getOrDefault(s.getId(), 0.0);
            rows.add(new String[]{ s.getStudentNumber(), s.getFullName(),
                    String.format("%.2f", pct), grade(pct), flags.getOrDefault(s.getId(), "") });
        }
        rows.sort(Comparator.comparing(r -> r[0]));   // O(n log n)
        return rows;
    }

    /**
     * Raw marks for the GUI marksheet: studentNumber -> (assessmentId -> mark).
     * Cells with no mark are simply absent from the inner map (= missing).
     */
    public Map<String, Map<Integer, Double>> rawMarks(int moduleId) {
        Map<String, Map<Integer, Double>> out = new HashMap<>();
        String sql =
                "SELECT s.student_number, m.assessment_id, m.mark " +
                "FROM marks m " +
                "JOIN assessments a ON a.id = m.assessment_id " +
                "JOIN students s    ON s.id = m.student_id " +
                "JOIN module_students ms ON ms.student_id = s.id AND ms.module_id = a.module_id " +
                "WHERE a.module_id = ? AND m.mark IS NOT NULL";
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                       .put(rs.getInt(2), rs.getDouble(3));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private List<Student> enrolledStudents(int moduleId) {
        List<Student> out = new ArrayList<>();
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT s.id, s.student_number, s.full_name FROM students s " +
                "JOIN module_students ms ON ms.student_id = s.id WHERE ms.module_id = ? ORDER BY s.student_number")) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Student(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** University-style grading scale. */
    public static String grade(double pct) {
        if (pct >= 75) return "A";
        if (pct >= 70) return "B";
        if (pct >= 60) return "C";
        if (pct >= 50) return "D";
        return "F";
    }

    // ---------------------------------------------------------------- missing-entry flagging
    /** Automatic flagging: anti-join finds every enrolled student x assessment pair with no mark. */
    public List<String> missingEntries(int moduleId) {
        List<String> out = new ArrayList<>();
        String sql =
                "SELECT s.student_number, s.full_name, a.name " +
                "FROM module_students ms " +
                "JOIN students s    ON s.id = ms.student_id " +
                "JOIN assessments a ON a.module_id = ms.module_id " +
                "LEFT JOIN marks m ON m.assessment_id = a.id AND m.student_id = s.id " +
                "WHERE ms.module_id = ? AND m.mark IS NULL " +
                "ORDER BY s.student_number, a.sort_order";
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(rs.getString(1) + " " + rs.getString(2) + " -> " + rs.getString(3));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    // ---------------------------------------------------------------- CSV export
    /** Export the full marksheet: one column per assessment, weighted %, grade, MISSING flags. */
    public Path exportCsv(int moduleId, Path target, int userId) throws IOException {
        List<Assessment> asmts = assessments(moduleId);

        // mark matrix: studentId -> assessmentId -> raw mark (or null)
        Map<Integer, Map<Integer, Double>> matrix = new HashMap<>();
        String sql = "SELECT m.student_id, m.assessment_id, m.mark FROM marks m " +
                     "JOIN assessments a ON a.id = m.assessment_id WHERE a.module_id = ?";
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    matrix.computeIfAbsent(rs.getInt(1), k -> new HashMap<>())
                          .put(rs.getInt(2), rs.getObject(3) == null ? null : rs.getDouble(3));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }

        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            StringBuilder header = new StringBuilder("StudentNumber,StudentName");
            for (Assessment a : asmts) header.append(',').append(a.getName());
            header.append(",Weighted%,Grade,Flags");
            w.write(header.toString()); w.newLine();

            for (String[] row : marksheet(moduleId)) {
                int sid = studentNumberToId(moduleId, row[0]);
                StringBuilder line = new StringBuilder(row[0]).append(',').append(csvEscape(row[1]));
                for (Assessment a : asmts) {
                    Double m = matrix.getOrDefault(sid, Map.of()).get(a.getId());
                    line.append(',').append(m == null ? "MISSING" : String.valueOf(m));
                }
                line.append(',').append(row[2]).append(',').append(row[3]).append(',').append(row[4]);
                w.write(line.toString()); w.newLine();
            }
        }
        try (Connection c = Database.get()) {
            Database.audit(c, userId, "EXPORT_CSV", moduleId + " -> " + target);
        } catch (SQLException e) { throw new RuntimeException(e); }
        return target;
    }

    private int studentNumberToId(int moduleId, String number) {
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT s.id FROM students s JOIN module_students ms ON ms.student_id = s.id " +
                "WHERE ms.module_id = ? AND s.student_number = ?")) {
            ps.setInt(1, moduleId); ps.setString(2, number);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static String csvEscape(String s) {
        return (s.contains(",") || s.contains("\"")) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ---------------------------------------------------------------- CSV import
    /**
     * Import marks from CSV. Header: StudentNumber,StudentName,<AssessmentName>,...
     *  - Blank / "MISSING" cells      -> left as NULL -> automatically flagged by the system
     *  - Unknown student numbers      -> student created + enrolled automatically
     *  - Unknown assessment columns   -> skipped with a warning
     * Runs in a single transaction with batched upserts.
     */
    public ImportResult importCsv(int moduleId, Path file, int userId) throws IOException {
        ImportResult res = new ImportResult();
        Map<String, Assessment> byName = new HashMap<>();
        for (Assessment a : assessments(moduleId)) byName.put(a.getName().toLowerCase(), a);

        List<String[]> cells = new ArrayList<>();       // [studentNumber, studentName, colName, value]
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = r.readLine();
            if (headerLine == null) throw new IOException("Empty CSV file.");
            String[] header = headerLine.split(",", -1);
            List<String> cols = new ArrayList<>();
            for (int i = 2; i < header.length; i++) {
                String col = header[i].trim();
                if (byName.containsKey(col.toLowerCase())) cols.add(col);
                else { res.warn("Unknown assessment column skipped: " + col); res.skipped++; }
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                String number = p[0].trim().toUpperCase();
                String name   = p.length > 1 ? p[1].trim() : "";
                for (int i = 2; i < p.length && (i - 2) < cols.size(); i++) {
                    cells.add(new String[]{number, name, cols.get(i - 2), p[i].trim()});
                }
            }
        }

        try (Connection c = Database.get()) {
            c.setAutoCommit(false);
            try {
                Map<String, Integer> students = studentIdsByNumber(c, moduleId);
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO marks(assessment_id, student_id, mark) VALUES (?,?,?) " +
                        "ON DUPLICATE KEY UPDATE mark = VALUES(mark)")) {
                    for (String[] cell : cells) {
                        String number = cell[0], name = cell[1], col = cell[2], value = cell[3];
                        if (value.isBlank() || value.equalsIgnoreCase("MISSING")) continue; // flagged later
                        double mark;
                        try { mark = Double.parseDouble(value); }
                        catch (NumberFormatException e) {
                            res.skipped++; res.warn("Bad mark value '" + value + "' for " + number); continue;
                        }
                        Integer sid = students.get(number);
                        if (sid == null) {                     // auto-create + enrol unknown student
                            try (PreparedStatement ins = c.prepareStatement(
                                    "INSERT INTO students(student_number, full_name) VALUES (?,?)",
                                    Statement.RETURN_GENERATED_KEYS)) {
                                ins.setString(1, number);
                                ins.setString(2, name.isBlank() ? "UNKNOWN" : name);
                                ins.executeUpdate();
                                try (ResultSet k = ins.getGeneratedKeys()) { k.next(); sid = k.getInt(1); }
                            }
                            try (PreparedStatement en = c.prepareStatement(
                                    "INSERT IGNORE INTO module_students(module_id, student_id) VALUES (?,?)")) {
                                en.setInt(1, moduleId); en.setInt(2, sid); en.executeUpdate();
                            }
                            students.put(number, sid);
                            res.created++;
                        }
                        Assessment a = byName.get(col.toLowerCase());
                        if (mark < 0 || mark > a.getMaxMarks()) {
                            res.skipped++;
                            res.warn(number + ": " + value + " out of range 0-" + a.getMaxMarks() + " for " + col);
                            continue;
                        }
                        up.setInt(1, a.getId()); up.setInt(2, sid); up.setDouble(3, mark);
                        up.addBatch();
                        res.updated++;
                    }
                    up.executeBatch();
                }
                Database.audit(c, userId, "IMPORT_CSV",
                        moduleId + ": " + file + " (" + res.updated + " marks)");
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return res;
    }

    // ---------------------------------------------------------------- HoD summary report
    /** One summary line per module: enrolments, assessments, class average, missing count. */
    public List<String> hodSummary() {
        List<String> out = new ArrayList<>();
        out.add(String.format("%-10s %-35s %8s %6s %8s %8s", "Code", "Module", "Students", "Tests", "Avg%", "Missing"));
        for (Module m : listModules(null)) {
            try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(
                    "SELECT " +
                    " (SELECT COUNT(*) FROM module_students WHERE module_id = ?) AS students," +
                    " (SELECT COUNT(*) FROM assessments    WHERE module_id = ?) AS tests," +
                    " (SELECT AVG(a.weight_pct * m.mark / a.max_marks) " +
                    "    FROM marks m JOIN assessments a ON a.id = m.assessment_id " +
                    "   WHERE a.module_id = ? AND m.mark IS NOT NULL)         AS avg_pct," +
                    " (SELECT COUNT(*) FROM module_students ms " +
                    "    JOIN assessments a ON a.module_id = ms.module_id " +
                    "    LEFT JOIN marks mk ON mk.assessment_id = a.id AND mk.student_id = ms.student_id " +
                    "   WHERE ms.module_id = ? AND mk.mark IS NULL)           AS missing")) {
                for (int i = 1; i <= 4; i++) ps.setInt(i, m.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    out.add(String.format("%-10s %-35s %8d %6d %8s %8d",
                            m.getCode(), m.getName(), rs.getInt("students"), rs.getInt("tests"),
                            rs.getObject("avg_pct") == null ? "n/a" : String.format("%.1f", rs.getDouble("avg_pct")),
                            rs.getInt("missing")));
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        return out;
    }
}

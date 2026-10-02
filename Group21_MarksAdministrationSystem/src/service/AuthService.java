package service;

import db.Database;
import model.*;
import util.PasswordUtil;
import java.sql.*;
import java.util.Optional;

/** Login + one-time seeding of default accounts. */
public class AuthService {

    /** Verify username/password against the salted hash. */
    public Optional<User> login(String username, String password) {
        String sql = "SELECT id, username, full_name, role, password_hash, salt FROM users WHERE username = ?";
        try (Connection c = Database.get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                if (!PasswordUtil.verify(password, rs.getString("salt"), rs.getString("password_hash")))
                    return Optional.empty();
                Role role = Role.valueOf(rs.getString("role"));
                return Optional.of(new User(rs.getInt("id"), rs.getString("username"),
                                            rs.getString("full_name"), role));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during login", e);
        }
    }

    /** Create HoD (admin/admin123) and a demo lecturer (lect/lect123) if the users table is empty. */
    public void seedDefaults() {
        try (Connection c = Database.get()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
            c.setAutoCommit(false);
            try {
                int hod  = insertUser(c, "admin", "admin123", "Head of Department", Role.HOD);
                int lect = insertUser(c, "lect",  "lect123",  "L. Ngwenya (Lecturer)", Role.LECTURER);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO modules(code, name, lecturer_id) VALUES (?,?,?)")) {
                    ps.setString(1, "SDN260S");
                    ps.setString(2, "Software Design II");
                    ps.setInt(3, lect);
                    ps.executeUpdate();
                }
                Database.audit(c, hod, "SEED", "Default accounts and demo module created");
                c.commit();
                System.out.println("[seed] Created admin/admin123 (HoD) and lect/lect123 (Lecturer), module SDN260S.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Seeding failed - did you run schema.sql?", e);
        }
    }

    private int insertUser(Connection c, String username, String password,
                           String fullName, Role role) throws SQLException {
        String salt = PasswordUtil.newSalt();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(username, password_hash, salt, full_name, role) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, PasswordUtil.hash(password, salt));
            ps.setString(3, salt);
            ps.setString(4, fullName);
            ps.setString(5, role.name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }
}

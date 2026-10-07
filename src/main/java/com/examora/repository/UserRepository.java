package com.examora.repository;

import com.examora.model.Role;
import com.examora.model.User;
import com.examora.model.UserProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<User> findAll() {
        return jdbcTemplate.query("select id, name, email, role, avatar from users order by name", this::mapUser);
    }

    public Optional<User> findById(String id) {
        return jdbcTemplate.query("select id, name, email, role, avatar from users where id = ?", this::mapUser, id)
                .stream()
                .findFirst();
    }

    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query("select id, name, email, role, avatar from users where email = ?", this::mapUser, email)
                .stream()
                .findFirst();
    }

    public Optional<UserWithPassword> findByEmailWithPassword(String email) {
        return jdbcTemplate.query(
                        "select id, name, email, role, avatar, password_hash from users where email = ?",
                        this::mapUserWithPassword,
                        email)
                .stream()
                .findFirst();
    }

    public int updatePasswordHash(String id, String passwordHash) {
        return jdbcTemplate.update("update users set password_hash = ? where id = ?", passwordHash, id);
    }

    public User create(String id, String name, String email, String passwordHash, Role role) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role) values (?, ?, ?, ?, ?)",
                id,
                name,
                email,
                passwordHash,
                role.name());
        return new User(id, name, email, role, null);
    }

    /**
     * Creates an academic student account seeded with roll number and date of birth and no
     * usable password. Such accounts sign in once with roll number + date of birth and are
     * forced to set a password before anything else.
     */
    public void createAcademicStudent(String id, String name, String email, String rollNumber,
                                      LocalDate dateOfBirth, String department, String batch,
                                      String passwordHash, boolean passwordChangeRequired) {
        jdbcTemplate.update(
                "insert into users (id, name, email, password_hash, role, roll_number, date_of_birth, department, batch, password_change_required) "
                        + "values (?, ?, ?, ?, 'STUDENT', ?, ?, ?, ?, ?)",
                id,
                name,
                email,
                passwordHash,
                rollNumber,
                dateOfBirth == null ? null : Date.valueOf(dateOfBirth),
                department,
                batch,
                passwordChangeRequired);
    }

    public Optional<UserProfile> findProfileById(String id) {
        return jdbcTemplate.query(
                        "select id, name, email, role, avatar, roll_number, date_of_birth, department, batch, password_change_required "
                                + "from users where id = ?",
                        this::mapProfile,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<UserProfile> findProfileByRollNumber(String rollNumber) {
        return jdbcTemplate.query(
                        "select id, name, email, role, avatar, roll_number, date_of_birth, department, batch, password_change_required "
                                + "from users where roll_number = ?",
                        this::mapProfile,
                        rollNumber)
                .stream()
                .findFirst();
    }

    public Optional<UserIdentityWithPassword> findByRollNumberWithPassword(String rollNumber) {
        return jdbcTemplate.query(
                        "select id, name, email, role, avatar, password_hash, roll_number, date_of_birth, department, batch, password_change_required "
                                + "from users where roll_number = ?",
                        this::mapIdentityWithPassword,
                        rollNumber)
                .stream()
                .findFirst();
    }

    public int updateProfileFields(String id, String name, String department, String batch, String avatar) {
        return jdbcTemplate.update(
                "update users set name = ?, department = ?, batch = ?, avatar = ? where id = ?",
                name,
                department,
                batch,
                avatar,
                id);
    }

    public int setPasswordChangeRequired(String id, boolean required) {
        return jdbcTemplate.update(
                "update users set password_change_required = ? where id = ?",
                required,
                id);
    }

    public int setRollNumber(String id, String rollNumber, LocalDate dateOfBirth) {
        return jdbcTemplate.update(
                "update users set roll_number = ?, date_of_birth = ? where id = ?",
                rollNumber,
                dateOfBirth == null ? null : Date.valueOf(dateOfBirth),
                id);
    }

    public Optional<String> findRollNumber(String id) {
        java.util.List<String> values = jdbcTemplate.query(
                "select roll_number from users where id = ?", (rs, row) -> rs.getString(1), id);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    public int update(String id, User user) {
        return jdbcTemplate.update(
                "update users set name = ?, email = ?, role = ?, avatar = ? where id = ?",
                user.name(),
                user.email(),
                user.role().name(),
                user.avatar(),
                id);
    }

    public int delete(String id) {
        return jdbcTemplate.update("delete from users where id = ?", id);
    }

    private User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getString("avatar"));
    }

    private UserWithPassword mapUserWithPassword(ResultSet rs, int rowNum) throws SQLException {
        User user = new User(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getString("avatar"));
        return new UserWithPassword(user, rs.getString("password_hash"));
    }

    public record UserWithPassword(User user, String passwordHash) {
    }

    public record UserIdentityWithPassword(User user, String passwordHash, String rollNumber,
                                           LocalDate dateOfBirth, boolean passwordChangeRequired) {
    }

    private UserProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new UserProfile(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getString("avatar"),
                rs.getString("roll_number"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("department"),
                rs.getString("batch"),
                rs.getBoolean("password_change_required"));
    }

    private UserIdentityWithPassword mapIdentityWithPassword(ResultSet rs, int rowNum) throws SQLException {
        User user = new User(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                Role.valueOf(rs.getString("role")),
                rs.getString("avatar"));
        return new UserIdentityWithPassword(
                user,
                rs.getString("password_hash"),
                rs.getString("roll_number"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getBoolean("password_change_required"));
    }
}

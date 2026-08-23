package com.examora.repository;

import com.examora.model.Role;
import com.examora.model.User;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public User create(User user) {
        jdbcTemplate.update(
                "insert into users (id, name, email, role, avatar) values (?, ?, ?, ?, ?)",
                user.id(),
                user.name(),
                user.email(),
                user.role().name(),
                user.avatar());
        return user;
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
}

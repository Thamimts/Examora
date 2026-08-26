-- Local demonstration accounts. Change or remove these before a production deployment.
-- The BCrypt value is the password "password".
insert into users (id, name, email, password_hash, role)
select 'demo-teacher', 'Demo Teacher', 'teacher@examora.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'TEACHER'
where not exists (select 1 from users where email = 'teacher@examora.local');

insert into users (id, name, email, password_hash, role)
select 'demo-student', 'Demo Student', 'student@examora.local', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'STUDENT'
where not exists (select 1 from users where email = 'student@examora.local');

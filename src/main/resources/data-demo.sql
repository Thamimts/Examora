-- Development-only demonstration accounts.
-- This file is loaded ONLY when the "demo" Spring profile is active
-- (see application-demo.properties). It never runs in production.
-- The BCrypt value is the password "password".
insert into users (id, name, email, password_hash, role)
select 'demo-teacher', 'Demo Teacher', 'teacher@examora.local', '$2a$10$zFvVGRWLNX68.j/GYJLJ8ukicGsRO946/B/XgwzvUJ4GnbrP8TXD6', 'TEACHER'
where not exists (select 1 from users where email = 'teacher@examora.local');

insert into users (id, name, email, password_hash, role)
select 'demo-student', 'Demo Student', 'student@examora.local', '$2a$10$zFvVGRWLNX68.j/GYJLJ8ukicGsRO946/B/XgwzvUJ4GnbrP8TXD6', 'STUDENT'
where not exists (select 1 from users where email = 'student@examora.local');

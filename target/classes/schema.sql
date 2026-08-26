create table if not exists users (
    id varchar(36) primary key,
    name varchar(120) not null,
    email varchar(180) not null unique,
    password_hash varchar(255),
    role varchar(30) not null,
    avatar varchar(255),
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp
);

create table if not exists exams (
    id varchar(36) primary key,
    title varchar(180) not null,
    subject varchar(120) not null,
    date varchar(30) not null,
    duration int not null,
    status varchar(30) not null,
    participants int not null default 0,
    average_score decimal(5,2),
    created_by varchar(36),
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    constraint fk_exams_created_by foreign key (created_by) references users(id) on delete set null
);

create table if not exists questions (
    id varchar(36) primary key,
    exam_id varchar(36) not null,
    text text not null,
    answer text,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    constraint fk_questions_exam foreign key (exam_id) references exams(id) on delete cascade
);

create table if not exists question_options (
    id varchar(36) primary key,
    question_id varchar(36) not null,
    text text not null,
    display_order int not null default 0,
    correct_answer boolean not null default false,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    constraint fk_question_options_question foreign key (question_id) references questions(id) on delete cascade
);

create table if not exists results (
    id varchar(36) primary key,
    user_id varchar(36),
    exam_id varchar(36),
    exam_title varchar(180) not null,
    subject varchar(120) not null,
    score int not null,
    total int not null,
    date varchar(30) not null,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    constraint fk_results_user foreign key (user_id) references users(id) on delete set null,
    constraint fk_results_exam foreign key (exam_id) references exams(id) on delete set null
);

create table if not exists answers (
    id varchar(36) primary key,
    user_id varchar(36),
    exam_id varchar(36) not null,
    question_id varchar(36) not null,
    option_id varchar(36),
    `value` text,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    constraint fk_answers_user foreign key (user_id) references users(id) on delete set null,
    constraint fk_answers_exam foreign key (exam_id) references exams(id) on delete cascade,
    constraint fk_answers_question foreign key (question_id) references questions(id) on delete cascade,
    constraint fk_answers_option foreign key (option_id) references question_options(id) on delete set null
);

create table if not exists proctor_events (
    id varchar(36) primary key,
    attempt_id varchar(80) not null,
    type varchar(80) not null,
    occurred_at varchar(40) not null,
    created_at timestamp default current_timestamp
);

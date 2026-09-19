create table if not exists users (
 id varchar(36) primary key, name varchar(120) not null, email varchar(180) not null unique, password_hash varchar(255), role varchar(30) not null, avatar varchar(255), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp
);
alter table users add column if not exists two_factor_enabled boolean not null default false;
alter table users add column if not exists two_factor_secret varchar(64);
alter table users add column if not exists two_factor_pending_secret varchar(64);
create table if not exists exams (
 id varchar(36) primary key, title varchar(180) not null, subject varchar(120) not null, date varchar(30) not null, duration int not null, status varchar(30) not null, participants int not null default 0, average_score decimal(5,2), created_by varchar(36), start_at timestamp, end_at timestamp, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint fk_exams_created_by foreign key (created_by) references users(id) on delete set null
);
create table if not exists questions (
 id varchar(36) primary key, exam_id varchar(36) not null, text text not null, answer text, difficulty int not null default 2, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint fk_questions_exam foreign key (exam_id) references exams(id) on delete cascade
);
alter table questions add column if not exists difficulty int not null default 2;
create table if not exists question_options (
 id varchar(36) primary key, question_id varchar(36) not null, text text not null, display_order int not null default 0, correct_answer boolean not null default false, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint fk_question_options_question foreign key (question_id) references questions(id) on delete cascade
);
create table if not exists results (
 id varchar(36) primary key, user_id varchar(36), exam_id varchar(36), exam_title varchar(180) not null, subject varchar(120) not null, score int not null, total int not null, date varchar(30) not null, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint fk_results_user foreign key (user_id) references users(id) on delete set null, constraint fk_results_exam foreign key (exam_id) references exams(id) on delete set null, constraint uq_result_user_exam unique (user_id, exam_id)
);
create table if not exists exam_attempts (
 id varchar(36) primary key, exam_id varchar(36) not null, student_id varchar(36) not null, attempt_number int not null default 1, status varchar(20) not null, started_at timestamp not null, expires_at timestamp not null, submitted_at timestamp, version int not null default 0,
 constraint uq_exam_attempt_number unique (exam_id, student_id, attempt_number), constraint fk_attempt_exam foreign key (exam_id) references exams(id) on delete cascade, constraint fk_attempt_student foreign key (student_id) references users(id) on delete cascade
);
create table if not exists retest_requests (
 id varchar(36) primary key, exam_id varchar(36) not null, student_id varchar(36) not null, status varchar(20) not null, requested_at timestamp not null, reviewed_at timestamp, reviewed_by varchar(36), reason varchar(500),
 constraint fk_retest_exam foreign key (exam_id) references exams(id) on delete cascade, constraint fk_retest_student foreign key (student_id) references users(id) on delete cascade, constraint fk_retest_reviewer foreign key (reviewed_by) references users(id) on delete set null,
 constraint uq_retest_pending unique (exam_id, student_id, status)
);
create index if not exists idx_retest_status on retest_requests(status, requested_at desc);
create table if not exists answers (
 id varchar(36) primary key, user_id varchar(36), exam_id varchar(36) not null, question_id varchar(36) not null, option_id varchar(36), answer_value text, attempt_id varchar(36), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint uq_answer_attempt_question unique (attempt_id, question_id), constraint fk_answers_user foreign key (user_id) references users(id) on delete set null, constraint fk_answers_exam foreign key (exam_id) references exams(id) on delete cascade, constraint fk_answers_question foreign key (question_id) references questions(id) on delete cascade, constraint fk_answers_option foreign key (option_id) references question_options(id) on delete set null, constraint fk_answers_attempt foreign key (attempt_id) references exam_attempts(id)
);
alter table answers add column if not exists correct boolean;
create table if not exists proctor_events (
 id varchar(36) primary key, attempt_id varchar(36) not null, event_id varchar(100), type varchar(80) not null, occurred_at varchar(40) not null, metadata text, created_at timestamp default current_timestamp, server_received_at timestamp default current_timestamp,
 constraint uq_proctor_event unique (attempt_id, event_id), constraint fk_proctor_attempt foreign key (attempt_id) references exam_attempts(id)
);
alter table proctor_events add column if not exists server_received_at timestamp default current_timestamp;
create index if not exists idx_proctor_events_attempt on proctor_events (attempt_id, occurred_at desc);
alter table proctor_events add column if not exists source varchar(30) not null default 'BROWSER';
alter table proctor_events add column if not exists confidence double precision;
alter table proctor_events add column if not exists duration_ms bigint;
create index if not exists idx_proctor_events_source on proctor_events (source);
create table if not exists activity_events (
 id varchar(36) primary key, actor_id varchar(36), audience varchar(20) not null,
 type varchar(60) not null, message varchar(300) not null, created_at timestamp not null default current_timestamp,
 constraint fk_activity_actor foreign key (actor_id) references users(id) on delete set null
);
create index if not exists idx_activity_audience_created on activity_events (audience, actor_id, created_at desc);
create table if not exists practice_sessions (
 id varchar(36) primary key, student_id varchar(36) not null, exam_id varchar(36) not null, status varchar(20) not null, target_question_count int not null default 10, working_difficulty decimal(4,1) not null default 3.0, in_progress_question_id varchar(36), answered_count int not null default 0, correct_count int not null default 0, started_at timestamp not null, last_activity_at timestamp not null, completed_at timestamp,
 constraint fk_practice_session_student foreign key (student_id) references users(id) on delete cascade, constraint fk_practice_session_exam foreign key (exam_id) references exams(id) on delete cascade, constraint fk_practice_session_question foreign key (in_progress_question_id) references questions(id)
);
create index if not exists idx_practice_sessions_active on practice_sessions (student_id, status, started_at desc);
create table if not exists practice_answers (
 id varchar(36) primary key, session_id varchar(36) not null, question_id varchar(36) not null, option_id varchar(36), answer_value text, correct boolean not null, difficulty int not null, sequence_index int not null, answered_at timestamp not null,
 constraint fk_practice_answer_session foreign key (session_id) references practice_sessions(id) on delete cascade, constraint fk_practice_answer_question foreign key (question_id) references questions(id) on delete cascade, constraint fk_practice_answer_option foreign key (option_id) references question_options(id) on delete set null, constraint uq_practice_session_question unique (session_id, question_id)
);
create index if not exists idx_practice_answers_session on practice_answers (session_id, sequence_index);
create table if not exists ai_practice_sessions (
 id varchar(36) primary key, student_id varchar(36) not null, topic varchar(100) not null, difficulty varchar(10) not null, status varchar(20) not null default 'ACTIVE', question_count int not null, answered_count int not null default 0, correct_count int not null default 0, score int, percentage decimal(5,2), min_question_count int, min_accuracy decimal(5,2), completion_met boolean not null default false, started_at timestamp not null, completed_at timestamp,
 constraint fk_ai_practice_session_student foreign key (student_id) references users(id) on delete cascade
);
alter table ai_practice_sessions add column if not exists min_question_count int;
alter table ai_practice_sessions add column if not exists min_accuracy decimal(5,2);
alter table ai_practice_sessions add column if not exists completion_met boolean not null default false;
create index if not exists idx_ai_practice_sessions_student on ai_practice_sessions (student_id, status, started_at desc);
create index if not exists idx_ai_practice_sessions_progress on ai_practice_sessions (student_id, status, topic, difficulty);
create table if not exists ai_generated_questions (
 id varchar(36) primary key, session_id varchar(36) not null, question_text text not null, correct_option text not null, explanation text not null, topic varchar(100) not null, difficulty varchar(10) not null, order_index int not null, created_at timestamp default current_timestamp,
 constraint fk_ai_question_session foreign key (session_id) references ai_practice_sessions(id) on delete cascade
);
create index if not exists idx_ai_generated_questions_session on ai_generated_questions (session_id, order_index);
create table if not exists ai_question_options (
 id varchar(36) primary key, question_id varchar(36) not null, text text not null, display_order int not null default 0,
 constraint fk_ai_option_question foreign key (question_id) references ai_generated_questions(id) on delete cascade
);
create index if not exists idx_ai_question_options_question on ai_question_options (question_id, display_order);
create table if not exists ai_practice_answers (
 id varchar(36) primary key, session_id varchar(36) not null, question_id varchar(36) not null, option_id varchar(36), selected_option text, correct boolean, answered_at timestamp not null,
 constraint fk_ai_practice_answer_session foreign key (session_id) references ai_practice_sessions(id) on delete cascade, constraint fk_ai_practice_answer_question foreign key (question_id) references ai_generated_questions(id) on delete cascade, constraint fk_ai_practice_answer_option foreign key (option_id) references ai_question_options(id) on delete set null, constraint uq_ai_practice_session_question unique (session_id, question_id)
);
create index if not exists idx_ai_practice_answers_session on ai_practice_answers (session_id);
create table if not exists ai_practice_reviews (
 id varchar(36) primary key, session_id varchar(36) not null, question_id varchar(36) not null, explanation text not null, created_at timestamp default current_timestamp,
 constraint fk_ai_review_session foreign key (session_id) references ai_practice_sessions(id) on delete cascade, constraint fk_ai_review_question foreign key (question_id) references ai_generated_questions(id) on delete cascade, constraint uq_ai_review_session_question unique (session_id, question_id)
);
create table if not exists ai_tutor_questions (
 id varchar(36) primary key, student_id varchar(36) not null, session_id varchar(36) not null, source_question_id varchar(36), kind varchar(24) not null, question_text text not null, options varchar(4096) not null, difficulty int not null, hint text not null, correct_answer text not null, answered boolean not null default false, correct boolean, answered_at timestamp, created_at timestamp default current_timestamp,
 constraint fk_ai_tutor_question_student foreign key (student_id) references users(id) on delete cascade, constraint fk_ai_tutor_question_session foreign key (session_id) references ai_practice_sessions(id) on delete cascade
);
create index if not exists idx_ai_tutor_questions_student on ai_tutor_questions (student_id, created_at desc);

create table if not exists two_factor_challenges (
 id varchar(36) primary key, user_id varchar(36) not null, token_hash varchar(64) not null, kind varchar(20) not null, expires_at timestamp not null, used boolean not null default false, created_at timestamp not null default current_timestamp,
 constraint fk_2fa_challenge_user foreign key (user_id) references users(id) on delete cascade
);
create unique index if not exists uq_2fa_challenge_hash on two_factor_challenges (token_hash);
create index if not exists idx_2fa_challenges_user on two_factor_challenges (user_id, used, expires_at);
create table if not exists two_factor_recovery_codes (
 id varchar(36) primary key, user_id varchar(36) not null, code_hash varchar(255) not null, used_at timestamp,
 constraint fk_2fa_recovery_user foreign key (user_id) references users(id) on delete cascade
);
create index if not exists idx_2fa_recovery_user on two_factor_recovery_codes (user_id, used_at);
create table if not exists oauth_accounts (
 id varchar(36) primary key, provider varchar(20) not null, provider_user_id varchar(120) not null, user_id varchar(36) not null, email varchar(180), created_at timestamp not null default current_timestamp, updated_at timestamp not null default current_timestamp,
 constraint fk_oauth_account_user foreign key (user_id) references users(id) on delete cascade,
 constraint uq_oauth_provider_user unique (provider, provider_user_id)
);
create index if not exists idx_oauth_account_user on oauth_accounts (user_id);

create table if not exists exam_rooms (
 id varchar(36) primary key, exam_id varchar(36) not null, room_code varchar(10) not null, status varchar(10) not null default 'WAITING', created_by varchar(36), started_at timestamp, ended_at timestamp, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
 constraint uq_exam_room_code unique (room_code), constraint fk_exam_room_exam foreign key (exam_id) references exams(id) on delete cascade, constraint fk_exam_room_creator foreign key (created_by) references users(id) on delete set null
);
create index if not exists idx_exam_room_active on exam_rooms (status, created_at desc);
create table if not exists exam_room_members (
 id varchar(36) primary key, room_id varchar(36) not null, student_id varchar(36) not null, joined_at timestamp not null default current_timestamp, left_at timestamp, status varchar(10) not null default 'JOINED',
 constraint fk_exam_room_member_room foreign key (room_id) references exam_rooms(id) on delete cascade, constraint fk_exam_room_member_student foreign key (student_id) references users(id) on delete cascade,
 constraint uq_exam_room_active_member unique (room_id, student_id, status)
);
create index if not exists idx_exam_room_member_room_active on exam_room_members (room_id, status, joined_at);
create index if not exists idx_exam_room_member_student_active on exam_room_members (student_id, status, joined_at desc);
create index if not exists idx_results_exam_id on results (exam_id);
create index if not exists idx_answers_exam_question on answers (exam_id, question_id);
create index if not exists idx_answers_user_exam on answers (user_id, exam_id);
create index if not exists idx_answers_attempt on answers (attempt_id);

create table if not exists exam_access_state (
 student_id varchar(36) not null,
 exam_id varchar(36) not null,
 status varchar(30) not null default 'ELIGIBLE',
 suspended_at timestamp,
 suspended_reason varchar(100),
 created_at timestamp default current_timestamp,
 updated_at timestamp default current_timestamp,
 primary key (student_id, exam_id),
 constraint fk_eas_student foreign key (student_id) references users(id) on delete cascade,
 constraint fk_eas_exam foreign key (exam_id) references exams(id) on delete cascade
);
create index if not exists idx_exam_access_status on exam_access_state (status);
alter table exam_attempts add column if not exists warning_count int not null default 0;
alter table exam_attempts add column if not exists terminated_at timestamp;
alter table exam_attempts add column if not exists terminated_reason varchar(100);

create table if not exists proctor_fusion_results (
 id varchar(36) primary key,
 attempt_id varchar(36) not null,
 calculated_at timestamp not null default current_timestamp,
 window_start varchar(40) not null,
 window_end varchar(40) not null,
 baseline_score double precision not null,
 fused_score double precision not null,
 fused_confidence double precision,
 evidence_count int not null,
 algorithm_version varchar(40) not null,
 constraint fk_fusion_attempt foreign key (attempt_id) references exam_attempts(id) on delete cascade,
 constraint uq_fusion_attempt_version unique (attempt_id, algorithm_version)
);
create index if not exists idx_fusion_attempt_calculated on proctor_fusion_results (attempt_id, calculated_at desc);

create table if not exists research_experiments (
 id varchar(36) primary key,
 name varchar(180) not null,
 description varchar(500),
 algorithm_version varchar(40) not null,
 baseline_version varchar(40) not null,
 status varchar(20) not null default 'DRAFT',
 created_by varchar(36),
 created_at timestamp not null default current_timestamp,
 constraint fk_research_experiment_creator foreign key (created_by) references users(id) on delete set null
);
create index if not exists idx_research_experiments_created on research_experiments (created_at desc);

create table if not exists research_samples (
 id varchar(36) primary key,
 experiment_id varchar(36) not null,
 attempt_id varchar(36),
 window_start varchar(40) not null,
 window_end varchar(40) not null,
 label varchar(40),
 metadata text,
 raw_media_bytes bigint,
 signal_bytes bigint,
 created_at timestamp not null default current_timestamp,
 constraint fk_research_sample_experiment foreign key (experiment_id) references research_experiments(id) on delete cascade,
 constraint fk_research_sample_attempt foreign key (attempt_id) references exam_attempts(id) on delete set null,
 constraint uq_research_sample_window unique (experiment_id, window_start, window_end)
);
create index if not exists idx_research_samples_experiment on research_samples (experiment_id, created_at desc);
create index if not exists idx_research_samples_attempt_window on research_samples (attempt_id, window_start, window_end);

create table if not exists research_reviews (
 id varchar(36) primary key,
 sample_id varchar(36) not null,
 reviewer_id varchar(36) not null,
 label varchar(40) not null,
 confidence double precision,
 notes varchar(500),
 reviewed_at timestamp not null default current_timestamp,
 constraint fk_research_review_sample foreign key (sample_id) references research_samples(id) on delete cascade,
 constraint fk_research_review_reviewer foreign key (reviewer_id) references users(id) on delete set null,
 constraint uq_research_review_sample_reviewer unique (sample_id, reviewer_id)
);
create index if not exists idx_research_reviews_sample on research_reviews (sample_id, reviewed_at desc);

alter table research_experiments add column if not exists dataset_version varchar(40) not null default 'dataset-v1';
alter table research_samples add column if not exists scenario varchar(40);
alter table research_samples add column if not exists measured_latency_ms bigint;
create index if not exists idx_research_samples_experiment_attempt on research_samples (experiment_id, attempt_id, window_start, window_end);

alter table research_experiments add column if not exists exam_id varchar(36);
create index if not exists idx_research_experiments_exam on research_experiments (exam_id);

create table if not exists research_runs (
 id varchar(36) primary key,
 experiment_id varchar(36) not null,
 run_code varchar(60) not null,
 started_at timestamp,
 ended_at timestamp,
 operator_id varchar(36) not null,
 status varchar(20) not null default 'PLANNED',
 dataset_version varchar(40) not null default 'dataset-v1',
 notes varchar(1000),
 created_at timestamp not null default current_timestamp,
 constraint fk_research_run_experiment foreign key (experiment_id) references research_experiments(id) on delete cascade,
 constraint fk_research_run_operator foreign key (operator_id) references users(id) on delete set null,
 constraint uq_research_run_code unique (experiment_id, run_code)
);
create index if not exists idx_research_runs_experiment on research_runs (experiment_id, created_at desc);

create table if not exists research_run_samples (
 id varchar(36) primary key,
 run_id varchar(36) not null,
 attempt_id varchar(36) not null,
 scenario varchar(40) not null,
 conditions_json varchar(1000),
 conditions_key varchar(255) not null,
 status varchar(20) not null default 'PLANNED',
 started_at varchar(40),
 ended_at varchar(40),
 measured_latency_ms bigint,
 raw_media_bytes bigint,
 signal_bytes bigint,
 research_sample_id varchar(36),
 created_at timestamp not null default current_timestamp,
 constraint fk_run_sample_run foreign key (run_id) references research_runs(id) on delete cascade,
 constraint fk_run_sample_attempt foreign key (attempt_id) references exam_attempts(id),
 constraint uq_run_sample unique (run_id, attempt_id, scenario, conditions_key)
);
create index if not exists idx_run_samples_run on research_run_samples (run_id, created_at desc);

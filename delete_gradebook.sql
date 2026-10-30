set foreign_key_checks = 0;
drop table if exists grade, assignment, student, class;
set foreign_key_checks = 1;
drop table if exists grade;
drop table if exists assignment;
drop table if exists student;
drop table if exists enrollment;
drop table if exists category;
drop table if exists class;
-- plus any other tables your script created
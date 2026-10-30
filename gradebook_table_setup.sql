CREATE DATABASE IF NOT EXISTS gradebook;
USE gradebook;

create table student (
	student_id char(10) primary key,
    student_name varchar(50),
    username varchar(50)
);

create table class (
	class_id char(10) primary key,
    course_num integer,
    term varchar(50),
    section_num integer,
    class_desc text
);

create table enrollment (
	student_id char(10) not null,
    class_id char(10) not null,
    
    primary key (student_id, class_id),
    foreign key (student_id) references student(student_id),
    foreign key (class_id) references class(class_id)
);

create table category (
	class_id char(10) not null,
    category_name varchar(50),
    weight decimal,
    
    primary key (class_id, category_name),
    foreign key (class_id) references class(class_id)
);

create table assignment (
	class_id char(10) not null,
    assignment_name varchar(150),
    category_name varchar(50),
    assignment_desc text,
    max_points integer,
    
    primary key (class_id, assignment_name),
    foreign key (class_id) references class(class_id)
		on delete cascade
);

create table grade (
    class_id char(10) not null,
    student_id char(10) not null,
    assignment_name varchar(150) not null,
    points integer,
    is_ungraded boolean,

    primary key (class_id, student_id, assignment_name),
    foreign key (class_id) references class(class_id),
    foreign key (student_id) references student(student_id),
    foreign key (class_id, assignment_name) references assignment(class_id, assignment_name)
);

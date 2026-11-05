CREATE DATABASE IF NOT EXISTS gradebook;
USE gradebook;

CREATE TABLE student (
    student_id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    student_num VARCHAR(20) NOT NULL UNIQUE,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL
);

CREATE TABLE class (
    class_id INT AUTO_INCREMENT PRIMARY KEY,
    course_num VARCHAR(20) NOT NULL,
    term VARCHAR(50) NOT NULL,
    section_num INT NOT NULL ,
    class_desc TEXT,
    CONSTRAINT unq_class UNIQUE (course_num, term, section_num)
);

CREATE TABLE enrollment (
    student_id INT NOT NULL,
    class_id INT NOT NULL,

    PRIMARY KEY (student_id, class_id),
    FOREIGN KEY (student_id) REFERENCES student(student_id) ON DELETE CASCADE,
    FOREIGN KEY (class_id) REFERENCES class(class_id) ON DELETE CASCADE
);

CREATE TABLE category (
    category_id INT AUTO_INCREMENT PRIMARY KEY, -- surrogate primary key
    class_id INT NOT NULL,
    category_name VARCHAR(50) NOT NULL,
    weight DECIMAL(5, 2) NOT NULL,

    CONSTRAINT unq_category_per_class UNIQUE (class_id, category_name),
    FOREIGN KEY (class_id) REFERENCES class(class_id) ON DELETE CASCADE
);

CREATE TABLE assignment (
    assignment_id INT AUTO_INCREMENT PRIMARY KEY,
    class_id INT NOT NULL,
    category_id INT NOT NULL,
    assignment_name VARCHAR(150) NOT NULL,
    assignment_desc TEXT,
    points DECIMAL(7, 2) NOT NULL,

    CONSTRAINT unq_assignment_per_class UNIQUE (class_id, assignment_id),
    FOREIGN KEY (class_id) REFERENCES class(class_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id) REFERENCES category(category_id) ON DELETE CASCADE
);

CREATE TABLE grade (
    student_id INT NOT NULL,
    class_id INT NOT NULL,
    assignment_id INT NOT NULL,
    points_earned DECIMAL(7, 2), -- NULL means ungraded

    PRIMARY KEY (student_id, assignment_id),
    FOREIGN KEY (student_id, class_id) REFERENCES enrollment(student_id, class_id)
                   ON DELETE CASCADE,
    FOREIGN KEY (class_id, assignment_id) REFERENCES assignment(class_id, assignment_id) ON DELETE CASCADE
);

-- Secondary index 1: helps with looking up all student grades for a given class
CREATE INDEX idx_grade_class ON grade(class_id, student_id);

-- Secondary index 2: helps with looking up assignments by class and category
CREATE INDEX idx_assignment_category ON assignment(class_id, category_id);
# CS410 Final Project: Gradebook Database

## Authors: Lex Watts, Trust Migihsa, and Calvin Arnold

### Project Setup

Each group member is working on their own branch, this branch is intended to work with MySQL workbench over an SSH connection.

### Software Used

MySQL - personal SQL environment

Java - language used for database interaction commands and interface

GitHub - version control and developer collaboration

### Using the Program

Firstly, ensure your MySQL environment is running.

Navigate to the directory via terminal where the GradebookApp.java file resides.

Then, compile the Java file with the command:</br>
javac GradebookApp.java

Run the program with:</br>
java GradebookApp.java

You know you've connected to your database successfully when the command line returns:</br>
"Connected. Type 'help' for commands."

Then, run the 'help' command to see all usable commands.

Exit the program using CTRL+C or entering quit/exit in the command line.

### Commands Explained

new-class - create a class, ex: new-class CS410 Fa26 1 </br>
list-classes - list every class with its enrollment count </br>
select-class - make a class active </br>
show-class - show the active class and its enrollment count </br>
add-category - add a grading category to the active class </br>
add-assignment - add an assignment to a category </br>
add-student - create and enroll a student, or enroll an existing one </br>
help - list commands or explain one </br>
quit / exit - leave the shell </br>

### Design Decisions

#### Relational Diagram Explained -

CLASS obtains the surrogate key ClassID. CourseNo., Term, and SectionNo are deemed
unique.

STUDENT uses StudentID as the primary key, while Username must be unique.

CATEGORY and ASSIGNMENT are both weak entities that each use (ClassID, Name) as their
primary key.

The ENROLLMENT relation is created by the Enrolls_In relationship. The primary key is
(ClassID, StudentID).

The GRADE relation is created by the Is_Graded relationship. The primary key is (ClassID,
StudentID, Assignment_Name). Points are NOT NULL.

D7 holds because GRADE carries a ClassID shared by both composite foreign keys. The same
class must own the enrollment and the assignment. No enrollment causes rejection, and a
grade against another class also means rejection.

### Future Implementations

Add more functions for deletion of classes, students, assignments.

Have an option to bulk search for multiple students.

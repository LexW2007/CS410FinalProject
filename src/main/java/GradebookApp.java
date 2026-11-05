import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/* Gradebook application for managing student grades 
   Connects to a MySQL database and provides a command-line interface 
   for creating classes, adding categories, assignments, and students, 
   and displaying class information.
*/
public class GradebookApp {
    static class CmdException extends Exception {
        public CmdException(String message){
            super(message);
        }
    }

    private static final int MYSQL_DUPLICATE_KEY = 1062; // MySQL error code for duplicate key

    private final Connection conn;
    private int classId = -1;          // -1 means no class selected
    private String classLabel = null;  // ex: CS410 Fa26 1

    GradebookApp(Connection conn) { this.conn = conn; }
    
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:56822/gradebook";
        String username = "msandbox";
        String password = "lexwatts";

        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            System.out.println("Connected. Type 'help' for commands.");
            new GradebookApp(c).run();
        } catch (SQLException e) {
            System.err.println("error: could not connect to database: " + firstLine(e.getMessage()));
            System.exit(1);
        }
    }

    /*
     * Main command loop
     */
    void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print(classLabel == null ? "gradebook> " : "gradebook[" + classLabel + "]> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) { System.out.println(); break; }   // Ctrl-D
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                List<String> t = tokenize(line);
                String cmd = t.get(0).toLowerCase();
                List<String> a = t.subList(1, t.size());
                if (cmd.equals("quit") || cmd.equals("exit")) break;
                commands(cmd, a);
            } catch (CmdException e) {
                System.out.println("error: " + e.getMessage());
            } catch (SQLException e) {
                System.out.println("error: database problem: " + firstLine(e.getMessage()));
            } catch (RuntimeException e) {
                System.out.println("error: unexpected problem: " + e.getClass().getSimpleName());
            }
        }
        System.out.println("Bye.");
    }

    // lists all commands
     private void commands(String cmd, List<String> a) throws CmdException, SQLException {
        switch (cmd) {
            case "new-class":      newClass(a);      break;
            case "list-classes":   listClasses(a);   break;
            case "select-class":   selectClass(a);   break;
            case "show-class":     showClass(a);     break;
            case "add-category":   addCategory(a);   break;
            case "add-assignment": addAssignment(a); break;
            case "add-student":    addStudent(a);    break;
            case "help":           help(a);          break;
            default: throw new CmdException("unknown command '" + cmd + "' (try: help)");
        }
    }

    // add a new class to the database
     private void newClass(List<String> a) throws CmdException, SQLException {
        need(a, 4, "new-class <course> <term> <section> \"<description>\"");
        String term = a.get(1);
        if (!term.matches("(Wi|Sp|Su|Fa)\\d{2}"))
            throw new CmdException("term must be (Wi/Sp/Su/Fa + 2-digit year), ex: Fa26");
        int section = parseInt(a.get(2), "section");

        String sql = "INSERT INTO class (course_num, term, section_num, class_desc) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.get(0));
            ps.setString(2, term);
            ps.setInt(3, section);
            ps.setString(4, a.get(3));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "class " + a.get(0) + " " + term + " section " + section + " already exists");
        }
        System.out.println("created class " + a.get(0) + " " + term + " section " + section);
    }

    // list all classes in the database, ordered by term (oldest first) then course number
    private void listClasses(List<String> a) throws CmdException, SQLException {
        need(a, 0, "list-classes");
        String sql = """
            SELECT c.course_num, c.term, c.section_num, c.class_desc, COUNT(e.student_id) AS enrolled
            FROM class c
            LEFT JOIN enrollment e ON e.class_id = c.class_id
            GROUP BY c.class_id, c.course_num, c.term, c.section_num, c.class_desc
            ORDER BY CAST(SUBSTRING(c.term, 3) AS UNSIGNED),
                     CASE LEFT(c.term, 2) WHEN 'Wi' THEN 0 WHEN 'Sp' THEN 1 WHEN 'Su' THEN 2 WHEN 'Fa' THEN 3 ELSE 0 END,
                     c.course_num, c.section_num
            """;
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3),
                                        rs.getString(4), rs.getString(5) });
            }
        }
        if (rows.isEmpty()) { System.out.println("(no classes)"); return; }
        printTable(new String[] {"COURSE", "TERM", "SEC", "DESCRIPTION", "STUDENTS"}, rows);
    }

    // select a class to work with: <course>, <course> <term>, or <course> <term> <section>
    private void selectClass(List<String> a) throws CmdException, SQLException {
        if (a.isEmpty() || a.size() > 3)
            throw new CmdException("usage: select-class <course> [<term>] [<section>]");

        String sql;
        if (a.size() == 3) {
            sql = """
                SELECT class_id, course_num, term, section_num FROM class
                WHERE course_num = ? AND term = ? AND section_num = ?
                """;
        } else if (a.size() == 2) {
            sql = """
                SELECT class_id, course_num, term, section_num FROM class
                WHERE course_num = ? AND term = ?
                ORDER BY section_num
                """;
        } else {
            sql = """
                SELECT class_id, course_num, term, section_num FROM class
                WHERE course_num = ?
                  AND term = (SELECT t.term FROM class t
                              WHERE t.course_num = ?
                              ORDER BY CAST(SUBSTRING(t.term, 3) AS UNSIGNED) DESC,
                                       CASE LEFT(t.term, 2) WHEN 'Wi' THEN 0 WHEN 'Sp' THEN 1 WHEN 'Su' THEN 2 WHEN 'Fa' THEN 3 ELSE 0 END DESC
                              LIMIT 1)
                ORDER BY section_num
                """;
        }

        List<Integer> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.get(0));
            if (a.size() == 1) {
                ps.setString(2, a.get(0));
            } else {
                ps.setString(2, a.get(1));
                if (a.size() == 3) ps.setInt(3, parseInt(a.get(2), "section"));
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getInt(1));
                labels.add(rs.getString(2) + " " + rs.getString(3) + " " + rs.getInt(4));
            }
        }

        if (ids.isEmpty()) throw new CmdException("class not found");
        if (ids.size() > 1)
            throw new CmdException("more than one match: " + String.join(", ", labels)
                + " (specify term and/or section)");
        classId = ids.get(0);
        classLabel = labels.get(0);
        System.out.println("selected class " + classLabel);
    }

    // show the currently selected class
    private void showClass(List<String> a) throws CmdException, SQLException {
        need(a, 0, "show-class");
        if (classId == -1) { System.out.println("no class selected"); return; }
        String sql = """
            SELECT c.course_num, c.term, c.section_num, c.class_desc, COUNT(e.student_id) AS enrolled
            FROM class c
            LEFT JOIN enrollment e ON e.class_id = c.class_id
            WHERE c.class_id = ?
            GROUP BY c.class_id, c.course_num, c.term, c.section_num, c.class_desc
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                classId = -1;
                classLabel = null;
                throw new CmdException("selected class no longer exists");
            }
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5) });
            printTable(new String[] {"COURSE", "TERM", "SEC", "DESCRIPTION", "STUDENTS"}, rows);
        }
    }

    // add a new category to the currently selected class
    private void addCategory(List<String> a) throws CmdException, SQLException {
        need(a, 2, "add-category <name> <weight>");
        if (classId == -1) throw new CmdException("no class selected");
        String name = a.get(0);
        BigDecimal weight = parsePositive(a.get(1), "weight");
        String sql = "INSERT INTO category (class_id, category_name, weight) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setString(2, name);
            ps.setBigDecimal(3, weight);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "category " + name + " already exists for this class");
        }
        System.out.println("added category " + name + " with weight " + weight.toPlainString());
    }

    // add an assignment to the currently selected class
    private void addAssignment(List<String> a) throws CmdException, SQLException {
        need(a, 4, "add-assignment <name> <category> <points> \"<description>\"");
        if (classId == -1) throw new CmdException("no class selected");
        String name = a.get(0);
        String category = a.get(1);
        BigDecimal points = parsePositive(a.get(2), "points");
        String desc = a.get(3);

        // looks the category up inside the INSERT and refuses a duplicate assignment name;
        String sql = """
            INSERT INTO assignment (class_id, category_id, assignment_name, assignment_desc, points)
            SELECT c.class_id, c.category_id, ?, ?, ?
            FROM category c
            WHERE c.class_id = ? AND c.category_name = ?
              AND NOT EXISTS (SELECT 1 FROM assignment x
                              WHERE x.class_id = c.class_id AND x.assignment_name = ?)
            """;
        int inserted;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, desc);
            ps.setBigDecimal(3, points);
            ps.setInt(4, classId);
            ps.setString(5, category);
            ps.setString(6, name);
            inserted = ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "assignment " + name + " already exists for this class");
        }

        if (inserted == 0) {
            String checkSql = "SELECT 1 FROM category WHERE class_id = ? AND category_name = ?";
            boolean categoryExists;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setInt(1, classId);
                ps.setString(2, category);
                categoryExists = ps.executeQuery().next();
            }
            if (!categoryExists) throw new CmdException("category " + category + " does not exist for this class");
            throw new CmdException("assignment " + name + " already exists for this class");
        }
        System.out.println("added assignment " + name + " in category " + category
            + " with points " + points.toPlainString());
    }

    // add a student to the current class
    private void addStudent(List<String> a) throws CmdException, SQLException {
        if (classId == -1) throw new CmdException("no class selected");
        if (a.size() == 1) {
            enrollExisting(a.get(0));
        } else if (a.size() == 4) {
            createAndEnroll(a.get(0), a.get(1), a.get(2), a.get(3));
        } else {
            throw new CmdException("usage: add-student <username> <student-id> <last> <first>"
                + "  or  add-student <username>");
        }
    }

    // enroll an existing student (by username)
    private void enrollExisting(String username) throws CmdException, SQLException {
        String sql = "INSERT INTO enrollment (student_id, class_id) SELECT s.student_id, ? FROM student s WHERE s.username = ?";
        int inserted;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setString(2, username);
            inserted = ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "student " + username + " is already enrolled in " + classLabel);
        }
        if (inserted == 0)
            throw new CmdException("no such student " + username
                + " (use add-student <username> <student-id> <last> <first> to create one)");
        System.out.println("enrolled " + username + " in " + classLabel);
    }

    // create the student if needed then enroll
    private void createAndEnroll(String username, String studentNum, String last, String first)
            throws CmdException, SQLException {
        if (studentNum.length() > 20) throw new CmdException("student-id must be at most 20 characters");
        String warning = null;
        boolean created = false;

        conn.setAutoCommit(false);
        try {
            int studentPk = -1;
            String oldNum = null, oldLast = null, oldFirst = null;

            String findSql = "SELECT student_id, student_num, last_name, first_name FROM student WHERE username = ? FOR UPDATE";
            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    studentPk = rs.getInt(1);
                    oldNum = rs.getString(2);
                    oldLast = rs.getString(3);
                    oldFirst = rs.getString(4);
                }
            }

            if (studentPk == -1) {
                String insertSql = "INSERT INTO student (username, student_num, first_name, last_name) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, username);
                    ps.setString(2, studentNum);
                    ps.setString(3, first);
                    ps.setString(4, last);
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    if (!keys.next()) throw new CmdException("could not create student");
                    studentPk = keys.getInt(1);
                } catch (SQLException e) {
                    throw dupOr(e, "student-id " + studentNum + " belongs to a different student");
                }
                created = true;
            } else {
                if (!oldNum.equals(studentNum))
                    throw new CmdException("student " + username + " already exists with student-id "
                        + oldNum + ", not " + studentNum);
                if (!oldLast.equals(last) || !oldFirst.equals(first)) {
                    String updateSql = "UPDATE student SET first_name = ?, last_name = ? WHERE student_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setString(1, first);
                        ps.setString(2, last);
                        ps.setInt(3, studentPk);
                        ps.executeUpdate();
                    }
                    warning = "warning: name for " + username + " changed from \"" + oldFirst + " "
                        + oldLast + "\" to \"" + first + " " + last + "\"";
                }
            }

            String enrollSql = "INSERT INTO enrollment (student_id, class_id) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(enrollSql)) {
                ps.setInt(1, studentPk);
                ps.setInt(2, classId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw dupOr(e, "student " + username + " is already enrolled in " + classLabel);
            }

            conn.commit();
        } catch (SQLException | CmdException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }

        if (warning != null) System.out.println(warning);
        System.out.println((created ? "created student " : "found student ") + username
            + " and enrolled in " + classLabel);
    }

    // helper function to show all user commands, or explain a single one
    private void help(List<String> a) throws CmdException {
        if (a.size() > 1) throw new CmdException("usage: help [<command>]");
        String[][] h = {
            {"new-class",      "new-class <course> <term> <section> \"<description>\"", "create a class, ex: new-class CS410 Fa26 1 \"Databases\""},
            {"list-classes",   "list-classes",                                          "list every class with its enrollment count"},
            {"select-class",   "select-class <course> [<term>] [<section>]",            "make a class active"},
            {"show-class",     "show-class",                                            "show the active class and its enrollment count"},
            {"add-category",   "add-category <name> <weight>",                          "add a grading category to the active class"},
            {"add-assignment", "add-assignment <name> <category> <points> \"<description>\"", "add an assignment to a category"},
            {"add-student",    "add-student <username> <student-id> <last> <first>  or  add-student <username>", "create and enroll a student, or enroll an existing one"},
            {"help",           "help [<command>]",                                      "list commands or explain one"},
            {"quit / exit",    "quit  or  exit",                                        "leave the shell"},
        };
        if (a.isEmpty()) {
            System.out.println("Available commands:");
            for (String[] r : h) System.out.println("  " + r[1]);
            return;
        }
        String want = a.get(0).toLowerCase();
        for (String[] r : h) {
            if (r[0].startsWith(want)) {
                System.out.println("usage: " + r[1]);
                System.out.println("       " + r[2]);
                return;
            }
        }
        throw new CmdException("no help for '" + want + "'");
    }

    // helper function to take a command line input and split into tokens, handling quoted strings
    static List<String> tokenize(String line) throws CmdException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false, have = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
                have = true;                 // allows empty "" argument
            } else if (Character.isWhitespace(ch) && !inQuote) {
                if (have) { out.add(cur.toString()); cur.setLength(0); have = false; }
            } else {
                cur.append(ch);
                have = true;
            }
        }
        if (inQuote) throw new CmdException("unterminated quote");
        if (have) out.add(cur.toString());
        return out;
    }

    // helper function to print an aligned table, sizing each column to its widest cell
    static void printTable(String[] headers, List<String[]> rows) {
        int[] w = new int[headers.length];
        for (int i = 0; i < w.length; i++) w[i] = headers[i].length();
        for (String[] r : rows)
            for (int i = 0; i < w.length; i++) w[i] = Math.max(w[i], r[i] == null ? 0 : r[i].length());

        StringBuilder fmt = new StringBuilder();
        for (int i = 0; i < w.length; i++)
            fmt.append("%-").append(w[i]).append("s").append(i < w.length - 1 ? "  " : "%n");
        String f = fmt.toString();

        System.out.printf(f, (Object[]) headers);
        String[] dashes = new String[w.length];
        for (int i = 0; i < w.length; i++) dashes[i] = "-".repeat(w[i]);
        System.out.printf(f, (Object[]) dashes);
        for (String[] r : rows) System.out.printf(f, (Object[]) r);
    }

    // helper function to check if the number of arguments matches the expected count
    private static void need(List<String> a, int n, String usage) throws CmdException {
        if (a.size() != n) throw new CmdException("usage: " + usage);
    }

    // helper function to parse an integer from a string, throwing a CmdException if parsing fails
    private static int parseInt(String s, String name) throws CmdException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CmdException(name + " must be an integer");
        }
    }

    // helper function to parse a BigDecimal from a string, throwing a CmdException if parsing fails
    private static BigDecimal parseBigDecimal(String s, String name) throws CmdException {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new CmdException(name + " must be a number");
        }
    }

    // helper function to parse a BigDecimal that must be greater than zero
    private static BigDecimal parsePositive(String s, String name) throws CmdException {
        BigDecimal v = parseBigDecimal(s, name);
        if (v.signum() <= 0) throw new CmdException(name + " must be a positive number");
        return v;
    }

    // helper function to get the first line of a string, used for error messages
    private static String firstLine(String s) {
        if (s == null) return "unknown";
        int i = s.indexOf('\n');
        return i == -1 ? s : s.substring(0, i);
    }

    // helper function to handle duplicate key SQL exceptions, throwing a CmdException with a custom message if the error code matches
    private static CmdException dupOr(SQLException e, String dupMessage) throws SQLException {
        if (e.getErrorCode() == MYSQL_DUPLICATE_KEY) return new CmdException(dupMessage);
        throw e;
    }
}
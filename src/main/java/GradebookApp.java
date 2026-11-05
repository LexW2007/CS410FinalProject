import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private int classId = -1;          // -1: no class selected
    private String classLabel = null;  // ex: "CS410 Fa26 1"

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
     * Main command loop.
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
            throw new CmdException("term must be (Wi/Sp/Su/Fa + 2-digit year)");
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

    // list all classes in the database
    private void listClasses(List<String> a) throws CmdException, SQLException {
        need(a, 0, "list-classes");
        String sql = "SELECT course_num, term, section_num, class_desc FROM class ORDER BY course_num, term, section_num";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String courseNum = rs.getString(1);
                String term = rs.getString(2);
                int sectionNum = rs.getInt(3);
                String desc = rs.getString(4);
                System.out.printf("%s %s %d \"%s\"\n", courseNum, term, sectionNum, desc);
            }
        }
    }

    // select a class to work with
    private void selectClass(List<String> a) throws CmdException, SQLException {
        need(a, 3, "select-class <course> <term> <section>");
        String sql = "SELECT class_id, class_desc FROM class WHERE course_num = ? AND term = ? AND section_num = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.get(0));
            ps.setString(2, a.get(1));
            ps.setInt(3, parseInt(a.get(2), "section"));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new CmdException("class not found");
            classId = rs.getInt(1);
            classLabel = a.get(0) + " " + a.get(1) + " section " + a.get(2);
            System.out.println("selected class " + classLabel);
        }
    }

    // show the currently selected class
    private void showClass(List<String> a) throws CmdException, SQLException {
        need(a, 0, "show-class");
        if (classId == -1) throw new CmdException("no class selected");
        String sql = "SELECT course_num, term, section_num, class_desc FROM class WHERE class_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new CmdException("class not found");
            String courseNum = rs.getString(1);
            String term = rs.getString(2);
            int sectionNum = rs.getInt(3);
            String desc = rs.getString(4);
            System.out.printf("%s %s %d \"%s\"\n", courseNum, term, sectionNum, desc);
        }
    }

    // add a new category to the currently selected class
    private void addCategory(List<String> a) throws CmdException, SQLException {
        need(a, 2, "add-category <name> <weight>");
        if (classId == -1) throw new CmdException("no class selected");
        String name = a.get(0);
        BigDecimal weight = parseBigDecimal(a.get(1), "weight");
        String sql = "INSERT INTO category (class_id, category_name, weight) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setString(2, name);
            ps.setBigDecimal(3, weight);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "category " + name + " already exists for this class");
        }
        System.out.println("added category " + name + " with weight " + weight);
    }

    // add an assignment to the currently selected class
    private void addAssignment(List<String> a) throws CmdException, SQLException {
        need(a, 3, "add-assignment <name> <category> <points>");
        if (classId == -1) throw new CmdException("no class selected");
        String name = a.get(0);
        String category = a.get(1);
        BigDecimal points = parseBigDecimal(a.get(2), "points");
        String sql = "INSERT INTO assignment (class_id, assignment_name, category_name, points) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setString(2, name);
            ps.setString(3, category);
            ps.setBigDecimal(4, points);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "assignment " + name + " already exists for this class");
        }
        System.out.println("added assignment " + name + " in category " + category + " with points " + points);
    }

    // add a student to the current class
    private void addStudent(List<String> a) throws CmdException, SQLException {
        need(a, 2, "add-student <first_name> <last_name>");
        if (classId == -1) throw new CmdException("no class selected");
        String firstName = a.get(0);
        String lastName = a.get(1);
        String sql = "INSERT INTO student (class_id, first_name, last_name) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, classId);
            ps.setString(2, firstName);
            ps.setString(3, lastName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dupOr(e, "student " + firstName + " " + lastName + " already exists for this class");
        }
        System.out.println("added student " + firstName + " " + lastName);
    }

    // helper function to show all user commands
    private void help(List<String> a) throws CmdException {
        need(a, 0, "help");
        System.out.println("Available commands:");
        System.out.println("  new-class <course> <term> <section> \"<description>\"");
        System.out.println("  list-classes");
        System.out.println("  select-class <course> <term> <section>");
        System.out.println("  show-class");
        System.out.println("  add-category <name> <weight>");
        System.out.println("  add-assignment <name> <category> <points>");
        System.out.println("  add-student <first_name> <last_name>");
        System.out.println("  help");
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

    // helper function to get the first line of a string, used for error messages
    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i == -1 ? s : s.substring(0, i);
    }

    // helper function to handle duplicate key SQL exceptions, throwing a CmdException with a custom message if the error code matches
    private static CmdException dupOr(SQLException e, String dupMessage) throws SQLException {
        if (e.getErrorCode() == MYSQL_DUPLICATE_KEY) return new CmdException(dupMessage);
        throw e;
    }
}
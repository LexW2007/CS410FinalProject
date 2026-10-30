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
    TODO: REVISE AND TEST
*/
class GradebookApp {

    static class CmdException extends Exception {
        public CmdException(String message){
            super(message);
        }
    }

    private final Connection conn;
    private int classId = -1;          // -1: no class selected
    private String classLabel = null;  // ex: "CS410 Fa26 1"

    GradebookApp(Connection conn) { this.conn = conn; }
    
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:56822/gradebook";
        String username = "msandbox";
        String password = "lexwatts";
        String query = "select * from student"; // Query to be run

        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection c = DriverManager.getConnection(url, username, password)) {
            System.out.println("Connected. Type 'help' for commands.");
            new GradebookApp(c).run();
        } catch (SQLException e) {
            System.err.println("error: could not connect to database: " + firstLine(e.getMessage()));
            System.exit(1);
        }
    }

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null ? dflt : v;
    }

    void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            String line = in.readLine();
            if (line == null) break;
            try {
                if (!processCommand(line)) break;
            } catch (CmdException e) {
                System.err.println("error: " + e.getMessage());
            } catch (SQLException e) {
                System.err.println("error: " + firstLine(e.getMessage()));
            }
        }
        System.out.println("Bye");
    }

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
        System.out.println("  exit");
    }
}
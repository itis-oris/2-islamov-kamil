package db;

import model.GameState;

import java.sql.*;

/**
 * DatabaseManager для PostgreSQL.
 * Параметры подключения (URL / USER / PASS) можно изменить под свою БД.
 *
 * NOTE: перед запуском убедись, что PostgreSQL запущен и БД/пользователь доступны.
 */
public class DatabaseManager {
    private static final String URL = "jdbc:postgresql://localhost:5434/clashgame";
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private static DatabaseManager instance;

    private DatabaseManager() {
        try {
            Class.forName("org.postgresql.Driver");
            initSchema();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) instance = new DatabaseManager();
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private void initSchema() {
        String createMatches =
                "CREATE TABLE IF NOT EXISTS matches (" +
                        "id SERIAL PRIMARY KEY, " +
                        "player1_damage INTEGER NOT NULL, " +
                        "player2_damage INTEGER NOT NULL, " +
                        "result VARCHAR(16) NOT NULL, " +
                        "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ");";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createMatches);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveResult(GameState state) {
        String sql = "INSERT INTO matches(player1_damage, player2_damage, result) VALUES(?,?,?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, state.players[0].towerDamage);
            ps.setInt(2, state.players[1].towerDamage);
            String res = state.players[0].towerDamage > state.players[1].towerDamage ? "P1" :
                    (state.players[1].towerDamage > state.players[0].towerDamage ? "P2" : "DRAW");
            ps.setString(3, res);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

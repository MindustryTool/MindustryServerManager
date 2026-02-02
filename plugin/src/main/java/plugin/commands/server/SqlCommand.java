package plugin.commands.server;

import arc.util.Log;
import plugin.commands.PluginCommand;
import plugin.database.DB;

public class SqlCommand extends PluginCommand {
    private Param scriptParam;

    public SqlCommand() {
        setName("sql");
        setDescription("Run SQL script");
        scriptParam = variadic("script");
    }

    @Override
    public void handleServer() {
        try (var conn = DB.getConnection(); var statement = conn.prepareStatement(scriptParam.asString())) {

            boolean hasResultSet = statement.execute();

            if (hasResultSet) {
                try (var result = statement.getResultSet()) {
                    var metaData = result.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    while (result.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= columnCount; i++) {
                            row.append(result.getString(i)).append(" | ");
                        }
                        Log.info(row.toString());
                    }
                }
            } else {
                int updateCount = statement.getUpdateCount();
                Log.info("Query OK, " + updateCount + " rows affected.");
            }
        } catch (Exception e) {
            Log.err("SQL Error: " + e.getMessage());
        }
    }
}

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
        DB.prepare(scriptParam.asString(), statement -> {
            boolean hasResultSet = statement.execute();

            if (hasResultSet) {
                try (var result = statement.getResultSet()) {
                    var metaData = result.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    StringBuilder header = new StringBuilder("[sky]");
                    for (int i = 1; i <= columnCount; i++) {
                        header.append(metaData.getColumnName(i)).append(" | ");
                    }

                    Log.info(header.toString());

                    while (result.next()) {
                        StringBuilder row = new StringBuilder("[sky]");
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
        });
    }
}

package com.mcmiddleearth.commonervote.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Where the vote database is and how to log in, read from the {@code database} section of config.yml.
 * <p>
 * The keys are 1.2.9's ({@code ip}, {@code port}, {@code dbName}, {@code user}, {@code password}), so an existing
 * config.yml keeps working. A missing section or key gives 1.2.9's default for it.
 */
public record DatabaseConfig(String host, int port, String database, String user, String password) {

    public static DatabaseConfig from(ConfigurationSection section) {
        ConfigurationSection s = section == null ? new MemoryConfiguration() : section;
        return new DatabaseConfig(
                s.getString("ip", "localhost"),
                s.getInt("port", 3306),
                s.getString("dbName", "development"),
                s.getString("user", "development"),
                s.getString("password", "development"));
    }

    /**
     * The JDBC URL for MySQL Connector/J. The timeouts keep an unreachable database from hanging a task for long:
     * five seconds to connect and ten per query.
     */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&connectTimeout=5000&socketTimeout=10000";
    }

    /** Without the password, so a log line or an exception message that prints the config never leaks it. */
    @Override
    public String toString() {
        return "DatabaseConfig[host=" + host + ", port=" + port + ", database=" + database + ", user=" + user
                + ", password=***]";
    }
}

package com.hexvane.eterniamod.runtime;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/** Small bounded-time JDBC adapter; the domain store owns transaction lifetimes. */
final class DriverDataSource implements DataSource {
    private final String url; private final Properties properties=new Properties();
    DriverDataSource(RuntimeConfig config) {
        url=config.databaseUrl();
        properties.setProperty("user",config.databaseUser()); properties.setProperty("password",config.databasePassword());
        properties.setProperty("connectTimeout","5"); properties.setProperty("socketTimeout","15");
        properties.setProperty("ApplicationName","Eternia");
    }
    public Connection getConnection() throws SQLException { return DriverManager.getConnection(url,properties); }
    public Connection getConnection(String user,String password) throws SQLException { Properties p=new Properties();p.putAll(properties);p.setProperty("user",user);p.setProperty("password",password);return DriverManager.getConnection(url,p); }
    public PrintWriter getLogWriter() { return null; }
    public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
    public void setLoginTimeout(int seconds) { properties.setProperty("connectTimeout",String.valueOf(seconds)); }
    public int getLoginTimeout() { return Integer.parseInt(properties.getProperty("connectTimeout")); }
    public Logger getParentLogger() { return Logger.getLogger("com.hexvane.eterniamod.persistence"); }
    public <T>T unwrap(Class<T> type) throws SQLException { if(type.isInstance(this))return type.cast(this);throw new SQLException("Not a wrapper for "+type); }
    public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
}

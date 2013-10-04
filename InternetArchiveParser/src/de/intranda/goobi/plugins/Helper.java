package de.intranda.goobi.plugins;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

public class Helper {

    private Connection con;
    private String DB_URL;
    private String DB_USERNAME;
    private String DB_PASSWORD;
    private final String PROPERTYTABLE = "prozesseeigenschaften";
    private final String IDCOLUMN = "prozesseID";
    private final String TITLECOLUMN = "Titel";
    private final String VALUECOLUMN = "Wert";
    private final String PROCESSID;

    private static PropertiesConfiguration cfg;

    public Helper(String configFile, String processId) {
        try {
            cfg = new PropertiesConfiguration(configFile);
            this.DB_URL = cfg.getString("databaseURL");
            this.DB_USERNAME = cfg.getString("dbUsername");
            this.DB_PASSWORD = cfg.getString("dbPassword");
        } catch (ConfigurationException e) {
            this.DB_URL = "jdbc:mysql://localhost/goobi";
            this.DB_USERNAME = "root";
            this.DB_PASSWORD = "goobi";
        }
        this.PROCESSID = processId;
        try {
            Class.forName("com.mysql.jdbc.Driver");
            this.con = DriverManager.getConnection(this.DB_URL, this.DB_USERNAME, this.DB_PASSWORD);
        } catch (ClassNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }
    }

    public String getFilename() {
        return cfg.getString("itemlist", "/opt/digiverso/other/wellcome/ia/itemlist.txt");
    }
    
    public String getDownloadFolder() {
        return cfg.getString("downloadfolder", "/opt/digiverso/other/wellcome/ia/");
    }

    public List<String> getProperties() {
        List<String> answer = new ArrayList<String>();
        try {

            PreparedStatement sQuery1 =
                    this.con.prepareStatement("SELECT " + this.VALUECOLUMN + " FROM " + this.PROPERTYTABLE + " WHERE " + this.IDCOLUMN + " = ?  AND "
                            + TITLECOLUMN + " like 'ISSUEID%' ORDER BY " + VALUECOLUMN + ";");
            sQuery1.setString(1, this.PROCESSID);
            ResultSet resultS = sQuery1.executeQuery();
            while (!resultS.isLast()) {
                resultS.next();
                String value = resultS.getString(this.VALUECOLUMN);
                answer.add(value);
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            System.exit(-1);
        }
        return answer;
    }

    public static void main(String[] args) throws ConfigurationException, ClassNotFoundException, SQLException {
        Helper h = new Helper("/opt/digiverso/goobi/scripts/internetarchive.properties", "38259");
        System.out.println(h.getProperties());
    }

   
}

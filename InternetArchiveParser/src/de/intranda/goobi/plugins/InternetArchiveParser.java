package de.intranda.goobi.plugins;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;

public class InternetArchiveParser {
    private static final Logger logger = Logger.getLogger(InternetArchiveParser.class);

    public static void main(String[] args) {
        Options options = generateOptions();
        CommandLineParser parser = new PosixParser();
        try {
            CommandLine cl = parser.parse(options, args);

            if (cl.hasOption("h")) {
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                return;
            }

            if (!cl.hasOption("i") || cl.getOptionValue("i").equals("")) {
                System.err.println("Process id is missing.");
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                return;
            }

            if (!cl.hasOption("r") || cl.getOptionValue("r").equals("")) {
                System.err.println("Ruleset is missing.");
                HelpFormatter hf = new HelpFormatter();
                hf.setWidth(79);
                hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
                return;
            }

            String config = "/opt/digiverso/goobi/scripts/internetarchive.properties";
            if (cl.hasOption("c") && !cl.getOptionValue("c").equals("")) {
                config = cl.getOptionValue("cf");
            }

            String processid = cl.getOptionValue("i");

            String rulesetname = cl.getOptionValue("r");

            if (!writeIdentifier(config, processid)) {
                System.err.println("Could not write identifier list.");
                return;
            }

        } catch (ParseException e) {
            logger.error(e);
            System.err.println(e.getMessage());
            HelpFormatter hf = new HelpFormatter();
            hf.setWidth(79);
            hf.printHelp("java -jar InternetArchiveParser.jar [options]", options);
            return;
        }

    }

    private static boolean writeIdentifier(String config, String processid) {
        Helper h = new Helper(config, processid);
        List<String> values = h.getProperties();

        if (values != null && !values.isEmpty()) {
            String filename = h.getFilename();
            FileWriter fw = null;
            BufferedWriter out = null;
            try {
                fw = new FileWriter(filename);
                out = new BufferedWriter(fw);
                for (String value : values) {
                    out.write(value);
                    out.newLine();
                }

            } catch (IOException e) {
                logger.error(e);
                return false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        logger.error(e);
                        return false;
                    }
                }
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        logger.error(e);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Options generateOptions() {
        Options options = new Options();

        Option id = new Option("i", "id", true, "process id");
        id.setArgs(1);
        id.setArgName("id");
        id.setRequired(true);
        options.addOption(id);

        Option ruleset = new Option("r", "ruleset", true, "ruleset file");
        ruleset.setArgs(1);
        ruleset.setArgName("ruleset");
        ruleset.setRequired(true);
        options.addOption(ruleset);

        Option properties = new Option("c", "config", false, "configuration file");
        properties.setArgs(1);
        properties.setArgName("config");
        properties.setRequired(false);
        options.addOption(properties);

        options.addOption(new Option("h", "help", false, "help"));

        return options;
    }
}

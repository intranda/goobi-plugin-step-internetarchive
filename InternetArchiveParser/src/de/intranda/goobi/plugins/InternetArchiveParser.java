package de.intranda.goobi.plugins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Scanner;

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

            String config = "/opt/digiverso/goobi/scripts/internetarchive.properties";
            if (cl.hasOption("c") && !cl.getOptionValue("c").equals("")) {
                config = cl.getOptionValue("cf");
            }
            
            String processid = cl.getOptionValue("i");
            Helper h = new Helper(config, processid);

            if (!writeIdentifier(h)) {
                System.err.println("Can not write identifier list.");
                return;
            }
            
            if (!download(h)) {
                System.err.println("Can not download data.");
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
    
    private static boolean download(Helper helper) {
        InputStream is = null;
        InputStream es = null;
        OutputStream out = null;

        String itemlist = helper.getFilename();
        String downloadFolder = helper.getDownloadFolder();
        
        ProcessBuilder pb = new ProcessBuilder("wget", "-r", "-H", "-nc", "-np", "-nH", "--cut-dirs=2",
                "-e",  "robots=off", "-l1",  "-i", itemlist);
        pb.directory(new File(downloadFolder));
        
        
//        String command = "wget -r -H -nc -np -nH --cut-dirs=2 -e robots=off -l1 -i " + itemlist + " -P " + downloadFolder + " -B 'http://archive.org/download/'";        
        try {
//            logger.debug("execute Shellcommand callShell2: " + command);
            boolean errorsExist = false;
//            if (command == null || command.length() == 0) {
//                return false;
//            }
            
//            Process process = Runtime.getRuntime().exec(command);
            Process process = pb.start();
            is = process.getInputStream();
            es = process.getErrorStream();
            out = process.getOutputStream();
            Scanner scanner = new Scanner(is);
            while (scanner.hasNextLine()) {
                String myLine = scanner.nextLine();
                System.out.println(myLine);
            }

            scanner.close();
            scanner = new Scanner(es);
            while (scanner.hasNextLine()) {
                errorsExist = true;
                System.err.println(scanner.nextLine());
            }
            scanner.close();
            int rueckgabe = process.waitFor();
            if (errorsExist) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            logger.error(e);
            return false;
        } catch (InterruptedException e) {
            logger.error(e);
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    is = null;
                }
            }
            if (es != null) {
                try {
                    es.close();
                } catch (IOException e) {
                    es = null;
                }

            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    out = null;
                }
            }
        }
    }

    private static boolean writeIdentifier(Helper helper) {
        
        List<String> values = helper.getProperties();

        if (values != null && !values.isEmpty()) {
            String filename = helper.getFilename();
            FileWriter fw = null;
            BufferedWriter out = null;
            try {
                fw = new FileWriter(filename);
                out = new BufferedWriter(fw);
                for (String value : values) {
                    out.write("http://archive.org/download/" + value);
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

        Option properties = new Option("c", "config", false, "configuration file");
        properties.setArgs(1);
        properties.setArgName("config");
        properties.setRequired(false);
        options.addOption(properties);

        options.addOption(new Option("h", "help", false, "help"));

        return options;
    }
}
